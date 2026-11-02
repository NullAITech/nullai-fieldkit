"""FIELDKIT grounding + recap (D6, stretch — env-gated, honest).

- SAM 3.1 step-target highlight: POSTs the current frame + current step
  text to the SAM endpoint (env SAM_API_URL; Meta Model API when hosted
  there) and returns normalized boxes. The PWA/Spotter preview draws them;
  the relay never fakes a box it didn't get.
- Muse Image recap card: on mission completion, asks Muse Image
  (Meta Model API /images/generations, model env MUSE_IMAGE_MODEL) for a
  visual recap, saves it into the vault assets, embeds it in the Obsidian
  engram and notes it on the Hermes Jot checklist. If Muse Image is
  unconfigured, an honest local SVG card is generated instead — labelled
  as local, never passed off as model output.
"""
from __future__ import annotations

import base64
import html
import os

import httpx

META_BASE = "https://api.meta.ai/v1"


def _key() -> str:
    return os.environ.get("META_API_KEY") or ""


# ------------------------------------------------------------ SAM 3.1
class SamHighlighter:
    def __init__(self, transport: httpx.AsyncBaseTransport | None = None):
        self.transport = transport

    @property
    def configured(self) -> bool:
        return bool(os.environ.get("SAM_API_URL"))

    async def highlight(self, image: tuple[bytes, str], step_text: str) -> list[dict]:
        """Normalized boxes [{x,y,w,h,label,score}] for the step's target."""
        url = os.environ.get("SAM_API_URL") or ""
        if not url or not step_text:
            return []
        raw, mime = image
        payload = {"image": f"data:{mime};base64," + base64.b64encode(raw).decode(),
                   "prompt": step_text[:200]}
        headers = {"Authorization": f"Bearer {_key()}"} if _key() else {}
        try:
            async with httpx.AsyncClient(transport=self.transport, timeout=20) as c:
                r = await c.post(url, json=payload, headers=headers)
                r.raise_for_status()
                doc = r.json()
        except Exception:
            return []
        raw_boxes = doc.get("boxes") or doc.get("results") or []
        out = []
        for b in raw_boxes[:8]:
            if not isinstance(b, dict):
                continue
            box = b.get("box") if isinstance(b.get("box"), dict) else b
            try:
                out.append({"x": float(box["x"]), "y": float(box["y"]),
                            "w": float(box["w"]), "h": float(box["h"]),
                            "label": str(b.get("label") or step_text)[:60],
                            "score": float(b.get("score", 0) or 0)})
            except (KeyError, TypeError, ValueError):
                continue
        return out


# ------------------------------------------------------------ "what it saw" replay (Nov 2)
def standin_boxes(step_text: str) -> list[dict]:
    """Honest placeholder when SAM_API_URL is unset: one centred box, labelled
    as a stand-in so it is never mistaken for a SAM 3.1 segmentation."""
    label = f"stand-in (SAM 3.1 not configured): {step_text}"[:60] if step_text \
        else "stand-in (SAM 3.1 not configured)"
    return [{"x": 0.3, "y": 0.3, "w": 0.4, "h": 0.4, "label": label, "score": 0.0,
             "source": "stand-in"}]


async def build_replay(session, transport: httpx.AsyncBaseTransport | None = None) -> dict:
    """Frames the guide used, highlight boxes, the steps it gave, when, and
    which model answered each one. Built on demand from the session's bounded
    in-memory log; frames are inlined as data URIs and never written to disk."""
    sam = SamHighlighter(transport or getattr(session, "transport", None))
    steps, models = [], []
    for e in list(getattr(session, "replay_log", [])):
        frame = e.get("frame")
        target = e.get("step") or e.get("text") or ""
        boxes = [dict(b, source=b.get("source", "sam-3.1")) for b in (e.get("boxes") or [])]
        if frame is not None and not boxes:
            if sam.configured:
                boxes = [dict(b, source="sam-3.1") for b in await sam.highlight(frame, target)]
            else:
                boxes = standin_boxes(target)
        image = None
        if frame is not None:
            raw, mime = frame
            image = f"data:{mime};base64," + base64.b64encode(raw).decode("ascii")
        if e.get("model") and e["model"] not in models:
            models.append(e["model"])
        steps.append({"kind": e.get("kind"), "at": e.get("t"),
                      "offset_s": round(e.get("t", 0) - getattr(session, "started_at", e.get("t", 0)), 1),
                      "step": e.get("step") or "", "prompt": e.get("prompt") or "",
                      "text": e.get("text") or "", "model": e.get("model"),
                      "model_id": e.get("model_id"), "switched_from": e.get("switched_from"),
                      "image": image, "boxes": boxes})
    m = session._mission() if hasattr(session, "_mission") else None
    return {"mission": m.goal if m else (getattr(session, "mission", "") or ""),
            "started_at": getattr(session, "started_at", None),
            "sam": "sam-3.1" if sam.configured else "stand-in",
            "models": models, "steps": steps}


# ------------------------------------------------------------ Muse Image recap
def _prompt(mission) -> str:
    done = [s.text for s in mission.steps if s.status in ("done", "skipped")]
    return ("Clean flat-lay celebration card for a completed hands-on job: "
            f"{mission.goal}. Show the finished result, tidy tools, warm light. "
            f"Completed steps: {'; '.join(done[:6])}. No text overlays.")


async def generate_image(mission, transport: httpx.AsyncBaseTransport | None = None) -> bytes | None:
    if not _key():
        return None
    model = os.environ.get("MUSE_IMAGE_MODEL", "muse-image")
    try:
        async with httpx.AsyncClient(transport=transport, timeout=60) as c:
            r = await c.post(f"{META_BASE}/images/generations",
                             json={"model": model, "prompt": _prompt(mission)},
                             headers={"Authorization": f"Bearer {_key()}"})
            r.raise_for_status()
            data = (r.json().get("data") or [{}])[0]
            if data.get("b64_json"):
                return base64.b64decode(data["b64_json"])
            if data.get("url"):
                got = await c.get(data["url"])
                got.raise_for_status()
                return got.content
    except Exception:
        return None
    return None


def local_card_svg(mission) -> bytes:
    lines = [f'<text x="40" y="70" font-size="30" fill="#111">{html.escape(mission.goal[:48])}</text>']
    y = 120
    for i, s in enumerate(mission.steps, 1):
        mark = "✓" if s.status in ("done", "skipped") else "·"
        lines.append(f'<text x="40" y="{y}" font-size="20" fill="#333">'
                     f"{mark} {i}. {html.escape(s.text[:52])}</text>")
        y += 30
    lines.append(f'<text x="40" y="{y + 16}" font-size="14" fill="#888">'
                 "FieldKit by NullAI — local recap card (Muse Image unconfigured)</text>")
    svg = ('<svg xmlns="http://www.w3.org/2000/svg" width="640" height="420">'
           '<rect width="640" height="420" fill="#ffffff"/>'
           '<rect x="24" y="24" width="592" height="372" rx="18" fill="#f4f4f5"/>'
           + "".join(lines) + "</svg>")
    return svg.encode("utf-8")


async def maybe_recap(mission, transport: httpx.AsyncBaseTransport | None = None,
                      force: bool = False) -> dict:
    """Attach a recap card once a mission completes. Best effort.

    force=True (explicit POST /v1/missions/{id}/recap) regenerates even for
    an in-progress mission."""
    if not mission.complete and not force:
        return {"ok": False, "detail": "mission not complete"}
    if getattr(mission, "recap", "") and not force:
        return {"ok": True, "recap": mission.recap, "source": "existing"}
    from . import jot_bridge, second_brain
    img = await generate_image(mission, transport)
    source = "muse-image" if img else "local"
    blob = img if img else local_card_svg(mission)
    ext = "png" if img else "svg"
    assets = second_brain.missions_dir() / "assets"
    try:
        assets.mkdir(parents=True, exist_ok=True)
        name = f"recap-{mission.id}.{ext}"
        (assets / name).write_bytes(blob)
        mission.recap = name
        second_brain.compile_mission(mission)
    except Exception as e:
        return {"ok": False, "detail": f"vault write failed: {e.__class__.__name__}"}
    await jot_bridge.append_field_note(
        mission.id, f"Recap card ({source}) saved: FieldKit-Missions/assets/{name}")
    return {"ok": True, "recap": name, "source": source}
