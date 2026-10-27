"""FastAPI app. REST at / and /v1, MCP at /mcp, GPT-Actions schema, local dashboard."""
from __future__ import annotations

import contextlib
import json

from fastapi import APIRouter, Depends, FastAPI, HTTPException, Query, Request, WebSocket, WebSocketDisconnect
from fastapi.responses import HTMLResponse, JSONResponse, StreamingResponse
from pydantic import BaseModel, Field

import time
import uuid

from . import __version__
from .ask import AskError, ask_events, build_messages, decode_image
from .missions import MISSIONS, MissionError, MissionStore, assess_step, plan_checklist
from .providers import provider_presets
from .sessions import ACTIVE_SESSIONS, Session
from .trio import Advisor, Director, Eyes
from .live import LiveError, mint_token
from .guide import Guide
from .guide_stream import Conn, StreamHub
from .tts import Tts
from .config import Agent, Config, load_config
from .core import Relay, RelayError
from .dashboard import DASHBOARD_HTML
from .openapi_actions import actions_schema


class ExecRequest(BaseModel):
    command: str = Field(..., max_length=16384)
    working_directory: str | None = None
    timeout: int | None = Field(None, ge=1)


class FileWrite(BaseModel):
    path: str
    content: str
    append: bool = False
    make_dirs: bool = True


class AskHistoryItem(BaseModel):
    role: str = Field(..., pattern="^(user|assistant)$")
    content: str = Field(..., max_length=4000)


class AskRequest(BaseModel):
    prompt: str = Field(..., min_length=1, max_length=8000)
    agent: str | None = Field(None, max_length=64, description="ask target; default = token's first target or ask_default")
    image: str | None = Field(None, description="base64 JPEG/PNG/WebP or data: URI (max 8 MB)")
    image_mime: str | None = None
    mission: str | None = Field(None, max_length=2000)
    system: str | None = Field(None, max_length=4000)
    history: list[AskHistoryItem] = Field(default_factory=list, max_length=40)
    stream: bool = True
    max_tokens: int | None = Field(None, ge=1, le=4000)


class LiveTokenRequest(BaseModel):
    mission: str | None = Field(None, max_length=2000)
    voice: str | None = Field(None, max_length=64, pattern=r"^[A-Za-z0-9_-]*$")


class MissionCreate(BaseModel):
    goal: str = Field(..., min_length=1, max_length=2000)
    provider: str | None = Field(None, max_length=64, description="planner provider; default wonder")


class MissionEventIn(BaseModel):
    type: str = Field(..., max_length=16)
    observation: str | None = Field(None, max_length=2000)


class GuideTurnRequest(BaseModel):
    mode: str = Field("hermes", pattern="^(hermes|local_agy)$")
    session: str = Field("default", max_length=64, pattern=r"^[A-Za-z0-9_.-]+$")
    image: str | None = Field(None, description="base64 JPEG/PNG/WebP (max 8 MB)")
    speech: str | None = Field(None, max_length=2000)
    mission: str | None = Field(None, max_length=2000)
    reset: bool = False


def client_ip(req) -> str:
    h = req.headers.get("cf-connecting-ip")
    return h or (req.client.host if req.client else "?")


def is_local(req: Request) -> bool:
    # Anything that came through cloudflared carries CF-Connecting-IP / Cf-Ray.
    if req.headers.get("cf-connecting-ip") or req.headers.get("cf-ray"):
        return False
    return bool(req.client) and req.client.host in ("127.0.0.1", "::1")


def _bearer(headers) -> str:
    scheme, _, tok = headers.get("authorization", "").partition(" ")
    return tok.strip() if scheme.lower() == "bearer" else ""


class MCPGuard:
    """ASGI wrapper: requires a valid agent bearer token (and rate limit) on /mcp."""

    def __init__(self, app, relay: Relay):
        self.app, self.relay = app, relay

    async def __call__(self, scope, receive, send):
        if scope["type"] == "http" and scope["path"].rstrip("/") == "/mcp":
            from starlette.requests import Request as SReq
            req = SReq(scope)
            try:
                self.relay.authenticate(_bearer(req.headers), client_ip(req), "mcp", "/mcp")
            except RelayError as e:
                resp = JSONResponse({"detail": e.detail}, status_code=e.status,
                                    headers={"WWW-Authenticate": "Bearer"} if e.status == 401 else None)
                return await resp(scope, receive, send)
            if scope["path"] == "/mcp/":
                scope = dict(scope, path="/mcp")
        return await self.app(scope, receive, send)


def create_app(cfg: Config | None = None, relay: Relay | None = None):
    cfg = cfg or load_config()
    relay = relay or Relay(cfg)
    from .mcp_server import build_mcp
    mcp = build_mcp(relay)
    mcp_app = mcp.streamable_http_app()

    @contextlib.asynccontextmanager
    async def lifespan(_app):
        async with mcp.session_manager.run():
            yield

    app = FastAPI(title="zoth-relay", version=__version__, docs_url=None, redoc_url=None,
                  openapi_url=None, lifespan=lifespan)
    app.state.relay = relay

    async def auth(request: Request) -> Agent:
        try:
            return relay.authenticate(_bearer(request.headers), client_ip(request), "rest", request.url.path)
        except RelayError as e:
            raise HTTPException(e.status, e.detail, headers={"WWW-Authenticate": "Bearer"} if e.status == 401 else None)

    r = APIRouter()

    @r.get("/status", operation_id="getStatus")
    async def status(request: Request, agent: Agent = Depends(auth)):
        return relay.status(agent, client_ip(request))

    @r.get("/file", operation_id="readFile")
    async def read_file(request: Request, path: str = Query(...), agent: Agent = Depends(auth)):
        return relay.read_file(agent, path, client_ip(request))

    @r.post("/file", operation_id="writeFile")
    async def write_file(request: Request, body: FileWrite, agent: Agent = Depends(auth)):
        return relay.write_file(agent, body.path, body.content, body.append, body.make_dirs, client_ip(request))

    @r.get("/dir", operation_id="listDir")
    async def list_dir(request: Request, path: str | None = None, agent: Agent = Depends(auth)):
        return relay.list_dir(agent, path, client_ip(request))

    @r.post("/exec", operation_id="execStream")
    async def exec_stream(request: Request, body: ExecRequest, agent: Agent = Depends(auth)):
        it = await relay.exec_stream(agent, body.command, body.working_directory, body.timeout, client_ip(request))

        async def ndjson():
            async for ev in it:
                yield json.dumps(ev) + "\n"
        return StreamingResponse(ndjson(), media_type="application/x-ndjson")

    @r.post("/exec/run", operation_id="runCommand")
    async def exec_run(request: Request, body: ExecRequest, agent: Agent = Depends(auth)):
        return await relay.exec_collect(agent, body.command, body.working_directory, body.timeout, client_ip(request))

    app.include_router(r)
    app.include_router(r, prefix="/v1")

    # ---------------------------------------------------------- mobile ask API (v1 only)
    @app.get("/v1/agents", operation_id="listAskAgents")
    async def list_agents(request: Request, agent: Agent = Depends(auth)):
        ts = relay.ask_targets_for(agent, client_ip(request))
        default = agent.ask_targets[0] if agent.ask_targets else cfg.ask_default
        return {"default": default, "agents": [t.public() for t in ts]}

    @app.post("/v1/live/token", operation_id="liveToken")
    async def live_token(request: Request, body: LiveTokenRequest | None = None, agent: Agent = Depends(auth)):
        ip = client_ip(request)
        live = relay.check_live(agent, ip)
        body = body or LiveTokenRequest()
        try:
            out = await mint_token(live, body.mission or "", body.voice or "",
                                   getattr(app.state, "live_transport", None))
        except LiveError as e:
            relay.audit.write("live_token", agent=agent.name, ip=ip, via="rest", result="error", status=e.status)
            raise HTTPException(e.status, e.detail)
        relay.audit.write("live_token", agent=agent.name, ip=ip, via="rest", result="ok", model=out["model"],
                          expires_at=out["expires_at"])  # never the token itself
        return JSONResponse(out, headers={"Cache-Control": "no-store"})

    @app.post("/v1/guide/turn", operation_id="guideTurn")
    async def guide_turn(request: Request, body: GuideTurnRequest, agent: Agent = Depends(auth)):
        ip = client_ip(request)
        gcfg = relay.check_guide(agent, ip)
        g = getattr(app.state, "guide", None)
        if g is None:
            g = app.state.guide = Guide(gcfg, getattr(app.state, "guide_transport", None),
                                        getattr(app.state, "agy_runner", None))
        key = f"{agent.name}:{body.session}"
        if body.reset:
            g.reset(key)
        try:
            image = decode_image(body.image) if body.image else None
            t0 = time.monotonic()
            speech, mission = (body.speech or "").strip(), (body.mission or "").strip()
            if body.mode == "hermes":
                out = await g.hermes_turn(key, image, speech, mission)
            else:
                out = await g.local_turn(key, image, speech, mission)
        except AskError as e:
            relay.audit.write("guide_turn", agent=agent.name, ip=ip, via="rest", mode=body.mode,
                              result="error", status=e.status)
            raise HTTPException(e.status, e.detail)
        out["ms"] = int((time.monotonic() - t0) * 1000)
        # sizes only: frames, speech and replies are never logged or stored
        relay.audit.write("guide_turn", agent=agent.name, ip=ip, via="rest", mode=body.mode, result="ok",
                          image_bytes=len(image[0]) if image else 0, speech_chars=len(speech),
                          spoke=bool(out.get("speak")), ms=out["ms"])
        return JSONResponse(out, headers={"Cache-Control": "no-store"})

    def hub_for(gcfg) -> StreamHub:
        h = getattr(app.state, "stream_hub", None)
        if h is None:
            h = app.state.stream_hub = StreamHub(gcfg, gcfg.stream, getattr(app.state, "tts", None) or Tts(gcfg.tts),
                                                 getattr(app.state, "agy_session", None),
                                                 getattr(app.state, "guide_transport", None),
                                                 agy_factory=getattr(app.state, "agy_factory", None))
        return h

    @app.websocket("/v1/guide/stream")
    async def guide_stream(ws: WebSocket):
        ip = ws.headers.get("cf-connecting-ip") or (ws.client.host if ws.client else "?")
        try:
            agent = relay.authenticate(_bearer(ws.headers), ip, "ws", "/v1/guide/stream")
            gcfg = relay.check_guide(agent, ip)
        except RelayError as e:
            await ws.close(code=4000 + e.status, reason=e.detail[:120])
            return
        await ws.accept()
        hub = hub_for(gcfg)
        try:
            hello = await ws.receive_json()
        except Exception:
            await ws.close(code=4400)
            return
        if hub.conn is not None:
            await hub.conn.close("Another phone connected; this stream was replaced.")
        if hub._unload_task:
            hub._unload_task.cancel()
        await hub.up()
        conn = Conn(hub, ws.send_json, ws.send_bytes, str(hello.get("mission") or "")[:2000], hello.get("tts") == "phone")
        hub.conn = conn
        relay.audit.write("guide_stream", agent=agent.name, ip=ip, via="ws", result="open")
        await conn.send({"type": "ready", "rate": hub.tts.rate, "tts": "phone" if conn.phone_tts else hub.tts.cfg.engine,
                         **{k: v for k, v in hub.status().items() if k in ("agy", "vision_model", "cpu_temp_c")}})
        conn.start()
        frames = speech = 0
        try:
            while not conn.closed:
                m = await ws.receive_json()
                t = m.get("type")
                if t == "frame" and isinstance(m.get("image"), str):
                    frames += 1; conn.on_frame(m["image"])
                elif t == "speech" and isinstance(m.get("text"), str):
                    speech += 1; await conn.on_speech(m["text"][:2000])
                elif t == "barge":
                    await conn.on_barge()
                elif t == "mission":
                    conn.mission = str(m.get("text") or "")[:2000]
                elif t == "bye":
                    break
        except (WebSocketDisconnect, RuntimeError, ValueError):
            pass
        finally:
            await conn.close()
            if hub.conn is conn:
                hub.conn = None
                hub.schedule_unload()
            # counts only: frames, speech and replies are never logged
            relay.audit.write("guide_stream", agent=agent.name, ip=ip, via="ws", result="closed", frames=frames, speech=speech)

    @app.post("/v1/guide/admin/{action}", include_in_schema=False)
    async def guide_admin(request: Request, action: str):
        # loopback only (the `zoth-guide up/down` launcher on zoth-pill); never reachable over Tailscale
        if not is_local(request) or cfg.guide is None or action not in ("up", "down", "status", "metrics"):
            raise HTTPException(404, "not found")
        hub = hub_for(cfg.guide)
        if action == "metrics":   # timings only; no captions, speech or replies
            return {"turns": hub.conn.metrics if hub.conn else [],
                    "agy": [{"t": t, "first_s": f, "total_s": tot, "chars": c} for t, f, tot, c in hub.agy.stats]}
        return await hub.up() if action == "up" else await hub.down() if action == "down" else hub.status()

    @app.post("/v1/ask", operation_id="ask")
    async def ask(request: Request, body: AskRequest, agent: Agent = Depends(auth)):
        ip = client_ip(request)
        target = relay.resolve_ask_target(agent, body.agent, ip)
        try:
            image = decode_image(body.image, body.image_mime) if body.image else None
        except AskError as e:
            raise HTTPException(e.status, e.detail)
        msgs = build_messages(target, body.prompt, image, [h.model_dump() for h in body.history],
                              body.system or "", body.mission or "")
        rid = uuid.uuid4().hex[:12]
        # sizes only: prompts and images are never logged or stored
        relay.audit.write("ask", agent=agent.name, ip=ip, via="rest", target=target.name, id=rid,
                          prompt_chars=len(body.prompt), image_bytes=len(image[0]) if image else 0,
                          image_used=bool(image and target.vision), stream=body.stream)
        transport = getattr(app.state, "ask_transport", None)
        events = ask_events(target, msgs, body.max_tokens, rid, transport)
        if body.stream:
            async def ndjson():
                async for ev in events:
                    yield json.dumps(ev) + "\n"
            return StreamingResponse(ndjson(), media_type="application/x-ndjson",
                                     headers={"Cache-Control": "no-store", "X-Accel-Buffering": "no"})
        last = None
        async for ev in events:
            last = ev
        if last and last["type"] == "error":
            raise HTTPException(last["status"], last["detail"])
        return {"id": rid, "agent": target.name, "model": target.model, "text": last["text"], "ms": last["ms"]}

    # -------------------------------- FIELDKIT sessions/missions (Sessions: D2; Missions: D3)
    def missions_store() -> MissionStore:
        # Tests may inject an isolated store; production shares the module
        # singleton so REST, sessions and MCP all see the same missions.
        return getattr(app.state, "missions", None) or MISSIONS

    def fieldkit_providers() -> dict:
        ps = getattr(app.state, "session_providers", None)
        if ps is None:
            ps = app.state.session_providers = provider_presets()
        return ps

    @app.post("/v1/missions", operation_id="createMission")
    async def create_mission(request: Request, body: MissionCreate, agent: Agent = Depends(auth)):
        ip = client_ip(request)
        relay.check_missions(agent, ip)
        name = (body.provider or "wonder").strip()
        provider = fieldkit_providers().get(name)
        if provider is None:
            raise HTTPException(404, f"unknown provider '{name}'")
        if not provider.configured:
            raise HTTPException(503, f"provider '{name}' is not configured on this relay")
        try:
            steps = await plan_checklist(provider, body.goal.strip(),
                                         getattr(app.state, "session_transport", None))
        except MissionError as e:
            relay.audit.write("mission_create", agent=agent.name, ip=ip, via="rest",
                              result="error", status=e.status, provider=name)
            raise HTTPException(e.status, e.detail)
        m = missions_store().create(body.goal.strip(), steps, provider=name)
        # ids + counts only: goals and steps are never written to the audit log
        relay.audit.write("mission_create", agent=agent.name, ip=ip, via="rest", result="ok",
                          mission=m.id, provider=name, steps=len(steps))
        return JSONResponse(m.public(), status_code=201)

    @app.get("/v1/missions/{mission_id}", operation_id="getMission")
    async def get_mission(request: Request, mission_id: str, agent: Agent = Depends(auth)):
        relay.check_missions(agent, client_ip(request))
        m = missions_store().get(mission_id)
        if m is None:
            raise HTTPException(404, "unknown mission")
        return m.public()

    @app.post("/v1/missions/{mission_id}/events", operation_id="missionEvent")
    async def mission_event(request: Request, mission_id: str, body: MissionEventIn,
                            agent: Agent = Depends(auth)):
        ip = client_ip(request)
        relay.check_missions(agent, ip)
        store = missions_store()
        m = store.get(mission_id)
        if m is None:
            raise HTTPException(404, "unknown mission")
        if body.type in ("advance", "skip"):
            store.apply_event(mission_id, body.type)
            say = m.current_step_text or "Mission complete."
            ev_name = "advanced" if body.type == "advance" else "skipped"
        elif body.type == "assess":
            provider = fieldkit_providers().get(m.provider)
            if provider is None or not provider.configured:
                raise HTTPException(503, f"mission provider '{m.provider}' is not available")
            try:
                ev = await assess_step(provider, m, (body.observation or "")[:2000],
                                       getattr(app.state, "session_transport", None))
            except MissionError as e:
                raise HTTPException(e.status, e.detail)
            if ev.type == "step_done":
                store.apply_event(mission_id, "advance")
            say = ev.say or (m.current_step_text or "Mission complete.")
            ev_name = ev.type
        else:
            raise HTTPException(400, f"unknown mission event '{body.type}'")
        relay.audit.write("mission_event", agent=agent.name, ip=ip, via="rest", result="ok",
                          mission=mission_id, kind=ev_name, current=m.current)
        return {"event": ev_name, "say": say, "mission": m.public()}

    @app.websocket("/v1/sessions")
    async def sessions(ws: WebSocket):
        ip = ws.headers.get("cf-connecting-ip") or (ws.client.host if ws.client else "?")
        try:
            agent = relay.authenticate(_bearer(ws.headers), ip, "ws", "/v1/sessions")
            relay.check_sessions(agent, ip, via="ws")
        except RelayError as e:
            await ws.close(code=4000 + e.status, reason=e.detail[:120])
            return
        await ws.accept()
        try:
            hello = await ws.receive_json()
        except Exception:
            await ws.close(code=4400)
            return
        if not isinstance(hello, dict) or hello.get("type") != "hello":
            await ws.close(code=4400, reason="first message must be hello")
            return
        providers = getattr(app.state, "session_providers", None)
        if providers is None:
            providers = app.state.session_providers = provider_presets()
        name = str(hello.get("provider") or "spark")
        provider = providers.get(name)
        if provider is None:
            await ws.send_json({"type": "error", "detail": f"unknown provider '{name}'"})
            await ws.close(code=1000)
            return
        if not provider.configured:
            await ws.send_json({"type": "error",
                                "detail": f"provider '{name}' is not configured on this relay"})
            await ws.close(code=1000)
            return
        sess = Session(provider, ws.send_json, getattr(app.state, "session_transport", None),
                       agent_name=agent.name)
        sess.set_hello(str(hello.get("mission") or "")[:2000], str(hello.get("system") or "")[:4000],
                       hello.get("history_budget"))
        mid = str(hello.get("mission_id") or "")
        if mid:
            if missions_store().get(mid) is None:
                await ws.send_json({"type": "error", "detail": f"unknown mission '{mid}'"})
                await ws.close(code=1000)
                return
            sess.attach_mission(missions_store(), mid)
        if hello.get("guide") or "preset" in hello or "preset_custom" in hello:
            transport = getattr(app.state, "session_transport", None)
            custom = hello.get("preset_custom") if isinstance(hello.get("preset_custom"), dict) else None
            sess.set_guide(Eyes(provider, transport), Advisor(provider, transport),
                           Director(providers.get("wonder") or provider, transport),
                           str(hello.get("preset") or "") or None, custom,
                           bool(hello.get("allow_outside", False)))
            sess.start_proactive()
        ACTIVE_SESSIONS[agent.name] = sess
        # counts/ids only: frames, speech and replies are never logged
        relay.audit.write("session", agent=agent.name, ip=ip, via="ws", result="open", provider=name)
        ready = {"type": "ready", "provider": name, **provider.public(),
                 "history_budget": sess.ctx.budget,
                 "guide": sess.guide_on, "preset": sess.preset.name}
        ms = sess.mission_state()
        if ms is not None:
            ready["mission"] = ms
        await ws.send_json(ready)
        try:
            while True:
                m = await ws.receive_json()
                if not isinstance(m, dict):
                    continue
                t = m.get("type")
                if t == "frame" and isinstance(m.get("image"), str):
                    sess.on_frame(m["image"])
                elif t == "clip" and isinstance(m.get("video_url"), str):
                    sess.on_clip(m["video_url"])
                elif t == "speech" and isinstance(m.get("text"), str):
                    if m.get("final", True):
                        await sess.on_user_speech(m["text"][:2000],
                                                  str(m.get("speaker") or "unknown"),
                                                  m.get("sound_relative"))
                elif t == "text" and isinstance(m.get("text"), str):
                    sess.start_turn(m["text"][:2000], t)
                elif t == "mission":
                    sess.set_mission(str(m.get("text") or "")[:2000])
                    await ws.send_json(sess.state())
                elif t == "preset":
                    sess.set_preset(str(m.get("name") or "") or None,
                                    m.get("custom") if isinstance(m.get("custom"), dict) else None)
                    await ws.send_json(sess.state())
                elif t == "pause":
                    sess.set_paused(True)
                    await ws.send_json(sess.state())
                elif t == "resume":
                    sess.set_paused(False)
                    await ws.send_json(sess.state())
                elif t == "compass":
                    sess.on_compass(m.get("heading"), str(m.get("source") or "phone"))
                elif t == "sound":
                    sess.on_sound(m.get("heading"), m.get("relative"),
                                  str(m.get("source") or "phone"), m.get("level_db"))
                elif t == "barge":
                    await sess.barge()
                elif t == "bye":
                    break
        except (WebSocketDisconnect, RuntimeError, ValueError):
            pass
        finally:
            if ACTIVE_SESSIONS.get(agent.name) is sess:
                ACTIVE_SESSIONS.pop(agent.name, None)
            await sess.close()
            relay.audit.write("session", agent=agent.name, ip=ip, via="ws", result="closed",
                              turns=sess.turns, frames=sess.frames_received,
                              frames_changed=sess.frames_changed)

    @app.get("/openapi-actions.json", include_in_schema=False)
    async def openapi_actions(request: Request):
        # Schema only (no data); public so ChatGPT's "Import from URL" works.
        if cfg.public_url:
            base = cfg.public_url
        else:
            proto = request.headers.get("x-forwarded-proto", request.url.scheme)
            base = f"{proto}://{request.headers.get('host', f'127.0.0.1:{cfg.port}')}"
        return JSONResponse(actions_schema(base))

    @app.get("/dashboard", include_in_schema=False)
    async def dashboard(request: Request):
        if not is_local(request):
            raise HTTPException(404, "not found")
        return HTMLResponse(DASHBOARD_HTML, headers={"Content-Security-Policy": "default-src 'self'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; img-src 'self' data:",
                                                     "X-Frame-Options": "DENY", "Cache-Control": "no-store"})

    @app.get("/v1/dashboard/data", include_in_schema=False)
    async def dashboard_data(request: Request, agent: Agent = Depends(auth)):
        if not is_local(request):
            raise HTTPException(404, "not found")
        return relay.dashboard_data(agent, client_ip(request))

    @app.get("/health", include_in_schema=False)
    @app.get("/healthz", include_in_schema=False)
    async def healthz():
        return {"ok": True}

    @app.exception_handler(RelayError)
    async def _relay_err(_, exc: RelayError):
        return JSONResponse({"detail": exc.detail}, status_code=exc.status)

    # MCP streamable-HTTP route(s) from the SDK app, guarded by MCPGuard below.
    for route in mcp_app.routes:
        app.router.routes.append(route)

    return MCPGuard(app, relay)
