"""Nov 2 (hackathon): live eyes switch, on-device detections, 'remember this person', People & Voices.

All synthetic: unit vectors and a 4-byte JPEG header stand in for faces and voices. No real
person's face or voice is in this repo. Upstreams mocked; no secrets/network."""
import asyncio
import base64
import json
import math
import stat

import httpx
import pytest
import yaml
from fastapi.testclient import TestClient

from zoth_relay.config import hash_token, load_config
from zoth_relay.people import People, clean_thumb
from zoth_relay.providers import OpenAICompatProvider, Provider
from zoth_relay.server import create_app
from zoth_relay.sessions import Session

MOB = "zr_mobile_" + "m" * 40
JPG = base64.b64encode(b"\xff\xd8\xff\xe0" + b"\x00" * 40).decode()
JPG2 = base64.b64encode(b"\xff\xd8\xff\xe0" + b"\x07" * 40).decode()


def unit(i, n=140):
    v = [0.0] * n
    v[i] = 1.0
    return v


def voice(i):
    return unit(i, 42)


def H():
    return {"Authorization": f"Bearer {MOB}"}


class Rec(Provider):
    def __init__(self):
        super().__init__("spark", "http://x", "m")
        self.turns = []

    async def stream_events(self, turn, request_id, transport=None):
        self.turns.append(turn)
        yield {"type": "delta", "text": "ok"}
        yield {"type": "done", "id": request_id, "text": "ok", "ms": 1, "first_ms": 1}


async def _noop(m):
    pass


# ------------------------------------------------------------------ Session (unit)
def test_eyes_switch_keeps_context_and_tells_model():
    prov = Rec()
    s = Session(prov, _noop)
    s.on_frame(JPG)
    asyncio.run(s.run_turn("what is this"))
    assert s.set_eyes("glasses") is True
    assert s.set_eyes("glasses") is False                    # idempotent
    s.on_frame(JPG)                                          # same bytes, new source: still counts
    assert s.frames_changed == 2
    asyncio.run(s.run_turn("and now"))
    t = prov.turns[-1]
    assert "Meta glasses" in t.system and "switched it mid-call" in t.system
    assert any(h["content"] == "what is this" for h in t.history)   # context preserved
    assert t.image is not None
    assert s.state()["eyes"] == "glasses" and s.state()["eyes_switches"] == 1
    assert any(e["kind"] == "eyes" and e["source"] == "glasses" for e in s.replay_log)


def test_switch_drops_pending_frame_from_old_source():
    s = Session(Rec(), _noop)
    s.on_frame(JPG)
    s.set_eyes("glasses")
    assert s._pending_frame is None


def test_detections_summary_is_bounded_and_reaches_prompt():
    prov = Rec()
    s = Session(prov, _noop)
    d = s.on_detections({"hands": 2, "faces": 99, "gestures": ["open_palm", 5], "objects": ["x" * 90],
                         "pose": 1})
    assert d["faces"] == 20 and d["gestures"] == ["open_palm"] and len(d["objects"][0]) == 32
    s.set_in_view(["Maya"])
    asyncio.run(s.run_turn("hi"))
    sysp = prov.turns[-1].system
    assert "2 hand(s) (open_palm)" in sysp and "20 face(s)" in sysp and "Maya" in sysp
    assert "never assert" in sysp


def test_stale_detections_not_in_prompt():
    s = Session(Rec(), _noop)
    s.on_detections({"hands": 1})
    s.detections_at -= 60
    assert "hand" not in s.vision_suffix()


def test_set_in_view_reports_only_new_names():
    s = Session(Rec(), _noop)
    assert s.set_in_view(["A", "B"]) == ["A", "B"]
    assert s.set_in_view(["B", "C"]) == ["C"]


# ------------------------------------------------------------------ People store (unit)
def test_save_person_with_thumb_voice_and_time(tmp_path):
    p = People(str(tmp_path / "people.json"))
    rec = p.save_person("Maya", unit(3), JPG, voice(1), at=1000.0)
    assert rec.saved_at == 1000.0 and rec.thumb == JPG and rec.voice_saved_at == 1000.0
    back = People(str(tmp_path / "people.json"))
    pub = back.listing()[0]
    assert pub["name"] == "Maya" and pub["thumb"] == JPG and pub["has_voice"] and pub["has_face"]
    assert "descriptor" not in pub and "voice" not in pub          # vectors never leave the store
    assert stat.S_IMODE((tmp_path / "people.json").stat().st_mode) == 0o600


def test_store_encrypted_at_rest_when_available(tmp_path):
    p = People(str(tmp_path / "people.json"))
    p.save_person("Maya", unit(3), JPG)
    if not p.encrypted:
        pytest.skip("cryptography not installed: plaintext 0600 fallback")
    raw = (tmp_path / "people.json").read_bytes()
    assert b"Maya" not in raw and JPG.encode() not in raw
    assert stat.S_IMODE((tmp_path / "people.json.key").stat().st_mode) == 0o600


def test_plaintext_store_from_before_nov2_still_loads(tmp_path):
    path = tmp_path / "people.json"
    path.write_text(json.dumps({"version": 1, "people": [
        {"id": "p0001", "name": "Old", "descriptor": unit(2), "first_seen": 1, "last_seen": 1,
         "encounters": 1}]}))
    assert People(str(path)).named()[0].name == "Old"


def test_bare_name_is_never_stored():
    with pytest.raises(ValueError):
        People().save_person("Nobody")


def test_bad_thumbs_dropped():
    assert clean_thumb("not base64!!") == ""
    assert clean_thumb(base64.b64encode(b"GIF89a....").decode()) == ""
    assert clean_thumb("A" * 200_000) == ""
    assert clean_thumb("data:image/jpeg;base64," + JPG) == JPG


def test_voice_only_person_and_voice_match():
    p = People()
    rec = p.save_person("Sam", voice=voice(4))
    assert rec.voice and not rec.descriptor
    assert p.match_voice(voice(4)).name == "Sam"
    assert p.match_voice(voice(5)) is None
    assert p.forget_voice(rec.id) and not p.records          # nothing left to bind the name to


def test_rename_and_forget_voice_keeps_face():
    p = People()
    rec = p.save_person("Maya", unit(1), voice=voice(2))
    assert p.rename(rec.id, "Maya R").name == "Maya R"
    assert p.rename(rec.id, "  ") is None
    assert p.forget_voice(rec.id) and p.records[rec.id].voice == []


# ------------------------------------------------------------------ relay (REST + WS)
@pytest.fixture
def env(tmp_path, monkeypatch):
    monkeypatch.setenv("FAKE_KEY", "test-key-123")
    (tmp_path / "ws").mkdir()
    (tmp_path / "agents.yaml").write_text(yaml.safe_dump({"agents": {
        "pixel": {"token_sha256": hash_token(MOB), "profile": "mobile",
                  "allowed_dirs": [str(tmp_path / "ws")]}}}))
    (tmp_path / "config.yaml").write_text(yaml.safe_dump({
        "allowed_dirs": [str(tmp_path / "ws")], "audit_log": str(tmp_path / "audit.log"),
        "agents_file": "agents.yaml"}))
    seen = []

    def handler(req: httpx.Request):
        seen.append(json.loads(req.content))
        sse = f"data: {json.dumps({'choices': [{'delta': {'content': 'ok'}}]})}\n\n"
        return httpx.Response(200, text=sse + "data: [DONE]\n\n",
                              headers={"content-type": "text/event-stream"})

    guard = create_app(load_config(str(tmp_path / "config.yaml")))
    guard.app.state.session_providers = {
        "spark": OpenAICompatProvider("spark", "https://up.example/v1", "m1", api_key_env="FAKE_KEY")}
    guard.app.state.session_transport = httpx.MockTransport(handler)
    guard.app.state.people = People(str(tmp_path / "state" / "people.json"))
    with TestClient(guard) as c:
        yield {"c": c, "seen": seen, "people": guard.app.state.people}


def test_people_rest_crud(env):
    c = env["c"]
    assert c.get("/v1/people").status_code in (401, 403)
    r = c.post("/v1/people", headers=H(), json={"name": "Maya", "descriptor": unit(1), "thumb": JPG,
                                                 "at": 1234.0})
    assert r.status_code == 200 and r.json()["saved_at"] == 1234.0
    pid = r.json()["id"]
    assert c.post("/v1/people", headers=H(), json={"name": "X"}).status_code == 400
    lst = c.get("/v1/people", headers=H()).json()["people"]
    assert [x["name"] for x in lst] == ["Maya"] and lst[0]["thumb"] == JPG
    assert c.patch(f"/v1/people/{pid}", headers=H(), json={"name": "Maya R"}).json()["name"] == "Maya R"
    assert c.post(f"/v1/people/{pid}/voice", headers=H(), json={"voice": voice(3)}).json()["has_voice"]
    assert c.delete(f"/v1/people/{pid}/voice", headers=H()).status_code == 200
    assert c.delete(f"/v1/people/{pid}", headers=H()).status_code == 200
    assert c.delete(f"/v1/people/{pid}", headers=H()).status_code == 404
    c.post("/v1/people", headers=H(), json={"name": "A", "descriptor": unit(5)})
    c.post("/v1/people", headers=H(), json={"name": "B", "descriptor": unit(6)})
    assert c.delete("/v1/people", headers=H()).json()["deleted"] == 2
    assert c.get("/v1/people", headers=H()).json()["people"] == []


def _hello(ws, **kw):
    ws.send_json({"type": "hello", "provider": "spark", **kw})
    assert ws.receive_json()["type"] == "ready"


def test_ws_eyes_switch_same_session(env):
    with env["c"].websocket_connect("/v1/sessions", headers=H()) as ws:
        _hello(ws, eyes="phone")
        ws.send_json({"type": "eyes", "source": "glasses"})
        ev = ws.receive_json()
        assert ev == {"type": "eyes", "source": "glasses", "switched": True}
        st = ws.receive_json()
        assert st["type"] == "state" and st["eyes"] == "glasses"
        ws.send_json({"type": "frame", "image": JPG2})
        ws.send_json({"type": "text", "text": "what do you see"})
        while ws.receive_json()["type"] != "done":
            pass
    sysmsg = env["seen"][-1]["messages"][0]["content"]
    assert "Meta glasses" in sysmsg


def test_ws_remember_then_recognise_in_view(env):
    with env["c"].websocket_connect("/v1/sessions", headers=H()) as ws:
        _hello(ws)
        ws.send_json({"type": "remember", "name": "Maya", "descriptor": unit(7), "thumb": JPG})
        ev = ws.receive_json()
        assert ev["type"] == "remembered" and ev["name"] == "Maya" and ev["has_face"]
        ws.send_json({"type": "remember", "name": "Eve", "descriptor": unit(8), "speaker": "other"})
        assert ws.receive_json()["type"] == "error"              # a bystander cannot save anyone
        ws.send_json({"type": "detections", "hands": 1, "faces": 1, "descriptors": [unit(9)]})
        ev = ws.receive_json()
        assert ev["type"] == "detections" and ev["in_view"] == []  # stranger: no name, no record
        assert len(env["people"].records) == 1
        ws.send_json({"type": "detections", "faces": 1, "descriptors": [unit(7)]})
        ev = ws.receive_json()
        assert ev == {"type": "person_in_view", "names": ["Maya"]}
        assert ws.receive_json()["in_view"] == ["Maya"]
        ws.send_json({"type": "detections", "faces": 1, "names": ["Nobody Saved"]})
        assert ws.receive_json()["in_view"] == []                # phone can't invent a name


def test_ws_voice_print_hint(env):
    env["people"].save_person("Sam", voice=voice(9))
    with env["c"].websocket_connect("/v1/sessions", headers=H()) as ws:
        _hello(ws)
        ws.send_json({"type": "speech", "text": "hey there", "final": True, "speaker": "other",
                      "voice_print": voice(9)})
        ws.send_json({"type": "text", "text": "who was that"})
        for _ in range(30):
            if ws.receive_json()["type"] == "done":
                break
    assert any("sounded like Sam" in s["messages"][0]["content"] for s in env["seen"])


def test_pwa_has_eyes_switch_and_people_card(env):
    r = env["c"].get("/pwa")
    assert r.status_code == 200
    for marker in ('id="eyesSrc"', "Remember person", "PEOPLE &amp; VOICES", '"/v1/people"', 'type: "eyes"'):
        assert marker in r.text


def test_people_analyze_and_context_snapshot(env):
    c = env["c"]
    c.post("/v1/people", headers=H(), json={"name": "Maya", "descriptor": unit(2), "relation": "Teammate",
                                             "note": "Attire: Cobalt Denim"})
    r_an = c.post("/v1/people/analyze", headers=H(), json={"image": JPG, "attire_hint": "Cobalt Jacket"})
    assert r_an.status_code == 200
    data_an = r_an.json()
    assert "Cobalt Jacket" in data_an["attire"]
    assert "summary" in data_an

    r_snap = c.get("/v1/context/snapshot", headers=H())
    assert r_snap.status_code == 200
    snap = r_snap.json()
    assert snap["saved_people_count"] == 1
    assert snap["saved_people"][0]["name"] == "Maya"
    assert "[SPOTTER LOCAL RELAY DIGEST" in snap["prompt_digest"]
