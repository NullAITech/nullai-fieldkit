"""FIELDKIT D1 stubs: /v1/sessions (WS) + /v1/missions must exist, require
auth, and honestly report not-implemented until D2/D3. Replaced by real APIs.
"""
import pytest
import yaml
from fastapi.testclient import TestClient
from starlette.websockets import WebSocketDisconnect

from zoth_relay.config import hash_token, load_config
from zoth_relay.server import create_app

MOB = "zr_mobile_" + "m" * 40


def H(t):
    return {"Authorization": f"Bearer {t}"}


@pytest.fixture
def env(tmp_path):
    (tmp_path / "ws").mkdir()
    (tmp_path / "agents.yaml").write_text(yaml.safe_dump({"agents": {
        "pixel": {"token_sha256": hash_token(MOB), "profile": "mobile",
                  "allowed_dirs": [str(tmp_path / "ws")]}}}))
    (tmp_path / "config.yaml").write_text(yaml.safe_dump({
        "allowed_dirs": [str(tmp_path / "ws")], "audit_log": str(tmp_path / "audit.log"),
        "agents_file": "agents.yaml"}))
    guard = create_app(load_config(str(tmp_path / "config.yaml")))
    with TestClient(guard) as c:
        yield c


def test_missions_stub_requires_auth_and_reports_501(env):
    assert env.post("/v1/missions", json={}).status_code == 401
    r = env.post("/v1/missions", json={}, headers=H(MOB))
    assert r.status_code == 501 and "Oct 28" in r.json()["detail"]


def test_sessions_stub_sends_error_event(env):
    with env.websocket_connect("/v1/sessions", headers=H(MOB)) as ws:
        msg = ws.receive_json()
    assert msg["type"] == "error" and "Oct 27" in msg["detail"]


def test_sessions_stub_rejects_anonymous(env):
    with pytest.raises(WebSocketDisconnect) as e:
        with env.websocket_connect("/v1/sessions") as ws:
            ws.receive_json()
    assert e.value.code == 4401
