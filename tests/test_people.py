"""/v1 guide people memory: confirmation-only identities, matching, persistence. No network."""
import json
import os
import stat
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from zoth_relay.people import People, cosine  # noqa: E402


def unit(v):
    n = sum(x * x for x in v) ** 0.5
    return [x / n for x in v]


A = unit([1.0, 0.2, 0.0, 0.1, 0.9, 0.3, 0.4, 0.2])
B = unit([0.0, 0.9, 1.0, 0.8, 0.1, 0.2, 0.3, 0.1])
A2 = unit([1.0, 0.25, 0.05, 0.1, 0.85, 0.35, 0.4, 0.22])     # same person, different light


def test_cosine_is_scale_free():
    assert cosine(A, A) == pytest.approx(1.0)
    assert cosine(A, [x * 5 for x in A]) == pytest.approx(1.0)
    assert cosine(A, B) < 0.7
    assert cosine(A, []) == 0.0
    assert cosine(A, [1.0, 2.0]) == 0.0                      # length mismatch, not an exception


def test_new_sighting_is_pending_and_never_named():
    p = People()
    out = p.observe(A)
    assert out["status"] == "new"
    assert out["name"] == ""
    assert p.pending_ids() == [out["id"]]
    assert p.prompt_lines() == []                            # nothing goes to the model
    assert p.named() == []


def test_remember_is_the_only_way_a_name_exists():
    p = People()
    pid = p.observe(A)["id"]
    assert p.pending_ids() == [pid]
    person = p.remember(A, "Maya", note="her flight is Thursday", relation="coworker")
    assert person.name == "Maya"
    assert p.pending_ids() == []                             # promoted, not duplicated
    lines = p.prompt_lines()
    assert len(lines) == 1 and lines[0].startswith("Maya (coworker)")
    assert "Thursday" in lines[0]


def test_resight_matches_and_reflects_the_gap():
    p = People()
    p.remember(A, "Maya", note="moved to Ridgeway")
    t0 = 1_000_000.0
    p.observe(A2, at=t0)
    again = p.observe(A2, at=t0 + 3 * 86400)
    assert again["status"] == "known" and again["name"] == "Maya"
    assert again["encounters"] >= 2
    # The cue must describe the gap BEFORE this sighting, and arrive with the observation itself.
    assert "3 days" in again["reflection"] and "Maya" in again["reflection"]
    assert "Ridgeway" in again["reflection"]
    assert again["since_seconds"] == pytest.approx(3 * 86400)
    # A sighting right after is not a "3 days" event any more.
    assert "still with you" in p.observe(A2, at=t0 + 3 * 86400 + 30)["reflection"]


def test_first_sighting_has_no_gap_claim():
    p = People()
    p.remember(A, "Maya")
    first = p.observe(A2, at=500.0)
    assert "first time" in first["reflection"]
    assert p.observe(A, at=1.0)["since_seconds"] == 0.0


def test_ambiguous_descriptor_is_not_merged():
    p = People()
    p.remember(A, "Maya")
    p.remember(B, "Tom")
    mid = unit([x + y for x, y in zip(A, B)])                # sits between the two
    person, score, runner = p.match(mid)
    assert person is None, "a descriptor between two people must not be asserted as either"
    assert score - runner < p.margin or score < p.threshold
    out = p.observe(mid)
    assert out["status"] == "new" and len(p.records) == 3


def test_unconfirmed_sighting_does_not_drift():
    p = People()
    pid = p.observe(A)["id"]
    before = list(p.records[pid].descriptor)
    p.observe(A2)
    p.observe(unit([0.9, 0.4, 0.1, 0.2, 0.8, 0.3, 0.5, 0.2]))
    assert p.records[pid].descriptor == before, "an unknown person's descriptor must stay put"


def test_confirmed_person_drifts_toward_new_lighting():
    p = People()
    p.remember(A, "Maya")
    p.observe(A2)
    p.observe(A2)
    moved = p.records[list(p.records)[0]].descriptor
    assert cosine(moved, A2) > cosine(A, A2)
    assert cosine(moved, A) > 0.9, "drift must not wander off the person"


def test_persistence_round_trip_is_0600_and_atomic(tmp_path):
    path = tmp_path / "state" / "people.json"
    p = People(str(path))
    p.remember(A, "Maya", note="has the spare key")
    p.observe(B)
    assert path.exists()
    assert stat.S_IMODE(path.stat().st_mode) == 0o600
    assert not list(path.parent.glob("*.tmp*")), "temp file must be renamed away"
    back = People(str(path))
    assert [r.name for r in back.named()] == ["Maya"]
    assert back.named()[0].notes == ["has the spare key"]
    assert len(back.pending_ids()) == 1
    json.loads(path.read_text())                             # must be valid JSON on disk


def test_corrupt_store_does_not_crash(tmp_path):
    path = tmp_path / "people.json"
    path.write_text("{ this is not json")
    p = People(str(path))
    assert p.records == {}
    p.remember(A, "Maya")
    assert People(str(path)).named()[0].name == "Maya"


def test_forget_drops_the_descriptor_too():
    p = People()
    pid = p.remember(A, "Maya").id
    assert p.forget(pid) is True
    assert p.records == {} and p.forget(pid) is False
    assert p.match(A)[0] is None, "a forgotten person must not still match"


def test_cap_prunes_pending_sightings_first():
    p = People(cap=3)
    p.remember(A, "Maya")
    p.remember(B, "Tom")
    p.observe(unit([0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 0.5]))
    p.observe(unit([0.2, 0.2, 0.2, 0.9, 0.9, 0.9, 0.1, 0.1]))
    assert len(p.records) == 3
    assert sorted(r.name for r in p.named()) == ["Maya", "Tom"], "named people outlive sightings"


def test_stale_pending_is_dropped_on_load(tmp_path):
    path = tmp_path / "people.json"
    path.write_text(json.dumps({"version": 1, "people": [
        {"id": "p0001", "name": "", "descriptor": A, "first_seen": 0.0, "last_seen": 0.0,
         "encounters": 1, "notes": [], "relation": "", "samples": 1},
        {"id": "p0002", "name": "Maya", "descriptor": A, "first_seen": 0.0, "last_seen": 0.0,
         "encounters": 1, "notes": [], "relation": "", "samples": 1}]}))
    p = People(str(path), now=lambda: 10_000.0)
    assert p.pending_ids() == []
    assert [r.name for r in p.named()] == ["Maya"]


def test_confirm_promotes_and_note_is_bounded():
    p = People()
    pid = p.observe(A)["id"]
    assert p.confirm(pid, "Maya", note="  lots   of   space  ") is not None
    assert p.records[pid].notes == ["lots of space"]
    for i in range(30):
        p.records[pid].add_note(f"n{i}")
    assert len(p.records[pid].notes) == 20
    assert p.records[pid].notes[-1] == "n29"
    assert p.confirm("nope", "Ghost") is None


def test_in_memory_store_writes_nothing(tmp_path):
    p = People(None)
    p.remember(A, "Maya")
    assert p.save() is None
    assert os.listdir(tmp_path) == []
