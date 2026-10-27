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

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from zoth_relay.config import hash_token, load_config  # noqa: E402

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
