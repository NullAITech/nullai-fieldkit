"""FIELDKIT x Hermes Jot: live mission checklist sync (tests first).

The bridge talks to the real Jot worker protocol through a test double
(tests/fake_jot_worker.cjs): stdin/stdout JSON, revision-checked edits,
AGENT_DISABLED when AI collaboration is off. No Hermes host needed.
"""
import asyncio
import shutil
from pathlib import Path

import pytest

from zoth_relay import jot_bridge
from zoth_relay.missions import MissionStore

FAKE = Path(__file__).parent / "fake_jot_worker.cjs"
pytestmark = pytest.mark.skipif(shutil.which("node") is None, reason="node required")


@pytest.fixture
def jotenv(tmp_path, monkeypatch):
    monkeypatch.setenv("FIELDKIT_JOT_DIR", str(tmp_path / "jotdata"))
    monkeypatch.setenv("FIELDKIT_JOT_WORKER", str(FAKE))
    jot_bridge.NOTE_BY_MISSION.clear()
    return tmp_path / "jotdata"


def _mission(store, goal="Fix the mower", steps=("Pull filter", "Fit new filter")):
    return store.create(goal, list(steps), provider="wonder")


def test_status_available(jotenv):
    st = jot_bridge.status()
    assert st["available"] is True and st["ai_enabled"] is True


def test_status_ai_disabled_is_graceful(jotenv):
    jotenv.mkdir(parents=True)
    (jotenv / "agent.disabled").touch()
    st = jot_bridge.status()
    assert st["available"] is True and st["ai_enabled"] is False
    assert "off" in st["detail"].lower()


def test_status_missing_engine_is_graceful(tmp_path, monkeypatch):
    monkeypatch.setenv("FIELDKIT_JOT_WORKER", str(tmp_path / "no-such-worker.cjs"))
    st = jot_bridge.status()
    assert st["available"] is False


def test_mission_sync_creates_checklist_note(jotenv):
    store = MissionStore()
    m = _mission(store)
    res = asyncio.run(jot_bridge.sync_new_mission(m))
    assert res["ok"] and res["note_id"]
    data = jot_bridge.call("jot_read", {"id": res["note_id"]})
    assert "FieldKit Mission: Fix the mower" in (data.get("title") or "")
    tasks = data["tasks"]
    assert [t["text"] for t in tasks] == ["Pull filter", "Fit new filter"]
    assert not any(t["checked"] for t in tasks)


def test_auto_checkoff_ticks_the_step(jotenv):
    store = MissionStore()
    m = _mission(store)
    res = asyncio.run(jot_bridge.sync_new_mission(m))
    out = asyncio.run(jot_bridge.checkoff(m.id, 1))
    assert out["ok"]
    data = jot_bridge.call("jot_read", {"id": res["note_id"]})
    assert data["tasks"][0]["checked"] is True
    assert data["tasks"][1]["checked"] is False


def test_field_note_appends_with_context(jotenv):
    store = MissionStore()
    m = _mission(store)
    res = asyncio.run(jot_bridge.sync_new_mission(m))
    out = asyncio.run(jot_bridge.append_field_note(
        m.id, "left clamp bolt is stripped", step_label="Step 1: Pull filter"))
    assert out["ok"]
    data = jot_bridge.call("jot_read", {"id": res["note_id"]})
    assert "left clamp bolt is stripped" in data["markdown"]
    assert "Step 1" in data["markdown"]


def test_disabled_ai_never_breaks_the_mission_loop(jotenv):
    jotenv.mkdir(parents=True)
    (jotenv / "agent.disabled").touch()
    store = MissionStore()
    m = _mission(store)
    res = asyncio.run(jot_bridge.sync_new_mission(m))
    assert res["ok"] is False and res["status"] == "off"
    out = asyncio.run(jot_bridge.checkoff(m.id, 1))
    assert out["ok"] is False and out["status"] == "off"
    store.apply_event(m.id, "advance")          # mission loop unaffected
    assert store.get(m.id).current == 2


def test_full_sync_checks_already_done_steps(jotenv):
    store = MissionStore()
    m = _mission(store, steps=("A", "B", "C"))
    store.apply_event(m.id, "advance")
    store.apply_event(m.id, "advance")
    res = asyncio.run(jot_bridge.sync_mission(store.get(m.id)))
    assert res["ok"] and res["note_id"]
    data = jot_bridge.call("jot_read", {"id": res["note_id"]})
    assert [t["checked"] for t in data["tasks"]] == [True, True, False]
