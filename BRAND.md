# FieldKit brand and plain-language guide

## Names people should see

| Layer | Public name | Use it for |
|---|---|---|
| Company | **NullAI** | Who built it |
| Product | **FieldKit by NullAI** | The thing users install and talk about |
| In-glasses experience | **FieldKit for Glasses** | Mission coaching on Ray-Ban Meta |
| Main mode | **Mission Mode** | Start a job, get steps, stay on track |
| Sharing mode | **Live Mode** | Captions or stream output, only with confirmation |
| Home for tools | **ZothOS** | Where the tools live |
| Studio | **Zoth Studio** | Demos, media, and project pages |

Internal names can stay in code when changing them would create risk. User-facing copy should use the public names above.

## Links

- Company: https://nullai.tech
- Studio: https://zoth.nullai.tech
- Tools home: https://zothos.nullai.tech

## One-liner options

Primary:

> FieldKit by NullAI tells you the next step while your hands stay on the work.

Short:

> A hands-free mission coach for Ray-Ban Meta.

Demo hook:

> I didn’t ask it anything. It told me what to do next.

## Voice

Write for a smart neighbor, not an engineer.

- Short sentences.
- Verbs first.
- One idea per line.
- Say “next step,” not “next action item.”
- Say “watch what changed,” not “perform frame-diff inference.”
- Say “backup brain,” not “fallback provider,” when talking to users.
- Never make the user learn our internal codenames to understand the product.

## Translation table

| Internal / technical | Say this instead |
|---|---|
| zoth-relay | FieldKit Relay |
| Sessions API | live connection |
| frame diff | only sends what changed |
| bounded context | remembers the job, forgets the clutter |
| provider | brain |
| Muse Spark | fast brain |
| Muse Wonder | planning brain |
| MCP tools | agent tools |
| audit log | activity record |
| confirm gate | asks before it acts |

## Design rules for digestibility

1. The first screen should answer: what is this, who made it, and what do I do next?
2. Every feature gets a human example before a technical explanation.
3. Show the current step big. Everything else can be smaller.
4. Read-aloud friendly text: short, calm, and specific.
5. Dangerous or public actions must look different from normal guidance and require a clear yes.
6. Avoid a wall of modes. Lead with Mission Mode; reveal Live Mode and agent tools later.

## Visual direction

Keep the existing Zoth gold/void energy, but make it calmer for regular users:

- Dark, quiet background.
- One warm gold accent for the current step and primary action.
- Large, high-contrast type.
- Fewer panels, more air.
- Motion only when it explains progress.
