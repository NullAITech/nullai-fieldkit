"""Mobile "ask an agent" API: text (+ optional image) -> an AI target -> streamed reply.

Targets are configured in config.yaml under `ask_targets`. Every target speaks the
OpenAI-compatible chat-completions protocol (OpenAI, xAI/Grok, Gemini's OpenAI endpoint,
OpenRouter, Ollama's /v1, LM Studio, ...). API keys come from the environment
(`api_key_env`), never from the request and never from config.yaml.

Privacy: images and prompts are held in memory for the one upstream request only.
They are never written to disk and never logged; the audit log records sizes only.
"""
from __future__ import annotations

import base64
import binascii
import json
import os
import time
from dataclasses import dataclass
from typing import AsyncIterator

import httpx

DEFAULT_SYSTEM = (
    "You are Zoth, a real-time guide speaking into the user's ear through smart glasses. "
    "You may receive a camera frame of what they are looking at. Answer in one or two short, "
    "spoken-style sentences. Be specific and actionable. Never identify people by face; "
    "describe them only by position and clothing."
)
MAX_IMAGE_BYTES = 8 * 1024 * 1024
MAX_HISTORY = 20
IMAGE_MAGIC = {b"\xff\xd8\xff": "image/jpeg", b"\x89PNG\r\n\x1a\n": "image/png"}


class AskError(Exception):
    def __init__(self, status: int, detail: str):
        super().__init__(detail)
        self.status, self.detail = status, detail


@dataclass
class AskTarget:
    name: str
    base_url: str
    model: str
    api_key_env: str = ""          # env var holding the key; empty = no auth (e.g. local Ollama)
    vision: bool = True            # False = images are dropped (text-only model)
    max_tokens: int = 300
    timeout: int = 60
    system: str = ""               # per-target system prompt override
    label: str = ""
    extra: dict = None             # extra request fields, e.g. {"reasoning_effort": "none"}

    @property
    def api_key(self) -> str:
        return os.environ.get(self.api_key_env, "") if self.api_key_env else ""

    @property
    def configured(self) -> bool:
        return not self.api_key_env or bool(self.api_key)

    def public(self) -> dict:
        return {"name": self.name, "label": self.label or self.name, "model": self.model,
                "vision": self.vision, "configured": self.configured}


def parse_targets(raw: dict | None) -> dict[str, AskTarget]:
    out: dict[str, AskTarget] = {}
    for name, t in (raw or {}).items():
        t = t or {}
        if not t.get("base_url") or not t.get("model"):
            raise SystemExit(f"[zoth-relay] ask target '{name}' needs base_url and model")
        out[str(name)] = AskTarget(
            name=str(name), base_url=str(t["base_url"]).rstrip("/"), model=str(t["model"]),
            api_key_env=str(t.get("api_key_env") or ""), vision=bool(t.get("vision", True)),
            max_tokens=int(t.get("max_tokens", 300)), timeout=int(t.get("timeout", 60)),
            system=str(t.get("system") or ""), label=str(t.get("label") or ""),
            extra=dict(t.get("extra") or {}))
    return out


def decode_image(data: str, mime: str | None = None) -> tuple[bytes, str]:
    """Accepts raw base64 or a data: URI. Only JPEG/PNG/WebP, sniffed from magic bytes."""
    if data.startswith("data:"):
        head, _, data = data.partition(",")
        mime = mime or head[5:].split(";")[0]
    if len(data) > MAX_IMAGE_BYTES * 4 // 3 + 16:
        raise AskError(413, "image too large (max 8 MB)")
    try:
        raw = base64.b64decode(data, validate=True)
    except (binascii.Error, ValueError):
        raise AskError(400, "image is not valid base64")
    if len(raw) > MAX_IMAGE_BYTES:
        raise AskError(413, "image too large (max 8 MB)")
    sniffed = next((m for sig, m in IMAGE_MAGIC.items() if raw.startswith(sig)), None)
    if sniffed is None and raw[:4] == b"RIFF" and raw[8:12] == b"WEBP":
        sniffed = "image/webp"
    if sniffed is None:
        raise AskError(415, "image must be JPEG, PNG or WebP")
    return raw, sniffed


def build_messages(target: AskTarget, prompt: str, image: tuple[bytes, str] | None,
                   history: list[dict], system: str, mission: str) -> list[dict]:
    sys_txt = system or target.system or DEFAULT_SYSTEM
    if mission:
        sys_txt += f"\nCurrent mission: {mission}"
    msgs: list[dict] = [{"role": "system", "content": sys_txt}]
    for h in history[-MAX_HISTORY:]:
        if h.get("role") in ("user", "assistant") and isinstance(h.get("content"), str):
            msgs.append({"role": h["role"], "content": h["content"][:4000]})
    if image and target.vision:
        raw, mime = image
        url = f"data:{mime};base64," + base64.b64encode(raw).decode("ascii")
        msgs.append({"role": "user", "content": [
            {"type": "text", "text": prompt},
            {"type": "image_url", "image_url": {"url": url}}]})
    else:
        msgs.append({"role": "user", "content": prompt})
    return msgs


async def stream_reply(target: AskTarget, messages: list[dict], max_tokens: int | None,
                       transport: httpx.AsyncBaseTransport | None = None,
                       extra_headers: dict | None = None) -> AsyncIterator[str]:
    """Yields text deltas from an OpenAI-compatible streaming chat completion."""
    headers = {"Content-Type": "application/json", **(extra_headers or {})}
    if target.api_key:
        headers["Authorization"] = f"Bearer {target.api_key}"
    body = {"model": target.model, "messages": messages, "stream": True,
            "max_tokens": min(max_tokens or target.max_tokens, 4000)}
    for k, v in (target.extra or {}).items():
        body.setdefault(k, v)  # never overrides model/messages/stream/max_tokens
    async with httpx.AsyncClient(timeout=httpx.Timeout(target.timeout, connect=10),
                                 transport=transport) as client:
        async with client.stream("POST", f"{target.base_url}/chat/completions",
                                 headers=headers, json=body) as r:
            if r.status_code >= 400:
                txt = (await r.aread()).decode("utf-8", "replace")[:300]
                raise AskError(502, f"upstream {target.name} returned {r.status_code}: {txt}")
            async for line in r.aiter_lines():
                line = line.strip()
                if not line.startswith("data:"):
                    continue
                payload = line[5:].strip()
                if payload == "[DONE]":
                    break
                try:
                    obj = json.loads(payload)
                except json.JSONDecodeError:
                    continue
                for ch in obj.get("choices") or []:
                    piece = (ch.get("delta") or {}).get("content")
                    if piece:
                        yield piece


async def ask_events(target: AskTarget, messages: list[dict], max_tokens: int | None,
                     request_id: str, transport=None) -> AsyncIterator[dict]:
    t0 = time.monotonic()
    yield {"type": "start", "id": request_id, "agent": target.name, "model": target.model}
    full = []
    try:
        async for piece in stream_reply(target, messages, max_tokens, transport):
            full.append(piece)
            yield {"type": "delta", "text": piece}
    except AskError as e:
        yield {"type": "error", "status": e.status, "detail": e.detail}
        return
    except httpx.HTTPError as e:
        yield {"type": "error", "status": 502, "detail": f"upstream {target.name} unreachable: {type(e).__name__}"}
        return
    yield {"type": "done", "id": request_id, "text": "".join(full),
           "ms": int((time.monotonic() - t0) * 1000)}
