"""FIELDKIT D5: confirm-gated Live Mode + recap endpoint + overlay."""
import yaml
from fastapi.testclient import TestClient

from zoth_relay.config import hash_token, load_config
from zoth_relay.live_mode import LiveGate
from zoth_relay.missions import MISSIONS
from zoth_relay.server import create_app

MOB = "zr_mobile_" + "m" * 40
H = {"Authorization": f"Bearer {MOB}"}


def _client(tmp_path):
    (tmp_path / "ws").mkdir()
    (tmp_path / "agents.yaml").write_text(yaml.safe_dump({"agents": {
        "pixel": {"token_sha256": hash_token(MOB), "profile": "mobile",
                  "allowed_dirs": [str(tmp_path / "ws")]}}}))
    (tmp_path / "config.yaml").write_text(yaml.safe_dump({
        "allowed_dirs": [str(tmp_path / "ws")], "audit_log": str(tmp_path / "a.log"),
        "agents_file": "agents.yaml"}))
    return TestClient(create_app(load_config(str(tmp_path / "config.yaml"))))


def test_gate_needs_explicit_confirm_token():
    g = LiveGate()
    r = g.start(None)
    assert r["ok"] is False and r["needs_confirm"] and r["confirm_token"]
    assert g.start("WRONG")["ok"] is False
    r = g.start(g.start(None)["confirm_token"])
    assert r["ok"] is True and g.active
    assert g.stop()["was_active"] is True and not g.active


def test_live_routes_and_audit(tmp_path):
    c = _client(tmp_path)
    r = c.post("/v1/live/start", headers=H, json={})
    assert r.json()["needs_confirm"]
    tok = r.json()["confirm_token"]
    r = c.post("/v1/live/start", headers=H, json={"confirm": tok})
    assert r.json()["ok"] is True
    assert c.get("/v1/live", headers=H).json()["active"] is True
    assert c.post("/v1/live/stop", headers=H).json()["ok"] is True
    audit = (tmp_path / "a.log").read_text()
    assert "live_start" in audit and "live_stop" in audit


def test_overlay_and_recap_endpoint(tmp_path, monkeypatch):
    monkeypatch.delenv("META_API_KEY", raising=False)
    c = _client(tmp_path)
    page = c.get("/overlay")
    assert page.status_code == 200 and "Next step" in page.text
    m = MISSIONS.create("Overlay job", ["Step one"], provider="wonder")
    MISSIONS.apply_event(m.id, "advance")
    r = c.post(f"/v1/missions/{m.id}/recap", headers=H)
    body = r.json()
    assert body["recap"]["ok"] is True and body["recap"]["source"] == "local"
    assert body["mission"]["recap"]
    r = c.post("/v1/missions/nope/recap", headers=H)
    assert r.status_code == 404
    latest = c.get("/v1/missions/latest", headers=H)
    assert latest.json()["id"] == m.id
