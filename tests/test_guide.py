"""/v1/guide/turn: Hermes (+ Nous fallback) and local vision + AGY. No network: mocked transports."""
import base64, json, sys
from pathlib import Path

import httpx, pytest, yaml
from fastapi.testclient import TestClient

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from zoth_relay.config import hash_token, load_config  # noqa: E402
from zoth_relay.guide import clean_reply, similar  # noqa: E402
from zoth_relay.server import create_app  # noqa: E402

TOK = "zr_phone_" + "p" * 40
RO = "zr_reader_" + "r" * 40
JPEG = base64.b64encode(b"\xff\xd8\xff\xe0" + b"0" * 200).decode()


def H(t=TOK):
    return {"Authorization": f"Bearer {t}"}



def sse_text(text):
    return f"data: {json.dumps({'choices': [{'delta': {'content': text}}]})}\n\ndata: [DONE]\n\n"


@pytest.fixture
def env(tmp_path, monkeypatch):
    monkeypatch.setenv("API_SERVER_KEY", "k" * 40)
    monkeypatch.delenv("NOUS_API_KEY", raising=False)
    (tmp_path / "ws").mkdir()
    (tmp_path / "agents.yaml").write_text(yaml.safe_dump({"agents": {
        "phone": {"token_sha256": hash_token(TOK), "profile": "mobile", "allowed_dirs": [str(tmp_path / "ws")]},
        "reader": {"token_sha256": hash_token(RO), "profile": "read-only", "allowed_dirs": [str(tmp_path / "ws")]}}}))
    (tmp_path / "config.yaml").write_text(yaml.safe_dump({
        "allowed_dirs": [str(tmp_path / "ws")], "audit_log": str(tmp_path / "audit.log"), "agents_file": "agents.yaml",
        "guide": {"hermes": {}, "fallback": {},
                  "local_vision": {"min_interval": 0, "hot_c": 90}, "agy": {"min_interval": 0}}}))
    return {"cfg": load_config(str(tmp_path / "config.yaml")), "tmp": tmp_path}


def make(env, handler, agy=None):
    app = create_app(env["cfg"])
    inner = app.app
    calls = []

    def h(req):
        calls.append(req)
        return handler(req)
    inner.state.guide_transport = httpx.MockTransport(h)
    prompts = []

    async def runner(p):
        prompts.append(p)
        return agy(p) if agy else "Check the label on the left shelf."
    inner.state.agy_runner = runner
    return app, calls, prompts


def test_requires_token_and_guide_cap(env):
    app, _, _ = make(env, lambda r: httpx.Response(500))
    with TestClient(app) as c:
        assert c.post("/v1/guide/turn", json={}).status_code == 401
        assert c.post("/v1/guide/turn", json={}, headers=H(RO)).status_code == 403


def test_hermes_turn_sends_frame_speech_mission_and_session(env):
    def handler(req):
        assert req.url.host == "127.0.0.1" and req.url.port == 8642
        assert req.headers["authorization"] == "Bearer " + "k" * 40
        assert req.headers["x-hermes-session-id"] == "zoth-guide-phone:s1"
        body = json.loads(req.content)
        assert body["model"] == "hermes-agent" and body["max_tokens"] == 80
        assert "Mission: find the router" in body["messages"][0]["content"]
        parts = body["messages"][-1]["content"]
        assert "where is it" in parts[0]["text"] and parts[1]["image_url"]["url"].startswith("data:image/jpeg;base64,")
        return httpx.Response(200, text=sse_text("**Look** behind the TV. It has blue lights. Extra third sentence."))
    app, calls, _ = make(env, handler)
    with TestClient(app) as c:
        r = c.post("/v1/guide/turn", headers=H(), json={"session": "s1", "image": JPEG, "speech": "where is it",
                                                        "mission": "find the router"})
    assert r.status_code == 200, r.text
    j = r.json()
    assert j["speak"] and j["text"] == "Look behind the TV. It has blue lights." and j["via"] == "hermes"
    log = (env["tmp"] / "audit.log").read_text()
    assert "where is it" not in log and "guide_turn" in log and JPEG[:20] not in log


def test_hermes_silent_reply_is_not_spoken(env):
    app, _, _ = make(env, lambda r: httpx.Response(200, text=sse_text("SILENT")))
    with TestClient(app) as c:
        j = c.post("/v1/guide/turn", headers=H(), json={"image": JPEG}).json()
    assert j["speak"] is False and j["text"] == ""


def test_falls_back_to_nous_when_hermes_down(env, monkeypatch):
    monkeypatch.setenv("NOUS_API_KEY", "n" * 40)
    def handler(req):
        if req.url.port == 8642:
            raise httpx.ConnectError("down")
        assert req.url.host == "inference-api.nousresearch.com"
        b = json.loads(req.content)
        assert b["model"] == "stepfun/step-3.7-flash:free" and isinstance(b["messages"][-1]["content"], str)
        return httpx.Response(200, text=sse_text("Turn left at the door."))
    app, _, _ = make(env, handler)
    with TestClient(app) as c:
        j = c.post("/v1/guide/turn", headers=H(), json={"image": JPEG, "speech": "which way"}).json()
    assert j["via"] == "nous" and j["text"] == "Turn left at the door."


def test_hermes_down_without_fallback_key_is_clear_error(env):
    def handler(req):
        raise httpx.ConnectError("down")
    app, _, _ = make(env, handler)
    with TestClient(app) as c:
        r = c.post("/v1/guide/turn", headers=H(), json={"speech": "hi"})
    assert r.status_code == 502 and "hermes unreachable" in r.json()["detail"]


def test_local_agy_captions_then_agy_suggests(env):
    def handler(req):
        assert req.url.port == 11434 and req.url.path == "/api/chat"
        b = json.loads(req.content)
        assert b["model"] == "moondream" and b["options"]["num_thread"] == 4 and b["messages"][0]["images"]
        return httpx.Response(200, json={"message": {"content": "A kitchen counter with a red kettle."}})
    app, calls, prompts = make(env, handler)
    with TestClient(app) as c:
        j = c.post("/v1/guide/turn", headers=H(), json={"mode": "local_agy", "image": JPEG,
                                                        "mission": "make tea"}).json()
        # same scene again: caption deduped, no unprompted AGY call
        j2 = c.post("/v1/guide/turn", headers=H(), json={"mode": "local_agy", "image": JPEG}).json()
        # speech always triggers AGY
        j3 = c.post("/v1/guide/turn", headers=H(), json={"mode": "local_agy", "speech": "what now", "mission": "make tea"}).json()
    assert j["caption"] == "A kitchen counter with a red kettle." and j["speak"] and j["model"] == "agy"
    assert j2["speak"] is False and j2["transcript_len"] == 1
    assert j3["speak"] and len(prompts) == 2
    assert "red kettle" in prompts[1] and "make tea" in prompts[1] and "what now" in prompts[1]
    assert "Check the label" in prompts[1]  # recent suggestions are fed back to avoid repeats


def test_local_pauses_captioning_when_hot(env):
    app, calls, prompts = make(env, lambda r: httpx.Response(500))
    with TestClient(app) as c:
        c.post("/v1/guide/turn", headers=H(), json={"mode": "local_agy"})  # create guide
        app.app.state.guide.temp_reader = lambda: 95.0
        j = c.post("/v1/guide/turn", headers=H(), json={"mode": "local_agy", "image": JPEG}).json()
    assert j["skipped"] == "hot" and j["temp_c"] == 95.0 and not calls and not prompts


def test_missing_ollama_model_message(env):
    app, _, _ = make(env, lambda r: httpx.Response(404, text="model not found"))
    with TestClient(app) as c:
        r = c.post("/v1/guide/turn", headers=H(), json={"mode": "local_agy", "image": JPEG})
    assert r.status_code == 503 and "ollama pull moondream" in r.json()["detail"]


def test_bad_mode_and_bad_image(env):
    app, _, _ = make(env, lambda r: httpx.Response(500))
    with TestClient(app) as c:
        assert c.post("/v1/guide/turn", headers=H(), json={"mode": "x"}).status_code == 422
        assert c.post("/v1/guide/turn", headers=H(), json={"image": base64.b64encode(b"GIF89a").decode()}).status_code == 415


def test_not_enabled_without_guide_section(env, tmp_path):
    raw = yaml.safe_load((env["tmp"] / "config.yaml").read_text()); raw.pop("guide")
    (env["tmp"] / "config.yaml").write_text(yaml.safe_dump(raw))
    env["cfg"] = load_config(str(env["tmp"] / "config.yaml"))
    app, _, _ = make(env, lambda r: httpx.Response(500))
    with TestClient(app) as c:
        assert c.post("/v1/guide/turn", headers=H(), json={}).status_code == 404


def test_helpers():
    assert clean_reply("SILENT.") == ("", False)
    assert clean_reply("") == ("", False)
    assert clean_reply("`Go` now.")[0] == "Go now."
    assert similar("A red kettle on a counter", "a red kettle on the counter")
    assert not similar("a red kettle", "a blue car in a street")
