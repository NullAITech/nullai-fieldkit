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
            if (videoOn && call != null) call.sendVideoFrame(jpeg);
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

        use3d = getIntent().getBooleanExtra(EXTRA_ON_GLASSES, false);
        videoOn = getIntent().getBooleanExtra(EXTRA_EYES, false);
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
        if (camFeed != null) {
            camFeed.stop();
            camFeed = null;
        }
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

    /** Mint a token, open the session, and keep the wearer's mic open until hang-up. */
    private void open() {
        if (started) return;
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
            if (use3d) {
                setState("Eyes: the glasses feed the call.");
            } else if (camFeed == null) {
                camFeed = new CamFeed(this, (jpeg, w, h) -> {
                    if (videoOn && call != null) call.sendVideoFrame(jpeg);
                }, 500);
                camFeed.start();
                setState("Eyes: the phone camera feeds the call.");
            }
        } else if (camFeed != null) {
            camFeed.stop();
            camFeed = null;
        }
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
