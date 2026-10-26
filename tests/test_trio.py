"""FIELDKIT trio: Eyes dedupe, Advisor priorities, Director gating + presets."""
import json

import httpx
import pytest

from zoth_relay.providers import OpenAICompatProvider
from zoth_relay.trio import Advisor, Director, Eyes, Preset, custom_preset, get_preset

IMG = (b"\xff\xd8\xff\xe0" + b"\x00" * 32, "image/jpeg")


def provider(replies):
    """Mock upstream: pops one canned reply per call, records prompts."""
    seen = []

    def handler(req: httpx.Request):
        body = json.loads(req.content)
        seen.append(body["messages"][-1]["content"])
        text = replies.pop(0) if replies else "ok"
        sse = f"data: {json.dumps({'choices': [{'delta': {'content': text}}]})}\n\n" + "data: [DONE]\n\n"
        return httpx.Response(200, text=sse, headers={"content-type": "text/event-stream"})

    p = OpenAICompatProvider("mock", "https://up.example/v1", "m1")
    return p, httpx.MockTransport(handler), seen


async def test_eyes_silent_on_unchanged_scene():
    p, t, _ = provider(["A red mower on grass.", "NOCHANGE", "A red mower on grass."])
    eyes = Eyes(p, t)
    first = await eyes.observe(IMG, "fix mower")
    assert first is not None and "mower" in first.text
    assert await eyes.observe(IMG, "fix mower") is None          # model said NOCHANGE
    assert await eyes.observe(IMG, "fix mower") is None          # near-duplicate suppressed


async def test_advisor_parses_prioritized_suggestions():
    payload = json.dumps({"suggestions": [
        {"text": "Blade is exposed", "priority": "safety", "say": "Stop — blade exposed."},
        {"text": "Filter is next", "priority": "progress", "say": "Now the filter."},
        {"text": "junk", "priority": "bogus", "say": ""}]})
    p, t, _ = provider([payload])
    from zoth_relay.trio import Observation
    out = await Advisor(p, t).advise(Observation("blade visible"), "fix mower | step 2/7")
    assert [s.priority for s in out] == ["safety", "progress", "info"]
    assert out[0].say == "Stop — blade exposed."


async def test_director_safety_always_speaks_and_pause_mutes():
    from zoth_relay.trio import Observation, Suggestion
    p, t, _ = provider([])
    d = Director(p, t)
    quiet = Preset("quiet", chattiness=0.0)
    dec = await d.decide(Observation("x"), [Suggestion("s", "safety", "Stop.")], "", "m", quiet)
    assert dec.speak and dec.text == "Stop."                      # safety beats chattiness=0
    dec = await d.decide(Observation("x"), [Suggestion("s", "social", "Hi.")], "", "m", quiet)
    assert not dec.speak                                          # social gated by quiet preset
    d.paused = True
    dec = await d.decide(Observation("x"), [Suggestion("s", "progress", "Go.")], "", "m", get_preset("social"))
    assert not dec.speak and dec.reason == "paused"


def test_presets_and_custom():
    assert get_preset("teach-me").teaching == 1.0
    assert get_preset("nonsense").name == "get-it-done"
    c = custom_preset("social", chattiness=0.1, progress=2.0)
    assert c.name == "custom" and c.chattiness == 0.1 and c.progress == 1.0  # clamped
