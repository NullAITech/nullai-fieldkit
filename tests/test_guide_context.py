"""/v1/guide/turn + the turn-context layer: wake commands, per-session focus, people memory.

No network and no device: the Hermes transport and the AGY runner are both fakes. What these tests
lock down is the turn contract -

  * an old phone build (no new fields) keeps working, and gains only what the relay learned itself;
  * a wake command only counts from the wearer, and muting really stops the spend;
  * a face never names anyone, and an unnamed sighting names nobody on disk or in the reply.

The phone-side contract (WakePhrase.Command ids, lowercased on the wire) is checked against the
Java source so the two halves cannot drift apart.
"""
import base64
import json
import sys
import time
from pathlib import Path

import httpx
import pytest
import yaml
from fastapi.testclient import TestClient

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from zoth_relay.config import hash_token, load_config          # noqa: E402
from zoth_relay.context import COMMAND_HINTS, SPEAKER_LINE, TurnContext  # noqa: E402
from zoth_relay.guide import GUIDE_SYSTEM                      # noqa: E402
from zoth_relay.people import People                           # noqa: E402
from zoth_relay.server import create_app                       # noqa: E402

TOK = "zr_phone_" + "p" * 40
JPEG = base64.b64encode(b"\xff\xd8\xff\xe0" + b"0" * 200).decode()
WAKE_JAVA = ROOT / "android/app/src/main/java/tech/nullai/fieldkit/voice/WakePhrase.java"

# Markers that must never appear in a prompt unless the request justified them.
CONTEXT_MARKERS = ("The wearer is the one speaking", "NOT the wearer", "You cannot tell who is speaking",
                   "just activated guide mode", "just deactivated guide mode", "asked for quiet",
                   "People he has told you about", "came back into view", "no name for them",
                   "don't know them")


def H(t=TOK):
    return {"Authorization": f"Bearer {t}"}


def sse_text(text):
    return f"data: {json.dumps({'choices': [{'delta': {'content': text}}]})}\n\ndata: [DONE]\n\n"


def unit(i, n=8):
    """A unit-length 8-d descriptor, like the phone sends."""
    v = [0.0] * n
    v[i % n] = 1.0
    return v


FACE_A, FACE_B = unit(0), unit(1)


@pytest.fixture
def env(tmp_path, monkeypatch):
    monkeypatch.setenv("API_SERVER_KEY", "k" * 40)
    monkeypatch.delenv("NOUS_API_KEY", raising=False)
    (tmp_path / "ws").mkdir()
    (tmp_path / "state").mkdir()
    (tmp_path / "agents.yaml").write_text(yaml.safe_dump({"agents": {
        "phone": {"token_sha256": hash_token(TOK), "profile": "mobile", "allowed_dirs": [str(tmp_path / "ws")]}}}))
    # agy.workdir is where the people store gets put (its parent dir), so nothing touches the real
    # ~/.local/state/zoth-relay-mobile when this suite runs.
    (tmp_path / "config.yaml").write_text(yaml.safe_dump({
        "allowed_dirs": [str(tmp_path / "ws")], "audit_log": str(tmp_path / "audit.log"),
        "agents_file": "agents.yaml",
        "guide": {"hermes": {}, "fallback": {},
                  "local_vision": {"min_interval": 0, "hot_c": 90},
                  "agy": {"min_interval": 0, "workdir": str(tmp_path / "state" / "agy-ws")}}}))
    return {"cfg": load_config(str(tmp_path / "config.yaml")), "tmp": tmp_path, "state": tmp_path / "state"}


def make(env, reply="Ready, watching."):
    app = create_app(env["cfg"])
    calls, systems = [], []

    def h(req):
        calls.append(req)
        systems.append(json.loads(req.content)["messages"][0]["content"])
        return httpx.Response(200, text=sse_text(reply))

    app.app.state.guide_transport = httpx.MockTransport(h)
    return app, calls, systems


def turn(c, body):
    payload = {"session": "s1"}
    payload.update(body)
    r = c.post("/v1/guide/turn", headers=H(), json=payload)
    assert r.status_code == 200, r.text
    return r.json()


# ------------------------------------------------------------------ an old phone build
def test_a_plain_turn_adds_only_what_the_relay_learned_itself(env):
    app, calls, systems = make(env)
    with TestClient(app) as c:
        j = turn(c, {"image": JPEG, "speech": "where is the van", "mission": "find the van"})
    assert j["speak"] is True and j["via"] == "hermes" and len(calls) == 1
    s = systems[-1]
    assert GUIDE_SYSTEM[:60] in s and "Mission: find the van" in s
    # Focus is derived relay-side from his own words, so a phone that sends no new fields still gets
    # the smarter voice - but nothing else is invented about who is speaking or who is in frame.
    assert "focused on right now: van" in s
    for marker in CONTEXT_MARKERS:
        assert marker not in s, marker


def test_an_empty_body_yields_an_empty_context_block():
    # The neutrality claim, unit-tested: no fields -> no prompt block at all.
    assert TurnContext.from_body({}).system_suffix() == ""
    assert TurnContext.from_body({"session": "s1", "image": "x"}).system_suffix() == ""


# ------------------------------------------------------------------ wake commands
def test_the_wearer_mutes_and_unmutes_the_guide(env):
    app, calls, systems = make(env)
    with TestClient(app) as c:
        j = turn(c, {"speaker": "wearer", "command": "guide_off", "speech": "spotter guide mode off"})
        # The turn that carries the command is still answered - it is the acknowledgement - and the
        # guide is told to say nothing after it.
        assert not j.get("muted") and len(calls) == 1
        assert COMMAND_HINTS["guide_off"] in systems[-1]
        j2 = turn(c, {"speaker": "wearer", "speech": "are you still there"})
        assert j2["muted"] is True and j2["via"] == "mute" and j2["speak"] is False and j2["text"] == ""
        assert len(calls) == 1                               # muted: no model call, no spend
        j3 = turn(c, {"speaker": "wearer", "speech": "where is the van"})
        assert j3["muted"] is True and len(calls) == 1       # and it stays off across turns
        j4 = turn(c, {"speaker": "wearer", "command": "guide_on", "speech": "spotter guide mode activate"})
        assert not j4.get("muted") and len(calls) == 2       # unmuting is answered too
        assert COMMAND_HINTS["guide_on"] in systems[-1]
        j5 = turn(c, {"speaker": "wearer", "speech": "back to it"})
        assert not j5.get("muted") and len(calls) == 3


def test_another_voice_cannot_command_the_guide(env):
    app, calls, systems = make(env)
    with TestClient(app) as c:
        j = turn(c, {"speaker": "other", "command": "guide_off", "speech": "spotter guide mode activate"})
        assert not j.get("muted") and len(calls) == 1        # a visitor saying the phrase changes nothing
        assert COMMAND_HINTS["guide_off"] not in systems[-1]
        assert SPEAKER_LINE["other"] in systems[-1]
        j2 = turn(c, {"speaker": "wearer", "speech": "still listening?"})
        assert not j2.get("muted") and len(calls) == 2       # the session was never muted


def test_an_ungated_phone_build_still_commands(env):
    # No `speaker` field at all: a pre-gating build. Its commands count, or the feature would be
    # dead for anyone who has not updated the app.
    app, calls, systems = make(env)
    with TestClient(app) as c:
        j = turn(c, {"command": "quiet_on", "speech": "spotter quiet mode on"})
        assert not j.get("muted") and len(calls) == 1
        assert COMMAND_HINTS["quiet_on"] in systems[-1]
        j2 = turn(c, {"speech": "and now this"})
        assert j2["muted"] is True and len(calls) == 1


def test_a_gated_build_that_cannot_tell_gives_no_command(env):
    app, calls, systems = make(env)
    with TestClient(app) as c:
        j = turn(c, {"speaker": "unknown", "command": "guide_off"})
    assert not j.get("muted") and len(calls) == 1
    assert COMMAND_HINTS["guide_off"] not in systems[-1]      # honest "unknown" cannot command
    assert SPEAKER_LINE["unknown"] in systems[-1]


def test_command_ids_match_the_phone_enum():
    """The wire carries Command.name().toLowerCase() - so every enum constant must be known here."""
    src = WAKE_JAVA.read_text()
    body = src.split("public enum Command {", 1)[1].split("}", 1)[0]
    names = [n.strip() for n in body.replace("\n", " ").split(",") if n.strip()]
    ids = {n.lower() for n in names if n != "NONE"}
    assert ids == set(COMMAND_HINTS), sorted(ids ^ set(COMMAND_HINTS))


# ------------------------------------------------------------------ focus
def test_focus_reaches_the_guide_from_his_own_words(env):
    app, calls, systems = make(env)
    with TestClient(app) as c:
        turn(c, {"speaker": "wearer", "speech": "I need the van packed before noon"})
        turn(c, {"speaker": "wearer", "speech": "the van again - what first"})
    assert len(calls) == 2
    assert "focused on right now: van" in systems[-1]
    assert "mentions" in systems[-1]


# ------------------------------------------------------------------ people memory
def test_a_named_person_coming_back_is_offered_to_the_guide(env):
    store = People(str(env["state"] / "people.json"))
    now = time.time()
    store.remember(FACE_A, "Maya", note="said the van needs packing before noon",
                   relation="site foreman", at=now - 3 * 86400)
    store.observe(FACE_A, at=now - 3 * 86400)          # a second sighting, so there is a gap to speak of

    app, calls, systems = make(env)
    with TestClient(app) as c:
        j = turn(c, {"descriptor": FACE_A, "mission": "walk the site"})
    assert j["speak"] is True and len(calls) == 1
    s = systems[-1]
    assert "came back into view" in s
    assert "Maya" in s and "3 days since you last saw them" in s
    assert "site foreman" in s                          # the confirmed-people list rides along too
    assert "never infer identity from appearance" in s


def test_an_unnamed_sighting_names_nobody(env):
    app, calls, systems = make(env)
    with TestClient(app) as c:
        j = turn(c, {"descriptor": FACE_B, "mission": "walk the site"})
        s = systems[-1]
        assert "no name for them" in s and "came back into view" not in s
        assert "Maya" not in s and "Maya" not in json.dumps(j)
        blob = json.loads((env["state"] / "people.json").read_text())
    assert [p["name"] for p in blob["people"]] == [""]   # remembered as a silhouette, not a person
    assert blob["people"][0]["descriptor"] == FACE_B
    assert blob["people"][0]["notes"] == []
    # The store is his, private, and 0600.
    assert oct((env["state"] / "people.json").stat().st_mode)[-3:] == "600"


def test_people_prompt_lines_only_carry_named_people(env):
    store = People(str(env["state"] / "people.json"))
    store.remember(FACE_A, "Maya", note="site foreman", at=time.time())
    store.observe(FACE_B, at=time.time())                # an unnamed sighting alongside her
    app, calls, systems = make(env)
    with TestClient(app) as c:
        turn(c, {"speaker": "wearer", "speech": "morning", "mission": "walk the site"})
    s = systems[-1]
    assert "People he has told you about" in s and "- Maya" in s
    assert s.count("- ") == 1                            # one known person, no unnamed entries
