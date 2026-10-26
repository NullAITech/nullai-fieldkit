"""Speak-marker parser, persistent AGY stream-json session, and the full-duplex /v1/guide/stream WebSocket."""
import asyncio, base64, json, struct, sys, time
from pathlib import Path

import httpx, pytest, yaml
from fastapi.testclient import TestClient

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from zoth_relay.agy_session import AgySession  # noqa: E402
from zoth_relay.config import hash_token, load_config  # noqa: E402
from zoth_relay.markers import MarkerParser  # noqa: E402
from zoth_relay.server import create_app  # noqa: E402

TOK = "zr_phone_" + "p" * 40
JPEG = base64.b64encode(b"\xff\xd8\xff\xe0" + b"0" * 200).decode()


def run_parser(chunks, **kw):
    p = MarkerParser(**kw)
    ev = []
    for c in chunks:
        ev += p.feed(c)
    ev += p.finish()
    return [t for k, t in ev if k == "speak"], p


# ------------------------------------------------------------------ marker parser
def test_basic_marker_and_context():
    said, p = run_parser(["Thinking about it. [^Turn left. Then stop.^] Because the exit is there."])
    assert said == ["Turn left.", "Then stop."]
    assert p.context_text == "Thinking about it. Because the exit is there."


def test_markers_split_across_every_chunk_boundary():
    text = "ctx one [^Grab the red kettle. Fill it halfway.^] ctx two [^Then boil.^] end"
    for size in (1, 2, 3, 5, 7):
        said, p = run_parser([text[i:i + size] for i in range(0, len(text), size)])
        assert said == ["Grab the red kettle.", "Fill it halfway.", "Then boil."], size
        assert "[" not in p.context_text and "^" not in p.context_text and "ctx two" in p.context_text


def test_sentence_emitted_before_marker_closes():
    p = MarkerParser()
    assert p.feed("[^Look up. Th") == [("speak", "Look up.")]   # first sentence goes to TTS immediately
    assert p.feed("e sign says exit") == []
    assert p.feed("^]") == [("speak", "The sign says exit"), ("end_marker", "")]


def test_missing_closer_is_flushed_at_end_of_turn():
    said, _ = run_parser(["[^Walk to the door", " and knock"])
    assert said == ["Walk to the door and knock"]


def test_silent_and_no_marker_means_quiet():
    said, p = run_parser(["SILENT"])
    assert said == [] and p.context_text == ""
    said, p = run_parser(["Just reasoning, nothing to say."])
    assert said == [] and p.context_text == "Just reasoning, nothing to say."
    said, _ = run_parser(["[^SILENT^]"])
    assert said == []


def test_decimals_dont_split_and_long_first_sentence_splits_at_comma():
    said, _ = run_parser(["[^It costs 3.50 here.^]"])
    assert said == ["It costs 3.50 here."]
    p = MarkerParser(min_chunk_words=4)
    out = p.feed("[^Walk past the two big blue doors, then turn ")
    assert out == [("speak", "Walk past the two big blue doors,")]


def test_dangling_open_bracket_is_context():
    said, p = run_parser(["array[", "0] is fine"])
    assert said == [] and p.context_text == "array[0] is fine"


# ------------------------------------------------------------------ agy session (real subprocess, fake agy)
def fake_argv(tmp_path, replies, delay=0.0):
    script = tmp_path / "replies.txt"
    script.write_text("\n".join(replies) + "\n")
    import os
    os.environ.update(FAKE_AGY_SCRIPT=str(script), FAKE_AGY_DELAY=str(delay), FAKE_AGY_LOG=str(tmp_path / "agy_in.log"))
    return lambda: [sys.executable, str(ROOT / "tests" / "fake_agy.py")]


def test_agy_session_streams_and_keeps_context(tmp_path):
    async def go():
        s = AgySession(workdir=str(tmp_path / "ws"), mirror_path=str(tmp_path / "mirror.log"),
                       argv_factory=fake_argv(tmp_path, ["[^Hi|.^] ctx", "unused"], delay=0.05))
        t0 = time.monotonic(); first_at = None; parts = []
        async for d in s.turn("remember kelp"):
            first_at = first_at or time.monotonic() - t0
            parts.append(d)
        assert parts == ["[^Hi", ".^] ctx"] and s.conversation_id == "conv-1234abcd"
        pid = s.proc.pid
        out = "".join([d async for d in s.turn("RECALL")])
        assert out == "first was: remember kelp" and s.proc.pid == pid   # same process, same conversation
        await s.stop()
        mirror = (tmp_path / "mirror.log").read_text()
        assert "remember kelp" in mirror and "AGY session started" in mirror
    asyncio.run(go())


# ------------------------------------------------------------------ websocket end to end
class FakeTts:
    class cfg:
        engine = "kokoro"
    rate = 24000
    error = ""
    _impl = object()
    enabled = True
    spoken = []

    def load(self):
        return True

    def synth(self, text):
        FakeTts.spoken.append(text)
        time.sleep(0.05)
        return b"\x01\x00" * 24000  # 1 s of audio -> 5 chunks of 200 ms


@pytest.fixture
def stream_app(tmp_path):
    (tmp_path / "ws").mkdir()
    (tmp_path / "agents.yaml").write_text(yaml.safe_dump({"agents": {
        "phone": {"token_sha256": hash_token(TOK), "profile": "mobile", "allowed_dirs": [str(tmp_path / "ws")]}}}))
    (tmp_path / "config.yaml").write_text(yaml.safe_dump({
        "allowed_dirs": [str(tmp_path / "ws")], "audit_log": str(tmp_path / "audit.log"), "agents_file": "agents.yaml",
        "guide": {"local_vision": {"hot_c": 200}, "stream": {"batch_interval": 0.2, "caption_interval": 0, "mirror": str(tmp_path / "m.log")}}}))
    cfg = load_config(str(tmp_path / "config.yaml"))

    def make(replies, delay=0.0):
        app = create_app(cfg)
        st = app.app.state
        caps = iter(["A kitchen with a red kettle.", "A kitchen with a red kettle on it.", "A hallway with a blue door."])
        st.guide_transport = httpx.MockTransport(lambda r: httpx.Response(200, json={} if r.url.path == "/api/generate" else
                                                 {"message": {"content": next(caps, "A hallway with a blue door.")}}))
        st.tts = FakeTts()
        FakeTts.spoken = []
        st.agy_session = AgySession(workdir=str(tmp_path / "agyws"), mirror_path=str(tmp_path / "m.log"),
                                    argv_factory=fake_argv(tmp_path, replies, delay))
        return app
    return make, tmp_path


def recv_until(ws, pred, limit=200):
    got = []
    for _ in range(limit):
        m = ws.receive()
        item = json.loads(m["text"]) if m.get("text") is not None else m.get("bytes")
        got.append(item)
        if pred(item):
            return got
    raise AssertionError(f"not found in {got[-10:]}")


def says(got):
    return [g["text"] for g in got if isinstance(g, dict) and g.get("type") == "say"]


def test_ws_rejects_bad_token(stream_app):
    make, _ = stream_app
    with TestClient(make([])) as c:
        with pytest.raises(Exception):
            with c.websocket_connect("/v1/guide/stream", headers={"Authorization": "Bearer nope"}) as ws:
                ws.receive_json()


def test_ws_full_pipeline_caption_agy_tts(stream_app):
    make, tmp = stream_app
    app = make(["[^Zoth is with you.^]", "Kettle spotted. [^Fill the red ke|ttle. Then switch it on.^] more ctx",
                "[^It needs about two minutes.^]"])
    with TestClient(app) as c, c.websocket_connect("/v1/guide/stream", headers={"Authorization": f"Bearer {TOK}"}) as ws:
        ws.send_json({"type": "hello", "mission": "make tea"})
        got = recv_until(ws, lambda m: isinstance(m, dict) and m.get("type") == "ready")
        assert got[-1]["rate"] == 24000 and got[-1]["tts"] == "kokoro"
        got = recv_until(ws, lambda m: isinstance(m, dict) and m.get("text") == "Zoth is with you.")
        ws.send_json({"type": "frame", "image": JPEG})
        got = recv_until(ws, lambda m: isinstance(m, dict) and m.get("text") == "Then switch it on.")
        assert any(isinstance(g, dict) and g.get("type") == "caption" and "red kettle" in g["text"] for g in got)
        assert "Fill the red kettle." in says(got)
        got = recv_until(ws, lambda m: isinstance(m, dict) and m.get("type") == "context")
        assert "Kettle spotted." in got[-1]["text"] and "more ctx" in got[-1]["text"]
        # near-duplicate caption is deduped -> no new AGY turn
        ws.send_json({"type": "frame", "image": JPEG})
        # audio frames: 4-byte gen header + PCM
        audio = [g for g in got if isinstance(g, (bytes, bytearray))]
        ws.send_json({"type": "speech", "text": "how long does it take"})
        got = recv_until(ws, lambda m: isinstance(m, dict) and m.get("text") == "It needs about two minutes.")
        audio += [g for g in got if isinstance(g, (bytes, bytearray))]
        audio += [recv_until(ws, lambda m: isinstance(m, (bytes, bytearray)))[-1]]
        ws.send_json({"type": "bye"})
    assert audio and all(len(a) > 4 for a in audio)
    agy_in = [json.loads(l) for l in (tmp / "agy_in.log").read_text().splitlines()]
    assert agy_in[0].startswith("You are Zoth") and "[^" in agy_in[0] and "MISSION: make tea" in agy_in[0]
    assert "SEEN: A kitchen with a red kettle." in agy_in[1]
    assert any('NEAL: "how long does it take"' in x for x in agy_in)
    assert sum("red kettle on it" in x for x in agy_in) == 0   # deduped caption never reached AGY
    log = (tmp / "audit.log").read_text()
    assert "how long" not in log and "kettle" not in log


def test_ws_barge_in_stops_audio_and_mutes_rest_of_turn(stream_app):
    make, tmp = stream_app
    # slow turn: Zoth starts a long answer; Neal barges in after the first sentence
    app = make(["[^Zoth is with you.^]", "[^First sentence here.| Second sentence here.| Third sentence here.^]", "[^Okay, stopping.^]"], delay=0.4)
    with TestClient(app) as c, c.websocket_connect("/v1/guide/stream", headers={"Authorization": f"Bearer {TOK}"}) as ws:
        ws.send_json({"type": "hello", "mission": ""})
        recv_until(ws, lambda m: isinstance(m, dict) and m.get("text") == "Zoth is with you.")
        ws.send_json({"type": "frame", "image": JPEG})
        got = recv_until(ws, lambda m: isinstance(m, dict) and m.get("text") == "First sentence here.")
        gen0 = got[-1]["gen"]
        ws.send_json({"type": "barge"})
        ws.send_json({"type": "speech", "text": "wait stop"})
        got = recv_until(ws, lambda m: isinstance(m, dict) and m.get("text") == "Okay, stopping.")
    stops = [g for g in got if isinstance(g, dict) and g.get("type") == "stop_audio"]
    assert stops and stops[0]["gen"] > gen0
    assert "Second sentence here." not in says(got) and "Third sentence here." not in says(got)
    new_gen = got[-1]["gen"]
    late_audio = [g for g in got[got.index(stops[-1]):] if isinstance(g, (bytes, bytearray))]
    assert all(struct.unpack(">i", a[:4])[0] >= new_gen for a in late_audio)   # no stale audio after stop


def test_tell_me_more_speaks_stored_context_instantly(stream_app):
    make, _ = stream_app
    app = make(["[^Zoth is with you.^]", "The door is a fire exit. It stays unlocked. Alarm may sound. [^Use the blue door.^]"])
    with TestClient(app) as c, c.websocket_connect("/v1/guide/stream", headers={"Authorization": f"Bearer {TOK}"}) as ws:
        ws.send_json({"type": "hello", "mission": "get out", "tts": "phone"})
        ready = recv_until(ws, lambda m: isinstance(m, dict) and m.get("type") == "ready")[-1]
        assert ready["tts"] == "phone"
        recv_until(ws, lambda m: isinstance(m, dict) and m.get("text") == "Zoth is with you.")
        ws.send_json({"type": "frame", "image": JPEG})
        recv_until(ws, lambda m: isinstance(m, dict) and m.get("type") == "context")
        t0 = time.monotonic()
        ws.send_json({"type": "speech", "text": "tell me more"})
        got = recv_until(ws, lambda m: isinstance(m, dict) and m.get("type") == "say" and "fire exit" in m["text"])
        assert time.monotonic() - t0 < 1.0 and got[-1]["tts"] == "phone"
        assert got[-1]["text"] == "The door is a fire exit. It stays unlocked."
    assert FakeTts.spoken == []   # phone TTS mode: relay synthesizes nothing


def test_admin_is_loopback_only(stream_app):
    make, _ = stream_app
    with TestClient(make([]), base_url="http://100.106.132.5:8766") as c:
        # TestClient's client host is "testclient", i.e. not loopback
        assert c.post("/v1/guide/admin/status").status_code == 404
