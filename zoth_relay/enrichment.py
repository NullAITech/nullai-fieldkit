"""Local Relay Model-Assist & Visual Enrichment Layer.

Pre-digests visual frames, on-device MediaPipe detections, 4-Angle Character Boards,
and active Mission state into compact structured JSON and prompt digests so that any
downstream model (Muse Spark, Muse Wonder, Gemini Live, Hermes, Local Ollama/Moondream,
Claude, or OpenAI) can reason rapidly and accurately with minimal latency.
"""
from __future__ import annotations

import base64
import io
import json
import os
import time
from typing import Any

import httpx


def _rgb_to_color_name(r: int, g: int, b: int) -> str:
    mx = max(r, g, b)
    mn = min(r, g, b)
    d = mx - mn
    lum = 0.299 * r + 0.587 * g + 0.114 * b
    if d < 22:
        if lum < 45:
            return "Jet Black"
        if lum < 95:
            return "Charcoal Grey"
        if lum < 155:
            return "Heather Grey"
        if lum < 205:
            return "Silver / Ash"
        return "Ivory White"
    if r > g and r >= b:
        if g > b + 25:
            return "Amber / Camel" if lum > 140 else "Warm Brown"
        return "Burgundy" if lum < 90 else "Crimson / Coral"
    if g >= r and g >= b:
        if b > r + 20:
            return "Teal / Sage"
        return "Forest Olive" if lum < 95 else "Emerald / Mint"
    if r > g + 25:
        return "Indigo / Violet"
    return "Midnight Navy" if lum < 90 else "Cobalt / Denim"


def _analyze_pixels_deterministic(raw_bytes: bytes, attire_hint: str = "", build_hint: str = "") -> dict[str, Any]:
    """Fast deterministic pixel/zone analysis for face + body crops using PIL if available."""
    upper_hex = "#2563EB"
    lower_hex = "#334155"
    upper_name = "Cobalt / Denim"
    lower_name = "Charcoal Grey"
    pattern = "Structured"
    lighting = "Balanced Indoor"
    feature_tags: list[str] = []

    try:
        from PIL import Image
        img = Image.open(io.BytesIO(raw_bytes)).convert("RGB")
        w, h = img.size
        small = img.resize((64, 64))
        px = list(small.getdata())

        # Overall luminance
        total_lum = sum(0.299 * r + 0.587 * g + 0.114 * b for r, g, b in px) / len(px)
        if total_lum < 65:
            lighting = "Low-Light / Dim"
        elif total_lum > 195:
            lighting = "Bright Key Light"
        else:
            lighting = "Balanced Natural Light"

        # Upper vs lower torso sampling
        ur = ug = ub = un = 0
        lr = lg = lb = ln = 0
        edge_sum = 0.0
        for y in range(10, 36):
            for x in range(12, 52):
                r, g, b = px[y * 64 + x]
                ur += r
                ug += g
                ub += b
                un += 1
                if x + 1 < 52:
                    r2, g2, b2 = px[y * 64 + x + 1]
                    edge_sum += abs((0.299 * r + 0.587 * g + 0.114 * b) - (0.299 * r2 + 0.587 * g2 + 0.114 * b2))
        for y in range(36, 58):
            for x in range(12, 52):
                r, g, b = px[y * 64 + x]
                lr += r
                lg += g
                lb += b
                ln += 1

        if un > 0:
            ur //= un
            ug //= un
            ub //= un
            upper_hex = f"#{ur:02X}{ug:02X}{ub:02X}"
            upper_name = _rgb_to_color_name(ur, ug, ub)
            avg_edge = edge_sum / un
            pattern = "Textured / Graphic" if avg_edge > 18.0 else ("Structured" if avg_edge > 9.5 else "Clean Solid")

        if ln > 0:
            lr //= ln
            lg //= ln
            lb //= ln
            lower_hex = f"#{lr:02X}{lg:02X}{lb:02X}"
            lower_name = _rgb_to_color_name(lr, lg, lb)

        # Check upper facial zones (crown/hair vs eye-band vs lower jaw)
        hair_lum = sum(0.299 * px[y * 64 + x][0] + 0.587 * px[y * 64 + x][1] + 0.114 * px[y * 64 + x][2]
                       for y in range(2, 12) for x in range(18, 46)) / (10 * 28)
        eye_edge = sum(abs(px[y * 64 + x][0] - px[y * 64 + x + 1][0])
                       for y in range(12, 22) for x in range(18, 45)) / (10 * 27)
        jaw_lum = sum(0.299 * px[y * 64 + x][0] + 0.587 * px[y * 64 + x][1] + 0.114 * px[y * 64 + x][2]
                      for y in range(22, 32) for x in range(20, 44)) / (10 * 24)

        if hair_lum < 55:
            feature_tags.append("Dark hair profile")
        elif hair_lum < 115:
            feature_tags.append("Medium-tone hair")
        else:
            feature_tags.append("Light/bright crown")

        if eye_edge > 14.5:
            feature_tags.append("High-contrast eye/brow or eyewear line")
        if abs(jaw_lum - hair_lum) < 28 and jaw_lum < 95:
            feature_tags.append("Textured lower-jaw / facial hair contour")
    except Exception:
        pass

    attire = attire_hint.strip() if attire_hint.strip() else f"{upper_name} ({pattern}) · Lower {lower_name}"
    build = build_hint.strip() if build_hint.strip() else "Balanced Proportion"
    features = " · ".join(feature_tags) if feature_tags else "Clear frontal + oblique facial geometry"
    summary = f"{attire} · {features}"
    return {
        "attire": attire,
        "features": features,
        "build": build,
        "lighting": lighting,
        "upper_hex": upper_hex,
        "lower_hex": lower_hex,
        "summary": summary,
        "source": "deterministic_cv",
    }


async def analyze_person_image(
    image_b64: str,
    attire_hint: str = "",
    build_hint: str = "",
    transport: httpx.AsyncBaseTransport | None = None,
) -> dict[str, Any]:
    """Analyzes a person crop or 4-Angle Character Board image and returns structured tags."""
    clean_b64 = (image_b64 or "").strip()
    if clean_b64.startswith("data:"):
        _, _, clean_b64 = clean_b64.partition(",")
    try:
        raw = base64.b64decode(clean_b64, validate=False) if clean_b64 else b""
    except Exception:
        raw = b""

    base = _analyze_pixels_deterministic(raw, attire_hint=attire_hint, build_hint=build_hint)
    if not raw or transport is not None:
        return base

    # Optional fast Gemini Flash / local vision enrichment (strictly bounded at 3.2s)
    api_key = os.environ.get("GEMINI_API_KEY", "").strip()
    if api_key:
        try:
            url = "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions"
            prompt = (
                "Analyze this person's appearance for a wearable smart-glasses Character Board. "
                "Return ONLY compact JSON with keys: "
                '{"attire": "short clothing description (colors + garment type)", '
                '"features": "distinguishing non-biometric visual features (e.g. glasses, beard, hat, hairstyle)", '
                '"build": "posture/framing summary"}. Keep each field under 65 chars.'
            )
            payload = {
                "model": "gemini-2.5-flash",
                "messages": [
                    {
                        "role": "user",
                        "content": [
                            {"type": "text", "text": prompt},
                            {"type": "image_url", "image_url": {"url": f"data:image/jpeg;base64,{clean_b64}"}},
                        ],
                    }
                ],
                "max_tokens": 120,
                "temperature": 0.1,
            }
            async with httpx.AsyncClient(timeout=3.2) as client:
                resp = await client.post(url, json=payload, headers={"Authorization": f"Bearer {api_key}"})
                if resp.status_code == 200:
                    content = resp.json()["choices"][0]["message"]["content"].strip()
                    if "```" in content:
                        content = content.split("```")[1].removeprefix("json").strip()
                    parsed = json.loads(content)
                    if isinstance(parsed, dict):
                        if parsed.get("attire"):
                            base["attire"] = str(parsed["attire"])[:90]
                        if parsed.get("features"):
                            base["features"] = str(parsed["features"])[:100]
                        if parsed.get("build"):
                            base["build"] = str(parsed["build"])[:60]
                        base["summary"] = f"{base['attire']} · {base['features']}"
                        base["source"] = "gemini_flash_cv"
        except Exception:
            pass

    return base


def build_model_assist_digest(
    agent_name: str,
    people_store=None,
    session=None,
    mission_store=None,
) -> dict[str, Any]:
    """Builds a pre-digested situational context snapshot for any cloud or local model."""
    now = time.time()
    saved_people: list[dict[str, Any]] = []
    prompt_people_lines: list[str] = []
    if people_store is not None:
        try:
            for item in people_store.listing()[:12]:
                saved_people.append({
                    "id": item.get("id"),
                    "name": item.get("name"),
                    "relation": item.get("relation", ""),
                    "note": item.get("note", ""),
                    "sightings": item.get("sightings", 1),
                    "has_face": item.get("has_face", False),
                    "has_voice": item.get("has_voice", False),
                })
            prompt_people_lines = people_store.prompt_lines(limit=12)
        except Exception:
            pass

    in_view: list[str] = []
    detections: dict[str, Any] = {}
    camera_source = "phone"
    heading = None
    active_mission = None

    if session is not None:
        camera_source = getattr(session, "eyes_source", "phone") or "phone"
        heading = getattr(session, "heading", None)
        if getattr(session, "in_view", None) and (now - getattr(session, "in_view_at", 0) < 30):
            in_view = list(session.in_view)
        if getattr(session, "detections", None) and (now - getattr(session, "detections_at", 0) < 30):
            detections = dict(session.detections)
        try:
            active_mission = session.mission_state()
        except Exception:
            active_mission = None

    if active_mission is None and mission_store is not None:
        try:
            latest_m = mission_store.latest()
            if latest_m is not None and not latest_m.complete:
                active_mission = latest_m.public()
        except Exception:
            pass

    lines = [
        "[SPOTTER LOCAL RELAY DIGEST — PRE-PROCESSED CONTEXT FOR ALL MODELS]",
        f"• Active Camera Source: {'Ray-Ban Meta Smart Glasses (First-Person POV)' if camera_source == 'glasses' else 'Phone Camera'}.",
        "• Language & Audio Lock: English (en-US) ONLY. Wearer-only voice gate active (ignore background bystanders and phantom 1-letter noise).",
    ]
    if heading is not None:
        lines.append(f"• Wearer Compass Heading: {heading:03d}°.")
    if in_view:
        lines.append(f"• Recognized Saved People Currently in Frame: {', '.join(in_view)} (Matched via On-Device 4-Angle Face+Body Character Board).")
    if detections:
        det_parts = []
        if detections.get("faces"):
            det_parts.append(f"{detections['faces']} face(s)")
        if detections.get("pose"):
            det_parts.append("person body/torso locked")
        if detections.get("hands"):
            g = ", ".join(detections.get("gestures") or [])
            det_parts.append(f"{detections['hands']} hand(s)" + (f" [{g}]" if g else ""))
        if detections.get("objects"):
            det_parts.append("objects: " + ", ".join(detections["objects"]))
        if det_parts:
            lines.append("• On-Device MediaPipe Detections: " + "; ".join(det_parts) + ".")
    if prompt_people_lines:
        lines.append("• Saved People Character Boards: " + " | ".join(prompt_people_lines))
    else:
        lines.append("• Saved People Character Boards: None saved yet (wearer can tap Save Person to capture a 4-Angle Character Board).")
    if active_mission:
        goal = active_mission.get("goal", "")
        step = active_mission.get("step") or active_mission.get("current_step_text") or ""
        cur = active_mission.get("current", 1)
        tot = active_mission.get("total", 1)
        lines.append(f"• Active Mission ({cur}/{tot}): {goal} -> Current Step: {step}")

    return {
        "agent": agent_name,
        "timestamp": now,
        "camera_source": camera_source,
        "heading": heading,
        "in_view": in_view,
        "detections": detections,
        "saved_people_count": len(saved_people),
        "saved_people": saved_people,
        "active_mission": active_mission,
        "prompt_digest": "\n".join(lines),
    }
