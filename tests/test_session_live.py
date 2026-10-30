"""FIELDKIT: the wearer's live guidance context on the phone's real turn path.

WS /v1/sessions is what the phone actually talks to. This pins the wiring added for
voice-first coaching:

  * a text-only turn (older phone build) keeps the provider prompt byte-identical;
  * who spoke, his honoured wake command and what he is focused on reach the SAME
    system prompt the provider sees - via TurnContext, the one contract both paths use;
  * an honoured guide_off by the WEARER silences coaching without the guide spending a
    token, and his next words do not silently un-silence it (paused wears off when he
    talks, muted does not);
  * a bystander saying the same magic words changes nothing;
  * a bare descriptor can NEVER name anyone: a stranger's face yields no name, no
    "back in view" line, and creates no person in the store;
  * the ONLY way a name exists is the wearer typing or saying it - and once he has, the
    same face coming back later greets them by name.

Upstreams mocked; no secrets, no network.
"""
import json
import math

import httpx
import pytest
import yaml
from fastapi.testclient import TestClient

from zoth_relay.config import hash_token, load_config
from zoth_relay.context import TurnContext
from zoth_relay.people import People
from zoth_relay.providers import OpenAICompatProvider
from zoth_relay.server import create_app

MOB = "zr_mobile_" + "m" * 40

# Two L2-normalised descriptors 45 degrees apart (cosine .707 < the .86 match threshold):
# D1 is the face he later names, D2 is a stranger.
D1 = [1.0 / math.sqrt(32)] * 32
D2 = [1.0 / math.sqrt(16)] * 16 + [0.0] * 16


def H(t):
    return {"Authorization": f"Bearer {t}"}


@pytest.fixture
def env(tmp_path, monkeypatch):
    (tmp_path / "ws").mkdir()
    (tmp_path / "agents.yaml").write_text(yaml.safe_dump({"agents": {
        "pixel": {"token_sha256": hash_token(MOB), "profile": "mobile",
                  "allowed_dirs": [str(tmp_path / "ws")]},
    }}))
    (tmp_path / "config.yaml").write_text(yaml.safe_dump({
        "allowed_dirs": [str(tmp_path / "ws")], "audit_log": str(tmp_path / "audit.log"),
        "agents_file": "agents.yaml"}))
    seen = []

    def handler(req: httpx.Request):
        seen.append(json.loads(req.content))
        sse = "".join(f"data: {json.dumps({'choices': [{'delta': {'content': p}}]})}\n\n"
                      for p in ("Go ", "left."))
        return httpx.Response(200, text=sse + "data: [DONE]\n\n",
                              headers={"content-type": "text/event-stream"})

    guard = create_app(load_config(str(tmp_path / "config.yaml")))
    guard.app.state.session_providers = {
        "spark": OpenAICompatProvider("spark", "https://up.example/v1", "m1",
                                      api_key_env="FAKE_KEY"),
    }
    guard.app.state.session_transport = httpx.MockTransport(handler)
    # One people store for the whole relay, handed in before the app serves: the session
    # path and the guide REST path must read THIS one, never a second of their own.
    people = People(str(tmp_path / "people.json"))
    guard.app.state.people = people
    monkeypatch.setenv("FAKE_KEY", "test-key-123")
    with TestClient(guard) as c:
        yield {"c": c, "seen": seen, "people": people}


def until_done(ws, limit=12):
    evs = []
    for _ in range(limit):
        ev = ws.receive_json()
        evs.append(ev)
        if ev["type"] in ("done", "error"):
            break
    return evs


def system_prompt(env):
    return env["seen"][-1]["messages"][0]["content"]


def test_a_text_only_turn_leaves_the_prompt_unchanged(env):
    with env["c"].websocket_connect("/v1/sessions", headers=H(MOB)) as ws:
        ws.send_json({"type": "hello", "provider": "spark", "mission": "fix pump"})
        assert ws.receive_json()["type"] == "ready"
        ws.send_json({"type": "speech", "text": "what now", "final": True})
        until_done(ws)
        state = ws.receive_json()
    assert state["type"] == "state" and state["turns"] == 1
    assert state["muted"] is False
    prompt = system_prompt(env)
    assert "fix pump" in prompt
    # Nothing of the guidance block - exactly the prompt an older build always got.
    assert TurnContext.from_body({}).system_suffix() == ""
    for marker in ("is the one speaking", "focused on right now", "came back into view",
                   "no name for them"):
        assert marker not in prompt


def test_the_wearers_speaker_command_and_focus_reach_the_provider(env):
    with env["c"].websocket_connect("/v1/sessions", headers=H(MOB)) as ws:
        ws.send_json({"type": "hello", "provider": "spark", "mission": "fix pump"})
        assert ws.receive_json()["type"] == "ready"
        ws.send_json({"type": "speech", "text": "spotter, guide mode off", "final": True,
                      "speaker": "wearer", "command": "guide_off",
                      "focus": "packing the van"})
        evs = until_done(ws)
        assert evs[-1]["type"] == "done"
        ack_state = ws.receive_json()
        assert ack_state["type"] == "state" and ack_state["muted"] is True
        # The ack turn itself is answered, and it saw his speaker, command and focus.
        expected = TurnContext.from_body({"speaker": "wearer", "command": "guide_off",
                                          "focus": "packing the van"}).system_suffix()
        assert expected in system_prompt(env)
        assert "packing the van" in system_prompt(env)
        calls = len(env["seen"])

        # Coaching is off: he is still transcribed, but the guide spends nothing and says
        # nothing - and his own words do NOT lift his own command.
        ws.send_json({"type": "speech", "text": "what is next then", "final": True,
                      "speaker": "wearer"})
        muted = ws.receive_json()
        assert muted["type"] == "state" and muted["muted"] is True
        assert len(env["seen"]) == calls

        # He turns it back on by voice: answered again.
        ws.send_json({"type": "speech", "text": "spotter, guide mode on", "final": True,
                      "speaker": "wearer", "command": "guide_on"})
        evs = until_done(ws)
        assert evs[-1]["type"] == "done"
        back = ws.receive_json()
        assert back["type"] == "state" and back["muted"] is False
        assert len(env["seen"]) == calls + 1


def test_a_bystander_cannot_silence_the_guide(env):
    with env["c"].websocket_connect("/v1/sessions", headers=H(MOB)) as ws:
        ws.send_json({"type": "hello", "provider": "spark", "mission": "fix pump"})
        assert ws.receive_json()["type"] == "ready"
        ws.send_json({"type": "speech", "text": "spotter, guide mode off", "final": True,
                      "speaker": "other", "command": "guide_off"})
        ev = ws.receive_json()
        while ev["type"] not in ("state", "error"):
            ev = ws.receive_json()
        assert ev["muted"] is False, "a bystander's words must never silence his guide"
        assert len(env["seen"]) == 0, "a bystander's turn is ignored, not answered"

        ws.send_json({"type": "speech", "text": "what now", "final": True,
                      "speaker": "wearer"})
        evs = until_done(ws)
        assert evs[-1]["type"] == "done", "the wearer's own turn still works"
        assert len(env["seen"]) == 1


def test_a_bare_descriptor_can_never_name_anyone(env):
    people = env["people"]
    people.observe(D1)
    people.remember(D1, "Marisol")            # the name came from the WEARER, not a face
    assert [p.name for p in people.named()] == ["Marisol"]

    with env["c"].websocket_connect("/v1/sessions", headers=H(MOB)) as ws:
        ws.send_json({"type": "hello", "provider": "spark", "mission": "fix pump"})
        assert ws.receive_json()["type"] == "ready"

        # A stranger's face: no name, no reflection line, and no person invented. (Her name
        # may be listed as someone HE told the guide about - that list came from him, not
        # from the pixels.)
        ws.send_json({"type": "speech", "text": "who is this?", "final": True,
                      "speaker": "wearer", "person_new": True, "descriptor": D2})
        assert until_done(ws)[-1]["type"] == "done"
        ws.receive_json()                     # state
        prompt = system_prompt(env)
        assert "came back into view" not in prompt
        assert "no name for them" in prompt
        assert [p.name for p in people.named()] == ["Marisol"]

        # The face he named himself: the reflection is allowed, because he said the name.
        ws.send_json({"type": "speech", "text": "is that Marisol?", "final": True,
                      "speaker": "wearer", "descriptor": D1})
        assert until_done(ws)[-1]["type"] == "done"
        ws.receive_json()
        prompt = system_prompt(env)
        assert "came back into view" in prompt and "Marisol" in prompt
        assert [p.name for p in people.named()] == ["Marisol"]


def test_the_wearers_own_words_are_the_only_way_a_name_exists(env):
    """He types or says a name for the person in frame; the relay binds it to the descriptor and
    a LATER sighting of that face greets them by name. Nothing else can write a name."""
    people = env["people"]
    with env["c"].websocket_connect("/v1/sessions", headers=H(MOB)) as ws:
        ws.send_json({"type": "hello", "provider": "spark", "mission": "fix pump"})
        assert ws.receive_json()["type"] == "ready"

        # Someone else in the room names them: refused at the door, and not even answered.
        ws.send_json({"type": "speech", "text": "remember this person: Dave", "final": True,
                      "speaker": "other", "command": "person_enroll", "name": "Dave",
                      "descriptor": D1})
        ev = ws.receive_json()
        while ev["type"] not in ("state", "error"):
            ev = ws.receive_json()
        assert people.named() == [], "a bystander cannot put a name in his memory"
        assert len(env["seen"]) == 0

        # A name with nothing to bind it to is not a memory either.
        ws.send_json({"type": "speech", "text": "remember this person: Dave", "final": True,
                      "speaker": "wearer", "command": "person_enroll", "name": "Dave"})
        assert until_done(ws)[-1]["type"] == "done"
        ws.receive_json()
        assert people.named() == [], "a name that binds to nobody is not a memory"

        # His own words, with the face in frame: this is the one channel that writes a name.
        # The turn acknowledges the naming - it does not also announce a re-sighting of the
        # person he is naming this very moment.
        ws.send_json({"type": "speech", "text": "remember this person: Marisol", "final": True,
                      "speaker": "wearer", "command": "person_enroll", "name": "Marisol",
                      "descriptor": D1})
        assert until_done(ws)[-1]["type"] == "done"
        ws.receive_json()
        prompt = system_prompt(env)
        assert "is naming a person you should remember" in prompt
        assert "came back into view" not in prompt, "naming is not a re-sighting"
        assert [p.name for p in people.named()] == ["Marisol"]

        # Later, the same face, nothing typed this time: the store hands the guide her name.
        ws.send_json({"type": "speech", "text": "who is this?", "final": True,
                      "speaker": "wearer", "descriptor": D1})
        assert until_done(ws)[-1]["type"] == "done"
        ws.receive_json()
        prompt = system_prompt(env)
        assert "came back into view" in prompt and "Marisol" in prompt
