#!/usr/bin/env python3
"""FIELDKIT D3 live check: one spoken sentence -> structured checklist.

Usage:
  META_API_KEY=... python3 scripts/plan_mission.py [--provider wonder] "replace the air filter"

Exit 2 when the provider is not configured. Prints steps + timing only.
"""
import argparse
import asyncio
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from zoth_relay.missions import MissionError, plan_checklist  # noqa: E402
from zoth_relay.providers import provider_presets  # noqa: E402


async def run(a) -> int:
    presets = provider_presets()
    p = presets.get(a.provider)
    if p is None:
        print(f"unknown provider {a.provider!r}; choose: {', '.join(presets)}")
        return 2
    if not p.configured:
        print(f"{p.name} ({p.model}) is not configured: set {p.api_key_env or 'its key env'} and retry.")
        return 2
    t0 = time.monotonic()
    try:
        steps = await plan_checklist(p, a.goal)
    except MissionError as e:
        print(f"planner error {e.status}: {e.detail}")
        return 1
    ms = int((time.monotonic() - t0) * 1000)
    print(f"provider={p.name} model={p.model} ms={ms} steps={len(steps)}")
    for i, s in enumerate(steps, 1):
        print(f"{i}. {s}")
    return 0


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--provider", default="wonder")
    ap.add_argument("goal", nargs="?", default="replace the air filter on a mower")
    raise SystemExit(asyncio.run(run(ap.parse_args())))
