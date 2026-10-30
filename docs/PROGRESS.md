# PROGRESS.md — Zoth Fieldkit build tracking

Live status board for the **Meta Global AI Developer Hackathon** (indie track, solo).
Event: kickoff Mon Oct 26 12:00 PM ET → **submission Wed Nov 4, 9:00 PM ET** (target 6:00 PM).
Last updated: **Fri 2026-10-30**.

How to follow along, in order of freshness:

| Surface | What it holds | Cadence |
|---|---|---|
| **GitHub milestones + issues** | Every concrete task for the remaining days, with owners and due dates | Updated as work lands |
| **This file** (`docs/PROGRESS.md`) | Verified state, what's in flight, what's open | Each working session |
| `HACKATHON-LOG.md` (repo parent dir) | Raw day-by-day build log, unedited | Continuous |
| `README.md` → Timeline | The judge-facing story of what shipped, with commits | On milestones |

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
| Spotter v0.2.9 debug APK built with a real DAT app id (`mwdat_app_id="0"`, Developer Mode) | `apks/Spotter-0.2.9-debug.apk`; generated `res` inspected before build | Oct 28 |
| Glasses-streaming root cause found and fixed in code | v0.2.7/0.2.8 shipped the literal `REPLACE_WITH_WEARABLES_APP_ID` placeholder, so DAT could never register; `build.gradle.kts` default is now `"0"` | Oct 28 |
| Guide trio (Eyes / Advisor / Director) wired into live sessions | commits `2c67626`, `46fa057` | Oct 26 |
| Sessions API (WS) + Missions API with structured planner and step tracker | commits `13a2336`, `db1706b`; live smoke passed Oct 27 | Oct 27 |
| Confirm-gated Live Mode + OBS overlay + Muse Image / SAM 3.1 recap path | commit `04b3875`, recap endpoint in `recap.py` | Oct 27 |
| MCP agent tools `see_now` / `say` / `mission_status` / `advance_step` | commit `513178c` | Oct 26 |
| Python relay test suite | **240 passed** at last confirmed full run (`/tmp/all6.log`) | Oct 30 |

---

## IN FLIGHT

| Item | State |
|---|---|
| Full pytest re-run after the Oct 30 naming-path changes | Running under a thermal guard; result lands in `/tmp/build-verify.log`. 240 was the last confirmed count. |
| Android `assembleDebug` after `VoiceCapture.java`, the `MainActivity` voice/person tap paths, and the new layout rows | Same guarded run. **Last confirmed green build predates `VoiceCapture.java`** — treat the build as unverified until that log says `GRADLE_EXIT=0`. |
| Person-naming loop (`name_person` + `◆ Name` button + live test) | Python side verified green; Android side rides on the build above. |

---

## OPEN

| Item | Impact | Notes |
|---|---|---|
| **Glasses first frame never observed end-to-end** | Highest — it is the demo's opening shot | Code path is correct; needs the Pixel plugged in, `adb install -r apks/Spotter-0.2.9-debug.apk`, then Meta glasses mode → Connect → approve in Meta AI. Blocked purely on device access. |
| Relay `:8770` state after the vault keepalive change | Blocks live smoke tests | It was relaunched and healthy on Oct 27; **not re-checked since**. Confirm with a health probe before relying on it. |
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
| Fri Oct 30 | Confirm the build; commit and push the Oct 30 delta; get the timeline and tracking public | Depth 20% — a build that visibly compiles is table stakes for "Technical Execution" |
| Sat Oct 31 | Plug in the Pixel, install v0.2.9, **get the glasses first frame** | Demo 25% + Impact 30% — the product is the glasses |
| Sun Nov 1 | PWA judge path so the demo works without glasses; round out Muse tooling surface | Impact 30% + Demo 25% |
| Mon Nov 2 | Film the 3-minute demo | Demo 25% |
| Tue Nov 3 | README/architecture pass, prior-vs-new clarity, X clip | Tooling 25% + $20K viral bonus + protects against a DQ |
| Wed Nov 4 | Submit by 6:00 PM ET (hard stop 9:00 PM) | — |
