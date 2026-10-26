#!/usr/bin/env python3
"""Synthetic soak test for WS /v1/guide/stream with the REAL AGY, Ollama vision and local TTS.

Starts a private relay instance on 127.0.0.1:<port> from <app_dir> (so 0.4.0 and 0.5.0 can be
compared), using the guide: section of the live mobile config, a throwaway token (random, never
printed or written outside a temp dir) and its own AGY mirror log. A fake phone sends a scene-change
frame every `--scene` s (frames sent ~1/s like the app) and a spoken question every `--ask` s.

Measures per minute: speech -> first spoken line, speech -> first audio byte, AGY turn time
(thinking -> listening), captions/min, unprompted directives/min, CPU temp, relay RSS.
Usage: guide_bench.py <app_dir> <frames_dir> --minutes 10 --out result.json
"""
import argparse, asyncio, base64, glob, json, os, resource, secrets, statistics, sys, tempfile, time

ap = argparse.ArgumentParser()
ap.add_argument("app_dir"); ap.add_argument("frames_dir")
ap.add_argument("--minutes", type=float, default=10); ap.add_argument("--port", type=int, default=8799)
ap.add_argument("--scene", type=float, default=6); ap.add_argument("--ask", type=float, default=30)
ap.add_argument("--out", default="bench.json"); ap.add_argument("--stream", default="{}", help="JSON overrides for guide.stream"); ap.add_argument("--mission", default="Explore this place and find something interesting to talk about")
a = ap.parse_args()
sys.path.insert(0, os.path.abspath(a.app_dir))

import yaml, uvicorn, websockets  # noqa: E402
from zoth_relay.config import hash_token, load_config  # noqa: E402
from zoth_relay.guide import cpu_temp_c  # noqa: E402
from zoth_relay.server import create_app  # noqa: E402

QUESTIONS = ["what should I do next", "what do you see", "okay done, what now", "where should I go",
             "is there anything interesting here", "give me the next step"]


def rss_mb():
    try:
        for line in open("/proc/self/status"):
            if line.startswith("VmRSS"):
                return int(line.split()[1]) / 1024
    except OSError:
        return None


async def main():
    tmp = tempfile.mkdtemp(prefix="zoth-bench-")
    live = yaml.safe_load(open(os.path.expanduser("~/.config/zoth-relay/mobile/config.yaml")))
    guide = live["guide"]
    guide.setdefault("stream", {}).update(json.loads(a.stream))
    guide["stream"]["mirror"] = os.path.join(tmp, "agy-bench.log")
    guide.setdefault("agy", {})["workdir"] = os.path.join(tmp, "agy-ws")
    tok = "zr_bench_" + secrets.token_urlsafe(24)
    yaml.safe_dump({"agents": {"bench": {"token_sha256": hash_token(tok), "profile": "mobile",
                                          "allowed_dirs": [tmp]}}}, open(os.path.join(tmp, "agents.yaml"), "w"))
    yaml.safe_dump({"host": "127.0.0.1", "port": a.port, "agents_file": "agents.yaml", "allowed_dirs": [tmp],
                    "audit_log": os.path.join(tmp, "audit.log"), "guide": guide}, open(os.path.join(tmp, "config.yaml"), "w"))
    app = create_app(load_config(os.path.join(tmp, "config.yaml")))
    server = uvicorn.Server(uvicorn.Config(app, host="127.0.0.1", port=a.port, log_level="warning", ws_max_size=16 * 2**20))
    st = asyncio.create_task(server.serve())
    while not server.started:
        await asyncio.sleep(0.1)

    frames = [base64.b64encode(open(f, "rb").read()).decode() for f in sorted(glob.glob(os.path.join(a.frames_dir, "*.jpg")))]
    ev = []                     # (t, kind, extra)
    t0 = time.monotonic()
    end = t0 + a.minutes * 60
    pending_ask = None
    async with websockets.connect(f"ws://127.0.0.1:{a.port}/v1/guide/stream", max_size=None,
                                  additional_headers={"Authorization": f"Bearer {tok}"}) as ws:
        await ws.send(json.dumps({"type": "hello", "mission": a.mission}))

        async def reader():
            nonlocal pending_ask
            thinking_at = None
            async for m in ws:
                t = time.monotonic() - t0
                if isinstance(m, bytes):
                    if pending_ask and pending_ask.get("audio") is None and pending_ask.get("say") is not None:
                        pending_ask["audio"] = t - pending_ask["t"]
                    continue
                j = json.loads(m)
                k = j.get("type")
                if k == "say":
                    ev.append((t, "say", None))
                    if pending_ask and pending_ask.get("say") is None:
                        pending_ask["say"] = t - pending_ask["t"]
                elif k == "caption":
                    ev.append((t, "caption", j.get("text", "")[:80]))
                elif k == "state":
                    if j.get("text") == "thinking":
                        thinking_at = t
                    elif j.get("text") in ("listening", "paused") and thinking_at is not None:
                        ev.append((t, "turn", t - thinking_at)); thinking_at = None
                elif k == "error":
                    ev.append((t, "error", j.get("text", "")[:120]))

        rd = asyncio.create_task(reader())
        asks, i, scene_i, next_ask, next_scene = [], 0, 0, time.monotonic() + 20, time.monotonic()
        temps = []
        while time.monotonic() < end:
            now = time.monotonic()
            if now >= next_scene:
                scene_i = (scene_i + 1) % len(frames); next_scene = now + a.scene
            await ws.send(json.dumps({"type": "frame", "image": frames[scene_i]}))
            if now >= next_ask:
                q = QUESTIONS[i % len(QUESTIONS)]; i += 1
                pending_ask = {"t": now - t0, "q": q, "say": None, "audio": None}
                asks.append(pending_ask)
                await ws.send(json.dumps({"type": "barge"}))
                await ws.send(json.dumps({"type": "speech", "text": q}))
                next_ask = now + a.ask
            if int(now - t0) % 15 == 0:
                temps.append((now - t0, cpu_temp_c(), rss_mb()))
            await asyncio.sleep(1.0)
        await asyncio.sleep(8)
        await ws.send(json.dumps({"type": "bye"}))
        rd.cancel()

    hub = app.app.state.stream_hub
    agy_stats = [{"t": round(t - (time.time() - (time.monotonic() - t0)), 1), "first_s": f, "total_s": tot, "chars": c}
                 for t, f, tot, c in getattr(hub.agy, "stats", [])]
    await hub.down()
    server.should_exit = True
    await st

    def per_min(kind):
        out = {}
        for t, k, x in ev:
            if k == kind:
                out.setdefault(int(t // 60), []).append(x)
        return out
    mins = int(a.minutes) + 1
    turns, caps, says = per_min("turn"), per_min("caption"), per_min("say")
    rows = []
    for m in range(mins):
        aq = [x for x in asks if int(x["t"] // 60) == m]
        rows.append({"min": m,
                     "ask_to_say_s": [round(x["say"], 2) if x["say"] is not None else None for x in aq],
                     "ask_to_audio_s": [round(x["audio"], 2) if x["audio"] is not None else None for x in aq],
                     "turn_s_median": round(statistics.median(turns[m]), 2) if turns.get(m) else None,
                     "turns": len(turns.get(m, [])), "captions": len(caps.get(m, [])), "says": len(says.get(m, [])),
                     "temp_c": max([t for tt, t, _ in temps if int(tt // 60) == m and t] or [0]) or None})
    bad_caps = [x for t, k, x in ev if k == "caption" and any(s in x.lower() for s in ("ids", "[", "0.", "0, 1"))]
    res = {"app": a.app_dir, "minutes": a.minutes, "rows": rows, "errors": [x for t, k, x in ev if k == "error"][:20],
           "rolls": getattr(hub, "rolls", None), "agy_turns_last_session": hub.agy.turns,
           "degenerate_captions": len(bad_caps), "caption_samples": [x for t, k, x in ev if k == "caption"][:6],
           "max_rss_mb": max([r for _, _, r in temps if r] or [0])}
    res["agy_stats"] = agy_stats
    json.dump(res, open(a.out, "w"), indent=1)
    for r in rows:
        print(json.dumps(r))
    print(json.dumps({k: v for k, v in res.items() if k != "rows"}))

asyncio.run(main())
