package tech.nullai.fieldkit;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

import java.util.ArrayDeque;

import tech.nullai.fieldkit.avatar.Muse2DView;
import tech.nullai.fieldkit.avatar.Muse3DView;
import tech.nullai.fieldkit.live.LiveCall;

/**
 * The Muse call: a real conversational session, not a push-to-talk exchange.
 *
 * <p>Audio is continuous in both directions — 16 kHz PCM up, 24 kHz PCM down,
 * echo-cancelled — and capture runs from connect until the wearer hangs up.
 * Nothing in this screen gates, ducks or interrupts the microphone; muting is
 * the wearer's explicit choice and is reversible at any moment.
 *
 * <p>Eyes (video) ride the same session: while the glasses are linked the
 * glasses feed the call, otherwise the phone camera does. The Muse itself is
 * drawn locally — flat 2D on the phone, the WebGL Muse when the phone screen is
 * the thing being shown.
 */
public final class CallActivity extends Activity {

    public static final String EXTRA_RELAY = "relay";
    public static final String EXTRA_TOKEN = "token";
    public static final String EXTRA_ON_GLASSES = "on_glasses";
    public static final String EXTRA_EYES = "eyes";

    private static final int REQ_MIC = 4711;

    private LiveCall call;
    private Muse2DView muse2d;
    private Muse3DView muse3d;
    private TextView state;
    private TextView log;
    private TextView meter;
    private Button muteBtn, eyesBtn, avatarBtn, hangBtn;

    private boolean use3d, videoOn, muted, started, greeted;

    /** True while a call owns the microphone and the screen (read by the home screen's voice
     * paths).
     *
     * Android's recognizer is an Activity: opening one from the home screen during a call puts a
     * foreign screen over the call and hands the foreground back to MainActivity afterwards, whose
     * singleTask launch mode clears the call off the stack. The call says it is live so no voice
     * path opens a competing screen; the wearer's audio is never cut, it is already going out. */
    public static volatile boolean LIVE = false;
    /** Wall clock of the last logcat meter line, so the call is auditable off-screen. */
    private long lastMeterLog;
    private volatile float userLevel, museLevel;
    private final ArrayDeque<String> captions = new ArrayDeque<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private DatGlasses.Listener previousGlassesListener;
    private CamFeed camFeed;

    /** The glasses hand their frames to the call while it is up. */
    private final DatGlasses.Listener callEyes = new DatGlasses.Listener() {
        @Override public void onStatus(String text) {
            if (!started) setState(text);
        }

        @Override public void onJpeg(byte[] jpeg) {
            if (eyesGlasses) onEyesFrame(jpeg);
        }
    };

    private final LiveCall.Listener callListener = new LiveCall.Listener() {
        @Override public void onState(String text) {
            setState(text);
        }

        @Override public void onReady() {
            setState(videoOn ? "Muse live — voice and eyes" : "Muse live — talk normally");
            // A spoken hello the moment the session opens: it proves the audio return
            // path out loud (down counter) instead of leaving the wearer guessing.
            if (!greeted && call != null) {
                greeted = true;
                call.sendText("Say hello to the wearer in one short warm sentence"
                        + " and tell them what you can see.");
                Log.i("CallStats", "greeting sent — listening for the Muse's voice");
            }
        }

        @Override public void onLevel(boolean model, float level) {
            if (model) {
                if (level > museLevel) museLevel = level;
            } else if (level > userLevel) {
                userLevel = level;
            }
        }

        @Override public void onText(String text, boolean fromWearer) {
            addCaption((fromWearer ? "You: " : "Muse: ") + text);
        }

        @Override public void onEnded(String why) {
            setState("Call ended — " + why);
            main.postDelayed(CallActivity.this::finish, 1500);
        }
    };

    /** Meter decay so the Muse breathes between syllables instead of sticking. */
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            userLevel *= 0.75f;
            museLevel *= 0.75f;
            applyLevels();
            showMeter();
            main.postDelayed(this, 100);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_call);

        state = findViewById(R.id.tvCallState);
        log = findViewById(R.id.tvCallLog);
        meter = findViewById(R.id.tvCallMeter);
        muse2d = findViewById(R.id.muse2d);
        muse3d = findViewById(R.id.muse3d);
        muteBtn = findViewById(R.id.btnMute);
        eyesBtn = findViewById(R.id.btnEyes);
        avatarBtn = findViewById(R.id.btnAvatar);
        hangBtn = findViewById(R.id.btnHangUp);
        sourceBtn = findViewById(R.id.btnSource);
        detectBtn = findViewById(R.id.btnDetect);
        preview = findViewById(R.id.ivEyesPreview);
        visionTv = findViewById(R.id.tvVision);

        use3d = getIntent().getBooleanExtra(EXTRA_ON_GLASSES, false);
        videoOn = getIntent().getBooleanExtra(EXTRA_EYES, false);
        eyesGlasses = use3d;   // starting default only; the Source button switches it live
        sourceBtn.setText(eyesGlasses ? "Glasses" : "Phone cam");
        sourceBtn.setOnClickListener(v -> switchSource(!eyesGlasses));
        findViewById(R.id.btnRemember).setOnClickListener(v -> rememberPerson());
        detectBtn.setOnClickListener(v -> toggleDetect());
        findViewById(R.id.btnPeople).setOnClickListener(v ->
                startActivity(new android.content.Intent(this, PeopleActivity.class)));
        applyAvatarMode();
        avatarBtn.setOnClickListener(v -> {
            use3d = !use3d;
            applyAvatarMode();
        });
        muteBtn.setOnClickListener(v -> toggleMute());
        eyesBtn.setOnClickListener(v -> toggleEyes());
        hangBtn.setOnClickListener(v -> {
            setState("Hanging up…");
            if (call != null) call.hangUp();
        });

        if (use3d || videoOn) setEyes(true);   // glasses mode implies eyes: they are the camera

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        main.removeCallbacks(ticker);
        main.post(ticker);
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            open();
        }
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacks(ticker);
        if (call != null) call.hangUp();
        call = null;
        LIVE = false;
        if (camFeed != null) {
            camFeed.stop();
            camFeed = null;
        }
        visionExec.shutdownNow();
        if (detector != null) detector.close();
        if (DatGlasses.INSTANCE.getListener() == callEyes) {
            DatGlasses.INSTANCE.setListener(previousGlassesListener);
        }
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] grants) {
        super.onRequestPermissionsResult(code, perms, grants);
        if (code == REQ_MIC) {
            if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) {
                open();
            } else {
                setState("Microphone permission is needed — without it the call cannot carry your voice.");
            }
        }
    }

    /** A second open must never restart a live session (the home screen can replay its deep link). */
    @Override
    public void onNewIntent(android.content.Intent intent) {
        if (LIVE || started) return;
        super.onNewIntent(intent);
    }

    /** Mint a token, open the session, and keep the wearer's mic open until hang-up. */
    private void open() {
        if (started) return;
        LIVE = true;
        String relay = getIntent().getStringExtra(EXTRA_RELAY);
        String token = getIntent().getStringExtra(EXTRA_TOKEN);
        if (relay == null || token == null) {
            // Resume path: the home screen already saved the relay and token, so a
            // call can be reopened without handing the token around again.
            android.content.SharedPreferences p = getSharedPreferences("fieldkit", MODE_PRIVATE);
            if (relay == null) relay = p.getString("relay", null);
            if (token == null) token = p.getString("token", null);
        }
        if (relay == null || token == null) {
            setState("No relay token — launch the call from the Fieldkit home screen.");
            return;
        }
        started = true;
        previousGlassesListener = DatGlasses.INSTANCE.getListener();
        DatGlasses.INSTANCE.setListener(callEyes);
        call = new LiveCall(relay, token, callListener);
        if (videoOn) call.setVideoEnabled(true);
        call.start();
        Log.i("CallStats", "call open · relay " + relay + " · video " + use3d
                + " · mic open until hang-up");
    }

    private void toggleMute() {
        muted = !muted;
        if (call != null) call.setMuted(muted);
        muteBtn.setText(muted ? "Mic muted" : "Mic on");
    }

    private void toggleEyes() {
        setEyes(!videoOn);
    }

    /** Eyes on/off is idempotent so the launcher (and the glasses) can arm it up front. */
    private void setEyes(boolean on) {
        videoOn = on;
        eyesBtn.setText(videoOn ? "Eyes on" : "Eyes off");
        muse2d.setVideoMode(videoOn);
        if (call != null) call.setVideoEnabled(videoOn);
        if (videoOn) {
            if (eyesGlasses) {
                if (camFeed != null) { camFeed.stop(); camFeed = null; }
                if (!DatGlasses.INSTANCE.isStreaming() && !DatGlasses.INSTANCE.isConnecting()) {
                    try { DatGlasses.INSTANCE.connect(); } catch (Throwable ignored) { }
                }
                setState(DatGlasses.INSTANCE.isStreaming() ? "Eyes: the glasses feed the call."
                        : "Eyes: glasses selected — connecting live glasses stream…");
            } else if (camFeed == null) {
                camFeed = new CamFeed(this, (jpeg, w, h) -> {
                    if (!eyesGlasses) onEyesFrame(jpeg);
                }, 500);
                camFeed.start();
                setState("Eyes: the phone camera feeds the call.");
            }
        } else if (camFeed != null) {
            camFeed.stop();
            camFeed = null;
        }
    }

    // ------------------------------------------------------------------ Nov 2: eyes source, people, detections

    private Button sourceBtn, detectBtn;
    private android.widget.ImageView preview;
    private TextView visionTv;
    private volatile boolean eyesGlasses, detectOn, visionBusy;
    private volatile byte[] lastJpeg;
    private long lastVisionAt, lastSummaryAt;
    private String lastSummary = "";
    private tech.nullai.fieldkit.vision.Detector detector;
    private final java.util.concurrent.ExecutorService visionExec =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private final java.util.Map<String, Long> announced = new java.util.HashMap<>();

    /** Swap the AI's eyes between phone camera and glasses without touching the live session. */
    private void switchSource(boolean glasses) {
        if (glasses == eyesGlasses) return;
        eyesGlasses = glasses;
        lastJpeg = null;
        sourceBtn.setText(glasses ? "Glasses" : "Phone cam");
        if (glasses && !DatGlasses.INSTANCE.isStreaming()) {
            try { DatGlasses.INSTANCE.connect(); } catch (Throwable ignored) { }
        }
        if (videoOn) {
            setEyes(true);
        } else {
            setState("Eyes source: " + (glasses ? "glasses" : "phone camera") + " (eyes are off).");
        }
        if (call != null && videoOn) {
            call.sendText("(Context, no reply needed: the wearer just switched your camera to the "
                    + (glasses ? "Meta glasses — first-person view." : "phone camera.") + ")");
        }
        addCaption("· eyes → " + (glasses ? "glasses" : "phone"));
    }

    private void toggleDetect() {
        detectOn = !detectOn;
        detectBtn.setText(detectOn ? "Detect on" : "Detect off");
        preview.setVisibility(detectOn ? View.VISIBLE : View.GONE);
        visionTv.setVisibility(detectOn ? View.VISIBLE : View.GONE);
    }

    /** Every frame from whichever source is live: to the model, and (throttled) to the detector. */
    private void onEyesFrame(byte[] jpeg) {
        if (!videoOn) return;
        lastJpeg = jpeg;
        if (call != null) call.sendVideoFrame(jpeg);
        long now = System.currentTimeMillis();
        boolean recognise = !tech.nullai.fieldkit.people.PeopleVault.get(this).isEmpty();
        if ((!detectOn && !recognise) || visionBusy || now - lastVisionAt < 1200) return;
        lastVisionAt = now;
        visionBusy = true;
        visionExec.execute(() -> {
            try { runVision(jpeg); } catch (Throwable t) { Log.w("CallVision", "vision: " + t); }
            finally { visionBusy = false; }
        });
    }

    private tech.nullai.fieldkit.vision.Detector detector() {
        if (detector == null) detector = new tech.nullai.fieldkit.vision.Detector(this);
        return detector;
    }

    private static android.graphics.Bitmap decodeFrame(byte[] jpeg) {
        android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
        o.inSampleSize = 2;   // ~half res is plenty for BlazeFace / hands at 1 fps
        return android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, o);
    }

    private void runVision(byte[] jpeg) {
        android.graphics.Bitmap bmp = decodeFrame(jpeg);
        if (bmp == null) return;
        tech.nullai.fieldkit.vision.Detector.Result r = detector().detect(bmp, detectOn, detectOn);
        // recognition: wearer-named people only, appearance match, announce once a minute at most
        tech.nullai.fieldkit.people.PeopleVault vault = tech.nullai.fieldkit.people.PeopleVault.get(this);
        java.util.List<String> seen = new java.util.ArrayList<>();
        for (android.graphics.RectF f : r.faces) {
            float[] d = descriptor(bmp, f);
            tech.nullai.fieldkit.people.PeopleVault.Person p = vault.matchFace(d);
            if (p != null && !seen.contains(p.name)) seen.add(p.name);
        }
        long now = System.currentTimeMillis();
        java.util.List<String> fresh = new java.util.ArrayList<>();
        for (String n : seen) {
            Long at = announced.get(n);
            if (at == null || now - at > 60_000) { fresh.add(n); announced.put(n, now); }
        }
        String summary = r.summary();
        boolean sendSummary = detectOn && !summary.equals(lastSummary) && now - lastSummaryAt > 20_000;
        if (sendSummary) { lastSummary = summary; lastSummaryAt = now; }
        android.graphics.Bitmap shown = detectOn ? drawBoxes(bmp, r) : null;
        main.post(() -> {
            if (detectOn) {
                preview.setImageBitmap(shown);
                visionTv.setText(summary + (seen.isEmpty() ? "" : "\nin view: " + String.join(", ", seen))
                        + "  · " + r.ms + " ms");
            }
            if (call == null) return;
            if (!fresh.isEmpty()) {
                addCaption("· in view: " + String.join(", ", fresh));
                call.sendText("(Context, no reply needed unless useful: an on-device appearance match "
                        + "suggests " + String.join(", ", fresh) + " — whom the wearer saved — may be in view. "
                        + "It can be wrong; don't assert it.)");
            }
            if (sendSummary) {
                call.sendText("(Context, no reply needed: on-device detector sees " + summary + ".)");
            }
        });
    }

    private static android.graphics.Bitmap drawBoxes(android.graphics.Bitmap src,
                                                     tech.nullai.fieldkit.vision.Detector.Result r) {
        android.graphics.Bitmap out = src.copy(android.graphics.Bitmap.Config.ARGB_8888, true);
        android.graphics.Canvas c = new android.graphics.Canvas(out);
        android.graphics.Paint p = new android.graphics.Paint();
        p.setStyle(android.graphics.Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(2f, out.getWidth() / 160f));
        p.setColor(0xFF8FE3B0);
        for (android.graphics.RectF f : r.faces) c.drawRect(f, p);
        p.setColor(0xFFFFC857);
        for (android.graphics.RectF f : r.objectBoxes) c.drawRect(f, p);
        p.setStyle(android.graphics.Paint.Style.FILL);
        p.setColor(0xFF6EC8FF);
        for (tech.nullai.fieldkit.vision.Detector.Hand h : r.hands)
            for (float[] pt : h.points) c.drawCircle(pt[0] * out.getWidth(), pt[1] * out.getHeight(), p.getStrokeWidth() * 1.5f, p);
        return out;
    }

    /** Face crop (padded) -> the same coarse descriptor the rest of Spotter uses. */
    private static float[] descriptor(android.graphics.Bitmap bmp, android.graphics.RectF f) {
        android.graphics.Rect c = padded(bmp, f);
        if (c.width() < 8 || c.height() < 8) return null;
        int[] px = new int[c.width() * c.height()];
        bmp.getPixels(px, 0, c.width(), c.left, c.top, c.width(), c.height());
        return tech.nullai.fieldkit.people.PersonDescriptor.fromCrop(px, c.width(), c.height());
    }

    private static android.graphics.Rect padded(android.graphics.Bitmap bmp, android.graphics.RectF f) {
        float pw = f.width() * 0.2f, ph = f.height() * 0.2f;
        return new android.graphics.Rect(Math.max(0, (int) (f.left - pw)), Math.max(0, (int) (f.top - ph)),
                Math.min(bmp.getWidth(), (int) (f.right + pw)), Math.min(bmp.getHeight(), (int) (f.bottom + ph)));
    }

    /** Consent-first: only the wearer's tap + typed name ever creates an entry. */
    private void rememberPerson() {
        byte[] jpeg = lastJpeg;
        if (jpeg == null || !videoOn) {
            setState("Turn Eyes on and point at the person first.");
            return;
        }
        android.widget.EditText name = new android.widget.EditText(this);
        name.setHint("Their name");
        new android.app.AlertDialog.Builder(this)
                .setTitle("Remember this person")
                .setMessage("Saves a face crop of this frame on this phone only (encrypted). Ask them first.")
                .setView(name)
                .setPositiveButton("Save", (d, w) -> {
                    String n = name.getText().toString().trim();
                    if (n.isEmpty()) return;
                    visionExec.execute(() -> saveFromFrame(jpeg, n));
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void saveFromFrame(byte[] jpeg, String n) {
        android.graphics.Bitmap bmp = decodeFrame(jpeg);
        if (bmp == null) return;
        java.util.List<android.graphics.RectF> faces = detector().faces(bmp);
        android.graphics.RectF best = null;
        for (android.graphics.RectF f : faces)
            if (best == null || f.width() * f.height() > best.width() * best.height()) best = f;
        if (best == null) {
            main.post(() -> setState("No face found in the frame — nothing saved. Try again closer."));
            return;
        }
        float[] d = descriptor(bmp, best);
        android.graphics.Rect c = padded(bmp, best);
        android.graphics.Bitmap crop = android.graphics.Bitmap.createBitmap(bmp, c.left, c.top, c.width(), c.height());
        float s = 160f / Math.max(crop.getWidth(), crop.getHeight());
        if (s < 1f) crop = android.graphics.Bitmap.createScaledBitmap(crop,
                Math.round(crop.getWidth() * s), Math.round(crop.getHeight() * s), true);
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        crop.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, bos);
        String thumb = android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP);
        tech.nullai.fieldkit.people.PeopleVault.get(this).add(n, d, thumb);
        announced.put(n, System.currentTimeMillis());
        main.post(() -> {
            setState("Saved " + n + " (this phone only). Add their voice from People.");
            addCaption("· remembered " + n);
            if (call != null) call.sendText("(Context: the wearer just saved the person in view as "
                    + n + ". You may use that name for them.)");
        });
    }

    private void applyAvatarMode() {
        muse3d.setVisibility(use3d ? View.VISIBLE : View.GONE);
        muse2d.setVisibility(use3d ? View.GONE : View.VISIBLE);
        avatarBtn.setText(use3d ? "3D" : "2D");
        applyLevels();
    }

    private void applyLevels() {
        if (use3d) {
            muse3d.setLevels(userLevel, museLevel);
            muse3d.setSpeaking(museLevel > 0.06f);
        } else {
            muse2d.setLevels(userLevel, museLevel);
            muse2d.setSpeaking(museLevel > 0.06f);
            muse2d.setStatus(videoOn ? "voice + eyes" : "voice");
        }
    }

    private void addCaption(String line) {
        captions.addLast(line);
        while (captions.size() > 5) captions.removeFirst();
        StringBuilder sb = new StringBuilder();
        for (String c : captions) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(c);
        }
        log.setText(sb.toString());
    }

    /** On-screen proof: the mic is open and the session is carrying both ways. */
    private void showMeter() {
        if (meter == null) return;
        if (call == null) {
            meter.setText("mic —  ·  session not open");
            return;
        }
        String line = String.format(java.util.Locale.US,
                "mic %.2f  ·  up %d  ·  Muse %.2f  ·  down %d  ·  eyes %d%s",
                call.micLevel(), call.uplinkChunks(), museLevel, call.downlinkChunks(),
                call.videoFrames(), muted ? "  ·  muted" : "");
        meter.setText(line);
        // The same numbers go to logcat (adb logcat -s CallStats:I) so a call can be
        // audited with the screen off. Capture is never gated, so a frozen counter
        // here means the link died — never that the wearer stopped talking.
        long now = System.currentTimeMillis();
        if (now - lastMeterLog >= 2000) {
            lastMeterLog = now;
            Log.i("CallStats", line + "  ·  " + (state != null ? state.getText() : ""));
        }
    }

    private void setState(String text) {
        state.setText(text);
    }
}
