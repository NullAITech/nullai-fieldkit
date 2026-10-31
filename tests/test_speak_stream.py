import asyncio

from zoth_relay.providers import Provider
from zoth_relay.sessions import DEFAULT_VOICE_SYSTEM, Session, split_speakable


class FakeProvider(Provider):
    def __init__(self, tokens, delay=0.0):
        super().__init__("fake", "http://x", "m")
        self.tokens, self.delay, self.seen = tokens, delay, None

    async def stream_events(self, turn, request_id, transport=None):
        self.seen = turn
        async def pieces():
            for t in self.tokens:
                await asyncio.sleep(self.delay)
                yield t
        async for ev in self._emit(request_id, pieces()):
            yield ev


def test_split_speakable_sentences_and_early_comma():
    chunks, rest = split_speakable("Loosen the left clamp, then lift it. Next", True)
    assert chunks == ["Loosen the left clamp,", "then lift it."] and rest == " Next"
    chunks, rest = split_speakable("v1.2 is fine", False)
    assert chunks == [] and rest == "v1.2 is fine"


def run(tokens):
    out = []
    async def send(m):
        out.append(m)
    p = FakeProvider(tokens)
    s = Session(p, send)
    asyncio.run(s.run_turn("what now"))
    return p, out


def test_speak_chunks_stream_before_done_and_default_prompt():
    p, out = run(["Stop. ", "The cover ", "is cracked", ". Swap it."])
    kinds = [m["type"] for m in out]
    speaks = [m["text"] for m in out if m["type"] == "speak"]
    assert speaks == ["Stop.", "The cover is cracked.", "Swap it."]
    assert kinds.index("speak") < kinds.index("done")
    assert kinds.index("done") > max(i for i, k in enumerate(kinds) if k == "speak")
    assert p.seen.system.startswith(DEFAULT_VOICE_SYSTEM)


def test_barged_turn_speaks_nothing_more():
    out = []
    async def send(m):
        out.append(m)
    async def go():
        s = Session(FakeProvider(["One. ", "Two. ", "Three."], delay=0.05), send)
        s.start_turn("hi")
        await asyncio.sleep(0.08)
        await s.barge()
        await asyncio.sleep(0.2)
    asyncio.run(go())
    speaks = [m["text"] for m in out if m["type"] == "speak"]
    assert "Three." not in speaks and out[-1]["type"] == "stop"
