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
from .context import FocusTracker
from .missions import assess_step, jot_command_text
from .providers import Provider, Turn
from .trio import Advisor, Director, Eyes, Preset, custom_preset, get_preset

DEFAULT_HISTORY_BUDGET = 24000      # chars of transcript replayed per turn

# Used when the client sends no system prompt. Replies are spoken aloud into
# glasses, so the style is short, concrete and ear-first.
DEFAULT_VOICE_SYSTEM = (
    "You are FieldKit, a calm hands-free coach speaking into the wearer's ear while "
    "their hands are busy. Reply in one or two short spoken sentences, under 35 words. "
    "No markdown, lists, emoji or headings. Lead with the action. If an image is "
    "attached, ground your answer in what is actually visible and say so briefly; "
    "never invent details you cannot see. Use the mission and current step when given. "
    "If something looks unsafe, say stop first. If unsure, ask one short question. "
    "Never identify people by their face."
)

_SPEAK_BREAK = ".!?;:\n"


def split_speakable(buf: str, first: bool) -> tuple[list[str], str]:
    """Cut a streaming text buffer into chunks a TTS can start on.

    Breaks after sentence punctuation; the first chunk may also break at a
    comma once it has a few words, so the first audio starts sooner.
    Returns (ready chunks, remainder still buffering).
    """
    out: list[str] = []
    start = 0
    for i, ch in enumerate(buf):
        seg = buf[start:i + 1]
        is_break = ch in _SPEAK_BREAK and (i + 1 == len(buf) or buf[i + 1].isspace())
        early = first and not out and ch == "," and len(seg.split()) >= 4
        if (is_break or early) and seg.strip():
            out.append(seg.strip())
            start = i + 1
    return out, buf[start:]

MIN_HISTORY_BUDGET = 500
MAX_HISTORY_BUDGET = 200_000


class FrameStore:
    """Latest changed frame per agent, for MCP see_now. Memory only."""

    def __init__(self):
        self._latest: dict[str, tuple[bytes, str, float]] = {}

    def put(self, agent: str, jpeg: bytes, mime: str) -> None:
        if agent:
            self._latest[agent] = (jpeg, mime, time.time())

    def latest(self, agent: str) -> tuple[bytes, str, float] | None:
        return self._latest.get(agent or "")


FRAMES = FrameStore()
LAST_SESSIONS: dict[str, "Session"] = {}     # agent name -> last closed session (replay)
REPLAY_MAX = 24
EYES_SOURCES = {"phone": "the phone camera", "glasses": "the wearer's Meta glasses (first-person view)",
                "off": ""}
DETECTIONS_TTL = 10.0       # seconds a detection summary / in-view list stays fresh
ACTIVE_SESSIONS: dict[str, "Session"] = {}   # agent name -> live session (for MCP say)


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
                 history_budget: int = DEFAULT_HISTORY_BUDGET, agent_name: str = ""):
        self.provider, self.send, self.transport = provider, send, transport
        self.agent_name = agent_name
        self.ctx = BoundedContext(history_budget)
        self.mission = ""
        self.system = ""
        self.live = None          # per-turn guidance context (speaker/focus/sighting), see set_live
        self.focus = FocusTracker()      # what he is working on, decayed over speech + captions
        self._last_hash: str | None = None
        self._pending_frame: tuple[bytes, str] | None = None
        self._pending_clip: str | None = None
        self.frames_received = 0
        self.frames_changed = 0
        self.frames_sent = 0
        self.turns = 0
        self.gen = 0
        self.metrics: list[dict] = []
        self.replay_log: list[dict] = []   # bounded "what it saw" log (in memory only)
        self.started_at = time.time()
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
        # Coaching silenced by the WEARER's own wake command (honoured() gate only). Distinct
        # from `paused`: paused is the momentary "shh" that his next utterance clears.
        self.muted = False
        self._last_assess_text = ""         # auto step-tracking dedupe/throttle
        self._last_assess_at = 0.0
        self.heading: int | None = None     # last compass heading (degrees)
        self.heading_source = ""            # "phone" | "glasses"
        self.sound_heading: int | None = None   # estimated world bearing of sound
        self.sound_relative: int | None = None  # degrees rel. to device forward
        self.sound_source = ""
        self.sound_level_db: float | None = None
        # Nov 2 (hackathon): live eyes switch + on-device detections + people in view.
        self.eyes_source = "phone"          # "phone" | "glasses" | "off"
        self.eyes_switched_at: float | None = None
        self.eyes_switches = 0
        self.detections: dict | None = None
        self.detections_at = 0.0
        self.in_view: list[str] = []        # names the WEARER saved, matched on-device/relay
        self.in_view_at = 0.0
        self.heard: str = ""                # saved voice that matched the last utterance

    # ------------------------------------------------------------ lifecycle
    def set_hello(self, mission: str, system: str, history_budget=None) -> None:
        self.mission, self.system = mission or "", system or ""
        if history_budget is not None:
            self.ctx = BoundedContext(history_budget)

    def set_mission(self, text: str) -> None:
        self.mission = text or ""

    def set_live(self, ctx=None) -> None:
        """Attach the per-turn guidance context built by the relay (speaker, honoured
        command, focus, person sighting). Replaced each turn; None or an empty ctx
        leaves the provider prompt byte-identical to an older build's.

        An HONOURED guide_off/quiet_on/guide_on by the wearer moves coaching with it, so the
        wake words work by voice alone. A bystander saying them changes nothing: the gate is
        honoured() (wearer, or an old build that never gated the voice), never the raw words.
        Coaching is silenced through `muted`, not `paused`: the wearer's next utterance clears
        paused (he is the one talking), which would undo his own command instantly."""
        self.live = ctx
        if ctx is None:
            return
        hon = ctx.honoured()
        if hon in ("guide_off", "quiet_on"):
            self.muted = True
        elif hon in ("guide_on", "quiet_off"):
            self.muted = False

    def live_suffix(self, speech: str = "") -> str:
        """The context block for the next turn: the speaker line, an honoured command
        hint, what he has been focused on, and any sighting. Empty when nothing applies."""
        ctx = self.live
        if ctx is None:
            return ""
        ctx.focus = ctx.focus or self.focus.observe(speech or "").line()
        return ctx.system_suffix()

    # ------------------------------------------------------------ eyes + detections (Nov 2)
    def set_eyes(self, source: str) -> bool:
        """Switch the AI's vision source mid-session. Context, mission and history stay;
        only the next frames come from somewhere else. Returns True when it changed."""
        src = source if source in EYES_SOURCES else "phone"
        if src == self.eyes_source:
            return False
        prev, self.eyes_source = self.eyes_source, src
        self.eyes_switched_at = time.time()
        self.eyes_switches += 1
        self._last_hash = None              # the new source's first frame always counts
        self._pending_frame = None          # never answer about the old source's view
        self.in_view, self.detections = [], None
        self._log_replay("eyes", source=src, previous=prev)
        return True

    def on_detections(self, m: dict) -> dict:
        """Short on-device detection summary (MediaPipe hands/faces, optional pose/objects).
        Counts and labels only - never pixels or landmarks - so it is cheap and private."""
        def _n(k):
            try:
                return max(0, min(20, int(m.get(k) or 0)))
            except (TypeError, ValueError):
                return 0
        labels = lambda k: [str(x)[:32] for x in (m.get(k) or []) if isinstance(x, str)][:8]
        d = {"hands": _n("hands"), "faces": _n("faces"), "gestures": labels("gestures"),
             "objects": labels("objects"), "pose": bool(m.get("pose")),
             "source": self.eyes_source}
        self.detections, self.detections_at = d, time.time()
        return d

    def set_in_view(self, names: list[str]) -> list[str]:
        """Names now in view; returns the ones that just appeared (to announce once)."""
        names = [n for n in dict.fromkeys(names) if n][:6]
        new = [n for n in names if n not in self.in_view]
        self.in_view, self.in_view_at = names, time.time()
        return new

    def vision_suffix(self) -> str:
        """Eyes source, fresh detections and people in view, for the next turn's system prompt."""
        now, out = time.time(), []
        label = EYES_SOURCES.get(self.eyes_source, "")
        if label:
            line = f"Vision source: {label}."
            if self.eyes_switched_at and now - self.eyes_switched_at < 120:
                line += (" The wearer switched it mid-call; frames now come from here, earlier"
                         " frames came from the other source.")
            out.append(line)
        d = self.detections
        if d and now - self.detections_at < DETECTIONS_TTL:
            bits = []
            if d["hands"]:
                bits.append(f"{d['hands']} hand(s)" + (f" ({', '.join(d['gestures'])})" if d["gestures"] else ""))
            if d["faces"]:
                bits.append(f"{d['faces']} face(s)")
            if d["pose"]:
                bits.append("a person's body pose")
            if d["objects"]:
                bits.append("objects: " + ", ".join(d["objects"]))
            if bits:
                out.append("On-device detections (approximate): " + "; ".join(bits) + ".")
        if self.in_view and now - self.in_view_at < DETECTIONS_TTL:
            out.append("People the wearer saved who appear to be in view (on-device match, may be"
                       " wrong; never assert, never describe faces): " + ", ".join(self.in_view) + ".")
        if self.heard:
            out.append(f"The last voice sounded like {self.heard} (saved voice, may be wrong).")
        return "\n".join(out)

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

    def on_sound(self, heading, relative, source: str = "phone", level_db=None) -> None:
        """Estimated sound bearing (device mics + compass). Estimate, not proof."""
        try:
            self.sound_heading = int(heading) % 360
            self.sound_relative = max(-180, min(180, int(relative)))
        except (TypeError, ValueError):
            return
        self.sound_source = source if source in ("phone", "glasses") else "phone"
        try:
            self.sound_level_db = float(level_db) if level_db is not None else None
        except (TypeError, ValueError):
            self.sound_level_db = None

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

    async def _maybe_advance_mission(self, observation: str) -> bool:
        """Auto step-tracking: the coach knows when a step is done — no Next button.

        Typed assessor (missions.assess_step) judges the Eyes observation
        against the current step. step_done advances the shared mission and
        speaks the next step; unsafe speaks at once. Returns True when this
        tick already spoke authoritatively (Director sits this one out).
        """
        m = self._mission()
        if m is None or not m.current_step_text:
            return False
        now = time.monotonic()
        if observation == self._last_assess_text or now - self._last_assess_at < 4.0:
            return False
        self._last_assess_text, self._last_assess_at = observation, now
        try:
            ev = await assess_step(self.provider, m, observation, self.transport)
        except Exception:
            return False
        m.last_event = ev.type
        if ev.type == "step_done":
            done_n = m.current
            self._mission_store.apply_event(m.id, "advance")
            # Hands-free mirror updates: Jot ticks itself, engram refreshes.
            from . import jot_bridge, recap, second_brain
            await jot_bridge.checkoff(m.id, done_n)
            try:
                second_brain.compile_mission(m)
            except Exception:
                pass
            if m.complete:
                await recap.maybe_recap(m, self.transport)
            nxt = m.current_step_text
            text = ev.say or (f"Next: {nxt}" if nxt else "Mission complete.")
            self.ctx.add("assistant", text)
            await self.send({"type": "say", "text": text, "reason": "step-done"})
            await self.send(self.state())
            return True
        if ev.type == "unsafe":
            text = ev.say or "Stop — that looks unsafe."
            self.ctx.add("assistant", text)
            await self.send({"type": "say", "text": text, "reason": "unsafe"})
            return True
        return False

    async def proactive_tick(self) -> None:
        """One Eyes->Advisor->Director pass over the latest changed frame."""
        if not self.guide_on or self.closed or self.paused or self.muted:
            return
        image, self._pending_frame = self._pending_frame, None
        if image is None:
            return
        obs = await self._eyes.observe(image, self._mission_text())
        if obs is None:
            return
        ev_out = {"type": "observation", "text": obs.text}
        if m_step := (self._mission().current_step_text if self._mission() else ""):
            from .recap import SamHighlighter
            boxes = await SamHighlighter(self.transport).highlight(image, m_step)
            if boxes:
                ev_out["highlight"] = boxes
        await self.send(ev_out)
        self._log_replay("observation", text=obs.text, model=self._eyes.provider.name
                         if getattr(self._eyes, "provider", None) else self.provider.name,
                         model_id=self.provider.model, frame=image,
                         boxes=ev_out.get("highlight") or [])
        if await self._maybe_advance_mission(obs.text):
            return
        suggestions = await self._advisor.advise(obs, self._mission_text())
        dec = await self._director.decide(obs, suggestions, "", self._mission_text(), self.preset)
        self.metrics.append({"kind": "proactive", "speak": dec.speak, "reason": dec.reason})
        self.metrics = self.metrics[-200:]
        if dec.speak:
            self.ctx.add("assistant", dec.text)
            await self.send({"type": "say", "text": dec.text, "reason": dec.reason})

    async def on_user_speech(self, text: str, speaker: str = "wearer",
                             sound_relative=None) -> None:
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
        # Voice focus: explicit bystanders are out. An unlabelled voice arriving
        # from well off-axis (>75 deg) is treated as outside conversation too.
        off_axis = False
        if speaker == "unknown" and sound_relative is not None:
            try:
                off_axis = abs(int(sound_relative)) > 75
            except (TypeError, ValueError):
                off_axis = False
        if (speaker == "other" or off_axis) and not self.allow_outside:
            # Bystander conversation: heard, counted, never answered.
            self.ignored_outside += 1
            await self.send(self.state())
            return
        if self.paused and speaker == "wearer":
            self.set_paused(False)  # wearer talking again resumes coaching
        jot_text = jot_command_text(text)
        if jot_text is not None and speaker == "wearer":
            await self.jot_down(jot_text)
            return
        if self.muted and not (self.live is not None and self.live.honoured()):
            # The wearer turned coaching off by voice. He is still transcribed and Jot still
            # hears him, but the guide spends nothing and says nothing until he turns it back on
            # (the turn carrying that command is answered, so he gets his acknowledgement).
            await self.send(self.state())
            return
        self.start_turn(text, "speech")

    async def jot_down(self, text: str) -> dict:
        """Voice 'jot this down': append a field note to the mission's Jot note."""
        from . import jot_bridge
        m = self._mission()
        step_label = ""
        if m is not None and m.current_step_text:
            step_label = f"Step {m.current}: {m.current_step_text}"
        res = await jot_bridge.append_field_note(m.id if m else "", text, step_label)
        said = "Jotted." if res.get("ok") else "Jot is off or unavailable — note not saved."
        await self.send({"type": "say", "text": said, "reason": "jot"})
        return res

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
            FRAMES.put(self.agent_name, raw, mime)

    def on_clip(self, video_url: str) -> None:
        self._pending_clip = video_url or None

    async def say_direct(self, text: str) -> None:
        """Speak a line into this live session (MCP say)."""
        text = (text or "").strip()[:300]
        if not text or self.closed:
            return
        self.ctx.add("assistant", text)
        await self.send({"type": "say", "text": text, "reason": "agent"})

    async def barge(self) -> None:
        self.gen += 1
        if self._task and not self._task.done():
            self._task.cancel()
        await self.send({"type": "stop", "gen": self.gen})

    # ------------------------------------------------------------ replay
    def _log_replay(self, kind: str, **kw) -> None:
        m = self._mission()
        step = m.current_step_text if m else ""
        self.replay_log.append({"kind": kind, "t": time.time(), "step": step or "", **kw})
        if len(self.replay_log) > REPLAY_MAX:
            self.replay_log = self.replay_log[-REPLAY_MAX:]

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
               "muted": self.muted,
               "ignored_outside": self.ignored_outside,
               "heading": self.heading, "heading_source": self.heading_source or None,
               "sound_heading": self.sound_heading, "sound_relative": self.sound_relative,
               "sound_source": self.sound_source or None, "sound_level_db": self.sound_level_db,
               "eyes": self.eyes_source, "eyes_switches": self.eyes_switches,
               "in_view": self.in_view}
        ms = self.mission_state()
        if ms is not None:
            out["mission"] = ms
        return out

    async def run_turn(self, prompt: str, kind: str = "speech") -> None:
        self._task = asyncio.current_task()
        gen = self.gen
        image, self._pending_frame = self._pending_frame, None
        clip, self._pending_clip = self._pending_clip, None
        sys_prompt = self.system or DEFAULT_VOICE_SYSTEM
        live = self.live_suffix(prompt)
        vis = self.vision_suffix()
        if vis:
            live = f"{live}\n{vis}" if live else vis
        if live:
            sys_prompt = f"{sys_prompt}\n\n{live}" if sys_prompt else live
        turn = Turn(prompt=prompt, system=sys_prompt, mission=self._mission_text(),
                    history=self.ctx.history(), image=image, video_url=clip)
        t0 = time.monotonic()
        full: list[str] = []
        done_ev: dict | None = None
        spk_buf, spk_n, first_speak_ms = "", 0, None
        switched: dict | None = None

        async def speak(text: str) -> None:
            nonlocal spk_n, first_speak_ms
            if gen != self.gen or self.closed or not text:
                return
            if first_speak_ms is None:
                first_speak_ms = int((time.monotonic() - t0) * 1000)
            await self.send({"type": "speak", "text": text, "gen": gen, "seq": spk_n})
            spk_n += 1
        try:
            async for ev in self.provider.stream_events(
                    turn, f"s{uuid.uuid4().hex[:8]}", self.transport):
                if ev.get("type") == "model_switched":
                    # partial primary reply is dropped; fallback re-answers the turn
                    full, spk_buf, switched = [], "", ev
                    self.metrics.append({"kind": "model_switched", "from": ev.get("from"),
                                         "to": ev.get("to"), "reason": ev.get("reason")})
                if ev.get("type") == "delta":
                    full.append(ev.get("text", ""))
                elif ev.get("type") == "done":
                    done_ev = ev
                if ev.get("type") == "done":
                    await speak(spk_buf.strip())  # flush the tail before "done"
                    spk_buf = ""
                if gen == self.gen and not self.closed:
                    await self.send({**ev, "gen": gen})
                if ev.get("type") == "delta":
                    # Stream sentence-sized chunks to the client's TTS as they form.
                    chunks, spk_buf = split_speakable(spk_buf + ev.get("text", ""), spk_n == 0)
                    for c in chunks:
                        await speak(c)
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
            fb = getattr(self.provider, "fallback", None) if switched else None
            self._log_replay("turn", prompt=prompt, text="".join(full),
                             model=done_ev.get("agent") or (fb.name if fb else self.provider.name),
                             model_id=done_ev.get("model") or (fb.model if fb else self.provider.model),
                             switched_from=switched.get("from") if switched else None,
                             frame=image, ms=done_ev.get("ms"))
        self.metrics.append({"kind": kind,
                             "first_ms": done_ev.get("first_ms") if done_ev else None,
                             "first_speak_ms": first_speak_ms,
                             "ms": done_ev.get("ms") if done_ev else int((time.monotonic() - t0) * 1000)})
        self.metrics = self.metrics[-200:]
        if not self.closed:
            await self.send(self.state())
