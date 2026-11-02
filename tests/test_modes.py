"""Spotter goal modes (Nov 2 2026): onboarding, persistence, and prompt injection."""
import json

import httpx
import yaml
from fastapi.testclient import TestClient

from zoth_relay.config import hash_token, load_config
from zoth_relay.modes import MODES, ModeStore, mode_prompt, onboard_step, parse_mode
from zoth_relay.providers import OpenAICompatProvider
from zoth_relay.server import create_app

MOB = "zr_mobile_" + "m" * 40
H = {"Authorization": f"Bearer {MOB}"}


def test_parse_mode_free_text():
    assert parse_mode("i'd like to network and meet random people") == "network"
    assert parse_mode("id like to fix this car") == "repair"
    assert parse_mode("go on an adventure, tell me where to go") == "adventure"
    assert parse_mode("adventure") == "adventure"
    assert parse_mode("teach me chess") == "custom"


def test_onboard_flow_is_stepwise():
    s = onboard_step([])
    assert not s["done"] and {c["key"] for c in s["choices"]} == set(MODES)
    s = onboard_step(["repair"])
    assert not s["done"] and s["mode"] == "repair" and "fixing" in s["question"]
    s = onboard_step(["repair", "2012 Civic"])
    assert not s["done"]
    s = onboard_step(["repair", "2012 Civic", "won't start"])
    assert s["done"] and s["mode"] == "repair" and s["answers"] == ["2012 Civic", "won't start"]
    assert s["map_hud"] is False
    assert onboard_step(["adventure", "walking", "food"])["map_hud"] is True
    c = onboard_step(["custom", "help me practice spanish"])
    assert c["done"] and c["goal"] == "help me practice spanish"


def test_store_persists_and_prompt(tmp_path):
    p = tmp_path / "m.json"
    st = ModeStore(str(p))
    assert st.get("pixel") is None and mode_prompt(None) == ""
    st.set("pixel", "fix my car", answers=["Civic", "no start"])
    again = ModeStore(str(p)).get("pixel")
    assert again.mode == "repair" and again.onboarded
    txt = mode_prompt(again)
    assert "Goal mode REPAIR" in txt and "Civic" in txt
    assert oct(p.stat().st_mode & 0o777) == "0o600"
    st.clear("pixel")
    assert ModeStore(str(p)).get("pixel") is None


def test_store_ignores_corrupt_file(tmp_path):
    p = tmp_path / "m.json"
    p.write_text("{not json")
    assert ModeStore(str(p)).get("x") is None
    p.write_text(json.dumps({"x": {"mode": "bogus"}}))
    assert ModeStore(str(p)).get("x") is None


def _env(tmp_path, monkeypatch, handler):
    monkeypatch.setenv("FAKE_KEY", "test-key-123")
    (tmp_path / "ws").mkdir()
    (tmp_path / "agents.yaml").write_text(yaml.safe_dump({"agents": {
        "pixel": {"token_sha256": hash_token(MOB), "profile": "mobile",
                  "allowed_dirs": [str(tmp_path / "ws")]}}}))
    (tmp_path / "config.yaml").write_text(yaml.safe_dump({
        "allowed_dirs": [str(tmp_path / "ws")], "audit_log": str(tmp_path / "a.log"),
        "agents_file": "agents.yaml"}))
    guard = create_app(load_config(str(tmp_path / "config.yaml")))
    prov = OpenAICompatProvider("wonder", "https://up.example/v1", "m", api_key_env="FAKE_KEY")
    guard.app.state.session_providers = {"wonder": prov, "spark": prov}
    guard.app.state.session_transport = httpx.MockTransport(handler)
    guard.app.state.modes = ModeStore(str(tmp_path / "modes.json"))
    return TestClient(guard)


def _sse(text):
    return httpx.Response(200, text=f"data: {json.dumps({'choices': [{'delta': {'content': text}}]})}\n\ndata: [DONE]\n\n",
                          headers={"content-type": "text/event-stream"})


def test_mode_endpoints_and_auth(tmp_path, monkeypatch):
    c = _env(tmp_path, monkeypatch, lambda r: _sse("ok"))
    assert c.get("/v1/mode").status_code == 401
    r = c.get("/v1/mode", headers=H)
    assert r.status_code == 200 and r.json()["mode"] is None and "adventure" in r.json()["modes"]
    r = c.post("/v1/mode/onboard", headers=H, json={"answers": []})
    assert r.json()["done"] is False and len(r.json()["choices"]) == 4
    r = c.post("/v1/mode/onboard", headers=H, json={"answers": ["tell me where to go", "walking", "views"]})
    j = r.json()
    assert j["done"] and j["state"]["mode"] == "adventure" and j["state"]["map_hud"] is True
    assert c.get("/v1/mode", headers=H).json()["mode"]["label"].startswith("Adventure")
    r = c.post("/v1/mode", headers=H, json={"mode": "network", "goal": "meet founders"})
    assert r.status_code == 200 and r.json()["mode"] == "network"
    audit = (tmp_path / "a.log").read_text()
    assert "mode_set" in audit and "meet founders" not in audit  # goal text never audited
    assert c.delete("/v1/mode", headers=H).json()["ok"]
    assert c.get("/v1/mode", headers=H).json()["mode"] is None


def test_mode_reaches_planner_prompt(tmp_path, monkeypatch):
    seen = []

    def handler(req):
        body = json.loads(req.content)
        seen.append(json.dumps(body["messages"]))
        return _sse(json.dumps({"steps": ["Pop the hood", "Check the battery"]}))

    c = _env(tmp_path, monkeypatch, handler)
    c.post("/v1/mode", headers=H, json={"mode": "repair", "answers": ["2012 Civic"]})
    r = c.post("/v1/missions", headers=H, json={"goal": "car won't start", "provider": "wonder",
                                                 "answers": ["no crank"]})
    assert r.status_code == 201
    assert any("Goal mode REPAIR" in s and "2012 Civic" in s for s in seen)


def test_mode_reaches_voice_session_prompt(tmp_path, monkeypatch):
    seen = []

    def handler(req):
        seen.append(json.loads(req.content))
        return _sse("Head north.")

    c = _env(tmp_path, monkeypatch, handler)
    c.post("/v1/mode", headers=H, json={"mode": "adventure"})
    with c.websocket_connect(f"/v1/sessions?token={MOB}") as ws:
        ws.send_json({"type": "hello", "provider": "spark"})
        assert ws.receive_json()["type"] == "ready"
        ws.send_json({"type": "speech", "text": "where should I go", "final": True})
        for _ in range(12):
            if ws.receive_json()["type"] in ("done", "error"):
                break
    sys_msgs = [m["content"] for b in seen for m in b["messages"] if m["role"] == "system"]
    assert any("Goal mode ADVENTURE" in (s if isinstance(s, str) else json.dumps(s)) for s in sys_msgs)


def test_pwa_serves_presence_and_map(tmp_path, monkeypatch):
    c = _env(tmp_path, monkeypatch, lambda r: _sse("ok"))
    page = c.get("/pwa").text
    for marker in ("/pwa/presence.js", "/pwa/maphud.js", "SET UP SPOTTER", "OpenStreetMap", 'id="pstyle"'):
        assert marker in page
    js = c.get("/pwa/presence.js")
    assert js.status_code == 200 and "javascript" in js.headers["content-type"]
    for marker in ("Presence", "0x2800", "braille", "dither", "lattice", "webdev.teknium.io"):
        assert marker in js.text
    m = c.get("/pwa/maphud.js")
    assert m.status_code == 200 and "OpenStreetMap contributors" in m.text
    assert "/pwa/presence.js" in c.get("/pwa/sw.js").text
