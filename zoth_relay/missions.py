"""FIELDKIT Missions (D3): planner + store + step tracker.

One spoken sentence -> a structured checklist -> tracked steps.

- Planner (Wonder by default): prompts for ONLY a JSON checklist and parses
  tolerantly (fences, prose wrappers, numbered-line fallback). The checklist is
  data, not prose: nothing downstream regex-scrapes model text.
- Tracker: consumes typed StepEvents (step_done / off_track / unsafe / none)
  produced by an assess call, so step state is machine state, never inferred
  from free text after the fact.
- Store: in-memory missions for the event; missions are session-scoped state,
  not durable records (audit log keeps counts only, as elsewhere).
"""
from __future__ import annotations

import json
import uuid
from dataclasses import dataclass, field

import httpx

from .providers import Provider, Turn

MAX_STEPS = 12
PLANNER_SYSTEM = (
    "You are a mission planner for a hands-free glasses coach. Break the goal into "
    "concrete physical steps the user does with their hands, in order. Each step is "
    "one short imperative sentence. Never plan anything unsafe or illegal."
)


class MissionError(Exception):
    def __init__(self, status: int, detail: str):
        super().__init__(detail)
        self.status, self.detail = status, detail


@dataclass
class Step:
    text: str
    status: str = "pending"          # pending | active | done | skipped

    def public(self, n: int) -> dict:
        return {"n": n, "text": self.text, "status": self.status}


@dataclass
class Mission:
    id: str
    goal: str
    provider: str
    steps: list[Step] = field(default_factory=list)
    jot_id: str = ""                 # Hermes Jot checklist note mirroring this mission
    recap: str = ""                  # recap card filename in the vault (set on completion)

    @property
    def current(self) -> int:
        """1-based index of the active step; total+1 when complete."""
        for i, s in enumerate(self.steps):
            if s.status == "active":
                return i + 1
        return len(self.steps) + 1

    @property
    def complete(self) -> bool:
        return bool(self.steps) and all(s.status in ("done", "skipped") for s in self.steps)

    @property
    def current_step_text(self) -> str:
        for s in self.steps:
            if s.status == "active":
                return s.text
        return ""

    def public(self) -> dict:
        return {"id": self.id, "goal": self.goal, "provider": self.provider,
                "steps": [s.public(i + 1) for i, s in enumerate(self.steps)],
                "current": self.current, "complete": self.complete,
                "step": self.current_step_text,
                "jot_note_id": self.jot_id}


class MissionStore:
    def __init__(self):
        self._by_id: dict[str, Mission] = {}
        self._last_id = ""

    def latest(self) -> Mission | None:
        return self._by_id.get(self._last_id)

    def create(self, goal: str, step_texts: list[str], provider: str = "") -> Mission:
        steps = [Step(t) for t in step_texts]
        if steps:
            steps[0].status = "active"
        m = Mission(id=uuid.uuid4().hex[:12], goal=goal, provider=provider, steps=steps)
        self._by_id[m.id] = m
        self._last_id = m.id
        return m

    def get(self, mission_id: str) -> Mission | None:
        return self._by_id.get(mission_id or "")

    def apply_event(self, mission_id: str, event: str) -> Mission:
        """advance: active->done; skip: active->skipped. Next pending activates."""
        m = self._by_id[mission_id]
        for s in m.steps:
            if s.status == "active":
                s.status = "done" if event == "advance" else "skipped"
                break
        for s in m.steps:
            if s.status == "pending":
                s.status = "active"
                break
        return m


MISSIONS = MissionStore()   # shared store: REST, sessions and MCP see one truth


def jot_command_text(text: str) -> str | None:
    """'Jot down: X' / 'jot this down X' -> X, else None. A voice command, parsed
    like pause/resume keywords (control phrase, not model output)."""
    low = (text or "").strip().lower()
    for prefix in ("jot this down", "jot that down", "jot down"):
        if low.startswith(prefix):
            rest = (text or "").strip()[len(prefix):].lstrip(" :,-").strip()
            return rest or None
    return None


def parse_checklist(text: str) -> list[str]:
    """Tolerant structured-output parse: JSON object, fenced JSON, or lines."""
    t = (text or "").strip()
    if t.startswith("```"):
        t = t.strip("`")
        t = t[4:] if t.lower().startswith("json") else t
    steps: list[str] = []
    lo, hi = t.find("{"), t.rfind("}")
    if 0 <= lo < hi:
        try:
            obj = json.loads(t[lo:hi + 1])
            raw = obj.get("steps") if isinstance(obj, dict) else None
            if isinstance(raw, list):
                steps = [str(s).strip() for s in raw]
        except json.JSONDecodeError:
            steps = []
    if not steps:
        for line in t.splitlines():
            line = line.strip().lstrip("-*").strip()
            if line and line[0].isdigit():
                line = line.lstrip("0123456789.) ").strip()
            if line:
                steps.append(line)
    return [s[:200] for s in steps if s][:MAX_STEPS]


async def _complete(provider: Provider, turn: Turn,
                    transport: httpx.AsyncBaseTransport | None) -> str:
    full: list[str] = []
    async for ev in provider.stream_events(turn, f"m{uuid.uuid4().hex[:8]}", transport):
        if ev.get("type") == "delta":
            full.append(ev.get("text", ""))
        elif ev.get("type") == "error":
            raise MissionError(ev.get("status", 502), ev.get("detail", "provider error"))
    return "".join(full)


async def plan_checklist(provider: Provider, goal: str,
                         transport: httpx.AsyncBaseTransport | None = None,
                         hints: list[str] | None = None) -> list[str]:
    """One spoken sentence -> structured checklist (Wonder structured plan).

    hints: compact prior-job notes (Second Brain recall). They inform the
    plan; they never replace the goal.
    """
    prior = ""
    if hints:
        prior = ("\nPrior field notes from similar missions (use what's relevant):\n"
                 + "\n".join(f"- {h}" for h in hints[:3]) + "\n")
    turn = Turn(
        system=PLANNER_SYSTEM,
        prompt=(f"Goal: {goal}\n{prior}\n"
                'Reply with ONLY JSON: {"steps": ["step 1", "step 2"]} — 4 to 8 short '
                "imperative steps, each under 12 words, in order. No other text."))
    steps = parse_checklist(await _complete(provider, turn, transport))
    if not steps:
        raise MissionError(502, f"planner {provider.name} returned no usable steps")
    return steps


@dataclass
class StepEvent:
    type: str                      # step_done | off_track | unsafe | none
    say: str = ""


async def assess_step(provider: Provider, mission: Mission, observation: str,
                      transport: httpx.AsyncBaseTransport | None = None) -> StepEvent:
    """Maps an observation to a typed step event (no prose scraping downstream)."""
    turn = Turn(prompt=(
        f"Mission: {mission.goal}\n"
        f"Current step {mission.current}/{len(mission.steps)}: {mission.current_step_text}\n"
        f"Observation (what the camera/user reports): {observation}\n"
        'Reply with ONLY JSON: {"event":"step_done|off_track|unsafe|none",'
        '"say":"one short spoken sentence"}'))
    text = await _complete(provider, turn, transport)
    lo, hi = text.find("{"), text.rfind("}")
    if 0 <= lo < hi:
        try:
            obj = json.loads(text[lo:hi + 1])
            ev = str(obj.get("event") or "none")
            if ev in ("step_done", "off_track", "unsafe", "none"):
                return StepEvent(ev, str(obj.get("say") or "")[:300])
        except json.JSONDecodeError:
            pass
    return StepEvent("none")
