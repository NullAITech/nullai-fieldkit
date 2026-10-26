#!/usr/bin/env python3
"""FIELDKIT D1 smoke: one real turn through a provider preset.

Usage:
  META_API_KEY=... python3 scripts/smoke_provider.py [--provider spark] [--image frame.jpg] ["prompt"]

Exit 2 with guidance when the provider is not configured (no key). Never prints keys.
"""
import argparse
import asyncio
import base64
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from zoth_relay.ask import decode_image          # noqa: E402
from zoth_relay.providers import Turn, provider_presets  # noqa: E402


async def run(a) -> int:
    presets = provider_presets()
    p = presets.get(a.provider)
    if p is None:
        print(f"unknown provider {a.provider!r}; choose: {', '.join(presets)}")
        return 2
    if not p.configured:
        print(f"{p.name} ({p.model}) is not configured: set {p.api_key_env or 'its key env'} and retry.")
        return 2
    image = None
    if a.image:
        raw = Path(a.image).read_bytes()
        image = decode_image(base64.b64encode(raw).decode("ascii"))
    turn = Turn(prompt=a.prompt, image=image, mission="hackathon D1 smoke test")
    async for ev in p.stream_events(turn, "smoke"):
        print(ev)
    return 0


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--provider", default="spark")
    ap.add_argument("--image", help="JPEG/PNG/WebP frame for a vision turn")
    ap.add_argument("prompt", nargs="?",
                    default="In one short spoken sentence, say what you see and one next action.")
    raise SystemExit(asyncio.run(run(ap.parse_args())))
