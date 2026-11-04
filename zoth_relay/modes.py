"""Spotter goal modes (built Nov 2 2026 at the hackathon).

The wearer picks what Spotter is *for* this outing -- network & meet people,
repair/fix something, adventure/explore, or a custom goal -- through a short
guided onboarding. The chosen mode is persisted per agent and folded into the
Guide/voice system prompt and the mission planner so every reply leans toward
that goal. Storage is a small JSON file (no goal text is ever audited).
"""
from __future__ import annotations

import json
import os
import re
import threading
import time
from dataclasses import asdict, dataclass, field
from pathlib import Path

MODES: dict[str, dict] = {
    "intel": {
        "label": "Intel & People",
        "blurb": "Character Boards, facial recognition, social dossiers, VIP tracking.",
        "prompt": ("Goal mode INTEL & RECON: the wearer is networking, meeting people, or conducting "
                   "social recon. Identify people in view using the Saved People roster and 4-Angle "
                   "Character Boards. Suggest natural, confident openers tied to what is seen, note attire "
                   "and expressions, and help remember names and follow-ups. Speak concisely into the wearer's ear."),
        "questions": ["Where are you (conference, meetup, pitch, event)?",
                      "What kind of people or partners are you looking to meet?"],
        "map": False,
    },
    "god_voice": {
        "label": "Subconscious (God's Voice)",
        "blurb": "God's voice in your ear · quiet inner guidance and deep intuition.",
        "prompt": ("Goal mode SUBCONSCIOUS (GOD'S VOICE IN YOUR EAR): You are the wearer's inner voice, "
                   "subconscious guardian, and higher intuition speaking softly into their ear through smart glasses. "
                   "Do NOT chatter continuously. Speak ONLY when a critical intuitive insight, hidden pattern, "
                   "moral clarity, or safety hazard is detected, or when the wearer pauses and speaks to you. "
                   "Keep all replies under 16 words, calm, serene, and deeply grounding."),
        "questions": ["What is your inner focus or intention right now?",
                      "How proactive should the inner voice be (whisper only on key insights vs reflective)?"],
        "map": False,
    },
    "vibe_coder": {
        "label": "Vibe Coder",
        "blurb": "Screen pair programmer, code review, debugging, terminal wingman.",
        "prompt": ("Goal mode VIBE CODER: You are an elite software engineering pair programmer looking directly "
                   "at the wearer's monitors, laptop screens, IDEs, and terminals. "
                   "Instantly spot syntax errors, tracebacks, git commands, and architecture improvements. "
                   "When the wearer asks about code in view, provide the exact fix, command, or explanation punchily."),
        "questions": ["What stack or language are you coding in right now?",
                      "Are you building a new feature or debugging an issue?"],
        "map": False,
    },
    "directive": {
        "label": "Custom Goal",
        "blurb": "Autonomous co-pilot executing the wearer's specific objective.",
        "prompt": ("Goal mode DIRECTIVE: Help the wearer accomplish their custom stated mission. "
                   "Actively watch the Vision Memory stream for landmarks, objects, or people matching the goal, "
                   "and provide proactive guidance toward completing the mission."),
        "questions": ["In one sentence, what specific mission or goal do you want Spotter to execute?"],
        "map": False,
    },
    # Aliases for backward compatibility
    "network": {
        "label": "Intel & People",
        "blurb": "Character Boards, facial recognition, social dossiers, VIP tracking.",
        "prompt": ("Goal mode INTEL & RECON: the wearer is networking, meeting people, or conducting "
                   "social recon. Identify people in view using the Saved People roster and 4-Angle "
                   "Character Boards. Suggest natural, confident openers tied to what is seen."),
        "questions": ["Where are you (event, bar, campus, conference)?",
                      "What kind of people do you want to meet?"],
        "map": False,
    },
    "custom": {
        "label": "Custom Goal",
        "blurb": "Tell Spotter your own goal.",
        "prompt": "Goal mode DIRECTIVE: help the wearer with their stated goal.",
        "questions": ["In one sentence, what do you want Spotter to help with?"],
        "map": False,
    },
    "repair": {
        "label": "Repair / fix this",
        "blurb": "Step-by-step help fixing a car, appliance or gadget.",
        "prompt": ("Goal mode REPAIR: the wearer is fixing something. Identify parts in "
                   "view, give one safe concrete step at a time, call out hazards first "
                   "(hot, sharp, electrical, jack stands), and confirm before moving on."),
        "questions": ["What are you fixing (make/model if you know)?",
                      "What is it doing wrong?"],
        "map": False,
    },
    "adventure": {
        "label": "Adventure / tell me where to go",
        "blurb": "Explore nearby, pick a direction, discover spots.",
        "prompt": ("Goal mode ADVENTURE: the wearer wants to explore. Use the compass "
                   "heading and location if given, suggest where to go next in plain "
                   "directions (left, ahead, 2 blocks north), point out interesting "
                   "things in view, and keep them safe and aware of surroundings."),
        "questions": ["Walking, biking or driving?",
                      "What sounds fun: food, views, history, nightlife, nature?"],
        "map": True,
    },
}

_KEYWORDS = [
    ("network", r"\b(network|meet|people|friends?|socia\w*|date|party|mingle|conference)\b"),
    ("repair", r"\b(fix|repair|car|engine|broken|mechanic|tire|leak|install|appliance)\b"),
    ("adventure", r"\b(adventure|explore|where to go|wander|hike|travel|tour|discover|trip)\b"),
]


def parse_mode(text: str) -> str:
    """Map a free-form answer ("i'd like to fix this car") to a mode key."""
    t = (text or "").strip().lower()
    if t in MODES:
        return t
    for key, pat in _KEYWORDS:
        if re.search(pat, t):
            return key
    return "custom"


@dataclass
class ModeState:
    mode: str = "custom"
    goal: str = ""
    answers: list[str] = field(default_factory=list)
    map_hud: bool = False
    onboarded: bool = False
    updated: float = 0.0

    def public(self) -> dict:
        d = asdict(self)
        d["label"] = MODES[self.mode]["label"]
        return d


def mode_prompt(st: ModeState | None) -> str:
    if st is None or not st.onboarded:
        return ""
    lines = [MODES[st.mode]["prompt"]]
    if st.goal:
        lines.append(f"Wearer's goal: {st.goal[:300]}")
    for q, a in zip(MODES[st.mode]["questions"], st.answers):
        if a:
            lines.append(f"{q} -> {a[:200]}")
    return "\n".join(lines)


def default_path() -> str:
    return os.environ.get("ZOTH_MODES_FILE") or str(
        Path("~/.local/share/zoth-relay/modes.json").expanduser())


class ModeStore:
    def __init__(self, path: str | None = None):
        self.path = Path(path or default_path())
        self._lock = threading.Lock()
        self._data: dict[str, ModeState] = {}
        self._load()

    def _load(self) -> None:
        try:
            raw = json.loads(self.path.read_text())
        except Exception:
            return
        for k, v in (raw or {}).items():
            try:
                st = ModeState(**{f: v[f] for f in ModeState.__dataclass_fields__ if f in v})
                if st.mode in MODES:
                    self._data[k] = st
            except Exception:
                continue

    def _save(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        tmp = self.path.with_suffix(".tmp")
        tmp.write_text(json.dumps({k: asdict(v) for k, v in self._data.items()}))
        os.chmod(tmp, 0o600)
        tmp.replace(self.path)

    def get(self, agent: str) -> ModeState | None:
        return self._data.get(agent)

    def set(self, agent: str, mode: str, goal: str = "", answers=None,
            map_hud: bool | None = None) -> ModeState:
        key = mode if mode in MODES else parse_mode(mode)
        st = ModeState(mode=key, goal=(goal or "").strip()[:500],
                       answers=[str(a).strip()[:300] for a in (answers or [])][:6],
                       map_hud=MODES[key]["map"] if map_hud is None else bool(map_hud),
                       onboarded=True, updated=time.time())
        with self._lock:
            self._data[agent] = st
            self._save()
        return st

    def clear(self, agent: str) -> None:
        with self._lock:
            self._data.pop(agent, None)
            self._save()


def onboard_step(answers: list[str]) -> dict:
    """Stateless guided setup. answers[0] picks the mode; the rest answer
    that mode's questions. Returns the next question or done=True."""
    if not answers:
        return {"done": False, "question": "What do you want Spotter to help with today?",
                "choices": [{"key": k, "label": v["label"], "blurb": v["blurb"]} for k, v in MODES.items()]}
    mode = parse_mode(answers[0])
    qs = MODES[mode]["questions"]
    rest = answers[1:]
    if len(rest) < len(qs):
        return {"done": False, "mode": mode, "label": MODES[mode]["label"], "question": qs[len(rest)]}
    goal = answers[0] if mode == "custom" or answers[0].strip().lower() not in MODES else ""
    if mode == "custom" and rest:
        goal = rest[0]
    return {"done": True, "mode": mode, "label": MODES[mode]["label"], "goal": goal,
            "answers": rest[:len(qs)], "map_hud": MODES[mode]["map"]}
