"""FIELDKIT guide trio (D4 core): Eyes -> Advisor -> Director.

Three model roles, one decision:

- Eyes (vision model): turns frames into observation text. It is a set of
  eyes, not a narrator: it reports ONLY what is new or changed, and stays
  silent (None) when the scene is materially the same as its last report.
- Advisor (analyst): reads observations + mission state and proposes
  suggestions, each tagged with a priority (safety / progress / teaching /
  social / info).
- Director (overseer): sees eyes + advisor + the user's words + the active
  preset, and makes the ONLY speak/silent decision. Hard rules are applied
  in code after the model proposes: safety always speaks; the preset's
  chattiness gates the rest; pause mutes everything.

Presets are priority profiles ("desires"): safety-first, get-it-done,
teach-me, social — plus user-made customs via custom_preset().
Voice out (TTS) and voice in (STT / live chat) stay transport concerns of
the session; the trio only decides text.
"""
from __future__ import annotations

import json
import uuid
from dataclasses import dataclass, field

import httpx

from .guide import similar
from .missions import MissionError
from .providers import Provider, Turn

PRIORITIES = ("safety", "progress", "teaching", "social", "info")


@dataclass
class Preset:
    """A desire profile: what the Director optimizes for."""
    name: str
    safety: float = 1.0        # safety suggestions: weight + always-speak
    progress: float = 0.8      # next-step momentum
    teaching: float = 0.4      # explanations, technique
    social: float = 0.3        # people, conversation
    chattiness: float = 0.5    # 0 = near-silent, 1 = talks freely

    def weight(self, priority: str) -> float:
        return {"safety": self.safety, "progress": self.progress,
                "teaching": self.teaching, "social": self.social,
                "info": 0.5}.get(priority, 0.3)


PRESETS: dict[str, Preset] = {
    "safety-first": Preset("safety-first", safety=1.0, progress=0.6, teaching=0.5, social=0.2, chattiness=0.35),
    "get-it-done": Preset("get-it-done", safety=1.0, progress=1.0, teaching=0.15, social=0.1, chattiness=0.55),
    "teach-me": Preset("teach-me", safety=1.0, progress=0.6, teaching=1.0, social=0.3, chattiness=0.8),
    "social": Preset("social", safety=1.0, progress=0.4, teaching=0.3, social=1.0, chattiness=0.85),
}


def get_preset(name: str | None) -> Preset:
    return PRESETS.get((name or "").strip().lower(), PRESETS["get-it-done"])


def custom_preset(base: str = "get-it-done", **overrides) -> Preset:
    """User-made preset: start from a built-in, override any desire 0..1."""
    p = Preset(**{**PRESETS[get_preset(base).name].__dict__})
    p.name = "custom"
    for k, v in overrides.items():
        if k in ("safety", "progress", "teaching", "social", "chattiness"):
            setattr(p, k, max(0.0, min(1.0, float(v))))
    return p


@dataclass
class Observation:
    text: str


@dataclass
class Suggestion:
    text: str
    priority: str = "info"
    say: str = ""


@dataclass
class Decision:
    speak: bool
    text: str = ""
    reason: str = ""


async def _complete(provider: Provider, turn: Turn,
                    transport: httpx.AsyncBaseTransport | None) -> str:
    full: list[str] = []
    async for ev in provider.stream_events(turn, f"t{uuid.uuid4().hex[:8]}", transport):
        if ev.get("type") == "delta":
            full.append(ev.get("text", ""))
        elif ev.get("type") == "error":
            raise MissionError(ev.get("status", 502), ev.get("detail", "provider error"))
    return "".join(full)


def _json_obj(text: str) -> dict:
    lo, hi = text.find("{"), text.rfind("}")
    if 0 <= lo < hi:
        try:
            obj = json.loads(text[lo:hi + 1])
            return obj if isinstance(obj, dict) else {}
        except json.JSONDecodeError:
            return {}
    return {}


class Eyes:
    """Vision -> deduped observation. Same scene twice = silence."""

    def __init__(self, provider: Provider, transport: httpx.AsyncBaseTransport | None = None):
        self.provider, self.transport = provider, transport
        self.last_text = ""

    async def observe(self, image: tuple[bytes, str] | None, mission_text: str) -> Observation | None:
        if image is None:
            return None
        prompt = (
            "You are a set of eyes for a hands-free coach. Describe ONLY what is new, "
            "changed, or newly relevant in this frame"
            + (f", given the last report: \"{self.last_text}\"" if self.last_text else "")
            + ". One or two short factual sentences. No advice, no step talk. "
            "If nothing meaningful changed, reply with exactly: NOCHANGE")
        text = (await _complete(self.provider, Turn(
            prompt=prompt, mission=mission_text, image=image), self.transport)).strip()
        if not text or text.upper().startswith("NOCHANGE"):
            return None
        if self.last_text and similar(text, self.last_text):
            return None
        self.last_text = text
        return Observation(text)


class Advisor:
    """Observations + mission -> prioritized suggestions."""

    def __init__(self, provider: Provider, transport: httpx.AsyncBaseTransport | None = None):
        self.provider, self.transport = provider, transport

    async def advise(self, observation: Observation, mission_text: str,
                     heard: str = "") -> list[Suggestion]:
        prompt = (
            f"Mission state: {mission_text}\nEyes report: {observation.text}\n"
            + (f"User just said: \"{heard}\"\n" if heard else "")
            + "Propose up to 3 suggestions for the coach. Reply with ONLY JSON: "
              '{"suggestions":[{"text":"...","priority":"safety|progress|teaching|social|info",'
              '"say":"one short spoken sentence"}]}')
        obj = _json_obj(await _complete(self.provider, Turn(prompt=prompt), self.transport))
        out: list[Suggestion] = []
        for s in obj.get("suggestions") or []:
            if isinstance(s, dict) and s.get("text"):
                pr = str(s.get("priority") or "info")
                out.append(Suggestion(str(s["text"])[:300],
                                      pr if pr in PRIORITIES else "info",
                                      str(s.get("say") or "")[:200]))
        return out


class Director:
    """Overseer: the only speak/silent decision, preset- and rule-gated."""

    def __init__(self, provider: Provider, transport: httpx.AsyncBaseTransport | None = None):
        self.provider, self.transport = provider, transport
        self.paused = False

    async def decide(self, observation: Observation | None, suggestions: list[Suggestion],
                     heard: str, mission_text: str, preset: Preset) -> Decision:
        if self.paused and not heard:
            return Decision(False, reason="paused")
        # Hard rule: a safety suggestion always speaks, preset cannot veto it.
        for s in suggestions:
            if s.priority == "safety":
                return Decision(True, s.say or s.text, "safety-first rule")
        if heard:
            # The user spoke: the Director answers via the model, briefly.
            text = await _complete(self.provider, Turn(prompt=(
                f"Mission state: {mission_text}\n"
                + (f"Eyes report: {observation.text}\n" if observation else "")
                + f"User said: \"{heard}\"\nReply with ONE short spoken sentence, or exactly SILENT.")), self.transport)
            text = text.strip()
            if text and not text.upper().startswith("SILENT"):
                return Decision(True, text[:240], "user spoke")
            return Decision(False, reason="director silent on user turn")
        if not suggestions:
            return Decision(False, reason="nothing to say")
        best = max(suggestions, key=lambda s: preset.weight(s.priority))
        if preset.weight(best.priority) * preset.chattiness < 0.25:
            return Decision(False, reason=f"preset {preset.name} gates {best.priority}")
        return Decision(True, best.say or best.text, f"top suggestion ({best.priority})")
