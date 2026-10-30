"""Zoth Guide turn API: POST /v1/guide/turn (glasses frame + speech -> one short spoken line).

Two free modes, both configured under `guide:` in config.yaml:

* hermes    - frame + transcript + mission go to Neal's own Hermes Agent through its local
              OpenAI-compatible API server (http://127.0.0.1:8642/v1, key API_SERVER_KEY).
              If Hermes is down, falls back to Nous Portal directly (key NOUS_API_KEY, kept only
              on the laptop in secrets.env).
* local_agy - a small local vision model (Ollama, default `moondream`) captions the frame into a
              rolling visual transcript (only when the scene changed; the phone already diffs
              frames). Then AGY (`agy -p`) reads transcript + mission + speech and suggests what
              to do next. Bounded: one caption + one AGY call in flight, CPU threads capped,
              captioning paused while the CPU package is hot.

Privacy: frames are kept in memory only for the one request; captions/turns live in memory per
session (bounded, expire after `session_ttl`). Nothing is written to disk; audit logs sizes only.
"""
from __future__ import annotations

import asyncio
import base64
import glob
import os
import re
import shutil
import time
from collections import deque
from dataclasses import dataclass, field

import httpx

from .ask import AskError, AskTarget, stream_reply
from .context import FocusTracker, TurnContext

SILENT = "SILENT"
GUIDE_SYSTEM = (
    "You are Zoth, a mission coach speaking into Neal's ear through smart glasses. "
    "You get a camera frame of what he sees, what he just said (may be empty) and his mission. "
    "Reply with ONE short spoken sentence (max 25 words): a concrete next step or a direct answer. "
    "No markdown, no lists, no preamble. Never identify people by face. "
    f"If he said nothing and there is nothing new or useful to say, reply exactly {SILENT}."
)
AGY_PROMPT = (
    "You are Zoth, a mission coach speaking into Neal's ear through smart glasses. Do not use tools, "
    "do not edit files; just answer. Below is a rolling transcript of what his glasses camera saw "
    "(captions from a small local vision model, oldest first, may contain errors), his mission, "
    "what he just said, and what you suggested recently.\n"
    "Reply with ONE short spoken sentence (max 25 words) suggesting what he could do next, or "
    "answering him. No markdown. Don't repeat a recent suggestion. "
    f"If nothing is worth saying, reply exactly {SILENT}.\n\n"
    "{extra}MISSION: {mission}\n\nWHAT THE GLASSES SAW:\n{seen}\n\nNEAL JUST SAID: {speech}\n\n"
    "YOUR RECENT SUGGESTIONS:\n{recent}\n"
)
CAPTION_PROMPT = "Describe this image in one short sentence."
# Stateless, deterministic, short: Moondream degenerates into "ids: [0.3, 0.3, ...]" detection-style
# output with long/"objects"-style prompts; repeat_penalty + small num_predict keep it a caption.
CAPTION_OPTIONS = {"num_predict": 40, "temperature": 0.0, "top_k": 20, "repeat_penalty": 1.3}


def sanitize_caption(text: str) -> str:
    """Returns a clean one-sentence caption, or "" for degenerate output (arrays, coordinates, id lists, loops)."""
    t = re.sub(r"\s+", " ", (text or "")).strip()
    t = re.sub(r"^(ids?|objects?|labels?)( of [a-z ]+)?\s*[:=-]\s*", "", t, flags=re.I)
    t = re.sub(r"\[[^\]]*\]|\([\d.,\s]+\)|\{[^}]*\}", "", t)          # [..] (0.1, 0.2) {..}
    t = re.sub(r"(?:\b\d+(?:\.\d+)?\s*,\s*){2,}\d*(?:\.\d+)?", "", t)     # 0, 1, 2, 3 / 0.3, 0.69
    t = re.sub(r"^(ids?|uth|!)\b\W*", "", t, flags=re.I).strip(" ,;:-!\"'")
    t = re.sub(r"^(and|of|with)\s+", "", t, flags=re.I)
    t = re.sub(r"\s+([.,!?])", r"\1", t)
    if t.count('"') % 2:
        t = t.replace('"', "")
    words = re.findall(r"[A-Za-z']+", t)
    if len(words) < 3:
        return ""
    digits = sum(c.isdigit() for c in t)
    if digits > max(4, len(t) * 0.15):
        return ""
    low = [w.lower() for w in words]
    if max(low.count(w) for w in set(low)) > max(3, len(low) // 3):      # "the the the" loops
        return ""
    t = re.split(r"(?<=[.!?])\s", t)[0][:220].strip()
    return t[0].upper() + t[1:] if t else ""


@dataclass
class LocalVision:
    base_url: str = "http://127.0.0.1:11434"
    model: str = "moondream"
    num_thread: int = 4            # of 12 logical CPUs on zoth-pill
    keep_alive: str = "10m"
    timeout: int = 40
    hot_c: float = 88.0            # pause captioning above this CPU package temp
    min_interval: float = 3.0      # never caption more often than this per session


@dataclass
class AgyConfig:
    command: str = "agy"
    model: str = ""
    effort: str = "low"
    timeout: int = 30
    min_interval: float = 12.0     # unprompted suggestions at most this often (speech bypasses)
    workdir: str = "~/.local/state/zoth-relay-mobile/agy-ws"


@dataclass
class GuideConfig:
    hermes: AskTarget | None = None
    fallback: AskTarget | None = None
    vision: LocalVision = field(default_factory=LocalVision)
    agy: AgyConfig = field(default_factory=AgyConfig)
    max_tokens: int = 80
    transcript_len: int = 12
    session_ttl: int = 1800
    tts: object = None      # tts.TtsConfig (streaming mode)
    stream: object = None   # guide_stream.StreamConfig


def _target(name: str, raw: dict | None, defaults: dict) -> AskTarget | None:
    if raw is None:
        return None
    t = {**defaults, **(raw or {})}
    return AskTarget(name=name, base_url=str(t["base_url"]).rstrip("/"), model=str(t["model"]),
                     api_key_env=str(t.get("api_key_env") or ""), vision=bool(t.get("vision", True)),
                     max_tokens=int(t.get("max_tokens", 80)), timeout=int(t.get("timeout", 45)),
                     label=str(t.get("label") or name), extra=dict(t.get("extra") or {}))


def _parse_tts(r):
    from .tts import parse_tts
    return parse_tts(r)


def _parse_stream(r):
    from .guide_stream import parse_stream
    return parse_stream(r)


def parse_guide(raw: dict | None) -> GuideConfig | None:
    if not raw:
        return None
    lv = raw.get("local_vision") or {}
    ag = raw.get("agy") or {}
    return GuideConfig(
        hermes=_target("hermes", raw.get("hermes", {}), {
            "base_url": "http://127.0.0.1:8642/v1", "model": "hermes-agent",
            "api_key_env": "API_SERVER_KEY", "timeout": 45}),
        fallback=_target("nous", raw.get("fallback"), {
            "base_url": "https://inference-api.nousresearch.com/v1", "model": "stepfun/step-3.7-flash:free",
            "api_key_env": "NOUS_API_KEY", "timeout": 30, "vision": False}),
        vision=LocalVision(**{k: v for k, v in lv.items() if k in LocalVision.__dataclass_fields__}),
        agy=AgyConfig(**{k: v for k, v in ag.items() if k in AgyConfig.__dataclass_fields__}),
        max_tokens=int(raw.get("max_tokens", 80)),
        transcript_len=int(raw.get("transcript_len", 12)),
        session_ttl=int(raw.get("session_ttl", 1800)),
        tts=_parse_tts(raw.get("tts")), stream=_parse_stream(raw.get("stream")))


def clean_reply(text: str) -> tuple[str, bool]:
    """-> (spoken text, speak?). Strips markdown, caps to ~2 sentences / 220 chars."""
    t = re.sub(r"[*_`#>]+", "", (text or "")).strip().strip('"').strip()
    if not t or t.upper().rstrip(".!") == SILENT or t.upper().startswith(SILENT):
        return "", False
    parts = re.split(r"(?<=[.!?])\s+", t)
    t = " ".join(parts[:2])
    if len(t) > 220:
        t = t[:220].rsplit(" ", 1)[0] + "…"
    return t, True


def similar(a: str, b: str) -> bool:
    wa, wb = set(re.findall(r"[a-z0-9]+", a.lower())), set(re.findall(r"[a-z0-9]+", b.lower()))
    if not wa or not wb:
        return False
    return len(wa & wb) / len(wa | wb) >= 0.7


def cpu_temp_c() -> float | None:
    """CPU package temperature from hwmon coretemp (Linux), else max thermal zone."""
    best = None
    for d in glob.glob("/sys/class/hwmon/hwmon*"):
        try:
            if open(f"{d}/name").read().strip() in ("coretemp", "k10temp", "zenpower"):
                return int(open(f"{d}/temp1_input").read()) / 1000.0
        except OSError:
            continue
    for z in glob.glob("/sys/class/thermal/thermal_zone*/temp"):
        try:
            v = int(open(z).read()) / 1000.0
            best = v if best is None or v > best else best
        except (OSError, ValueError):
            continue
    return best


@dataclass
class Session:
    seen: deque = field(default_factory=lambda: deque(maxlen=12))       # (ts, caption)
    turns: deque = field(default_factory=lambda: deque(maxlen=8))       # (role, text)
    said: deque = field(default_factory=lambda: deque(maxlen=5))
    last_caption: float = 0.0
    last_agy: float = 0.0
    last_used: float = field(default_factory=time.monotonic)
    caption_lock: asyncio.Lock = field(default_factory=asyncio.Lock)
    think_lock: asyncio.Lock = field(default_factory=asyncio.Lock)
    guide_on: bool = True                                                        # muted by "guide mode off"
    focus: FocusTracker = field(default_factory=FocusTracker)                    # what he keeps talking about
    last_focus: str = ""


class Guide:
    def __init__(self, cfg: GuideConfig, transport: httpx.AsyncBaseTransport | None = None,
                 agy_runner=None, temp_reader=cpu_temp_c):
        self.cfg, self.transport, self.temp_reader = cfg, transport, temp_reader
        self.agy_runner = agy_runner or self._run_agy
        self.sessions: dict[str, Session] = {}

    def session(self, key: str) -> Session:
        now = time.monotonic()
        for k in [k for k, s in self.sessions.items() if now - s.last_used > self.cfg.session_ttl]:
            del self.sessions[k]
        s = self.sessions.get(key)
        if s is None:
            if len(self.sessions) >= 32:
                del self.sessions[min(self.sessions, key=lambda k: self.sessions[k].last_used)]
            s = self.sessions[key] = Session(seen=deque(maxlen=self.cfg.transcript_len))
        s.last_used = now
        return s

    def reset(self, key: str):
        self.sessions.pop(key, None)

    # ------------------------------------------------------- wake / focus policy
    def _apply_commands(self, s: Session, ctx: TurnContext) -> bool:
        """Fold an activation command into the session. -> True when the guide is muted this turn.

        Commands only count from the wearer (`TurnContext.honour_command`): another voice in the
        room can say the phrase all day and the guide stays as it is.
        """
        cmd = ctx.command if ctx.honour_command() else ""
        if cmd in ("guide_off", "quiet_on"):
            s.guide_on = False
        elif cmd in ("guide_on", "quiet_off"):
            s.guide_on = True
        return not s.guide_on and not cmd

    def _focus(self, s: Session, ctx: TurnContext, speech: str, captions=()) -> None:
        """Feed what he said and saw into the decaying focus profile, then fill the focus line."""
        ctx.focus = ctx.focus or s.focus.observe(speech, captions).line()
        s.last_focus = ctx.focus

    # ------------------------------------------------------------------ hermes
    async def _complete(self, target: AskTarget, messages: list[dict], session_key: str) -> str:
        extra_headers = {"X-Hermes-Session-Id": f"zoth-guide-{session_key}"} if target.name == "hermes" else {}
        out = []
        async for piece in stream_reply(target, messages, self.cfg.max_tokens, self.transport, extra_headers):
            out.append(piece)
        return "".join(out)

    async def hermes_turn(self, key: str, image: tuple[bytes, str] | None, speech: str, mission: str,
                          ctx: TurnContext | None = None) -> dict:
        s = self.session(key)
        ctx = ctx or TurnContext()
        muted = self._apply_commands(s, ctx)
        self._focus(s, ctx, speech)
        if muted:
            return {"text": "", "speak": False, "muted": True, "via": "mute"}
        if s.think_lock.locked():
            return {"busy": True, "speak": False, "text": ""}
        async with s.think_lock:
            sys_txt = GUIDE_SYSTEM + (f"\nMission: {mission}" if mission else "") + ctx.system_suffix()
            msgs = [{"role": "system", "content": sys_txt}]
            msgs += [{"role": r, "content": c} for r, c in s.turns]
            ask = f'Neal said: "{speech}"' if speech else "(Neal said nothing. Glance at the frame; speak only if useful.)"
            url = f"data:{image[1]};base64," + base64.b64encode(image[0]).decode("ascii") if image else None

            def with_frame(target: AskTarget) -> list[dict]:
                if url and target.vision:
                    return msgs + [{"role": "user", "content": [{"type": "text", "text": ask},
                                                                {"type": "image_url", "image_url": {"url": url}}]}]
                note = " (No camera view available to you this turn.)" if url else ""
                return msgs + [{"role": "user", "content": ask + note}]
            used, err = None, None
            for target in [t for t in (self.cfg.hermes, self.cfg.fallback) if t is not None]:
                if not target.configured:
                    err = err or AskError(503, f"{target.name} has no key on the host ({target.api_key_env})")
                    continue
                try:
                    raw = await self._complete(target, with_frame(target), key)
                    used = target
                    break
                except (AskError, httpx.HTTPError) as e:
                    err = e if isinstance(e, AskError) else AskError(502, f"{target.name} unreachable: {type(e).__name__}")
            if used is None:
                raise err or AskError(503, "no guide target configured")
            text, speak = clean_reply(raw)
            s.turns.append(("user", ask))
            s.turns.append(("assistant", text or SILENT))
            if speak:
                s.said.append(text)
            return {"text": text, "speak": speak, "via": used.name, "model": used.model}

    # --------------------------------------------------------------- local + agy
    async def caption(self, s: Session, image: tuple[bytes, str]) -> dict:
        v = self.cfg.vision
        now = time.monotonic()
        if s.caption_lock.locked() or now - s.last_caption < v.min_interval:
            return {"caption": None, "skipped": "busy"}
        temp = self.temp_reader()
        if temp is not None and temp >= v.hot_c:
            return {"caption": None, "skipped": "hot", "temp_c": round(temp, 1)}
        async with s.caption_lock:
            s.last_caption = now
            body = {"model": v.model, "stream": False, "keep_alive": v.keep_alive,
                    "options": {"num_thread": v.num_thread, **CAPTION_OPTIONS},
                    "messages": [{"role": "user", "content": CAPTION_PROMPT,
                                  "images": [base64.b64encode(image[0]).decode("ascii")]}]}
            try:
                async with httpx.AsyncClient(timeout=v.timeout, transport=self.transport) as c:
                    r = await c.post(f"{v.base_url}/api/chat", json=body)
            except httpx.HTTPError as e:
                raise AskError(502, f"local vision (Ollama) unreachable: {type(e).__name__}")
            if r.status_code == 404:
                raise AskError(503, f"Ollama model '{v.model}' not pulled. On zoth-pill run: ollama pull {v.model}")
            if r.status_code >= 400:
                raise AskError(502, f"Ollama returned {r.status_code}: {r.text[:200]}")
            cap = sanitize_caption((r.json().get("message") or {}).get("content") or "")
            if cap and not (s.seen and similar(cap, s.seen[-1][1])):
                s.seen.append((time.time(), cap))
                return {"caption": cap, "new_scene": True, "temp_c": temp}
            return {"caption": cap, "new_scene": False, "temp_c": temp}

    def agy_prompt(self, s: Session, speech: str, mission: str, extra: str = "") -> str:
        now = time.time()
        seen = "\n".join(f"- {int(now - ts)}s ago: {c}" for ts, c in s.seen) or "- (nothing yet)"
        recent = "\n".join(f"- {x}" for x in s.said) or "- (none)"
        return AGY_PROMPT.format(mission=mission or "(none set)", seen=seen, extra=extra,
                                 speech=speech or "(nothing)", recent=recent)

    async def _run_agy(self, prompt: str) -> str:
        a = self.cfg.agy
        exe = shutil.which(os.path.expanduser(a.command)) or shutil.which(os.path.expanduser("~/.local/bin/agy"))
        if not exe:
            raise AskError(503, "AGY CLI not found on zoth-pill (expected ~/.local/bin/agy)")
        wd = os.path.expanduser(a.workdir)
        os.makedirs(wd, mode=0o700, exist_ok=True)
        args = [exe, "-p", prompt, "--output-format", "text", "--print-timeout", f"{a.timeout}s",
                "--sandbox", "--disable-slash-commands", "--effort", a.effort]
        if a.model:
            args += ["--model", a.model]
        proc = await asyncio.create_subprocess_exec(*args, cwd=wd, stdin=asyncio.subprocess.DEVNULL,
                                                    stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE)
        try:
            out, errb = await asyncio.wait_for(proc.communicate(), a.timeout + 5)
        except asyncio.TimeoutError:
            proc.kill()
            raise AskError(504, "AGY timed out")
        if proc.returncode != 0:
            raise AskError(502, f"AGY exited {proc.returncode}: {errb.decode('utf-8', 'replace')[-200:]}")
        return out.decode("utf-8", "replace")

    async def local_turn(self, key: str, image: tuple[bytes, str] | None, speech: str, mission: str,
                         ctx: TurnContext | None = None) -> dict:
        s = self.session(key)
        ctx = ctx or TurnContext()
        muted = self._apply_commands(s, ctx)
        if muted:
            # Guide mode off: no caption model, no AGY, no spend. The wearer's voice is still
            # transcribed on the phone, so "spotter guide mode activate" still reaches us - and
            # what he says while muted still counts toward what he is focused on.
            self._focus(s, ctx, speech)
            return {"text": "", "speak": False, "muted": True, "via": "mute", "caption": None,
                    "skipped": "muted", "transcript_len": len(s.seen)}
        cap = await self.caption(s, image) if image else {"caption": None, "skipped": "no_frame"}
        self._focus(s, ctx, speech, [cap["caption"]] if cap.get("caption") else ())
        now = time.monotonic()
        woke = bool(ctx.command) and ctx.honour_command()
        due = bool(speech) or woke or (cap.get("new_scene") and now - s.last_agy >= self.cfg.agy.min_interval)
        res = {"text": "", "speak": False, "via": "local_agy", "caption": cap.get("caption"),
               "skipped": cap.get("skipped"), "temp_c": cap.get("temp_c"), "transcript_len": len(s.seen)}
        if not due:
            return res
        if s.think_lock.locked():
            return {**res, "busy": True}
        async with s.think_lock:
            s.last_agy = now
            prompt = self.agy_prompt(s, speech, mission, ctx.agy_block())
            text, speak = clean_reply(await self.agy_runner(prompt))
            if speak:
                s.said.append(text)
            return {**res, "text": text, "speak": speak, "model": "agy"}
