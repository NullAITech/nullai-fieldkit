"""Multi-agent scoping, revoke/rotate, REST v1, MCP (in-process + HTTP), OpenAPI, dashboard."""
import json
import subprocess
import sys
from pathlib import Path

import pytest
from fastapi.testclient import TestClient
from mcp.shared.memory import create_connected_server_and_client_session
from openapi_spec_validator import validate

from zoth_relay.core import Relay
from zoth_relay.mcp_server import build_mcp
from zoth_relay.openapi_actions import actions_schema
from zoth_relay.server import create_app

ROOT = Path(__file__).resolve().parents[1]


def H(t):
    return {"Authorization": f"Bearer {t}"}


@pytest.fixture
def client(env):
    with TestClient(create_app(env["cfg"])) as c:
        yield c


def audit(env):
    return [json.loads(l) for l in (env["tmp"] / "audit.log").read_text().splitlines()]


# ------------------------------------------------------------- auth / scoping
def test_no_token_and_bad_token(client):
    assert client.get("/v1/status").status_code == 401
    assert client.get("/v1/status", headers=H("x" * 40)).status_code == 401


def test_status_reports_agent(client, env):
    for prefix in ("", "/v1"):
        r = client.get(prefix + "/status", headers=H(env["tok"]["alpha"]))
        assert r.status_code == 200 and r.json()["agent"] == "alpha" and r.json()["profile"] == "exec"


def test_agent_a_cannot_reach_agent_b_dirs(client, env):
    a = H(env["tok"]["alpha"])
    secret = str(env["ws"] / "beta" / "secret.txt")
    assert client.get("/v1/file", params={"path": secret}, headers=a).status_code == 403
    assert client.get("/v1/file", params={"path": "../beta/secret.txt"}, headers=a).status_code == 403
    assert client.get("/v1/dir", params={"path": str(env["ws"] / "beta")}, headers=a).status_code == 403
    assert client.post("/v1/file", json={"path": str(env["ws"] / "beta" / "x"), "content": "hi"}, headers=a).status_code == 403
    assert client.post("/v1/exec/run", json={"command": "ls", "working_directory": str(env["ws"] / "beta")}, headers=a).status_code == 403
    # symlink escape
    (env["ws"] / "alpha" / "link").symlink_to(env["ws"] / "beta")
    assert client.get("/v1/file", params={"path": "link/secret.txt"}, headers=a).status_code == 403
    # beta can read its own
    r = client.get("/v1/file", params={"path": "secret.txt"}, headers=H(env["tok"]["beta"]))
    assert r.status_code == 200 and r.json()["content"] == "beta-only"


def test_profiles(client, env):
    b, rd = H(env["tok"]["beta"]), H(env["tok"]["reader"])
    assert client.post("/v1/exec/run", json={"command": "echo hi"}, headers=b).status_code == 403  # workspace: no exec
    assert client.post("/v1/file", json={"path": "w.txt", "content": "ok"}, headers=b).status_code == 200
    assert client.post("/v1/file", json={"path": "w2.txt", "content": "ok"}, headers=rd).status_code == 403  # read-only
    assert client.get("/v1/dir", headers=rd).status_code == 200
    ev = [e for e in audit(env) if e["event"] == "forbidden"]
    assert {e["agent"] for e in ev} == {"beta", "reader"}


def test_per_agent_rate_limit(client, env):
    rd = H(env["tok"]["reader"])
    codes = [client.get("/v1/status", headers=rd).status_code for _ in range(5)]
    assert codes[:3] == [200] * 3 and codes[3:] == [429, 429]
    assert client.get("/v1/status", headers=H(env["tok"]["alpha"])).status_code == 200  # others unaffected


def test_command_allowlist_and_blocklist(client, env):
    adm = H(env["tok"]["admin"])
    assert client.post("/v1/exec/run", json={"command": "echo ok | ls"}, headers=adm).status_code == 200
    r = client.post("/v1/exec/run", json={"command": "echo hi; cat /etc/passwd"}, headers=adm)
    assert r.status_code == 403 and "allowlist" in r.json()["detail"]
    a = H(env["tok"]["alpha"])
    assert client.post("/v1/exec/run", json={"command": "rm -rf /"}, headers=a).status_code == 403
    assert client.post("/v1/exec/run", json={"command": "cat /etc/shadow"}, headers=a).status_code == 403


def test_exec_run_and_ndjson_stream(client, env):
    a = H(env["tok"]["alpha"])
    r = client.post("/v1/exec/run", json={"command": "echo out; echo err >&2; echo $ZOTH_RELAY_AGENT; exit 3"}, headers=a)
    j = r.json()
    assert j["stdout"] == "out\nalpha\n" and j["stderr"] == "err\n" and j["exit_code"] == 3
    r = client.post("/v1/exec", json={"command": "echo s1; echo s2"}, headers=a)
    assert r.headers["content-type"].startswith("application/x-ndjson")
    lines = [json.loads(l) for l in r.text.splitlines()]
    assert "".join(l.get("data", "") for l in lines) == "s1\ns2\n" and lines[-1]["exit_code"] == 0
    t = client.post("/v1/exec/run", json={"command": "sleep 5", "timeout": 1}, headers=a).json()
    assert t["timed_out"] is True


def test_audit_tags_agent(client, env):
    client.get("/v1/status", headers=H(env["tok"]["alpha"]))
    client.get("/v1/dir", headers=H(env["tok"]["beta"]))
    client.get("/v1/status", headers=H("bogus" * 10))
    ev = audit(env)
    assert any(e["event"] == "status" and e["agent"] == "alpha" for e in ev)
    assert any(e["event"] == "list_dir" and e["agent"] == "beta" for e in ev)
    assert any(e["event"] == "auth_fail" for e in ev)
    assert not any(env["tok"]["alpha"] in json.dumps(e) for e in ev)  # tokens never logged


# ------------------------------------------------------------- CLI: add / revoke / rotate
def cli(env, *args):
    return subprocess.run([sys.executable, "-m", "zoth_relay", "--config", str(env["tmp"] / "config.yaml"), *args],
                          capture_output=True, text=True, cwd=ROOT)


def test_cli_add_list_revoke_rotate(client, env):
    r = cli(env, "agent", "add", "dots", "--profile", "workspace", "--dir", str(env["ws"] / "alpha"))
    assert r.returncode == 0, r.stderr
    tok = r.stdout.strip().splitlines()[-1].strip()
    assert tok.startswith("zr_") and tok not in (env["tmp"] / "agents.yaml").read_text()
    assert client.get("/v1/status", headers=H(tok)).json()["agent"] == "dots"
    assert "dots" in cli(env, "agent", "list").stdout
    assert cli(env, "agent", "add", "dots").returncode == 1  # duplicate
    # revoke takes effect without restart (hot reload)
    assert cli(env, "agent", "revoke", "dots").returncode == 0
    assert client.get("/v1/status", headers=H(tok)).status_code == 401
    # rotate: old token dead, new token works
    r = cli(env, "agent", "rotate", "dots")
    new = r.stdout.strip().splitlines()[-1].strip()
    assert new != tok
    assert client.get("/v1/status", headers=H(tok)).status_code == 401
    assert client.get("/v1/status", headers=H(new)).status_code == 200
    assert cli(env, "agent", "add", "Bad Name!").returncode == 2


# ------------------------------------------------------------- MCP
async def test_mcp_in_process_tools(env):
    relay = Relay(env["cfg"])
    alpha = relay.registry.lookup(env["tok"]["alpha"])
    srv = build_mcp(relay, alpha)
    async with create_connected_server_and_client_session(srv._mcp_server) as s:
        names = {t.name for t in (await s.list_tools()).tools}
        assert names == {"run_command", "read_file", "write_file", "list_dir", "status"}
        r = await s.call_tool("write_file", {"path": "m.txt", "content": "from-mcp"})
        assert not r.isError
        r = await s.call_tool("read_file", {"path": "m.txt"})
        assert r.structuredContent["content"] == "from-mcp"
        r = await s.call_tool("run_command", {"command": "cat m.txt"})
        assert r.structuredContent["stdout"] == "from-mcp" and r.structuredContent["exit_code"] == 0
        r = await s.call_tool("list_dir", {})
        assert "m.txt" in [e["name"] for e in r.structuredContent["entries"]]
        r = await s.call_tool("status", {})
        assert r.structuredContent["agent"] == "alpha"
        # scoping holds over MCP too
        r = await s.call_tool("read_file", {"path": str(env["ws"] / "beta" / "secret.txt")})
        assert r.isError and "403" in r.content[0].text
    assert any(e.get("via") == "mcp" and e.get("agent") == "alpha" for e in audit(env))


async def test_mcp_read_only_agent_cannot_exec(env):
    relay = Relay(env["cfg"])
    srv = build_mcp(relay, relay.registry.lookup(env["tok"]["reader"]))
    async with create_connected_server_and_client_session(srv._mcp_server) as s:
        r = await s.call_tool("run_command", {"command": "echo hi"})
        assert r.isError and "403" in r.content[0].text


MCP_H = {"Accept": "application/json, text/event-stream", "Content-Type": "application/json"}


def rpc(client, tok, method, params=None, id_=1):
    h = dict(MCP_H, **({"Authorization": f"Bearer {tok}"} if tok else {}))
    return client.post("/mcp", headers=h, json={"jsonrpc": "2.0", "id": id_, "method": method, "params": params or {}})


def test_mcp_http_auth_and_tools(client, env):
    assert rpc(client, None, "tools/list").status_code == 401
    assert rpc(client, "z" * 40, "tools/list").status_code == 401
    t = env["tok"]["beta"]
    init = rpc(client, t, "initialize", {"protocolVersion": "2025-06-18", "capabilities": {},
                                          "clientInfo": {"name": "pytest", "version": "1"}})
    assert init.status_code == 200, init.text
    r = rpc(client, t, "tools/list", id_=2)
    assert {x["name"] for x in r.json()["result"]["tools"]} >= {"run_command", "status"}
    r = rpc(client, t, "tools/call", {"name": "read_file", "arguments": {"path": "secret.txt"}}, 3).json()
    assert r["result"]["structuredContent"]["content"] == "beta-only"
    r = rpc(client, t, "tools/call", {"name": "run_command", "arguments": {"command": "id"}}, 4).json()
    assert r["result"]["isError"] is True  # beta = workspace profile
    r = rpc(client, env["tok"]["alpha"], "tools/call", {"name": "read_file", "arguments": {"path": str(env["ws"] / "beta/secret.txt")}}, 5).json()
    assert r["result"]["isError"] is True


# ------------------------------------------------------------- OpenAPI / dashboard
def test_openapi_actions_valid(client):
    r = client.get("/openapi-actions.json")
    spec = r.json()
    validate(spec)  # raises on invalid OpenAPI 3.1
    assert spec["openapi"] == "3.1.0"
    assert spec["components"]["securitySchemes"]["bearerAuth"] == {"type": "http", "scheme": "bearer"}
    ops = {op["operationId"] for p in spec["paths"].values() for op in p.values()}
    assert ops == {"getStatus", "listDir", "readFile", "writeFile", "runCommand"}
    assert "/v1/exec" not in spec["paths"]  # no streaming in Actions
    validate(actions_schema("https://abc.trycloudflare.com"))


def test_dashboard_local_and_token_gated(client, env):
    # TestClient peer is "testclient", not loopback -> hidden
    assert client.get("/dashboard").status_code == 404


def test_dashboard_loopback(env):
    inner = create_app(env["cfg"])

    async def app(scope, receive, send):  # pretend the peer is loopback
        if scope["type"] == "http":
            scope = dict(scope, client=("127.0.0.1", 5555))
        await inner(scope, receive, send)
    with TestClient(app) as c:
        assert c.get("/dashboard").status_code == 200
        assert c.get("/dashboard", headers={"CF-Connecting-IP": "1.2.3.4"}).status_code == 404
        assert c.get("/v1/dashboard/data").status_code == 401
        assert c.get("/v1/dashboard/data", headers=H(env["tok"]["alpha"])).status_code == 403
        d = c.get("/v1/dashboard/data", headers=H(env["tok"]["admin"])).json()
        assert {a["name"] for a in d["agents"]} == {"alpha", "beta", "reader", "admin"}
        assert "token_sha256" not in json.dumps(d)
