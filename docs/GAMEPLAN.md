# GAMEPLAN — ZOTH FIELDKIT (Meta Global AI Developer Hackathon)

**Entry:** FieldKit by NullAI (working codename ZOTH FIELDKIT) · indie track · solo builder "Zoth".
**Deadline:** submit via Cerebral Valley by **Wed Nov 4, 9:00 PM ET** (hard). Working target **6:00 PM ET**.
**Goal (explicit):** a **top-4 paid indie placement** (4th = $50K). 5th pays $25K; top-5 in the track also reaches Stage-3 finals (Mon Nov 9).
**Written:** Fri Oct 30, 2026 (ET). Supersedes the kickoff-day roadmap *ROADMAP-ZOTH-FIELDKIT.txt*.

All times **ET**. Lines tagged **(judgement)** are my call, not a doc quote. Everything else traces to
`DETAILS-meta-hackathon.txt`, `RULES-NOTES.md`, `PRIOR-WORK.md`, `HACKATHON-LOG.md`, the product docs
(`README.md`, `ROADMAP-FOR-MUSE.md`, `MOBILE.md`, `android/DAT-SETUP.md`), or the judging rubric.

---

## 0. What changed since the kickoff roadmap (read this first)

The kickoff roadmap (Oct 26) planned a straight build: provider → Sessions → Missions → proactive → MCP →
SAM/PWA/Live, all inside D1–D9. Reality ran **ahead on the server and behind on proofs.** The whole server
stack is already shipped; what is *not* shipped is the part that actually scores: a **verified build**, a
**proven on-glass or on-phone run**, the **60-min bench**, and **the 3-minute film.**

| Kickoff roadmap said | What actually happened (by Oct 28) | What this plan does |
|---|---|---|
| Build provider/Sessions/Missions across D1–D4 | Done early: provider layer, Sessions WS, Missions REST, trio, Jot/Obsidian, MCP, Live gate, PWA all committed | Stop building; **switch to verify + package + film** |
| Glasses first frame mid-week | **Still blocked** on Meta-side DAT gate (Dev Mode / dev-app listing / glasses WiFi) | Demote glasses to *story*, make **phone camera the demo path** |
| Android "rebuild" assumed working | v0.2.9 built but **not installed**; **build currently UNVERIFIED** | Make **"verify the build passes" a first-order task, day one** |
| SAM / PWA / Live as D6 fill | Mostly shipped via env-gated recap + `/pwa` + `/overlay` | Prove them once; don't gold-plate |
| Film on D8–D9 | Not started | Film **Mon Nov 2**, with a fallback take on Sun Nov 1 |
| Bench on D7 | Not run | Run **Sun Nov 1**, graph into README |

**Thesis of the back half:** the entry is ~80% built and ~30% *proven*. A top-4 finish is won in the last
55% of the rubric — **Impact 30% + Demo 25%** — which are won by a clean run and a clean film, not by one
more feature. **(judgement: freeze features, protect the film.)**

---

## 0b. Status delta — what actually landed on Fri Oct 30 (end of day)

D5's stated priority was "verify the build passes; unblock glasses". What actually happened: **the
visibility work got done and the build did not.** That is the honest delta, and D6 opens with it.

**Landed and verified today:**

- Repo confirmed **PUBLIC + MIT** (`gh repo view`) — the submission gate is closed, not pending.
- **Baseline frozen as an artifact, not a promise:** the tag `baseline/pre-event-2026-10-26` points at
  the repo's **first commit** (`7f5f463`) and is pushed to origin, so
  `git diff baseline/pre-event-2026-10-26..main` is exactly the hackathon's work. This is what settles
  the kickoff-date disagreement below.
- **Tracking live on GitHub:** 7 labels, milestones **D5–D10**, 14 per-task issues, plus
  `docs/PROGRESS.md` as the verified-state board (three separate surfaces on purpose: issues answer
  "what's next", PROGRESS answers "what is true", `HACKATHON-LOG.md` keeps the raw record).
- **README timeline** written from the repo's own history (`git log --all`, 26 commits): 24 dated rows,
  each traceable to a commit or a `HACKATHON-LOG.md` section. Where the event documents disagree
  (kickoff header Oct 26 12:00 PM ET vs the stated building period Oct 27 – Nov 4) the README states
  both and points at the tag instead of choosing a convenient one.
- **Python relay suite: 241 passed**, 1 warning, 21.16 s on the current tree
  (`.venv/bin/python -m pytest -q`). Up from the 189/240 in earlier notes.
- Oct 30 source committed in **three coherent commits with explicit paths**, so no build junk entered
  history.

**Not landed, and it matters:**

- **The Android build.** A thermal-guarded verify-and-build was queued, logged `waiting:` lines for
  hours, never fired (the user's desktop pins the package at 77–82 °C and the guard aborts at ≥82 °C)
  and was then stopped. The APK on disk is dated **17:05**; `MainActivity.java` is **17:09** — the APK
  predates the newest source, so v0.2.9 is still unverified **and** uninstalled.
- **Partial mitigation that did land (late Oct 30):** with the packaged build thermally blocked, the
  Android **source** was type-checked heat-free instead. `tools/typecheck-android.sh` runs plain `javac`
  over all 11 Java sources against the SDK `android.jar`, the last resource pass's generated `R`, the
  the 1007 cached dependency jars, and the compiled Kotlin `DatGlasses` class → **0 errors, 38 classes**
  (re-run after the voice/segmenter files landed: **0 errors, 45 classes**, still RC=0).
  An injected bad call/type in a scratch copy returns RC=1 with 2 errors, so the pass carries real
  signal. What it does **not** cover: AAPT2, dexing, manifest merge, packaging — it does not replace the
  `assembleDebug` below, it only means the newest Java edits are known type-correct going in.
- **Glasses first frame:** still unproven. Unchanged from yesterday.

**Therefore D6 opens in this order:** one clean `assembleDebug` on a cool box → confirm
`REPLACE_WITH_WEARABLES_APP_ID` is absent from the merged manifest → install to the Pixel → glasses
Connect / first frame → the phone-camera Mission Mode take, which is the take that matters even if the
glasses never land.

---

## 0c. Status delta — Fri Oct 30, later the same night (supersedes 0b's "therefore")

0b ended with "D6 opens with one clean `assembleDebug`." That is still the right *first* move
but it cannot be the *only* one, because the box is what is blocking it. What changed after 0b
was written:

**Landed and verified (all pushed to `origin/main`):**

- **The typecheck is no longer a late-night mitigation, it is instrumented tooling.**
  `tools/typecheck-android.sh` now reads `x86_pkg_temp` instead of `thermal_zone0` (the zone that
  actually governs the guard), runs under `nice -n 19` with two JIT threads, and passes the
  classpath through a javac argfile because an inline `-cp` over 1007 jars exceeds the exec arg
  limit. It **refuses to start above 82 °C** rather than pushing load onto a hot machine — observed
  working: one run aborted at 82–83 °C, the retry at 79 °C returned **0 errors, 45 classes**. So
  the newest source (including the new voice files) is type-correct going into the build.
- **Background listening is implemented, not just planned.** `SpotterService` now owns a screen-off
  loop (`LockedListener`) that segments speech with the same pure-Java `UtteranceSegmenter` the
  screen-on capture uses, gates each utterance against the wearer's enrolled print, and keeps a WAV
  — deliberately **not** transcribing, because Android's recogniser needs an Activity. **92 checks
  pass** (`tools/voicecore/run.sh`), which is how the trailing-silence-counted-as-voicing bug was
  found. Issue #6's wiring half is done; only the on-device proof is outstanding.
- **The on-device proof is now one command.** `tools/device-smoke.sh` refuses an APK older than the
  newest source, checks the foreground type mask from `dumpsys`, has the wearer lock and speak, reads
  `logcat -s SpotterVoice:V` back and asserts each claim, and `--pull DIR` fetches the kept WAV. Both
  sides log under `TAG "SpotterVoice"` specifically so this path is observable at all.
- **The tracking asked for by the brief exists as three surfaces:** `README.md#timeline` (story),
  `docs/TIMELINE.md` (story + hashes re-checked with `git cat-file -e`: 34/41 resolve here, 6 marked
  as relay-mirror hashes, 1 in the sibling repo), `docs/PROGRESS.md` (verified/in-flight/open).
- **`NullAITech/meta-dev-skills` is published** — PUBLIC, MIT, 8 skills, `970f5ef`.

**Revised D6 opening order (Sat Oct 31) — the change is that step 1 no longer gates step 2:**

1. `tools/device-smoke.sh` **the moment the phone is plugged back in.** It needs no new build: it
   uses the APK on disk, and while that APK predates the newest source it is newer than the voice
   wiring that the screen-off path actually lives in, so the run answers "can the service hold the
   microphone with the screen off and gate my voice?" *today*, independently of the packaging
   problem. Record the outcome in PROGRESS either way.
2. `assembleDebug` on a cool box, then re-run the smoke test against the **fresh** APK (the script's
   staleness check enforces exactly this) — then confirm `REPLACE_WITH_WEARABLES_APP_ID` is absent
   from the merged manifest.
3. Glasses Connect / first frame — still Meta-side-gated; if it fails again, stop spending hours
   there and take the phone-camera Mission Mode take, which is the take that scores.
4. Then the film, on schedule.

**Standing constraint, unchanged:** the thermal guard wins. If the box is at or above 82 °C, do not
force a packaged build — do a typecheck, write docs, or run the smoke test on the existing APK.

---

## 1. Where we actually stand (ground truth, Fri Oct 30)

**Shipped and committed (this is the hackathon delta — the demo may claim all of it):**

- Provider layer; **Muse Spark** default (live, first-token p50 ≈ 1.27 s over 60 turns) + **Muse Wonder** planner (live, ~4.6 s plan).
- **Sessions WS** `/v1/sessions`: frame-diff (changed-only), bounded context, prefix-cache layout, per-turn latency metrics.
- **Missions REST** `/v1/missions`: one sentence → checklist, **interview-then-plan** (`status=clarify`), typed `step_done`/`off_track`/`unsafe` (no regex over prose).
- **Guide trio** (Eyes → Advisor → Director) with presets, Shh, **wearer voice-focus**, compass + sound-heading estimate; proactive loop **auto-advances steps**.
- **MCP tools**: `see_now`, `say`, `mission_status`, `advance_step`, `jot_field_note`, `jot_mission_sync`, `recall_second_brain`, confirm-gated `stream_start`/`stream_stop`.
- **Hermes Jot** live checklist + **Obsidian** Second Brain engrams with prior-job recall into planning.
- **SAM 3.1** step-target boxes + **Muse Image** completion recap (both env-gated, honest local fallback).
- **Live Mode**: `/overlay` OBS browser source, one-time confirm-token gate, audit-logged.
- **PWA** at `/pwa` (judge path, no glasses): camera or synthetic scenes, plan → review → Begin, Jot/Obsidian badges.
- **Spotter Android**: phone camera, QR pairing, plan-review-then-Begin, voice in/out, compass/sound, **DAT glasses bridge** (camera→relay).

**Open — the six things between here and a top-4 finish:**

| # | Open item | Why it matters | Owner |
|---|---|---|---|
| 1 | **Android build UNVERIFIED** — the guarded run only logged `waiting:` (box pinned 77–82 °C, abort at ≥82 °C) and was stopped; the on-disk APK is **17:05**, `MainActivity.java` **17:09**, so the APK predates the newest source. **v0.2.9 not installed.** | if the build is broken, the flagship client is dead | you (build+adb) |
| 2 | **Glasses first frame never proven on hardware** | it's the headline story; unproven = risky to film on | Neal taps (Meta-side) |
| 3 | **Live Mode / X RTMP not proven end-to-end** | optional bonus + viral; can sink a demo take if flaky | you |
| 4 | **60-min bench not run; no latency graph** | Technical Depth 20% needs an artifact | you |
| 5 | **Final 3-min demo not filmed** | Demo 25% + the viral post = the single biggest lever | you |
| 6 | **README/PRIOR-WORK final accuracy + submission** | prior/new split or **DQ** | you |

**Known external blockers (from `ROADMAP-FOR-MUSE.md` — not code bugs):** glasses gate needs Meta AI
Developer Mode + Spotter in the glasses' dev apps + glasses on WiFi; relay `:8770` needs **one**
`zoth-vault unlock` after a daemon restart (do **not** `daemon restart`); Pixel must be on USB for installs;
partner keys (Baseten/Modal) land in `zoth-vault` when claimed.

---

## 2. The scoring math (what actually wins)

Stage 1 is **pass/fail**: fits the theme **and** uses Meta Model API and/or Meta open-weight models.
FieldKit calls **Muse Spark + Muse Wonder through the Meta Model API** → **Stage 1 passes** (documented in
README). Stage 2 is where placement is decided:

| Weight | What satisfies it | Where FieldKit stands | The gap to close |
|---|---|---|---|
| **Impact 30%** | a real-world problem, clearly better solved | hands-free mission coach for greasy-hands work ("Build for Your World") | **the story + the film.** One crisp before/after beat |
| **Use of Meta AI Tooling 25%** | breadth *and* depth of Meta models | Spark (vision+reply), Wonder (plan), SAM 3.1 (highlight), Muse Image (recap), Spark Open (open-weight), Muse Code (builder) | README must **enumerate every Meta model with the call site** |
| **Technical Depth & Execution 20%** | it's hard *and* it runs | provider layer, frame-diff + bounded context + prefix caching, typed events, MCP, confirm-gate + audit | **the bench graph** — turns "it's fast" into proof |
| **Demo 25%** | watchable, honest, working 3-min video | not filmed | **film it.** Agent speaks first; prior/new honesty slide |

**Read this again: Impact + Demo = 55% of the score, and both are won by a *film*, not a feature.**
**(judgement)** If Feature Freeze costs one stretch feature but buys a clean master take, take the freeze.

Stage-3 finals (top 5 per track) reuse the same rubric — so optimize for the same four things, not for novelty.

**Meta model → where FieldKit calls it (lift this into README for the 25%).**

| Meta model | Role in FieldKit | Call site |
|---|---|---|
| **Muse Spark** (`muse-spark-1.1`) | default live vision + reply brain | provider layer, default Sessions target |
| **Muse Wonder** | planner / reviewer (one sentence → checklist; step assessor) | `zoth_relay/missions.py` |
| **SAM 3.1** | step-target highlight boxes | recap/grounding, `SAM_API_URL`-gated, drawn in PWA |
| **Muse Image** | completion recap card | `/v1/missions/{id}/recap`, `MUSE_IMAGE_MODEL` |
| **Muse Spark Open** (`meta-models/muse-spark-open-1026`) | open-weight path (Baseten/Modal) | env-gated preset → **Best Open Weights** |
| **Muse Code** | build tool (not a runtime endpoint) | MCP client during development |

---

## 3. Hard lines (non-negotiable — violating any of these loses the entry)

1. **Demo only shows hackathon-built work.** README + PRIOR-WORK.md split "existed before" vs "built at the
   hackathon"; the video carries an honesty slide. Failure to identify original contributions = **DQ**
   (`RULES-NOTES.md` 4–6, `DETAILS` §4).
2. **No face identity — ever.** Identity comes only from the **wearer's typed or spoken words**; a face may
   at most prompt a **yes/no confirmation**. Bystanders stay gated (off-axis speech ignored, **zero model
   calls**). See §4 for the traps.
3. **Public repo + accessible 3-min video** at submission; only hackathon commits on the submitted branch.
4. **No secrets in git** (`grep` for `sk-`, `ghp-`, Bearer, `.token` before every push).
5. **Public / hands-on actions stay confirm-gated + audit-logged** (`RULES-NOTES.md` 15).
6. **No build storms.** One deliberate `assembleDebug` at a time; no parallel gradle/pip/pytest loops — the
   box runs hot and other work is running. A broken build mid-demo is worse than a slow one.

---

## 4. The face rule — and the shortcuts that would kill the entry

The product already respects this (`MOBILE.md`: the default system prompt tells the model **not** to
identify people by face; `HACKATHON-LOG.md`: bystander speech is counted as `ignored_outside` and **never
sent to models**). It is easy to break while chasing a "wow" demo beat. Do not:

| Tempting shortcut | Why it's disallowed |
|---|---|
| "Look at this person and tell me who they are" | face→identity. Forbidden. |
| SAM 3.1 used to **label a face as a named person** | SAM may box an *object*; a face must never carry a name. |
| Storing a face/embedding to re-recognize later | identity from a face. Forbidden. |
| Letting a bystander's voice drive the model | breaks the wearer-only voice gate + privacy story. |

**Allowed person beat (use this in the demo if you want one):** the **wearer says the name** — *"I'm
meeting Dan."* When a face appears, the app may ask **"Is this Dan? yes / no."** Identity is the wearer's
word; the face only triggers a **yes/no confirmation**. Keep it exactly that.

---

## 5. Cut line — MVP vs stretch (decide once, then stop re-deciding)

**MUST ship (the entry is disqualified or empty without these):**
1. Android: **a verified build** (`assembleDebug` → BUILD SUCCESSFUL) installed to the Pixel **or** the PWA accepted as the judge client.
2. **One end-to-end Mission Mode run**: goal → Wonder plan → proactive "next step" spoken → Jot checklist ticks → Obsidian engram.
3. **Prior/new honesty** in README + PRIOR-WORK + video slide.
4. **3-minute accessible demo** (captions, plain narration) on a stable link.
5. **Public repo** at the submitted commit; **credits claimed** (log says done — verify billing + $150 alert).

**STRETCH (attempt only after the above, in this order — each also feeds a bonus prize):**
1. Glasses first frame on hardware (biggest Impact lift, but gated on Neal's Meta taps).
2. Live Mode `/overlay` in OBS + confirm gate on camera + audit proof.
3. X RTMP stream + **viral post** (bonus: Most Viral $20K).
4. Spark Open soak on Baseten/Modal (bonus: Best Open Weights $10K).
5. MCP `see_now` screen moment (bonus: Best Agentic $5K).
6. Muse Image recap card in the demo mission (bonus: Best Creative $5K).

---

## 6. Day-by-day

Six days: **Fri Oct 30 → Wed Nov 4.** Two non-negotiables each day at the top; "done when" at the bottom.

**Week at a glance — one priority per day, in order:**

| Day | Single priority | Why (rubric link) |
|---|---|---|
| **Fri Oct 30** | Verify the build passes; unblock glasses | you cannot demo a client you have not run — gates Impact + Demo |
| **Sat Oct 31** | Prove one full Mission Mode run + Live Mode | the working product *is* the Demo 25% |
| **Sun Nov 1** | 60-min bench graph + PWA polish + **freeze** | Technical Depth 20%; freeze protects the film |
| **Mon Nov 2** | **Film the 3-minute demo** | Demo 25% + Impact 30% are won on camera |
| **Tue Nov 3** | Docs + submission dry-run + X cut | prior/new accuracy (no DQ) + viral bonus |
| **Wed Nov 4** | **Submit** (target 6 PM, hard 9 PM) | nothing ships if this doesn't |

### Fri Oct 30  — Verify the build; unblock glasses; confirm the ground (D5)

**Non-negotiables:** (1) prove the Android build passes, (2) put glasses-blocker to rest one way or another.

- **Build verify (first-order).** One clean `./gradlew assembleDebug -PmwdatAppId=0` in `android/`. Then
  verify the generated `mwdat_app_id="0"` resource (the v0.2.8→v0.2.9 fix) so DAT can register. Do **one**
  build, wait for it, don't chain gradle.
- **Install.** Pixel on USB (`adb devices` authorized) → `adb install -r android/app/build/outputs/apk/debug/app-debug.apk`.
  Confirm with `dumpsys package tech.nullai.fieldkit | grep versionName` (expect ≥ 0.2.9 / code ≥ 11). If ADB
  says a device is `unauthorized`, that is a **Neal tap**, not a code bug — log it and move on.
- **Relay up.** Confirm `:8770` health on loopback + Tailscale. If it's down from a vault lock, one
  `zoth-vault unlock` → relaunch (`nullai-fieldkit/.venv`, `relay-runtime` config, `setsid -f`). **Do not**
  `daemon restart`.
- **Glasses, timeboxed.** In the **10:00–11:00 AM ET weekday #office-hours** window if you're blocked on the
  Meta API. Do the DAT sequence once (Meta glasses → Connect → approve cam → approve mic → first frame).
  If it does not land, **write it down as an external blocker and stop** — phone camera is the demo path.
- **Noon:** Fireworks workshop (12–1 PM) — **only** if it feeds the open-weight bonus; otherwise skip.
- **Housekeeping:** confirm credits claim status + set the **$150 spend-limit alert**; keep the OBS/X-RTMP
  physical checks on the list.

**Done when:** `assembleDebug` returned **BUILD SUCCESSFUL**; the APK is installed on the Pixel with a
verified versionCode; relay health is green; **and** either a first glasses frame is captured **or** the DAT
gate is written as an external blocker with the phone-camera fallback accepted.

### Sat Oct 31  — Prove the flagship run + Live Mode once (D6)

**Non-negotiables:** (1) one full uncut Mission Mode run, (2) Live Mode proven or explicitly parked.

- **Mission Mode E2E (the money beat).** Phone camera (or glasses if Fri landed). Run the whole spine live:
  say the goal → Wonder makes the plan → you Begin → the **coach speaks the next step unprompted** on a
  changed view → **Jot checklist ticks** → on completion, the **Obsidian engram** is written and prior-job
  recall feeds the next plan. Record the raw take (screen + voice). This is the spine of the final film.
- **Live Mode once.** `/overlay` in OBS as a browser source; run a **confirm-gated** `stream_start`; show
  the on-screen confirm challenge; stop it; **capture the audit entry**. If the X RTMP path is flaky,
  **park it** — Mission Mode remains the demo and the overlay alone still proves the safety story.
- **Face-rule pass.** Confirm the wearer/bystander gate holds in this run: off-axis speech ignored, no model
  call, no face→identity anywhere. If a person beat is wanted, script it as §4 ("wearer says the name; face
  triggers yes/no").

**Done when:** a full Mission Mode take exists on disk (Jot tick + Obsidian engram visible in-frame), and
Live Mode either shows the OBS overlay with a confirm gate on camera + an audit entry **or** is parked with
a one-line reason.

### Sun Nov 1  — Bench + judge-PWA polish; FEATURE FREEZE (D7)

**Non-negotiables:** (1) the 60-min flat-latency soak → graph in README, (2) freeze the feature surface.

- **Bench.** Run `zoth-relay bench` as a **60-minute** soak against Muse Spark. Capture p50/p95 first-token
  and the flat-line "turn 200 as fast as turn 10" property (already seen over 60 turns at p50 ≈ 1.27 s).
  Commit the **graph** into README — this is the single cheapest Technical-Depth 20% point you can buy.
- **PWA judge polish.** Open `/pwa` on a phone **with no glasses**: run plan → questions? → review → Begin,
  camera **or** synthetic scenes, badges visible. This is the fallback if the flagship phone/glasses client
  is unavailable to a judge — it must be one-tap obvious.
- **Freeze.** After the bench, **stop adding features.** Tag a `feature-freeze` commit. Remaining work is
  docs, film, and submission only. **(judgement: freeze tonight, not Tuesday.)**
- **Bonus keys (optional, if unblocked):** if Baseten/Modal keys are in the vault, run one **Spark Open**
  soak to bank the Best Open Weights path; otherwise note it env-gated and move on.

**Done when:** the 60-min bench graph is committed to README, `/pwa` completes a mission on a glasses-less
phone, and the `feature-freeze` commit is tagged.

### Mon Nov 2  — FILM the 3-minute demo (D8)

**Non-negotiables:** (1) a 3:00–3:10 master cut, (2) the prior/new honesty slide on screen.

- **Master take (uncut preferred).** One continuous Mission Mode run narrated for a judge who has never
  seen the product. Script shape (from the product's own demo script, updated for what actually shipped):
  - `0:00` **Hook** — POV. The agent speaks first: *"Next: tighten the left clamp."* You did not ask.
  - `0:40` **Mission Mode** — steps advance from a changed view; typed `step_done`; safety speaks first.
  - `1:20` **Jot + Second Brain beat** — checklist ticks itself; the engram appears; prior recall into planning.
  - `1:50` **Platform beat** — an MCP client (Claude/Cursor/Muse Code) calls `see_now`/`mission_status` on the live session.
  - `2:20` **PWA beat** — same API on a glasses-less phone (judge path).
  - `2:40` **Honesty + proof** — "existed before" vs "built at the hackathon" slide; the bench graph; end card.
- **Accessibility.** Burned-in or platform **captions**, plain narration, no unlabelled jargon. "Accessible"
  is a stated requirement, not a nicety.
- **Screen-recording hygiene.** Pre-roll the relay + OBS so the take starts clean; keep one backup take on
  the phone camera even if the glasses take (Sat) is the primary.

**Done when:** a `3:00–3:10` master cut exists with captions, shows **only hackathon-built features**, and
includes the prior/new honesty slide; a stable (unlisted-or-public) link is uploaded.

### Tue Nov 3  — Bug bash + packaging + submission dry-run (D9)

**Non-negotiables:** (1) README/PRIOR-WORK accurate, (2) the submission drafted and dry-run.

- **Docs final.** README: list **every Meta model with its call site**; the prior/new split; the bench graph.
  PRIOR-WORK.md committed. Add the demo-video link near the top of README.
- **Correctness sweep.** **Do not** run the full test suite for this (thermal rule) unless the box is idle
  and you accept one run; rely on the earlier green state (**241 passed**, 1 warning — Fri Oct 30 full `.venv/bin/python -m pytest -q`, `/tmp/all8.log`) and re-run only if something changed at freeze. Re-check: Jot/Obsidian beat, PWA, `/overlay`,
  MCP tools still answer. Fix **demo-path** bugs only — no new features.
- **Submission dry-run.** Fill the Cerebral Valley form fields offline (title, one-liner, repo URL, video
  URL, tech list). Verify: **repo is PUBLIC**, secret scan clean, video link opens **logged-out**, team/
  contributor line correct (solo → just you).
- **X viral cut.** Make a **≤ 60 s** cut optimized for X; draft the post; **do not publish yet** (publish
  near submission for the Nov 8 engagement window). This is the Most Viral $20K play.
- **Nov 3 is the last full day for submission questions/troubleshooting** (`DETAILS` §3) — ask anything
  that could block Wed **today**.

**Done when:** README + PRIOR-WORK are accurate and committed, the submission form is filled as a dry-run,
the repo is confirmed public with a clean secret scan, and the X cut + draft post exist.

### Wed Nov 4  — SUBMIT (deadline 9:00 PM ET · target 6:00 PM ET)

**Non-negotiables:** (1) submission **accepted** on the CV platform, (2) credits claim confirmed.

- **Submit by 6:00 PM ET** for buffer. Then re-open the form and **read back** what the platform saved.
- **Final read-back checks:** repo public at the exact submitted commit; video link stable + accessible
  logged-out; credits claimed (hard cutoff **9:00 PM ET**); spend-limit alert = $150.
- **Publish the X post** with the demo reel; grab the post link (engagement runs through **Nov 8 EOD**;
  submit the link as the bonus entry).
- **Final office hours 9:00 PM** = last call. **Stop feature work after submit.**
- **Do not touch the repo after submit** except a README typo if truly needed.

**Done when:** the submission shows **accepted** on the CV platform, the repo + video links verify from a
logged-out browser, credits are confirmed claimed, and the viral X post is live with its link recorded.

---

## 7. Risk register

| Risk | Likelihood | Impact | Mitigation (do this pre-emptively) |
|---|---|---|---|
| Android build broken / won't install | Med-High | Flagship client dead | Verify build **Fri**; keep Pixel on USB; have the last-good APK (v0.2.7) + PWA as fallback |
| Glasses first frame never lands | Med-High | Headline story unproven | Phone camera **is** the demo path; glasses = architecture story; timebox Fri, then stop |
| Live Mode / X RTMP flaky | Med-High | Could sink a demo take | Mission Mode stays flagship; overlay alone proves the safety beat; park X if flaky |
| Vault locks → `:8770` down mid-work | Med | Everything stalls | One `unlock`, keepalive via direct key read; never `daemon restart` |
| Demo shows prior work → **DQ** | Low | Total loss | Honesty slide + README/PRIOR-WORK split; freeze demos to the hackathon branch only |
| Solo + sleepy → run over the deadline | High | Miss submission | Cut line (§5); freeze Sun Nov 1; submit Tue dry-run; **6 PM** target |
| Feature creep delays the film | High | Demo 25% suffers | Hard freeze Sun Nov 1; film is the priority after that |
| Box thermal / build storms | Med | Corrupt take / lost time | One build at a time; no parallel gradle/pip/pytest; film while box is cool |
| Credits/billing surprise | Low | API cuts off | Verify billing + $150 alert **Fri**; key stays in `zoth-vault` |

---

## 8. Bonus-prize plays (cheap, stack them on the demo — don't build new for them)

| Bonus | Amount | What it needs | FieldKit already has… |
|---|---|---|---|
| Most Viral Project | $20K | X post w/ engagement through **Nov 8 EOD**, link submitted | the demo reel → make the ≤60s cut (Tue) |
| Best Build on Meta Open Weight Models | $10K | a real open-weight build | Spark Open preset (`meta-models/muse-spark-open-1026`, Baseten/Modal env-gated) → one soak |
| Community Vote | $10K | Discord vote (opens Nov 9, winner Nov 16) | the same film; post the honest demo in #general |
| Best Agentic Use-Case | $5K | agent that acts on the world | MCP `see_now`/`advance_step` on a live session → the 1:50 beat |
| Best Creative Build | $5K | Muse Image / Muse Wonder | Muse Image recap card (env-gated) → show it in the demo mission |

**(judgement)** Do **not** chase all five at the cost of the master take. Rank: **viral cut > open-weight
soak > agentic beat > creative beat > community push.** All are surfaced *by* the one film.

---

## 9. Submission checklist (print this, tick it Wed)

- [x] Repo **PUBLIC** at the submitted commit (confirmed **Oct 30**: `visibility: PUBLIC`, MIT); only
      hackathon commits after the tagged baseline. `git diff baseline/pre-event-2026-10-26..main` **is** the
      hackathon delta, which is how the prior/new split is proven rather than asserted.
- [ ] Secret scan clean (`sk-`, `ghp-`, Bearer, `.token`) on tracked files.
- [ ] README: **every Meta model listed with its call site**; prior/new split; bench graph; video link near top.
- [ ] PRIOR-WORK.md committed; honesty slide in the video.
- [ ] **3-min accessible video** (captions, plain narration) on a stable, logged-out-openable link.
- [ ] Submission **accepted** on the CV platform (read it back).
- [ ] Credits claimed (hard cutoff **9 PM ET**); spend-limit alert = $150. *Oct 30 status: form due
      Nov 4 — do it **before** the filming day, not during it.*
- [ ] README **Timeline** section reads as a build story with commits behind every row; `docs/PROGRESS.md`
      (verified state) and this file (the plan) agree with the video.
- [ ] X viral post live; **link recorded**; engagement tracked through Nov 8.
- [ ] Team/contributor line correct (solo → you).
- [ ] Nothing on screen in the video that predates Oct 26 12:00 PM ET.

---

## 10. If you only get 6 hours (collapsed recovery)

When tired, do exactly these, in order, and nothing else:

1. **Verify the Android build passes** (`assembleDebug`, one attempt). If it fails, fall back to `/pwa`.
2. **One uncut Mission Mode run** on the phone camera — goal → plan → proactive next step → Jot ticks.
3. **Record it.** Screen + voice. Even a clean phone-camera take beats a fancy broken one.
4. **Add the honesty slide** (prior vs new) and the bench number if you have it.
5. **README prior/new split** + video link. Repo public.
6. **Submit** on the CV platform.

Everything else — glasses, Live/X, SAM, Muse Image, the viral cut — is a **stretch**, and every one of them
is worth $0 if the master take and the submission don't exist.

---

*Backbone flow (for the README/architecture story):*

```text
Ray-Ban Meta glasses / phone
        │ frames (changed only) + voice
        ▼
  FieldKit Relay — Muse Wonder plans · Muse Spark replies fast
        │         — remembers the mission + current step (bounded context)
        ▼
Voice guidance · phone screen · OBS captions · agent tools (MCP)
```

**The one sentence to keep in your head all week:** the entry is built; the week is about **proving it and
filming it.**
