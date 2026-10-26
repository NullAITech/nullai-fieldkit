"""FIELDKIT D2: real Sessions API (WS /v1/sessions).

Provider-routed turns, server-side frame diff, bounded context with cached
prefix, per-turn first_ms/ms metrics. Upstreams mocked; no secrets/network.
"""
import asyncio
import base64
import json

import httpx
import pytest
import yaml
from fastapi.testclient import TestClient
from starlette.websockets import WebSocketDisconnect

from zoth_relay.config import hash_token, load_config
from zoth_relay.providers import OpenAICompatProvider
from zoth_relay.server import create_app
from zoth_relay.sessions import Session

PNG = base64.b64encode(b"\x89PNG\r\n\x1a\n" + b"\x00" * 64).decode()
PNG2 = base64.b64encode(b"\x89PNG\r\n\x1a\n" + b"\x01" * 64).decode()
MOB = "zr_mobile_" + "m" * 40
EXEC = "zr_exec_" + "e" * 40


def H(t):
    return {"Authorization": f"Bearer {t}"}


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
        seen.append(json.loads(req.content))
        sse = "".join(f"data: {json.dumps({'choices': [{'delta': {'content': p}}]})}\n\n"
                      for p in ("Go ", "left."))
        return httpx.Response(200, text=sse + "data: [DONE]\n\n",
                              headers={"content-type": "text/event-stream"})

    guard = create_app(load_config(str(tmp_path / "config.yaml")))
    guard.app.state.session_providers = {
        "spark": OpenAICompatProvider("spark", "https://up.example/v1", "m1",
                                      api_key_env="FAKE_KEY"),
        "nokey": OpenAICompatProvider("nokey", "https://up.example/v1", "m2",
                                      api_key_env="DEFINITELY_MISSING_KEY"),
    }
    guard.app.state.session_transport = httpx.MockTransport(handler)
    with TestClient(guard) as c:
        yield {"c": c, "seen": seen}


def until_done(ws, limit=12):
    evs = []
    for _ in range(limit):
        ev = ws.receive_json()
        evs.append(ev)
        if ev["type"] in ("done", "error"):
            break
    return evs


def test_missions_requires_auth(env):
    # Real Missions API coverage lives in test_missions.py (D3); here: auth wall.
    assert env["c"].post("/v1/missions", json={"goal": "x"}).status_code == 401


def test_sessions_rejects_anonymous_and_wrong_profile(env):
    with pytest.raises(WebSocketDisconnect) as e:
        with env["c"].websocket_connect("/v1/sessions") as ws:
            ws.receive_json()
    assert e.value.code == 4401
    with pytest.raises(WebSocketDisconnect) as e:
        with env["c"].websocket_connect("/v1/sessions", headers=H(EXEC)) as ws:
            ws.receive_json()
    assert e.value.code == 4403


def test_sessions_unknown_and_unconfigured_provider(env):
    with env["c"].websocket_connect("/v1/sessions", headers=H(MOB)) as ws:
        ws.send_json({"type": "hello", "provider": "nope"})
        ev = ws.receive_json()
        assert ev["type"] == "error" and "unknown provider" in ev["detail"]
    with env["c"].websocket_connect("/v1/sessions", headers=H(MOB)) as ws:
        ws.send_json({"type": "hello", "provider": "nokey"})
        ev = ws.receive_json()
        assert ev["type"] == "error" and "not configured" in ev["detail"]


def test_speech_turn_streams_with_metrics(env):
    with env["c"].websocket_connect("/v1/sessions", headers=H(MOB)) as ws:
        ws.send_json({"type": "hello", "provider": "spark", "mission": "fix pump"})
        ready = ws.receive_json()
        assert ready["type"] == "ready" and ready["model"] == "m1"
        ws.send_json({"type": "speech", "text": "what now", "final": True})
        evs = until_done(ws)
        assert evs[0]["type"] == "start"
        assert "".join(e["text"] for e in evs if e["type"] == "delta") == "Go left."
        done = evs[-1]
        assert done["type"] == "done" and "first_ms" in done and "ms" in done
        state = ws.receive_json()
        assert state["type"] == "state" and state["turns"] == 1
    msgs = env["seen"][-1]["messages"]
    assert msgs[0]["role"] == "system" and "fix pump" in msgs[0]["content"]
    assert msgs[-1]["content"] == "what now"


def test_frame_diff_sends_only_changed_frames(env):
    with env["c"].websocket_connect("/v1/sessions", headers=H(MOB)) as ws:
        ws.send_json({"type": "hello", "provider": "spark"})
        ws.receive_json()
        ws.send_json({"type": "frame", "image": PNG})
        ws.send_json({"type": "speech", "text": "one", "final": True})
        until_done(ws)
        first = env["seen"][-1]["messages"][-1]["content"]
        assert isinstance(first, list) and first[1]["image_url"]["url"].startswith("data:image/png")
        st = ws.receive_json()
        ws.send_json({"type": "frame", "image": PNG})          # identical frame
        ws.send_json({"type": "speech", "text": "two", "final": True})
        until_done(ws)
        assert env["seen"][-1]["messages"][-1]["content"] == "two"   # no image resent
        st = ws.receive_json()
        assert st["frames_received"] == 2 and st["frames_changed"] == 1
        ws.send_json({"type": "frame", "image": PNG2})          # changed frame
        ws.send_json({"type": "speech", "text": "three", "final": True})
        until_done(ws)
        third = env["seen"][-1]["messages"][-1]["content"]
        assert isinstance(third, list) and third[1]["image_url"]["url"].startswith("data:image/png")


def test_bounded_context_caps_history(env):
    with env["c"].websocket_connect("/v1/sessions", headers=H(MOB)) as ws:
        ws.send_json({"type": "hello", "provider": "spark", "mission": "soak",
                      "history_budget": 3000})
        ws.receive_json()
        last_state = None
        for i in range(30):
            ws.send_json({"type": "text", "text": f"turn {i} " + "x" * 200})
            until_done(ws)
            last_state = ws.receive_json()
        assert last_state["history_chars"] <= last_state["history_budget"]
        assert last_state["turns"] == 30
    msgs = env["seen"][-1]["messages"]
    hist_chars = sum(len(str(m["content"])) for m in msgs[1:-1])
    assert hist_chars <= last_state["history_budget"] + 500
    assert msgs[0]["role"] == "system" and "soak" in msgs[0]["content"]
    assert len(msgs) < 62        # 30 turns did NOT replay 30x2 messages


async def test_barge_cancels_active_turn():
    sent = []

    async def send(obj):
        sent.append(obj)

    class SlowProvider:
        name, model = "slow", "m-slow"

        async def stream_events(self, turn, request_id, transport=None):
            yield {"type": "start", "id": request_id, "agent": "slow", "model": "m-slow"}
            yield {"type": "delta", "text": "first "}
            await asyncio.sleep(30)
            yield {"type": "delta", "text": "never"}

    s = Session(SlowProvider(), send)
    task = asyncio.create_task(s.run_turn("go", "speech"))
    await asyncio.sleep(0.1)
    await s.barge()
    await asyncio.sleep(0.1)
    assert task.cancelled() or task.done()
    kinds = [e["type"] for e in sent]
    assert "stop" in kinds and "done" not in kinds
    assert "".join(e.get("text", "") for e in sent if e["type"] == "delta") == "first "
