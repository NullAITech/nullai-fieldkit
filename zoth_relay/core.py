"""Transport-independent relay operations. REST, MCP and the dashboard all call these,
so every interface gets identical scoping, sandboxing, rate limiting and audit."""
from __future__ import annotations

import asyncio
import base64
import getpass
import os
import shutil
import signal
import re
import subprocess
import sys
import time
from pathlib import Path
from typing import AsyncIterator

from . import __version__, landlock
from .audit import AuditLog
from .config import Agent, AgentRegistry, Config
from .ratelimit import RateLimiter
from .sandbox import SandboxError, resolve_inside
from .sanitizer import SPLIT_RE, BlockedCommand, _real_program, check_command, normalise

START = time.time()
SAFE_ENV_KEYS = ("PATH", "LANG", "LC_ALL", "HOME", "USER", "LOGNAME", "TERM")
# dev profile: refuse obvious wildcard binds (defence-in-depth; ufw default-deny-incoming
# is the network guard, Landlock limits *which ports* can be bound).
WILDCARD_BIND_RE = re.compile(r"0\.0\.0\.0|\[::\]|(--host|--hostname|--bind|-b|host)[= ](::|0|\*)(\s|$|:)")


def dev_env(root: Path, ports: str) -> dict:
    """Private HOME-like environment inside the dev workspace so installs work under
    ProtectHome/ProtectSystem (nothing is written outside `root`)."""
    home, cache = root / ".home", root / ".cache"
    for d in (home, cache, root / ".tmp", home / ".local" / "bin", home / ".npm-global"):
        d.mkdir(parents=True, exist_ok=True, mode=0o750)
    first = ports.split(",")[0].split("-")[0]
    return {
        "HOME": str(home), "TMPDIR": str(root / ".tmp"),
        "XDG_CACHE_HOME": str(cache), "XDG_CONFIG_HOME": str(home / ".config"),
        "XDG_DATA_HOME": str(home / ".local" / "share"),
        "npm_config_cache": str(cache / "npm"), "npm_config_prefix": str(home / ".npm-global"),
        "npm_config_update_notifier": "false", "npm_config_fund": "false",
        "PNPM_HOME": str(home / ".local" / "share" / "pnpm"), "COREPACK_HOME": str(cache / "corepack"),
        "PIP_CACHE_DIR": str(cache / "pip"), "PIP_REQUIRE_VIRTUALENV": "true", "PIP_DISABLE_PIP_VERSION_CHECK": "1",
        "CARGO_HOME": str(home / ".cargo"), "GOPATH": str(home / "go"), "GOCACHE": str(cache / "go-build"),
        "GIT_CONFIG_GLOBAL": str(home / ".gitconfig"), "GIT_CONFIG_NOSYSTEM": "1",
        "PATH": ":".join([str(root / ".venv" / "bin"), str(root / "node_modules" / ".bin"),
                          str(home / ".npm-global" / "bin"), str(home / ".local" / "bin"),
                          str(home / ".cargo" / "bin"), "/usr/local/bin", "/usr/bin", "/bin"]),
        "HOST": "127.0.0.1", "PORT": first, "ZOTH_DEV_PORTS": ports, "ZOTH_DEV_ROOT": str(root),
    }


class RelayError(Exception):
    def __init__(self, status: int, detail: str):
        super().__init__(detail)
        self.status, self.detail = status, detail


def programs_in(cmd: str) -> list[str]:
    out = []
    for seg in SPLIT_RE.split(normalise(cmd)):
        prog, _ = _real_program(seg.split())
        if prog:
            out.append(prog)
    return out


class Relay:
    def __init__(self, cfg: Config):
        self.cfg = cfg
        self.registry = AgentRegistry(cfg)
        self.audit = AuditLog(cfg.audit_log)
        self._limiters: dict[str, RateLimiter] = {}
        for a in self.registry.agents.values():
            for d in a.allowed_dirs:
                try:
                    d.mkdir(parents=True, exist_ok=True, mode=0o750)
                except OSError:
                    pass

    # ------------------------------------------------------------ auth
    def authenticate(self, token: str, ip: str, via: str, path: str = "") -> Agent:
        agent = self.registry.lookup(token)
        if agent is None:
            self.audit.write("auth_fail", ip=ip, via=via, path=path)
            raise RelayError(401, "unauthorized")
        lim = self._limiters.get(agent.name)
        if lim is None or lim.per_minute != agent.rate_limit_per_minute:
            lim = self._limiters[agent.name] = RateLimiter(agent.rate_limit_per_minute)
        if not lim.allow(agent.name):
            self.audit.write("rate_limited", agent=agent.name, ip=ip, via=via, path=path)
            raise RelayError(429, "rate limit exceeded")
        return agent

    def _need(self, agent: Agent, cap: str, ip: str, via: str) -> None:
        if not agent.can(cap):
            self.audit.write("forbidden", agent=agent.name, ip=ip, via=via, op=cap, profile=agent.profile)
            raise RelayError(403, f"agent '{agent.name}' (profile {agent.profile}) may not {cap}")

    def _resolve(self, agent: Agent, path: str) -> Path:
        try:
            return resolve_inside(path, agent.allowed_dirs)
        except SandboxError as e:
            raise RelayError(403, str(e))

    # ------------------------------------------------------------ ask (mobile)
    def ask_targets_for(self, agent: Agent, ip: str = "?", via: str = "rest") -> list:
        self._need(agent, "ask", ip, via)
        ts = self.cfg.ask_targets
        return [t for n, t in ts.items() if not agent.ask_targets or n in agent.ask_targets]

    def resolve_ask_target(self, agent: Agent, name: str | None, ip: str = "?", via: str = "rest"):
        self._need(agent, "ask", ip, via)
        name = name or (agent.ask_targets[0] if agent.ask_targets else self.cfg.ask_default)
        t = self.cfg.ask_targets.get(name or "")
        if t is None:
            raise RelayError(404, f"unknown agent '{name}'")
        if agent.ask_targets and name not in agent.ask_targets:
            self.audit.write("forbidden", agent=agent.name, ip=ip, via=via, op="ask", target=name)
            raise RelayError(403, f"agent '{agent.name}' may not ask '{name}'")
        if not t.configured:
            raise RelayError(503, f"agent '{name}' has no API key configured on the host ({t.api_key_env})")
        return t

    def check_guide(self, agent: Agent, ip: str = "?", via: str = "rest"):
        self._need(agent, "guide", ip, via)
        if self.cfg.guide is None:
            raise RelayError(404, "Zoth Guide turns are not enabled on this relay (no `guide:` section)")
        return self.cfg.guide

    def check_live(self, agent: Agent, ip: str = "?", via: str = "rest"):
        self._need(agent, "live", ip, via)
        if self.cfg.live is None:
            raise RelayError(404, "Gemini Live is not enabled on this relay (no `live:` section)")
        return self.cfg.live

    def check_sessions(self, agent: Agent, ip: str = "?", via: str = "rest"):
        # FIELDKIT Sessions API (D2): capability only; providers are env-driven.
        self._need(agent, "sessions", ip, via)

    def check_missions(self, agent: Agent, ip: str = "?", via: str = "rest"):
        # FIELDKIT Missions API (D3): capability only; planner provider is env-driven.
        self._need(agent, "missions", ip, via)

    # ------------------------------------------------------------ ops
    def status(self, agent: Agent, ip: str = "?", via: str = "rest") -> dict:
        self._need(agent, "status", ip, via)
        root = agent.allowed_dirs[0]
        disk = shutil.disk_usage(str(root)) if root.exists() else None
        mem = {}
        try:
            for line in Path("/proc/meminfo").read_text().splitlines():
                k, v = line.split(":", 1)
                if k in ("MemTotal", "MemAvailable"):
                    mem[k] = int(v.split()[0]) * 1024
        except OSError:
            pass
        try:
            sys_up = float(Path("/proc/uptime").read_text().split()[0])
        except OSError:
            sys_up = None
        tunnel = bool(shutil.which("pgrep")) and subprocess.run(
            ["pgrep", "-x", "cloudflared"], capture_output=True).returncode == 0
        self.audit.write("status", agent=agent.name, ip=ip, via=via)
        return {
            "ok": True, "version": __version__, "agent": agent.name, "profile": agent.profile,
            "service_uptime_s": round(time.time() - START, 1), "system_uptime_s": sys_up,
            "run_as": getpass.getuser(), "euid": os.geteuid(),
            "disk": None if disk is None else {"path": str(root), "total": disk.total, "used": disk.used, "free": disk.free},
            "memory": {"total": mem.get("MemTotal"), "available": mem.get("MemAvailable")},
            "tunnel": {"cloudflared_running": tunnel},
            "allowed_dirs": [str(d) for d in agent.allowed_dirs],
        }

    def read_file(self, agent: Agent, path: str, ip: str = "?", via: str = "rest") -> dict:
        self._need(agent, "read_file", ip, via)
        try:
            real = self._resolve(agent, path)
        except RelayError as e:
            self.audit.write("file_read", agent=agent.name, ip=ip, via=via, path=path, result="denied", reason=e.detail)
            raise
        if not real.is_file():
            self.audit.write("file_read", agent=agent.name, ip=ip, via=via, path=str(real), result="not_found")
            raise RelayError(404, "not found")
        size = real.stat().st_size
        if size > self.cfg.max_file_bytes:
            self.audit.write("file_read", agent=agent.name, ip=ip, via=via, path=str(real), result="too_large", size=size)
            raise RelayError(413, "file too large")
        data = real.read_bytes()
        self.audit.write("file_read", agent=agent.name, ip=ip, via=via, path=str(real), result="ok", size=size)
        try:
            return {"path": str(real), "encoding": "utf-8", "content": data.decode("utf-8"), "size": size}
        except UnicodeDecodeError:
            return {"path": str(real), "encoding": "base64", "content": base64.b64encode(data).decode(), "size": size}

    def write_file(self, agent: Agent, path: str, content: str, append: bool = False, make_dirs: bool = True,
                   ip: str = "?", via: str = "rest") -> dict:
        self._need(agent, "write_file", ip, via)
        try:
            real = self._resolve(agent, path)
        except RelayError as e:
            self.audit.write("file_write", agent=agent.name, ip=ip, via=via, path=path, result="denied", reason=e.detail)
            raise
        data = content.encode("utf-8")
        if len(data) > self.cfg.max_file_bytes:
            raise RelayError(413, "content too large")
        if make_dirs:
            real.parent.mkdir(parents=True, exist_ok=True)
        self._resolve(agent, str(real))  # re-check after mkdir (symlink races on parent)
        with open(real, "ab" if append else "wb") as fh:
            fh.write(data)
        self.audit.write("file_write", agent=agent.name, ip=ip, via=via, path=str(real), result="ok", bytes=len(data), append=append)
        return {"ok": True, "path": str(real), "bytes": len(data)}

    def list_dir(self, agent: Agent, path: str | None = None, ip: str = "?", via: str = "rest") -> dict:
        self._need(agent, "list_dir", ip, via)
        real = self._resolve(agent, path or str(agent.allowed_dirs[0]))
        if not real.is_dir():
            raise RelayError(404, "not a directory")
        entries = []
        for i, p in enumerate(sorted(real.iterdir(), key=lambda x: x.name)):
            if i >= 2000:
                break
            try:
                st = p.lstat()
            except OSError:
                continue
            kind = "symlink" if p.is_symlink() else "dir" if p.is_dir() else "file"
            entries.append({"name": p.name, "type": kind, "size": st.st_size, "mtime": int(st.st_mtime)})
        self.audit.write("list_dir", agent=agent.name, ip=ip, via=via, path=str(real), count=len(entries))
        return {"path": str(real), "entries": entries}

    # ------------------------------------------------------------ exec
    def _prepare_exec(self, agent: Agent, command: str, working_directory: str | None, timeout: int | None,
                      ip: str, via: str):
        self._need(agent, "run_command", ip, via)
        try:
            check_command(command, self.cfg.blocklist)
            if agent.allowed_commands:
                bad = [p for p in programs_in(command) if p not in agent.allowed_commands]
                if bad:
                    raise BlockedCommand(f"program '{bad[0]}' not in agent allowlist")
            if agent.profile == "dev" and WILDCARD_BIND_RE.search(normalise(command)):
                raise BlockedCommand("dev servers must bind 127.0.0.1 (wildcard/0.0.0.0 bind refused)")
        except BlockedCommand as e:
            self.audit.write("exec", agent=agent.name, ip=ip, via=via, command=command, result="blocked", reason=str(e))
            raise RelayError(403, f"command blocked: {e}")
        try:
            cwd = self._resolve(agent, working_directory or str(agent.allowed_dirs[0]))
        except RelayError as e:
            self.audit.write("exec", agent=agent.name, ip=ip, via=via, command=command, result="denied_cwd", reason=e.detail)
            raise
        if not cwd.is_dir():
            raise RelayError(400, "working_directory does not exist")
        cap = min(agent.max_timeout or self.cfg.max_timeout, self.cfg.max_timeout)
        t = max(1, min(timeout or agent.default_timeout or self.cfg.default_timeout, cap))
        return cwd, t

    async def exec_stream(self, agent: Agent, command: str, working_directory: str | None = None,
                          timeout: int | None = None, ip: str = "?", via: str = "rest") -> AsyncIterator[dict]:
        """Validate synchronously (raises RelayError), then return an async iterator of events:
        {"stream":"stdout|stderr","data":...} ... then {"exit_code":..,"timed_out":..,...}."""
        cwd, tmo = self._prepare_exec(agent, command, working_directory, timeout, ip, via)
        env = {k: os.environ[k] for k in SAFE_ENV_KEYS if k in os.environ}
        env.setdefault("PATH", "/usr/local/bin:/usr/bin:/bin")
        env["ZOTH_RELAY_AGENT"] = agent.name
        argv = ["/bin/bash", "--noprofile", "--norc", "-c", command]
        if agent.profile == "dev":
            root = agent.allowed_dirs[0]
            env.update(dev_env(root, agent.dev_ports))
            # -I + absolute script path: the workspace (cwd) can't shadow the sandbox module
            argv = [sys.executable, "-I", os.path.abspath(landlock.__file__), "--rw", str(root),
                    "--ports", agent.dev_ports, "--", *argv]
        max_out = agent.max_output_bytes or self.cfg.max_output_bytes
        proc = await asyncio.create_subprocess_exec(
            *argv,
            cwd=str(cwd), env=env, stdin=asyncio.subprocess.DEVNULL,
            stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE, start_new_session=True)
        self.audit.write("exec_start", agent=agent.name, ip=ip, via=via, command=command, cwd=str(cwd), timeout=tmo, pid=proc.pid)
        cfg, audit = self.cfg, self.audit

        async def gen():
            q: asyncio.Queue = asyncio.Queue()
            sent, truncated, timed_out = 0, False, False
            t0 = time.monotonic()

            async def pump(reader, name):
                while chunk := await reader.read(4096):
                    await q.put((name, chunk))
                await q.put((name, None))

            tasks = [asyncio.create_task(pump(proc.stdout, "stdout")), asyncio.create_task(pump(proc.stderr, "stderr"))]
            code = None
            try:
                done = 0
                while done < 2:
                    remaining = tmo - (time.monotonic() - t0)
                    if remaining <= 0:
                        timed_out = True
                        break
                    try:
                        name, chunk = await asyncio.wait_for(q.get(), remaining)
                    except asyncio.TimeoutError:
                        timed_out = True
                        break
                    if chunk is None:
                        done += 1
                        continue
                    if sent >= max_out:
                        truncated = True
                        continue
                    sent += len(chunk)
                    yield {"stream": name, "data": chunk.decode("utf-8", "replace")}
                if timed_out:
                    try:
                        os.killpg(proc.pid, signal.SIGKILL)
                    except ProcessLookupError:
                        pass
                code = await proc.wait()
            finally:
                for t in tasks:
                    t.cancel()
                if proc.returncode is None:  # consumer went away
                    try:
                        os.killpg(proc.pid, signal.SIGKILL)
                    except ProcessLookupError:
                        pass
            dur = round(time.monotonic() - t0, 3)
            audit.write("exec_end", agent=agent.name, ip=ip, via=via, command=command, pid=proc.pid, exit_code=code,
                        timed_out=timed_out, output_bytes=sent, truncated=truncated, duration_s=dur)
            yield {"exit_code": code, "timed_out": timed_out, "truncated": truncated, "duration_s": dur}

        return gen()

    async def exec_collect(self, agent: Agent, command: str, working_directory: str | None = None,
                           timeout: int | None = None, ip: str = "?", via: str = "rest") -> dict:
        it = await self.exec_stream(agent, command, working_directory, timeout, ip, via)
        out, err, final = [], [], {}
        async for ev in it:
            if "stream" in ev:
                (out if ev["stream"] == "stdout" else err).append(ev["data"])
            else:
                final = ev
        return {"stdout": "".join(out), "stderr": "".join(err), **final}

    # ------------------------------------------------------------ dashboard
    def dashboard_data(self, agent: Agent, ip: str) -> dict:
        if not agent.dashboard:
            raise RelayError(403, "agent lacks dashboard permission")
        tail = []
        try:
            with open(self.cfg.audit_log, "rb") as fh:
                fh.seek(0, 2)
                fh.seek(max(0, fh.tell() - 65536))
                tail = fh.read().decode("utf-8", "replace").splitlines()[-100:]
        except OSError:
            pass
        import json
        events = []
        for line in tail:
            try:
                e = json.loads(line)
            except ValueError:
                continue
            if "command" in e:
                e["command"] = str(e["command"])[:200]
            events.append(e)
        self.audit.write("dashboard", agent=agent.name, ip=ip)
        return {"version": __version__, "uptime_s": round(time.time() - START, 1),
                "agents": [a.public() for a in self.registry.agents.values()], "recent": events[::-1]}
