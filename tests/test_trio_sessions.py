"""FIELDKIT D4: trio wired into sessions — proactive loop, pause, voice-focus
gate (wearer vs bystander), compass propagation, preset selection."""
import base64
import json

import httpx
import pytest
import yaml
from fastapi.testclient import TestClient

from zoth_relay.config import hash_token, load_config
from zoth_relay.providers import OpenAICompatProvider
from zoth_relay.server import create_app
from zoth_relay.sessions import Session
from zoth_relay.trio import Advisor, Director, Eyes

PNG = base64.b64encode(b"\x89PNG\r\n\x1a\n" + b"\x00" * 64).decode()
MOB = "zr_mobile_" + "m" * 40


def H(t):
    return {"Authorization": f"Bearer {t}"}


def make_provider():
    calls = {"n": 0}

    def handler(req: httpx.Request):
        calls["n"] += 1
        body = json.loads(req.content)
        content = body["messages"][-1]["content"]
        prompt = content[0]["text"] if isinstance(content, list) else content
        if "set of eyes" in prompt:
            text = "A red mower, filter cover off."
        elif "Propose up to 3" in prompt:
            text = json.dumps({"suggestions": [
                {"text": "Filter next", "priority": "progress", "say": "Pull the old filter out."}]})
        else:
            text = "Go left."
        sse = f"data: {json.dumps({'choices': [{'delta': {'content': text}}]})}\n\n" + "data: [DONE]\n\n"
        return httpx.Response(200, text=sse, headers={"content-type": "text/event-stream"})

    p = OpenAICompatProvider("spark", "https://up.example/v1", "m1")
    return p, httpx.MockTransport(handler), calls


def guided_session(sent, preset="get-it-done"):
    p, t, calls = make_provider()

    async def send(obj):
        sent.append(obj)

    s = Session(p, send, t)
    s.set_guide(Eyes(p, t), Advisor(p, t), Director(p, t), preset)
    return s, calls


async def test_proactive_tick_speaks_next_step():
    sent = []
    s, _ = guided_session(sent)
    s.on_frame(PNG)
    await s.proactive_tick()
    kinds = [e["type"] for e in sent]
    assert kinds[0] == "observation" and "mower" in sent[0]["text"]
    say = [e for e in sent if e["type"] == "say"]
    assert say and say[0]["text"] == "Pull the old filter out."


async def test_pause_and_quiet_preset_gate_speech():
    sent = []
    s, _ = guided_session(sent)
    s.set_paused(True)
    s.on_frame(PNG)
    await s.proactive_tick()
    assert not [e for e in sent if e["type"] in ("say", "observation")]
    s.set_paused(False)
    s.set_preset("custom", {"chattiness": 0.0})
    await s.proactive_tick()   # pending frame was consumed while paused? no: paused returns before consuming
    assert [e for e in sent if e["type"] == "observation"]
    assert not [e for e in sent if e["type"] == "say"]     # quiet preset gates progress


async def test_wearer_focus_ignores_bystanders():
    sent = []
    s, calls = guided_session(sent)
    await s.on_user_speech("and then he said the meeting moved", speaker="other")
    assert s.ignored_outside == 1 and calls["n"] == 0      # never reached a model
    assert sent[-1]["ignored_outside"] == 1
    await s.on_user_speech("what now", speaker="wearer")
    await s._task
    assert calls["n"] >= 1 and any(e["type"] == "start" for e in sent)


async def test_sound_direction_state_and_offaxis_gate():
    sent = []
    s, calls = guided_session(sent)
    s.on_sound(123, -100, "phone", -42.5)
    st = s.state()
    assert st["sound_heading"] == 123 and st["sound_relative"] == -100
    assert st["sound_source"] == "phone" and st["sound_level_db"] == -42.5
    await s.on_user_speech("side conversation at the next table",
                           speaker="unknown", sound_relative=-100)
    assert s.ignored_outside == 1 and calls["n"] == 0      # off-axis: no model call
    await s.on_user_speech("what now", speaker="unknown", sound_relative=5)
    await s._task
    assert calls["n"] >= 1                                 # on-axis voice proceeds


async def test_compass_state_and_shh():
    sent = []
    s, _ = guided_session(sent)
    s.on_compass(-10, "glasses")
    assert s.heading == 350 and s.heading_source == "glasses"
    st = s.state()
    assert st["heading"] == 350 and st["heading_source"] == "glasses" and st["guide"] is True
    await s.on_user_speech("shh")
    assert s.paused is True
    await s.on_user_speech("resume")
    assert s.paused is False


@pytest.fixture
def env(tmp_path, monkeypatch):
    monkeypatch.setenv("FAKE_KEY", "test-key-123")
    (tmp_path / "ws").mkdir()
    (tmp_path / "agents.yaml").write_text(yaml.safe_dump({"agents": {
        "pixel": {"token_sha256": hash_token(MOB), "profile": "mobile",
                  "allowed_dirs": [str(tmp_path / "ws")]}}}))
    (tmp_path / "config.yaml").write_text(yaml.safe_dump({
        "allowed_dirs": [str(tmp_path / "ws")], "audit_log": str(tmp_path / "audit.log"),
        "agents_file": "agents.yaml"}))
    p, transport, _ = make_provider()
    guard = create_app(load_config(str(tmp_path / "config.yaml")))
    guard.app.state.session_providers = {"spark": p, "wonder": p}
    guard.app.state.session_transport = transport
    with TestClient(guard) as c:
        yield c


def test_ws_guide_preset_compass_and_voice_gate(env):
    with env.websocket_connect("/v1/sessions", headers=H(MOB)) as ws:
        ws.send_json({"type": "hello", "provider": "spark", "guide": True, "preset": "teach-me"})
        ready = ws.receive_json()
        assert ready["guide"] is True and ready["preset"] == "teach-me"
        ws.send_json({"type": "compass", "heading": 42, "source": "glasses"})
        ws.send_json({"type": "sound", "heading": 84, "relative": 42,
                      "source": "phone", "level_db": -30.0})
        ws.send_json({"type": "speech", "text": "bystander chat", "final": True, "speaker": "other"})
        st = ws.receive_json()
        assert st["type"] == "state" and st["ignored_outside"] == 1
        assert st["heading"] == 42 and st["heading_source"] == "glasses"
        assert st["sound_heading"] == 84 and st["sound_relative"] == 42
        ws.send_json({"type": "preset", "name": "social"})
        st = ws.receive_json()
        assert st["preset"] == "social"
        ws.send_json({"type": "pause"})
        st = ws.receive_json()
        assert st["paused"] is True
