"""FIELDKIT Sessions API (D2): WS /v1/sessions.

One WebSocket carries a whole coaching session. Frames/audio/events in,
provider events out. Replaces the D1 stub; the AGY guide stream
(/v1/guide/stream) is untouched.

Client -> server (JSON text frames):
  {"type":"hello","provider":"spark","mission":"...","system":"...",
   "history_budget":24000}                                    first message
  {"type":"frame","image":"<b64 jpeg/png/webp>"}              glasses/phone frames
  {"type":"clip","video_url":"<url>"}                         short clip, sent on next turn
  {"type":"speech","text":"...","final":true}                 user words (client STT)
  {"type":"text","text":"..."}                                typed input (PWA, tests)
  {"type":"mission","text":"..."}                             update mission prefix
  {"type":"barge"}                                            stop the current turn
  {"type":"bye"}                                              hang up

Server -> client:
  {"type":"ready","provider":...,"model":...,"history_budget":...}
  provider events verbatim: start / delta / done(first_ms, ms) / error
  {"type":"state", turns, frames_received, frames_changed, frames_sent,
   history_chars, history_budget}                             after each turn
  {"type":"stop","gen":n}                                     barge acknowledged

Design rules (from the 0.5.0 lag post-mortem):
- Frames are diffed server-side (SHA-256): only a changed scene is ever sent
  upstream, and only once — a held frame is consumed by the next turn.
- Context is bounded: cached prefix (system + mission) + a char-capped
  transcript. History never grows turn-over-turn; the provider sees the same
  stable prefix every turn, so prompt caching keeps hitting.
- One turn at a time; new input or barge cancels the active turn (the user's
  words always win, as in guide_stream).
- Per-turn metrics (kind, first_ms, ms) mirror the provider done-event.
"""
from __future__ import annotations

import asyncio
import hashlib
import time
import uuid

import httpx

from .ask import AskError, decode_image
from .providers import Provider, Turn
from .trio import Advisor, Director, Eyes, Preset, custom_preset, get_preset

DEFAULT_HISTORY_BUDGET = 24000      # chars of transcript replayed per turn
MIN_HISTORY_BUDGET = 500
MAX_HISTORY_BUDGET = 200_000


class BoundedContext:
    """Char-capped transcript. Oldest turns drop first; never grows unbounded."""

    def __init__(self, budget: int = DEFAULT_HISTORY_BUDGET):
        self.budget = max(MIN_HISTORY_BUDGET, min(int(budget), MAX_HISTORY_BUDGET))
        self._turns: list[dict] = []
        self.chars = 0

    def add(self, role: str, text: str) -> None:
        text = (text or "")[:4000]
        if not text:
            return
        self._turns.append({"role": role, "content": text})
        self.chars += len(text)
        while self.chars > self.budget and len(self._turns) > 1:
            self.chars -= len(self._turns.pop(0)["content"])

    def history(self) -> list[dict]:
        return list(self._turns)


class Session:
    """One connected client session, routed to one provider."""

    def __init__(self, provider: Provider, send, transport: httpx.AsyncBaseTransport | None = None,
                 history_budget: int = DEFAULT_HISTORY_BUDGET):
        self.provider, self.send, self.transport = provider, send, transport
        self.ctx = BoundedContext(history_budget)
        self.mission = ""
        self.system = ""
        self._last_hash: str | None = None
        self._pending_frame: tuple[bytes, str] | None = None
        self._pending_clip: str | None = None
        self.frames_received = 0
        self.frames_changed = 0
        self.frames_sent = 0
        self.turns = 0
        self.gen = 0
        self.metrics: list[dict] = []
        self._task: asyncio.Task | None = None
        self.closed = False
        # Guide trio (D4): preset desires + proactive Eyes/Advisor/Director.
        self.preset: Preset = get_preset(None)
        self._eyes: Eyes | None = None
        self._advisor: Advisor | None = None
        self._director: Director | None = None
        self._proactive_task: asyncio.Task | None = None
        self.allow_outside = False          # wearer voice-focus: ignore bystanders
        self.ignored_outside = 0
        self.heading: int | None = None     # last compass heading (degrees)
        self.heading_source = ""            # "phone" | "glasses"

    # ------------------------------------------------------------ lifecycle
    def set_hello(self, mission: str, system: str, history_budget=None) -> None:
        self.mission, self.system = mission or "", system or ""
        if history_budget is not None:
            self.ctx = BoundedContext(history_budget)

    def set_mission(self, text: str) -> None:
        self.mission = text or ""

    # ------------------------------------------------------------ mission attach (D3)
    def attach_mission(self, store, mission_id: str) -> None:
        self._mission_store, self._mission_id = store, mission_id

    def _mission(self):
        store = getattr(self, "_mission_store", None)
        return store.get(self._mission_id) if store is not None else None

    def mission_state(self) -> dict | None:
        m = self._mission()
        if m is None:
            return None
        return {"id": m.id, "goal": m.goal, "current": m.current,
                "total": len(m.steps), "step": m.current_step_text, "complete": m.complete}

    def _mission_text(self) -> str:
        m = self._mission()
        if m is None:
            return self.mission
        if m.complete:
            return f"{m.goal} | Mission complete"
        return f"{m.goal} | Current step {m.current}/{len(m.steps)}: {m.current_step_text}"

    # ------------------------------------------------------------ guide trio (D4)
    def set_guide(self, eyes: Eyes, advisor: Advisor, director: Director,
                  preset_name: str | None = None, custom: dict | None = None,
                  allow_outside: bool = False) -> None:
        self._eyes, self._advisor, self._director = eyes, advisor, director
        self.allow_outside = bool(allow_outside)
        self.set_preset(preset_name, custom)

    def set_preset(self, name: str | None, custom: dict | None = None) -> None:
        self.preset = custom_preset(name or "get-it-done", **(custom or {})) if custom else get_preset(name)
        if self._director is not None:
            pass  # preset is read per-decision; nothing else to rewire

    @property
    def guide_on(self) -> bool:
        return self._director is not None

    def set_paused(self, paused: bool) -> None:
        if self._director is not None:
            self._director.paused = paused

    @property
    def paused(self) -> bool:
        return bool(self._director and self._director.paused)

    def on_compass(self, heading, source: str = "phone") -> None:
        try:
            self.heading = int(heading) % 360
        except (TypeError, ValueError):
            return
        self.heading_source = source if source in ("phone", "glasses") else "phone"

    def start_proactive(self, interval: float = 2.5) -> None:
        if self._proactive_task is None and self.guide_on:
            self._proactive_task = asyncio.create_task(self._proactive_loop(interval))

    async def _proactive_loop(self, interval: float) -> None:
        while not self.closed:
            try:
                await self.proactive_tick()
            except asyncio.CancelledError:
                raise
            except Exception:
                pass  # a bad tick must never kill the session loop
            await asyncio.sleep(interval)

    async def proactive_tick(self) -> None:
        """One Eyes->Advisor->Director pass over the latest changed frame."""
        if not self.guide_on or self.closed or self.paused:
            return
        image, self._pending_frame = self._pending_frame, None
        if image is None:
            return
        obs = await self._eyes.observe(image, self._mission_text())
        if obs is None:
            return
        await self.send({"type": "observation", "text": obs.text})
        suggestions = await self._advisor.advise(obs, self._mission_text())
        dec = await self._director.decide(obs, suggestions, "", self._mission_text(), self.preset)
        self.metrics.append({"kind": "proactive", "speak": dec.speak, "reason": dec.reason})
        self.metrics = self.metrics[-200:]
        if dec.speak:
            self.ctx.add("assistant", dec.text)
            await self.send({"type": "say", "text": dec.text, "reason": dec.reason})

    async def on_user_speech(self, text: str, speaker: str = "wearer") -> None:
        """Wearer voice-focus gate, then a normal (Director-aware) turn."""
        text = (text or "").strip()
        if not text:
            return
        low = text.lower()
        if low in ("pause", "shh", "be quiet", "stop coaching"):
            self.set_paused(True)
            await self.send({"type": "stop", "gen": self.gen})
            await self.send(self.state())
            return
        if low in ("resume", "unpause", "keep coaching") and self.paused:
            self.set_paused(False)
            await self.send(self.state())
            return
        if speaker == "other" and not self.allow_outside:
            # Bystander conversation: heard, counted, never answered.
            self.ignored_outside += 1
            await self.send(self.state())
            return
        if self.paused and speaker == "wearer":
            self.set_paused(False)  # wearer talking again resumes coaching
        self.start_turn(text, "speech")

    async def close(self) -> None:
        self.closed = True
        if self._proactive_task and not self._proactive_task.done():
            self._proactive_task.cancel()
        if self._task and not self._task.done():
            self._task.cancel()

    # ------------------------------------------------------------ inputs
    def on_frame(self, b64: str) -> None:
        try:
            raw, mime = decode_image(b64)
        except AskError:
            return  # undecodable frames are dropped, never forwarded
        self.frames_received += 1
        h = hashlib.sha256(raw).hexdigest()
        if h != self._last_hash:
            self._last_hash = h
            self._pending_frame = (raw, mime)
            self.frames_changed += 1

    def on_clip(self, video_url: str) -> None:
        self._pending_clip = video_url or None

    async def barge(self) -> None:
        self.gen += 1
        if self._task and not self._task.done():
            self._task.cancel()
        await self.send({"type": "stop", "gen": self.gen})

    # ------------------------------------------------------------ turns
    def start_turn(self, prompt: str, kind: str = "speech") -> None:
        if self._task and not self._task.done():
            self._task.cancel()
        self._task = asyncio.create_task(self.run_turn(prompt, kind))

    def state(self) -> dict:
        out = {"type": "state", "provider": self.provider.name, "turns": self.turns,
               "frames_received": self.frames_received, "frames_changed": self.frames_changed,
               "frames_sent": self.frames_sent, "history_chars": self.ctx.chars,
               "history_budget": self.ctx.budget,
               "guide": self.guide_on, "preset": self.preset.name, "paused": self.paused,
               "ignored_outside": self.ignored_outside,
               "heading": self.heading, "heading_source": self.heading_source or None}
        ms = self.mission_state()
        if ms is not None:
            out["mission"] = ms
        return out

    async def run_turn(self, prompt: str, kind: str = "speech") -> None:
        self._task = asyncio.current_task()
        gen = self.gen
        image, self._pending_frame = self._pending_frame, None
        clip, self._pending_clip = self._pending_clip, None
        turn = Turn(prompt=prompt, system=self.system, mission=self._mission_text(),
                    history=self.ctx.history(), image=image, video_url=clip)
        t0 = time.monotonic()
        full: list[str] = []
        done_ev: dict | None = None
        try:
            async for ev in self.provider.stream_events(
                    turn, f"s{uuid.uuid4().hex[:8]}", self.transport):
                if ev.get("type") == "delta":
                    full.append(ev.get("text", ""))
                elif ev.get("type") == "done":
                    done_ev = ev
                if gen == self.gen and not self.closed:
                    await self.send(ev)
        except asyncio.CancelledError:
            raise  # barged: partial reply is dropped, never enters the transcript
        except Exception as e:  # provider bug must not kill the socket loop
            await self.send({"type": "error", "status": 502,
                             "detail": f"provider {self.provider.name} failed: {type(e).__name__}"})
        self.turns += 1
        if image is not None:
            self.frames_sent += 1
        self.ctx.add("user", prompt)
        if done_ev is not None and full:
            self.ctx.add("assistant", "".join(full))
        self.metrics.append({"kind": kind,
                             "first_ms": done_ev.get("first_ms") if done_ev else None,
                             "ms": done_ev.get("ms") if done_ev else int((time.monotonic() - t0) * 1000)})
        self.metrics = self.metrics[-200:]
        if not self.closed:
            await self.send(self.state())
