"""Mobile ask API: mobile profile scoping, image validation, streaming, privacy of audit log."""
import base64
import json
import socket

import httpx
import pytest
import yaml
from fastapi.testclient import TestClient

from zoth_relay.__main__ import bind_sockets, main
from zoth_relay.config import hash_token, load_config
from zoth_relay.server import create_app

PNG = base64.b64encode(b"\x89PNG\r\n\x1a\n" + b"\x00" * 64).decode()
JPEG = base64.b64encode(b"\xff\xd8\xff\xe0" + b"\x00" * 64).decode()
MOB = "zr_mobile_" + "m" * 40
OTHER = "zr_other_" + "o" * 40
EXEC = "zr_exec_" + "e" * 40


def H(t):
    return {"Authorization": f"Bearer {t}"}


@pytest.fixture
def menv(tmp_path, monkeypatch):
    monkeypatch.setenv("FAKE_KEY", "sk-test-upstream")
    monkeypatch.delenv("MISSING_KEY", raising=False)
    (tmp_path / "ws").mkdir()
    (tmp_path / "agents.yaml").write_text(yaml.safe_dump({"agents": {
        "pixel": {"token_sha256": hash_token(MOB), "profile": "mobile", "ask_targets": ["vis", "txt", "nokey"],
                  "allowed_dirs": [str(tmp_path / "ws")]},
        "other": {"token_sha256": hash_token(OTHER), "profile": "mobile", "allowed_dirs": [str(tmp_path / "ws")]},
        "ex": {"token_sha256": hash_token(EXEC), "profile": "exec", "allowed_dirs": [str(tmp_path / "ws")]},
    }}))
    (tmp_path / "config.yaml").write_text(yaml.safe_dump({
        "allowed_dirs": [str(tmp_path / "ws")], "audit_log": str(tmp_path / "audit.log"),
        "agents_file": "agents.yaml", "ask_default": "txt",
        "ask_targets": {
            "vis": {"base_url": "https://up.example/v1", "model": "vision-1", "api_key_env": "FAKE_KEY"},
            "txt": {"base_url": "http://127.0.0.1:11434/v1", "model": "tiny", "vision": False},
            "nokey": {"base_url": "https://up.example/v1", "model": "x", "api_key_env": "MISSING_KEY"},
        }}))
    seen = []

    def handler(req: httpx.Request):
        body = json.loads(req.content)
        seen.append({"url": str(req.url), "auth": req.headers.get("authorization"), "body": body})
        if body["model"] == "boom":
            return httpx.Response(500, text="upstream exploded")
        sse = "".join(f"data: {json.dumps({'choices': [{'delta': {'content': p}}]})}\n\n" for p in ("Go ", "left."))
        return httpx.Response(200, text=sse + "data: [DONE]\n\n", headers={"content-type": "text/event-stream"})

    cfg = load_config(str(tmp_path / "config.yaml"))
    guard = create_app(cfg)
    guard.app.state.ask_transport = httpx.MockTransport(handler)
    with TestClient(guard) as c:
        yield {"c": c, "seen": seen, "tmp": tmp_path}


def events(r):
    return [json.loads(l) for l in r.text.splitlines() if l.strip()]


def test_health_is_public(menv):
    assert menv["c"].get("/health").json() == {"ok": True}


def test_mobile_profile_cannot_touch_files_or_exec(menv):
    c, h = menv["c"], H(MOB)
    assert c.get("/v1/status", headers=h).status_code == 200
    assert c.get("/v1/dir", headers=h).status_code == 403
    assert c.get("/v1/file", params={"path": "x"}, headers=h).status_code == 403
    assert c.post("/v1/file", json={"path": "x", "content": "y"}, headers=h).status_code == 403
    assert c.post("/v1/exec/run", json={"command": "echo hi"}, headers=h).status_code == 403
    assert c.post("/v1/exec", json={"command": "echo hi"}, headers=h).status_code == 403


def test_non_mobile_profile_cannot_ask(menv):
    r = menv["c"].post("/v1/ask", json={"prompt": "hi"}, headers=H(EXEC))
    assert r.status_code == 403


def test_ask_requires_token(menv):
    assert menv["c"].post("/v1/ask", json={"prompt": "hi"}).status_code == 401


def test_list_agents_scoped_to_token(menv):
    r = menv["c"].get("/v1/agents", headers=H(MOB)).json()
    assert r["default"] == "vis"
    assert {a["name"]: a["configured"] for a in r["agents"]} == {"vis": True, "txt": True, "nokey": False}
    assert "api_key_env" not in json.dumps(r) and "sk-test" not in json.dumps(r)
    r2 = menv["c"].get("/v1/agents", headers=H(OTHER)).json()
    assert r2["default"] == "txt" and len(r2["agents"]) == 3


def test_ask_stream_with_image(menv):
    r = menv["c"].post("/v1/ask", json={"prompt": "What should I do?", "image": PNG, "mission": "networking"},
                       headers=H(MOB))
    assert r.status_code == 200 and r.headers["content-type"].startswith("application/x-ndjson")
    ev = events(r)
    assert ev[0]["type"] == "start" and ev[0]["agent"] == "vis"
    assert [e["text"] for e in ev if e["type"] == "delta"] == ["Go ", "left."]
    assert ev[-1]["type"] == "done" and ev[-1]["text"] == "Go left."
    up = menv["seen"][-1]
    assert up["url"] == "https://up.example/v1/chat/completions" and up["auth"] == "Bearer sk-test-upstream"
    user = up["body"]["messages"][-1]["content"]
    assert user[1]["image_url"]["url"].startswith("data:image/png;base64,")
    assert "networking" in up["body"]["messages"][0]["content"]


def test_ask_non_stream_and_data_uri(menv):
    r = menv["c"].post("/v1/ask", json={"prompt": "hi", "image": "data:image/jpeg;base64," + JPEG,
                                        "stream": False, "history": [{"role": "user", "content": "earlier"},
                                                                     {"role": "assistant", "content": "ok"}]},
                       headers=H(MOB))
    assert r.status_code == 200 and r.json()["text"] == "Go left."
    msgs = menv["seen"][-1]["body"]["messages"]
    assert [m["role"] for m in msgs] == ["system", "user", "assistant", "user"]


def test_text_only_target_drops_image(menv):
    r = menv["c"].post("/v1/ask", json={"prompt": "hi", "agent": "txt", "image": PNG, "stream": False}, headers=H(MOB))
    assert r.status_code == 200
    up = menv["seen"][-1]
    assert up["auth"] is None and up["body"]["messages"][-1]["content"] == "hi"


def test_target_scoping_and_unknown_and_unconfigured(menv):
    c = menv["c"]
    assert c.post("/v1/ask", json={"prompt": "hi", "agent": "nope"}, headers=H(MOB)).status_code == 404
    r = c.post("/v1/ask", json={"prompt": "hi", "agent": "nokey"}, headers=H(MOB))
    assert r.status_code == 503 and "MISSING_KEY" in r.json()["detail"]


def test_token_restricted_to_targets(menv, tmp_path):
    ag = yaml.safe_load((menv["tmp"] / "agents.yaml").read_text())
    ag["agents"]["pixel"]["ask_targets"] = ["txt"]
    (menv["tmp"] / "agents.yaml").write_text(yaml.safe_dump(ag))
    assert menv["c"].post("/v1/ask", json={"prompt": "hi", "agent": "vis"}, headers=H(MOB)).status_code == 403


@pytest.mark.parametrize("img,code", [("not base64!!", 400), (base64.b64encode(b"GIF89a....").decode(), 415)])
def test_bad_images(menv, img, code):
    r = menv["c"].post("/v1/ask", json={"prompt": "hi", "image": img}, headers=H(MOB))
    assert r.status_code == code


def test_upstream_error_streams_error_event(menv):
    cfg_p = menv["tmp"] / "config.yaml"
    guard_cfg = load_config(str(cfg_p))
    guard_cfg.ask_targets["vis"].model = "boom"
    guard = create_app(guard_cfg)
    guard.app.state.ask_transport = menv["c"].app.app.state.ask_transport
    with TestClient(guard) as c:
        ev = events(c.post("/v1/ask", json={"prompt": "hi"}, headers=H(MOB)))
        assert ev[-1]["type"] == "error" and ev[-1]["status"] == 502
        r = c.post("/v1/ask", json={"prompt": "hi", "stream": False}, headers=H(MOB))
        assert r.status_code == 502


def test_audit_logs_sizes_not_content(menv):
    menv["c"].post("/v1/ask", json={"prompt": "secret plan", "image": PNG, "stream": False}, headers=H(MOB))
    log = (menv["tmp"] / "audit.log").read_text()
    assert "secret plan" not in log and PNG not in log
    rec = [json.loads(l) for l in log.splitlines() if '"ask"' in l][-1]
    assert rec["target"] == "vis" and rec["image_bytes"] > 0 and rec["prompt_chars"] == len("secret plan")


def test_wildcard_hosts_rejected(tmp_path):
    (tmp_path / "config.yaml").write_text(yaml.safe_dump({"hosts": ["127.0.0.1", "0.0.0.0"],
                                                          "audit_log": str(tmp_path / "a.log")}))
    with pytest.raises(SystemExit):
        load_config(str(tmp_path / "config.yaml"))


def test_bind_sockets_freebind_non_local_address():
    s = socket.socket(); s.bind(("127.0.0.1", 0)); port = s.getsockname()[1]; s.close()
    socks = bind_sockets(["127.0.0.1", "100.64.255.254"], port)  # CGNAT addr not on this host
    try:
        assert {x.getsockname()[0] for x in socks} == {"127.0.0.1", "100.64.255.254"}
    finally:
        for x in socks:
            x.close()


def test_agent_add_mobile_writes_token_file(tmp_path, capsys):
    (tmp_path / "config.yaml").write_text(yaml.safe_dump({"agents_file": "agents.yaml", "allowed_dirs": [str(tmp_path / "ws")],
                                                          "audit_log": str(tmp_path / "a.log")}))
    tf = tmp_path / "sub" / "pixel.token"
    rc = main(["--config", str(tmp_path / "config.yaml"), "agent", "add", "pixel-mobile", "--profile", "mobile",
               "--ask-target", "grok", "--token-file", str(tf)])
    assert rc == 0
    tok = tf.read_text().strip()
    assert oct(tf.stat().st_mode & 0o777) == "0o600" and tok.startswith("zr_")
    assert tok not in capsys.readouterr().out
    ag = yaml.safe_load((tmp_path / "agents.yaml").read_text())["agents"]["pixel-mobile"]
    assert ag["profile"] == "mobile" and "agent-workspace" not in str(ag["allowed_dirs"]) and ag["ask_targets"] == ["grok"] and ag["token_sha256"] == hash_token(tok)
