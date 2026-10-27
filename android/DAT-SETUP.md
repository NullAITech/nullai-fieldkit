# Meta glasses (DAT) setup — FieldKit Android

FieldKit connects to Ray-Ban Meta glasses the same way the original Zoth
glasses app did: through the **Meta AI app** on this phone, using Meta's
**Wearables Device Access Toolkit (DAT)** (`com.meta.wearable:mwdat-*`).

## What is wired in the app

- `DatGlasses.kt` — DAT bridge: `Wearables.initialize`, registration state,
  `Wearables.startRegistration` (opens the Meta AI flow), session via
  `AutoDeviceSelector`, camera capability, explicit `Stream.start()`, and a
  continuous video-frame flow. Readable frames are converted to JPEG, shown on
  the phone, and forwarded to the relay as normal session frames.
- The app does not call the glasses connected until the first real frame is
  decoded. Session/stream errors are surfaced instead of being hidden.
- Source switch: **Meta glasses** mode makes this phone the controller;
  glasses frames become the session's eyes (phone camera is the other source).
- Manifest carries `com.meta.wearable.mwdat.APPLICATION_ID` plus analytics
  and crash-reporting opt-outs (privacy: both off).

## What Neal must do once (Meta-side)

Fast path (hackathon, works today):

1. Build with the Developer Mode id: `./gradlew assembleDebug -PmwdatAppId=0`
   (`0` is DAT's documented Developer Mode APPLICATION_ID).
2. In the **Meta AI app**: pair the glasses and enable **Developer Mode** in
   the glasses' device settings.
3. In Spotter: choose **Meta glasses** → **Connect glasses** → approve the
   registration in Meta AI if prompted → tap **Connect glasses** again.

Production path (later): register the app in the
[Wearables Developer Center](https://wearables.developer.meta.com/)
(create a project — see `docs/manage-projects` there), then build with the
real id: `./gradlew assembleDebug -PmwdatAppId=<YOUR_ID>`. Without either
`0`+Developer Mode or a real id, DAT refuses with "no eligible device".

## Honest status

Compiles against the real DAT 1.0.1 SDK and the phone UI flow is in place.
First on-glasses frame flow is **not yet verified on the physical glasses** —
it needs the app id above plus the physical glasses. The original Zoth Guide
Android source was not present on this machine; the bridge follows the public
DAT session/stream API and the working relay pattern of one long-lived session
with newest frames forwarded as JPEG.
