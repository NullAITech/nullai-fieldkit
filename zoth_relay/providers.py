"""Protocol-agnostic vision providers (FIELDKIT hackathon, D1).

One turn shape in, one event stream out — regardless of wire protocol:

  Turn(system, mission, history, prompt, image?, video_url?) -> events
  events: {"type":"start"|"delta"|"done"|"error", ...}  (same as ask.ask_events,
  plus "first_ms" time-to-first-delta on "done" for latency instrumentation)

Transports:
- OpenAICompatProvider: OpenAI-style /chat/completions. Serves the Meta Model
  API (Muse Spark planner/reactor + Muse Wonder planner), xAI/Grok, OpenRouter,
  Ollama /v1 and LM Studio — anything speaking that protocol.
- AnthropicMessagesProvider: Anthropic-style /messages. The Meta Model API
  exposes it natively (preserves thinking blocks); also direct Anthropic.

Keys come from the environment only (api_key_env), never from requests or
config files. Images are held in memory for the upstream call only.

Roadmap: providers are selected by name (provider_presets). Sessions (D2) will
consume stream_events directly; /v1/ask keeps using ask.py unchanged.
"""
from __future__ import annotations

import base64
import json
import os
import time
from dataclasses import dataclass, field
from typing import AsyncIterator

import httpx

from .ask import AskError, AskTarget, build_messages, stream_reply

META_BASE = "https://api.meta.ai/v1"


@dataclass
class Turn:
    """One vision turn. history items: {"role","content"} dicts or (role, text)."""
    prompt: str
    system: str = ""
    mission: str = ""
    history: list = field(default_factory=list)
    image: tuple[bytes, str] | None = None      # (raw bytes, mime)
    video_url: str | None = None                # short clip on scene change (D2)
    max_tokens: int | None = None


def _history_dicts(history: list) -> list[dict]:
    out = []
    for h in history or []:
        if isinstance(h, dict):
            out.append({"role": h.get("role", ""), "content": h.get("content", "")})
        else:  # (role, text)
            out.append({"role": h[0], "content": h[1]})
    return out


class Provider:
    """Base: a named model endpoint that streams turn events."""

    transport_kind = "abstract"

    def __init__(self, name: str, base_url: str, model: str, api_key_env: str = "",
                 vision: bool = True, max_tokens: int = 300, timeout: int = 60):
        self.name, self.base_url, self.model = name, base_url.rstrip("/"), model
        self.api_key_env, self.vision = api_key_env, vision
        self.max_tokens, self.timeout = max_tokens, timeout
        self.supports_video = False

    @property
    def api_key(self) -> str:
        return os.environ.get(self.api_key_env, "") if self.api_key_env else ""

    @property
    def configured(self) -> bool:
        return not self.api_key_env or bool(self.api_key)

    def public(self) -> dict:
        # No key material and no key-env names: safe to expose over the API.
        return {"name": self.name, "model": self.model, "transport": self.transport_kind,
                "vision": self.vision, "configured": self.configured}

    async def stream_events(self, turn: Turn, request_id: str,
                            transport: httpx.AsyncBaseTransport | None = None) -> AsyncIterator[dict]:
        raise NotImplementedError

    async def _emit(self, request_id: str, pieces: AsyncIterator[str]) -> AsyncIterator[dict]:
        """Wraps a text-delta stream in ask-compatible events with latency."""
        t0 = time.monotonic()
        first_ms: int | None = None
        full: list[str] = []
        yield {"type": "start", "id": request_id, "agent": self.name, "model": self.model}
        try:
            async for piece in pieces:
                if first_ms is None:
                    first_ms = int((time.monotonic() - t0) * 1000)
                full.append(piece)
                yield {"type": "delta", "text": piece}
        except AskError as e:
            yield {"type": "error", "status": e.status, "detail": e.detail}
            return
        except httpx.HTTPError as e:
            yield {"type": "error", "status": 502,
                   "detail": f"upstream {self.name} unreachable: {type(e).__name__}"}
            return
        yield {"type": "done", "id": request_id, "text": "".join(full),
               "ms": int((time.monotonic() - t0) * 1000),
               "first_ms": first_ms if first_ms is not None else int((time.monotonic() - t0) * 1000)}


class OpenAICompatProvider(Provider):
    """OpenAI-style /chat/completions transport (Meta Model API default path)."""

    transport_kind = "openai"

    def __init__(self, *a, extra: dict | None = None, **kw):
        super().__init__(*a, **kw)
        self.extra = dict(extra or {})
        self.supports_video = self.base_url.startswith(META_BASE)  # Meta accepts video parts
        self._target = AskTarget(name=self.name, base_url=self.base_url, model=self.model,
                                 api_key_env=self.api_key_env, vision=self.vision,
                                 max_tokens=self.max_tokens, timeout=self.timeout,
                                 extra=self.extra)

    def _messages(self, turn: Turn) -> list[dict]:
        image = turn.image if self.vision else None
        msgs = build_messages(self._target, turn.prompt, image,
                              _history_dicts(turn.history), turn.system, turn.mission)
        if turn.video_url and self.supports_video:
            # Short clip on scene change: attach beside the last user message so
            # the model tracks action/progress, not just a still (D2 verifies wire format).
            last = msgs[-1]
            part = {"type": "video_url", "video_url": {"url": turn.video_url}}
            if isinstance(last.get("content"), list):
                last["content"].append(part)
            else:
                last["content"] = [{"type": "text", "text": last["content"]}, part]
        return msgs

    async def stream_events(self, turn: Turn, request_id: str,
                            transport: httpx.AsyncBaseTransport | None = None) -> AsyncIterator[dict]:
        async for ev in self._emit(request_id, stream_reply(
                self._target, self._messages(turn), turn.max_tokens, transport)):
            yield ev


class AnthropicMessagesProvider(Provider):
    """Anthropic-style /messages transport (Meta passthrough incl. thinking blocks)."""

    transport_kind = "anthropic-messages"

    def _payload(self, turn: Turn) -> dict:
        msgs: list[dict] = []
        for h in _history_dicts(turn.history):
            if h["role"] in ("user", "assistant") and isinstance(h["content"], str):
                msgs.append({"role": h["role"], "content": h["content"][:4000]})
        blocks: list[dict] = [{"type": "text", "text": turn.prompt}]
        if turn.image and self.vision:
            raw, mime = turn.image
            blocks.append({"type": "image", "source": {
                "type": "base64", "media_type": mime,
                "data": base64.b64encode(raw).decode("ascii")}})
        msgs.append({"role": "user", "content": blocks})
        system = turn.system or ""
        if turn.mission:
            system = (system + f"\nCurrent mission: {turn.mission}").strip()
        body: dict = {"model": self.model, "max_tokens": min(turn.max_tokens or self.max_tokens, 4000),
                      "stream": True, "messages": msgs}
        if system:
            body["system"] = system
        return body

    async def _pieces(self, turn: Turn,
                      transport: httpx.AsyncBaseTransport | None) -> AsyncIterator[str]:
        headers = {"content-type": "application/json", "anthropic-version": "2023-06-01"}
        if self.api_key:
            headers["x-api-key"] = self.api_key
        async with httpx.AsyncClient(timeout=httpx.Timeout(self.timeout, connect=10),
                                     transport=transport) as client:
            async with client.stream("POST", f"{self.base_url}/messages",
                                     headers=headers, json=self._payload(turn)) as r:
                if r.status_code >= 400:
                    txt = (await r.aread()).decode("utf-8", "replace")[:300]
                    raise AskError(502, f"upstream {self.name} returned {r.status_code}: {txt}")
                async for line in r.aiter_lines():
                    line = line.strip()
                    if not line.startswith("data:"):
                        continue
                    try:
                        obj = json.loads(line[5:].strip())
                    except json.JSONDecodeError:
                        continue
                    if obj.get("type") == "content_block_delta":
                        piece = (obj.get("delta") or {}).get("text")
                        if piece:
                            yield piece

    async def stream_events(self, turn: Turn, request_id: str,
                            transport: httpx.AsyncBaseTransport | None = None) -> AsyncIterator[dict]:
        async for ev in self._emit(request_id, self._pieces(turn, transport)):
            yield ev


def provider_presets() -> dict[str, Provider]:
    """Named providers from the environment. Meta path is the judging default."""
    spark = os.environ.get("ZOTH_SPARK_MODEL", "muse-spark-1.1")
    wonder = os.environ.get("ZOTH_WONDER_MODEL", "muse-wonder-1.0")
    return {
        # Fast reactor: reactive coaching turns, minimal reasoning (tunable via extra).
        "spark": OpenAICompatProvider("spark", META_BASE, spark, api_key_env="META_API_KEY",
                                      extra={"reasoning_effort": "minimal"}),
        # Deep planner: mission planning + periodic review (rare, high-value calls).
        "wonder": OpenAICompatProvider("wonder", META_BASE, wonder, api_key_env="META_API_KEY",
                                       max_tokens=1200),
        # Same models over the Anthropic Messages wire (thinking blocks preserved).
        "spark-messages": AnthropicMessagesProvider("spark-messages", META_BASE, spark,
                                                    api_key_env="META_API_KEY"),
        "wonder-messages": AnthropicMessagesProvider("wonder-messages", META_BASE, wonder,
                                                     api_key_env="META_API_KEY", max_tokens=1200),
        # Meta open weights (Best Open Weights track): Muse Spark Open on
        # partner hosts. First-class per host; each is env-gated and never
        # silently routed until its key lands (Neal claims via zoth-vault).
        # Baseten: BASETEN_API_KEY (+ optional SPARK_OPEN_BASE_URL override).
        "spark-open": OpenAICompatProvider(
            "spark-open",
            os.environ.get("SPARK_OPEN_BASE_URL", "https://inference.baseten.co/v1"),
            os.environ.get("ZOTH_SPARK_OPEN_MODEL", "meta-models/muse-spark-open-1026"),
            api_key_env="BASETEN_API_KEY",
            extra={"reasoning_effort": "minimal"}),
        # Modal: MUSE_OPEN_MODAL_URL is the endpoint, MODAL_API_KEY its key.
        "spark-open-modal": OpenAICompatProvider(
            "spark-open-modal",
            os.environ.get("MUSE_OPEN_MODAL_URL", ""),
            os.environ.get("ZOTH_SPARK_OPEN_MODEL", "meta-models/muse-spark-open-1026"),
            api_key_env="MODAL_API_KEY",
            extra={"reasoning_effort": "minimal"}),
        # Offline fallback: local Ollama OpenAI endpoint, no key.
        "local": OpenAICompatProvider("local", os.environ.get(
            "ZOTH_LOCAL_BASE", "http://127.0.0.1:11434/v1"),
            os.environ.get("ZOTH_LOCAL_MODEL", "tiny")),
    }
