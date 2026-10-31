# PROGRESS.md — Zoth Fieldkit build tracking

Live status board for the **Meta Global AI Developer Hackathon** (indie track, solo).
Event: kickoff Mon Oct 26 12:00 PM ET → **submission Wed Nov 4, 9:00 PM ET** (target 6:00 PM).
Last updated: **Fri 2026-10-30, very late** (the relay now survives a flaky upstream — `f3c6f1b`; the judge web trial's whole loop was re-driven with no hardware attached and both sides of it read out of the page's own log).

How to follow along, in order of freshness:

| Surface | What it holds | Cadence |
|---|---|---|
| **GitHub milestones + issues** | Every concrete task for the remaining days, with owners and due dates | Updated as work lands |
| **This file** (`docs/PROGRESS.md`) | Verified state, what's in flight, what's open | Each working session |
| `HACKATHON-LOG.md` (repo parent dir) | Raw day-by-day build log, unedited | Continuous |
| `README.md` → Timeline | The judge-facing story of what shipped, with commits | On milestones |
| `docs/TIMELINE.md` | That timeline with every commit hash checked against this repo (`git cat-file -e`), the relay-mirror hashes marked, and the open blockers listed smallest-first | On milestones |

---

## Status legend

- **VERIFIED** — I ran it and read real output. Evidence line included.
- **IN FLIGHT** — work started, outcome not yet confirmed.
- **OPEN** — known and unresolved.
- **UNKNOWN** — not checked recently; do not assume either way.

---

## VERIFIED

| Item | Evidence | Date |
|---|---|---|
| Repo is **PUBLIC** and MIT-licensed — hard submission requirement | `gh repo view NullAITech/nullai-fieldkit` → `visibility: PUBLIC`, `licenseInfo: MIT` | Oct 30 |
| The debug APK on disk carries a real DAT app id, not the placeholder | `grep -a -c 'REPLACE_WITH_WEARABLES_APP_ID' android/app/build/outputs/apk/debug/app-debug.apk` → **0**; APK 41,619,990 bytes, built **Oct 30 17:05**, newer than `build.gradle.kts` (16:33) whose default is `"0"` | Oct 30 |
| Glasses-streaming root cause found and fixed in code | v0.2.7/0.2.8 shipped the literal `REPLACE_WITH_WEARABLES_APP_ID` placeholder, so DAT could never register; `build.gradle.kts` default is now `"0"` | Oct 28 |
| Guide trio (Eyes / Advisor / Director) wired into live sessions | commits `2c67626`, `46fa057` | Oct 26 |
| Sessions API (WS) + Missions API with structured planner and step tracker | commits `13a2336`, `db1706b`; live smoke passed Oct 27 | Oct 27 |
| Confirm-gated Live Mode + OBS overlay + Muse Image / SAM 3.1 recap path | commit `04b3875`, recap endpoint in `recap.py` | Oct 27 |
| MCP agent tools `see_now` / `say` / `mission_status` / `advance_step` | commit `513178c` | Oct 26 |
| **Meta dev skills published as its own public repo** (new work, hackathon period) | `gh repo view NullAITech/meta-dev-skills` → `visibility: PUBLIC`, MIT; commit `970f5ef`; 8/8 SKILL.md frontmatter valid, manifest matches disk | Oct 30 |
| Android **Java source type-checks** on the current tree — including the 17:09 `MainActivity.java` edit the APK predates | `tools/typecheck-android.sh` → **TYPECHECK OK, 0 errors, 45 classes** (1007 classpath jars, under `nice`). Negative control: an injected bad call + bad type in a scratch copy exits RC=1 with 2 errors, so the pass is not vacuous. **This is not `assembleDebug`** — no AAPT2, dexing, manifest merge or packaging. | Oct 30 |
| **Screen-off listening works at the unit level** — the microphone read loop, the utterance segmenter it shares with the screen-on path, and the enrolled-print codec | `bash tools/voicecore/run.sh` → **92 passed / 0 failed** (VoiceCoreTest 41, SegmenterCheck 31, OwnerPrintCheck 20), RC=0. Android-free: no device, no SDK, no emulator. Writing the segmenter harness found a real bug — trailing silence counted as voicing, so a 200 ms bang plus quiet passed the 400 ms minimum; `voicedMs()` now counts only blocks at or above `STOP_DB`. | Oct 30 |
| Python relay test suite, current tree | **247 passed**, 1 warning, 21.06 s — `.venv/bin/python -m pytest -q` (pre-retry baseline was 241; the two runs that followed the retry work: 21.06 s and 20.88 s, both exit 0) | Oct 30 |
| `tools/device-smoke.sh` exists and its own plumbing is sound | `bash -n` OK; `shellcheck -S warning` clean; `--help` prints the header; with no phone attached it exits **RC=2** with `no device: adb sees no attached phone in the 'device' state`. Steps 2–6 (stale-APK refusal, install, `dumpsys` foreground/mic-bit check, `logcat` assertions, WAV pull) are **unexercised until a phone is attached** — the script is written against the literal `SpotterVoice` log strings read out of the source, not invented ones. | Oct 30 |
| **A kept screen-off utterance now becomes a transcript** | `c50308f`: `LockedListener.keptOnDisk(ctx)` lists the kept WAVs straight off the cache directory (the service drops the listener when the app comes to the front, not the audio) and `MainActivity.drainKeptUtterances()` sends them to the recognizer one at a time on resume, relaying each with speaker `wearer`. Two guards a kept utterance needs and now has: it never falls through to the live recognizer when a transcript is missing, and it never carries the descriptor of whoever is in front of the camera now. Off-device evidence: `tools/typecheck-android.sh` → **0 errors, 45 classes** (at 81 °C), `tools/voicecore/run.sh` → **92 passed / 0 failed**. The drain itself needs a real recognizer, so it is unverified until the Pixel is back. | Oct 30 |
| `meta-dev-skills` is live on GitHub | `gh repo view NullAITech/meta-dev-skills` → `visibility: PUBLIC`, `licenseInfo: MIT`, `pushedAt 2026-10-30T21:27:41Z`; local == `origin/main` == `970f5ef`; 8 SKILL.md + manifest + idempotent INSTALL.sh | Oct 30 |
| **The judge path drives the whole Eyes → Advisor → Director loop with no camera, no phone and no glasses** | `pwa_journey_verify.py` — this agent's own headless Chrome over CDP on a private port, loopback only — against the live relay at `http://127.0.0.1:8770/pwa`: the page reported itself ready (`complete\|function\|function`), the socket went `new → open → ready → observation → say → start`, `#status` read **`Connected · spark · guide on · preset get-it-done`** for the whole 15 s sample, and the page's own log showed **both sides** of the loop — `Eyes: Workbench with mower visible, cover on and dirty air filter still on.` and `Coach: Don't blow it out, that dust is bad to breathe.` A typed turn got a spoken coach reply, and Goal → Plan returned **7 typed steps** ending *"Planned with Wonder. Nothing starts until you say Begin."* **0 console errors, 0 responses ≥ 400.** `PWA_JOURNEY=ok`, exit 0. | Oct 30 |
| **A flaky upstream no longer kills the judge's demo or goes quiet on the wearer** | `f3c6f1b`: `missions._complete` and `trio._complete` retry the provider stream **once**, and only when zero deltas were collected, so streamed text can never duplicate; the web trial retries a 5xx Plan tap once with a "planner busy" status. **247 passed** (was 241). The five retry tests are proven non-vacuous: with the retry code stashed (`git stash push -- zoth_relay/missions.py zoth_relay/trio.py`) exactly those five fail, and they pass with it restored. | Oct 30 |

---

## IN FLIGHT

| Item | State |
|---|---|
| Android `assembleDebug` on the current tree | Still the one gap in the packaged build. The first guarded attempt was SIGTERM-killed before gradle reported anything, and the box then sat at 77–82 °C, so its own guard refused the load; a second guarded run (`guarded-verify2.sh`) was launched that waits for the box to fall below 74 °C, runs the suite, refuses the build at ≥ 82 °C, and logs `GRADLE_EXIT` plus the APK path, size and mtime. Evidence of the gap it is closing: the debug APK is dated **17:05**, `MainActivity.java` **17:09**, so the APK predates the newest source. **Mitigated:** the full Java tree type-checks clean (`tools/typecheck-android.sh`, 0 errors, 45 classes). What is still unproven without it is resource linking, dexing and manifest merge — the current revision is **type-checked, not packaged**. |
| Person-naming loop (`name_person` + `◆ Name` row) | Python side verified green (247 passed). The Android half rides on the build above. |
| **Screen-off listening on a real device** | The unit layer is green (92/92) and the whole tree type-checks, but the loop has never read a real microphone: no on-device run of `LockedListener` yet. What it must show on the Pixel: enrol a voice, lock the screen, talk, unlock — the status line should say *"While the screen was off: …"* with a count of what it kept versus refused as not-you. Also unproven on hardware: the microphone handover between the activity (`setListening(false)` on resume) and the service (`setListening(true)` on pause) — a second `AudioRecord` on this device does not fail loudly, it errors from `read()`, so the ordering matters. The first on-device run is one command — `tools/device-smoke.sh` — which refuses a stale APK, checks the foreground type mask from `dumpsys`, has the wearer lock the phone and talk, then reads the log back and asserts each claim; `--pull` fetches the kept WAV so the audio itself can be heard. The drain that turns a kept WAV into a transcript exists as of `c50308f` but has only ever run against a type-check: on the Pixel the second half of the loop should show *"From while the screen was off: …"* for each kept utterance, in order, oldest first. |

---

## OPEN

| Item | Impact | Notes |
|---|---|---|
| **Glasses first frame never observed end-to-end** | Highest — it is the demo's opening shot | Code path is correct; needs the Pixel plugged in, `adb install -r android/app/build/outputs/apk/debug/app-debug.apk` (build output is gitignored, so it lives only on this machine), then Meta glasses mode → Connect → approve in Meta AI. Blocked purely on device access. |
| Relay `:8770` runs code older than the retry patch | Low, but it is the difference between the fix existing and the fix running | The live relay (PID 1288782) started **Tue Oct 27 09:30:34**; `zoth_relay/missions.py` and `zoth_relay/trio.py` were last written **Oct 30 18:56:52**. The server-side single retry (`f3c6f1b`) is therefore **not active in the running process** — the page's own client-side retry is (it is served from disk per request, which is why the log-pane edits did show up). It was left running on purpose rather than restarted out from under the wearer; restart with the process's own command line, then re-probe: `.venv/bin/python -m zoth_relay --config /home/zoth/Desktop/Zoth-Meta-Hackathon/relay-runtime/config.yaml serve`. Re-checked Oct 30: `/pwa` → 200, WS handshake answered `ready`, live observations arriving. |
| 3-minute demo video | 25% of the score, and the primary judging artifact | Not started. Uncut take, MCP beat, prior-vs-new honesty slide. |
| $150 Meta Model API credits | Gates continued development against the real API | Claim form due Nov 4. Card must be on the account; set the spend-limit alert. |
| X build-in-public clips | $20K Most Viral bonus, engagement counted through Nov 8 | Not started. |

---

## UNKNOWN — verify before quoting

- Current relay latency numbers (a 60-minute flat-latency soak was planned, never run).
- Whether Muse Code / Claude / Cursor MCP calls have been exercised against the live relay from a second machine.
- Real-world tail of the cost curve on Muse Spark with the prefix-caching path.

---

## Design invariant (do not break this)

**Spotter never identifies a person by face.** A name exists in the store only because the
wearer typed or said it, and it is only ever written together with a descriptor the wearer
just captured. A face can at most prompt a yes/no question. A bystander's voice can never
name anyone. This is enforced in `zoth_relay/people.py` and `zoth_relay/server.py`
(`name_person` refuses any speaker that is not the wearer) and covered by tests.

---

## Remaining days, weighted by the judging rubric

Rubric: **Impact 30% · Use of Meta AI Tooling 25% · Technical Depth & Execution 20% · Demo 25%.**
Stage 1 is pass/fail on theme fit plus actually using Meta models — the repo clears that today.

| Day | Priority | Why it moves the score |
|---|---|---|
| ~~Fri Oct 30~~ **done** | Python suite green (241 passed), source committed, labels + milestones D5–D10 + 14 issues public, README timeline written from the repo history. Left over: the Android build. | Depth 20% — a build that visibly compiles is table stakes for "Technical Execution" |
| Sat Oct 31 | Plug in the Pixel, install v0.2.9, **get the glasses first frame** | Demo 25% + Impact 30% — the product is the glasses |
| Sun Nov 1 | PWA judge path so the demo works without glasses; round out Muse tooling surface | Impact 30% + Demo 25% |
| Mon Nov 2 | Film the 3-minute demo | Demo 25% |
| Tue Nov 3 | README/architecture pass, prior-vs-new clarity, X clip | Tooling 25% + $20K viral bonus + protects against a DQ |
| Wed Nov 4 | Submit by 6:00 PM ET (hard stop 9:00 PM) | — |
