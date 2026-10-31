"""Headless-Chrome-over-CDP helper for driving the FieldKit web trial.

Why this exists: the judge-facing page at `/pwa` is the fallback demo path when
no glasses and no phone are on the table, so it has to be provable without
either. This drives a private headless Chrome over the DevTools protocol using
only the standard library (plus `websockets`), reads loopback only, and never
touches a browser or a relay process the operator is already using.

Three rules are baked in, each learned the hard way:

  1. Pick a CDP port that is PROVEN free (bind it yourself first). If the port is
     already listening, Chrome silently falls back to an IPv6-only bind and an
     IPv4-only probe then reports "chrome never came up" while Chrome is fine.
  2. Probe BOTH 127.0.0.1 and [::1] while waiting for the devtools endpoint.
  3. Kill any Chrome left holding our own profile marker BEFORE launching, and
     clean up on every exit path. A crashed run otherwise leaves a stale browser
     holding the profile, and the next run then drives a half-loaded page and
     misreads its state (which is exactly how a "Not connected." reading shows up
     on a page that is connected and live).

The relay token is read from a file the operator owns and is never printed: any
URL logged through this helper has `token=…` replaced with `[redacted]`.
"""

from __future__ import annotations

import asyncio
import json
import os
import pathlib
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import time
import urllib.request

CHROME = os.environ.get("CHROME_BIN", "/usr/bin/google-chrome")
DEFAULT_RELAY = os.environ.get("FIELDKIT_RELAY", "http://127.0.0.1:8770")
DEFAULT_TOKEN_FILE = os.environ.get(
    "FIELDKIT_TOKEN_FILE",
    "/home/zoth/Desktop/Zoth-Meta-Hackathon/relay-runtime/pixel.token",
)
SCRATCH = pathlib.Path(os.environ.get(
    "FIELDKIT_SELFTEST_TMP",
    str(pathlib.Path(tempfile.gettempdir()) / "fieldkit-webtrial-selftest"),
))


def redact(url: str) -> str:
    out, tail = [], url
    while "token=" in tail:
        head, tail = tail.split("token=", 1)
        end = min([i for i in (tail.find("&"), tail.find("#"), len(tail)) if i != -1])
        out.append(head + "token=[redacted]")
        tail = tail[end:]
    return "".join(out) + tail


def free_port(lo: int = 9410, hi: int = 9499) -> int:
    """A port nothing is listening on (verified by binding it ourselves)."""
    for p in range(lo, hi):
        s = socket.socket()
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            s.bind(("127.0.0.1", p))
            s.close()
            return p
        except OSError:
            s.close()
    raise RuntimeError("no free port in range")


def relay_token(path: str | pathlib.Path = DEFAULT_TOKEN_FILE) -> str:
    return pathlib.Path(path).read_text().strip()


def kill_our_chromes(tag: str) -> int:
    """SIGKILL any chrome started with our own profile marker. Returns count."""
    killed = 0
    for entry in os.listdir("/proc"):
        if not entry.isdigit():
            continue
        try:
            cmd = pathlib.Path(f"/proc/{entry}/cmdline").read_bytes().decode("utf-8", "replace")
        except Exception:
            continue
        if CHROME in cmd and tag in cmd:
            try:
                os.kill(int(entry), signal.SIGKILL)
                killed += 1
            except Exception:
                pass
    return killed


class Page:
    """One headless Chrome tab driven over CDP, using only the stdlib."""

    def __init__(self, tag: str = "chrome-fieldkit-selftest"):
        self.tag = tag
        # a per-run profile name means two runs can never fight over one profile
        self.profile = SCRATCH / f"profile-{tag}-{os.getpid()}"
        self.errlog_path = SCRATCH / f"{tag}-stderr.log"
        self.port = free_port()
        self.proc: subprocess.Popen | None = None
        self.ws = None
        self._id = 0
        self.console: list[str] = []
        self.bad_http: list[str] = []

    async def __aenter__(self) -> "Page":
        # a crashed earlier run can leave a chrome holding this profile; that is
        # exactly how a run ends up driving a stale page and misreading the state
        kill_our_chromes(self.tag)
        SCRATCH.mkdir(parents=True, exist_ok=True)
        shutil.rmtree(self.profile, ignore_errors=True)
        self.profile.mkdir(parents=True, exist_ok=True)
        self.errlog = open(self.errlog_path, "wb")
        self.proc = subprocess.Popen(
            [CHROME, "--headless=new", f"--remote-debugging-port={self.port}",
             "--no-sandbox", "--disable-gpu", "--no-first-run", "--disable-dev-shm-usage",
             "--mute-audio", "--window-size=900,1200",
             f"--user-data-dir={self.profile}", "about:blank"],
            stdout=self.errlog, stderr=self.errlog)
        ws_url = await self._find_target()
        import websockets
        self.ws = await websockets.connect(ws_url, max_size=64 * 1024 * 1024)
        await self.send("Page.enable")
        await self.send("Runtime.enable")
        await self.send("Network.enable")
        await self.send("Log.enable")
        return self

    async def __aexit__(self, *exc) -> None:
        try:
            if self.ws is not None:
                await self.ws.close()
        finally:
            # The browser must be DEAD before its profile is removed: a `rm` that
            # races a still-dying Chrome fails with "Directory not empty", and a
            # profile left behind is what makes the next run read a stale page.
            if self.proc is not None:
                self.proc.terminate()
                try:
                    self.proc.wait(timeout=8)
                except subprocess.TimeoutExpired:
                    self.proc.kill()
                    self.proc.wait(timeout=8)
            killed = kill_our_chromes(self.tag)
            self.errlog.close()
            shutil.rmtree(self.profile, ignore_errors=True)
            if self.profile.exists():
                print(f"warning: profile {self.profile} survived cleanup "
                      f"(killed {killed} strays); next run will use a fresh one",
                      file=sys.stderr)

    async def _find_target(self) -> str:
        hosts = [f"127.0.0.1:{self.port}", f"[::1]:{self.port}"]
        for _ in range(120):
            for h in hosts:
                try:
                    with urllib.request.urlopen(f"http://{h}/json/list", timeout=2) as r:
                        pages = [t for t in json.loads(r.read().decode()) if t.get("type") == "page"]
                    if pages:
                        return pages[0]["webSocketDebuggerUrl"]
                except Exception:
                    pass
            await asyncio.sleep(1)
        tail = ""
        try:
            tail = self.errlog_path.read_text(errors="replace")[-800:]
        except Exception:
            pass
        raise RuntimeError(f"chrome never came up on {hosts}. stderr tail: {tail}")

    async def send(self, method: str, **params):
        assert self.ws is not None, "Page.send() before __aenter__"
        self._id += 1
        mid = self._id
        await self.ws.send(json.dumps({"id": mid, "method": method, "params": params}))
        while True:
            msg = json.loads(await self.ws.recv())
            if msg.get("method") == "Runtime.consoleAPICalled":
                args = [a.get("value", a.get("description", "")) for a in msg["params"].get("args", [])]
                self.console.append(" ".join(str(a) for a in args))
            elif msg.get("method") == "Network.responseReceived":
                r = msg["params"]["response"]
                if r.get("status", 200) >= 400:
                    self.bad_http.append(f"{r['status']} {redact(r['url'])}")
            elif msg.get("method") == "Log.entryAdded":
                e = msg["params"]["entry"]
                if e.get("level") in ("error", "warning"):
                    self.console.append(f"[{e['level']}] {e.get('text','')}")
            if msg.get("id") == mid:
                if "error" in msg:
                    raise RuntimeError(msg["error"])
                return msg.get("result", {})

    async def goto(self, url: str) -> None:
        await self.send("Page.navigate", url=url)

    async def js(self, expr: str):
        r = await self.send("Runtime.evaluate", expression=expr, awaitPromise=True, returnByValue=True)
        res = r.get("result", {})
        if r.get("exceptionDetails"):
            raise RuntimeError(r["exceptionDetails"].get("text", "js error"))
        return res.get("value")

    async def text(self, selector: str) -> str:
        v = await self.js(f"document.querySelector({selector!r})?.innerText ?? ''")
        return v or ""

    async def wait_text(self, selector: str, want: str, timeout: float = 40.0,
                        poll: float = 0.5) -> str:
        """Wait until selector's text contains `want`; return the final text either way."""
        deadline = time.time() + timeout
        seen = ""
        while time.time() < deadline:
            seen = await self.text(selector)
            if want.lower() in seen.lower():
                return seen
            await asyncio.sleep(poll)
        return seen

    async def click(self, selector: str) -> bool:
        return bool(await self.js(
            f"(() => {{ const e = document.querySelector({selector!r});"
            f" if (!e) return false; e.click(); return true; }})()"))

    async def wait_ready(self, timeout: float = 30.0) -> str:
        """Wait until the page's own script has run and wired its buttons.

        A click delivered before the inline script executes is a silent no-op —
        the page stays on 'Not connected.' and a harness that reads it too early
        blames the page for a race it caused. `connect` and the button handler
        are both plain function values once the script has run.
        """
        deadline = time.time() + timeout
        seen = ""
        while time.time() < deadline:
            seen = await self.js(
                "(() => document.readyState + '|' + (typeof connect) + '|' +"
                " (typeof document.getElementById('btnConnect').onclick))()")
            if seen == "complete|function|function":
                return seen
            await asyncio.sleep(0.5)
        return seen
