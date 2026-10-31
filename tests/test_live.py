"""Gemini Live ephemeral token endpoint (/v1/live/token) and ask target `extra` fields."""
import json

import httpx
import pytest
import yaml
from fastapi.testclient import TestClient

from zoth_relay.config import hash_token, load_config
from zoth_relay.server import create_app

MOB = "zr_mobile_" + "m" * 40
EXEC = "zr_exec_" + "e" * 40
REAL_KEY = "AIza-test-real-key-0000"


def H(t):
    return {"Authorization": f"Bearer {t}"}


def make(tmp_path, live=True, status=200, name="auth_tokens/abc123", live_fields=None):
    (tmp_path / "ws").mkdir(exist_ok=True)
    (tmp_path / "agents.yaml").write_text(yaml.safe_dump({"agents": {
        "pixel": {"token_sha256": hash_token(MOB), "profile": "mobile", "allowed_dirs": [str(tmp_path / "ws")]},
        "ex": {"token_sha256": hash_token(EXEC), "profile": "exec", "allowed_dirs": [str(tmp_path / "ws")]}}}))
    cfg = {"allowed_dirs": [str(tmp_path / "ws")], "audit_log": str(tmp_path / "audit.log"), "agents_file": "agents.yaml",
           "ask_targets": {"g": {"base_url": "https://up.example/v1", "model": "gemini-2.5-flash",
                                 "api_key_env": "LIVE_KEY", "extra": {"reasoning_effort": "none", "model": "evil"}}}}
    if live:
        cfg["live"] = {"model": "models/gemini-3.8-live", "api_key_env": "LIVE_KEY", "voice": "Puck"}
        cfg["live"].update(live_fields or {})
    (tmp_path / "config.yaml").write_text(yaml.safe_dump(cfg))
    seen = []

    def handler(req: httpx.Request):
        seen.append({"url": str(req.url), "headers": dict(req.headers), "body": json.loads(req.content)})
        if "chat/completions" in str(req.url):
            return httpx.Response(200, text='data: {"choices":[{"delta":{"content":"ok"}}]}\n\ndata: [DONE]\n\n')
        if status != 200:
            return httpx.Response(status, text="quota")
        return httpx.Response(200, json={"name": name}, headers={"date": "Sun, 18 Oct 2026 04:00:00 GMT"})

    guard = create_app(load_config(str(tmp_path / "config.yaml")))
    guard.app.state.live_transport = guard.app.state.ask_transport = httpx.MockTransport(handler)
    return guard, seen


@pytest.fixture(autouse=True)
def key(monkeypatch):
    monkeypatch.setenv("LIVE_KEY", REAL_KEY)


def test_live_token_minted_and_locked(tmp_path):
    guard, seen = make(tmp_path)
    with TestClient(guard) as c:
        r = c.post("/v1/live/token", json={"mission": "networking at a meetup"}, headers=H(MOB))
    assert r.status_code == 200 and r.headers["cache-control"] == "no-store"
    d = r.json()
    assert d["token"] == "auth_tokens/abc123" and d["model"] == "gemini-3.8-live"
    # expiry derived from Google's Date header, not the local clock
    assert d["expires_at"] == "2026-10-18T04:30:00Z" and d["new_session_expires_at"] == "2026-10-18T04:01:00Z"
    assert d["ws_url"].endswith("v1alpha.GenerativeService.BidiGenerateContentConstrained")
    assert d["ws_url"].startswith("wss://generativelanguage.googleapis.com/")
    up = seen[-1]
    assert up["url"] == "https://generativelanguage.googleapis.com/v1alpha/auth_tokens"
    assert up["headers"]["x-goog-api-key"] == REAL_KEY
    setup = up["body"]["bidiGenerateContentSetup"]
    assert up["body"]["uses"] == 1 and setup["model"] == "models/gemini-3.8-live"
    assert setup["generationConfig"]["responseModalities"] == ["AUDIO"]
    assert setup["generationConfig"]["speechConfig"]["voiceConfig"]["prebuiltVoiceConfig"]["voiceName"] == "Puck"
    assert "networking at a meetup" in setup["systemInstruction"]["parts"][0]["text"]
    assert REAL_KEY not in r.text


def test_live_token_no_body_and_voice_override(tmp_path):
    guard, seen = make(tmp_path)
    with TestClient(guard) as c:
        assert c.post("/v1/live/token", headers=H(MOB)).status_code == 200
        assert c.post("/v1/live/token", json={"voice": "Kore"}, headers=H(MOB)).status_code == 200
        assert c.post("/v1/live/token", json={"voice": "bad voice!"}, headers=H(MOB)).status_code == 422
    assert seen[-1]["body"]["bidiGenerateContentSetup"]["generationConfig"]["speechConfig"][
        "voiceConfig"]["prebuiltVoiceConfig"]["voiceName"] == "Kore"


def test_live_token_auth_and_profile(tmp_path):
    guard, _ = make(tmp_path)
    with TestClient(guard) as c:
        assert c.post("/v1/live/token").status_code == 401
        assert c.post("/v1/live/token", headers=H(EXEC)).status_code == 403


def test_live_disabled_and_missing_key(tmp_path, monkeypatch):
    guard, _ = make(tmp_path, live=False)
    with TestClient(guard) as c:
        assert c.post("/v1/live/token", headers=H(MOB)).status_code == 404
    (tmp_path / "b").mkdir()
    guard, _ = make(tmp_path / "b")
    monkeypatch.delenv("LIVE_KEY")
    with TestClient(guard) as c:
        r = c.post("/v1/live/token", headers=H(MOB))
        assert r.status_code == 503 and "LIVE_KEY" in r.json()["detail"]


@pytest.mark.parametrize("status,name", [(429, "x"), (200, "not-a-token")])
def test_live_upstream_failures(tmp_path, status, name):
    guard, _ = make(tmp_path, status=status, name=name)
    with TestClient(guard) as c:
        assert c.post("/v1/live/token", headers=H(MOB)).status_code == 502


def test_audit_never_contains_token(tmp_path):
    guard, _ = make(tmp_path, name="auth_tokens/supersecret999")
    with TestClient(guard) as c:
        c.post("/v1/live/token", headers=H(MOB))
    log = (tmp_path / "audit.log").read_text()
    assert "supersecret999" not in log and REAL_KEY not in log and '"live_token"' in log


def test_ask_target_extra_fields_do_not_override_core(tmp_path):
    guard, seen = make(tmp_path)
    with TestClient(guard) as c:
        r = c.post("/v1/ask", json={"prompt": "hi", "agent": "g", "stream": False}, headers=H(MOB))
    assert r.status_code == 200
    body = seen[-1]["body"]
    assert body["reasoning_effort"] == "none" and body["model"] == "gemini-2.5-flash"


def test_live_output_language_pinned(tmp_path):
    """The guide must speak the relay's language, not whatever it hears in the room."""
    guard, seen = make(tmp_path)
    with TestClient(guard) as c:
        assert c.post("/v1/live/token", headers=H(MOB)).status_code == 200
    setup = seen[-1]["body"]["bidiGenerateContentSetup"]
    assert setup["generationConfig"]["speechConfig"]["languageCode"] == "en-US"
    assert setup["generationConfig"]["speechConfig"]["voiceConfig"][
        "prebuiltVoiceConfig"]["voiceName"] == "Puck"
    assert "Always speak English (en-US)" in setup["systemInstruction"]["parts"][0]["text"]


def test_live_language_pin_survives_custom_system_and_can_be_disabled(tmp_path):
    custom = "You are a scout."
    guard, seen = make(tmp_path, live_fields={"system": custom})
    with TestClient(guard) as c:
        assert c.post("/v1/live/token", headers=H(MOB)).status_code == 200
    text = seen[-1]["body"]["bidiGenerateContentSetup"]["systemInstruction"]["parts"][0]["text"]
    assert text.startswith(custom) and "Always speak English (en-US)" in text

    (tmp_path / "off").mkdir()
    guard, seen = make(tmp_path / "off", live_fields={"language_code": ""})
    with TestClient(guard) as c:
        assert c.post("/v1/live/token", headers=H(MOB)).status_code == 200
    setup = seen[-1]["body"]["bidiGenerateContentSetup"]
    assert "languageCode" not in setup["generationConfig"]["speechConfig"]
    assert "Always speak" not in setup["systemInstruction"]["parts"][0]["text"]
