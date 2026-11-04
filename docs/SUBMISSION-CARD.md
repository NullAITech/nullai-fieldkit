# Devpost / Cerebral Valley Submission Dossier
**Project Name:** FieldKit by NullAI (Spotter)  
**Tagline:** A proactive, hands-free mission coach for Ray-Ban Meta glasses: You talk. It plans. It watches. It guides.  
**Primary Track:** Indie Track (Solo Builder)  
**Repository:** https://github.com/NullAITech/nullai-fieldkit  
**Branch:** `hackathon/2026-11-02-integrated` (and `main`)  
**Companion Skills Repo:** https://github.com/NullAITech/meta-dev-skills  
**License:** MIT License  
**Maintainer:** Neal Frazier Tech (@1nc0gn30) • NullAI Platform  

---

## 1. Elevator Pitch & Inspiration
When your hands are covered in grease under a lawnmower, handling fragile electronics, or cooking over a hot stove, you should **never** have to stop, wipe off your hands, unlock a smartphone, and type into a chat box. 

Smart glasses have cameras, microphones, and speakers—yet almost all "smart assistants" treat them like a Bluetooth earpiece waiting for you to ask a question. 

**FieldKit turns Ray-Ban Meta glasses into a proactive mission coach.** You speak your goal in one sentence (*"Swap mower air filter"*). FieldKit uses **Meta Muse Wonder** to create a structured 6-step checklist. As you work, your glasses stream live video to **Meta Muse Spark**, which watches the visual scene, hashes changes on-device, and **speaks the next step out loud automatically when your hands finish the work**—before you have to ask.

---

## 2. What It Does (Key Features)

1. **Proactive Multimodal Vision Coach**:
   - Watches what changes, not an endless video dump.
   - Autonomous step advance: detects when a clamp is loosened, a bolt is set, or a wire is connected, and speaks the next instruction.
   - Director voice policy: only speaks when a step is completed, off-track, or unsafe.

2. **Ray-Ban Meta Wearables Hardware Integration**:
   - Real, verified native `540×960` video streaming from physical Ray-Ban Meta glasses (`RB Meta 01QV`) to Pixel 10 Pro over the Meta Wearables Device Access Toolkit (DAT SDK `1.0.1`).
   - Seamless mid-call eye switching: seamlessly swap camera view between Ray-Ban Meta glasses and phone camera without dropping context.

3. **12 Hands-Free Spoken Wake Triggers**:
   - Unique spoken command grammar (*"Zoth Protocol Zero"*, *"Aperture Character Lock"*, *"Obsidian Ghost Veil"*, *"Zoth Broadcast Start"*, etc.) behind an acoustic speaker gate that prevents bystander trigger injection.

4. **Consent-First 4-Angle CharacterBoard & Viral Dossier Export**:
   - When introducing collaborators, Spotter extracts a 4-angle visual dossier (*Front, Left Oblique, Right Oblique, Full Torso & Attire*).
   - Generates a high-res 1080×1680 cybernetic dossier card exported via native Android share sheet (`Intent.ACTION_SEND`) to X, Discord, or Signal.

5. **Live RTMP Broadcast Studio**:
   - Direct live streaming connector to X, Twitch, YouTube Live, and OBS Studio.
   - Injects silent AAC clock frames to maintain stable audio synchronization on OBS even during pauses.

6. **2D & 3D Chiaroscuro Human Presence**:
   - A full anatomical human face rendered in sub-pixel Unicode Braille (`U+2800`), ASCII, and Bayer 8×8 dither with real-time 4-channel viseme speech animation and interactive 3D rotation.

7. **Dual-Layer Memory (Hermes Jot + Obsidian Second Brain)**:
   - Live missions tick off on Hermes Jot checklists in real time.
   - Jobs compile into Obsidian vault engrams, so prior troubleshooting is recalled next time: *"Last time on step 3, the left bolt was stripped."*

---

## 3. How We Built It (Meta AI Tooling & Architecture)

| Component | Technology | Role |
| :--- | :--- | :--- |
| **Live Vision Brain** | **Meta Muse Spark (`muse-spark-1.1`)** | Sub-1.3s first-token live reasoning over changed camera frames and conversational replies. |
| **Mission Planner** | **Meta Muse Wonder (`muse-wonder-1.0`)** | Interactive interview-then-plan generator producing typed 1–12 step checklists. |
| **Open-Weights Failover** | **Muse Spark Open (`meta-models/muse-spark-open-1026`)** | Baseten / Modal provider preset with automatic mid-turn failover if network stalls. |
| **Glasses Connectivity** | **Meta Wearables DAT SDK (`1.0.1`)** | BLE/Wi-Fi pairing, camera permission handshake, and live frame extraction. |
| **Agent Tools** | **FastMCP (Model Context Protocol)** | 9 live tools (`see_now`, `say`, `advance_step`, `jot_field_note`, `recall_second_brain`, etc.). |
| **On-Device Edge ML** | **MediaPipe Tasks 0.10.35** | BlazeFace detection, pose landmarks, and object bounding boxes computed on the phone. |
| **Hardware Encryption** | **Android Keystore (AES-256-GCM)** | Local encryption of saved people descriptors, voice prints, and mission notes. |

---

## 4. Challenges We Overcame

1. **DAT SDK Dev-Mode Registration Stale**:
   - Discovered that changing Android `versionCode` invalidates DAT developer registration in Meta View. Solved by locking `versionCode = 12` and pre-seeding `forceDevMode()` in `DatGlasses.kt`.
2. **Context Latency Drift on Wearables**:
   - Long interactive sessions tend to slow down as conversation history grows. We implemented prefix caching and char-capped rolling transcripts, maintaining a flat **1.27s p50 first-token latency across 60 live turns** (Turn 60 answers as fast as Turn 10).
3. **Wearer vs Bystander Audio Disambiguation**:
   - Developed a pure-Java `UtteranceSegmenter` and offline spectral voice-print gate that listens under a locked screen and refuses bystander speech.

---

## 5. Prior Work vs. Hackathon Honesty

- **Pre-Event Baseline (`baseline/pre-event-2026-10-26`, commit `7f5f463`)**:
  - Contained only a bare local FastAPI relay skeleton with no Meta AI models, no Missions API, no DAT SDK bridge, and no Android app.
- **Built 100% During the Hackathon (Oct 26 – Nov 4, 2026)**:
  - Meta Muse Spark & Wonder integration
  - Meta Wearables DAT SDK live glasses streaming on physical hardware
  - Sessions & Missions APIs with automatic step progression
  - 4-Angle CharacterBoard & high-res PNG export
  - 12 spoken hands-free triggers HUD
  - RTMP Live Broadcast Studio (Twitch, X, OBS)
  - 2D/3D Braille anatomical human presence
  - 299 Python tests + 112 JVM tests passing (100% green)
  - Machine-verifiable via `git diff baseline/pre-event-2026-10-26..main`.

---

## 6. What's Next for FieldKit
- Native audio streaming directly over DAT Bluetooth mic when released by Meta.
- Wearable-to-wearable collaborative mission sync for multi-technician teams.
- On-device lightweight Llama 3.2 1B edge quantized models running locally on Snapdragon NPU.
