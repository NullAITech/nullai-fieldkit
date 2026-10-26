# Prior work and hackathon-new work

This public repo uses a clean, curated history so judges and new readers can see the order of work without digging through an older internal repository.

## The honest split

### Existed before the hackathon

The first commit in this repo is a frozen import of the pre-hackathon baseline. It represents prior work by NullAI Tech, including:

- A local relay server with REST and agent-tool connections.
- Token, permission, sandbox, rate-limit, and activity-record foundations.
- Mobile question-and-image plumbing.
- Earlier guide streaming experiments, including local vision and backup-brain paths.
- A demo/showcase website for the earlier glasses-guide idea.

That baseline is **not** presented as hackathon-new work.

### Built during the hackathon

Later commits in this repo add the FieldKit work in order:

1. **Provider layer** — Muse Spark and Muse Wonder behind one simple interface, with a local backup option.
2. **API stubs** — first placeholders for live sessions and missions.
3. **Sessions API** — live frames, voice/text, changed-frame handling, bounded memory, and per-turn timing.
4. **Missions API** — one sentence becomes a checklist; a typed step tracker records progress.

Only those later additions should be treated as the hackathon delta.

## Why the history looks clean

The public history starts from the frozen snapshot taken when hackathon work began, then follows the new commits forward. NullAI keeps the fuller internal history separately for provenance. This file exists so the clean presentation never hides the prior-work boundary.

## Rule we follow in the demo

If a feature existed before the hackathon, we may show it as context, but we do not claim it as new. The demo focuses on the provider layer, Sessions API, Missions API, step tracking, and the user-facing work built on top during the event window.
