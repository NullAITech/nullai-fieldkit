import os
import sys
from pathlib import Path

import pytest
import yaml

# FieldKit mirrors (Hermes Jot + Obsidian vault) must never touch the
# operator's real stores from tests. tmp defaults; bridge tests override.
_TEST_SANDBOX = Path(os.environ.get("TMPDIR", "/tmp")) / "fieldkit-test-sandbox"
os.environ.setdefault("FIELDKIT_JOT_DIR", str(_TEST_SANDBOX / "jot"))
os.environ.setdefault("FIELDKIT_VAULT_DIR", str(_TEST_SANDBOX / "vault"))
# Goal modes (modes.py): never read/write the operator's real modes file.
os.environ["ZOTH_MODES_FILE"] = str(_TEST_SANDBOX / "modes.json")
(_TEST_SANDBOX / "modes.json").unlink(missing_ok=True)

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from zoth_relay.config import hash_token, load_config  # noqa: E402

# Compatibility shim for MCP 2.x which removed create_connected_server_and_client_session
import mcp.shared.memory
import mcp.types
if not hasattr(mcp.types.CallToolResult, "structuredContent"):
    setattr(mcp.types.CallToolResult, "structuredContent", property(lambda self: getattr(self, "structured_content", None)))
if not hasattr(mcp.types.CallToolResult, "isError"):
    setattr(mcp.types.CallToolResult, "isError", property(lambda self: getattr(self, "is_error", False)))

if not hasattr(mcp.shared.memory, "create_connected_server_and_client_session"):
    from contextlib import asynccontextmanager
    import anyio
    from mcp.client.session import ClientSession
    from mcp.shared.memory import create_client_server_memory_streams

    @asynccontextmanager
    async def _compat_create_connected_server_and_client_session(server, init_options=None):
        async with create_client_server_memory_streams() as (client_stream, server_stream):
            read_stream, write_stream = client_stream
            srv_read, srv_write = server_stream
            async with ClientSession(read_stream, write_stream) as client_session:
                async with anyio.create_task_group() as tg:
                    async def run_server():
                        try:
                            opts = init_options or (server.create_initialization_options() if hasattr(server, "create_initialization_options") else None)
                            await server.run(srv_read, srv_write, opts)
                        except anyio.get_cancelled_exc_class():
                            pass
                    tg.start_soon(run_server)
                    _inited = False
                    orig_init = client_session.initialize
                    async def _idempotent_init(*args, **kwargs):
                        nonlocal _inited
                        if not _inited:
                            res = await orig_init(*args, **kwargs)
                            _inited = True
                            return res
                        return None
                    client_session.initialize = _idempotent_init
                    await client_session.initialize()
                    yield client_session
                    tg.cancel_scope.cancel()

    mcp.shared.memory.create_connected_server_and_client_session = _compat_create_connected_server_and_client_session

TOK = {"alpha": "zr_alpha_" + "a" * 40, "beta": "zr_beta_" + "b" * 40,
       "reader": "zr_reader_" + "r" * 40, "admin": "zr_admin_" + "d" * 40}


@pytest.fixture
def env(tmp_path):
    ws = tmp_path / "ws"
    (ws / "alpha").mkdir(parents=True)
    (ws / "beta").mkdir()
    (ws / "beta" / "secret.txt").write_text("beta-only")
    (tmp_path / "outside.txt").write_text("nope")
    agents = {"agents": {
        "alpha": {"token_sha256": hash_token(TOK["alpha"]), "profile": "exec", "allowed_dirs": [str(ws / "alpha")]},
        "beta": {"token_sha256": hash_token(TOK["beta"]), "profile": "workspace", "allowed_dirs": [str(ws / "beta")]},
        "reader": {"token_sha256": hash_token(TOK["reader"]), "profile": "read-only",
                   "allowed_dirs": [str(ws / "beta")], "rate_limit_per_minute": 3},
        "admin": {"token_sha256": hash_token(TOK["admin"]), "profile": "exec", "dashboard": True,
                  "allowed_dirs": [str(ws / "alpha")], "allowed_commands": ["echo", "ls"]},
    }}
    (tmp_path / "agents.yaml").write_text(yaml.safe_dump(agents))
    (tmp_path / "config.yaml").write_text(yaml.safe_dump({
        "allowed_dirs": [str(ws)], "audit_log": str(tmp_path / "audit.log"),
        "agents_file": "agents.yaml", "blocklist": ["/etc/shadow"], "default_timeout": 5}))
    cfg = load_config(str(tmp_path / "config.yaml"))
    return {"cfg": cfg, "tmp": tmp_path, "ws": ws, "tok": TOK}
