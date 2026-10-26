"""FIELDKIT D3: Missions API + structured planner + step tracker.

Planner (Wonder) turns one sentence into a JSON checklist; tracker consumes
typed events, never regex over prose. Upstreams mocked; no secrets/network.
"""
import json

import httpx
import pytest
import yaml
from fastapi.testclient import TestClient

from zoth_relay.config import hash_token, load_config
from zoth_relay.missions import MissionStore, parse_checklist
from zoth_relay.providers import OpenAICompatProvider
from zoth_relay.server import create_app

MOB = "zr_mobile_" + "m" * 40
EXEC = "zr_exec_" + "e" * 40
PLAN_JSON = json.dumps({"steps": ["Clear the bench", "Loosen the left clamp",
                                   "Swap the filter", "Tighten and test"]})


def H(t):
    return {"Authorization": f"Bearer {t}"}


def sse(payload_text):
    return "".join(f"data: {json.dumps({'choices': [{'delta': {'content': p}}]})}\n\n"
                   for p in (payload_text[:10], payload_text[10:])) + "data: [DONE]\n\n"


@pytest.fixture
def env(tmp_path, monkeypatch):
    monkeypatch.setenv("FAKE_KEY", "test-key-123")
    (tmp_path / "ws").mkdir()
    (tmp_path / "agents.yaml").write_text(yaml.safe_dump({"agents": {
        "pixel": {"token_sha256": hash_token(MOB), "profile": "mobile",
                  "allowed_dirs": [str(tmp_path / "ws")]},
        "ex": {"token_sha256": hash_token(EXEC), "profile": "exec",
               "allowed_dirs": [str(tmp_path / "ws")]},
    }}))
    (tmp_path / "config.yaml").write_text(yaml.safe_dump({
        "allowed_dirs": [str(tmp_path / "ws")], "audit_log": str(tmp_path / "audit.log"),
        "agents_file": "agents.yaml"}))
    seen = []

    def handler(req: httpx.Request):
        body = json.loads(req.content)
        seen.append(body)
        prompt = body["messages"][-1]["content"]
        if "Current step" in prompt:               # tracker assess call
            out = json.dumps({"event": "step_done", "say": "Clamp is loose. Next."})
        else:                                      # planner call
            out = PLAN_JSON
        return httpx.Response(200, text=sse(out), headers={"content-type": "text/event-stream"})

    guard = create_app(load_config(str(tmp_path / "config.yaml")))
    prov = OpenAICompatProvider("wonder", "https://up.example/v1", "m-wonder",
                                api_key_env="FAKE_KEY")
    guard.app.state.session_providers = {"wonder": prov, "spark": prov}
    guard.app.state.session_transport = httpx.MockTransport(handler)
    with TestClient(guard) as c:
        yield {"c": c, "seen": seen}


# ------------------------------------------------------------ pure parsing
def test_parse_checklist_variants():
    assert parse_checklist(PLAN_JSON) == ["Clear the bench", "Loosen the left clamp",
                                          "Swap the filter", "Tighten and test"]
    assert parse_checklist("```json\n" + PLAN_JSON + "\n```")[0] == "Clear the bench"
    assert parse_checklist("1. First\n2. Second\n- Third") == ["First", "Second", "Third"]
    assert parse_checklist("") == []
    many = json.dumps({"steps": [f"s{i}" for i in range(30)]})
    assert len(parse_checklist(many)) == 12          # capped


def test_store_advance_skip_complete():
    st = MissionStore()
    m = st.create("fix pump", ["a", "b"])
    assert m.current == 1 and not m.complete
    st.apply_event(m.id, "advance")
    assert m.steps[0].status == "done" and m.current == 2
    st.apply_event(m.id, "skip")
    assert m.steps[1].status == "skipped" and m.complete


# ------------------------------------------------------------ REST API
def test_missions_auth_and_create(env):
    c = env["c"]
    assert c.post("/v1/missions", json={"goal": "x"}).status_code == 401
    assert c.post("/v1/missions", json={"goal": "x"}, headers=H(EXEC)).status_code == 403
    r = c.post("/v1/missions", json={"goal": "replace the air filter"}, headers=H(MOB))
    assert r.status_code == 201
    body = r.json()
    assert body["goal"] == "replace the air filter" and body["provider"] == "wonder"
    assert [s["text"] for s in body["steps"]] == ["Clear the bench", "Loosen the left clamp",
                                                  "Swap the filter", "Tighten and test"]
    assert body["steps"][0]["status"] == "active" and body["current"] == 1


def test_missions_get_events_404(env):
    c = env["c"]
    mid = c.post("/v1/missions", json={"goal": "g"}, headers=H(MOB)).json()["id"]
    st = c.get(f"/v1/missions/{mid}", headers=H(MOB)).json()
    assert st["current"] == 1 and st["complete"] is False
    r = c.post(f"/v1/missions/{mid}/events", json={"type": "advance"}, headers=H(MOB)).json()
    assert r["event"] == "advanced" and r["mission"]["current"] == 2
    assert "Loosen" in r["say"]
    r = c.post(f"/v1/missions/{mid}/events", json={"type": "bogus"}, headers=H(MOB))
    assert r.status_code == 400
    assert c.get("/v1/missions/nope", headers=H(MOB)).status_code == 404


async def test_tracker_assess_typed_event(env):
    c = env["c"]
    mid = c.post("/v1/missions", json={"goal": "g"}, headers=H(MOB)).json()["id"]
    r = c.post(f"/v1/missions/{mid}/events",
               json={"type": "assess", "observation": "bench is clear now"},
               headers=H(MOB)).json()
    assert r["event"] == "step_done" and r["mission"]["current"] == 2
    assert r["say"] == "Clamp is loose. Next."


def test_session_surfaces_mission_state(env):
    c = env["c"]
    mid = c.post("/v1/missions", json={"goal": "replace the air filter"},
                 headers=H(MOB)).json()["id"]
    with c.websocket_connect("/v1/sessions", headers=H(MOB)) as ws:
        ws.send_json({"type": "hello", "provider": "spark", "mission_id": mid})
        ready = ws.receive_json()
        assert ready["type"] == "ready" and ready["mission"]["id"] == mid
        assert ready["mission"]["current"] == 1 and ready["mission"]["total"] == 4
        ws.send_json({"type": "text", "text": "what now"})
        evs = []
        for _ in range(10):
            ev = ws.receive_json()
            evs.append(ev)
            if ev["type"] == "done":
                break
        state = ws.receive_json()
        assert state["mission"]["step"] == "Clear the bench"
    # the turn's system prefix carried goal + current step upstream
    system = env["seen"][-1]["messages"][0]["content"]
    assert "replace the air filter" in system and "Clear the bench" in system
