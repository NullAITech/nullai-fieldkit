# zoth-relay mobile API (Zoth Live / Zoth Guide)

The Android apps talk to a **separate, user-level** zoth-relay instance that only exposes the
`mobile` profile: ask an AI target (text + optional camera frame) and read status. Mobile tokens
**cannot** read/write files or run commands (403).

| | |
|---|---|
| URL (from the phone) | `http://100.106.132.5:8766` (zoth-pill's Tailscale IP) |
| Local URL | `http://127.0.0.1:8766` |
| Bound to | `127.0.0.1` and the Tailscale IP only (never `0.0.0.0`; LAN/Wi-Fi IPs refuse connections) |
| Service | `systemctl --user status zoth-relay-mobile` (enabled, lingering, so it runs at boot) |
| Config | `~/.config/zoth-relay/mobile/config.yaml` (targets), `agents.yaml` (token hashes) |
| Provider keys | `~/.config/zoth-relay/mobile/secrets.env` (0600; `GEMINI_API_KEY` set, add `XAI_API_KEY=...` etc.) |
| Phone token | `~/.config/zoth-relay/pixel-mobile.token` (0600) |
| Pairing QR | `~/.config/zoth-relay/pixel-pair.png` (0600), opened by the **Zoth Relay Pair** launcher |
| Audit log | `~/.local/state/zoth-relay-mobile/audit.log` (sizes only, never prompts or images) |

The system instance on `127.0.0.1:8765` (desktop agents, `/etc/zoth-relay`) is unchanged.

Transport is plain HTTP **inside Tailscale** (WireGuard-encrypted end to end). Android blocks
cleartext HTTP by default, so the apps need a `network_security_config` that permits cleartext to
`100.64.0.0/10` hosts (or use `tailscale serve` for HTTPS on the `*.ts.net` name).

## Pairing payload (QR)

```json
{"v":1,"name":"zoth-pill","url":"http://100.106.132.5:8766","token":"zr_…","client":"pixel-mobile"}
```
Scan, store `url` + `token` in EncryptedSharedPreferences, then call `GET /health` and `GET /v1/agents`.

## Auth
Every `/v1/*` call: `Authorization: Bearer <token>`. 401 = bad/revoked token, 403 = not allowed,
429 = rate limit (120/min for `pixel-mobile`).

## Endpoints

### `GET /health` (public)
`{"ok": true}`. Use it for the "relay reachable" indicator.

### `GET /v1/status`
Relay version, host, uptime, memory. Confirms the token works.

### `GET /v1/agents`
AI targets this token may ask, and which have a key configured on the host.
```json
{"default":"gemini","agents":[
  {"name":"gemini","label":"Gemini","model":"gemini-2.5-flash","vision":true,"configured":true},
  {"name":"local","label":"Local (Ollama, text only)","model":"llama3.2:1b","vision":false,"configured":true},
  {"name":"grok","label":"Grok (xAI)","model":"grok-4","vision":true,"configured":false},
  {"name":"openai","label":"OpenAI","model":"gpt-4o-mini","vision":true,"configured":false},
  {"name":"claude","label":"Claude","model":"claude-haiku-4-5","vision":true,"configured":false}]}
```

### `POST /v1/ask`
Ask a target about what the glasses see. JSON body:

| field | type | notes |
|---|---|---|
| `prompt` | string, required | ≤ 8000 chars, e.g. "What should I say to them?" |
| `agent` | string | target name from `/v1/agents`; default = token's first target |
| `image` | string | base64 JPEG/PNG/WebP or a `data:image/jpeg;base64,…` URI, ≤ 8 MB. Downscale frames to ~768 px JPEG q70 for speed. Ignored by `vision:false` targets |
| `mission` | string | ≤ 2000 chars, appended to the system prompt ("networking at a meetup") |
| `system` | string | replaces the default "Zoth ear-guide" system prompt |
| `history` | array | up to 40 `{role:"user"|"assistant", content}` text turns (last 20 used) |
| `stream` | bool | default `true` |
| `max_tokens` | int | 1–4000, default 300 |

**Streaming response** (`stream:true`): `Content-Type: application/x-ndjson`, one JSON object per line:
```
{"type":"start","id":"7e23478ab00d","agent":"grok","model":"grok-4"}
{"type":"delta","text":"Go say hi "}
{"type":"delta","text":"to the couple by the bar."}
{"type":"done","id":"7e23478ab00d","text":"Go say hi to the couple by the bar.","ms":1840}
```
On an upstream failure the stream ends with `{"type":"error","status":502,"detail":"…"}`.
Speak deltas as they arrive (TTS sentence by sentence) to keep latency low.

**Non-streaming** (`stream:false`): `{"id","agent","model","text","ms"}`; upstream errors return 502.

Errors before streaming starts: 400 bad base64, 404 unknown agent, 403 target not allowed for this
token, 413 image too large, 415 not JPEG/PNG/WebP, 422 invalid body, 503 target has no API key on the host.

### `POST /v1/live/token` (Gemini Live, real-time audio + video)
Mints a **single-use, short-lived Gemini Live ephemeral token** so the phone can open the Live
WebSocket to Google directly (lowest latency) without ever holding the real API key. The token is
locked server-side to the model, AUDIO output, session resumption and the relay's system prompt.

Body (optional): `{"mission": "networking at a meetup", "voice": "Puck"}` (mission ≤ 2000 chars is
baked into the locked system instruction; voice = Gemini prebuilt voice name).

The relay also pins the spoken language: `live.language_code` (default `en-US`) is sent as
`speechConfig.languageCode` **and** repeated in the locked system instruction, so the guide keeps
answering in that language even when it hears people, a TV or another call in a different one. Set
`live.language_code: ""` to give the model free choice again. The voice stays overridable per request
from the app; both values are locked into the token.

```json
{"token":"auth_tokens/…","model":"gemini-3.8-live",
 "expires_at":"2026-10-18T04:38:13Z","new_session_expires_at":"2026-10-18T04:09:13Z","uses":1,
 "api_version":"v1alpha","auth_header":"Token","response_modalities":["AUDIO"],"locked":true,
 "ws_url":"wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContentConstrained"}
```
- Start the session before `new_session_expires_at` (~1 min); the session may run until `expires_at`
  (30 min, reconnect with session resumption every ~10 min; the same token works for resumption).
- Times come from Google's clock, not the host's.
- Android: Google GenAI SDK / Firebase AI Logic with `apiKey = token`, `apiVersion = v1alpha`; or raw
  WebSocket to `ws_url` with header `Authorization: Token <token>` (or `?access_token=<token>`).
- Live I/O: input 16 kHz 16-bit PCM mono audio + JPEG frames (≤ 1 fps) + text; output 24 kHz 16-bit PCM.
- Errors: 401/403 auth, 404 live not enabled, 503 no key on host, 502 Google refused.
- Fetch a new token per session; never cache it on disk.

### Examples
```bash
T=$(cat ~/.config/zoth-relay/pixel-mobile.token)
curl -sN http://100.106.132.5:8766/v1/ask -H "Authorization: Bearer $T" -H 'Content-Type: application/json' \
  -d "{\"agent\":\"grok\",\"prompt\":\"What am I looking at?\",\"image\":\"$(base64 -w0 frame.jpg)\"}"
```
Kotlin (OkHttp): POST the JSON, then `response.body!!.source()` and `readUtf8Line()` in a loop,
`JSONObject(line)`, switch on `type`.

## Operating it
```bash
systemctl --user restart zoth-relay-mobile            # after editing config/secrets
journalctl --user -u zoth-relay-mobile -f
cd ~/.local/share/zoth-relay-mobile/app
R="../venv/bin/python -m zoth_relay --config ~/.config/zoth-relay/mobile/config.yaml"
$R agent list
$R agent rotate pixel-mobile --token-file ~/.config/zoth-relay/pixel-mobile.token && ~/NullAITech/zoth-relay/scripts/mobile-pair-qr.sh
$R agent revoke pixel-mobile                           # lost phone: immediate
```
Add a target: append to `ask_targets` in config.yaml (any OpenAI-compatible `/chat/completions`
endpoint: `base_url`, `model`, optional `api_key_env`, `vision`, `max_tokens`, `timeout`, `system`).

## Privacy
Frames and prompts are held in memory for one upstream request and never written to disk or logged.
The default system prompt tells the model not to identify people by face. Note that frames you send to a
cloud target (xAI, OpenAI, Google, Anthropic) are processed under that provider's terms.
