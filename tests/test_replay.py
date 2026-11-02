"""'What it saw' replay (Nov 2): frames + SAM boxes + steps + timestamps + model."""
import asyncio
import base64

import pytest

from zoth_relay import recap
from zoth_relay.providers import Provider
from zoth_relay.sessions import Session

JPG = b"\xff\xd8\xff" + b"\x00" * 32


class Fake(Provider):
    def __init__(self, name="spark", text="Tighten the bolt."):
        super().__init__(name, "http://x", name + "-m")
        self.text = text

    async def stream_events(self, turn, request_id, transport=None):
        yield {"type": "start", "id": request_id, "agent": self.name, "model": self.model}
        yield {"type": "delta", "text": self.text}
        yield {"type": "done", "id": request_id, "text": self.text, "ms": 5, "first_ms": 2}


def make_session():
    async def send(m):
        pass
    s = Session(Fake(), send)
    s.on_frame(base64.b64encode(JPG).decode())
    asyncio.run(s.run_turn("what next"))
    return s


def test_turn_logged_with_frame_model_and_time():
    s = make_session()
    e = s.replay_log[-1]
    assert e["kind"] == "turn" and e["model"] == "spark" and e["model_id"] == "spark-m"
    assert e["text"] == "Tighten the bolt." and e["prompt"] == "what next"
    assert e["frame"] is not None and isinstance(e["t"], float)


def test_replay_standin_boxes_when_sam_unset(monkeypatch):
    monkeypatch.delenv("SAM_API_URL", raising=False)
    s = make_session()
    rep = asyncio.run(recap.build_replay(s))
    assert rep["sam"] == "stand-in" and rep["models"] == ["spark"]
    st = rep["steps"][0]
    assert st["image"].startswith("data:image/jpeg;base64,")
    assert st["boxes"] and st["boxes"][0]["source"] == "stand-in"
    assert "stand-in" in st["boxes"][0]["label"].lower()
    assert st["model"] == "spark" and st["text"] == "Tighten the bolt." and "at" in st


def test_replay_uses_sam_when_configured(monkeypatch):
    monkeypatch.setenv("SAM_API_URL", "http://sam")

    async def fake_hl(self, image, step_text):
        return [{"x": .1, "y": .2, "w": .3, "h": .4, "label": "bolt", "score": .9}]
    monkeypatch.setattr(recap.SamHighlighter, "highlight", fake_hl)
    rep = asyncio.run(recap.build_replay(make_session()))
    assert rep["sam"] == "sam-3.1"
    assert rep["steps"][0]["boxes"][0]["source"] == "sam-3.1"


def test_replay_log_is_bounded():
    s = make_session()
    for i in range(60):
        s._log_replay("turn", text=f"t{i}", model="spark", model_id="m", frame=(JPG, "image/jpeg"))
    assert len(s.replay_log) <= 24
