"""MCP server (official `mcp` SDK, FastMCP API) exposing the relay as tools.

* HTTP: mounted at /mcp by server.py (streamable HTTP, stateless, JSON responses).
  The bearer token is checked by an ASGI guard before the SDK sees the request, and
  each tool call re-resolves the agent from that request's Authorization header.
* stdio: `zoth-relay mcp-stdio` either bridges to the running service over REST
  (default, keeps the hardened service-user sandbox) or, with --direct, runs the
  relay in-process as the current user (development / tests).
"""
from __future__ import annotations

import json
import os
from typing import Any, Awaitable, Callable

from mcp.server.fastmcp import Context, FastMCP
from mcp.server.transport_security import TransportSecuritySettings

from .config import Agent
from .core import Relay, RelayError

INSTRUCTIONS = ("zoth-relay: sandboxed access to a ZOTHOS/Parrot Linux box. Paths must be inside "
                "your agent's allowed_dirs. run_command runs bash as an unprivileged user with a timeout.")


def _err(e: RelayError) -> Exception:
    return ValueError(f"{e.status}: {e.detail}")


def _register(mcp: FastMCP, backend: Callable[[str, dict, Context | None], Awaitable[Any]]) -> None:
    @mcp.tool(description="Server health, uptime, memory, disk and your agent's scope.")
    async def status(ctx: Context) -> dict[str, Any]:
        return await backend("status", {}, ctx)

    @mcp.tool(description="Read a UTF-8 (or base64 for binary) file inside your allowed dirs.")
    async def read_file(path: str, ctx: Context) -> dict[str, Any]:
        return await backend("read_file", {"path": path}, ctx)

    @mcp.tool(description="Write (or append) a text file inside your allowed dirs. Needs profile workspace or exec.")
    async def write_file(path: str, content: str, ctx: Context, append: bool = False) -> dict[str, Any]:
        return await backend("write_file", {"path": path, "content": content, "append": append}, ctx)

    @mcp.tool(description="List a directory inside your allowed dirs (defaults to your first allowed dir).")
    async def list_dir(ctx: Context, path: str | None = None) -> dict[str, Any]:
        return await backend("list_dir", {"path": path}, ctx)

    @mcp.tool(description="Run a bash command (non-interactive, timeout-capped) as the unprivileged relay "
                          "user. Needs profile exec or dev (dev = sandboxed to your workspace, binds 127.0.0.1:4100-4199). Returns stdout, stderr, exit_code.")
    async def run_command(command: str, ctx: Context, working_directory: str | None = None,
                          timeout: int | None = None) -> dict[str, Any]:
        return await backend("run_command", {"command": command, "working_directory": working_directory,
                                             "timeout": timeout}, ctx)


def _local_backend(relay: Relay, fixed_agent: Agent | None):
    def agent_for(ctx: Context | None) -> tuple[Agent, str]:
        req = None
        try:
            req = ctx.request_context.request if ctx is not None else None
        except (LookupError, ValueError, AttributeError):
            req = None
        if req is not None:
            scheme, _, tok = req.headers.get("authorization", "").partition(" ")
            a = relay.registry.lookup(tok.strip()) if scheme.lower() == "bearer" else None
            if a is None:
                raise ValueError("401: unauthorized")
            ip = req.headers.get("cf-connecting-ip") or (req.client.host if req.client else "?")
            return a, ip
        if fixed_agent is None:
            raise ValueError("401: no agent bound to this session")
        return fixed_agent, "stdio"

    async def backend(op: str, args: dict, ctx: Context | None):
        agent, ip = agent_for(ctx)
        via = "mcp"
        try:
            if op == "status":
                return relay.status(agent, ip, via)
            if op == "read_file":
                return relay.read_file(agent, args["path"], ip, via)
            if op == "write_file":
                return relay.write_file(agent, args["path"], args["content"], args.get("append", False), True, ip, via)
            if op == "list_dir":
                return relay.list_dir(agent, args.get("path"), ip, via)
            if op == "run_command":
                return await relay.exec_collect(agent, args["command"], args.get("working_directory"),
                                                args.get("timeout"), ip, via)
        except RelayError as e:
            raise _err(e)
        raise ValueError(f"unknown op {op}")

    return backend


def build_mcp(relay: Relay, fixed_agent: Agent | None = None) -> FastMCP:
    hosts = relay.cfg.mcp_allowed_hosts
    sec = TransportSecuritySettings(enable_dns_rebinding_protection=bool(hosts),
                                    allowed_hosts=hosts, allowed_origins=[f"https://{h}" for h in hosts])
    mcp = FastMCP("zoth-relay", instructions=INSTRUCTIONS, stateless_http=True, json_response=True,
                  streamable_http_path="/mcp", transport_security=sec, log_level="WARNING")
    _register(mcp, _local_backend(relay, fixed_agent))
    return mcp


def build_bridge(url: str, token: str) -> FastMCP:
    """stdio MCP server whose tools forward to a running zoth-relay over REST."""
    import httpx

    client = httpx.AsyncClient(base_url=url.rstrip("/"), headers={"Authorization": f"Bearer {token}"}, timeout=960)

    async def backend(op: str, args: dict, ctx):
        if op == "status":
            r = await client.get("/v1/status")
        elif op == "read_file":
            r = await client.get("/v1/file", params={"path": args["path"]})
        elif op == "write_file":
            r = await client.post("/v1/file", json=args)
        elif op == "list_dir":
            r = await client.get("/v1/dir", params={k: v for k, v in args.items() if v})
        elif op == "run_command":
            r = await client.post("/v1/exec/run", json={k: v for k, v in args.items() if v is not None})
        else:
            raise ValueError(op)
        if r.status_code >= 400:
            try:
                detail = r.json().get("detail")
            except (ValueError, json.JSONDecodeError):
                detail = r.text
            raise ValueError(f"{r.status_code}: {detail}")
        return r.json()

    mcp = FastMCP("zoth-relay", instructions=INSTRUCTIONS, log_level="WARNING")
    _register(mcp, backend)
    return mcp


def run_stdio(url: str | None, token: str | None, direct_config: str | None) -> None:
    token = token or os.environ.get("ZOTH_RELAY_TOKEN", "")
    if direct_config:
        from .config import load_config
        relay = Relay(load_config(direct_config))
        agent = relay.registry.lookup(token)
        if agent is None:
            raise SystemExit("[zoth-relay] ZOTH_RELAY_TOKEN does not match an enabled agent")
        build_mcp(relay, agent).run("stdio")
    else:
        if not token:
            raise SystemExit("[zoth-relay] set ZOTH_RELAY_TOKEN")
        build_bridge(url or os.environ.get("ZOTH_RELAY_URL", "http://127.0.0.1:8765"), token).run("stdio")
