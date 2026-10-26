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

Hackathon build, developer preview. The current public story is the server backbone: providers, live sessions, missions, step tracking, tests, and soak checks. The glasses coach, live mode, phone-only trial, and agent tools are being connected in that order.

Repository history in this public repo is curated on purpose: the first commit is a frozen snapshot of what existed before the hackathon; later commits show only the new FieldKit work in order. Internal provenance is preserved separately by NullAI.

## Where to find us

- Company: **[NullAI](https://nullai.tech)**
- Studio: **[Zoth Studio](https://zoth.nullai.tech)**
- Tools home: **[ZothOS](https://zothos.nullai.tech)**

Built by NullAI Tech. Working codename during development: **ZOTH FIELDKIT**.
