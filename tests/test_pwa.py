"""FIELDKIT D6: judge-facing PWA shell + browser WS token path."""
import yaml
from fastapi.testclient import TestClient

from zoth_relay.config import hash_token, load_config
from zoth_relay.server import create_app

MOB = "zr_mobile_" + "m" * 40


def _client(tmp_path):
    (tmp_path / "ws").mkdir()
    (tmp_path / "agents.yaml").write_text(yaml.safe_dump({"agents": {
        "pixel": {"token_sha256": hash_token(MOB), "profile": "mobile",
                  "allowed_dirs": [str(tmp_path / "ws")]}}}))
    (tmp_path / "config.yaml").write_text(yaml.safe_dump({
        "allowed_dirs": [str(tmp_path / "ws")], "audit_log": str(tmp_path / "a.log"),
        "agents_file": "agents.yaml"}))
    return TestClient(create_app(load_config(str(tmp_path / "config.yaml"))))


def test_pwa_shell_served(tmp_path):
    c = _client(tmp_path)
    r = c.get("/pwa")
    assert r.status_code == 200
    for marker in ("FieldKit", "Begin mission", "Hermes Jot synced", "jot down"):
        assert marker in r.text
    m = c.get("/pwa/manifest.webmanifest")
    assert m.status_code == 200 and m.json()["short_name"] == "FieldKit"
    sw = c.get("/pwa/sw.js")
    assert sw.status_code == 200 and "fieldkit-pwa" in sw.text


def test_pwa_ws_token_query_auth(tmp_path):
    c = _client(tmp_path)
    with c.websocket_connect(f"/v1/sessions?token={MOB}") as ws:
        ws.send_json({"type": "hello", "provider": "nope"})
        ev = ws.receive_json()
        assert ev["type"] == "error" and "unknown provider" in ev["detail"]
