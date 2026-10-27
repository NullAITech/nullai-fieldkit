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
import time
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

    @mcp.tool(description="FIELDKIT: what your agent's glasses/phone camera sees right now "
                          "(deduped Eyes observation of the latest live frame; silent if unchanged).")
    async def see_now(ctx: Context) -> dict[str, Any]:
        return await backend("see_now", {}, ctx)

    @mcp.tool(description="FIELDKIT: current mission state (checklist + current step). "
                          "Omit mission_id for the latest mission.")
    async def mission_status(ctx: Context, mission_id: str | None = None) -> dict[str, Any]:
        return await backend("mission_status", {"mission_id": mission_id}, ctx)

    @mcp.tool(description="FIELDKIT: mark the current mission step done; returns the next step to say.")
    async def advance_step(ctx: Context, mission_id: str | None = None) -> dict[str, Any]:
        return await backend("advance_step", {"mission_id": mission_id}, ctx)

    @mcp.tool(description="FIELDKIT: speak a short line into your agent's live Spotter session.")
    async def say(text: str, ctx: Context) -> dict[str, Any]:
        return await backend("say", {"text": text}, ctx)

    @mcp.tool(description="FIELDKIT: append a hands-free field note ('jot this down: ...') "
                          "to the active mission's Hermes Jot checklist note.")
    async def jot_field_note(text: str, ctx: Context, mission_id: str | None = None) -> dict[str, Any]:
        return await backend("jot_field_note", {"text": text, "mission_id": mission_id}, ctx)

    @mcp.tool(description="FIELDKIT: create or refresh the Hermes Jot checklist note for a "
                          "mission, ticking every completed step.")
    async def jot_mission_sync(ctx: Context, mission_id: str | None = None) -> dict[str, Any]:
        return await backend("jot_mission_sync", {"mission_id": mission_id}, ctx)

    @mcp.tool(description="FIELDKIT: recall prior-job field notes from the Obsidian Second "
                          "Brain (+ Jot) matching this goal/equipment, before planning.")
    async def recall_second_brain(query: str, ctx: Context) -> dict[str, Any]:
        return await backend("recall_second_brain", {"query": query}, ctx)

    @mcp.tool(description="FIELDKIT LIVE: go live (OBS overlay / X). PUBLIC action, "
                          "confirm-gated: first call returns a confirm_token; only a repeat "
                          "call with confirm=<token> starts. Audited either way.")
    async def stream_start(ctx: Context, confirm: str | None = None,
                           destination: str | None = None) -> dict[str, Any]:
        return await backend("stream_start", {"confirm": confirm, "destination": destination}, ctx)

    @mcp.tool(description="FIELDKIT LIVE: stop the live stream. Audited.")
    async def stream_stop(ctx: Context) -> dict[str, Any]:
        return await backend("stream_stop", {}, ctx)


async def _fieldkit_op(relay: Relay, agent: Agent, op: str, args: dict, ip: str, via: str):
    """FIELDKIT agent tools (Beat 3): see_now / say / mission_status / advance_step."""
    from .missions import MISSIONS
    from .providers import provider_presets
    from .sessions import ACTIVE_SESSIONS, FRAMES
    from .trio import Eyes

    # Same senses-vs-hands scopes as REST/WS: sessions/missions caps gate these.
    if op in ("see_now", "say"):
        relay.check_sessions(agent, ip, via)
    else:
        relay.check_missions(agent, ip, via)

    relay.audit.write("fieldkit", agent=agent.name, ip=ip, via=via, op=op)

    if op in ("mission_status", "advance_step"):
        mid = args.get("mission_id")
        m = MISSIONS.get(mid) if mid else MISSIONS.latest()
        if m is None:
            return {"ok": False, "detail": "no such mission" if mid else "no missions yet"}
        if op == "mission_status":
            return {"ok": True, "mission": m.public()}
        done_n = m.current
        MISSIONS.apply_event(m.id, "advance")
        from . import jot_bridge, recap, second_brain
        jot_res = await jot_bridge.checkoff(m.id, done_n)
        try:
            second_brain.compile_mission(m)
        except Exception:
            pass
        recap_res = await recap.maybe_recap(m) if m.complete else None
        return {"ok": True, "event": "advanced", "jot": jot_res, "recap": recap_res,
                "say": m.current_step_text or "Mission complete.", "mission": m.public()}
    if op == "say":
        sess = ACTIVE_SESSIONS.get(agent.name)
        if sess is None or sess.closed:
            return {"ok": False, "detail": "no live session for this agent"}
        await sess.say_direct(args.get("text") or "")
        return {"ok": True}
    if op == "jot_field_note":
        from . import jot_bridge
        mid = args.get("mission_id")
        m = MISSIONS.get(mid) if mid else MISSIONS.latest()
        if m is None:
            return {"ok": False, "detail": "no mission to jot against"}
        step = f"Step {m.current}: {m.current_step_text}" if m.current_step_text else ""
        return await jot_bridge.append_field_note(m.id, args.get("text") or "", step)
    if op == "jot_mission_sync":
        from . import jot_bridge
        mid = args.get("mission_id")
        m = MISSIONS.get(mid) if mid else MISSIONS.latest()
        if m is None:
            return {"ok": False, "detail": "no such mission" if mid else "no missions yet"}
        return await jot_bridge.sync_mission(m)
    if op == "recall_second_brain":
        from . import second_brain
        return {"ok": True, "hints": second_brain.prior_hints(args.get("query") or "")}
    if op in ("stream_start", "stream_stop"):
        from .live_mode import LIVE
        if op == "stream_start":
            res = LIVE.start(args.get("confirm") or None, args.get("destination") or "")
        else:
            res = LIVE.stop()
        relay.audit.write("live_" + ("start" if op == "stream_start" else "stop"),
                          agent=agent.name, ip=ip, via=via,
                          result="live" if res.get("ok") else "needs_confirm")
        return res
    # see_now
    got = FRAMES.latest(agent.name)
    if got is None:
        return {"ok": False, "detail": "no live frame for this agent yet"}
    raw, mime, ts = got
    provider = provider_presets().get("spark")
    if provider is None or not provider.configured:
        return {"ok": False, "detail": "vision provider not configured on this relay"}
    sess = ACTIVE_SESSIONS.get(agent.name)
    obs = await Eyes(provider).observe((raw, mime), sess._mission_text() if sess else "")
    return {"ok": True, "observation": obs.text if obs else "",
            "silent": obs is None, "frame_age_s": round(time.time() - ts, 1)}


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
            if op in ("see_now", "say", "mission_status", "advance_step",
                      "jot_field_note", "jot_mission_sync", "recall_second_brain",
                      "stream_start", "stream_stop"):
                return await _fieldkit_op(relay, agent, op, args, ip, via)
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
        elif op in ("mission_status", "advance_step"):
            mid = args.get("mission_id")
            if not mid:
                raise ValueError(f"{op} over the bridge needs an explicit mission_id")
            if op == "mission_status":
                r = await client.get(f"/v1/missions/{mid}")
                if r.status_code < 400:
                    return {"ok": True, "mission": r.json()}
            else:
                r = await client.post(f"/v1/missions/{mid}/events", json={"type": "advance"})
        elif op in ("see_now", "say", "jot_field_note", "jot_mission_sync",
                    "stream_start", "stream_stop"):
            raise ValueError(f"{op} needs a direct MCP connection to the relay (live session state)")
        elif op == "recall_second_brain":
            from . import second_brain
            return {"ok": True, "hints": second_brain.prior_hints(args.get("query") or "")}
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
