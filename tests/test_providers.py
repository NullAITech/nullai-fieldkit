"""Provider layer: protocol-agnostic vision turns (D1, hackathon).

Transports: OpenAI-compatible (Meta Model API Spark/Wonder, Grok, Ollama, ...)
and Anthropic Messages (Meta /v1/messages passthrough). Upstreams are mocked;
no secrets or network. Events must match ask_events: start/delta/done/error.
"""
import json
import os

import httpx
import pytest

from zoth_relay.providers import (
    AnthropicMessagesProvider,
    OpenAICompatProvider,
    Turn,
    provider_presets,
)

PNG = (b"\x89PNG\r\n\x1a\n" + b"\x00" * 64, "image/png")


async def collect(gen):
    return [ev async for ev in gen]


def openai_mock(seen, pieces=("Look ", "left."), status=200):
    def handler(req: httpx.Request):
        seen.append({"url": str(req.url), "auth": req.headers.get("authorization"),
                     "key": req.headers.get("x-api-key"), "body": json.loads(req.content)})
        if status >= 400:
            return httpx.Response(status, text="upstream exploded")
        sse = "".join(f"data: {json.dumps({'choices': [{'delta': {'content': p}}]})}\n\n" for p in pieces)
        return httpx.Response(200, text=sse + "data: [DONE]\n\n",
                              headers={"content-type": "text/event-stream"})
    return httpx.MockTransport(handler)


def anthropic_mock(seen, pieces=("Look ", "left.")):
    def handler(req: httpx.Request):
        seen.append({"url": str(req.url), "auth": req.headers.get("authorization"),
                     "key": req.headers.get("x-api-key"), "body": json.loads(req.content)})
        sse = "".join(
            "event: content_block_delta\ndata: "
            + json.dumps({"type": "content_block_delta",
                          "delta": {"type": "text_delta", "text": p}}) + "\n\n" for p in pieces)
        return httpx.Response(200, text=sse, headers={"content-type": "text/event-stream"})
    return httpx.MockTransport(handler)


async def test_openai_transport_streams_and_posts_image(monkeypatch):
    monkeypatch.setenv("META_API_KEY", "test-key-123")
    seen = []
    p = OpenAICompatProvider("spark", "https://api.meta.ai/v1", "muse-spark-1.1",
                             api_key_env="META_API_KEY")
    ev = await collect(p.stream_events(Turn(prompt="What now?", mission="fix pump", image=PNG),
                                       "r1", openai_mock(seen)))
    assert ev[0]["type"] == "start" and ev[0]["agent"] == "spark" and ev[0]["model"] == "muse-spark-1.1"
    assert "".join(e["text"] for e in ev if e["type"] == "delta") == "Look left."
    assert ev[-1]["type"] == "done" and ev[-1]["text"] == "Look left." and "ms" in ev[-1]
    up = seen[-1]
    assert up["url"] == "https://api.meta.ai/v1/chat/completions"
    assert up["auth"] == "Bearer " + os.environ["META_API_KEY"]
    content = up["body"]["messages"][-1]["content"]
    assert content[1]["image_url"]["url"].startswith("data:image/png;base64,")
    assert "fix pump" in up["body"]["messages"][0]["content"]


async def test_anthropic_transport_parses_text_deltas(monkeypatch):
    monkeypatch.setenv("META_API_KEY", "test-key-123")
    seen = []
    p = AnthropicMessagesProvider("spark-msg", "https://api.meta.ai/v1", "muse-spark-1.1",
                                  api_key_env="META_API_KEY")
    ev = await collect(p.stream_events(Turn(prompt="What now?", image=PNG), "r2", anthropic_mock(seen)))
    assert "".join(e["text"] for e in ev if e["type"] == "delta") == "Look left."
    assert ev[-1]["type"] == "done"
    up = seen[-1]
    assert up["url"] == "https://api.meta.ai/v1/messages" and up["key"] == "test-key-123"
    block = up["body"]["messages"][-1]["content"][-1]
    assert block["type"] == "image" and block["source"]["media_type"] == "image/png"


def test_presets_register_spark_wonder_local(monkeypatch):
    monkeypatch.delenv("META_API_KEY", raising=False)
    presets = provider_presets()
    assert presets["spark"].model.startswith("muse-spark-")
    assert presets["spark"].base_url == "https://api.meta.ai/v1"
    assert presets["wonder"].model == "muse-wonder-1.0"
    assert presets["spark"].configured is False          # no key yet
    assert presets["local"].configured is True           # Ollama needs no key
    pub = presets["spark"].public()
    assert "api_key" not in json.dumps(pub) and "META_API_KEY" not in json.dumps(pub)


async def test_text_only_provider_drops_image():
    seen = []
    p = OpenAICompatProvider("txt", "http://127.0.0.1:11434/v1", "tiny", vision=False)
    await collect(p.stream_events(Turn(prompt="hi", image=PNG), "r3", openai_mock(seen)))
    assert seen[-1]["body"]["messages"][-1]["content"] == "hi"


async def test_upstream_error_is_error_event_not_exception():
    seen = []
    p = OpenAICompatProvider("spark", "https://api.meta.ai/v1", "muse-spark-1.1")
    ev = await collect(p.stream_events(Turn(prompt="hi"), "r4", openai_mock(seen, status=500)))
    assert ev[-1]["type"] == "error" and ev[-1]["status"] == 502
