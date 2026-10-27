"""FIELDKIT x Hermes Jot bridge: live mission checklists in the wearer's notes.

Talks to Jot's bundled, port-free engine exactly the way Hermes does
(`plugins/jot/backend.py`): a short-lived Node process
(`runtime/worker.cjs <data-dir>`) exchanging one JSON tool call on
stdin/stdout. No network port, no Hermes host APIs, no credentials.

Config (read per call so tests and operators can relocate it):
- FIELDKIT_JOT_DIR:    Jot note store (default ~/.hermes/plugin-data/jot)
- FIELDKIT_JOT_WORKER: worker.cjs path (default the installed Hermes plugin)

Honest fallback is a design rule here: Jot is a mirror, never the mission's
source of truth. Every operation returns {"ok": bool, ...}; when AI
collaboration is off (AGENT_DISABLED) or the engine is missing, callers get
ok=False with a status they can surface, and the mission loop never breaks.
"""
from __future__ import annotations

import asyncio
import json
import os
import shutil
import subprocess
import time
from pathlib import Path

DEFAULT_WORKER = "/home/zoth/.hermes/plugins/jot/runtime/worker.cjs"
DEFAULT_DIR = "~/.hermes/plugin-data/jot"

# mission_id -> Jot note id. A mirror of what jot_create returned; the note
# itself carries "Mission ID: <id>" so it can be found again after a restart.
NOTE_BY_MISSION: dict[str, str] = {}


class JotBridgeError(Exception):
    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code


def jot_dir() -> Path:
    return Path(os.environ.get("FIELDKIT_JOT_DIR") or DEFAULT_DIR).expanduser()


def jot_worker() -> Path:
    return Path(os.environ.get("FIELDKIT_JOT_WORKER") or DEFAULT_WORKER).expanduser()


def call(name: str, args: dict | None = None, timeout: float = 30) -> dict:
    """One tool call against the Jot engine. Returns response["data"]."""
    node = shutil.which("node")
    worker = jot_worker()
    if not node or not worker.is_file():
        raise JotBridgeError("UNAVAILABLE", "Jot engine (Node + worker.cjs) not found on this host")
    payload = json.dumps({"kind": "tool", "name": name, "args": args or {}},
                         ensure_ascii=False).encode("utf-8")
    env = {k: v for k, v in os.environ.items()
           if k in {"PATH", "SYSTEMROOT", "WINDIR", "TEMP", "TMP", "TMPDIR", "LANG", "LC_ALL"}}
    try:
        result = subprocess.run([node, str(worker), str(jot_dir().resolve())],
                                input=payload, capture_output=True, env=env,
                                timeout=timeout, check=False)
    except (subprocess.TimeoutExpired, OSError) as e:
        raise JotBridgeError("UNAVAILABLE", f"Jot engine did not answer: {e.__class__.__name__}")
    try:
        response = json.loads(result.stdout)
    except (ValueError, UnicodeDecodeError):
        raise JotBridgeError("UNAVAILABLE", "Jot engine returned an invalid response")
    if not isinstance(response, dict):
        raise JotBridgeError("UNAVAILABLE", "Jot engine returned an invalid response")
    error = response.get("error")
    if isinstance(error, dict):
        raise JotBridgeError(str(error.get("code") or "ERROR"),
                             str(error.get("message") or "Jot error"))
    data = response.get("data")
    return data if isinstance(data, dict) else {}


async def acall(name: str, args: dict | None = None) -> dict:
    return await asyncio.to_thread(call, name, args)


def status() -> dict:
    """{available, ai_enabled, detail} — never raises."""
    try:
        call("jot_list", {"limit": 1})
        return {"available": True, "ai_enabled": True, "detail": "Jot AI collaboration is on"}
    except JotBridgeError as e:
        if e.code == "AGENT_DISABLED":
            return {"available": True, "ai_enabled": False,
                    "detail": "Jot AI collaboration is off (Hermes: /jot ai on)"}
        return {"available": False, "ai_enabled": False, "detail": str(e)}


def _fail(e: Exception) -> dict:
    code = getattr(e, "code", "ERROR")
    return {"ok": False, "status": "off" if code == "AGENT_DISABLED" else "unavailable",
            "detail": str(e), "code": code}


def _note_id(mission_id: str) -> str | None:
    if mission_id in NOTE_BY_MISSION:
        return NOTE_BY_MISSION[mission_id]
    try:
        data = call("jot_list", {"query": f"Mission ID: {mission_id}", "limit": 5})
        for n in data.get("notes") or []:
            if isinstance(n, dict) and n.get("id"):
                NOTE_BY_MISSION[mission_id] = str(n["id"])
                return NOTE_BY_MISSION[mission_id]
    except JotBridgeError as e:
        if e.code == "AGENT_DISABLED":
            raise   # surface "off", not a misleading "unavailable"
    return None


def _read(note_id: str) -> dict:
    return call("jot_read", {"id": note_id})


def _tasks(data: dict) -> list[dict]:
    tasks = data.get("tasks")
    if isinstance(tasks, list) and tasks:
        return tasks
    out = []
    for line in str(data.get("markdown") or data.get("text") or "").splitlines():
        line = line.strip()
        if line.startswith("- ["):
            out.append({"index": len(out) + 1,
                        "text": line[6:].strip(),
                        "checked": line[3:4].lower() == "x"})
    return out


def _revision(data: dict) -> int:
    try:
        return int(data.get("revision") or 0)
    except (TypeError, ValueError):
        return 0


async def sync_new_mission(mission) -> dict:
    """Create the checklist note for a fresh mission. Best effort."""
    try:
        lines = [f"# FieldKit Mission: {mission.goal}", "",
                 f"Mission ID: {mission.id}", ""]
        lines += [f"- [ ] {s.text}" for s in mission.steps]
        data = await acall("jot_create", {
            "title": f"FieldKit Mission: {mission.goal}"[:120],
            "text": "\n".join(lines), "format": "markdown"})
        note_id = str(data.get("id") or (data.get("note") or {}).get("id") or "")
        if not note_id:
            return {"ok": False, "status": "unavailable", "detail": "Jot created no note id"}
        NOTE_BY_MISSION[mission.id] = note_id
        mission.jot_id = note_id
        return {"ok": True, "note_id": note_id}
    except JotBridgeError as e:
        return _fail(e)


async def checkoff(mission_id: str, step_n: int) -> dict:
    """Tick step step_n (1-based) in the mission's Jot checklist."""
    try:
        note_id = _note_id(mission_id)
        if not note_id:
            return {"ok": False, "status": "unavailable", "detail": "no Jot note for this mission"}
        for _attempt in range(2):   # read fresh, retry once on conflict (Jot's own rule)
            data = _read(note_id)
            rev = _revision(data)
            try:
                await acall("jot_set_task", {"id": note_id, "revision": rev,
                                             "index": int(step_n), "checked": True})
                return {"ok": True, "note_id": note_id, "step": int(step_n)}
            except JotBridgeError as e:
                if e.code != "REVISION_CONFLICT":
                    raise
        return {"ok": False, "status": "unavailable", "detail": "revision conflict after retry"}
    except JotBridgeError as e:
        return _fail(e)


async def append_field_note(mission_id: str, text: str, step_label: str = "") -> dict:
    """Voice 'jot this down': timestamped observation on the mission note."""
    try:
        note_id = _note_id(mission_id) if mission_id else None
        if not note_id:
            return {"ok": False, "status": "unavailable",
                    "detail": "no Jot note for the active mission"}
        text = (text or "").strip()[:500]
        if not text:
            return {"ok": False, "status": "unavailable", "detail": "nothing to jot"}
        stamp = time.strftime("%H:%M UTC", time.gmtime())
        suffix = f" ({step_label})" if step_label else ""
        for _attempt in range(2):
            data = _read(note_id)
            try:
                await acall("jot_update", {"id": note_id, "revision": _revision(data),
                                           "appendText": f"\n- [{stamp}] {text}{suffix}"})
                return {"ok": True, "note_id": note_id, "text": text}
            except JotBridgeError as e:
                if e.code != "REVISION_CONFLICT":
                    raise
        return {"ok": False, "status": "unavailable", "detail": "revision conflict after retry"}
    except JotBridgeError as e:
        return _fail(e)


async def sync_mission(mission) -> dict:
    """jot_mission_sync: create the note if missing, then tick all done steps."""
    res = {"ok": True, "note_id": getattr(mission, "jot_id", "") or ""}
    if not res["note_id"] or not _note_id(mission.id):
        res = await sync_new_mission(mission)
        if not res["ok"]:
            return res
    done = [i + 1 for i, s in enumerate(mission.steps) if s.status in ("done", "skipped")]
    for n in done:
        out = await checkoff(mission.id, n)
        if not out["ok"]:
            return out
    return {"ok": True, "note_id": res["note_id"], "checked": done}
