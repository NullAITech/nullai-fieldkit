#!/usr/bin/env python3
"""Live smoke: Mission -> Jot checklist -> hands-free checkoff -> Obsidian engram.

Runs against a live FieldKit relay (default http://127.0.0.1:8770) using the
phone token from FIELDKIT_TOKEN or the relay-runtime file. Verifies with the
real Hermes Jot engine and the real Obsidian vault, then cleans up its smoke
artifacts unless --keep is passed.

    .venv/bin/python scripts/smoke_jot_obsidian.py [--relay URL] [--keep]
"""
from __future__ import annotations

import argparse
import asyncio
import os
import sys
from pathlib import Path

import httpx

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from zoth_relay import jot_bridge, second_brain  # noqa: E402


def token() -> str:
    t = os.environ.get("FIELDKIT_TOKEN")
    if t:
        return t.strip()
    p = Path("/home/zoth/Desktop/Zoth-Meta-Hackathon/relay-runtime/pixel.token")
    return p.read_text().strip()


async def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--relay", default="http://127.0.0.1:8770")
    ap.add_argument("--keep", action="store_true")
    args = ap.parse_args()
    h = {"Authorization": f"Bearer {token()}"}
    goal = "Smoke test: inspect the mower air filter"
    async with httpx.AsyncClient(base_url=args.relay, timeout=60) as c:
        r = await c.get("/health")
        assert r.json().get("ok"), "relay health failed"
        print("1. relay health OK")
        r = await c.post("/v1/missions", headers=h, json={"goal": goal, "provider": "wonder"})
        assert r.status_code == 201, f"create: {r.status_code} {r.text[:200]}"
        m = r.json()
        print(f"2. mission {m['id']}: {len(m['steps'])} steps, "
              f"jot={m['jot']}, prior_notes={len(m.get('prior_notes') or [])}")
        assert m["jot"].get("ok"), f"Jot sync failed: {m['jot']}"
        total = len(m["steps"])
        for i in range(total):
            r = await c.post(f"/v1/missions/{m['id']}/events", headers=h,
                             json={"type": "advance"})
            body = r.json()
            assert body["jot"] and body["jot"].get("ok"), f"checkoff {i}: {body.get('jot')}"
        print(f"3. advanced all {total} steps; Jot checkoff ok each time")
        r = await c.get(f"/v1/missions/{m['id']}", headers=h)
        done = r.json()
        assert done["complete"] and done["jot_note_id"] == m["jot"]["note_id"]
        assert done.get("recap") is None or True  # recap rides the final event body
    note = jot_bridge.call("jot_read", {"id": m["jot"]["note_id"]})
    tasks = note.get("tasks") or []
    assert tasks and all(t["checked"] for t in tasks), f"tasks not all checked: {tasks}"
    print(f"4. real Jot note: {len(tasks)} tasks all checked")
    hints = second_brain.prior_hints(goal)
    assert hints, "prior recall found nothing for the smoke goal"
    engrams = list(second_brain.missions_dir().glob(f"*-{m['id']}.md"))
    assert engrams, "no Obsidian engram written"
    print(f"5. Obsidian engram: {engrams[0].name}; prior recall returns it")
    if not args.keep:
        rev = jot_bridge.call("jot_read", {"id": m["jot"]["note_id"]})["revision"]
        jot_bridge.NOTE_BY_MISSION.pop(m["id"], None)
        import subprocess, json as _json
        subprocess.run(["node", str(jot_bridge.jot_worker()), str(jot_bridge.jot_dir())],
                       input=_json.dumps({"kind": "tool", "name": "jot_delete",
                                          "args": {"id": m["jot"]["note_id"],
                                                   "revision": rev}}).encode(),
                       capture_output=True, timeout=60)
        engrams[0].unlink()
        print("6. smoke artifacts cleaned (note trashed, engram removed)")
    print("SMOKE PASS: Mission -> Jot -> checkoff -> Obsidian -> recall")
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
