# FieldKit by NullAI

**A hands-free mission coach for Ray-Ban Meta glasses.**

Say what you need to get done. FieldKit makes a short plan, watches what you are doing, and tells you the next step out loud — before you have to ask.

> You talk. It plans. It watches. It guides.

Built by **[NullAI](https://nullai.tech)**. Find the tools in **[ZothOS](https://zothos.nullai.tech)** and the studio at **[Zoth Studio](https://zoth.nullai.tech)**.

FieldKit is built for Ray-Ban Meta glasses. It is not affiliated with or endorsed by Meta.

---

## The moment we are building for

You are under a mower, hands greasy, filter half off. You should not need to stop, unlock a phone, and type a question.

With FieldKit, the coach speaks first:

> “Next: loosen the left clamp. Stop if the housing feels cracked.”

That is the product: not another chat box, but calm step-by-step help while your hands stay on the work.

## What FieldKit does

1. **You say the job.** One sentence is enough: “Replace the air filter.”
2. **It makes a simple plan.** A short checklist you can hear and follow.
3. **It watches the work.** Your glasses share what changed, not an endless video feed.
4. **It keeps you on step.** FieldKit knows the current step and what comes next.
5. **It speaks first when it matters.** A step is done, skipped, off track, or unsafe.
6. **It remembers the job.** Ask later: “What did I miss on step two?”
7. **It can go live, with permission.** The same session can feed captions or a stream only after you confirm.

## A normal example

**Goal:** “Swap the mower air filter.”

1. Park on flat ground and let the engine cool.
2. Clear grass and dirt from the cover.
3. Loosen the left clamp.
4. Lift the old filter out.
5. Seat the new filter.
6. Tighten, start, and listen.

FieldKit does not just list the steps. It follows along and moves with you.

## What was built during the hackathon

These parts are new for the Meta Global AI Developer Hackathon:

- **Muse Spark + Muse Wonder support** through a simple provider layer. Spark handles fast replies; Wonder handles the plan and review.
- **Sessions API** — a live connection for frames, voice, text, and events.
- **Smart frame use** — only changed scenes are sent onward, keeping replies steady.
- **Bounded memory** — the plan and current step stay in front; old chatter does not pile up.
- **Missions API** — one sentence becomes a checklist; steps can advance, skip, or be checked.
- **Typed step tracking** — the system records `step_done`, `off_track`, and `unsafe` as real events, not guesses scraped from prose.
- **It knows when a step is done** — the proactive Eyes → Advisor → Director coach judges each new view against the current step and speaks the next one itself. No Next button (kept only as a manual override).
- **Agent tools over MCP** — any coding agent can call `see_now`, `say`, `mission_status`, `advance_step`, `jot_field_note`, `jot_mission_sync`, and `recall_second_brain` against the same live session.
- **Hermes Jot live checklist** — creating a mission creates a Jot note; as steps complete, the checklist ticks itself off while your hands stay on the work. Say “jot down: …” and the observation lands on the note with its step context.
- **Obsidian Second Brain** — every mission compiles into a vault engram with a live progress record, and prior jobs are recalled before planning: “last time on step 3, you noted the left bolt was stripped.”
- **Muse Spark Open presets** — Meta's open-weight Spark (`meta-models/muse-spark-open-1026`) as first-class env-gated providers for Baseten (`BASETEN_API_KEY`) and Modal (`MUSE_OPEN_MODAL_URL`/`MODAL_API_KEY`), beside the Meta API default.
- **Web trial (PWA)** — judges without glasses open `/pwa` on the relay: camera, voice, mission planning with prior-job recall, live checklist with Jot + Obsidian sync badges, and the same proactive coach.
- **Grounding + recap (env-gated)** — SAM 3.1 step-target boxes (drawn in the web preview) and a Muse Image completion card saved into the Obsidian engram and noted on the Jot checklist; a labelled local card stands in until Muse Image is configured.
- **Remembered context** — a focus tracker holds what the job is about, and someone you named earlier comes back as a question, not a label: the coach asks, you answer, nothing is asserted on your behalf.
- **A face never names anyone** — Spotter must never identify people by face. A name exists only because the wearer said it out loud, it is written only together with a descriptor captured in the same moment, and a bystander can name nobody.
- **Hands-free voice gate** — a two-stage wake grammar (a rare anchor plus two or three ordered required tokens) sitting behind a speaker gate that learns the wearer's spectral shape, so the coach wakes for the person wearing it and not for the television.
- **Background capture under a locked screen** — an Android foreground service holds the microphone so coaching continues while the phone sits in a pocket. The read loop segments speech, checks the enrolled voice and keeps the utterance, and the app picks it up the moment it comes back to the front. Transcription waits for the screen on purpose: Android's recogniser needs an Activity, and pretending otherwise would be a silent failure.

What already existed is listed plainly in [PRIOR-WORK.md](PRIOR-WORK.md). The demo only claims the new work.

## How it works, in plain English

```text
Ray-Ban Meta glasses / phone
          │
          ▼
   FieldKit Relay  ── makes the plan with Muse Wonder
          │        ── replies fast with Muse Spark
          │        ── remembers the mission and current step
          ▼
Voice guidance, phone screen, live captions, agent tools
```

The relay is the quiet backbone. The user should never need to care about it. It keeps the conversation steady, sends only what changed, and records simple safety and activity logs without storing your prompts or images in the audit trail.

## Control and safety

FieldKit is designed around a simple rule: **watching is not the same as acting.**

- Helpful watching can be automatic.
- Public or hands-on actions need a clear “yes” first.
- Public actions are written to an audit log.
- You can pause coaching with a short command.
- A local backup brain can keep basic help available when the cloud is not.

## Status

Hackathon build, developer preview.

- **[docs/GAMEPLAN.md](docs/GAMEPLAN.md)** — the plan through the Nov 4 submission, ordered by the judging rubric.
- **[docs/PROGRESS.md](docs/PROGRESS.md)** — what is verified, what is in flight, what is open, and what is unknown.
- **[Timeline](#timeline--how-this-was-built)** below — how the build actually went, day by day, taken from the repo history and the working log.
- [ROADMAP-FOR-MUSE.md](ROADMAP-FOR-MUSE.md) — the earlier working roadmap this plan descends from.

Working today: the server backbone (providers, live sessions, missions with
interview-then-plan, typed step tracking with step history, the
Eyes/Advisor/Director guide trio, automatic step advance, vision-conditioned
planning, MCP agent tools, Hermes Jot sync and Obsidian Second Brain engrams,
tests, and live soak checks), the judge PWA at `/pwa` (camera or synthetic
test scenes, questions-before-plan, live Jot/Obsidian badges), and the Spotter
Android app (phone camera, QR pairing, plan-review-then-Begin missions, voice
in/out, compass and sound-direction estimates, Meta glasses via the Wearables
DAT video stream with Meta AI permission flow and held-frame flush). Still
being connected: live stream to X and the final demo film.

Repository history in this public repo is curated on purpose: the first commit is a frozen snapshot of what existed before the hackathon, tagged `baseline/pre-event-2026-10-26`; later commits show only the new FieldKit work, in order. That split is machine-checkable rather than a promise — `git diff baseline/pre-event-2026-10-26..main` lists exactly what the hackathon added. Internal provenance for the original working copy (which is not public) is preserved separately by NullAI.


## Timeline — how this was built

This is the build as it actually went, not as it was planned. Two sources: `HACKATHON-LOG.md`
(the working log, which records what was run and what was only intended, in that order — including
the days nothing landed) and this repo's commit history. Kickoff was **Mon Oct 26 2026, 12:00 PM ET**;
the official building period runs **Oct 27 – Nov 4**. Everything before kickoff was prep only, with
no entry features built.

| Date | What happened | Commits | What it is for |
| --- | --- | --- | --- |
| **Oct 25**, pre-kickoff (~11 PM ET) | Repos located, rules pinned, baseline suite run recorded (129 passed, 2 pre-existing environment-detection failures), prior-work skeleton written. Prep only, as the pre-kickoff rules require. | — | Puts the prior/new line down before the clock starts. |
| **Oct 26**, D1 kickoff (12:52 PM ET) | Baseline imported as the repo's first commit, frozen. Provider layer for Muse Spark and Muse Wonder (two thin transports, preset routing, no litellm). Sessions and Missions stubs added, honestly returning not-implemented. First live Spark turn against a synthetic red frame verified: "Solid bright red fills the entire frame." | `7f5f463`, `afd1e70`, `3d122a9` | Meta tooling 25% — the product runs on Meta models from the first day, not bolted on at the end. Depth — seams named before they are filled. |
| **Oct 26**, D2 Sessions API | Real Sessions API: handshake with provider routing, speech and text turns, SHA-256 frame diff so only changed scenes travel, clip attach, barge-in that cancels the active turn, bounded context (cached system+mission prefix plus a char-capped transcript), per-turn latency metrics. A 60-turn live soak recorded first-token p50 ≈ 1.27 s on Spark, and turn 60 answered as fast as turn 10. | `13a2336` | Depth 20% + demo 25% — steady replies on a live wire, measured rather than asserted. |
| **Oct 26**, D3 Missions API | One sentence becomes a typed checklist: Muse Wonder structured planner with a tolerant parse and a cap of 12 steps; `step_done` / `off_track` / `unsafe` are typed events, not regex over prose. | `db1706b` | Impact 30% — this is the product: it knows which step you are on. |
| **Oct 26**, public repo + rebrand | Published as **FieldKit by NullAI** with a curated fresh history, a README written for normal people, `BRAND.md`, and `PRIOR-WORK.md` stating plainly what existed before. Tracked files secret-scanned before the first push. | `3539f12` | Rules compliance — a public repo and a declared prior/new split, answered on day one instead of at submission. |
| **Oct 26**, Android v0.1.0 | Phone client: sessions WebSocket, Camera2 frames, speech in, TTS out, mission controls — then the white Meta-style redesign, a Phone/Glasses camera-source toggle, and QR pairing. Installed on the Pixel and launched clean. | `7a27ed8`, `f4512f5` | Demo 25% — it runs on hardware, not in a diagram. |
| **Oct 26**, glasses on the real path | Meta Wearables DAT (the public SDK; class signatures confirmed with `javap` before coding): Kotlin bridge for registration, session, and camera frames to the relay. Build ~41 MB with the DAT native libs. | `f55d581` | Meta tooling 25% — the actual wearable SDK, not a stand-in. |
| **Oct 26**, Spotter + guide trio | Renamed Spotter, and given a braille eyes-view: luminance to Bayer 4x4 ordered dither to braille 2x4 cells, live from phone and glasses frames, plus a real compass heading. The trio arrives — Eyes (deduped observations), Advisor (typed suggestions), Director (the only voice that decides to speak; safety always speaks). | `46fa057` | Depth 20% + demo 25% — deciding *when* to talk is the hard half of a coach. |
| **Oct 26**, trio wired live | Wired into live sessions: a 2.5 s proactive loop over changed frames, presets (Safety First / Get It Done / Teach Me / Social), pause and shh, and voice focus where bystander speech is counted as `ignored_outside` and never sent to any model. | `2c67626` | Depth + privacy — an unlabelled voice is not an invitation to listen. |
| **Oct 26**, sound direction | Direction estimate: cross-correlation on 48 kHz stereo plus level, smoothed, with mic-layout variance documented; speech more than 75° off-axis is treated as a bystander. Render polish: Photo, Braille and ASCII views. | `dd98d5e` | Depth 20% — an estimate is labelled as an estimate. |
| **Oct 26**, two corrections that mattered | The dither/ASCII art was pulled out of the camera story (it is app design, not something to draw over the user's view), and the DAT video stream was found never started (`Stream.start()` missing) — so glasses could report "connected" while showing nothing. The bridge now reports streaming only after the first decoded frame. | `8f04f71`, `2a2e400` | Depth 20% — "connected" without frames is a lie the code was telling. |
| **Oct 26**, agent tools | FieldKit MCP tools live — `see_now`, `say`, `mission_status`, `advance_step` — sharing one mission store with REST and WS, so any agent can drive the same live session. DAT self-diagnostics added so "no eligible device" is distinguishable from a code bug. | `513178c`, `c953fe4` | Meta tooling 25% + the agentic bonus path. |
| **Oct 27**, v0.2.3 | Meta AI camera-to-microphone permission flow, plan-review-then-Begin missions (no default plan), automatic step advance in the proactive loop, with the Next button demoted to a manual override. | `6afdc6b` | Impact 30% — the coach moves with you instead of waiting for a tap. |
| **Oct 27**, memory that survives the job | Hermes Jot plus an Obsidian Second Brain: a mission creates a real Jot note whose checklist ticks as steps complete, and every mission compiles into an Obsidian engram with a live progress record. Prior jobs are recalled before planning — "last time, on step 3, the left bolt was stripped." | `930094b` | Impact 30% — the difference between a demo and a tool. |
| **Oct 27**, prize coverage, done honestly | Baseten and Modal as first-class Spark Open presets (open weights), SAM 3.1 step-target boxes, a Muse Image completion card — each env-gated with a labelled local stand-in until configured — plus a judge PWA at `/pwa` with synthetic test scenes for judges who have no camera. | `6d9e7cc` | Meta tooling 25%, and the open-weights and creative-build bonus paths. |
| **Oct 27**, live-mode gate | `POST /v1/missions/{id}/recap`, an OBS browser-source overlay, and confirm-gated `stream_start` / `stream_stop` behind a one-time token challenge and an audit entry. PWA restyled to brand (obsidian `#06070a`, gold `#e5c058`). | `04b3875` | Impact 30% + safety — public actions need a yes, and the yes is recorded. |
| **Oct 27**, v0.2.5 and the planner interview | FIT preview with no skew, fullscreen and floating options menu, Bluetooth and string app-id fixes. Then the planner learns to interview first: questions before a plan, vision- and memory-conditioned planning, held-frame flush. Test expectation updated for the new MCP tool-set. | `f51935f`, `244ac79`, `2761e3c` | Depth 20% — plan quality is a product decision, not a prompt accident. |
| **Oct 27**, v0.2.7 | Braille retro design pass and accessibility work. | `0eee786` | Demo 25% — it has to look like something on a stage. |
| **Oct 28** | v0.2.7 installed on the Pixel 10 Pro over v0.2.6: `dumpsys` shows versionName 0.2.7 / code 9, launched clean, MainActivity top-resumed. | — | Demo 25% — the build on the phone is the build in the repo. |
| **Oct 28**, the glasses blocker, root-caused | v0.2.7 and v0.2.8 were built with a plain `assembleDebug`, so `mwdat_app_id` shipped as the literal `REPLACE_WITH_WEARABLES_APP_ID` placeholder and DAT could never register — the bridge and session code were already correct. Fixed: `mwdatId` defaults to `"0"` (DAT Developer Mode, `-PmwdatAppId` to override); v0.2.9 rebuilt and the generated resource verified as `mwdat_app_id="0"`. | `dd531dc` (committed Oct 30) | Meta tooling 25% — the real reason glasses were dead, found in the artifact instead of guessed at. |
| **Oct 29** | Quiet day. No commits and no log entries: the phone was off ADB. | — | An honest timeline shows the gaps too. |
| **Oct 30**, remembered context, never by face | `people.py` descriptor store (0600 JSON) that can ask the wearer a yes/no about a re-sighting; `context.py` FocusTracker plus a two-stage wake grammar with an `honoured()` gate; `server.py name_person()` — a name is written only from the wearer's own words and only alongside a descriptor captured in the same turn, so a bystander can name nobody. Four new test files. | `58f6c8f` | Impact 30% + the privacy invariant, enforced in code rather than promised in a README: **Spotter must never identify people by face.** |
| **Oct 30**, hands-free voice and background capture | One-shot 16 kHz PCM16 capture; a VoicePrint that keeps spectral shape rather than level, with a self-calibrated threshold; the two-stage wake phrase (rare anchor plus 2–3 ordered tokens) behind a speaker gate; and an Android foreground microphone service so coaching survives a locked screen. The phone UI gains the voice tap and the ◆ Name row. | `dd531dc` | Demo 25% + depth 20% — hands-free with the screen off. The voice gate is scoped as a convenience, never authentication. |
| **Oct 30**, public tracking | `docs/GAMEPLAN.md`, `docs/PROGRESS.md`, GitHub labels, day milestones D5–D10 and 14 per-task issues; repo confirmed public and MIT; Android build output and the machine-local SDK path excluded from history. | `137f0cb` | Demo 25% — a judge can follow the build day by day without asking us anything. |
| **Oct 30**, it can hear with the screen off | Background capture was built but could not listen: Android's recogniser needs an Activity, so nothing was reading the microphone under a locked screen. The utterance state machine was split out of `VoiceCapture` into a pure-Java `UtteranceSegmenter` (no Android imports) and shared with a new screen-off loop, `LockedListener`, that segments, checks the wearer's enrolled print and keeps the utterance as a WAV — explicitly not transcribing. `SpotterService` now owns that loop and reports what it kept versus refused as not-you; the single microphone is handed between activity and service on purpose. Both sides share one enrolled-print codec so the gate cannot drift from the enrolment. | `0ff6f45` | Depth 20% + demo 25% — hands-free only counts when the phone is in a pocket. A new Android-free test harness (92 checks, `tools/voicecore/run.sh`) immediately caught trailing silence being counted as speech. |

### Still open as of Oct 30

- **No frame has been seen on the glasses yet.** v0.2.9 is built and its generated resource is verified, but the Pixel was unplugged before the install: `adb install -r` → Meta glasses mode → Connect glasses → approve in Meta AI → first frame. That is the next hands-on step.
- **The newest APK predates the newest source.** The last `MainActivity.java` edit is newer than the APK on disk, so treat the Oct 30 Android commit as source-verified, not build-verified, until the next `assembleDebug` lands. Python side is verified now: **241 passed**.
- **`SpotterService` now drives the voice path, but it has never read a real microphone.** The screen-off loop, the segmenter it shares with the screen-on capture and the enrolled-print codec are all covered off-device (**92 checks, RC=0**, `tools/voicecore/run.sh`) and the whole Java tree type-checks clean, but the wiring has not been exercised on the Pixel: enrol a voice, lock the screen, talk, unlock — the status line should report *"While the screen was off: …"* with a kept-versus-refused count.
- **The phone speaks to WS `/v1/sessions`**, not the newer `/v1/guide/turn` REST path. Both share one store and one naming rule, so this is a wiring choice, not a fork.
- **The 3-minute demo film does not exist yet.** It is a primary judging artifact.
- **The live-stream path is confirm-gated and audited but has never been driven from the glasses.**

### How to read this

- The frozen pre-hackathon baseline is the repo's first commit, `7f5f463`, tagged `baseline/pre-event-2026-10-26`. `git diff baseline/pre-event-2026-10-26..main` lists exactly what the hackathon added. (In the private original working copy the same baseline is `778cb33` / `ad37ac2`.)
- Some rows describe work performed at runtime and committed later — v0.2.9 is the clearest case: built Oct 28, source committed Oct 30. The commit column names the commit, with the date when it differs.
- The last column says what a piece of work is *for*. It is not a claim that judges will score it that way.

## Where to find us

- Company: **[NullAI](https://nullai.tech)**
- Studio: **[Zoth Studio](https://zoth.nullai.tech)**
- Tools home: **[ZothOS](https://zothos.nullai.tech)**
- Meta dev skills (published during the hackathon): **[NullAITech/meta-dev-skills](https://github.com/NullAITech/meta-dev-skills)** — eight agent skills for Meta's AI tooling: Model API, Muse models, open weights, Muse Code, Wearables DAT.

Built by NullAI Tech. Working codename during development: **ZOTH FIELDKIT**.
