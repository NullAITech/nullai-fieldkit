# FieldKit / Spotter — Video Production & Hackathon Submission Kit

**Event:** Meta Global AI Developer Hackathon (Oct 26 – Nov 4, 2026)  
**Deadline:** Wed Nov 4, 2026 at **9:00 PM ET** (Target: **6:00 PM ET**)  
**Rubric Weights:** Impact **30%** · Use of Meta AI Tooling **25%** · Technical Depth & Execution **20%** · Demo **25%**

---

## 1. How to Record Your Live Demo Takes (2 Options)

### Option A — Directly on the Phone (`● REC Demo` Button)
1. Put on your **Ray-Ban Meta glasses (`RB Meta 01QV`)** and open **Spotter** on your **Pixel 10 Pro**.
2. Tap **`● REC Demo`** in the top utility bar (on either the Home screen or the Live Call screen) and tap **Start now** on Android's screen-capture prompt.
3. Run through your demo! The button turns into a live timer (`■ STOP 00:42`).
4. Tap **`■ STOP`** when done — the `.mp4` (1080p H.264 video + live microphone audio) is saved directly to **`Movies/SpotterDemos/`** on your phone AND opens a share sheet.

### Option B — From Your Linux Desktop Over USB (`scripts/record-live-demo.sh`)
```bash
bash /home/zoth/Desktop/Zoth-Meta-Hackathon/wt-int/scripts/record-live-demo.sh
```
- Captures a 12 Mbps `.mp4` from the Pixel 10 Pro alongside live `DatGlasses` / `Spotter` / `CallStats` telemetry logs into `~/Desktop/Zoth-Meta-Hackathon/demos/`.

### Opening the Keynote & Honesty Slides for Your Video (`slides.html`)
Open the built-in interactive 4-slide presentation deck in Chrome (or as an OBS Browser Source):
- **Local File**: `file:///home/zoth/Desktop/Zoth-Meta-Hackathon/wt-int/zoth_relay/pwa/slides.html`
- **Via Relay**: `http://127.0.0.1:8770/pwa/slides`
- Use **Left / Right Arrow Keys** (or click the top buttons `1..4`) to switch between:
  - **Slide 1**: Hook & Live 3D Anatomical Braille Human Presence
  - **Slide 2**: Every Meta AI Model & SDK Call Site (25% Rubric)
  - **Slide 3**: Mandatory Prior Work vs. Built at Hackathon Honesty Slide (Zero DQ Risk)
  - **Slide 4**: 60-Turn Flat-Latency Benchmark Proof (`p50 = 1.27 s`, Turn 60 as fast as Turn 10)

---

## 2. The 3-Minute Master Demo Script (`0:00 – 3:00`)

> **Accessibility Rule:** Keep **`CC: ON`** enabled in Spotter so live AI & wearer captions burn right into the screen recording, and enable captions on YouTube/X when uploading.

| Timecode | On Screen | Spoken Narration / Live Action | Rubric Target |
| --- | --- | --- | --- |
| **`0:00 – 0:25`**<br>**The Hook (Glasses POV + Proactive Coach)** | Split screen or phone screen showing **Spotter** in **`Meta glasses`** mode streaming live `540×960` video from your Ray-Ban Meta glasses (`RB Meta 01QV`) while your hands are holding tools/hardware. | *"When your hands are busy on a real job, you shouldn't have to stop, unlock a phone, and type into a chat box. This is Spotter—built onFieldKit for Ray-Ban Meta glasses. Watch what happens when I finish a step without touching my phone."*<br>→ **Coach speaks first (with `CC: ON` subtitles on screen):** *"Step 1 complete. Next: check the left connector and keep clear of the power lead."* | **Impact 30%**<br>**Demo 25%** |
| **`0:25 – 1:05`**<br>**Mission Planning with Muse Wonder + Proactive Muse Spark** | Show the **Spotter Home Screen**. Tap **`⚡ Ideas`** (or speak a goal: *"Inspect and prep the workbench setup"*), tap **`▶ Start`**. Show **Muse Wonder** generating a structured checklist, then **Muse Spark** watching changed frames and advancing steps automatically (`step_done`). | *"One sentence is enough. Muse Wonder interviews and plans a typed step-by-step checklist. As I work, Spotter hashes frames on-device and sends only changed views to Muse Spark. The Eyes-Advisor-Director trio decides when to speak—and only speaks when a step finishes, goes off-track, or looks unsafe."* | **Meta Tooling 25%**<br>**Impact 30%** |
| **`1:05 – 1:45`**<br>**Live Call: 2D/3D Anatomical Presence + Live Eye Switch + 4-Angle Character Board** | Tap **`☎ Live Call`**. Show the **2D Sculpted Face** speaking with live **`CC: ON`** captions, then tap **`2D → 3D`** to show the rotating **3D Anatomical Bust** (swipe to rotate + tap style badge). Flip **`Phone cam ↔ Glasses`** live without dropping the call. Tap **`◆ Save Person`** (or show `People` → **`Alex Rivera`** 4-Angle Character Board popup!). | *"In a live call, the AI has a real chiaroscuro 2D and 3D anatomical human presence rendered in sub-pixel Unicode Braille with real-time speech visemes and live closed captions. The full-duplex call itself rides Gemini Live through our relay's single-use ephemeral tokens — the right transport for phone-call latency — while Muse Spark and Muse Wonder run the mission coaching you saw before. Mid-call, I can switch its eyes between my phone camera and my Ray-Ban Meta glasses without hanging up. And with consent-first Character Boards, when I save a teammate, Spotter captures 4 angles plus attire and pops up their Character Card whenever they walk back into view."* | **Demo 25%**<br>**Meta Tooling 25%** |
| **`1:45 – 2:15`**<br>**Hermes Jot + Obsidian Second Brain + MCP Agent Tools + Failover** | Show the **Conversations Hub (`💬 Chats`)** and `/pwa` web trial: Hermes Jot checklist ticking off, Obsidian Second Brain engram recall, and Baseten **Muse Spark Open** (`meta-models/muse-spark-open-1026`) failover + `GET /v1/sessions/replay` with **SAM 3.1** boxes. | *"Every mission syncs live to Hermes Jot and compiles into an Obsidian Second Brain engram—so next time I run a job, Spotter recalls what went wrong last time. Nine MCP tools let coding agents like Muse Code or Claude inspect the live session. And if the primary API ever stalls, FailoverProvider switches mid-turn to Meta's open-weight Muse Spark Open."* | **Meta Tooling 25%**<br>**Bonus Prizes** |
| **`2:15 – 2:40`**<br>**Technical Depth: 60-Turn Flat-Latency Benchmark** | Show **Slide 4** of `slides.html` (`60-Turn Live Soak Benchmark`). Point to `p50 = 1.27 s` across all 60 turns. | *"Because we combine SHA-256 frame diffing with prefix caching and a bounded transcript window, context never bloats: over a measured 60-turn soak — the raw harness is `scripts/soak_sessions.py` — Turn 60 on Muse Spark answers at the same p50 as Turn 10, 1.27 seconds. That is a 60-turn soak, not a 60-minute one; we claim exactly what we measured."* | **Technical Depth 20%** |
| **`2:40 – 3:00`**<br>**Mandatory Honesty Slide (Prior vs. Hackathon Work)** | Show **Slide 2 & Slide 3** of `slides.html` (`Prior vs. Hackathon Honesty`). Hold on screen for 10+ seconds so judges can read both columns clearly. | *"For full transparency: before kickoff on October 26, only a bare local FastAPI skeleton existed, frozen at tag `baseline/pre-event-2026-10-26`. Everything shown today—the Meta Muse Spark, Wonder, Spark Open, SAM 3.1, and Muse Image integrations, the Ray-Ban Meta DAT 1.0.1 streaming app, the 2D/3D anatomical presence, closed captions, and 4-angle character boards—was built 100% during this hackathon."* | **Rules Compliance (Zero DQ)** |

---

## 3. The ≤ 60-Second X Viral Cut & Post Copy ($20K Most Viral Bonus)

### 55-Second Rapid Cut Structure
- **`0:00 – 0:10`**: Put on Ray-Ban Meta glasses → live `540×960` stream pops into Spotter on Pixel 10 Pro → AI Coach speaks first with live green subtitles (`CC: ON`).
- **`0:10 – 0:25`**: Cut to **Live Call (`CallActivity`)** → show the **2D & 3D Braille Anatomical Human Face** talking and turning in 3D while switching live between `Glasses` and `Phone cam`.
- **`0:25 – 0:42`**: Show **`◆ Save Person`** → the **4-Angle Character Board** (`Front Face`, `Left Angle`, `Right Angle`, `Full Body` + attire/history chart) popping up live on the HUD when a teammate enters view.
- **`0:42 – 0:55`**: Quick flash of the **Meta AI Stack & Honesty Slide** (`Muse Spark + Muse Wonder + Spark Open + SAM 3.1 + Meta Wearables DAT 1.0.1`) + GitHub repo URL.

### Ready-to-Paste X (Twitter) Post
```text
We turned Ray-Ban Meta glasses into a proactive hands-free mission coach with a real 2D/3D Braille human presence. 🕶️⚡

Meet Spotter (FieldKit by @NullAITech), built for the Meta Global AI Developer Hackathon:
• Live video stream from Ray-Ban Meta glasses (Wearables DAT SDK 1.0.1)
• Muse Wonder plans the checklist → Muse Spark watches & speaks the next step automatically (1.27s p50)
• Muse Spark Open failover + SAM 3.1 grounding + Muse Image recaps
• Real-time AI closed captions (CC) + 4-Angle Character Boards that pop up when saved teammates walk into view
• 2D & 3D chiaroscuro human face rendered in sub-pixel Unicode Braille (U+2800)

100% open-source (MIT): https://github.com/NullAITech/nullai-fieldkit
```

---

## 4. Ready-to-Paste Cerebral Valley Submission Form Answers

- **Project Title**: `FieldKit (Spotter) by NullAI`
- **One-Line Pitch**: `A proactive, hands-free mission coach for Ray-Ban Meta glasses powered by Muse Wonder, Muse Spark, Muse Spark Open, SAM 3.1, and the Meta Wearables DAT SDK.`
- **Track**: `Indie Track (Solo Builder)`
- **Public Repository URL**: `https://github.com/NullAITech/nullai-fieldkit` (plus companion skills repo: `https://github.com/NullAITech/meta-dev-skills`)
- **Meta AI Models & Tools Used**:
  1. **Muse Spark (`muse-spark-1.1`)** — Low-latency live vision and conversational voice/text brain (`zoth_relay/providers.py`, `zoth_relay/sessions.py`, `zoth_relay/trio.py`, p50 first-token latency = `1.27 s` over 60 turns).
  2. **Muse Wonder (`muse-wonder-1.0`)** — Structured interview-then-plan mission planner and step completion reviewer (`zoth_relay/missions.py`).
  3. **Muse Spark Open (`meta-models/muse-spark-open-1026`)** — Open-weight model preset (Baseten / Modal) + automatic mid-turn failover (`FailoverProvider` in `zoth_relay/providers.py`).
  4. **SAM 3.1 (Segment Anything 3.1)** — Step-target bounding-box grounding in `/v1/sessions/replay` and mission recaps (`zoth_relay/recap.py`, env-configured via `SAM_API_URL`; when unset the replay honestly labels its placeholder `stand-in` with `source: "stand-in"` rather than passing it off as a segmentation — never demo the stand-in as SAM output).
  5. **Muse Image** — Visual mission-completion recap card compiled into the Obsidian Second Brain engram (`POST /v1/missions/{id}/recap`).
  6. **Meta Wearables Device Access Toolkit (`mwdat-core:1.0.1`, `mwdat-camera:1.0.1`)** — Native Ray-Ban Meta glasses Bluetooth/Wi-Fi session, camera permission handshake, and live `540×960` video streaming (`DatGlasses.kt`).
  7. **Muse Code & MCP Server** — 9 live coding-agent tools (`see_now`, `say`, `mission_status`, `advance_step`, `jot_field_note`, `jot_mission_sync`, `recall_second_brain`, `stream_start`, `stream_stop`) in `zoth_relay/mcp_server.py`.
- **Prior Work vs. New Hackathon Work Declaration**:
  - **Frozen Pre-Event Baseline**: Tagged at `baseline/pre-event-2026-10-26` (commit `7f5f463`, documented in `PRIOR-WORK.md`). Contained only an early local FastAPI skeleton with no Meta models, no Missions API, no Eyes/Advisor/Director trio, no Wearables DAT SDK integration, and no Android app.
  - **Built at Hackathon (Oct 26 – Nov 4, 2026)**: Every feature demonstrated—including the Meta model provider layer (Muse Spark, Muse Wonder, Muse Spark Open failover, SAM 3.1, Muse Image), Sessions & Missions APIs, proactive Eyes/Advisor/Director coach, Hermes Jot & Obsidian Second Brain memory, 9 MCP tools, the `/pwa` web trial, and the complete Spotter Android app (Ray-Ban Meta DAT 1.0.1 live video stream, 2D/3D Braille anatomical human presence, live AI closed captions, Conversations Hub, and consent-first 4-Angle Character Boards)—was built during the official hackathon building period. Machine-verifiable via `git diff baseline/pre-event-2026-10-26..main`.
