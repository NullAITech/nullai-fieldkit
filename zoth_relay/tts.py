"""Local TTS on zoth-pill (CPU). Returns 16-bit mono PCM per sentence.

kokoro (default): Kokoro-82M fp32 ONNX via kokoro-onnx, 24 kHz, best quality. Box benchmark at
  3 threads: ~0.3 s for a 2-word line, ~0.9 s for a 13-word sentence (RTF ~0.25). The int8 model
  was ~3x SLOWER on these CPUs, so use kokoro-v1.0.onnx, not the int8 file.
piper: en_US-lessac-medium, 22.05 kHz, ~0.02-0.1 s per sentence (RTF ~0.03), slightly robotic.
none : no relay audio; the phone speaks the text with Android TTS.
Heavy imports happen lazily in load(), so the relay starts without these packages installed.
"""
from __future__ import annotations

import os
import threading
from dataclasses import dataclass


@dataclass
class TtsConfig:
    engine: str = "kokoro"            # kokoro | piper | none
    model: str = "~/.local/share/zoth-tts/kokoro-v1.0.onnx"
    voices: str = "~/.local/share/zoth-tts/voices-v1.0.bin"
    voice: str = "af_heart"
    speed: float = 1.1
    threads: int = 3
    piper_model: str = "~/.local/share/zoth-tts/en_US-lessac-medium.onnx"


def parse_tts(raw: dict | None) -> TtsConfig:
    raw = raw or {}
    return TtsConfig(**{k: v for k, v in raw.items() if k in TtsConfig.__dataclass_fields__})


class Tts:
    def __init__(self, cfg: TtsConfig):
        self.cfg = cfg
        self.rate = 24000 if cfg.engine == "kokoro" else 22050
        self._impl = None
        self._lock = threading.Lock()
        self.error = ""

    @property
    def enabled(self) -> bool:
        return self.cfg.engine in ("kokoro", "piper") and not self.error

    def load(self) -> bool:
        """Loads the model once (call off the event loop). False + self.error if unavailable."""
        if self._impl is not None or not self.enabled:
            return self._impl is not None
        try:
            os.environ.setdefault("OMP_NUM_THREADS", str(self.cfg.threads))
            if self.cfg.engine == "kokoro":
                import onnxruntime as ort
                from kokoro_onnx import Kokoro
                so = ort.SessionOptions()
                so.intra_op_num_threads, so.inter_op_num_threads = self.cfg.threads, 1
                sess = ort.InferenceSession(os.path.expanduser(self.cfg.model), so, providers=["CPUExecutionProvider"])
                self._impl = Kokoro.from_session(sess, os.path.expanduser(self.cfg.voices))
            else:
                from piper import PiperVoice
                self._impl = PiperVoice.load(os.path.expanduser(self.cfg.piper_model))
                self.rate = self._impl.config.sample_rate
            self.synth("Ready.")  # warm-up
            return True
        except Exception as e:  # missing package/model: fall back to phone TTS
            self.error = f"{self.cfg.engine} TTS unavailable: {type(e).__name__}: {str(e)[:160]}"
            self._impl = None
            return False

    def synth(self, text: str) -> bytes:
        with self._lock:  # one synthesis at a time keeps CPU bounded
            if self.cfg.engine == "kokoro":
                import numpy as np
                audio, _sr = self._impl.create(text, voice=self.cfg.voice, speed=self.cfg.speed, lang="en-us")
                return (np.clip(audio, -1, 1) * 32767).astype("<i2").tobytes()
            return b"".join(c.audio_int16_bytes for c in self._impl.synthesize(text))
