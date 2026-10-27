"""FIELDKIT Live Mode gate (D5): OBS overlay + stream start/stop.

Public streaming is a public action, so it is confirm-gated by design:
stream_start first issues a short-lived confirm token; only repeating the
call with that exact token (the user saying "yes, go live") activates.
Every transition is audit-logged by the callers. The relay itself serves
the OBS browser-source overlay; pushing RTMP to X happens in OBS, so the
gate honestly reports mode "overlay" until an RTMP destination is set.
"""
from __future__ import annotations

import os
import secrets
import time


class LiveGate:
    def __init__(self):
        self.active = False
        self.since = 0.0
        self.destination = ""
        self._challenge = ""
        self._exp = 0.0

    def status(self) -> dict:
        return {"active": self.active,
                "since": self.since or None,
                "destination": self.destination,
                "mode": "live" if self.active else "idle",
                "rtmp_configured": bool(os.environ.get("FIELDKIT_RTMP_URL"))}

    def _new_challenge(self) -> str:
        self._challenge = secrets.token_hex(3).upper()
        self._exp = time.time() + 300
        return self._challenge

    def start(self, confirm: str | None, destination: str = "") -> dict:
        if self.active:
            return {"ok": True, **self.status(), "detail": "already live"}
        if not confirm:
            return {"ok": False, "needs_confirm": True,
                    "confirm_token": self._new_challenge(),
                    "detail": "Public action: repeat stream_start with this confirm token to go live."}
        if confirm.strip().upper() != self._challenge or time.time() > self._exp:
            return {"ok": False, "needs_confirm": True,
                    "confirm_token": self._new_challenge(),
                    "detail": "Confirm token mismatched or expired — new token issued."}
        self._challenge = ""
        self.active = True
        self.since = time.time()
        self.destination = destination or os.environ.get("FIELDKIT_RTMP_URL", "") or "overlay"
        return {"ok": True, **self.status(),
                "detail": "Live. OBS browser source: /overlay (RTMP push stays in OBS)."}

    def stop(self) -> dict:
        was = self.active
        self.active = False
        self.since = 0.0
        self.destination = ""
        return {"ok": True, "was_active": was, **self.status()}


LIVE = LiveGate()
