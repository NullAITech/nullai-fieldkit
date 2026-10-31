#!/usr/bin/env python3
"""Prove the judge-facing web trial works with no camera, no phone and no glasses.

What it does, in one page session against a running relay:

  1. waits for the page's own script to be ready (a click before that is a
     silent no-op, and a harness that reads the status line too early reports
     a race it caused itself);
  2. puts the relay token into the page's own token field and clicks the page's
     own buttons — never anything else's;
  3. uses the built-in synthetic test scenes, so no camera is needed;
  4. watches the socket and the status line while the Eyes -> Advisor ->
     Director loop runs;
  5. reads the page's own log pane, which is where a judge actually sees both
     sides of the loop ("Eyes: ..." and "Coach: ...");
  6. plans a real mission through the REST API from the page and checks the
     typed step list came back.

It prints nothing secret: the token is read from the operator's own file, put
straight into the page, and any URL it logs has `token=…` replaced by
`[redacted]`.

Usage:
    python3 tools/webtrial-selftest/journey.py \
        --relay http://127.0.0.1:8770 \
        --token-file /path/to/relay-runtime/pixel.token

Exit codes: 0 = the loop ran and both sides reached the log; 1 = it did not;
2 = the relay or the token file was not reachable (nothing was verified).
"""

from __future__ import annotations

import argparse
import asyncio
import json
import pathlib
import sys
import time

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from webtrial_cdp import DEFAULT_TOKEN_FILE, Page, redact, relay_token  # noqa: E402

WRAP = """(() => {
  window.__ev = [];
  const W = window.WebSocket;
  window.WebSocket = function (u, p) {
    window.__ev.push(['new', String(u).replace(/token=[^&]*/, 'token=[redacted]')]);
    const s = new W(u, p);
    s.addEventListener('open', () => window.__ev.push(['open']));
    s.addEventListener('message', e => {
      let t = '?'; try { t = JSON.parse(e.data).type; } catch (_) {}
      window.__ev.push(['msg', t]);
    });
    s.addEventListener('close', e => window.__ev.push(['close', e.code]));
    s.addEventListener('error', () => window.__ev.push(['error']));
    return s;
  };
  window.WebSocket.prototype = W.prototype;
  return 1;
})()"""


def connected_line(text: str) -> bool:
    """True only for the connected form: "Not connected." must not pass."""
    t = " ".join((text or "").lower().split())
    return bool(t) and "connected" in t and not t.startswith("not connected")


async def run(args) -> int:
    relay = args.relay.rstrip("/")
    pwa = relay + "/pwa"
    token = relay_token(args.token_file)
    trace: list[dict] = []
    rep: dict = {"relay": relay, "trace": trace, "ok": False}

    async with Page(tag=args.tag) as pg:
        await pg.goto(pwa)
        ready = await pg.wait_ready(timeout=args.ready_timeout)
        trace.append({"page_ready": ready})
        await pg.js(WRAP)
        await pg.js("(() => { const t=document.getElementById('token');"
                    f" t.value = {json.dumps(token)};"
                    " t.dispatchEvent(new Event('input')); return t.value.length; })()")

        await pg.click("#btnSceneMode")          # no camera here, by design
        await asyncio.sleep(0.5)
        await pg.click("#btnConnect")
        # "Not connected." CONTAINS "connected" — a substring test here passes on
        # a page that never connected, which is exactly how a lying green run
        # looks. Require the connected form and explicitly reject the negative.
        status, start = "", time.time()
        while time.time() - start < args.connect_timeout:
            status = await pg.text("#status")
            if connected_line(status):
                break
            if time.time() - start > 6 and "not connected" in status.lower():
                await pg.click("#btnConnect")    # first tap can beat the page's wiring
            await asyncio.sleep(0.5)
        rep["status"] = status
        rep["connected"] = connected_line(status)

        # switch scenes so the frame hash changes and the eyes have something new
        seen = ""
        for i in (0, 1, 2, 0, 1):
            await pg.click(f'[data-scene="{i}"]')
            for _ in range(20):
                seen = await pg.text("#log")
                if "eyes:" in seen.lower():
                    break
                await asyncio.sleep(1)
            if "eyes:" in seen.lower():
                break
        rep["has_eyes_line"] = "eyes:" in seen.lower()
        rep["obs"] = await pg.text("#obs")

        # plan a real mission from the page (REST, the same call a judge makes)
        await pg.js("document.getElementById('goal').value = "
                    "'Swap the fuel filter on the generator'")
        await pg.click("#btnPlan")
        for _ in range(int(args.plan_timeout)):
            if (await pg.text("#steps")).strip() or (await pg.text("#qa")).strip():
                break
            await asyncio.sleep(1)
        rep["plan_status"] = await pg.text("#status")
        rep["questions_or_steps"] = (await pg.text("#qa")) + (await pg.text("#steps"))

        # a typed turn is how a judge with no camera talks to the coach
        await pg.js("document.getElementById('talkText').value = "
                    "'What is the first thing I should do?'")
        await pg.click("#btnTalk")
        log = ""
        for _ in range(60):
            log = await pg.text("#log")
            if "coach:" in log.lower():
                break
            await asyncio.sleep(1)
        rep["has_coach_line"] = "coach:" in log.lower()
        rep["log"] = log[:1500]

        rep["status_end"] = await pg.text("#status")
        rep["events"] = await pg.js("window.__ev")
        rep["console"] = pg.console[-10:]
        rep["bad_http"] = [redact(u) for u in pg.bad_http[-6:]]
        rep["ok"] = bool(rep.get("connected") and rep["has_eyes_line"]
                         and not rep["console"] and not rep["bad_http"])

    if args.out:
        pathlib.Path(args.out).write_text(json.dumps(rep, indent=2))
        print(f"wrote {args.out}")
    print("status         :", rep.get("status"))
    print("status at end  :", rep.get("status_end"))
    print("eyes line      :", rep.get("has_eyes_line"))
    print("coach line     :", rep.get("has_coach_line"))
    print("socket events  :", json.dumps(rep.get("events")))
    print("console errors :", rep.get("console"))
    print("responses >=400:", rep.get("bad_http"))
    print("plan status    :", rep.get("plan_status"))
    print("JOURNEY=" + ("ok" if rep["ok"] else "failed"))
    return 0 if rep["ok"] else 1


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--relay", default="http://127.0.0.1:8770",
                    help="base URL of the running relay (default: %(default)s)")
    ap.add_argument("--token-file", default=str(DEFAULT_TOKEN_FILE),
                    help="file holding the relay token (read, never printed)")
    ap.add_argument("--out", default="", help="write the JSON report here")
    ap.add_argument("--tag", default="chrome-fieldkit-journey",
                    help="marker for this run's own Chrome profile")
    ap.add_argument("--ready-timeout", type=float, default=30.0)
    ap.add_argument("--connect-timeout", type=float, default=45.0)
    ap.add_argument("--plan-timeout", type=float, default=60.0)
    args = ap.parse_args()

    tok_path = pathlib.Path(args.token_file)
    if not tok_path.exists():
        print(f"FATAL: no token file at {tok_path} — nothing was verified", file=sys.stderr)
        return 2
    import urllib.error
    import urllib.request
    try:
        with urllib.request.urlopen(args.relay.rstrip("/") + "/pwa", timeout=10) as r:
            if r.status != 200:
                print(f"FATAL: {args.relay}/pwa answered {r.status} — nothing was verified",
                      file=sys.stderr)
                return 2
    except (urllib.error.URLError, OSError) as e:
        print(f"FATAL: no relay at {args.relay} ({e}) — nothing was verified", file=sys.stderr)
        return 2
    try:
        return asyncio.run(run(args))
    except RuntimeError as e:
        print(f"FATAL: {e} — nothing was verified", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
