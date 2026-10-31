"""Gemini Live ephemeral tokens for the mobile apps.

The phone never sees the real Gemini API key. It asks the relay (with its mobile token) for a
short-lived, single-use Live API token that is *locked* to one model, AUDIO output and the
relay's system instruction, then opens the Live WebSocket to Google directly (lowest latency).

Google's defaults apply: the token must start a session within ~1 minute and the session can
run for 30 minutes (reconnect with session resumption every ~10 minutes). Expiry times are
computed from Google's own `Date` header, so a skewed local clock cannot break tokens.
"""
from __future__ import annotations

import os
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from email.utils import parsedate_to_datetime

import httpx

DEFAULT_LIVE_SYSTEM = (
    "You are Zoth, a real-time guide speaking into the user's ear through smart glasses. "
    "You see what they see. Speak in one or two short sentences, be specific and actionable. "
    "Never identify people by face; refer to them by position and clothing."
)
SESSION_START_WINDOW = timedelta(minutes=1)   # Google default newSessionExpireTime
SESSION_LIFETIME = timedelta(minutes=30)      # Google default expireTime

# The Live models choose their output language from whatever they hear, so a Spanish TV, a
# bystander or a video playing nearby can flip the guide mid-call. Google's fix is two-sided:
# speechConfig.languageCode pins the audio and the same instruction in the system prompt keeps
# the words on side, so the guide always answers in the language the relay picked.
LANGUAGE_NAMES = {"en": "English", "es": "Spanish", "fr": "French", "de": "German",
                  "pt": "Portuguese", "it": "Italian", "nl": "Dutch"}


def language_directive(code: str) -> str:
    """One system-instruction line that holds the guide in a single language."""
    name = LANGUAGE_NAMES.get(code.split("-", 1)[0].lower(), code)
    label = f"{name} ({code})" if name != code else code
    return (f"Always speak {label}, and never switch language mid-sentence: even if the wearer, "
            "the people around them, or audio playing nearby (TV, video, another call) is in a "
            "different language, keep answering in the same one.")


class LiveError(Exception):
    def __init__(self, status: int, detail: str):
        super().__init__(detail)
        self.status, self.detail = status, detail


@dataclass
class LiveConfig:
    model: str = "gemini-3.8-live"
    api_key_env: str = "GEMINI_API_KEY"
    base_url: str = "https://generativelanguage.googleapis.com"
    api_version: str = "v1alpha"
    system: str = ""
    voice: str = ""                 # prebuilt voice name, e.g. "Puck"; empty = model default
    language_code: str = "en-US"    # BCP-47 output language; "" = let the model choose
    response_modalities: list[str] = field(default_factory=lambda: ["AUDIO"])
    timeout: int = 20

    @property
    def api_key(self) -> str:
        return os.environ.get(self.api_key_env, "")

    @property
    def configured(self) -> bool:
        return bool(self.api_key)

    @property
    def ws_url(self) -> str:
        host = self.base_url.replace("https://", "wss://").replace("http://", "ws://").rstrip("/")
        return f"{host}/ws/google.ai.generativelanguage.{self.api_version}.GenerativeService.BidiGenerateContentConstrained"


def parse_live(raw: dict | None) -> LiveConfig | None:
    if not raw:
        return None
    return LiveConfig(
        model=str(raw.get("model", "gemini-3.8-live")).removeprefix("models/"),
        api_key_env=str(raw.get("api_key_env", "GEMINI_API_KEY")),
        base_url=str(raw.get("base_url", "https://generativelanguage.googleapis.com")).rstrip("/"),
        api_version=str(raw.get("api_version", "v1alpha")),
        system=str(raw.get("system") or ""), voice=str(raw.get("voice") or ""),
        language_code=str(raw.get("language_code", "en-US") or ""),
        response_modalities=[str(m).upper() for m in (raw.get("response_modalities") or ["AUDIO"])],
        timeout=int(raw.get("timeout", 20)))


def build_setup(cfg: LiveConfig, mission: str = "", voice: str = "") -> dict:
    system = (cfg.system or DEFAULT_LIVE_SYSTEM) + (f"\nCurrent mission: {mission}" if mission else "")
    if cfg.language_code:
        system += "\n" + language_directive(cfg.language_code)   # custom prompts get the pin too
    gen: dict = {"responseModalities": cfg.response_modalities}
    v = voice or cfg.voice
    speech: dict = {}
    if cfg.language_code:
        speech["languageCode"] = cfg.language_code               # pins the audio language
    if v:
        speech["voiceConfig"] = {"prebuiltVoiceConfig": {"voiceName": v}}
    if speech:
        gen["speechConfig"] = speech
    return {"model": f"models/{cfg.model}", "generationConfig": gen, "sessionResumption": {},
            "systemInstruction": {"parts": [{"text": system}]}}


def _server_now(resp: httpx.Response) -> datetime:
    try:
        return parsedate_to_datetime(resp.headers["date"]).astimezone(timezone.utc)
    except (KeyError, TypeError, ValueError):
        return datetime.now(timezone.utc)


def _iso(dt: datetime) -> str:
    return dt.replace(microsecond=0).isoformat().replace("+00:00", "Z")


async def mint_token(cfg: LiveConfig, mission: str = "", voice: str = "",
                     transport: httpx.AsyncBaseTransport | None = None) -> dict:
    if not cfg.configured:
        raise LiveError(503, f"Gemini Live has no API key configured on the host ({cfg.api_key_env})")
    setup = build_setup(cfg, mission, voice)
    body = {"uses": 1, "bidiGenerateContentSetup": setup}
    url = f"{cfg.base_url}/{cfg.api_version}/auth_tokens"
    try:
        async with httpx.AsyncClient(timeout=cfg.timeout, transport=transport) as c:
            r = await c.post(url, json=body, headers={"x-goog-api-key": cfg.api_key})
    except httpx.HTTPError as e:
        raise LiveError(502, f"Gemini token service unreachable: {type(e).__name__}")
    if r.status_code >= 400:
        raise LiveError(502, f"Gemini token service returned {r.status_code}: {r.text[:300]}")
    name = (r.json() or {}).get("name", "")
    if not name.startswith("auth_tokens/"):
        raise LiveError(502, "Gemini token service returned no token")
    now = _server_now(r)
    return {"token": name, "model": cfg.model, "expires_at": _iso(now + SESSION_LIFETIME),
            "new_session_expires_at": _iso(now + SESSION_START_WINDOW), "uses": 1,
            "api_version": cfg.api_version, "ws_url": cfg.ws_url, "auth_header": "Token",
            "response_modalities": cfg.response_modalities, "locked": True}
