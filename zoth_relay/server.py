"""FastAPI app. REST at / and /v1, MCP at /mcp, GPT-Actions schema, local dashboard."""
from __future__ import annotations

import contextlib
import base64
import json
import os

from fastapi import APIRouter, Depends, FastAPI, HTTPException, Query, Request, WebSocket, WebSocketDisconnect
from fastapi.responses import HTMLResponse, JSONResponse, Response, StreamingResponse
from pydantic import BaseModel, Field

import time
import uuid

from . import __version__
from .ask import AskError, ask_events, build_messages, decode_image
from . import enrichment, jot_bridge, recap, second_brain
from .missions import MISSIONS, MissionError, MissionStore, assess_step, plan_interview
from .providers import configured_providers, presets_public, provider_presets, with_failover
from .sessions import ACTIVE_SESSIONS, FRAMES, LAST_SESSIONS, Session
from .trio import Advisor, Director, Eyes
from .live import LiveError, mint_token
from .guide import AgyConfig, Guide
from .guide_stream import Conn, StreamHub
from .tts import Tts
from .config import Agent, Config, load_config
from .context import TurnContext
from .people import People, clean_vector
from .modes import MODES, ModeStore, mode_prompt, onboard_step
from .core import Relay, RelayError
from .dashboard import DASHBOARD_HTML
from .openapi_actions import actions_schema


# People & Voices (Nov 2)
class PersonSave(BaseModel):
    name: str = Field(..., min_length=1, max_length=80)
    descriptor: list[float] | None = None
    voice: list[float] | None = None
    thumb: str | None = None
    at: float | None = None
    relation: str = ""
    note: str = ""

class PersonPatch(BaseModel):
    name: str = Field(..., min_length=1, max_length=80)

class VoiceSave(BaseModel):
    voice: list[float]


class PersonAnalyzeRequest(BaseModel):
    image: str = Field(..., description="base64 JPEG of person crop or 4-angle Character Board")
    attire_hint: str = Field("", max_length=200)
    build_hint: str = Field("", max_length=120)


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
    mission: str | None = Field(None, max_length=16000)
    voice: str | None = Field(None, max_length=64, pattern=r"^[A-Za-z0-9_-]*$")


class ModeSet(BaseModel):
    mode: str = Field(..., min_length=1, max_length=200)
    goal: str | None = Field(None, max_length=500)
    answers: list[str] | None = Field(None, max_length=6)
    map_hud: bool | None = None


class ModeOnboard(BaseModel):
    answers: list[str] = Field(default_factory=list, max_length=7)


class MissionCreate(BaseModel):
    goal: str = Field(..., min_length=1, max_length=2000)
    provider: str | None = Field(None, max_length=64, description="planner provider; default wonder")
    answers: list[str] | None = Field(
        None, description="answers to a clarify round; resubmit goal with these to plan")
    memories: list[str] | None = Field(
        None, max_length=2000, description="wearer-attached memory notes the plan must honor")


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
    # --- optional turn context (older phone builds send none of these; the prompt is unchanged) ---
    speaker: str | None = Field(None, max_length=16, description="wearer | other | unknown")
    command: str | None = Field(None, max_length=32, description="WakePhrase command, e.g. guide_on")
    focus: str | None = Field(None, max_length=200, description="what the wearer is on about")
    person_seen: str | None = Field(None, max_length=300, description="re-sighting cue line")
    person_new: bool = False
    name: str | None = Field(None, max_length=80, description="a name the wearer typed or said")
    descriptor: list[float] | None = Field(
        None, max_length=512,
        description="PersonDescriptor from the phone, L2-normalised. Anonymous on its own: it can "
                    "only match a person the wearer has already named.")


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
        mission = (body.mission or "").strip()
        mode_text = agent_mode_prompt(agent.name)
        if mode_text and mode_text not in mission:
            mission = f"{mode_text}\n{mission}".strip() if mission else mode_text
        try:
            p_lines = people_for(agent, ip).prompt_lines(limit=12)
        except Exception:
            p_lines = []
        if p_lines and "[SAVED PEOPLE ROSTER]" not in mission:
            roster = "[SAVED PEOPLE ROSTER]: " + " | ".join(p_lines)
            mission = f"{mission}\n{roster}".strip() if mission else roster
        try:
            out = await mint_token(live, mission, body.voice or "",
                                   getattr(app.state, "live_transport", None))
        except LiveError as e:
            relay.audit.write("live_token", agent=agent.name, ip=ip, via="rest", result="error", status=e.status)
            raise HTTPException(e.status, e.detail)
        relay.audit.write("live_token", agent=agent.name, ip=ip, via="rest", result="ok", model=out["model"],
                          expires_at=out["expires_at"])  # never the token itself
        return JSONResponse(out, headers={"Cache-Control": "no-store"})

    def people_store(gcfg=None):
        """The people HE named, kept beside the guide workspace under the 0700 state dir.
        Built once and shared, so the REST and session paths read one store, never two."""
        people = getattr(app.state, "people", None)
        if people is None:
            wd = getattr(getattr(gcfg, "agy", None), "workdir", None) or AgyConfig().workdir
            state_dir = os.path.dirname(os.path.expanduser(wd)) or os.path.expanduser("~")
            people = app.state.people = People(os.path.join(state_dir, "people.json"))
        return people

    def people_for(agent, ip):
        """Same single store; the guide config only picks its directory, so a relay without a
        `guide:` section still keeps people (under the default state dir)."""
        try:
            gcfg = relay.check_guide(agent, ip)
        except Exception:
            gcfg = None
        return people_store(gcfg)

    def match_in_view(store, descriptors) -> list[str]:
        """Read-only match of on-device face descriptors against people HE saved. Never creates
        a pending record (detections run every second; sightings are the speech path's job)."""
        names = []
        for raw in (descriptors or [])[:6]:
            v = clean_vector(raw)
            if v:
                p, _, _ = store.match(v)
                if p is not None and not p.pending:
                    names.append(p.name)
        return names

    def sighting(gcfg, descriptor, person_new=False):
        """Match a bare descriptor from the phone against the people he named himself.
        Returns (seen_line, seen_new). A face NEVER names anyone: seen_line is only ever the
        reflection of a person the wearer already named, and an unnamed face stays "someone
        in view" - never identified, never described back."""
        seen_line, seen_new = "", bool(person_new)
        if descriptor:
            ob = people_store(gcfg).observe(descriptor)
            if ob["status"] == "known":
                seen_line = ob["reflection"]
            else:
                seen_new = True                        # matched a stranger, or never seen before
        return seen_line, seen_new

    def name_person(gcfg, desc, name, speaker) -> str:
        """The wearer typed or said a name for the person in frame. Returns it if it counted.

        A name is written ONLY from the wearer's own words and ONLY together with a descriptor to
        bind it to - a name with no descriptor would be a free-floating claim about somebody.
        Another voice in the room cannot name anyone, and nothing here ever derives a name from a
        face: this is the only way a name enters the store."""
        raw = name.strip()[:80] if isinstance(name, str) else ""
        if not raw or not desc:
            return ""
        if speaker not in (None, "") and speaker != "wearer":
            return ""                           # a bystander cannot put a name in his memory
        people_store(gcfg).remember(desc, raw)
        return raw

    def ws_ctx(sess, m: dict, agent, ip):
        """This turn's guidance context, built from a session message. None when the phone sent
        nothing but text, so an older build's prompt stays unchanged. Speaker, focus and
        sightings only sharpen the guide - and a descriptor alone can NEVER name anyone: the
        relay matches it against people the WEARER named himself."""
        fields = ("speaker", "command", "focus", "person_seen", "person_new", "descriptor")
        if not any(m.get(k) not in (None, "", False, []) for k in fields):
            return None
        raw = m.get("descriptor")
        try:
            desc = [float(x) for x in raw] if isinstance(raw, (list, tuple)) and raw else None
        except (TypeError, ValueError):
            desc = None                        # malformed: ignore, never invent a match
        if desc is not None and len(desc) > 512:
            desc = None
        gcfg = getattr(sess, "gcfg", None)
        if gcfg is None and not getattr(sess, "gcfg_tried", False):
            try:
                gcfg = relay.check_guide(agent, ip)
            except Exception:
                gcfg = None                    # sessions are not guide-gated; sightings degrade only
            sess.gcfg, sess.gcfg_tried = gcfg, True
        seen_line, seen_new = sighting(gcfg, desc, bool(m.get("person_new")))
        if name_person(gcfg, desc, m.get("name"), m.get("speaker")):
            seen_line, seen_new = "", False     # he just named them: acknowledge, never "back in view"
        body = {k: m.get(k) for k in fields}
        body["person_seen"], body["person_new"] = seen_line, bool(seen_new)
        return TurnContext.from_body(body, people_store(gcfg).prompt_lines())

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
            if mp := agent_mode_prompt(agent.name):
                mission = f"{mission}\n{mp}" if mission else mp
            seen_line, seen_new = sighting(gcfg, body.descriptor, body.person_new)
            if name_person(gcfg, body.descriptor, body.name, body.speaker):
                seen_line, seen_new = "", False
            ctx = TurnContext.from_body(body.model_dump(), people_store(gcfg).prompt_lines())
            ctx.person_seen, ctx.person_new = seen_line, ctx.person_new or seen_new
            if body.mode == "hermes":
                out = await g.hermes_turn(key, image, speech, mission, ctx)
            else:
                out = await g.local_turn(key, image, speech, mission, ctx)
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
                    try:
                        from .rtmp_streamer import STREAMER
                        STREAMER.push_frame(base64.b64decode(m["image"]))
                    except Exception:
                        pass
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
        mission_ctx = body.mission or ""
        try:
            p_lines = people_for(agent, ip).prompt_lines(limit=10)
            if p_lines and "[SAVED PEOPLE" not in mission_ctx:
                roster = "[SAVED PEOPLE ROSTER]: " + " | ".join(p_lines)
                mission_ctx = f"{mission_ctx}\n{roster}".strip() if mission_ctx else roster
        except Exception:
            pass
        msgs = build_messages(target, body.prompt, image, [h.model_dump() for h in body.history],
                              body.system or "", mission_ctx)
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

    def modes_store() -> ModeStore:
        st = getattr(app.state, "modes", None)
        if st is None:
            st = app.state.modes = ModeStore()
        return st

    def agent_mode_prompt(name: str) -> str:
        try:
            return mode_prompt(modes_store().get(name))
        except Exception:
            return ""

    @app.get("/v1/mode", operation_id="getMode")
    async def get_mode(agent: Agent = Depends(auth)):
        st = modes_store().get(agent.name)
        return {"mode": st.public() if st else None,
                "modes": {k: {"label": v["label"], "blurb": v["blurb"], "map": v["map"]}
                          for k, v in MODES.items()}}

    @app.post("/v1/mode", operation_id="setMode")
    async def set_mode(body: ModeSet, agent: Agent = Depends(auth)):
        st = modes_store().set(agent.name, body.mode, body.goal or "", body.answers, body.map_hud)
        relay.audit.write("mode_set", agent=agent.name, ip="?", via="rest", result="ok", mode=st.mode)
        sess = ACTIVE_SESSIONS.get(agent.name)
        if sess is not None:
            sess.mode_text = mode_prompt(st)
        return st.public()

    @app.delete("/v1/mode", operation_id="clearMode")
    async def clear_mode(agent: Agent = Depends(auth)):
        modes_store().clear(agent.name)
        return {"ok": True}

    @app.post("/v1/mode/onboard", operation_id="onboardMode")
    async def onboard_mode(body: ModeOnboard, agent: Agent = Depends(auth)):
        step = onboard_step(body.answers)
        if step.get("done"):
            st = modes_store().set(agent.name, step["mode"], step.get("goal", ""),
                                   step.get("answers"), step.get("map_hud"))
            step["state"] = st.public()
            sess = ACTIVE_SESSIONS.get(agent.name)
            if sess is not None:
                sess.mode_text = mode_prompt(st)
        return step

    def fieldkit_providers() -> dict:
        ps = getattr(app.state, "session_providers", None)
        if ps is None:
            ps = app.state.session_providers = with_failover(provider_presets())
        return ps

    @app.get("/v1/providers", operation_id="listProviders")
    async def list_providers(agent: Agent = Depends(auth)):
        """Which vision presets this relay can serve right now.

        Unconfigured presets stay listed with configured=false (key-free) so a
        client can pick a working one instead of dead-ending on an error.
        """
        ps = fieldkit_providers()
        pubs = presets_public(ps)
        for p in pubs:                       # name of the env var to set, never its value
            if not p.get("configured"):
                p["missing_env"] = ps[p["name"]].api_key_env
        return {"providers": pubs, "configured": configured_providers(ps)}

    @app.post("/v1/missions", operation_id="createMission")
    async def create_mission(request: Request, body: MissionCreate, agent: Agent = Depends(auth)):
        ip = client_ip(request)
        relay.check_missions(agent, ip)
        name = (body.provider or "wonder").strip()
        provider = fieldkit_providers().get(name)
        if provider is None:
            raise HTTPException(404, f"unknown provider '{name}'")
        if not provider.configured:
            raise HTTPException(503, f"provider '{name}' is not configured on this relay"
                                f"{' (missing ' + provider.api_key_env + ')' if provider.api_key_env else ''}"
                                f"; configured here: {', '.join(configured_providers(fieldkit_providers())) or 'none'}")
        # Prior-job memory recall (Second Brain + Jot): last time's field
        # notes inform this plan. Best effort; recall never blocks planning.
        try:
            hints = second_brain.prior_hints(body.goal.strip())
        except Exception:
            hints = []
        plan_hints = [h["note"] for h in hints]
        if mp := agent_mode_prompt(agent.name):
            plan_hints.insert(0, mp)  # first: planner keeps only 3 hints
        # Vision-aware planning: if this agent has a live frame, describe it
        # once so Wonder plans from what is actually there, not blind.
        vision_note = None
        try:
            latest = FRAMES.latest(agent.name)
            if latest is not None:
                from .trio import Eyes
                eye_provider = fieldkit_providers().get("spark") or provider
                if eye_provider.configured:
                    obs = await Eyes(eye_provider,
                                     getattr(app.state, "session_transport", None)
                                     ).observe((latest[0], latest[1]), "")
                    vision_note = obs.text if obs else None
        except Exception:
            vision_note = None
        try:
            plan = await plan_interview(
                provider, body.goal.strip(),
                getattr(app.state, "session_transport", None),
                hints=plan_hints,
                answers=(body.answers or None),
                vision_note=vision_note,
                memories=body.memories)
        except MissionError as e:
            relay.audit.write("mission_create", agent=agent.name, ip=ip, via="rest",
                              result="error", status=e.status, provider=name)
            raise HTTPException(e.status, e.detail)
        if plan.status == "clarify":
            relay.audit.write("mission_clarify", agent=agent.name, ip=ip, via="rest",
                              result="clarify", questions=len(plan.questions))
            return {"status": "clarify", "questions": plan.questions,
                    "prior_notes": hints}
        steps = plan.steps
        m = missions_store().create(body.goal.strip(), steps, provider=name)
        # ids + counts only: goals and steps are never written to the audit log
        relay.audit.write("mission_create", agent=agent.name, ip=ip, via="rest", result="ok",
                          mission=m.id, provider=name, steps=len(steps))
        # Mirrors: Hermes Jot live checklist + Obsidian engram. Both are
        # best-effort mirrors — the mission is the source of truth.
        jot_res = await jot_bridge.sync_new_mission(m)
        try:
            second_brain.compile_mission(m)
        except Exception:
            pass
        out = m.public()
        out["status"] = "ready"
        out["jot"] = jot_res
        out["prior_notes"] = hints
        return JSONResponse(out, status_code=201)

    @app.get("/v1/missions/latest", operation_id="latestMission")
    async def latest_mission(agent: Agent = Depends(auth)):
        relay.check_missions(agent, "?")
        m = missions_store().latest()
        if m is None:
            raise HTTPException(404, "no missions yet")
        return m.public()

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
        done_n = m.current
        jot_res = None
        if body.type in ("advance", "skip"):
            store.apply_event(mission_id, body.type)
            say = m.current_step_text or "Mission complete."
            ev_name = "advanced" if body.type == "advance" else "skipped"
            if body.type == "advance":
                jot_res = await jot_bridge.checkoff(mission_id, done_n)
        elif body.type == "assess":
            provider = fieldkit_providers().get(m.provider)
            if provider is None or not provider.configured:
                raise HTTPException(503, f"mission provider '{m.provider}' is not available")
            try:
                ev = await assess_step(provider, m, (body.observation or "")[:2000],
                                       getattr(app.state, "session_transport", None))
            except MissionError as e:
                raise HTTPException(e.status, e.detail)
            m.last_event = ev.type
            if ev.type == "step_done":
                store.apply_event(mission_id, "advance")
                jot_res = await jot_bridge.checkoff(mission_id, done_n)
            say = ev.say or (m.current_step_text or "Mission complete.")
            ev_name = ev.type
        else:
            raise HTTPException(400, f"unknown mission event '{body.type}'")
        relay.audit.write("mission_event", agent=agent.name, ip=ip, via="rest", result="ok",
                          mission=mission_id, kind=ev_name, current=m.current)
        # Refresh the Obsidian engram so the vault tracks live progress.
        try:
            second_brain.compile_mission(
                m, events=[{"type": ev_name, "text": say}] if ev_name in
                ("unsafe", "off_track") else None)
        except Exception:
            pass
        recap_res = await recap.maybe_recap(
            m, getattr(app.state, "session_transport", None)) if m.complete else None
        return {"event": ev_name, "say": say, "mission": m.public(),
                "jot": jot_res, "recap": recap_res}

    # ---------------------------------------------------------- People & Voices (Nov 2)
    def _people_rest(request, agent):
        ip = client_ip(request)
        relay.check_sessions(agent, ip)
        return people_for(agent, ip), ip

    @app.get("/v1/people", operation_id="listPeople")
    async def people_list(request: Request, agent: Agent = Depends(auth)):
        """People & Voices: everyone the wearer saved, with the frame crop and time saved.
        Local to this relay; never sent anywhere."""
        store, _ = _people_rest(request, agent)
        return JSONResponse({"people": store.listing(), "encrypted": store.encrypted},
                            headers={"Cache-Control": "no-store"})

    @app.post("/v1/people", operation_id="savePerson")
    async def people_save(request: Request, body: PersonSave, agent: Agent = Depends(auth)):
        store, ip = _people_rest(request, agent)
        try:
            p = store.save_person(body.name, body.descriptor, body.thumb or "", body.voice,
                                  body.at, body.relation, body.note)
        except ValueError as e:
            raise HTTPException(400, str(e))
        relay.audit.write("people", agent=agent.name, ip=ip, via="rest", result="saved")
        return store.public(p)

    @app.patch("/v1/people/{person_id}", operation_id="renamePerson")
    async def people_rename(request: Request, person_id: str, body: PersonPatch,
                            agent: Agent = Depends(auth)):
        store, _ = _people_rest(request, agent)
        p = store.rename(person_id, body.name)
        if p is None:
            raise HTTPException(404, "no such person")
        return store.public(p)

    @app.post("/v1/people/{person_id}/voice", operation_id="savePersonVoice")
    async def people_voice(request: Request, person_id: str, body: VoiceSave,
                           agent: Agent = Depends(auth)):
        store, _ = _people_rest(request, agent)
        p = store.add_voice(person_id, body.voice)
        if p is None:
            raise HTTPException(404, "no such person or empty voice print")
        return store.public(p)

    @app.delete("/v1/people/{person_id}/voice", operation_id="forgetPersonVoice")
    async def people_forget_voice(request: Request, person_id: str, agent: Agent = Depends(auth)):
        store, _ = _people_rest(request, agent)
        if not store.forget_voice(person_id):
            raise HTTPException(404, "no saved voice")
        return {"ok": True}

    @app.delete("/v1/people/{person_id}", operation_id="forgetPerson")
    async def people_forget(request: Request, person_id: str, agent: Agent = Depends(auth)):
        store, ip = _people_rest(request, agent)
        if not store.forget(person_id):
            raise HTTPException(404, "no such person")
        relay.audit.write("people", agent=agent.name, ip=ip, via="rest", result="forgot")
        return {"ok": True}

    @app.delete("/v1/people", operation_id="forgetAllPeople")
    async def people_forget_all(request: Request, agent: Agent = Depends(auth)):
        store, ip = _people_rest(request, agent)
        n = store.clear()
        relay.audit.write("people", agent=agent.name, ip=ip, via="rest", result="forgot_all", n=n)
        return {"ok": True, "deleted": n}

    @app.post("/v1/people/analyze", operation_id="analyzePersonBoard")
    async def people_analyze(request: Request, body: PersonAnalyzeRequest, agent: Agent = Depends(auth)):
        """Local Relay visual feature & attire analyzer for 4-Angle Character Boards."""
        ip = client_ip(request)
        relay.check_sessions(agent, ip)
        res = await enrichment.analyze_person_image(
            body.image,
            attire_hint=body.attire_hint,
            build_hint=body.build_hint,
            transport=getattr(app.state, "ask_transport", None),
        )
        return JSONResponse(res, headers={"Cache-Control": "no-store"})

    @app.get("/v1/context/snapshot", operation_id="contextSnapshot")
    async def context_snapshot(request: Request, agent: Agent = Depends(auth)):
        """Returns a pre-digested situational & Saved People context snapshot for any cloud or local model."""
        ip = client_ip(request)
        store = people_for(agent, ip)
        sess = ACTIVE_SESSIONS.get(agent.name) or LAST_SESSIONS.get(agent.name)
        snap = enrichment.build_model_assist_digest(
            agent.name,
            people_store=store,
            session=sess,
            mission_store=missions_store(),
        )
        return JSONResponse(snap, headers={"Cache-Control": "no-store"})

    @app.get("/v1/sessions/replay", operation_id="sessionReplay")
    async def session_replay(agent: Agent = Depends(auth)):
        """'What it saw': frames used, SAM 3.1 boxes (or labelled stand-in),
        steps given, timestamps, and which model answered each step."""
        sess = ACTIVE_SESSIONS.get(agent.name) or LAST_SESSIONS.get(agent.name)
        if sess is None:
            raise HTTPException(404, "no session to replay yet")
        return await recap.build_replay(sess)

    @app.post("/v1/missions/{mission_id}/recap", operation_id="missionRecap")
    async def mission_recap(request: Request, mission_id: str,
                            agent: Agent = Depends(auth)):
        ip = client_ip(request)
        relay.check_missions(agent, ip)
        m = missions_store().get(mission_id)
        if m is None:
            raise HTTPException(404, "unknown mission")
        res = await recap.maybe_recap(
            m, getattr(app.state, "session_transport", None), force=True)
        relay.audit.write("mission_recap", agent=agent.name, ip=ip, via="rest",
                          result="ok" if res.get("ok") else "error", mission=mission_id,
                          source=res.get("source", ""))
        return {"recap": res, "mission": m.public()}

    # Live Mode (D5): confirm-gated public streaming. The gate issues a
    # one-time confirm token; only an explicit repeat with it goes live.
    from .live_mode import LIVE

    @app.get("/v1/live", operation_id="liveStatus")
    async def live_status(agent: Agent = Depends(auth)):
        relay.check_missions(agent, "?")
        return LIVE.status()

    @app.post("/v1/live/start", operation_id="liveStart")
    async def live_start(request: Request, body: dict, agent: Agent = Depends(auth)):
        ip = client_ip(request)
        relay.check_missions(agent, ip)
        res = LIVE.start(str(body.get("confirm") or "") or None,
                         str(body.get("destination") or ""))
        relay.audit.write("live_start", agent=agent.name, ip=ip, via="rest",
                          result="live" if res.get("ok") else "needs_confirm")
        return res

    @app.post("/v1/live/stop", operation_id="liveStop")
    async def live_stop(request: Request, agent: Agent = Depends(auth)):
        ip = client_ip(request)
        relay.check_missions(agent, ip)
        res = LIVE.stop()
        relay.audit.write("live_stop", agent=agent.name, ip=ip, via="rest", result="stopped")
        return res

    # ------------------------------------------------------------------ RTMP & Live Broadcast Bridge
    from .rtmp_streamer import STREAMER, PRESETS as RTMP_PRESETS

    @app.get("/v1/rtmp", operation_id="rtmpStatus")
    async def rtmp_status():
        return STREAMER.status()

    @app.post("/v1/rtmp/start", operation_id="rtmpStart")
    async def rtmp_start(request: Request, body: dict, agent: Agent = Depends(auth)):
        ip = client_ip(request)
        relay.check_missions(agent, ip)
        preset = str(body.get("preset") or "")
        stream_key = str(body.get("stream_key") or "")
        dest = str(body.get("destination") or "")
        fps = int(body.get("fps") or 20)
        bitrate = str(body.get("bitrate") or "2500k")
        try:
            res = STREAMER.start(destination=dest, preset=preset, stream_key=stream_key, fps=fps, bitrate=bitrate)
            relay.audit.write("rtmp_start", agent=agent.name, ip=ip, via="rest", result="started")
            return res
        except Exception as e:
            raise HTTPException(400, str(e))

    @app.post("/v1/rtmp/stop", operation_id="rtmpStop")
    async def rtmp_stop(request: Request, agent: Agent = Depends(auth)):
        ip = client_ip(request)
        relay.check_missions(agent, ip)
        res = STREAMER.stop()
        relay.audit.write("rtmp_stop", agent=agent.name, ip=ip, via="rest", result="stopped")
        return res

    @app.post("/v1/camera/frame", operation_id="cameraPushFrame")
    async def camera_push_frame(request: Request, agent: Agent = Depends(auth)):
        body = await request.body()
        if body.startswith(b"{"):
            try:
                data = json.loads(body)
                b64 = data.get("image") or data.get("frame")
                if b64:
                    raw = base64.b64decode(b64)
                    STREAMER.push_frame(raw)
                    return {"ok": True, "size": len(raw)}
            except Exception as e:
                raise HTTPException(400, f"Invalid JSON frame: {e}")
        elif body:
            STREAMER.push_frame(body)
            return {"ok": True, "size": len(body)}
        raise HTTPException(400, "Empty frame payload")

    @app.get("/v1/camera/frame.jpg", include_in_schema=False)
    async def camera_frame():
        frame = STREAMER.get_latest_frame()
        if not frame:
            raise HTTPException(404, "No camera frame available yet")
        return Response(content=frame, media_type="image/jpeg")

    @app.get("/v1/camera/live.mjpg", include_in_schema=False)
    async def camera_mjpg():
        """MJPEG live video stream for OBS Studio (Browser/Media Source), VLC, or browser."""
        import asyncio
        async def frame_generator():
            while True:
                frame = STREAMER.get_latest_frame()
                if frame:
                    yield (b"--frame\r\n"
                           b"Content-Type: image/jpeg\r\n\r\n" + frame + b"\r\n")
                await asyncio.sleep(0.066)
        return StreamingResponse(frame_generator(), media_type="multipart/x-mixed-replace; boundary=frame")

    @app.websocket("/v1/sessions")
    async def sessions(ws: WebSocket):
        ip = ws.headers.get("cf-connecting-ip") or (ws.client.host if ws.client else "?")
        try:
            # Header auth for apps; ?token= for browser WebSockets (PWA cannot
            # set headers). Same agent scopes either way.
            agent = relay.authenticate(
                _bearer(ws.headers) or ws.query_params.get("token", ""), ip, "ws", "/v1/sessions")
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
            providers = app.state.session_providers = with_failover(provider_presets())
        name = str(hello.get("provider") or "spark")
        provider = providers.get(name)
        if provider is None:
            await ws.send_json({"type": "error", "detail": f"unknown provider '{name}'"})
            await ws.close(code=1000)
            return
        if not provider.configured:
            # Same detail string, plus the two facts a client needs to recover:
            # which env var is missing and which presets DO work here.
            await ws.send_json({"type": "error",
                                "detail": f"provider '{name}' is not configured on this relay",
                                "provider": name,
                                "missing_env": provider.api_key_env,
                                "available": configured_providers(providers)})
            await ws.close(code=1000)
            return
        sess = Session(provider, ws.send_json, getattr(app.state, "session_transport", None),
                       agent_name=agent.name)
        sess.set_hello(str(hello.get("mission") or "")[:2000], str(hello.get("system") or "")[:4000],
                       hello.get("history_budget"))
        if hello.get("eyes") in ("phone", "glasses", "off"):
            sess.eyes_source = hello["eyes"]
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
        sess.mode_text = agent_mode_prompt(agent.name)
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
                        vp = clean_vector(m.get("voice_print"), 128)
                        hp = people_for(agent, ip).match_voice(vp) if vp and \
                            str(m.get("speaker") or "") != "wearer" else None
                        sess.heard = hp.name if hp else ""
                        # live context first: speaker, honest command, focus, sighting
                        sess.set_live(ws_ctx(sess, m, agent, ip))
                        await sess.on_user_speech(m["text"][:2000],
                                                  str(m.get("speaker") or "unknown"),
                                                  m.get("sound_relative"))
                elif t == "text" and isinstance(m.get("text"), str):
                    sess.start_turn(m["text"][:2000], t)
                elif t == "jot" and isinstance(m.get("text"), str):
                    await sess.jot_down(m["text"][:500])
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
                elif t == "eyes":
                    # live eyes switch: same session, same context, new vision source
                    src = str(m.get("source") or "")
                    if src in ("phone", "glasses", "off"):
                        changed = sess.set_eyes(src)
                        await ws.send_json({"type": "eyes", "source": sess.eyes_source,
                                            "switched": changed})
                        await ws.send_json(sess.state())
                elif t == "detections":
                    d = sess.on_detections(m)
                    store = people_for(agent, ip)
                    names = match_in_view(store, m.get("descriptors"))
                    if isinstance(m.get("names"), list):        # matched on the phone already
                        names += [str(n)[:80] for n in m["names"] if isinstance(n, str)
                                  and any(p.name.lower() == str(n).strip().lower() for p in store.named())]
                    new = sess.set_in_view(names)
                    if new:
                        await ws.send_json({"type": "person_in_view", "names": new})
                    await ws.send_json({"type": "detections", **d, "in_view": sess.in_view})
                elif t == "remember":
                    # "remember this person as <name>": the wearer's explicit tap/command only
                    if str(m.get("speaker") or "wearer") != "wearer":
                        await ws.send_json({"type": "error", "detail": "only the wearer can save a person"})
                        continue
                    name = str(m.get("name") or "").strip()[:80]
                    try:
                        if not name:
                            raise ValueError("need a name")
                        p = people_for(agent, ip).save_person(
                            name, m.get("descriptor"), m.get("thumb") or "", m.get("voice"),
                            m.get("at") if isinstance(m.get("at"), (int, float)) else None)
                    except ValueError as e:
                        await ws.send_json({"type": "error", "detail": f"remember: {e}"})
                        continue
                    sess.set_in_view(sess.in_view + [p.name])
                    relay.audit.write("people", agent=agent.name, ip=ip, via="ws", result="saved")
                    await ws.send_json({"type": "remembered", "id": p.id, "name": p.name,
                                        "has_face": bool(p.descriptor), "has_voice": bool(p.voice)})
                elif t == "barge":
                    await sess.barge()
                elif t == "bye":
                    break
        except (WebSocketDisconnect, RuntimeError, ValueError):
            pass
        finally:
            if ACTIVE_SESSIONS.get(agent.name) is sess:
                ACTIVE_SESSIONS.pop(agent.name, None)
            LAST_SESSIONS[agent.name] = sess   # kept (bounded log) for the replay view
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

    # Judge-facing PWA proof client (D6): same Sessions/Missions APIs as the
    # glasses app, no glasses needed. Static shell only — all data stays
    # behind the token-gated APIs.
    from pathlib import Path as _Path

    _pwa = _Path(__file__).parent / "pwa"

    @app.get("/pwa", include_in_schema=False)
    async def pwa_page():
        return HTMLResponse((_pwa / "index.html").read_text(encoding="utf-8"))

    @app.get("/overlay", include_in_schema=False)
    async def overlay_page():
        return HTMLResponse((_pwa / "overlay.html").read_text(encoding="utf-8"))

    @app.get("/pwa/manifest.webmanifest", include_in_schema=False)
    async def pwa_manifest():
        return HTMLResponse((_pwa / "manifest.webmanifest").read_text(encoding="utf-8"),
                            media_type="application/manifest+json")

    @app.get("/pwa/sw.js", include_in_schema=False)
    async def pwa_sw():
        return HTMLResponse((_pwa / "sw.js").read_text(encoding="utf-8"),
                            media_type="application/javascript")

    @app.get("/pwa/presence.js", include_in_schema=False)
    async def pwa_presence():
        return HTMLResponse((_pwa / "presence.js").read_text(encoding="utf-8"),
                            media_type="application/javascript; charset=utf-8")

    @app.get("/pwa/maphud.js", include_in_schema=False)
    async def pwa_maphud():
        return HTMLResponse((_pwa / "maphud.js").read_text(encoding="utf-8"),
                            media_type="application/javascript; charset=utf-8")

    @app.get("/pwa/slides", include_in_schema=False)
    @app.get("/pwa/slides.html", include_in_schema=False)
    async def pwa_slides():
        return HTMLResponse((_pwa / "slides.html").read_text(encoding="utf-8"))

    @app.exception_handler(RelayError)
    async def _relay_err(_, exc: RelayError):
        return JSONResponse({"detail": exc.detail}, status_code=exc.status)

    # MCP streamable-HTTP route(s) from the SDK app, guarded by MCPGuard below.
    for route in mcp_app.routes:
        app.router.routes.append(route)

    return MCPGuard(app, relay)
