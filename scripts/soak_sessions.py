#!/usr/bin/env python3
"""FIELDKIT D2 soak: N live turns through Session + a provider preset.

Checks the roadmap's core promise: bounded context => flat per-turn latency
as the transcript grows. Prints first_ms/ms per turn + history growth.

Usage:
  META_API_KEY=... python3 scripts/soak_sessions.py [--provider spark] [--turns 10]

Exit 2 when the provider is not configured. Never prints keys or replies.
"""
import argparse
import asyncio
import statistics
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from zoth_relay.providers import provider_presets  # noqa: E402
from zoth_relay.sessions import Session  # noqa: E402

PROMPTS = [
    "I am looking at a cluttered workbench. What should I do first?",
    "The clamp is on the left side. Is it tight enough?",
    "I picked up the wrench. What is the next step?",
    "There is a loose screw near the motor. Should I worry?",
    "I finished tightening. What did I miss earlier in this session?",
]


async def run(a) -> int:
    presets = provider_presets()
    p = presets.get(a.provider)
    if p is None:
        print(f"unknown provider {a.provider!r}; choose: {', '.join(presets)}")
        return 2
    if not p.configured:
        print(f"{p.name} ({p.model}) is not configured: set {p.api_key_env or 'its key env'} and retry.")
        return 2

    async def send(obj):
        pass  # metrics live on the session; nothing to display per-event

    sess = Session(p, send)
    sess.set_hello("bench soak: assemble the field kit", "", None)
    print(f"provider={p.name} model={p.model} turns={a.turns} budget={sess.ctx.budget}")
    for i in range(a.turns):
        await sess.run_turn(PROMPTS[i % len(PROMPTS)], "soak")
        m = sess.metrics[-1]
        print(f"turn {i + 1:2d}: first_ms={m['first_ms']} ms={m['ms']} "
              f"history_chars={sess.ctx.chars}")
    firsts = [m["first_ms"] for m in sess.metrics if m["first_ms"] is not None]
    if firsts:
        print(f"first_ms p50={int(statistics.median(firsts))} min={min(firsts)} max={max(firsts)}")
    print(f"frames_sent={sess.frames_sent} turns={sess.turns} final_history_chars={sess.ctx.chars}")
    return 0


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--provider", default="spark")
    ap.add_argument("--turns", type=int, default=10)
    raise SystemExit(asyncio.run(run(ap.parse_args())))
