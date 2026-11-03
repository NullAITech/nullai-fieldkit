#!/usr/bin/env python3
"""
Spotter (NullAI FieldKit) — Automated Hackathon Video Producer
Generates:
  1. /home/zoth/Desktop/spotter-3min-master-demo.mp4 (~3:00 Master Hackathon Submission Video)
  2. /home/zoth/Desktop/spotter-60s-viral-cut.mp4    (~0:56 X/Twitter Viral Reel for $20K Bonus)
Also mirrors both files into /home/zoth/Desktop/Zoth-Meta-Hackathon/videos/.
"""

import os
import sys
import math
import wave
import struct
import shutil
import subprocess
from pathlib import Path
import numpy as np
from PIL import Image, ImageDraw, ImageFont, ImageFilter

EDGE_TTS = "/opt/zothos-ai-env/bin/edge-tts"
FFMPEG = "/usr/bin/ffmpeg"
FFPROBE = "/usr/bin/ffprobe"

W, H = 1920, 1080
FPS = 30
SR = 24000

VOICE_BUILDER = "en-US-ChristopherNeural"
VOICE_SPOTTER = "en-US-AriaNeural"

FONT_BOLD_PATH = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
FONT_REG_PATH = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"
FONT_MONO_PATH = "/usr/share/fonts/truetype/dejavu/DejaVuSansMono-Bold.ttf"

WORK_DIR = Path("/tmp/spotter_video_build")
OUT_DESKTOP = Path("/home/zoth/Desktop")
OUT_REPO_VIDEOS = Path("/home/zoth/Desktop/Zoth-Meta-Hackathon/videos")


def get_font(path, size):
    try:
        return ImageFont.truetype(path, size)
    except Exception:
        return ImageFont.load_default()


FONT_TAG = get_font(FONT_MONO_PATH, 16)
FONT_CHIP = get_font(FONT_MONO_PATH, 18)
FONT_TITLE = get_font(FONT_BOLD_PATH, 38)
FONT_SUB = get_font(FONT_REG_PATH, 22)
FONT_CC_SPEAKER = get_font(FONT_MONO_PATH, 17)
FONT_CC_TEXT = get_font(FONT_BOLD_PATH, 25)
FONT_CC_TEXT_SM = get_font(FONT_BOLD_PATH, 22)
FONT_HUD = get_font(FONT_MONO_PATH, 15)
FONT_CARD_H = get_font(FONT_BOLD_PATH, 22)
FONT_CARD_B = get_font(FONT_REG_PATH, 18)


def synth_line(line_id: str, speaker: str, text: str, rate: str = "+4%") -> tuple[Path, np.ndarray, float]:
    """Synthesize a line via edge-tts, convert to 24kHz mono 16-bit PCM WAV, and return samples + duration."""
    mp3_path = WORK_DIR / f"{line_id}.mp3"
    wav_path = WORK_DIR / f"{line_id}.wav"
    voice = VOICE_SPOTTER if speaker == "SPOTTER AI" else VOICE_BUILDER

    if not wav_path.exists():
        cmd_tts = [
            EDGE_TTS,
            "--voice", voice,
            f"--rate={rate}",
            "--text", text,
            "--write-media", str(mp3_path),
        ]
        subprocess.run(cmd_tts, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        cmd_wav = [
            FFMPEG, "-y", "-i", str(mp3_path),
            "-ac", "1", "-ar", str(SR), "-sample_fmt", "s16",
            str(wav_path),
        ]
        subprocess.run(cmd_wav, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

    with wave.open(str(wav_path), "rb") as wf:
        n_frames = wf.getnframes()
        raw = wf.readframes(n_frames)
        samples = np.frombuffer(raw, dtype=np.int16).astype(np.float32) / 32768.0

    # Add 0.14s tail padding for natural cadence while keeping master video under 3:00
    pad = np.zeros(int(0.14 * SR), dtype=np.float32)
    samples = np.concatenate([samples, pad])
    duration = len(samples) / SR
    return wav_path, samples, duration


def make_chime(freq1=528.0, freq2=792.0, dur=0.22) -> np.ndarray:
    t = np.linspace(0, dur, int(SR * dur), endpoint=False, dtype=np.float32)
    env = np.exp(-t * 14.0) * np.sin(np.pi * np.minimum(t / 0.015, 1.0))
    sig = (0.55 * np.sin(2 * np.pi * freq1 * t) + 0.45 * np.sin(2 * np.pi * freq2 * t)) * env * 0.12
    return sig.astype(np.float32)


def build_ambient_bed(total_samples: int) -> np.ndarray:
    """Subtle warm cinematic drone pad + gentle pulse under narration."""
    t = np.arange(total_samples, dtype=np.float32) / SR
    pad = (
        0.35 * np.sin(2 * np.pi * 110.0 * t)
        + 0.25 * np.sin(2 * np.pi * 164.81 * t)
        + 0.20 * np.sin(2 * np.pi * 220.0 * t + 0.4 * np.sin(2 * np.pi * 0.25 * t))
        + 0.12 * np.sin(2 * np.pi * 329.63 * t)
    )
    lfo = 0.65 + 0.35 * np.sin(2 * np.pi * 0.12 * t)
    bed = pad * lfo * 0.028
    # Fade in 1s, fade out 1.5s
    fi = min(int(1.0 * SR), total_samples)
    fo = min(int(1.5 * SR), total_samples)
    bed[:fi] *= np.linspace(0, 1, fi, dtype=np.float32)
    bed[-fo:] *= np.linspace(1, 0, fo, dtype=np.float32)
    return bed


def fit_cover(img: Image.Image, target_w: int, target_h: int) -> Image.Image:
    iw, ih = img.size
    scale = max(target_w / iw, target_h / ih)
    nw, nh = int(iw * scale), int(ih * scale)
    resized = img.resize((nw, nh), Image.Resampling.LANCZOS)
    left = (nw - target_w) // 2
    top = (nh - target_h) // 2
    return resized.crop((left, top, left + target_w, top + target_h))


def fit_contain(img: Image.Image, max_w: int, max_h: int) -> Image.Image:
    iw, ih = img.size
    scale = min(max_w / iw, max_h / ih)
    nw, nh = max(1, int(iw * scale)), max(1, int(ih * scale))
    return img.resize((nw, nh), Image.Resampling.LANCZOS)


def wrap_text(draw: ImageDraw.ImageDraw, text: str, font, max_width: int) -> list[str]:
    words = text.split()
    lines = []
    cur = ""
    for w in words:
        test = (cur + " " + w).strip()
        bbox = draw.textbbox((0, 0), test, font=font)
        if bbox[2] - bbox[0] <= max_width or not cur:
            cur = test
        else:
            lines.append(cur)
            cur = w
    if cur:
        lines.append(cur)
    return lines


def load_assets() -> dict[str, Image.Image]:
    paths = {
        "slide0": "/tmp/slides_shots/s0/slides_html_slide_0_desktop.jpg",
        "slide1": "/tmp/slides_shots/s1/slides_html_slide_1_desktop.jpg",
        "slide2": "/tmp/slides_shots/s2/slides_html_slide_2_desktop.jpg",
        "slide3": "/tmp/slides_shots/s3/slides_html_slide_3_desktop.jpg",
        "pwa": "/tmp/slides_shots/pwa/pwa_desktop.jpg",
        "presence_grid": "/tmp/presence_shots4/index_html_desktop.jpg",
        "presence_tablet": "/tmp/presence_shots4/index_html_tablet.jpg",
        "phone_main": "/tmp/after_main.png",
        "phone_call": "/tmp/phone_face_3d.png",
        "phone_chats": "/tmp/after_chats.png",
        "phone_people": "/tmp/after_people_card.png",
        "phone_dev": "/tmp/after_people_dev.png",
    }
    assets = {}
    for k, p in paths.items():
        if os.path.exists(p):
            assets[k] = Image.open(p).convert("RGB")
        else:
            raise FileNotFoundError(f"Missing required visual asset: {p}")
    return assets


def compose_stage_base(scene: dict, assets: dict[str, Image.Image]) -> Image.Image:
    """Create the high-res 1920x1080 stage plate for a scene before per-frame dynamic overlays."""
    base = Image.new("RGB", (W, H), (8, 12, 20))
    draw = ImageDraw.Draw(base, "RGBA")

    layout = scene["layout"]
    if layout == "slide_full":
        # Full-bleed slide with subtle top/bottom framing so captions & top HUD never collide
        slide_img = assets[scene["bg_key"]]
        fitted = fit_contain(slide_img, 1840, 860)
        sx = (W - fitted.width) // 2
        sy = 78 + (860 - fitted.height) // 2
        # Subtle glow border
        draw.rounded_rectangle(
            [sx - 4, sy - 4, sx + fitted.width + 4, sy + fitted.height + 4],
            radius=18,
            outline=(56, 189, 248, 140),
            width=2,
        )
        base.paste(fitted, (sx, sy))

    elif layout == "split_phone_slide":
        # Left: Wide stage card (slide or PWA or Presence Grid), Right: Pixel 10 Pro hardware frame
        left_img = assets[scene["left_key"]]
        right_img = assets[scene["right_key"]]

        # Soft blurred backdrop
        bg_blur = fit_cover(left_img, W, H).filter(ImageFilter.GaussianBlur(radius=28))
        dark_overlay = Image.new("RGBA", (W, H), (7, 10, 18, 215))
        base = Image.alpha_composite(bg_blur.convert("RGBA"), dark_overlay).convert("RGB")
        draw = ImageDraw.Draw(base, "RGBA")

        # Left stage panel (x=54..1310, y=84..814)
        lf = fit_contain(left_img, 1230, 720)
        lx = 58 + (1230 - lf.width) // 2
        ly = 88 + (720 - lf.height) // 2
        draw.rounded_rectangle(
            [lx - 6, ly - 6, lx + lf.width + 6, ly + lf.height + 6],
            radius=18,
            fill=(13, 19, 33, 245),
            outline=(56, 189, 248, 130),
            width=2,
        )
        base.paste(lf, (lx, ly))

        # Right Pixel 10 Pro phone mockup (x=1340..1862, y=92..910)
        rf = fit_contain(right_img, 460, 776)
        rx = 1365 + (495 - rf.width) // 2
        ry = 98 + (776 - rf.height) // 2
        # Phone bezel
        draw.rounded_rectangle(
            [rx - 14, ry - 26, rx + rf.width + 14, ry + rf.height + 20],
            radius=32,
            fill=(15, 23, 42, 255),
            outline=(52, 211, 153, 210),
            width=3,
        )
        # Phone top speaker pill
        draw.rounded_rectangle(
            [rx + rf.width // 2 - 36, ry - 15, rx + rf.width // 2 + 36, ry - 9],
            radius=3,
            fill=(100, 116, 139, 220),
        )
        base.paste(rf, (rx, ry))

        # Hardware badge on phone
        badge_txt = scene.get("phone_badge", "PIXEL 10 PRO · ADB LIVE")
        draw.rounded_rectangle(
            [rx + 12, ry + 12, rx + 290, ry + 44],
            radius=8,
            fill=(9, 13, 22, 230),
            outline=(52, 211, 153, 180),
            width=1,
        )
        draw.text((rx + 24, ry + 19), badge_txt, font=FONT_HUD, fill=(110, 231, 183))

    elif layout == "dual_phone_stage":
        # Left: Wide visual plate + 2 Pixel 10 Pro screens side-by-side on the right
        left_img = assets[scene["left_key"]]
        p1_img = assets[scene["phone1_key"]]
        p2_img = assets[scene["phone2_key"]]

        bg_blur = fit_cover(left_img, W, H).filter(ImageFilter.GaussianBlur(radius=28))
        dark_overlay = Image.new("RGBA", (W, H), (7, 10, 18, 220))
        base = Image.alpha_composite(bg_blur.convert("RGBA"), dark_overlay).convert("RGB")
        draw = ImageDraw.Draw(base, "RGBA")

        # Left visual plate
        lf = fit_contain(left_img, 1020, 716)
        lx = 52 + (1020 - lf.width) // 2
        ly = 88 + (716 - lf.height) // 2
        draw.rounded_rectangle(
            [lx - 6, ly - 6, lx + lf.width + 6, ly + lf.height + 6],
            radius=18,
            fill=(13, 19, 33, 245),
            outline=(167, 139, 250, 150),
            width=2,
        )
        base.paste(lf, (lx, ly))

        # Two Pixel 10 Pro screens
        for idx, (p_img, lbl) in enumerate([
            (p1_img, scene.get("phone1_label", "PIXEL 10 PRO #1")),
            (p2_img, scene.get("phone2_label", "PIXEL 10 PRO #2")),
        ]):
            pf = fit_contain(p_img, 360, 756)
            px = 1115 + idx * 395 + (370 - pf.width) // 2
            py = 104 + (756 - pf.height) // 2
            accent = (56, 189, 248, 210) if idx == 0 else (52, 211, 153, 210)
            draw.rounded_rectangle(
                [px - 10, py - 22, px + pf.width + 10, py + pf.height + 16],
                radius=26,
                fill=(15, 23, 42, 255),
                outline=accent,
                width=3,
            )
            base.paste(pf, (px, py))
            draw.rounded_rectangle(
                [px + 10, py + 10, px + pf.width - 10, py + 40],
                radius=7,
                fill=(9, 13, 22, 230),
                outline=accent,
                width=1,
            )
            draw.text((px + 20, py + 16), lbl, font=FONT_HUD, fill=(226, 232, 240))

    # Draw callout pills if specified
    callouts = scene.get("callouts", [])
    if callouts:
        cx = 56
        cy = 838
        for c_txt, c_col in callouts:
            bbox = draw.textbbox((0, 0), c_txt, font=FONT_HUD)
            cw = (bbox[2] - bbox[0]) + 28
            draw.rounded_rectangle(
                [cx, cy, cx + cw, cy + 34],
                radius=9,
                fill=(13, 19, 33, 235),
                outline=c_col + (210,),
                width=2,
            )
            draw.text((cx + 14, cy + 8), c_txt, font=FONT_HUD, fill=c_col)
            cx += cw + 14

    return base


def render_frame(
    stage_base: Image.Image,
    scene: dict,
    scene_idx: int,
    total_scenes: int,
    global_t: float,
    total_dur: float,
    active_speaker: str,
    active_caption: str,
    rms_val: float,
    video_title_badge: str,
) -> np.ndarray:
    """Render a single 1920x1080 RGB frame with top HUD, animated voice visualizer, and burned-in captions."""
    frame = stage_base.copy()
    draw = ImageDraw.Draw(frame, "RGBA")

    # 1. Top Navigation & Progress HUD Bar (y=0..68)
    draw.rectangle([0, 0, W, 68], fill=(9, 13, 22, 245))
    draw.line([(0, 68), (W, 68)], fill=(30, 41, 59, 255), width=2)

    # Progress fill line at y=67
    prog_w = int(W * min(1.0, max(0.0, global_t / max(0.1, total_dur))))
    draw.rectangle([0, 65, prog_w, 68], fill=(52, 211, 153, 255))

    # Left brand badge
    draw.rounded_rectangle([24, 14, 340, 54], radius=10, fill=(17, 24, 39, 255), outline=(52, 211, 153, 180), width=1)
    draw.ellipse([38, 27, 50, 39], fill=(52, 211, 153, 255))
    draw.text((60, 24), video_title_badge, font=FONT_TAG, fill=(110, 231, 183))

    # Scene title in top center-left
    scene_header = f"0{scene_idx + 1}/0{total_scenes} · {scene['title']}"
    draw.text((362, 22), scene_header, font=FONT_CHIP, fill=(248, 250, 252))

    # Right live telemetry badge + clock
    mm = int(global_t) // 60
    ss = int(global_t) % 60
    tot_m = int(total_dur) // 60
    tot_s = int(total_dur) % 60
    time_str = f"RB META 01QV · STREAMING   {mm:02d}:{ss:02d} / {tot_m:02d}:{tot_s:02d}"
    draw.rounded_rectangle([1470, 14, 1896, 54], radius=10, fill=(17, 24, 39, 255), outline=(56, 189, 248, 160), width=1)
    # Pulsing recording dot
    pulse = int(180 + 75 * math.sin(global_t * 6.0))
    draw.ellipse([1486, 28, 1498, 40], fill=(244, 63, 94, pulse))
    draw.text((1508, 24), time_str, font=FONT_HUD, fill=(226, 232, 240))

    # 2. Bottom Closed-Caption & Live Voice Waveform Dock (y=918..1064)
    draw.rounded_rectangle(
        [44, 916, W - 44, 1064],
        radius=18,
        fill=(9, 13, 22, 242),
        outline=(52, 211, 153, 190) if active_speaker == "SPOTTER AI" else (56, 189, 248, 190),
        width=2,
    )

    # Speaker Badge + Audio-Reactive 18-Bar Equalizer on Left of Caption Bar (x=64..370)
    is_spotter = active_speaker == "SPOTTER AI"
    spk_col = (52, 211, 153) if is_spotter else (56, 189, 248)
    spk_bg = (6, 46, 36, 235) if is_spotter else (12, 34, 58, 235)
    draw.rounded_rectangle([64, 932, 368, 974], radius=9, fill=spk_bg, outline=spk_col + (200,), width=1)
    spk_label = "◆ SPOTTER AI · MUSE SPARK" if is_spotter else "▸ NEAL FRAZIER · BUILDER"
    draw.text((78, 943), spk_label, font=FONT_CC_SPEAKER, fill=spk_col)

    # 18 animated audio-reactive bars (x=68..360, y=986..1046)
    num_bars = 18
    bar_w = 10
    gap = 6
    base_x = 68
    mid_y = 1018
    for b in range(num_bars):
        wave_mod = 0.45 + 0.55 * abs(math.sin(global_t * 11.0 + b * 0.65))
        bh = int(max(4, min(26, rms_val * 145.0 * wave_mod)))
        bx = base_x + b * (bar_w + gap)
        draw.rounded_rectangle(
            [bx, mid_y - bh, bx + bar_w, mid_y + bh],
            radius=3,
            fill=spk_col + (235,),
        )

    # Vertical divider
    draw.line([(388, 932), (388, 1048)], fill=(51, 65, 85, 200), width=2)

    # Wrapped Closed Caption text (x=412..1850) — supports 1, 2, or 3 lines cleanly
    cc_lines = wrap_text(draw, active_caption, FONT_CC_TEXT, 1425)
    if len(cc_lines) == 1:
        draw.text((412, 975), cc_lines[0], font=FONT_CC_TEXT, fill=(248, 250, 252))
    elif len(cc_lines) == 2:
        for li, ln in enumerate(cc_lines):
            draw.text((412, 952 + li * 40), ln, font=FONT_CC_TEXT, fill=(248, 250, 252))
    else:
        cc_lines_sm = wrap_text(draw, active_caption, FONT_CC_TEXT_SM, 1425)
        for li, ln in enumerate(cc_lines_sm[:3]):
            draw.text((412, 936 + li * 34), ln, font=FONT_CC_TEXT_SM, fill=(248, 250, 252))

    return np.asarray(frame, dtype=np.uint8)


def produce_video(video_name: str, video_title_badge: str, scenes_spec: list[dict], assets: dict[str, Image.Image]) -> Path:
    print(f"\n=== Building {video_name} ===")
    all_audio_chunks = []
    prepared_scenes = []

    for s_idx, sc in enumerate(scenes_spec):
        sc_lines = []
        sc_audio = []
        # Add subtle transition chime at scene start
        chime = make_chime(528.0 + s_idx * 44.0, 792.0 + s_idx * 33.0, dur=0.20)

        for l_idx, (speaker, text, rate) in enumerate(sc["lines"]):
            lid = f"{video_name}_s{s_idx}_l{l_idx}"
            _, samples, dur = synth_line(lid, speaker, text, rate=rate)
            if l_idx == 0 and len(samples) > len(chime):
                samples = samples.copy()
                samples[: len(chime)] += chime
            sc_lines.append({
                "speaker": speaker,
                "text": text,
                "samples": samples,
                "duration": dur,
            })
            sc_audio.append(samples)

        scene_samples = np.concatenate(sc_audio)
        scene_dur = len(scene_samples) / SR
        n_frames = max(1, int(round(scene_dur * FPS)))
        # Adjust audio sample count to match exact frame count
        target_samples = int(round((n_frames / FPS) * SR))
        if len(scene_samples) < target_samples:
            scene_samples = np.pad(scene_samples, (0, target_samples - len(scene_samples)))
        else:
            scene_samples = scene_samples[:target_samples]

        all_audio_chunks.append(scene_samples)
        stage_base = compose_stage_base(sc, assets)
        prepared_scenes.append({
            "spec": sc,
            "lines": sc_lines,
            "samples": scene_samples,
            "n_frames": n_frames,
            "duration": n_frames / FPS,
            "stage_base": stage_base,
        })
        print(f"  Scene {s_idx+1}: {sc['title']} -> {n_frames/FPS:.2f}s ({n_frames} frames)")

    full_voice = np.concatenate(all_audio_chunks)
    ambient = build_ambient_bed(len(full_voice))
    mixed = np.clip(full_voice * 0.92 + ambient, -0.98, 0.98)

    wav_master = WORK_DIR / f"{video_name}_master.wav"
    with wave.open(str(wav_master), "wb") as wf:
        wf.setnchannels(2)
        wf.setsampwidth(2)
        wf.setframerate(SR)
        pcm16 = (mixed * 32767.0).astype(np.int16)
        stereo = np.column_stack([pcm16, pcm16]).ravel()
        wf.writeframes(stereo.tobytes())

    total_frames = sum(ps["n_frames"] for ps in prepared_scenes)
    total_dur = total_frames / FPS
    print(f"  Total Duration: {total_dur:.2f}s ({total_frames} frames @ {FPS}fps)")

    out_mp4 = OUT_DESKTOP / f"{video_name}.mp4"
    cmd_ff = [
        FFMPEG, "-y",
        "-f", "rawvideo",
        "-vcodec", "rawvideo",
        "-pix_fmt", "rgb24",
        "-s", f"{W}x{H}",
        "-r", str(FPS),
        "-i", "-",
        "-i", str(wav_master),
        "-c:v", "libx264",
        "-preset", "fast",
        "-crf", "18",
        "-pix_fmt", "yuv420p",
        "-c:a", "aac",
        "-b:a", "192k",
        "-movflags", "+faststart",
        "-shortest",
        str(out_mp4),
    ]

    proc = subprocess.Popen(cmd_ff, stdin=subprocess.PIPE, stderr=subprocess.DEVNULL)

    global_frame = 0
    for s_idx, ps in enumerate(prepared_scenes):
        sc = ps["spec"]
        stage_base = ps["stage_base"]
        next_base = prepared_scenes[s_idx + 1]["stage_base"] if s_idx + 1 < len(prepared_scenes) else None

        # Precompute line time boundaries within this scene
        line_bounds = []
        t_acc = 0.0
        for ln in ps["lines"]:
            line_bounds.append((t_acc, t_acc + ln["duration"], ln["speaker"], ln["text"]))
            t_acc += ln["duration"]

        for f_idx in range(ps["n_frames"]):
            local_t = f_idx / FPS
            global_t = global_frame / FPS

            # Find active speaker & caption
            active_spk = line_bounds[-1][2]
            active_txt = line_bounds[-1][3]
            for t0, t1, spk, txt in line_bounds:
                if t0 <= local_t <= t1:
                    active_spk = spk
                    active_txt = txt
                    break

            # Compute RMS audio energy for this frame window
            s0 = int(local_t * SR)
            s1 = min(len(ps["samples"]), s0 + int(SR / FPS))
            if s1 > s0:
                rms_val = float(np.sqrt(np.mean(ps["samples"][s0:s1] ** 2)))
            else:
                rms_val = 0.0

            # Smooth 8-frame crossfade into next scene at the very end of the scene
            cur_base = stage_base
            fade_frames = 8
            if next_base is not None and f_idx >= ps["n_frames"] - fade_frames:
                alpha = (f_idx - (ps["n_frames"] - fade_frames)) / float(fade_frames)
                cur_base = Image.blend(stage_base, next_base, alpha)

            rgb_arr = render_frame(
                stage_base=cur_base,
                scene=sc,
                scene_idx=s_idx,
                total_scenes=len(prepared_scenes),
                global_t=global_t,
                total_dur=total_dur,
                active_speaker=active_spk,
                active_caption=active_txt,
                rms_val=rms_val,
                video_title_badge=video_title_badge,
            )
            proc.stdin.write(rgb_arr.tobytes())
            global_frame += 1

    proc.stdin.close()
    proc.wait()
    if proc.returncode != 0:
        raise RuntimeError(f"ffmpeg exited with {proc.returncode}")

    # Mirror into repo videos folder
    OUT_REPO_VIDEOS.mkdir(parents=True, exist_ok=True)
    repo_copy = OUT_REPO_VIDEOS / f"{video_name}.mp4"
    shutil.copy2(out_mp4, repo_copy)
    print(f"  Saved: {out_mp4} ({out_mp4.stat().st_size / (1024*1024):.2f} MB)")
    print(f"  Mirrored: {repo_copy}")
    return out_mp4


def main():
    WORK_DIR.mkdir(parents=True, exist_ok=True)
    assets = load_assets()

    # =========================================================================
    # VIDEO 1: 3-Minute Master Hackathon Submission Video (~2:55 - 3:02)
    # =========================================================================
    master_scenes = [
        {
            "title": "THE HOOK · PROACTIVE AMBIENT CO-PILOT ON RAY-BAN META",
            "layout": "split_phone_slide",
            "left_key": "slide0",
            "right_key": "phone_main",
            "phone_badge": "PIXEL 10 PRO · RB META 01QV",
            "callouts": [
                ("META WEARABLES DAT SDK v0.5.0", (52, 211, 153)),
                ("LIVE H.264 RFCOMM + BLE POV STREAM", (56, 189, 248)),
                ("CC: ON + ● REC DEMO BUILT IN", (251, 191, 36)),
            ],
            "lines": [
                (
                    "NEAL FRAZIER",
                    "Most smart-glasses assistants are reactive chatbots. You tap the temple, ask what you are looking at, and wait four seconds while your hands are covered in thermal paste.",
                    "+13%",
                ),
                (
                    "NEAL FRAZIER",
                    "This is Spotter—an ambient, proactive AI co-pilot for Ray-Ban Meta Smart Glasses that watches your live POV stream, plans multi-step tasks, and whispers only when it sees something critical.",
                    "+13%",
                ),
                (
                    "SPOTTER AI",
                    "Ray-Ban Meta zero-one-Q-V linked. Streaming live POV video. Closed captions enabled.",
                    "+10%",
                ),
            ],
        },
        {
            "title": "MUSE WONDER PLANNER + MUSE SPARK PROACTIVE VISION COACH",
            "layout": "split_phone_slide",
            "left_key": "pwa",
            "right_key": "phone_main",
            "phone_badge": "MISSION CHECKLIST · LIVE",
            "callouts": [
                ("MUSE WONDER -> 5-STEP CHECKLIST", (167, 139, 250)),
                ("MUSE SPARK -> VISION + AUDIO LOOP", (52, 211, 153)),
                ("ZERO-SHOT PROACTIVE SAFETY INTERVENTION", (244, 63, 94)),
            ],
            "lines": [
                (
                    "NEAL FRAZIER",
                    "Before a session starts, Muse Wonder decomposes our goal—like inspecting a workbench before leaving the studio—into a structured five-step checklist.",
                    "+13%",
                ),
                (
                    "NEAL FRAZIER",
                    "As I walk the bench wearing Ray-Ban Meta glasses, Muse Spark evaluates keyframes and voice in real time without waiting for a wake word.",
                    "+13%",
                ),
                (
                    "SPOTTER AI",
                    "Heads up, Neal. Your soldering station is still holding at three-eighty Celsius on the left mat, and the flux pen cap is off.",
                    "+10%",
                ),
                (
                    "NEAL FRAZIER",
                    "Check off the soldering iron and log the bench state.",
                    "+13%",
                ),
                (
                    "SPOTTER AI",
                    "Step one verified and checked off. Moving to step two: verify power supply rails and component tray.",
                    "+10%",
                ),
            ],
        },
        {
            "title": "2D/3D ANATOMICAL BRAILLE PRESENCE + EYE SWITCH + CHARACTER BOARD",
            "layout": "dual_phone_stage",
            "left_key": "presence_grid",
            "phone1_key": "phone_call",
            "phone1_label": "LIVE CALL · EYE CAMERA SWITCH",
            "phone2_key": "phone_people",
            "phone2_label": "4-ANGLE CHARACTER BOARD",
            "callouts": [
                ("CHIAROSCURO 2D/3D BRAILLE HUMAN FACE", (52, 211, 153)),
                ("1-TAP GLASSES POV <-> PHONE CAM", (56, 189, 248)),
                ("CONSENT-FIRST 4-ANGLE DOSSIER", (251, 191, 36)),
            ],
            "lines": [
                (
                    "NEAL FRAZIER",
                    "Tap Call Spotter to enter a full-duplex session with our procedural 2D and 3D anatomical human presence, rendered at sixty frames per second in sub-pixel Unicode Braille and Bayer dither.",
                    "+13%",
                ),
                (
                    "NEAL FRAZIER",
                    "The floating Eye Switch hot-swaps live video between the Ray-Ban Meta glasses POV, the Pixel front camera, and the rear lens in one tap.",
                    "+13%",
                ),
                (
                    "NEAL FRAZIER",
                    "And with explicit verbal consent, Spotter builds a four-angle Character Board—capturing Front Face, Left and Right Profiles, and Full Body posture alongside an instant encounter dossier.",
                    "+13%",
                ),
                (
                    "SPOTTER AI",
                    "Recognized Alex Rivera. Last seen eighteen minutes ago in the Hardware Lab. Topic: Ray-Ban Meta RFCOMM stream debugging.",
                    "+10%",
                ),
            ],
        },
        {
            "title": "CONVERSATIONS HUB · HERMES JOT · OBSIDIAN + MCP + SPARK FAILOVER",
            "layout": "dual_phone_stage",
            "left_key": "slide1",
            "phone1_key": "phone_chats",
            "phone1_label": "CONVERSATIONS HUB",
            "phone2_key": "phone_dev",
            "phone2_label": "BIOMETRIC VAULT + DEV DRAWER",
            "callouts": [
                ("HERMES JOT -> STRUCTURED DIGEST", (167, 139, 250)),
                ("OBSIDIAN VAULT + MCP TOOLS", (56, 189, 248)),
                ("MUSE SPARK OPEN LOCAL FAILOVER", (52, 211, 153)),
            ],
            "lines": [
                (
                    "NEAL FRAZIER",
                    "Every Mission and Muse Call is indexed in the Conversations Hub with full transcripts, checklists, and one-tap resume.",
                    "+13%",
                ),
                (
                    "NEAL FRAZIER",
                    "Hermes Jot compresses completed runs into structured summaries, syncing directly to your Obsidian Second Brain and Model Context Protocol endpoints.",
                    "+13%",
                ),
                (
                    "NEAL FRAZIER",
                    "And if Wi-Fi drops in the field, the circuit breaker fails over automatically to Muse Spark Open running locally so the coaching loop never dies.",
                    "+13%",
                ),
            ],
        },
        {
            "title": "LONG-SESSION STABILITY PROOF · 60-TURN FLAT LATENCY (1.27s MEDIAN)",
            "layout": "slide_full",
            "bg_key": "slide3",
            "callouts": [],
            "lines": [
                (
                    "NEAL FRAZIER",
                    "Most vision agents bog down after ten turns as raw conversation history balloons. Spotter uses rolling session memory compaction.",
                    "+13%",
                ),
                (
                    "NEAL FRAZIER",
                    "Across our sixty-turn benchmark, median round-trip latency stays rock-solid at one point two seven seconds—with turn sixty finishing in one point zero six seconds.",
                    "+13%",
                ),
            ],
        },
        {
            "title": "META AI STACK & PRIOR WORK VS. HACKATHON HONESTY DISCLOSURE",
            "layout": "slide_full",
            "bg_key": "slide2",
            "callouts": [],
            "lines": [
                (
                    "NEAL FRAZIER",
                    "For full judge transparency, this slide documents our exact boundary between pre-existing Zoth Relay scaffolding and what we built from scratch during the hackathon window.",
                    "+13%",
                ),
                (
                    "NEAL FRAZIER",
                    "All Muse Spark, Muse Wonder, Muse Spark Open failover, the 2D and 3D anatomical Braille face, the four-angle Character Board, and our native Ray-Ban Meta hardware fixes were built live for this hackathon.",
                    "+13%",
                ),
                (
                    "SPOTTER AI",
                    "Judges without glasses can test the full Muse loop right now in any browser at slash P-W-A. Thank you for watching Spotter.",
                    "+10%",
                ),
            ],
        },
    ]

    # =========================================================================
    # VIDEO 2: 60-Second Viral X / Twitter Cut (~52 - 58s)
    # =========================================================================
    viral_scenes = [
        {
            "title": "STOP TALKING TO GLASSES LIKE A SEARCH BAR",
            "layout": "split_phone_slide",
            "left_key": "slide0",
            "right_key": "phone_main",
            "phone_badge": "RAY-BAN META 01QV · LIVE",
            "callouts": [
                ("META WEARABLES DAT v0.5.0", (52, 211, 153)),
                ("PROACTIVE POV STREAMING", (56, 189, 248)),
            ],
            "lines": [
                (
                    "NEAL FRAZIER",
                    "Stop talking to smart glasses like a search bar. We built Spotter—a proactive AI co-pilot for Ray-Ban Meta glasses powered by Muse Spark and Muse Wonder.",
                    "+7%",
                ),
                (
                    "SPOTTER AI",
                    "Heads up, Neal. Your soldering iron is still at three-eighty Celsius and the flux pen cap is off.",
                    "+6%",
                ),
            ],
        },
        {
            "title": "60FPS ANATOMICAL BRAILLE FACE + 4-ANGLE CHARACTER BOARD",
            "layout": "dual_phone_stage",
            "left_key": "presence_grid",
            "phone1_key": "phone_call",
            "phone1_label": "EYE SWITCH · GLASSES POV",
            "phone2_key": "phone_people",
            "phone2_label": "4-ANGLE CHARACTER BOARD",
            "callouts": [
                ("2D/3D ANATOMICAL BRAILLE FACE", (52, 211, 153)),
                ("CONSENT-FIRST 4-ANGLE DOSSIER", (251, 191, 36)),
            ],
            "lines": [
                (
                    "NEAL FRAZIER",
                    "Talk face-to-face with a sixty-frame-per-second 3D anatomical Braille presence, hot-swap between glasses POV and phone cameras, and capture consent-first four-angle Character Boards.",
                    "+7%",
                ),
            ],
        },
        {
            "title": "60-TURN FLAT LATENCY (1.27s MEDIAN) + BROWSER PWA TRIAL",
            "layout": "split_phone_slide",
            "left_key": "slide3",
            "right_key": "phone_chats",
            "phone_badge": "CONVERSATIONS + HERMES JOT",
            "callouts": [
                ("60 TURNS · 1.27s MEDIAN LATENCY", (52, 211, 153)),
                ("MUSE SPARK OPEN FAILOVER", (167, 139, 250)),
            ],
            "lines": [
                (
                    "NEAL FRAZIER",
                    "With rolling memory compaction and Muse Spark Open local failover, sixty-turn latency stays flat at one point two seven seconds.",
                    "+7%",
                ),
                (
                    "SPOTTER AI",
                    "Spotter for Ray-Ban Meta. Try it live on hardware or in your browser at slash P-W-A.",
                    "+6%",
                ),
            ],
        },
    ]

    master_mp4 = produce_video(
        video_name="spotter-3min-master-demo",
        video_title_badge="SPOTTER · MASTER DEMO",
        scenes_spec=master_scenes,
        assets=assets,
    )
    viral_mp4 = produce_video(
        video_name="spotter-60s-viral-cut",
        video_title_badge="SPOTTER · 60S VIRAL CUT",
        scenes_spec=viral_scenes,
        assets=assets,
    )
    print("\nALL VIDEOS BUILT SUCCESSFULLY:")
    print(f"  1. {master_mp4}")
    print(f"  2. {viral_mp4}")


if __name__ == "__main__":
    main()
