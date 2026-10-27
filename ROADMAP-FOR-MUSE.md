# ROADMAP-FOR-MUSE — FieldKit by NullAI, Meta Hackathon run-out

Solo indie entry. Today ~2026-10-27. Submit by **2026-11-04 6:00 PM ET**
(hard deadline 9:00 PM). Repo: `NullAITech/nullai-fieldkit`, branch for this
work, PR opened not merged. Author on commits:
`Neal Frazier <1nc0gn30@users.noreply.github.com>`.

## What is already shipped (do not rebuild)

- Relay: protocol-agnostic providers (Muse Spark/Wonder, Messages wire,
  `spark-open` Baseten + `spark-open-modal`, local), Sessions WS
  (frame-diff, bounded context, prefix layout, latency metrics), Missions
  REST (planner, typed assess, now with **interview->plan**: ambiguous goals
  return `status=clarify` + questions, answers resubmitted to plan;
  plans conditioned on live vision note + prior memories + attached memories;
  assess carries step history).
- Guide trio (Eyes/Advisor/Director, presets + custom, Shh, wearer
  voice-focus, sound heading, compass); proactive loop auto-advances steps
  (typed `step_done`/`unsafe`), safety speaks first.
- MCP: `see_now`/`say`/`mission_status`/`advance_step` + `jot_field_note`/
  `jot_mission_sync`/`recall_second_brain` + confirm-gated `stream_start`/
  `stream_stop`. Bridge mode serves mission/recall tools, refuses live-state
  tools honestly.
- Hermes Jot: mission notes auto-created (numbered tasks), hands-free
  `jot_set_task` checkoff on every advance path, voice/WS "jot down",
  AGENT_DISABLED graceful. Obsidian Second Brain: engrams, Base + Canvas,
  `prior_notes` recall into planning, Command Center mirror.
- SAM 3.1 boxes (env `SAM_API_URL`, drawn in PWA preview) + Muse Image recap
  on completion (`/v1/missions/{id}/recap`, labelled local fallback).
- Live Mode: `/overlay` OBS browser source, confirm-token gate, audit-logged.
- PWA at `/pwa`: same Sessions/Missions endpoints, camera or synthetic test
  scenes, Plan->questions?->Review->Begin, Jot/Obsidian badges.
- Spotter Android v0.2.5: phone camera FIT preview, fullscreen + ☰ menu,
  DAT (APPLICATION_ID=0 dev mode, Meta AI camera->mic permission chain,
  BT-CONNECT, held-frame flush on connect, SDK diagnostics in status).

## Honest blockers (Neal-owned, not code)

1. Glasses first frame: needs Meta AI Developer Mode + Spotter listed under
   the glasses' dev apps + glasses on WiFi (Meta AI logs showed
   `registeredSNApps: []` and `WIFI_NETWORKS_UNREACHABLE`). All taps are Neal's.
2. Relay `:8770` needs one `zoth-vault unlock` after daemon restarts (10-day
   TTL set; do NOT run `daemon restart`).
3. Pixel must be on USB for Spotter installs.
4. Partner keys (Baseten/Modal) land in `zoth-vault` when claimed; presets are
   env-gated until then.

## Day plan

- **D2 (today)**: land interview/vision/memory planning + held-frame flush
  (this branch). Keep suite green. PR open.
- **D3**: Spotter glasses first-frame test with Neal (diagnostics line:
  `reg/devMode/devices`); 10-min soak + latency check on live relay.
- **D4**: film 30s internal Mission Mode clip; proactive-coach tuning
  (DEDUP/nag limits, safety wording).
- **D5**: Live Mode E2E (overlay in OBS, confirm-gated start/stop, audit
  proof); X RTMP path documented; if flaky, Mission Mode stays the demo.
- **D6**: PWA polish for judges (1-click scenes, preset tour); SAM highlight
  on preview; recap card in demo mission.
- **D7**: `zoth-relay bench` 60-min flat-latency soak -> graph in README;
  scopes docs; feature freeze.
- **D8-D9**: film uncut Mission Mode take (+ optional Live take), MCP
  `see_now` screen moment, bug bash.
- **11-04**: README (prior vs new, bench graph), PRIOR-WORK, pitch script,
  submit ≤ 6 PM ET. Stop feature work after submit.

## Operating rules

- Small reversible commits, day-scoped messages. Never rewrite history.
- No secrets in git: keys stay in env / `zoth-vault` / `local.properties`.
  Secret-scan before every push (`grep` for sk-, ghp-, Bearer tokens, .token).
- Public/exec actions confirm-gated + audited. Keep plan-review-Begin,
  Shh, bystander-ignore. No fake hardware: diagnostics over simulation.
- New tests for new behavior; full suite must stay green (baseline: only the
  2 pre-existing `test_install` failures).
