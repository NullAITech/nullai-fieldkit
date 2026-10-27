"""FIELDKIT Second Brain: sovereign mission engrams in the Obsidian vault.

Every mission becomes an Obsidian Flavored Markdown note in
`<vault>/FieldKit-Missions/` (env FIELDKIT_VAULT_DIR, default
`~/Documents/Obsidian Vault`) with rich YAML frontmatter, checklists,
safety/advisor callouts and wikilinks, plus a `FieldKit Missions.base`
database view and a `FieldKit Architecture & Mission Flow.canvas` map of
the live pipeline. Prior missions are searchable: before Wonder plans a
new mission, `prior_hints` recalls matching equipment/keyword notes so the
coach can say "last time on step 3, you noted the left bolt was stripped."

Files only — no Obsidian app or network needed. Writes are best-effort at
call sites: a vault problem must never break a mission.
"""
from __future__ import annotations

import json
import os
import re
from pathlib import Path

DEFAULT_VAULT = "~/Documents/Obsidian Vault"
MISSIONS_DIRNAME = "FieldKit-Missions"
BASE_NAME = "FieldKit Missions.base"
CANVAS_NAME = "FieldKit Architecture & Mission Flow.canvas"
DEFAULT_HACKATHON_DIR = "/home/zoth/Desktop/Zoth-Meta-Hackathon"

_STOP = {"the", "a", "an", "on", "of", "to", "and", "my", "this", "that", "with"}


def vault_dir() -> Path:
    return Path(os.environ.get("FIELDKIT_VAULT_DIR") or DEFAULT_VAULT).expanduser()


def missions_dir() -> Path:
    return vault_dir() / MISSIONS_DIRNAME


def _slug(text: str) -> str:
    slug = re.sub(r"[^a-z0-9]+", "-", (text or "mission").lower()).strip("-")[:48]
    return slug or "mission"


def _q(text: str) -> str:
    return json.dumps(str(text or ""), ensure_ascii=False)


def compile_mission(mission, events: list[dict] | None = None,
                    spark_p50_ms: int | None = None) -> Path:
    """(Re)write the mission's engram note. Idempotent per mission id."""
    done = sum(1 for s in mission.steps if s.status in ("done", "skipped"))
    status = "complete" if mission.complete else "active"
    tags = "[fieldkit, meta-hackathon, ray-ban-meta]"
    fm = [
        "---",
        f"title: {_q('FieldKit Mission: ' + mission.goal)}",
        f"mission_id: {_q(mission.id)}",
        f"goal: {_q(mission.goal)}",
        f"status: {_q(status)}",
        "provider_plan: muse-wonder",
        "provider_vision: muse-spark",
        f"steps_total: {len(mission.steps)}",
        f"steps_done: {done}",
        f"spark_p50_ms: {int(spark_p50_ms) if spark_p50_ms else 'null'}",
        f"tags: {tags}",
        "---",
    ]
    body = [
        f"# FieldKit Mission: {mission.goal}",
        "",
        f"Mission `{mission.id}` - {status}, step {min(mission.current, len(mission.steps))}"
        f" of {len(mission.steps)}.",
        "",
        "## Checklist",
    ]
    for s in mission.steps:
        box = "x" if s.status in ("done", "skipped") else " "
        body.append(f"- [{box}] {s.text}" + (" (skipped)" if s.status == "skipped" else ""))
    body += ["", "## Events"]
    kinds = {"unsafe": "warning", "off_track": "caution"}
    for ev in events or []:
        kind = kinds.get(str(ev.get("type") or ""), "tip")
        body.append(f"> [!{kind}] {ev.get('type', 'note')}")
        body.append(f"> {str(ev.get('text') or ev.get('say') or '')[:500]}")
        body.append("")
    if getattr(mission, "jot_id", ""):
        body += ["", f"Hermes Jot checklist note: `{mission.jot_id}`"]
    body += ["", "Pipeline: [[FieldKit Architecture & Mission Flow]] - "
             "all missions: [[FieldKit Missions.base]]"]
    out_dir = missions_dir()
    out_dir.mkdir(parents=True, exist_ok=True)
    path = out_dir / f"{_slug(mission.goal)}-{mission.id}.md"
    path.write_text("\n".join(fm + [""] + body) + "\n", encoding="utf-8")
    return path


def write_base() -> Path:
    """Obsidian Base: table + card views over every FieldKit mission note."""
    content = (
        "filters:\n"
        "  and:\n"
        "    - file.inFolder(\"FieldKit-Missions\")\n"
        "    - file.hasTag(\"fieldkit\")\n"
        "properties:\n"
        "  goal:\n"
        "    displayName: Goal\n"
        "  status:\n"
        "    displayName: Status\n"
        "  steps_done:\n"
        "    displayName: Done\n"
        "  steps_total:\n"
        "    displayName: Total\n"
        "  spark_p50_ms:\n"
        "    displayName: Spark p50 ms\n"
        "views:\n"
        "  - type: table\n"
        "    name: All missions\n"
        "    order: [file.name, goal, status, steps_done, steps_total, spark_p50_ms]\n"
        "  - type: cards\n"
        "    name: Mission cards\n"
        "    order: [file.name, goal, status]\n"
    )
    out_dir = missions_dir()
    out_dir.mkdir(parents=True, exist_ok=True)
    path = out_dir / BASE_NAME
    path.write_text(content, encoding="utf-8")
    return path


def write_canvas() -> Path:
    """JSON Canvas 1.0 map of the live FieldKit pipeline."""
    def node(i, text, x, y, color=None):
        n = {"id": i, "type": "text", "text": text, "x": x, "y": y,
             "width": 260, "height": 120}
        if color:
            n["color"] = color
        return n

    def edge(i, a, b, label):
        return {"id": i, "fromNode": a, "toNode": b, "fromSide": "right",
                "toSide": "left", "label": label}

    nodes = [
        node("glasses", "## Ray-Ban Meta (DAT)\nGlasses POV, Meta AI permission flow", -560, 0, "4"),
        node("relay", "## FieldKit Relay (:8770)\nSessions + Missions APIs, bounded context", -240, 0, "5"),
        node("wonder", "## Muse Wonder\nPlanner + typed step assessor", 80, -140, "6"),
        node("spark", "## Muse Spark\nEyes -> Advisor -> Director proactive coach", 80, 60, "3"),
        node("jot", "## Hermes Jot\nLive checklist ticks itself off", 400, -140, "2"),
        node("brain", "## Obsidian Second Brain\nMission engrams + prior-job recall", 400, 60, "1"),
        {"id": "base", "type": "file", "file": f"{MISSIONS_DIRNAME}/{BASE_NAME}",
         "x": 400, "y": 260, "width": 260, "height": 120},
    ]
    edges = [
        edge("e1", "glasses", "relay", "frames + voice (WS /v1/sessions)"),
        edge("e2", "relay", "wonder", "one sentence -> checklist"),
        edge("e3", "relay", "spark", "changed frames only"),
        edge("e4", "spark", "jot", "step_done -> jot_set_task"),
        edge("e5", "spark", "brain", "compile engram"),
        edge("e6", "brain", "wonder", "prior_notes recall"),
        edge("e7", "brain", "base", "frontmatter views"),
    ]
    out_dir = missions_dir()
    out_dir.mkdir(parents=True, exist_ok=True)
    path = out_dir / CANVAS_NAME
    path.write_text(json.dumps({"nodes": nodes, "edges": edges}, indent=2) + "\n",
                    encoding="utf-8")
    return path


def _keywords(text: str) -> set[str]:
    return {w for w in re.findall(r"[a-z0-9]{3,}", (text or "").lower())
            if w not in _STOP}


def prior_hints(goal: str, limit: int = 3) -> list[dict]:
    """Compact recall from prior mission notes (+ Jot, best effort).

    Returns [{mission_id, goal, note}] ranked by keyword overlap. Only
    missions with real overlap surface — an unrelated goal gets silence.
    """
    want = _keywords(goal)
    if not want:
        return []
    out: list[tuple[int, dict]] = []
    d = missions_dir()
    if d.is_dir():
        for path in sorted(d.glob("*.md")):
            text = path.read_text(encoding="utf-8", errors="ignore")
            fm_goal, mid = "", ""
            head = text[:1200]
            m = re.search(r"^goal:\s*\"?(.*?)\"?$", head, re.M)
            if m:
                fm_goal = m.group(1)
            m = re.search(r"^mission_id:\s*\"?([\w-]+)", head, re.M)
            if m:
                mid = m.group(1)
            score = len(want & _keywords(fm_goal + " " + path.stem))
            if score < 2:
                continue
            notes = [ln.strip()[2:] for ln in text.splitlines()
                     if ln.strip().startswith("- [") and "UTC]" in ln]
            field = [ln.strip() for ln in text.splitlines() if "UTC]" in ln]
            snippet = (field[0][:160] if field else
                       f"Prior mission '{fm_goal}' ({len(notes)} field notes)")
            out.append((score, {"mission_id": mid, "goal": fm_goal, "note": snippet}))
    try:
        from . import jot_bridge
        words = " ".join(sorted(want)[:2])
        data = jot_bridge.call("jot_list", {"query": words, "limit": 5})
        for n in data.get("notes") or []:
            title = str((n or {}).get("title") or "")
            if "fieldkit" in title.lower() and _keywords(title) & want:
                out.append((1, {"mission_id": "", "goal": title,
                                "note": f"Jot note: {title}"[:160]}))
    except Exception:
        pass
    out.sort(key=lambda t: -t[0])
    return [h for _s, h in out[:limit]]


def mirror_command_center() -> Path:
    """Mirror HACKATHON-LOG milestones + PRIOR-WORK split into the vault."""
    src = Path(os.environ.get("FIELDKIT_HACKATHON_DIR") or DEFAULT_HACKATHON_DIR)
    parts = ["---", "title: \"FieldKit Command Center\"", "mission_id: \"command-center\"",
             "goal: \"Hackathon build log mirror\"", "status: \"active\"",
             "provider_plan: muse-wonder", "provider_vision: muse-spark",
             "steps_total: 0", "steps_done: 0", "spark_p50_ms: null",
             "tags: [fieldkit, meta-hackathon, command-center]", "---", "",
             "# FieldKit Command Center", "",
             "Pipeline: [[FieldKit Architecture & Mission Flow]]", ""]
    for name, heading in (("HACKATHON-LOG.md", "## Build log milestones"),
                          ("PRIOR-WORK.md", "## Prior vs new (honesty split)")):
        f = src / name
        parts.append(heading)
        parts.append("")
        parts.append(f.read_text(encoding="utf-8", errors="ignore")
                     if f.is_file() else "_(not found)_")
        parts.append("")
    out_dir = missions_dir()
    out_dir.mkdir(parents=True, exist_ok=True)
    path = out_dir / "FieldKit Command Center.md"
    path.write_text("\n".join(parts), encoding="utf-8")
    return path
