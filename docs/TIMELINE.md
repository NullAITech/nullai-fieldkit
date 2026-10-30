# TIMELINE.md — how Zoth Fieldkit got here, commit by commit

The judge-facing timeline lives in the [README](../README.md#timeline). This file is the
same story with the evidence attached: every row cites the commit that carried it, and the
commit column has been checked rather than remembered.

**What it is.** Zoth Fieldkit ("Spotter") is a solo-built Android + Python relay that puts
Meta Muse models (Spark + Wonder) behind Ray-Ban Meta glasses for proactive, hands-free
mission coaching. Built for the **Meta Global AI Developer Hackathon** (indie track):
kickoff Mon Oct 26 2026 12:00 PM ET, submissions due **Wed Nov 4, 9:00 PM ET**.

**How the commit column was verified.** Each hash below was run through
`git cat-file -e <hash>^{commit}` inside this repository. **35 of 42 resolve here.** The
6 marked `(mirror \`…\`)` are the relay working copy's hash for the same work: they
resolve in the `zoth-relay` mirror (checked with the same command there) but not in this
repository, so they are shown because the build log recorded them and not as citations of
this repo. The remaining one, `970f5ef`, is a commit in the sibling `meta-dev-skills`
repository and is described as such below. Rows with `—` are runtime state or pre-kickoff prep, not code
commits.

**Prior vs. new.** Prior work is declared separately in [PRIOR-WORK.md](../PRIOR-WORK.md),
as the rules require. Everything here tagged NEW is a commit after kickoff; the pre-event
relay/demo state is frozen at commit `7f5f463`, tagged **`baseline/pre-event-2026-10-26`**
(annotated tag, peels to that commit, published to `origin`). The hackathon delta is exactly
`git diff baseline/pre-event-2026-10-26..main`.

**Judging weights** (DETAILS §8): Impact 30% · Use of Meta AI Tooling 25% · Technical Depth
& Execution 20% · Demo 25%. Stage 1 is pass/fail on theme fit plus actually using the Meta
Model API. Bonus pool: Most Viral $20K, Best Open Weights $10K, Community Vote $10K, Best
Agentic Use-Case $5K, Best Creative Build $5K.

## Timeline

| Date | What shipped | Commit | Why it mattered (judging dimension) |
|---|---|---|---|
| 2026-10-25 (pre-kickoff) | Day pinned; repos located; Official Rules noted as not-yet-published; baseline relay tests run (129 passed, 2 pre-existing `test_install` failures); read-only research on Meta Model API (`api.meta.ai/v1`, OpenAI-compatible) and relay seams; `PRIOR-WORK.md` skeleton drafted; two-tier cognition + protocol-agnostic provider decisions locked. | — (pre-event, no entry code) | **Rule compliance** — prep only, no entry features before kickoff; seeds the prior/new split (failure to mark original work = DQ). |
| 2026-10-26 (D1, kickoff 12:52 PM) | Rules captured in `RULES-NOTES.md` (public repo, new-work-only, prior/new split, weights); baselines tagged `baseline/pre-event-2026-10-26`; branch `hackathon/2026-10-26`; Provider layer (OpenAI-compat + Anthropic Messages transports, `spark`/`wonder`/local presets) + 5 tests + smoke script; auth-gated Sessions/Missions stubs. Suite 137 passed; **live Spark smoke PASSED** (text + vision turn). | `afd1e70`, `3d122a9` (mirror `8900af8`, `8326557`) | **Use of Meta AI Tooling 25%** (Muse Spark/Wonder wired); **Technical Depth 20%** (provider ABC, no litellm — two thin transports); **Stage 1** (Meta Model API actually called, verified live). |
| 2026-10-26 (D1 late) | Three "white-space" fills adopted: Wonder structured step events, confirm-gated agent actions via trust layer, open-weight preset soak. Verified no OBS/RTMP plumbing existed in the relay. | — | **Technical Depth 20%** + bonus targeting (Best Agentic, Best Open Weights); honest scoping of what is genuinely new. |
| 2026-10-26 (D2, built early) | `WS /v1/sessions` replaced the stub with the real API (`zoth_relay/sessions.py`): hello handshake w/ provider routing, speech/text turns, SHA-256 frame diff (changed-only), clip attach, mission prefix, barge; bounded context (cached system+mission prefix + char-capped transcript); per-turn latency metrics; live soak harness. 7 tests; suite 141 passed. Live soak vs Muse Spark 1.1: 60 turns, first_ms p50 **1274 ms**, bounded history, no context drift at turn 60. | `13a2336` (mirror `c1ebdb9`) | **Impact 30%** (real-time streaming = the product); **Technical Depth 20%** (bounded context, frame diff, metrics); **Demo 25%** (soak numbers). |
| 2026-10-26 (D3, built early) | `zoth_relay/missions.py`: in-memory `MissionStore`, Wonder structured planner (one sentence → JSON checklist, tolerant parse, cap 12), typed step tracker (`step_done`/`off_track`/`unsafe`/`none`); REST create/get/events; sessions attach `mission_id`; current step surfaces in next turn's prefix. 6 tests; suite 147 passed. Live Wonder call flagged **UNVERIFIED** — vault session expired. | `db1706b` (mirror `9565afb`) | **Use of Meta AI Tooling 25%** (Muse Wonder as planner); **Impact 30%** (Mission Mode flagship); **Technical Depth 20%** (typed tracker replaces regex). |
| 2026-10-26 | Relay LIVE: FieldKit relay running detached on `100.106.132.5:8770` (Tailscale) + loopback, health OK. Live Wonder planner **VERIFIED** (8-step plan, 4.6 s); REST mission create w/ phone token verified; live WS session verified (mission attach → Spark turn on-step, ms 1390 / first_ms 1247). | — (runtime; code = D2/D3) | **Demo 25%** (full end-to-end path proven); closes the earlier "unverified" flags transparently. |
| 2026-10-26 | Meta glasses via Wearables DAT: Android **v0.2.0** — Kotlin bridge `DatGlasses.kt` (initialize, Meta AI registration, AutoDeviceSelector session, camera photos → relay frames); compileSdk 35, Kotlin 2.2.20; APPLICATION_ID placeholder; BUILD SUCCESSFUL (41 MB w/ DAT native libs); `DAT-SETUP.md`. Pushed public. On-glasses session flagged unverified. | `f55d581` | **Use of Meta AI Tooling 25%** (Wearables DAT SDK); **Technical Depth 20%** (real AAR signatures inspected via `javap` before coding). |
| 2026-10-26 | `zoth_relay/trio.py`: Eyes (deduped observations), Advisor (typed prioritized suggestions), Director (sole speak decision; safety always speaks; preset chattiness gates; pause mutes); presets safety-first/get-it-done/teach-me/social. App renamed **Spotter**; live braille eyes-view (luminance → Bayer 4×4 → braille 2×4 cells); real compass heading. Rebuilt + reinstalled to Pixel. Phone proved able to open relay sessions. | `46fa057` (mirror `a3e2acf`) | **Impact 30%** (proactive coach); **Technical Depth 20%** (trio architecture, dithered braille); **Demo 25%** (visible on-device). |
| 2026-10-26 | Trio wired into live sessions: WS hello accepts `guide`/`preset`/`preset_custom`/`allow_outside`; 2.5 s proactive Eyes→Advisor→Director loop over changed frames; Director-owned `say`; pause/resume/`shh` gating; safety rule-first. Voice focus v1 (`speaker: wearer|other`; bystanders counted `ignored_outside`, never sent to models). Spotter preset cycling, Shh, compass 1/sec. New tests; suite 156 passed. | `2c67626` (mirror `f9dd037`) | **Impact 30%** + **Technical Depth 20%** (bystander privacy gate = zero model calls); guarded agent loop under Meta tooling. |
| 2026-10-26 | Sound direction + render polish: Android `SoundField` (48 kHz stereo, fallbacks) estimates relative bearing via cross-correlation + level, smoothed; app shows `Sound NNN deg` and streams `{type:sound}` 1/sec; relay stores `sound_heading` etc.; unlabelled speech >75° off-axis treated as bystander. Eyes-view Photo→Braille→ASCII cycle. +1 test; APK rebuilt + reinstalled to Pixel clean. | `dd98d5e` | **Technical Depth 20%** (on-device DSP, honestly labelled as an estimate); **Impact 30%** (spatial awareness). DAT audio DoA marked as future work. |
| 2026-10-26 | Camera/DAT correction (per Neal): ASCII/braille/dither moved out of the camera stream into app design (removed stream overlay; added design-only `DitherStripView`); phone camera stabilization (preview-size selection, center-crop rotation fix, AF/AE, session cleanup, throttled QR); `DatGlasses` now explicitly `Stream.start()`s the DAT video and only reports streaming after the first decoded frame (previously falsely said connected). Fit pass: suite 157 passed; **Spotter v0.2.1**; v0.2.1 installed + screenshot-inspected. | `8f04f71`, `2a2e400` | **Demo 25%** (correct glasses POV, not a text overlay); **Technical Depth 20%** (DAT video-stream bug fixed). |
| 2026-10-26 | New canonical repo **`nullai-fieldkit`** with a clean curated history (frozen baseline → D1 provider → D1 stubs → D2 Sessions → D3 Missions → rebrand docs); public name **"FieldKit by NullAI"**; user-facing README + `BRAND.md` + public `PRIOR-WORK.md`; **PUBLISHED PUBLIC** at `github.com/NullAITech/nullai-fieldkit`; secret scan clean. Android phone client v0.1.0; white Meta-style redesign (Phone/Glasses source toggle, QR pairing). | `7f5f463`, `3539f12`, `7a27ed8`, `f4512f5` | **Rule compliance** (public repo; honest prior/new split); **Demo 25%** (judge-facing client). |
| 2026-10-26 late | Vault unlocked; `:8770` restarted on current FieldKit code (scratch-port proof, then swap); health verified on loopback + Tailscale; live WS guide session (trio safety-first, real Spark answer); **MCP agent tools** `see_now`/`say`/`mission_status`/`advance_step` shipped. | `513178c` | **Use of Meta AI Tooling 25%** (MCP agent tools); **Demo 25%** (relay serving current code). |
| 2026-10-27 (D2) | MCP agent tools live with a shared mission store across REST/WS/MCP; **Spotter v0.2.3** — DAT camera→microphone permission via Meta AI before session + DAT self-diagnostics (reg/devMode/devices); plan-review-Begin missions (no default plan); AUTO step advance in the proactive loop (typed assessor). Relay suite 164 passed. | `513178c`, `c953fe4`, `6afdc6b` | **Use of Meta AI Tooling 25%**; **Technical Depth 20%** (typed assessor auto-advance). |
| 2026-10-27 (D2) | Jot + Second Brain integration: `jot_bridge.py` (real worker protocol via Node subprocess — mission→Jot checklist, revision-safe auto-checkoff, voice "jot down" appends, graceful degrade); `second_brain.py` (Obsidian engrams w/ frontmatter + callouts + wikilinks; `FieldKit Missions.base` + canvas; `prior_hints` recall feeds the Wonder planner). Spark **Open** preset (`muse-spark-open-1026`). 13 new tests; suite 178 passed; real-engine smoke vs the Hermes Jot worker passed. | `930094b` | **Impact 30%** (durable, reviewable missions); **Bonus: Best Open Weights** (Spark Open preset); **Use of Meta AI Tooling 25%**. |
| 2026-10-27 (D2) | Prize-coverage alignment: Baseten/Modal first-class presets; `recap.py` — SAM 3.1 highlight boxes (SAM_API_URL-gated) + Muse Image recap on completion; grounding wired into observation events; **PWA judge client** at `/pwa` (camera frames, talk, Wonder plan w/ prior_notes, review-then-Begin checklist, Jot/Obsidian badges, SAM overlay, Shh/presets); WS accepts `?token=` for browsers. Suite 186 passed. | `6d9e7cc` | **Bonus: Best Open Weights + Best Creative Build** (Muse Image/Muse Wonder); **Use of Meta AI Tooling 25%** (SAM 3.1); **Demo 25%** (judge panel usable without glasses). |
| 2026-10-27 (D2) | Vault keepalive confirmed; `:8770` relaunched detached on latest code, health OK; **LIVE smoke pass** (Wonder 7-step mission → real Jot note → 7 advances ticking Jot → Obsidian engram → prior recall → cleanup; WS "Jot down" note verified); PWA restyled to brand; `POST /v1/missions/{id}/recap`; `GET /overlay` OBS browser source (step/progress/LIVE/safety); confirm-gated `stream_start`/`stream_stop` (one-time token challenge, audited). Suite 189 passed. | `04b3875` | **Demo 25%** (OBS overlay + live smoke); **Impact 30%**; **process rule** (stream/hands actions confirm-gated + audited). |
| 2026-10-27 (git only) | Spotter **v0.2.5** — FIT preview (no skew), fullscreen + floating options menu, BT + string app-id fixes; then interview-then-plan, vision/memory-aware planning, held-frame flush, Muse roadmap. | `f51935f`, `2761e3c` | **Demo 25% / Technical Depth 20%** — recorded from commit subjects only; **not narrated in `HACKATHON-LOG.md`**. |
| 2026-10-28 | Spotter **v0.2.7** installed on Pixel 10 Pro (back on ADB): `adb install -r` over v0.2.6, Success; `dumpsys` confirms versionName 0.2.7 / code 9; launched clean, MainActivity top-resumed, PID alive. End-to-end relay/glasses flow still needs an in-app check by Neal. | `0eee786` | **Demo 25%** (verified on-device install); honesty about the remaining manual step. |
| 2026-10-28 | Glasses blocker **root-caused + fixed**: v0.2.7/v0.2.8 shipped `mwdat_app_id` as the literal `REPLACE_WITH_WEARABLES_APP_ID` placeholder (plain `assembleDebug`), so DAT could not register/session — the bridge/registration/permission/stream code was already correct. Fix: `build.gradle.kts` default `mwdatId = "0"` (DAT Developer Mode, overridable via `-PmwdatAppId`); rebuilt **v0.2.9** (versionCode 11), generated res verified `mwdat_app_id="0"`. Also v0.2.8: system-bar inset padding, "A FieldKit by NullAI" rebrand, Building FieldKits about page. **Install pending — Pixel unplugged.** | `dd531dc` (committed Oct 30; `build.gradle.kts` at HEAD defaults `mwdatId` to `"0"` — verified) | **Technical Depth 20%** (root-caused a build-config bug, not a code bug); **Demo 25%** (glasses path unblocked pending install). |

## Oct 30 — the public-tracking and voice day

| Date | What shipped | Commit | Why it mattered (judging dimension) |
|---|---|---|---|
| 2026-10-30 | **Voice, and names that never come from a face.** Relay: `people.py` descriptor store (0600 JSON) that can ask the wearer a yes/no about a re-sighting; `context.py` `FocusTracker` plus a two-stage wake grammar with an `honoured()` gate; `server.py name_person()` refuses any speaker who is not the wearer. Android: one-shot 16 kHz PCM16 capture, a VoicePrint that keeps spectral shape rather than level, the two-stage wake phrase behind a speaker gate, a foreground microphone service, the ◆ Name row. Four new test files. | `58f6c8f`, `dd531dc` | Impact 30% + the privacy invariant enforced in code rather than promised in a README: **Spotter must never identify people by face.** |
| 2026-10-30 | **Public tracking.** Day plan to Nov 4, `docs/PROGRESS.md` verified/in-flight/open split, `docs/GAMEPLAN.md`, GitHub labels + day milestones D5–D10 + 14 per-task issues; README timeline written from this repo's own history; the published `meta-dev-skills` repo linked from the docs. | `137f0cb`, `7cf1e55`, `546d7ca`, `dfc2e19` | Demo 25% — a judge can follow the build day by day without asking anything. |
| 2026-10-30 17:36 | **Heat-free Android source typecheck.** The packaged build is thermally blocked on this box (the guard aborts at 82 °C), so the Java tree is checked the cheap way: plain `javac` over all sources against `android.jar` plus the last resource pass's generated `R`, passing the classpath via an argfile. Result **0 errors, 45 classes**; negative control (injected bad call/type) exits **RC=1** with 2 errors, so the pass is not vacuous. **Explicitly not `assembleDebug`** — no AAPT2, dexing or manifest merge. | `4f84244` | Depth 20% — "technical execution" needs a tree that compiles; this says honest and specific where it stops. |
| 2026-10-30 17:54 | **It can hear with the screen off.** Background capture could not listen: Android's recogniser needs an Activity, so nothing read the microphone under a locked screen. The utterance state machine was split out of `VoiceCapture` into a pure-Java `UtteranceSegmenter` (no Android imports) and shared with a new screen-off loop `LockedListener`, which segments, checks the wearer's enrolled print and keeps a WAV — explicitly not transcribing. `SpotterService` owns the loop and reports kept vs. refused-as-not-you; the single microphone is handed between activity and service on purpose. One enrolled-print codec, so the gate cannot drift from the enrolment. **92 checks pass** (`tools/voicecore/run.sh`), which immediately caught trailing silence being counted as speech. | `0ff6f45` | Depth 20% + Demo 25% — hands-free only counts when the phone is in a pocket. |
| 2026-10-30 17:58 | Corrected the docs to say what the screen-off loop actually does (hears, gates, keeps a WAV, transcribes only when the app is next in front) instead of implying transcription in the background. | `7c483f4` | Credibility — a judge who tries the thing must find what the README said. |
| 2026-10-30 18:04 | **Logcat observability for the one path that cannot be tested off-device.** Both sides now log under `TAG "SpotterVoice"`: whether the microphone opened or who else holds it, how many utterances were discarded as not-you, each gated utterance's length and print score with kept-or-refused, and why the service is not listening. | `41156ae` | Depth 20% — an unobservable path is an unprovable one. |
| 2026-10-30 | **`tools/device-smoke.sh`** — one command for the first on-device run: refuses an APK older than the newest source, confirms from `dumpsys` that the service is foreground with the microphone bit set, has the wearer lock the phone and talk, reads the log back and asserts each claim, and `--pull DIR` copies the kept WAV off the phone so the audio can be heard. Verified only as plumbing (`bash -n`, `shellcheck -S warning`, no-device path exits RC=2): **no on-device pass is claimed.** | `5be2479` | Depth 20% + Demo 25% — turns "unproven on hardware" into a repeatable one-liner. |
| 2026-10-30 18:24 | **The kept audio reaches the recogniser when the app comes back.** A kept utterance was audio and nothing more: it sat in the cache while the app returned in front unaware of it. On resume the app now drains the cache directory oldest-first and hands each WAV to the recogniser through the same `EXTRA_AUDIO_SOURCE` path the Talk button uses, relaying each transcript with speaker `wearer` — the print gate already accepted that voice at the time, so re-testing it now would measure the wrong moment. The list is read from disk, not from the listener's memory, because the activity/service microphone handover drops the listener and not the audio; writer and reader share `KEPT_PREFIX`. Two guards: a kept utterance with no transcript must not fall through to the live recogniser, and it must not carry the camera descriptor of whoever is standing there now. Off-device: `tools/typecheck-android.sh` **0 errors / 45 classes** at 81 °C, `tools/voicecore/run.sh` **92/92**. **No on-device pass is claimed** — the drain needs a real recogniser. | `c50308f` | Depth 20% + Impact 30% — a pocket conversation that has evaporated by the time you take the phone out is not a feature. |

## Sibling repository

`NullAITech/meta-dev-skills` — eight agent skills for Meta AI tooling (Model API quickstart,
Muse Spark multimodal agents, SAM 3 segmentation, Muse Image + Wonder, Muse voice transcribe,
Muse Code agent, Meta open-weight models, Ray-Ban Meta wearables DAT). Public, MIT, commit
`970f5ef`, `pushedAt 2026-10-30T21:27:41Z` (re-checked with `gh repo view`). New work inside
the hackathon period; the Ray-Ban DAT flow inside it is flagged as general knowledge rather
than something the participant guide states.

## Still open, smallest first

- **On-glasses DAT session unverified** (first flagged Oct 26, still open Oct 30) — needs a
  real Wearables Developer Center `APPLICATION_ID`, Meta AI registration / Developer Mode,
  and the physical glasses. The Dev-Mode `"0"` id is already the build default (`dd531dc`).
- **Packaged Android build never landed on this revision** — `assembleDebug` is blocked by the
  desktop's own thermal guard (aborts at 82 °C; observed 82–83 °C). The tree is
  type-checked, not packaged. The APK on disk predates the newest source.
- **No on-device run of the screen-off path** — unit layer green (92/92), wiring unexercised.
  `tools/device-smoke.sh` exists to close exactly this gap; it has not been run.
- **The kept-utterance drain is type-checked, not exercised** — `c50308f` compiles and the
  shared segmenter passes 92 checks, but a drain needs a real recogniser: on the Pixel it should
  print one *"From while the screen was off: …"* per kept WAV, oldest first. Untested here.
- **Spotter v0.2.9 install pending** (since Oct 28) — the Pixel was unplugged before the
  install. v0.2.3's install was likewise pending on Oct 27.
- **DAT audio direction-of-arrival not implemented** — honestly staged as future work; sound
  direction ships as an on-device estimate (relative bearing + level).
- **Live relay/glasses first frame unconfirmed** — installs verified, interactive motion not.
- **Guide/FieldKit Android 0.5.0 + Live app source not recoverable on this machine** — a
  whole-box search found none; the server-side path and new client code substitute for it,
  but the standalone checkout is still absent.

## How to read the dates

Date is the log's own section date, which is not always the commit date: v0.2.9 is the
clearest case — built Oct 28, source committed Oct 30. Oct 29 is genuinely empty: no commits
and no log entries, because the phone was off ADB. Where a row's commit lives in a different
repository, it says so.
