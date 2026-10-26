"""Always-on, full-duplex "Local vision + AGY" pipeline over one WebSocket: /v1/guide/stream.

phone -> relay (JSON text frames)
  {"type":"hello","mission":"...","tts":"relay"|"phone"}   first message
  {"type":"frame","image":"<b64 jpeg>"}                    scene-changed glasses frames (phone diffs)
  {"type":"speech","text":"...","final":true}               Neal's words (phone SpeechRecognizer)
  {"type":"barge"}                                          Neal started talking: stop audio now
  {"type":"mission","text":"..."} / {"type":"bye"}
relay -> phone
  JSON: ready, caption, say {gen,text,tts}, context {text}, stop_audio {gen}, state, error
  binary: 4-byte big-endian `gen` + PCM16LE mono at `rate` (from the ready message); the phone
          drops audio whose gen is older than the last stop_audio.

Captions (local Ollama vision model, keep_alive indefinite while connected) are batched and
deduped into one persistent AGY conversation (agy_session.AgySession). AGY wraps lines to speak
in [^ ... ^]; markers.MarkerParser streams them sentence-by-sentence to local TTS (tts.Tts) while
AGY is still generating. Text outside markers is stored as context for "tell me more".
"""
from __future__ import annotations

import asyncio
import base64
import re
import struct
import time
from dataclasses import dataclass

import httpx

from .agy_session import AgyError, AgySession
from .ask import AskError, decode_image
from .guide import CAPTION_OPTIONS, CAPTION_PROMPT, GuideConfig, cpu_temp_c, sanitize_caption, similar
from .markers import MarkerParser
from .tts import Tts

PREFIX = """You are Zoth, Neal's take-charge mission coach, speaking into his Ray-Ban Meta glasses.
You LEAD. You decide the next step toward his mission and tell him to do it, without waiting to be asked.
Updates arrive in this one conversation:
  SEEN: caption of what his camera sees (small local vision model; may be wrong or vague)
  NEAL: what he said out loud
  TICK: no new direction for a while -> give the next step now
  MISSION / NOTES: his goal and where you are in it
SPEAKING
- Anything to be spoken MUST be inside [^ ... ^], e.g. [^Walk to the guy in the blue jacket. Say: "Hey, what are you building?"^]
- Spoken = direct imperative commands, max ~20 words, at most two short sentences, the most important first. No markdown.
- Outside the markers (never spoken), keep a terse tracker: STEP <n>: <current step> | DONE: <what he finished> | NEXT: <idea>
COACHING
- Reply to every NEAL and every TICK with a spoken directive or answer. Never answer those with SILENT.
- For SEEN-only updates, speak only if the scene changed meaningfully for the mission; otherwise reply exactly SILENT.
- After you give a step, watch SEEN/NEAL for whether he did it; confirm briefly and move to the next step.
- If he has no mission yet, pick a sensible small one from what you see and say it.
- If NEAL says pause/stop coaching, reply [^Paused.^] and stay SILENT until he speaks again or resumes.
SAFETY (always)
- Never direct him into traffic, roads, heights, water, fire, machinery or anything dangerous; nothing illegal, harmful, harassing or deceptive.
- Never identify people by face; refer to them by clothing/position. Respect "no" from other people.
- Do not use tools, run commands or edit files. Just reply."""

MORE_RE = re.compile(r"\b(tell me more|more detail|explain( that| more| it)?|elaborate|what do you mean)\b", re.I)
PAUSE_RE = re.compile(r"\b(pause|stop coaching|be quiet|quiet mode|shut up|stop talking)\b", re.I)
RESUME_RE = re.compile(r"\b(resume|unpause|start coaching|keep coaching|coach me|back on)\b", re.I)
STEP_RE = re.compile(r"STEP\s*\d*\s*:[^|\n]*", re.I)


@dataclass
class StreamConfig:
    batch_interval: float = 3.0      # min seconds between SEEN-only AGY updates
    caption_interval: float = 1.5    # min seconds between captions
    nudge_after: float = 12.0        # TICK if no spoken directive for this long (coaching on)
    max_batch: int = 4               # captions per AGY update
    roll_turns: int = 14             # start a fresh AGY conversation after this many turns ...
    roll_chars: int = 6000           # ... or this many chars sent (keeps per-turn latency flat)
    idle_unload: int = 600           # unload the vision model this long after the phone disconnects
    mirror: str = "~/.local/state/zoth-relay-mobile/agy-live.log"
    chunk_ms: int = 200              # PCM chunk size sent to the phone


def parse_stream(raw: dict | None) -> StreamConfig:
    raw = raw or {}
    return StreamConfig(**{k: v for k, v in raw.items() if k in StreamConfig.__dataclass_fields__})


def seed_message(mission: str, notes: str) -> str:
    """Identical PREFIX on every (rolled) session so the provider's prompt cache keeps hitting."""
    return (PREFIX + f"\n\nMISSION: {mission or '(none yet: pick one from what you see)'}"
            + (f"\nNOTES (from the previous session): {notes}" if notes else "")
            + "\nReply now with exactly: SILENT")


class StreamHub:
    """Process-wide: one live AGY conversation (+ a pre-warmed spare), one TTS engine, one phone."""

    def __init__(self, gcfg: GuideConfig, scfg: StreamConfig, tts: Tts, agy: AgySession | None = None,
                 transport: httpx.AsyncBaseTransport | None = None, temp_reader=cpu_temp_c, agy_factory=None):
        self.g, self.s, self.tts, self.transport, self.temp_reader = gcfg, scfg, tts, transport, temp_reader
        a = gcfg.agy
        self._n = 0
        self.agy_factory = agy_factory or (lambda label: AgySession(
            a.command, a.workdir, a.model, a.effort, scfg.mirror, turn_timeout=40, first_token_timeout=12,
            resume=False, label=label))
        self.agy = agy or self.new_agy()
        self.greeted = False
        self.spare: AgySession | None = None
        self._spare_task: asyncio.Task | None = None
        self.rolls = 0
        self.conn: "Conn | None" = None
        self._unload_task: asyncio.Task | None = None

    def new_agy(self) -> AgySession:
        self._n += 1
        return self.agy_factory(f"#{self._n}")

    # ---------------------------------------------------------------- rolling sessions
    def needs_roll(self) -> bool:
        return self.agy.turns >= self.s.roll_turns or self.agy.chars_sent >= self.s.roll_chars

    def prewarm(self, mission: str, notes: str):
        """Starts a fresh AGY conversation in the background, seeded with the fixed prefix + notes."""
        if self._spare_task and not self._spare_task.done():
            return
        async def go():
            sp = self.new_agy()
            try:
                async for _ in sp.turn(seed_message(mission, notes)):
                    pass
                self.spare = sp
            except AgyError:
                await sp.stop()
        self._spare_task = asyncio.create_task(go())

    async def swap(self, mission: str, notes: str, wait: bool) -> bool:
        """Switch to the pre-warmed session (instant) or, if [wait], build one now."""
        if self.spare is None and wait:
            self.prewarm(mission, notes)
            try:
                await asyncio.wait_for(asyncio.shield(self._spare_task), 30)
            except (asyncio.TimeoutError, Exception):
                pass
        if self.spare is None:
            return False
        old, self.agy, self.spare = self.agy, self.spare, None
        self.rolls += 1
        asyncio.create_task(old.stop())
        return True

    # ---------------------------------------------------------------- vision / lifecycle
    async def ollama(self, body: dict, path: str = "/api/chat") -> httpx.Response:
        async with httpx.AsyncClient(timeout=self.g.vision.timeout, transport=self.transport) as c:
            return await c.post(f"{self.g.vision.base_url}{path}", json=body)

    async def warm_vision(self, keep_alive):
        # same num_thread as captions, so the first caption doesn't trigger a ~20 s model reload
        try:
            await self.ollama({"model": self.g.vision.model, "keep_alive": keep_alive, "prompt": "",
                               "options": {"num_thread": self.g.vision.num_thread}}, "/api/generate")
        except httpx.HTTPError:
            pass

    async def up(self) -> dict:
        if self._unload_task:
            self._unload_task.cancel()
        await asyncio.gather(self.warm_vision(-1), asyncio.to_thread(self.tts.load), return_exceptions=True)
        err = ""
        try:
            await self.agy.start()
        except AgyError as e:
            err = str(e)
        return self.status() | ({"error": err} if err else {})

    async def down(self) -> dict:
        if self.conn:
            await self.conn.close("relay shutting down guide")
        await self.agy.stop()
        if self.spare:
            await self.spare.stop()
            self.spare = None
        self.greeted = False
        await self.warm_vision(0)
        return self.status()

    def status(self) -> dict:
        t = self.temp_reader()
        lat = [x for x in self.agy.stats if x[1] is not None]
        return {"agy": self.agy.alive, "conversation": self.agy.conversation_id[:8], "agy_turns": self.agy.turns,
                "agy_rolls": self.rolls, "spare_ready": self.spare is not None,
                "agy_first_token_s": [x[1] for x in lat[-5:]],
                "tts": self.tts.cfg.engine if self.tts._impl is not None else ("phone" + (f" ({self.tts.error})" if self.tts.error else "")),
                "vision_model": self.g.vision.model, "phone_connected": self.conn is not None,
                "coaching": (not self.conn.paused) if self.conn else None,
                "cpu_temp_c": round(t, 1) if t is not None else None}

    def schedule_unload(self):
        async def later():
            await asyncio.sleep(self.s.idle_unload)
            if self.conn is None:
                await self.warm_vision(0)
        self._unload_task = asyncio.create_task(later())


class Conn:
    def __init__(self, hub: StreamHub, send_json, send_bytes, mission: str = "", phone_tts: bool = False):
        self.hub, self._send_json, self._send_bytes = hub, send_json, send_bytes
        self.mission = mission
        self.mission_sent = ""
        self.phone_tts = phone_tts or not hub.tts.enabled
        self.gen = 0
        self.obs: list[tuple[float, str]] = []
        self.speech: list[str] = []
        self.last_caption_text = ""
        self.last_caption_at = 0.0
        self.last_turn_at = 0.0
        self.last_context = ""
        self.t0 = time.monotonic()
        self.frame: tuple[bytes, str] | None = None
        self.frame_evt = asyncio.Event()
        self.wake = asyncio.Event()
        self.tts_q: asyncio.Queue = asyncio.Queue()
        self.tasks: list[asyncio.Task] = []
        self.closed = False
        self.turn_active = False
        self.paused = False
        self.last_say_at = 0.0
        self.last_directive_at = time.monotonic()
        self.spoken_log: list[str] = []
        self.heard_log: list[str] = []
        self.step = ""
        self.metrics: list[dict] = []    # per turn: kind, first_say_s (from trigger), total_s

    async def send(self, obj):
        if self.closed:
            return
        try:
            await (self._send_bytes(obj) if isinstance(obj, (bytes, bytearray)) else self._send_json(obj))
        except Exception:
            self.closed = True

    def start(self):
        self.tasks = [asyncio.create_task(f()) for f in (self.vision_loop, self.brain_loop, self.tts_loop)]

    async def close(self, reason: str = ""):
        if self.closed:
            return
        if reason:
            await self.send({"type": "state", "text": reason})
        self.closed = True
        for t in self.tasks:
            t.cancel()

    # ---------------------------------------------------------------- inputs
    def on_frame(self, b64: str):
        try:
            self.frame = decode_image(b64)
        except AskError:
            return
        self.frame_evt.set()

    async def on_barge(self):
        self.gen += 1
        while not self.tts_q.empty():
            self.tts_q.get_nowait()
        await self.send({"type": "stop_audio", "gen": self.gen})

    async def on_speech(self, text: str):
        text = text.strip()
        if not text:
            return
        await self.on_barge()  # his words always win over whatever Zoth was saying
        self.heard_log = (self.heard_log + [text])[-4:]
        if PAUSE_RE.search(text) and len(text.split()) <= 6:
            self.paused = True
            await self.speak("Paused. Say resume when you're ready.", self.gen)
            self.speech.append(f"{text} (coaching PAUSED: stay SILENT unless I talk to you)")
            self.wake.set()
            return
        if self.paused and RESUME_RE.search(text):
            self.paused = False
            self.last_directive_at = 0.0
            self.speech.append(f"{text} (coaching RESUMED: give me the next step now)")
            self.wake.set()
            return
        if MORE_RE.search(text) and self.last_context and len(text.split()) <= 6:
            # instant: speak the stored deeper context (first two sentences), no AGY round trip
            parts = re.split(r"(?<=[.!?])\s+", self.last_context)
            say, self.last_context = " ".join(parts[:2]), " ".join(parts[2:])
            await self.speak(say, self.gen)
            self.speech.append(f"{text} (I already read him your stored context: {say})")
        else:
            self.speech.append(text)
        self.wake.set()

    # ---------------------------------------------------------------- vision
    async def vision_loop(self):
        v = self.hub.g.vision
        while not self.closed:
            await self.frame_evt.wait()
            self.frame_evt.clear()
            wait = self.hub.s.caption_interval - (time.monotonic() - self.last_caption_at)
            if wait > 0:
                await asyncio.sleep(wait)
            img, self.frame = self.frame, None   # always the newest frame; older ones were dropped
            if img is None:
                continue
            temp = self.hub.temp_reader()
            if temp is not None and temp >= v.hot_c:
                await self.send({"type": "state", "text": f"zoth-pill hot ({temp:.0f}°C): captions paused", "temp_c": temp})
                await asyncio.sleep(10)
                continue
            self.last_caption_at = time.monotonic()
            # stateless: one user message, no history, so a degenerate caption can't poison the next
            body = {"model": v.model, "stream": False, "keep_alive": -1,
                    "options": {"num_thread": v.num_thread, **CAPTION_OPTIONS},
                    "messages": [{"role": "user", "content": CAPTION_PROMPT, "images": [base64.b64encode(img[0]).decode()]}]}
            try:
                r = await self.hub.ollama(body)
                if r.status_code >= 400:
                    await self.send({"type": "error", "text": f"vision model error {r.status_code}"
                                     + (f": run `ollama pull {v.model}` on zoth-pill" if r.status_code == 404 else "")})
                    await asyncio.sleep(5)
                    continue
                cap = sanitize_caption((r.json().get("message") or {}).get("content") or "")
            except httpx.HTTPError as e:
                await self.send({"type": "error", "text": f"vision model unreachable: {type(e).__name__}"})
                await asyncio.sleep(5)
                continue
            if cap and not similar(cap, self.last_caption_text):
                self.last_caption_text = cap
                self.obs.append((time.monotonic(), cap))
                self.obs = self.obs[-self.hub.s.max_batch:]
                await self.send({"type": "caption", "text": cap, "temp_c": temp})
                self.wake.set()

    # ---------------------------------------------------------------- brain
    def notes(self) -> str:
        """Short running summary carried into a rolled AGY session (no growth, no history replay)."""
        parts = []
        if self.step:
            parts.append(self.step)
        if self.spoken_log:
            parts.append("Last said: " + " / ".join(self.spoken_log[-3:]))
        if self.heard_log:
            parts.append("Neal said: " + " / ".join(self.heard_log[-3:]))
        if self.last_caption_text:
            parts.append("Last seen: " + self.last_caption_text)
        if self.paused:
            parts.append("Coaching is PAUSED.")
        return " | ".join(parts)[:900]

    def compose(self, tick: bool = False) -> str:
        lines = []
        if self.mission != self.mission_sent:
            lines.append(f"MISSION: {self.mission or '(none set)'}")
            self.mission_sent = self.mission
        now = time.monotonic()
        for ts, c in self.obs:
            lines.append(f"[+{int(ts - self.t0)}s] SEEN: {c}")
        for s in self.speech:
            lines.append(f'[+{int(now - self.t0)}s] NEAL: "{s}"')
        if tick and not self.speech:
            lines.append(f"[+{int(now - self.t0)}s] TICK: {int(now - self.last_directive_at)}s since your last directive. Next step?")
        self.obs, self.speech = [], []
        return "\n".join(lines)

    def due(self) -> str:
        """'' | 'speech' | 'seen' | 'tick'"""
        now = time.monotonic()
        if self.speech:
            return "speech"
        if self.paused:
            return ""
        if self.obs and now - self.last_turn_at >= self.hub.s.batch_interval:
            return "seen"
        if (now - self.last_directive_at >= self.hub.s.nudge_after and now - self.last_say_at >= 4
                and now - self.last_turn_at >= 3):
            return "tick"
        return ""

    async def brain_loop(self):
        hub = self.hub
        if not hub.greeted:
            hub.greeted = True
            await self.run_turn(seed_message(self.mission, ""), "seed")
            self.mission_sent = self.mission
            await self.speak("Zoth is with you. I'll lead.", self.gen)
            self.last_directive_at = time.monotonic() - hub.s.nudge_after + 2  # first directive right away
        while not self.closed:
            try:
                await asyncio.wait_for(self.wake.wait(), 0.5)
            except asyncio.TimeoutError:
                pass
            self.wake.clear()
            kind = self.due()
            if kind:
                await self.run_turn(self.compose(tick=kind == "tick"), kind)

    async def run_turn(self, text: str, kind: str = "seen"):
        hub = self.hub
        if kind != "seed" and hub.needs_roll():
            if not await hub.swap(self.mission, self.notes(), wait=False):
                hub.prewarm(self.mission, self.notes())
        gen = self.gen
        parser = MarkerParser(min_chunk_words=6)
        t0 = self.last_turn_at = time.monotonic()
        first_say = None
        self.turn_active = True
        await self.send({"type": "state", "text": "thinking"})
        try:
            for attempt in (1, 2):
                try:
                    async for delta in hub.agy.turn(text):
                        if await self.handle(parser.feed(delta), gen) and first_say is None:
                            first_say = time.monotonic() - t0
                    if await self.handle(parser.finish(), gen) and first_say is None:
                        first_say = time.monotonic() - t0
                    break
                except AgyError as e:
                    # stuck/slow turn: jump to a fresh session (pre-warmed if possible) and retry once
                    if attempt == 2 or kind == "seed" or not await hub.swap(self.mission, self.notes(), wait=True):
                        await self.send({"type": "error", "text": str(e)})
                        break
            if kind != "seed" and hub.needs_roll():
                hub.prewarm(self.mission, self.notes())
        finally:
            self.turn_active = False
            self.metrics.append({"kind": kind, "first_say_s": round(first_say, 2) if first_say is not None else None,
                                 "total_s": round(time.monotonic() - t0, 2), "session": hub.agy.label})
            self.metrics = self.metrics[-500:]
            ctx = parser.context_text
            if ctx:
                m = STEP_RE.search(ctx)
                if m:
                    self.step = m.group(0).strip()[:200]
                self.last_context = STEP_RE.sub("", ctx).replace("|", " ").strip() or self.last_context
                await self.send({"type": "context", "text": ctx[:2000]})
            if parser.spoken:
                self.last_directive_at = time.monotonic()
                self.spoken_log = (self.spoken_log + [" ".join(parser.spoken)])[-6:]
            await self.send({"type": "state", "text": "paused" if self.paused else "listening"})

    async def handle(self, events, gen: int) -> bool:
        spoke = False
        for kind, text in events:
            if kind == "speak" and gen == self.gen:   # barged-in turns are muted (still kept as context by AGY)
                await self.speak(text, gen)
                spoke = True
        return spoke

    async def speak(self, text: str, gen: int):
        self.last_say_at = time.monotonic()
        await self.send({"type": "say", "gen": gen, "text": text, "tts": "phone" if self.phone_tts else "relay"})
        if not self.phone_tts:
            self.tts_q.put_nowait((gen, text))

    async def tts_loop(self):
        tts = self.hub.tts
        chunk = int(tts.rate * 2 * self.hub.s.chunk_ms / 1000)
        while not self.closed:
            gen, text = await self.tts_q.get()
            if gen != self.gen:
                continue
            try:
                pcm = await asyncio.to_thread(tts.synth, text)
            except Exception as e:
                await self.send({"type": "say", "gen": gen, "text": text, "tts": "phone", "error": f"tts failed: {type(e).__name__}"})
                continue
            head = struct.pack(">i", gen)
            for i in range(0, len(pcm), chunk):
                if gen != self.gen:
                    break
                await self.send(head + pcm[i:i + chunk])
