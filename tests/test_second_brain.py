"""FIELDKIT Second Brain: Obsidian mission engrams, Base + Canvas, prior recall.

Writes only inside a tmp vault. No network, no Obsidian app needed: the
artifacts are files (YAML frontmatter, .base YAML, JSON Canvas 1.0).
"""
import json

import pytest
import yaml

from zoth_relay import second_brain
from zoth_relay.missions import MissionStore


@pytest.fixture
def vault(tmp_path, monkeypatch):
    monkeypatch.setenv("FIELDKIT_VAULT_DIR", str(tmp_path / "vault"))
    return tmp_path / "vault"


def _mission(store, goal="Fix the mower", steps=("Pull filter", "Fit new filter")):
    return store.create(goal, list(steps), provider="wonder")


def test_compile_mission_note_frontmatter_and_body(vault):
    store = MissionStore()
    m = _mission(store)
    store.apply_event(m.id, "advance")
    path = second_brain.compile_mission(store.get(m.id), spark_p50_ms=1274,
                                        events=[{"type": "unsafe",
                                                 "text": "Blade guard was off"}])
    text = path.read_text()
    assert path.parent.name == "FieldKit-Missions"
    fm = yaml.safe_load(text.split("---", 2)[1])
    assert fm["mission_id"] == m.id
    assert fm["goal"] == "Fix the mower"
    assert fm["status"] == "active"
    assert fm["provider_plan"] == "muse-wonder"
    assert fm["provider_vision"] == "muse-spark"
    assert fm["steps_total"] == 2 and fm["steps_done"] == 1
    assert "fieldkit" in fm["tags"] and "ray-ban-meta" in fm["tags"]
    assert "- [x] Pull filter" in text and "- [ ] Fit new filter" in text
    assert "> [!warning]" in text and "Blade guard was off" in text
    assert "[[FieldKit Architecture & Mission Flow]]" in text


def test_base_and_canvas_are_valid(vault):
    base = second_brain.write_base()
    data = yaml.safe_load(base.read_text())
    assert data["views"] and {v["type"] for v in data["views"]} >= {"table", "cards"}
    canvas = second_brain.write_canvas()
    doc = json.loads(canvas.read_text())
    labels = " ".join(n.get("text", "") for n in doc["nodes"])
    for needle in ("Ray-Ban Meta", "Muse Wonder", "Muse Spark", "Hermes Jot", "Obsidian"):
        assert needle in labels
    file_nodes = [n for n in doc["nodes"] if n["type"] == "file"]
    assert file_nodes and "FieldKit Missions.base" in file_nodes[0]["file"]
    assert doc["edges"], "pipeline must be connected"


def test_prior_hints_recall_similar_job(vault):
    store = MissionStore()
    m = _mission(store, goal="Replace mower air filter")
    second_brain.compile_mission(m)
    hints = second_brain.prior_hints("Change the mower filter on the mower")
    assert hints and hints[0]["mission_id"] == m.id
    assert "mower" in hints[0]["note"].lower()
    assert second_brain.prior_hints("Unrelated quantum zebra question") == []


def test_compile_complete_status_and_rewrite(vault):
    store = MissionStore()
    m = _mission(store)
    store.apply_event(m.id, "advance")
    store.apply_event(m.id, "advance")
    path = second_brain.compile_mission(store.get(m.id))
    fm = yaml.safe_load(path.read_text().split("---", 2)[1])
    assert fm["status"] == "complete" and fm["steps_done"] == 2


def test_command_center_mirror(vault, tmp_path, monkeypatch):
    (tmp_path / "hk").mkdir()
    (tmp_path / "hk" / "HACKATHON-LOG.md").write_text("# Log\n\n- Milestone A shipped.\n")
    (tmp_path / "hk" / "PRIOR-WORK.md").write_text("# Prior\n\n- Existed before: relay.\n")
    monkeypatch.setenv("FIELDKIT_HACKATHON_DIR", str(tmp_path / "hk"))
    path = second_brain.mirror_command_center()
    text = path.read_text()
    assert "Milestone A shipped." in text and "Existed before" in text
    assert "fieldkit" in text.split("---", 2)[1]
