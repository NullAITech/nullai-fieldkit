"""Spotter RTMP Streamer & Live Broadcast Bridge (Nov 2026).

Pipes live video frames (from Ray-Ban Meta glasses or Phone camera) and audio
into an ultra-low-latency FFmpeg pipeline that pushes directly via RTMP/RTMPS to:
- X (Twitter Live)
- Twitch
- YouTube Live
- OBS Studio (RTMP Ingest or HTTP MJPEG Browser Source)
"""
from __future__ import annotations

import asyncio
import logging
import os
import re
import shutil
import subprocess
import threading
import time
from typing import Any

log = logging.getLogger("zoth_relay.rtmp")

PRESETS: dict[str, dict[str, str]] = {
    "x": {
        "name": "X (Twitter Live)",
        "url_template": "rtmps://prod-fastly-us-east-1.video.pscp.tv:443/x/{key}",
        "default_bitrate": "2200k",
        "description": "Stream directly to your X account via Creator Studio Live Producer."
    },
    "twitch": {
        "name": "Twitch",
        "url_template": "rtmp://live.twitch.tv/app/{key}",
        "default_bitrate": "2500k",
        "description": "Broadcast live glasses POV to your Twitch channel."
    },
    "youtube": {
        "name": "YouTube Live",
        "url_template": "rtmp://a.rtmp.youtube.com/live2/{key}",
        "default_bitrate": "3000k",
        "description": "Stream live glasses video to YouTube Live Control Room."
    },
    "obs": {
        "name": "OBS Studio / Local RTMP",
        "url_template": "rtmp://127.0.0.1:1935/live/{key}",
        "default_bitrate": "3500k",
        "description": "Stream to a local OBS Studio RTMP ingest server or proxy."
    }
}


class RTMPStreamer:
    def __init__(self):
        self._lock = threading.Lock()
        self._proc: subprocess.Popen | None = None
        self._feed_thread: threading.Thread | None = None
        self._running = False
        self._active_destination = ""
        self._preset_name = ""
        self._start_time = 0.0
        self._frames_sent = 0
        self._fps = 20
        self._latest_jpeg: bytes | None = None
        self._new_frame_event = threading.Event()

    @property
    def is_active(self) -> bool:
        with self._lock:
            return self._running and self._proc is not None and self._proc.poll() is None

    def get_latest_frame(self) -> bytes | None:
        with self._lock:
            return self._latest_jpeg

    def push_frame(self, jpeg_bytes: bytes) -> None:
        if not jpeg_bytes:
            return
        with self._lock:
            self._latest_jpeg = jpeg_bytes
            self._new_frame_event.set()

    def build_destination_url(self, preset: str, stream_key: str, custom_url: str = "") -> str:
        p = preset.strip().lower()
        if p in PRESETS:
            clean_key = stream_key.strip()
            if not clean_key:
                raise ValueError(f"Stream key is required for preset '{p}'")
            return PRESETS[p]["url_template"].format(key=clean_key)
        if custom_url:
            url = custom_url.strip()
            if stream_key.strip() and not url.endswith("/"):
                url = f"{url.rstrip('/')}/{stream_key.strip()}"
            return url
        raise ValueError("Either a valid preset ('x', 'twitch', 'youtube', 'obs') or custom_url is required")

    def start(self, destination: str = "", preset: str = "", stream_key: str = "",
              fps: int = 20, bitrate: str = "2500k") -> dict[str, Any]:
        with self._lock:
            if self._running and self._proc is not None and self._proc.poll() is None:
                return {
                    "ok": True,
                    "already_active": True,
                    **self._status_unlocked()
                }

            dest = destination.strip()
            if preset or stream_key:
                dest = self.build_destination_url(preset, stream_key, dest)
            if not dest:
                # Fallback to env var if present
                dest = os.environ.get("FIELDKIT_RTMP_URL", "").strip()
            if not dest:
                raise ValueError("No RTMP destination URL provided. Specify destination or preset + stream_key.")

            if not shutil.which("ffmpeg"):
                raise RuntimeError("ffmpeg binary not found on host system.")

            self._fps = max(10, min(60, fps))
            self._active_destination = dest
            self._preset_name = preset.lower() if preset else "custom"
            self._frames_sent = 0
            self._start_time = time.time()
            self._running = True
            self._new_frame_event.clear()

            # Generate blank 540x960 fallback frame if none available yet
            if not self._latest_jpeg:
                try:
                    self._latest_jpeg = subprocess.check_output([
                        "ffmpeg", "-f", "lavfi", "-i", "color=c=black:s=540x960:d=0.1",
                        "-frames:v", "1", "-f", "image2", "pipe:1"
                    ], stderr=subprocess.DEVNULL)
                except Exception as e:
                    log.warning(f"Could not generate initial blank frame: {e}")

            # FFmpeg ultra-low-latency RTMP streaming command
            cmd = [
                "ffmpeg", "-y",
                "-f", "image2pipe", "-vcodec", "mjpeg", "-r", str(self._fps),
                "-i", "pipe:0",
                # Synchronous silent AAC stereo audio track so streaming platforms (X, Twitch, YouTube) accept the stream
                "-f", "lavfi", "-i", "anullsrc=channel_layout=stereo:sample_rate=44100",
                "-c:v", "libx264",
                "-preset", "ultrafast",
                "-tune", "zerolatency",
                "-pix_fmt", "yuv420p",
                "-b:v", bitrate,
                "-maxrate", bitrate,
                "-bufsize", str(int(bitrate.rstrip("kM")) * 2) + "k",
                "-g", str(self._fps * 2),  # 2-second GOP keyframe interval
                "-c:a", "aac",
                "-b:a", "128k",
                "-ar", "44100",
                "-f", "flv",
                dest
            ]

            log.info(f"Starting RTMP stream to {self._mask_url(dest)} with cmd: {' '.join(cmd[:12])}...")
            try:
                self._proc = subprocess.Popen(
                    cmd,
                    stdin=subprocess.PIPE,
                    stdout=subprocess.DEVNULL,
                    stderr=subprocess.PIPE
                )
            except Exception as e:
                self._running = False
                raise RuntimeError(f"Failed to start FFmpeg: {e}")

            self._feed_thread = threading.Thread(target=self._pipe_loop, daemon=True, name="rtmp-feed")
            self._feed_thread.start()

            return {
                "ok": True,
                **self._status_unlocked()
            }

    def _pipe_loop(self) -> None:
        interval = 1.0 / self._fps
        last_frame_bytes = self._latest_jpeg

        while self._running:
            start_t = time.time()
            frame_to_write = None

            with self._lock:
                if not self._running or self._proc is None or self._proc.poll() is not None:
                    break
                if self._latest_jpeg:
                    frame_to_write = self._latest_jpeg
                    last_frame_bytes = frame_to_write
                elif last_frame_bytes:
                    frame_to_write = last_frame_bytes

            if frame_to_write and self._proc and self._proc.stdin:
                try:
                    self._proc.stdin.write(frame_to_write)
                    self._proc.stdin.flush()
                    self._frames_sent += 1
                except (BrokenPipeError, OSError) as e:
                    log.warning(f"RTMP pipe closed by FFmpeg: {e}")
                    break
                except Exception as e:
                    log.error(f"Error writing to RTMP pipe: {e}")
                    break

            elapsed = time.time() - start_t
            sleep_time = max(0.005, interval - elapsed)
            time.sleep(sleep_time)

        self.stop()

    def stop(self) -> dict[str, Any]:
        with self._lock:
            was_active = self._running
            self._running = False

            if self._proc:
                try:
                    if self._proc.stdin:
                        self._proc.stdin.close()
                except Exception:
                    pass
                try:
                    self._proc.terminate()
                    self._proc.wait(timeout=2.0)
                except Exception:
                    try:
                        self._proc.kill()
                    except Exception:
                        pass
                self._proc = None

            status = self._status_unlocked()
            status["was_active"] = was_active
            status["ok"] = True
            log.info("RTMP stream stopped.")
            return status

    def status(self) -> dict[str, Any]:
        with self._lock:
            return self._status_unlocked()

    def _status_unlocked(self) -> dict[str, Any]:
        alive = self._running and self._proc is not None and self._proc.poll() is None
        uptime = (time.time() - self._start_time) if (alive and self._start_time > 0) else 0.0
        return {
            "active": alive,
            "destination": self._mask_url(self._active_destination),
            "destination_raw": self._active_destination if not alive else "",
            "preset": self._preset_name,
            "fps": self._fps,
            "frames_sent": self._frames_sent,
            "uptime_seconds": round(uptime, 1),
            "pid": self._proc.pid if alive else None,
            "presets_available": {k: {"name": v["name"], "description": v["description"]}
                                  for k, v in PRESETS.items()}
        }

    @staticmethod
    def _mask_url(url: str) -> str:
        if not url:
            return ""
        # Mask stream key at end of URL (e.g. /x/ab12cd34 -> /x/ab12***)
        parts = url.split("/")
        if len(parts) > 1 and len(parts[-1]) > 4:
            key = parts[-1]
            masked_key = key[:3] + "•" * max(4, len(key) - 6) + key[-3:]
            return "/".join(parts[:-1]) + "/" + masked_key
        return url


# Global singleton instance
STREAMER = RTMPStreamer()
