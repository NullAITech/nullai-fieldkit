"""zoth-relay CLI.

  zoth-relay serve [--config PATH]                       run the HTTP service (REST + MCP)
  zoth-relay agent add NAME [--profile P] [--dir D]... [--allow-cmd C]... [--rate N] [--dashboard] [--ask-target T]...
          [--token-file PATH]   (mobile apps: write the token to a 0600 file instead of printing it)
  zoth-relay agent list | revoke NAME | rotate NAME | remove NAME
  zoth-relay mcp-stdio [--url URL] [--direct --config PATH]   (token from $ZOTH_RELAY_TOKEN)
  zoth-relay openapi [--url URL]                          print the GPT Actions schema
"""
from __future__ import annotations

import argparse
import json
import os
import secrets
import sys
import tempfile
from pathlib import Path

import yaml


def _cfg(path):
    from .config import load_config
    return load_config(path)


def _load_agents(p: Path) -> dict:
    if not p.exists():
        return {"agents": {}}
    d = yaml.safe_load(p.read_text("utf-8")) or {}
    d.setdefault("agents", {})
    d["agents"] = d["agents"] or {}
    return d


def _save_agents(p: Path, d: dict) -> None:
    p.parent.mkdir(parents=True, exist_ok=True)
    fd, tmp = tempfile.mkstemp(dir=p.parent, prefix=".agents.")
    with os.fdopen(fd, "w", encoding="utf-8") as fh:
        fh.write("# zoth-relay agents. Managed by `zoth-relay agent ...`. Token HASHES only.\n")
        yaml.safe_dump(d, fh, sort_keys=False)
    if p.exists():
        st = p.stat()
        os.chmod(tmp, st.st_mode & 0o777)
        try:
            os.chown(tmp, st.st_uid, st.st_gid)
        except PermissionError:
            pass
    else:
        os.chmod(tmp, 0o640)
    os.replace(tmp, p)


LOOPBACK = ("127.0.0.1", "::1", "localhost")


def bind_sockets(hosts: list[str], port: int) -> list:
    """One listening socket per explicit address. Non-loopback addresses (e.g. the Tailscale IP)
    use IP_FREEBIND so the service can start before tailscaled brings the interface up."""
    import socket
    socks = []
    for h in hosts:
        h = "127.0.0.1" if h == "localhost" else h
        fam = socket.AF_INET6 if ":" in h else socket.AF_INET
        s = socket.socket(fam, socket.SOCK_STREAM)
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        if h not in LOOPBACK:
            if fam == socket.AF_INET:
                s.setsockopt(socket.IPPROTO_IP, getattr(socket, "IP_FREEBIND", 15), 1)
            else:
                s.setsockopt(socket.IPPROTO_IPV6, 78, 1)  # IPV6_FREEBIND
        s.bind((h, port))
        s.listen(2048)
        s.set_inheritable(True)
        socks.append(s)
    return socks


def _write_token_file(path: str, tok: str) -> None:
    p = Path(os.path.expanduser(path))
    p.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    fd = os.open(p, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as fh:
        fh.write(tok + "\n")
    os.chmod(p, 0o600)


def _new_token() -> str:
    return "zr_" + secrets.token_urlsafe(36)


def cmd_agent(a) -> int:
    from .config import NAME_RE, PROFILES, hash_token
    cfg = _cfg(a.config)
    p = cfg.agents_file
    d = _load_agents(p)
    ag = d["agents"]
    if a.action == "list":
        if a.json:
            print(json.dumps({k: {kk: vv for kk, vv in v.items() if kk != "token_sha256"} for k, v in ag.items()}, indent=1))
            return 0
        print(f"{'NAME':<16} {'PROFILE':<10} {'STATE':<8} {'RATE':>5}  DIRS")
        for k, v in ag.items():
            print(f"{k:<16} {v.get('profile','read-only'):<10} {'revoked' if v.get('disabled') else 'active':<8} "
                  f"{v.get('rate_limit_per_minute',60):>5}  {', '.join(v.get('allowed_dirs') or ['(default)'])}")
        return 0
    name = (a.name or "").lower()
    if not NAME_RE.match(name):
        print("invalid name (a-z0-9 . _ -, max 63)", file=sys.stderr)
        return 2
    if a.action == "add":
        if name in ag:
            print(f"agent '{name}' exists; use rotate", file=sys.stderr)
            return 1
        if a.profile not in PROFILES:
            print(f"profile must be one of {PROFILES}", file=sys.stderr)
            return 2
        tok = _new_token()
        ent = {"token_sha256": hash_token(tok), "profile": a.profile,
               "allowed_dirs": a.dir or ([str(d) for d in cfg.default_allowed_dirs] if a.profile == "mobile"
                                         else [f"~/agent-workspace/{name}"]),  # mobile has no file access
               "rate_limit_per_minute": a.rate}
        if a.allow_cmd:
            ent["allowed_commands"] = a.allow_cmd
        if a.dashboard:
            ent["dashboard"] = True
        if a.ask_target:
            ent["ask_targets"] = a.ask_target
        ag[name] = ent
    elif name not in ag:
        print(f"no agent '{name}'", file=sys.stderr)
        return 1
    elif a.action == "revoke":
        ag[name]["disabled"] = True
        _save_agents(p, d)
        print(f"revoked '{name}' (takes effect on next request; 'rotate' re-enables with a new token)")
        return 0
    elif a.action == "remove":
        del ag[name]
        _save_agents(p, d)
        print(f"removed '{name}'")
        return 0
    elif a.action == "rotate":
        tok = _new_token()
        ag[name]["token_sha256"] = hash_token(tok)
        ag[name].pop("disabled", None)
    _save_agents(p, d)
    print(f"agent '{name}' profile={ag[name]['profile']} dirs={ag[name]['allowed_dirs']}")
    if a.token_file:
        _write_token_file(a.token_file, tok)
        print(f"TOKEN written to {a.token_file} (0600)")
        return 0
    print("TOKEN (shown once, store it in the client now):")
    print(f"  {tok}")
    return 0


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(prog="zoth-relay")
    ap.add_argument("--config", default=None, help="default $ZOTH_RELAY_CONFIG or /etc/zoth-relay/config.yaml")
    sub = ap.add_subparsers(dest="cmd")
    sub.add_parser("serve")
    ag = sub.add_parser("agent")
    ag.add_argument("action", choices=["add", "list", "revoke", "rotate", "remove"])
    ag.add_argument("name", nargs="?")
    ag.add_argument("--profile", default="read-only")
    ag.add_argument("--dir", action="append", help="allowed dir (repeatable); default ~/agent-workspace/<name>")
    ag.add_argument("--allow-cmd", action="append", help="restrict exec to these programs (repeatable)")
    ag.add_argument("--rate", type=int, default=60)
    ag.add_argument("--dashboard", action="store_true")
    ag.add_argument("--json", action="store_true")
    ag.add_argument("--ask-target", action="append", help="mobile: allowed ask target (repeatable; first = default)")
    ag.add_argument("--token-file", default=None, help="write the new token to this 0600 file instead of stdout")
    ms = sub.add_parser("mcp-stdio")
    ms.add_argument("--url", default=None)
    ms.add_argument("--direct", action="store_true", help="run in-process as the current user (dev only)")
    oa = sub.add_parser("openapi")
    oa.add_argument("--url", default="https://YOUR-TUNNEL.trycloudflare.com")
    a = ap.parse_args(argv)
    cmd = a.cmd or "serve"

    if cmd == "agent":
        if a.action != "list" and not a.name:
            ap.error("name required")
        return cmd_agent(a)
    if cmd == "openapi":
        from .openapi_actions import actions_schema
        print(json.dumps(actions_schema(a.url), indent=2))
        return 0
    if cmd == "mcp-stdio":
        from .mcp_server import run_stdio
        if a.direct and os.geteuid() == 0:
            sys.exit("[zoth-relay] refusing --direct as root")
        run_stdio(a.url, None, (a.config or os.environ.get("ZOTH_RELAY_CONFIG", "/etc/zoth-relay/config.yaml")) if a.direct else None)
        return 0

    # serve: never as root
    if os.geteuid() == 0 or os.getegid() == 0:
        sys.exit("[zoth-relay] refusing to run as root; run as the 'zoth-relay' user")
    os.umask(0o027)
    import uvicorn
    from .server import create_app
    cfg = _cfg(a.config)
    for h in cfg.bind_hosts:
        if h not in LOOPBACK:
            print(f"[zoth-relay] binding to {h}:{cfg.port} (non-loopback; keep it on a private network like Tailscale)",
                  file=sys.stderr)
    socks = bind_sockets(cfg.bind_hosts, cfg.port)
    server = uvicorn.Server(uvicorn.Config(create_app(cfg), log_level="info", proxy_headers=False,
                                           server_header=False))
    server.run(sockets=socks)
    return 0


if __name__ == "__main__":
    sys.exit(main())
