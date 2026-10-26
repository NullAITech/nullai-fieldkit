# Zoth Guide turn API (`POST /v1/guide/turn`) — zoth-relay mobile 0.3.0

Request (Bearer = the phone's mobile token):
`{"mode": "hermes"|"local_agy", "session": "abc", "image": "<base64 jpeg>", "speech": "...", "mission": "...", "reset": false}`

Response: `{"text": "...", "speak": true, "via": "hermes|nous|local_agy", "ms": 2100, "caption": "...", "skipped": "busy|hot|no_frame", "busy": true}`

* `speak:false` means the model chose silence (`SILENT`) or nothing was due.
* One request per session at a time: concurrent turns return `busy:true` instead of queueing.
* Frames, speech and replies are never logged or written to disk; the audit log has sizes only.
* `mobile` profile tokens get the new `guide` capability automatically.

See guide.config.example.yaml for config and the Zoth Guide 0.3.0 report for setup steps.

## 0.4.0: always-on streaming (`WS /v1/guide/stream`)
See the module docstring in `zoth_relay/guide_stream.py` for the message protocol. AGY is driven
over its stream-json protocol as one persistent conversation (`zoth_relay/agy_session.py`), lines
in `[^ ... ^]` are spoken via local Kokoro/Piper TTS as they stream (`markers.py`, `tts.py`).
`scripts/zoth-guide up|down|status|log` is the launcher (Konsole + tmux view of AGY).
