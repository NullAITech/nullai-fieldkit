# Web-trial self-test

Proves the judge-facing page at `/pwa` works, and keeps working, **with no
glasses, no phone and no camera in the room**. That page is the fallback demo: if
a judge cannot put the app on their own hardware, this is the path they touch, so
it is the one path that must never be taken on faith.

```
python3 tools/webtrial-selftest/journey.py \
    --relay http://127.0.0.1:8770 \
    --token-file /home/zoth/Desktop/Zoth-Meta-Hackathon/relay-runtime/pixel.token
```

Exit codes: **0** the loop ran and both sides of it reached the page's log;
**1** the page loaded but the loop did not complete; **2** no relay or no token
file, so *nothing was verified* (it never reports a pass it did not observe).

## What it checks

1. The page's own script is ready before anything is clicked. A click delivered
   before the inline script runs is a silent no-op; reading the status line too
   early then blames the page for the harness's own race.
2. The relay token goes into the page's own token field, and the page's own
   buttons are the only things clicked.
3. The built-in **synthetic scenes** are switched, so the camera-less path is
   what is exercised — the same path a judge without glasses has.
4. The socket and the status line are watched while the Eyes → Advisor →
   Director loop runs.
5. The page's **own log pane** is read, because that is literally where a judge
   sees both sides of the loop:
   `Eyes: …` (what the camera-side saw) and `Coach: …` (what the companion said
   back). A page that shows only a spinner is a page whose loop nobody can see.
6. A real mission is planned through the same REST call the page uses
   (`POST /v1/missions`) and the typed step list is checked to have come back.

## The three rules that make this reliable

Each was learned by watching a green-looking run lie:

- **Pick a CDP port that is proven free** (bind it first). If the port is already
  listening, Chrome falls back to an IPv6-only bind, and an IPv4-only probe then
  reports "chrome never came up" while Chrome is running fine.
- **Probe both `127.0.0.1` and `[::1]`** for the devtools endpoint. Belt and
  braces against exactly that fallback.
- **Kill any Chrome of ours still holding the profile, before launching**, and
  clean up on every exit path. A crashed run leaves a stale browser on the
  profile; the next run then drives a half-loaded page and reads "Not connected."
  off a page that is connected and live.

It launches its **own** headless Chrome on a dynamic private port and reads
loopback only. It never touches a browser or a relay process that is already in
use — including whatever is sitting on the standard debugging port.

## Nothing secret is printed

The token is read from the operator's own file, typed straight into the page, and
never echoed. Any URL this tool logs has `token=…` replaced with `[redacted]`,
including URLs that only appear inside browser console or network diagnostics.
