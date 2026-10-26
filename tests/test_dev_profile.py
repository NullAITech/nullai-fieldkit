"""dev profile: Landlock-sandboxed exec locked to one workspace."""
import shutil

import pytest
import yaml

from zoth_relay import landlock
from zoth_relay.config import hash_token, load_config, parse_agents
from zoth_relay.core import Relay, RelayError

pytestmark = pytest.mark.skipif(landlock.abi_version() < 4, reason="needs Landlock ABI >= 4")
MUSE = "zr_muse_" + "m" * 40
GROK = "zr_grok_" + "g" * 40


@pytest.fixture
def dev(tmp_path):
    ws = tmp_path / "ws"
    for d in ("muse", "grok"):
        (ws / d).mkdir(parents=True)
    (ws / "grok" / "secret.txt").write_text("grok-only")
    (tmp_path / "private.txt").write_text("relay-home")
    agents = {"agents": {
        "muse": {"token_sha256": hash_token(MUSE), "profile": "dev", "allowed_dirs": [str(ws / "muse")],
                 "default_timeout": 120, "max_timeout": 900, "max_output_bytes": 1000, "rate_limit_per_minute": 300},
        "grok": {"token_sha256": hash_token(GROK), "profile": "workspace", "allowed_dirs": [str(ws / "grok")]},
    }}
    (tmp_path / "agents.yaml").write_text(yaml.safe_dump(agents))
    (tmp_path / "config.yaml").write_text(yaml.safe_dump({
        "allowed_dirs": [str(ws)], "audit_log": str(tmp_path / "audit.log"), "agents_file": "agents.yaml",
        "default_timeout": 5, "max_timeout": 900}))
    relay = Relay(load_config(str(tmp_path / "config.yaml")))
    return relay, relay.registry.lookup(MUSE), relay.registry.lookup(GROK), tmp_path, ws


async def run(relay, agent, cmd, **kw):
    return await relay.exec_collect(agent, cmd, **kw)


async def test_dev_limits_and_env(dev):
    relay, muse, _, _, ws = dev
    assert muse.dev_ports == "4100-4199" and muse.rate_limit_per_minute == 300
    r = await run(relay, muse, 'echo "$HOME|$npm_config_cache|$PIP_CACHE_DIR|$TMPDIR|$HOST|$PORT|$PWD"')
    home, npmc, pipc, tmp, host, port, pwd = r["stdout"].strip().split("|")
    root = str(ws / "muse")
    assert all(p.startswith(root) for p in (home, npmc, pipc, tmp, pwd))
    assert (host, port) == ("127.0.0.1", "4100")
    big = await run(relay, muse, "head -c 5000 /dev/zero | tr '\\0' x")
    assert big["truncated"] and len(big["stdout"]) <= 4096 + 1000


async def test_dev_fs_sandbox(dev):
    relay, muse, _, tmp, ws = dev
    r = await run(relay, muse, "echo ok > f.txt && cat f.txt && git init -q . && echo git-ok")
    assert r["exit_code"] == 0 and "git-ok" in r["stdout"]
    for path in (ws / "grok" / "secret.txt", tmp / "private.txt", tmp / "agents.yaml"):
        r = await run(relay, muse, f"cat {path}")
        assert r["exit_code"] != 0 and "denied" in r["stderr"].lower(), path
    r = await run(relay, muse, f"echo x > {ws / 'grok' / 'pwn'}")
    assert r["exit_code"] != 0 and not (ws / "grok" / "pwn").exists()
    with pytest.raises(RelayError):  # cwd outside the workspace
        await run(relay, muse, "pwd", working_directory=str(ws / "grok"))


async def test_dev_cwd_cannot_shadow_sandbox(dev):
    relay, muse, _, tmp, ws = dev
    (ws / "muse" / "zoth_relay").mkdir()
    (ws / "muse" / "zoth_relay" / "__init__.py").write_text("")
    (ws / "muse" / "zoth_relay" / "landlock.py").write_text("import os,sys; os.execvp('bash',['bash','-c',sys.argv[-1]])")
    r = await run(relay, muse, f"cat {tmp / 'private.txt'}")
    assert r["exit_code"] != 0


async def test_dev_bind_ports(dev):
    relay, muse, *_ = dev
    py = "python3 -c 'import socket,sys;s=socket.socket();s.bind((\"127.0.0.1\",int(sys.argv[1])));print(\"bound\")'"
    assert "bound" in (await run(relay, muse, f"{py} 4100"))["stdout"]
    r = await run(relay, muse, f"{py} 5000")
    assert r["exit_code"] != 0 and "PermissionError" in r["stderr"]
    with pytest.raises(RelayError, match="127.0.0.1"):
        await run(relay, muse, "python3 -m http.server 4101 --bind 0.0.0.0")
    with pytest.raises(RelayError, match="blocked"):
        await run(relay, muse, "sudo id")


@pytest.mark.skipif(not shutil.which("python3"), reason="python3")
async def test_dev_venv_and_local_server(dev):
    relay, muse, *_ = dev
    r = await run(relay, muse, "python3 -m venv --without-pip .venv && . .venv/bin/activate && "
                               "python -c 'import sys;print(sys.prefix)'", timeout=60)
    assert r["exit_code"] == 0 and ".venv" in r["stdout"], r
    r = await run(relay, muse, "(python3 -m http.server 4102 --bind 127.0.0.1 >srv.log 2>&1 & echo $! > srv.pid); "
                               "for i in $(seq 50); do curl -fs -o /dev/null http://127.0.0.1:4102/ && break; sleep 0.1; done; "
                               "curl -fs -o /dev/null -w '%{http_code}' http://127.0.0.1:4102/; kill $(cat srv.pid)", timeout=30)
    assert r["stdout"].endswith("200"), r


async def test_other_profiles_unchanged(dev):
    relay, _, grok, *_ = dev
    with pytest.raises(RelayError) as e:
        await run(relay, grok, "echo hi")
    assert e.value.status == 403


def test_dev_requires_single_dir(tmp_path):
    (tmp_path / "c.yaml").write_text("{}")
    cfg = load_config(str(tmp_path / "c.yaml"))
    raw = {"agents": {"m": {"token_sha256": "0" * 64, "profile": "dev", "allowed_dirs": ["/tmp/a", "/tmp/b"]}}}
    with pytest.raises(SystemExit):
        parse_agents(raw, cfg)
    raw["agents"]["m"].update(allowed_dirs=["/tmp/a"], dev_ports="80-90")
    with pytest.raises(SystemExit):
        parse_agents(raw, cfg)
