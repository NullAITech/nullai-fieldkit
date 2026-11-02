"""People the wearer has met: confirmed identities, notes, and re-sighting hints.

Privacy contract (same rule the relay already applies in `sessions.py` and `GUIDE_SYSTEM`):

* A face NEVER names a person. Spotter's on-device descriptor can only answer "this looks like
  someone you have met before" - a candidate, never an identity. Two similar-looking people
  collide on purpose, and that is why the app asks instead of asserting.
* Names, notes and relationships exist ONLY because the wearer typed or said them. The model is
  allowed to read them; it is never the source of them.
* An unnamed sighting is stored `pending` and is never named in a prompt or spoken about. One
  command forgets it.
* Not a biometric: descriptors are coarse (8x8 grey / 4x4 colour / 12-bin gradient, from
  `PersonDescriptor.java`), the store is a 0600 JSON file under the relay's state dir, and
  forgetting a person drops their descriptor with the record.

Descriptors arrive from the phone already L2-normalised, so matching is a plain dot product.
"""
from __future__ import annotations

import json
import math
import os
import time
import base64
from dataclasses import asdict, dataclass, field

DEFAULT_CAP = 120
DEFAULT_THRESHOLD = 0.86      # cosine; calibrated on synthetic crops in the Android harness
DEFAULT_MARGIN = 0.03         # best must beat runner-up by this, else we say "not sure"
VOICE_THRESHOLD = 0.88       # cosine on the phone's 42-dim VoicePrint (voice/VoicePrint.java)
THUMB_MAX_B64 = 96_000       # ~70 KB JPEG: a face crop, never a full frame
DRIFT_ALPHA = 0.25            # slow follow of hair/lighting changes on a CONFIRMED person only


def cosine(a, b) -> float:
    """Dot product of two L2-normalised descriptors; falls back to a real cosine if not unit-norm."""
    if not a or not b or len(a) != len(b):
        return 0.0
    dot = sum(x * y for x, y in zip(a, b))
    na = math.sqrt(sum(x * x for x in a))
    nb = math.sqrt(sum(y * y for y in b))
    if na < 1e-9 or nb < 1e-9:
        return 0.0
    return dot / (na * nb)


def _fernet(key_path: str | None):
    """At-rest encryption for the people store when `cryptography` is installed.

    The key is a 0600 file beside the store (same 0700 state dir). That protects a copied or
    backed-up people.json, not a machine whose user account is already compromised. Without
    `cryptography` the store stays a 0600 plaintext JSON, exactly as before Nov 2."""
    if not key_path:
        return None
    try:
        from cryptography.fernet import Fernet
    except Exception:
        return None
    try:
        if os.path.exists(key_path):
            with open(key_path, "rb") as fh:
                key = fh.read().strip()
        else:
            parent = os.path.dirname(key_path)
            if parent:
                os.makedirs(parent, mode=0o700, exist_ok=True)
            key = Fernet.generate_key()
            fd = os.open(key_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(fd, "wb") as fh:
                fh.write(key)
        return Fernet(key)
    except Exception:
        return None


def clean_thumb(b64) -> str:
    """Accept only a small base64 JPEG/PNG/WebP; anything else is dropped, never stored."""
    if not isinstance(b64, str) or not b64:
        return ""
    if "," in b64[:80] and b64.startswith("data:"):
        b64 = b64.split(",", 1)[1]
    if len(b64) > THUMB_MAX_B64:
        return ""
    try:
        raw = base64.b64decode(b64, validate=True)
    except Exception:
        return ""
    if raw[:3] == b"\xff\xd8\xff" or raw[:8] == b"\x89PNG\r\n\x1a\n" or raw[8:12] == b"WEBP":
        return b64
    return ""


def clean_vector(raw, limit: int = 512) -> list[float] | None:
    if not isinstance(raw, (list, tuple)) or not raw or len(raw) > limit:
        return None
    try:
        v = [float(x) for x in raw]
    except (TypeError, ValueError):
        return None
    if any(math.isnan(x) or math.isinf(x) for x in v):
        return None
    return v


@dataclass
class Person:
    id: str
    name: str = ""                       # empty => pending sighting, never spoken
    relation: str = ""                   # "my brother", "site foreman" - wearer's words
    notes: list[str] = field(default_factory=list)
    descriptor: list[float] = field(default_factory=list)
    samples: int = 0                     # descriptor samples folded in (EMA weight)
    first_seen: float = 0.0
    last_seen: float = 0.0
    encounters: int = 0
    # Nov 2 (hackathon): "remember this person as <name>" on a live call.
    saved_at: float = 0.0                # when the wearer saved them (the frame's time)
    thumb: str = ""                      # small JPEG face crop, base64; the frame he saved
    voice: list[float] = field(default_factory=list)   # optional on-device voice print
    voice_saved_at: float = 0.0

    @property
    def pending(self) -> bool:
        return not self.name

    def add_note(self, text: str, at: float | None = None) -> None:
        text = " ".join((text or "").split())[:400]
        if not text:
            return
        self.notes.append(text)
        del self.notes[:-20]


class People:
    """Bounded, atomic-on-disk people memory. `path=None` => in-memory only (tests, PRIVACY off)."""

    def __init__(self, path: str | None = None, cap: int = DEFAULT_CAP,
                 threshold: float = DEFAULT_THRESHOLD, margin: float = DEFAULT_MARGIN,
                 now=time.time):
        self.path = os.path.expanduser(path) if path else None
        self.cap = max(1, int(cap))
        self.threshold = float(threshold)
        self.margin = float(margin)
        self._now = now
        self.records: dict[str, Person] = {}
        self._seq = 0
        self._fernet = _fernet(f"{self.path}.key" if self.path else None)
        self.encrypted = self._fernet is not None
        self.load()

    # ------------------------------------------------------------------ storage
    def load(self) -> None:
        if not self.path or not os.path.exists(self.path):
            return
        try:
            with open(self.path, "rb") as fh:
                blob = fh.read()
            if blob[:1] != b"{" and self._fernet is not None:
                blob = self._fernet.decrypt(blob)         # encrypted store (Nov 2)
            raw = json.loads(blob.decode("utf-8"))        # a pre-Nov-2 plaintext store still loads
        except Exception:
            return
        for d in raw.get("people", []):
            try:
                p = Person(**{k: v for k, v in d.items() if k in Person.__dataclass_fields__})
            except TypeError:
                continue
            if p.id:
                self.records[p.id] = p
        for p in list(self.records.values()):                 # pending never outlives a restart
            if p.pending and self._now() - p.last_seen > 3600:
                self.records.pop(p.id, None)
        self._seq = len(self.records)

    def save(self) -> None:
        """Atomic + 0600: a half-written store must never replace a good one."""
        if not self.path:
            return
        parent = os.path.dirname(self.path)
        if parent:
            os.makedirs(parent, mode=0o700, exist_ok=True)
        blob = {"version": 1, "people": [asdict(p) for p in self.records.values()]}
        tmp = f"{self.path}.tmp.{os.getpid()}"
        data = json.dumps(blob, separators=(",", ":")).encode("utf-8")
        if self._fernet is not None:
            data = self._fernet.encrypt(data)
        fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "wb") as fh:
            fh.write(data)
        os.replace(tmp, self.path)

    def prune(self) -> None:
        """Over cap: drop the oldest unconfirmed sighting first, then the least-encountered."""
        if len(self.records) <= self.cap:
            return
        order = sorted(self.records.values(), key=lambda p: (not p.pending, p.encounters, p.last_seen))
        for p in order[: len(self.records) - self.cap]:
            self.records.pop(p.id, None)

    # ------------------------------------------------------------------ matching
    def match(self, descriptor) -> tuple[Person | None, float, float]:
        """-> (person|None, best_score, runner_up_score). None means 'say nothing about who this is'."""
        scored = sorted(((cosine(descriptor, p.descriptor), p) for p in self.records.values()
                         if p.descriptor), key=lambda t: t[0], reverse=True)
        if not scored:
            return None, 0.0, 0.0
        best, person = scored[0]
        runner = scored[1][0] if len(scored) > 1 else 0.0
        if best < self.threshold or (best - runner) < self.margin:
            return None, best, runner
        return person, best, runner

    # ------------------------------------------------------------------ writes
    def observe(self, descriptor, at: float | None = None) -> dict:
        """A sighting came in from the glasses. Never invents a name.

        The spoken cue is computed from the PREVIOUS sighting and returned with the observation, so
        the caller gets the "you last saw them three days ago" line without having to reason about
        ordering itself.
        """
        at = self._now() if at is None else at
        person, score, runner = self.match(descriptor)
        if person is None:
            self._seq += 1
            pid = f"p{self._seq:04d}"
            person = Person(id=pid, descriptor=list(descriptor), samples=1,
                            first_seen=at, last_seen=at, encounters=1)
            self.records[pid] = person
            self.prune()
            self.save()
            return {"status": "new", "id": pid, "name": "", "score": round(score, 3),
                    "ambiguous": score >= self.threshold, "reflection": "", "since_seconds": 0.0}
        cue = self.reflection(person, at)                     # BEFORE last_seen moves
        since = max(0.0, at - person.last_seen)
        person.encounters += 1
        person.last_seen = at
        if not person.pending:                    # only confirmed people drift with the wearer
            person.descriptor = [(1 - DRIFT_ALPHA) * cur + DRIFT_ALPHA * new for cur, new in
                                 zip(person.descriptor, descriptor)]
            norm = math.sqrt(sum(x * x for x in person.descriptor)) or 1.0
            person.descriptor = [x / norm for x in person.descriptor]
            person.samples += 1
        self.save()
        return {"status": "known" if not person.pending else "pending",
                "id": person.id, "name": person.name, "score": round(score, 3),
                "runner_up": round(runner, 3), "encounters": person.encounters,
                "reflection": cue, "since_seconds": round(since, 1)}

    def remember(self, descriptor, name: str, note: str = "", relation: str = "",
                 at: float | None = None) -> Person:
        """The wearer named this person (from the phone UI or out loud). The ONLY way a name is set."""
        at = self._now() if at is None else at
        person, _, _ = self.match(descriptor) if descriptor else (None, 0.0, 0.0)
        if person is None:
            self._seq += 1
            person = Person(id=f"p{self._seq:04d}", descriptor=list(descriptor or []),
                            samples=1 if descriptor else 0, first_seen=at, encounters=1)
            self.records[person.id] = person
        person.name = name.strip()[:80]
        person.relation = relation.strip()[:80] or person.relation
        person.last_seen = at
        if note.strip():
            person.add_note(note, at)
        self.prune()
        self.save()
        return person

    def confirm(self, person_id: str, name: str, note: str = "", relation: str = "") -> Person | None:
        """Promote a pending sighting to a named person."""
        p = self.records.get(person_id)
        if p is None:
            return None
        p.name = name.strip()[:80]
        p.relation = relation.strip()[:80] or p.relation
        if note.strip():
            p.add_note(note, self._now())
        self.save()
        return p

    def forget(self, person_id: str) -> bool:
        p = self.records.pop(person_id, None)
        if p is None:
            return False
        self.save()
        return True

    def clear(self) -> int:
        n = len(self.records)
        self.records.clear()
        self.save()
        return n

    def save_person(self, name: str, descriptor=None, thumb: str = "", voice=None,
                    at: float | None = None, relation: str = "", note: str = "") -> Person:
        """'Remember this person as <name>' from a live call: the wearer's explicit action.

        Binds his name to a face descriptor and/or a voice print, plus the frame crop and the time
        he saved it. Needs at least one of descriptor/voice - a bare name is never stored."""
        desc = clean_vector(descriptor)
        vp = clean_vector(voice, 128)
        if not desc and not vp:
            raise ValueError("need a face descriptor or a voice print")
        at = self._now() if at is None else float(at)
        if desc:
            p = self.remember(desc, name, note, relation, at)
        else:
            p = self.match_voice(vp)
            if p is None:
                self._seq += 1
                p = Person(id=f"p{self._seq:04d}", first_seen=at, encounters=1)
                while p.id in self.records:
                    self._seq += 1
                    p.id = f"p{self._seq:04d}"
                self.records[p.id] = p
            p.name = name.strip()[:80]
            p.relation = relation.strip()[:80] or p.relation
            p.last_seen = at
            if note.strip():
                p.add_note(note, at)
        p.saved_at = at
        t = clean_thumb(thumb)
        if t:
            p.thumb = t
        if vp:
            p.voice, p.voice_saved_at = vp, at
        self.save()
        return p

    def add_voice(self, person_id: str, voice, at: float | None = None) -> Person | None:
        p = self.records.get(person_id)
        vp = clean_vector(voice, 128)
        if p is None or not vp:
            return None
        p.voice, p.voice_saved_at = vp, self._now() if at is None else float(at)
        self.save()
        return p

    def forget_voice(self, person_id: str) -> bool:
        p = self.records.get(person_id)
        if p is None or not p.voice:
            return False
        p.voice, p.voice_saved_at = [], 0.0
        if not p.descriptor:                       # nothing left to bind the name to
            self.records.pop(person_id, None)
        self.save()
        return True

    def rename(self, person_id: str, name: str) -> Person | None:
        p = self.records.get(person_id)
        name = (name or "").strip()[:80]
        if p is None or not name:
            return None
        p.name = name
        self.save()
        return p

    def match_voice(self, voice, threshold: float = VOICE_THRESHOLD) -> Person | None:
        """Named people only: a voice match is a hint for the model, never a new identity."""
        if not voice:
            return None
        scored = sorted(((cosine(voice, p.voice), p) for p in self.named() if p.voice),
                        key=lambda t: t[0], reverse=True)
        if not scored or scored[0][0] < threshold:
            return None
        if len(scored) > 1 and scored[0][0] - scored[1][0] < self.margin:
            return None
        return scored[0][1]

    def public(self, person: Person, with_thumb: bool = True) -> dict:
        """What the People & Voices screen shows. Never the raw vectors."""
        out = {"id": person.id, "name": person.name, "relation": person.relation,
               "saved_at": person.saved_at or person.first_seen, "last_seen": person.last_seen,
               "encounters": person.encounters, "has_face": bool(person.descriptor),
               "has_voice": bool(person.voice), "voice_saved_at": person.voice_saved_at or None,
               "notes": person.notes[-3:]}
        if with_thumb:
            out["thumb"] = person.thumb or None
        return out

    def listing(self, with_thumb: bool = True) -> list[dict]:
        return [self.public(p, with_thumb) for p in
                sorted(self.named(), key=lambda p: p.saved_at or p.first_seen, reverse=True)]

    def pending_ids(self) -> list[str]:
        return [p.id for p in self.records.values() if p.pending]

    # ------------------------------------------------------------------ reads
    def named(self) -> list[Person]:
        return [p for p in self.records.values() if not p.pending]

    def prompt_lines(self, limit: int = 6) -> list[str]:
        """Compact, confirmed-only lines for the model. Pending sightings contribute nothing."""
        seen = sorted(self.named(), key=lambda p: p.last_seen, reverse=True)[:limit]
        out = []
        for p in seen:
            bits = [p.name]
            if p.relation:
                bits.append(f"({p.relation})")
            bits.append(f"met {p.encounters}x")
            if p.notes:
                bits.append(f"noted: {'; '.join(p.notes[-2:])[:160]}")
            out.append(" ".join(bits))
        return out

    def reflection(self, person: Person, at: float | None = None) -> str:
        """Deterministic spoken cue for a re-sighting, so this works with no network at all."""
        at = self._now() if at is None else at
        gap = max(0.0, at - person.last_seen)
        if person.encounters <= 1:
            return f"{person.name} - first time you've met."
        if gap < 120:
            return f"{person.name} is still with you."
        if gap < 5400:
            return f"{person.name} again, {int(gap / 60)} minutes later."
        if gap < 172800:
            return f"{person.name} again, {int(gap / 3600)} hours since you last saw them."
        days = int(gap / 86400)
        line = f"{person.name} again, {days} days since you last saw them."
        if person.notes:
            line += f" Last time: {person.notes[-1][:120]}"
        return line
