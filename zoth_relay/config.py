"""Config + agent registry.

Main config: $ZOTH_RELAY_CONFIG or /etc/zoth-relay/config.yaml (global limits, blocklist).
Agents file: `agents_file` key (default /etc/zoth-relay/agents.yaml). Only SHA-256 token
hashes are stored there; plaintext tokens are printed once by `zoth-relay agent add`.
"""
from __future__ import annotations

import hashlib
import os
import re
from dataclasses import dataclass, field
from pathlib import Path

import yaml

DEFAULT_PATH = "/etc/zoth-relay/config.yaml"
PROFILES = ("read-only", "workspace", "exec", "dev", "mobile")
# Capabilities granted by each profile.
PROFILE_CAPS = {
    "read-only": {"status", "read_file", "list_dir"},
    "workspace": {"status", "read_file", "list_dir", "write_file"},
    "exec": {"status", "read_file", "list_dir", "write_file", "run_command"},
    # dev = exec inside a kernel (Landlock) sandbox locked to ONE workspace dir, with a private
    # HOME/caches/TMPDIR there and TCP bind limited to `dev_ports` (see landlock.py).
    "dev": {"status", "read_file", "list_dir", "write_file", "run_command"},
    # mobile = phone/glasses apps: may only ask an AI target (text + image) and read status.
    # No file access, no exec.
    "mobile": {"status", "ask", "live", "guide", "sessions", "missions"},
}
DEFAULT_DEV_PORTS = "4100-4199"
NAME_RE = re.compile(r"^[a-z0-9][a-z0-9_.-]{0,62}$")


def hash_token(token: str) -> str:
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


def _dirs(raw, home_default: list[str]) -> list[Path]:
    dirs = raw or home_default
    out = [Path(os.path.expanduser(str(d))).resolve() for d in dirs]
    if any(str(d) == "/" for d in out):
        raise SystemExit("[zoth-relay] '/' is not allowed in allowed_dirs")
    return out


@dataclass
class Agent:
    name: str
    token_sha256: str
    profile: str = "read-only"
    allowed_dirs: list[Path] = field(default_factory=list)
    allowed_commands: list[str] = field(default_factory=list)  # empty = any (blocklist still applies)
    rate_limit_per_minute: int = 60
    dashboard: bool = False  # may view /dashboard (loopback only)
    disabled: bool = False
    # Per-agent overrides (None = use global config). Still capped by global max_timeout.
    default_timeout: int | None = None
    max_timeout: int | None = None
    max_output_bytes: int | None = None
    dev_ports: str = ""  # dev profile: TCP bind range, e.g. "4100-4199"
    ask_targets: list[str] = field(default_factory=list)  # mobile: allowed ask targets (empty = all)

    def can(self, cap: str) -> bool:
        return not self.disabled and cap in PROFILE_CAPS[self.profile]

    def public(self) -> dict:
        return {"name": self.name, "profile": self.profile, "allowed_dirs": [str(d) for d in self.allowed_dirs],
                "allowed_commands": self.allowed_commands, "rate_limit_per_minute": self.rate_limit_per_minute,
                "dashboard": self.dashboard, "disabled": self.disabled,
                "default_timeout": self.default_timeout, "max_timeout": self.max_timeout,
                "max_output_bytes": self.max_output_bytes, "dev_ports": self.dev_ports or None,
                "ask_targets": self.ask_targets or None}


@dataclass
class Config:
    host: str = "127.0.0.1"
    port: int = 8765
    hosts: list[str] = field(default_factory=list)  # optional: bind several addresses (overrides host)
    default_allowed_dirs: list[Path] = field(default_factory=list)
    blocklist: list[str] = field(default_factory=list)
    audit_log: Path = Path("/var/log/zoth-relay/audit.log")
    agents_file: Path = Path("/etc/zoth-relay/agents.yaml")
    default_timeout: int = 60
    max_timeout: int = 900
    max_file_bytes: int = 10 * 1024 * 1024
    max_output_bytes: int = 5 * 1024 * 1024
    public_url: str = ""          # e.g. https://xyz.trycloudflare.com, used in /openapi-actions.json
    mcp_allowed_hosts: list[str] = field(default_factory=list)  # empty = DNS-rebinding check off (bearer still required)
    config_path: Path | None = None
    ask_targets: dict = field(default_factory=dict)   # name -> ask.AskTarget
    ask_default: str = ""
    live: object = None   # live.LiveConfig | None (Gemini Live ephemeral tokens)
    guide: object = None  # guide.GuideConfig | None (/v1/guide/turn: Hermes / local vision + AGY)

    @property
    def bind_hosts(self) -> list[str]:
        return self.hosts or [self.host]


def load_config(path: str | None = None) -> Config:
    path = path or os.environ.get("ZOTH_RELAY_CONFIG", DEFAULT_PATH)
    with open(path, "r", encoding="utf-8") as fh:
        raw = yaml.safe_load(fh) or {}
    base = Path(path).resolve().parent
    af = Path(os.path.expanduser(str(raw.get("agents_file", base / "agents.yaml"))))
    if not af.is_absolute():
        af = base / af
    from .ask import parse_targets
    from .live import parse_live
    from .guide import parse_guide
    targets = parse_targets(raw.get("ask_targets"))
    ask_default = str(raw.get("ask_default") or (next(iter(targets)) if targets else ""))
    if ask_default and ask_default not in targets:
        raise SystemExit(f"[zoth-relay] ask_default '{ask_default}' is not in ask_targets")
    hosts = raw.get("hosts") or []
    if isinstance(hosts, str):
        hosts = [hosts]
    if any(str(h) in ("0.0.0.0", "::", "*", "") for h in hosts):
        raise SystemExit("[zoth-relay] wildcard addresses are not allowed in hosts; list explicit IPs")
    return Config(
        hosts=[str(h) for h in hosts],
        ask_targets=targets, ask_default=ask_default, live=parse_live(raw.get("live")), guide=parse_guide(raw.get("guide")),
        host=str(raw.get("host", "127.0.0.1")),
        port=int(raw.get("port", 8765)),
        default_allowed_dirs=_dirs(raw.get("allowed_dirs"), ["~/agent-workspace"]),
        blocklist=[str(b) for b in (raw.get("blocklist") or [])],
        audit_log=Path(os.path.expanduser(str(raw.get("audit_log", "/var/log/zoth-relay/audit.log")))),
        agents_file=af,
        default_timeout=int(raw.get("default_timeout", 60)),
        max_timeout=int(raw.get("max_timeout", 900)),
        max_file_bytes=int(raw.get("max_file_bytes", 10 * 1024 * 1024)),
        max_output_bytes=int(raw.get("max_output_bytes", 5 * 1024 * 1024)),
        public_url=str(raw.get("public_url", "") or ""),
        mcp_allowed_hosts=[str(h) for h in (raw.get("mcp_allowed_hosts") or [])],
        config_path=Path(path),
    )


def parse_agents(raw: dict | None, cfg: Config) -> dict[str, Agent]:
    agents: dict[str, Agent] = {}
    for name, a in ((raw or {}).get("agents") or {}).items():
        a = a or {}
        prof = str(a.get("profile", "read-only"))
        h = str(a.get("token_sha256", ""))
        if prof not in PROFILES or not re.fullmatch(r"[0-9a-f]{64}", h) or not NAME_RE.match(str(name)):
            raise SystemExit(f"[zoth-relay] invalid agent entry '{name}' in {cfg.agents_file}")
        opt = lambda k: None if a.get(k) is None else int(a[k])  # noqa: E731
        dirs = _dirs(a.get("allowed_dirs"), [str(d) for d in cfg.default_allowed_dirs])
        ports = str(a.get("dev_ports") or (DEFAULT_DEV_PORTS if prof == "dev" else ""))
        if prof == "dev":
            if len(dirs) != 1:
                raise SystemExit(f"[zoth-relay] dev agent '{name}' must have exactly one allowed_dir")
            from .landlock import parse_ports
            try:
                parse_ports(ports)
            except ValueError as e:
                raise SystemExit(f"[zoth-relay] dev agent '{name}': {e}")
        agents[str(name)] = Agent(
            name=str(name), token_sha256=h, profile=prof,
            allowed_dirs=dirs,
            allowed_commands=[str(c) for c in (a.get("allowed_commands") or [])],
            rate_limit_per_minute=int(a.get("rate_limit_per_minute", 60)),
            dashboard=bool(a.get("dashboard", False)), disabled=bool(a.get("disabled", False)),
            default_timeout=opt("default_timeout"), max_timeout=opt("max_timeout"),
            max_output_bytes=opt("max_output_bytes"), dev_ports=ports,
            ask_targets=[str(t) for t in (a.get("ask_targets") or [])],
        )
    return agents


class AgentRegistry:
    """Loads agents.yaml and hot-reloads it when its mtime changes (so revoke is immediate)."""

    def __init__(self, cfg: Config):
        self.cfg = cfg
        self._mtime = None
        self._by_hash: dict[str, Agent] = {}
        self.agents: dict[str, Agent] = {}
        self.reload(force=True)

    def reload(self, force: bool = False) -> None:
        p = self.cfg.agents_file
        try:
            m = p.stat().st_mtime_ns
        except FileNotFoundError:
            m = None
        if not force and m == self._mtime:
            return
        raw = yaml.safe_load(p.read_text("utf-8")) if m is not None else {}
        self.agents = parse_agents(raw, self.cfg)
        self._by_hash = {a.token_sha256: a for a in self.agents.values()}
        for a in self.agents.values():  # best effort: create per-agent workspaces
            for d in a.allowed_dirs:
                try:
                    d.mkdir(parents=True, exist_ok=True, mode=0o750)
                except OSError:
                    pass
        self._mtime = m

    def lookup(self, token: str) -> Agent | None:
        if not token or len(token) < 32:
            return None
        self.reload()
        # Lookup by SHA-256 digest: the dict key is a hash of a 384-bit random token,
        # so timing on the lookup leaks nothing usable about other tokens.
        a = self._by_hash.get(hash_token(token))
        return None if a is None or a.disabled else a
