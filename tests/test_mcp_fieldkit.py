"""FIELDKIT D5 (partial): MCP agent tools — see_now / say / mission_status /
advance_step. No secrets, no live provider needed for the mission tools; the
see_now tests cover the honest no-frame / no-provider paths."""
import asyncio

import pytest
import yaml
from mcp.shared.memory import create_connected_server_and_client_session

from zoth_relay.config import hash_token, load_config
from zoth_relay.core import Relay
from zoth_relay.mcp_server import build_mcp
from zoth_relay.missions import MISSIONS
from zoth_relay.providers import OpenAICompatProvider
from zoth_relay.sessions import ACTIVE_SESSIONS, FRAMES, Session

EX = "zr_exec_" + "e" * 40
MOB = "zr_mobile_" + "m" * 40
RO = "zr_ro_" + "r" * 40
TOKENS = {"ex": EX, "pixel": MOB, "ro": RO}


@pytest.fixture
def relay(tmp_path, monkeypatch):
    monkeypatch.delenv("META_API_KEY", raising=False)
    (tmp_path / "ws").mkdir()
    (tmp_path / "agents.yaml").write_text(yaml.safe_dump({"agents": {
        "ex": {"token_sha256": hash_token(EX), "profile": "exec",
               "allowed_dirs": [str(tmp_path / "ws")]},
        "pixel": {"token_sha256": hash_token(MOB), "profile": "mobile",
                  "allowed_dirs": [str(tmp_path / "ws")]},
        "ro": {"token_sha256": hash_token(RO), "profile": "read-only",
               "allowed_dirs": [str(tmp_path / "ws")]},
    }}))
    (tmp_path / "config.yaml").write_text(yaml.safe_dump({
        "allowed_dirs": [str(tmp_path / "ws")], "audit_log": str(tmp_path / "audit.log"),
        "agents_file": "agents.yaml"}))
    r = Relay(load_config(str(tmp_path / "config.yaml")))
    yield r
    ACTIVE_SESSIONS.pop("pixel", None)


async def call(relay, agent_name, tool, args=None):
    srv = build_mcp(relay, relay.registry.lookup(TOKENS[agent_name]))
    async with create_connected_server_and_client_session(srv._mcp_server) as s:
        await s.initialize()
        r = await s.call_tool(tool, args or {})
        return r


def test_fieldkit_tool_list(relay):
    async def main():
        srv = build_mcp(relay, relay.registry.lookup(EX))
        async with create_connected_server_and_client_session(srv._mcp_server) as s:
            await s.initialize()
            names = {t.name for t in (await s.list_tools()).tools}
            assert {"see_now", "say", "mission_status", "advance_step",
                    "run_command", "status"} <= names
    asyncio.run(main())


def test_mission_status_and_advance(relay):
    async def main():
        m = MISSIONS.create("water the plants", ["Fill can", "Pour"], provider="wonder")
        r = await call(relay, "pixel", "mission_status", {"mission_id": m.id})
        assert r.structuredContent["ok"] is True
        assert r.structuredContent["mission"]["current"] == 1
        r = await call(relay, "pixel", "advance_step", {"mission_id": m.id})
        assert r.structuredContent["ok"] is True
        assert r.structuredContent["mission"]["current"] == 2
        assert r.structuredContent["say"] == "Pour"
        r = await call(relay, "pixel", "mission_status", {"mission_id": "nope"})
        assert r.structuredContent["ok"] is False
        r = await call(relay, "pixel", "mission_status")
        assert r.structuredContent["mission"]["id"] == m.id   # latest
    asyncio.run(main())


def test_say_and_see_now_honest_failures(relay):
    async def main():
        r = await call(relay, "pixel", "say", {"text": "Next step."})
        assert r.structuredContent["ok"] is False          # no live session
        r = await call(relay, "pixel", "see_now")
        assert r.structuredContent["ok"] is False          # no live frame
        FRAMES.put("pixel", b"\xff\xd8fake-jpeg", "image/jpeg")
        r = await call(relay, "pixel", "see_now")
        assert r.structuredContent["ok"] is False          # provider unconfigured
        assert "provider" in r.structuredContent["detail"]
        sent = []
        provider = OpenAICompatProvider("spark", "https://up.example/v1", "m1",
                                        api_key_env="DEFINITELY_MISSING_KEY")

        async def send(ev):
            sent.append(ev)
        sess = Session(provider, send, agent_name="pixel")
        ACTIVE_SESSIONS["pixel"] = sess
        r = await call(relay, "pixel", "say", {"text": "Mind the blade guard."})
        assert r.structuredContent["ok"] is True
        assert sent and sent[-1]["type"] == "say"
        assert sent[-1]["text"] == "Mind the blade guard."
        await sess.close()
    asyncio.run(main())


def test_mobile_agent_can_use_fieldkit(relay):
    async def main():
        r = await call(relay, "pixel", "mission_status", {"mission_id": "nope"})
        assert r.structuredContent["ok"] is False  # gated in, honest failure
    asyncio.run(main())


def test_readonly_agent_cannot_use_fieldkit(relay):
    async def main():
        r = await call(relay, "ro", "say", {"text": "hi"})
        assert r.isError and "403" in r.content[0].text
        r = await call(relay, "ro", "mission_status", {})
        assert r.isError and "403" in r.content[0].text
    asyncio.run(main())


def test_mcp_jot_tools_end_to_end(relay, tmp_path, monkeypatch):
    import shutil
    from pathlib import Path

    from zoth_relay import jot_bridge

    if shutil.which("node") is None:
        import pytest as _pt
        _pt.skip("node required")
    monkeypatch.setenv("FIELDKIT_JOT_DIR", str(tmp_path / "jotdata"))
    monkeypatch.setenv("FIELDKIT_JOT_WORKER",
                       str(Path(__file__).parent / "fake_jot_worker.cjs"))
    jot_bridge.NOTE_BY_MISSION.clear()

    async def main():
        m = MISSIONS.create("Change mower filter", ["Pull filter", "Fit filter"],
                            provider="wonder")
        r = await call(relay, "pixel", "jot_mission_sync", {"mission_id": m.id})
        assert r.structuredContent["ok"] is True and r.structuredContent["note_id"]
        r = await call(relay, "pixel", "jot_field_note",
                       {"text": "left bolt is stripped", "mission_id": m.id})
        assert r.structuredContent["ok"] is True
        r = await call(relay, "pixel", "advance_step", {"mission_id": m.id})
        assert r.structuredContent["ok"] is True
        assert r.structuredContent["jot"]["ok"] is True     # checkoff fired
        r = await call(relay, "pixel", "recall_second_brain", {"query": "mower filter"})
        assert r.structuredContent["ok"] is True
    asyncio.run(main())
