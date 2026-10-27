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

1. Register the app in the **Wearables Developer Center** and get its
   APPLICATION_ID.
2. Build with it: `./gradlew assembleDebug -PmwdatAppId=<YOUR_ID>`
   (placeholder in the manifest until then; DAT sessions will refuse).
3. In the **Meta AI app**: pair the glasses, enable **Developer Mode**, and
   make sure the glasses' DAT app is up to date.
4. In FieldKit: choose **Meta glasses** → **Connect glasses** → finish the
   registration in Meta AI → tap **Connect glasses** again.

## Honest status

Compiles against the real DAT 1.0.1 SDK and the phone UI flow is in place.
First on-glasses frame flow is **not yet verified on the physical glasses** —
it needs the app id above plus the physical glasses. The original Zoth Guide
Android source was not present on this machine; the bridge follows the public
DAT session/stream API and the working relay pattern of one long-lived session
with newest frames forwarded as JPEG.
