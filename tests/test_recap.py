"""FIELDKIT D6: SAM 3.1 highlight boxes + Muse Image recap attach (env-gated)."""
import json

import httpx
import pytest

from zoth_relay import recap, second_brain
from zoth_relay.missions import MissionStore
from zoth_relay.recap import SamHighlighter


def _done_mission():
    store = MissionStore()
    m = store.create("Fix the mower", ["Pull filter", "Fit filter"], provider="wonder")
    store.apply_event(m.id, "advance")
    store.apply_event(m.id, "advance")
    return store.get(m.id)


async def test_sam_boxes_normalized(monkeypatch):
    monkeypatch.setenv("SAM_API_URL", "https://sam.example/segment")

    def handler(req: httpx.Request):
        body = json.loads(req.content)
        assert body["prompt"] == "Fit filter"
        return httpx.Response(200, json={"boxes": [
            {"x": 0.1, "y": 0.2, "w": 0.3, "h": 0.4, "label": "filter", "score": 0.9},
            {"junk": True}]})

    h = SamHighlighter(httpx.MockTransport(handler))
    boxes = await h.highlight((b"\xff\xd8img", "image/jpeg"), "Fit filter")
    assert boxes == [{"x": 0.1, "y": 0.2, "w": 0.3, "h": 0.4,
                      "label": "filter", "score": 0.9}]


async def test_sam_unconfigured_returns_nothing(monkeypatch):
    monkeypatch.delenv("SAM_API_URL", raising=False)
    h = SamHighlighter()
    assert not h.configured
    assert await h.highlight((b"x", "image/jpeg"), "anything") == []


async def test_recap_local_svg_when_no_key(tmp_path, monkeypatch):
    monkeypatch.setenv("FIELDKIT_VAULT_DIR", str(tmp_path / "vault"))
    monkeypatch.delenv("META_API_KEY", raising=False)
    monkeypatch.setenv("FIELDKIT_JOT_DIR", str(tmp_path / "jot"))
    m = _done_mission()
    res = await recap.maybe_recap(m)
    assert res["ok"] and res["source"] == "local" and res["recap"].endswith(".svg")
    asset = tmp_path / "vault" / "FieldKit-Missions" / "assets" / res["recap"]
    assert asset.is_file() and b"Fix the mower" in asset.read_bytes()
    note = second_brain.compile_mission(m).read_text()
    assert f"![[assets/{res['recap']}]]" in note
    again = await recap.maybe_recap(m)
    assert again["source"] == "existing"          # idempotent


async def test_recap_muse_image_b64(tmp_path, monkeypatch):
    monkeypatch.setenv("FIELDKIT_VAULT_DIR", str(tmp_path / "vault"))
    monkeypatch.setenv("META_API_KEY", "test-key")
    monkeypatch.setenv("FIELDKIT_JOT_DIR", str(tmp_path / "jot"))
    png = b"\x89PNG\r\n\x1a\nfake"

    def handler(req: httpx.Request):
        body = json.loads(req.content)
        assert body["model"] and "Fix the mower" in body["prompt"]
        import base64
        return httpx.Response(200, json={"data": [{"b64_json": base64.b64encode(png).decode()}]})

    m = _done_mission()
    res = await recap.maybe_recap(m, httpx.MockTransport(handler))
    assert res["ok"] and res["source"] == "muse-image" and res["recap"].endswith(".png")
    asset = tmp_path / "vault" / "FieldKit-Missions" / "assets" / res["recap"]
    assert asset.read_bytes() == png


async def test_recap_waits_for_completion(tmp_path, monkeypatch):
    monkeypatch.setenv("FIELDKIT_VAULT_DIR", str(tmp_path / "vault"))
    store = MissionStore()
    m = store.create("Fix the mower", ["Pull filter"], provider="wonder")
    res = await recap.maybe_recap(m)
    assert res["ok"] is False
