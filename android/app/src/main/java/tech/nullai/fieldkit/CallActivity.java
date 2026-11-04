package tech.nullai.fieldkit;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import tech.nullai.fieldkit.avatar.Muse2DView;
import tech.nullai.fieldkit.avatar.Muse3DView;
import tech.nullai.fieldkit.live.LiveCall;
import tech.nullai.fieldkit.live.RtmpBroadcastManager;
import tech.nullai.fieldkit.people.CharacterBoard;
import tech.nullai.fieldkit.people.PeopleVault;
import tech.nullai.fieldkit.people.PersonDescriptor;
import tech.nullai.fieldkit.vision.Detector;
import tech.nullai.fieldkit.vision.VisionMemory;
import tech.nullai.fieldkit.voice.WakePhrase;

/**
 * The Muse call: a real conversational session with live AI closed captions,
 * conversation history switching, and 4-angle Face + Body Character Board recognition.
 */
public final class CallActivity extends Activity {

    public static final String EXTRA_RELAY = "relay";
    public static final String EXTRA_TOKEN = "token";
    public static final String EXTRA_ON_GLASSES = "on_glasses";
    public static final String EXTRA_EYES = "eyes";
    public static final String EXTRA_MISSION = "mission";

    private static final int REQ_MIC = 4711;
    private static final int REQ_DAT_CAM = 4101;
    private static final int REQ_CHATS = 4202;

    private LiveCall call;
    private Muse2DView muse2d;
    private Muse3DView muse3d;
    private TextView state;
    private TextView log;
    private TextView meter;
    private TextView liveCaptionsTv;
    private LinearLayout personCardOverlay;
    private Button muteBtn, eyesBtn, avatarBtn, hangBtn, recBtn, captionsBtn, voiceLockBtn, chatsBtn, devBtn, rtmpBtn;
    private boolean pipExpanded = false;
    private boolean devTelemetryVisible = false;
    private boolean voiceLockEnabled = false;
    private long lastPreviewPaintAt = 0L;
    private long lastRollingFrameAt = 0L;

    private boolean use3d, videoOn, muted, started, greeted;
    private boolean captionsEnabled = true;
    private String activeConvId;
    private String lastStreamingSpeaker = "";
    private String lastStreamingCaptionLine = "";
    private String lastPersistedMuseText = "";
    private String lastPersistedUserText = "";
    private WakePhrase.Command lastExecutedCallCommand = null;
    private long lastExecutedCallCommandMs = 0L;

    public static volatile boolean LIVE = false;
    private long lastMeterLog;
    private volatile float userLevel, museLevel;
    private final ArrayDeque<String> captions = new ArrayDeque<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private DatGlasses.Listener previousGlassesListener;
    private CamFeed camFeed;

    private final Runnable hideLiveCaptionsTask = () -> {
        if (liveCaptionsTv != null) liveCaptionsTv.setVisibility(View.GONE);
    };

    private String dismissedPersonId = "";
    private long dismissedPersonUntil = 0L;
    private String activePopupPersonId = "";
    private long activePopupUpdatedAt = 0L;

    private final VisionMemory visionMemory = VisionMemory.get();
    private long lastVisionMemoryPushAt = 0L;
    private Button btnCallModeIntel, btnCallModeGodVoice, btnCallModeCoder, btnCallModeCustom;

    /** Rolling 4-frame buffer for multi-angle Character Board extraction. */
    private final ArrayDeque<Bitmap> rollingFrames = new ArrayDeque<>(4);
    private volatile RectF lastBestFaceBox;
    private volatile RectF lastBestBodyBox;

    private final DatGlasses.Listener callEyes = new DatGlasses.Listener() {
        @Override public void onStatus(String text) {
            if (!started || !DatGlasses.INSTANCE.isStreaming()) {
                main.post(() -> setState(text));
            }
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
            if (call != null) {
                byte[] cur = lastJpeg;
                if (cur != null && videoOn) {
                    call.sendVideoFrameImmediate(cur);
                }
                String roster = buildSavedPeopleContext();
                if (!roster.isEmpty()) {
                    call.sendContext(roster);
                }
                if (!greeted) {
                    greeted = true;
                    call.sendText("Say hello to the wearer in one short warm sentence"
                            + " and tell them what you can see right now.");
                    Log.i("CallStats", "greeting sent — listening for the Muse's voice");
                }
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
            onStreamingTranscript(text, fromWearer);
        }

        @Override public void onVoiceLockNotice(String notice, boolean blockedBystander) {
            showLiveCaptionBanner(notice);
            addCaption("· " + notice);
            updateVoiceLockButtonUi();
        }

        @Override public void onEnded(String why) {
            setState("Call ended — " + why);
            main.postDelayed(CallActivity.this::finish, 1500);
        }
    };

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
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_call);

        state = findViewById(R.id.tvCallState);
        log = findViewById(R.id.tvCallLog);
        meter = findViewById(R.id.tvCallMeter);
        liveCaptionsTv = findViewById(R.id.tvLiveCaptions);
        personCardOverlay = findViewById(R.id.personCardOverlay);
        muse2d = findViewById(R.id.muse2d);
        muse3d = findViewById(R.id.muse3d);
        muteBtn = findViewById(R.id.btnMute);
        eyesBtn = findViewById(R.id.btnEyes);
        avatarBtn = findViewById(R.id.btnAvatar);
        hangBtn = findViewById(R.id.btnHangUp);
        sourceBtn = findViewById(R.id.btnSource);
        detectBtn = findViewById(R.id.btnDetect);
        recBtn = findViewById(R.id.btnCallRec);
        captionsBtn = findViewById(R.id.btnCallCaptions);
        voiceLockBtn = findViewById(R.id.btnCallVoiceLock);
        chatsBtn = findViewById(R.id.btnCallChats);
        devBtn = findViewById(R.id.btnCallDev);
        rtmpBtn = findViewById(R.id.btnCallRtmp);
        preview = findViewById(R.id.ivEyesPreview);
        visionTv = findViewById(R.id.tvVision);

        updateCallRtmpButtonUi();
        if (rtmpBtn != null) {
            rtmpBtn.setOnClickListener(v -> {
                RtmpBroadcastManager.get(this).showBroadcastDialog(this, getCallRelay(), getCallToken(), this::updateCallRtmpButtonUi);
            });
        }

        voiceLockEnabled = getSharedPreferences("fieldkit", MODE_PRIVATE).getBoolean("voice_lock_enabled", false);
        updateVoiceLockButtonUi();
        if (voiceLockBtn != null) {
            voiceLockBtn.setOnClickListener(v -> {
                startActivity(new android.content.Intent(this, VoiceGateActivity.class));
            });
        }

        if (devBtn != null) {
            devBtn.setOnClickListener(v -> {
                devTelemetryVisible = !devTelemetryVisible;
                if (meter != null) {
                    meter.setVisibility(devTelemetryVisible ? View.VISIBLE : View.GONE);
                }
                devBtn.setTextColor(devTelemetryVisible ? 0xFF34D399 : 0xFFCBD5E1);
                devBtn.setBackgroundResource(devTelemetryVisible ? R.drawable.bg_accent_emerald : R.drawable.bg_dark_pill);
            });
        }

        ConversationStore store = ConversationStore.get(this);
        captionsEnabled = store.isCaptionsEnabled();
        ConversationStore.Conversation activeConv = store.getActiveOrCreate("Muse Live Call", "call");
        activeConvId = activeConv.id;
        updateCaptionsButtonUi();

        if (captionsBtn != null) {
            captionsBtn.setOnClickListener(v -> {
                captionsEnabled = !captionsEnabled;
                ConversationStore.get(this).setCaptionsEnabled(captionsEnabled);
                updateCaptionsButtonUi();
                if (!captionsEnabled && liveCaptionsTv != null) {
                    liveCaptionsTv.setVisibility(View.GONE);
                } else if (captionsEnabled && liveCaptionsTv != null) {
                    showLiveCaptionBanner("🔊 LIVE CAPTIONS ENABLED — AI & Wearer subtitles active");
                }
            });
        }

        if (chatsBtn != null) {
            chatsBtn.setOnClickListener(v ->
                    startActivityForResult(new Intent(this, ConversationsActivity.class), REQ_CHATS));
        }

        if (recBtn != null) {
            recBtn.setText(DemoRecorder.get().isRecording() ? DemoRecorder.get().currentButtonLabel() : "● REC");
            recBtn.setOnClickListener(v -> DemoRecorder.get().toggle(this));
        }
        if (preview != null) {
            preview.setOnClickListener(v -> togglePipExpanded());
        }

        use3d = getIntent().getBooleanExtra(EXTRA_ON_GLASSES, false) || DatGlasses.INSTANCE.isStreaming();
        videoOn = getIntent().getBooleanExtra(EXTRA_EYES, false) || use3d;
        eyesGlasses = use3d;
        detectOn = true;
        sourceBtn.setText(eyesGlasses ? "Glasses" : "Phone cam");
        sourceBtn.setOnClickListener(v -> switchSource(!eyesGlasses));
        findViewById(R.id.btnRemember).setOnClickListener(v -> rememberPerson());
        detectBtn.setText("Detect on");
        detectBtn.setOnClickListener(v -> toggleDetect());
        if (visionTv != null) {
            visionTv.setVisibility(View.VISIBLE);
        }
        findViewById(R.id.btnPeople).setOnClickListener(v ->
                startActivity(new Intent(this, PeopleActivity.class)));
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

        initCallModeSelector();

        if (use3d || videoOn) setEyes(true);

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
        }
    }

    private void updateCaptionsButtonUi() {
        if (captionsBtn == null) return;
        captionsBtn.setText(captionsEnabled ? "CC: ON" : "CC: OFF");
        captionsBtn.setTextColor(captionsEnabled ? 0xFF34D399 : 0xFF94A3B8);
        captionsBtn.setBackgroundResource(captionsEnabled ? R.drawable.bg_accent_emerald : R.drawable.bg_dark_pill);
    }

    private void updateCallRtmpButtonUi() {
        if (rtmpBtn == null) return;
        boolean live = RtmpBroadcastManager.get(this).isLive();
        rtmpBtn.setText(RtmpBroadcastManager.get(this).getButtonBadgeText());
        rtmpBtn.setTextColor(live ? 0xFFFFFFFF : 0xFFF59E0B);
        rtmpBtn.setBackgroundResource(live ? R.drawable.bg_accent_rose : R.drawable.bg_dark_pill);
    }

    private String getCallRelay() {
        String relay = getIntent().getStringExtra(EXTRA_RELAY);
        if (relay == null || relay.isEmpty()) {
            relay = getSharedPreferences("fieldkit", MODE_PRIVATE).getString("relay", "");
        }
        return relay != null ? relay : "";
    }

    private String getCallToken() {
        String token = getIntent().getStringExtra(EXTRA_TOKEN);
        if (token == null || token.isEmpty()) {
            token = getSharedPreferences("fieldkit", MODE_PRIVATE).getString("token", "");
        }
        return token != null ? token : "";
    }

    private void updateVoiceLockButtonUi() {
        if (voiceLockBtn == null) return;
        boolean enrolled = (call != null && call.isVoiceEnrolled())
                || tech.nullai.fieldkit.voice.OwnerPrintPrefs.load(this).isEnrolled();
        if (voiceLockEnabled) {
            voiceLockBtn.setText(enrolled ? "🔒 My Voice" : "🔒 Voice Auto");
            voiceLockBtn.setTextColor(0xFF34D399);
            voiceLockBtn.setBackgroundResource(R.drawable.bg_accent_emerald);
        } else {
            voiceLockBtn.setText("🔓 All Voices");
            voiceLockBtn.setTextColor(0xFF94A3B8);
            voiceLockBtn.setBackgroundResource(R.drawable.bg_dark_pill);
        }
    }

    private void initCallModeSelector() {
        btnCallModeIntel = findViewById(R.id.btnCallModeIntel);
        btnCallModeGodVoice = findViewById(R.id.btnCallModeGodVoice);
        btnCallModeCoder = findViewById(R.id.btnCallModeCoder);
        btnCallModeCustom = findViewById(R.id.btnCallModeCustom);

        if (btnCallModeIntel != null) btnCallModeIntel.setOnClickListener(v -> selectCallMode(SpotterMode.MODE_INTEL));
        if (btnCallModeGodVoice != null) btnCallModeGodVoice.setOnClickListener(v -> selectCallMode(SpotterMode.MODE_GOD_VOICE));
        if (btnCallModeCoder != null) btnCallModeCoder.setOnClickListener(v -> selectCallMode(SpotterMode.MODE_VIBE_CODER));
        if (btnCallModeCustom != null) btnCallModeCustom.setOnClickListener(v -> promptCallCustomObjective());

        updateCallModeUi();
    }

    private void updateCallModeUi() {
        SpotterMode.ModeInfo mi = SpotterMode.getActiveMode(this);
        setCallModeBtnState(btnCallModeIntel, SpotterMode.MODE_INTEL.equals(mi.key));
        setCallModeBtnState(btnCallModeGodVoice, SpotterMode.MODE_GOD_VOICE.equals(mi.key));
        setCallModeBtnState(btnCallModeCoder, SpotterMode.MODE_VIBE_CODER.equals(mi.key));
        setCallModeBtnState(btnCallModeCustom, SpotterMode.MODE_DIRECTIVE.equals(mi.key));
    }

    private void setCallModeBtnState(Button btn, boolean active) {
        if (btn == null) return;
        btn.setBackgroundResource(active ? R.drawable.bg_accent_emerald : R.drawable.bg_dark_pill);
        btn.setTextColor(active ? 0xFF34D399 : 0xFFCBD5E1);
    }

    private void selectCallMode(String modeKey) {
        SpotterMode.setActiveMode(this, modeKey, null);
        updateCallModeUi();
        SpotterMode.ModeInfo mi = SpotterMode.getMode(modeKey);
        String obj = SpotterMode.getActiveObjective(this);
        showLiveCaptionBanner("🎯 MODE: " + mi.title.toUpperCase(Locale.US) + " — " + mi.tag);
        addCaption("· switched mode to " + mi.title);
        if (call != null) {
            call.sendContext("[SPOTTER PERSONA ACTIVATED]: The wearer selected " + mi.title
                    + " (" + mi.tag + "). Active Objective: \"" + obj + "\".\nDirective: "
                    + mi.promptDirective);
        }
        syncModeToRelayAsync(modeKey, obj);
    }

    private void promptCallCustomObjective() {
        android.widget.EditText et = new android.widget.EditText(this);
        et.setHint("e.g. Network with people to sell my services");
        et.setText(SpotterMode.getActiveObjective(this));
        new android.app.AlertDialog.Builder(this)
                .setTitle("🎯 Custom Spotter Directive")
                .setMessage("What specific goal or task should Spotter co-pilot?")
                .setView(et)
                .setPositiveButton("Set Directive", (d, w) -> {
                    String goal = et.getText().toString().trim();
                    SpotterMode.setActiveMode(this, SpotterMode.MODE_DIRECTIVE, goal);
                    updateCallModeUi();
                    SpotterMode.ModeInfo mi = SpotterMode.getMode(SpotterMode.MODE_DIRECTIVE);
                    showLiveCaptionBanner("🎯 DIRECTIVE: " + (goal.isEmpty() ? mi.title : goal));
                    addCaption("· custom directive: " + goal);
                    if (call != null) {
                        call.sendContext("[SPOTTER PERSONA ACTIVATED]: Custom Objective: \"" + goal
                                + "\". Proactively watch the Vision Memory stream and help wearer achieve this goal.");
                    }
                    syncModeToRelayAsync(SpotterMode.MODE_DIRECTIVE, goal);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void syncModeToRelayAsync(String modeKey, String goal) {
        new Thread(() -> {
            try {
                String relay = getIntent().getStringExtra(EXTRA_RELAY);
                String token = getIntent().getStringExtra(EXTRA_TOKEN);
                if (relay == null || token == null || relay.isEmpty() || token.isEmpty()) {
                    android.content.SharedPreferences sp = getSharedPreferences("fieldkit", MODE_PRIVATE);
                    relay = sp.getString("relay", null);
                    token = sp.getString("token", null);
                }
                if (relay == null || token == null || relay.isEmpty() || token.isEmpty()) return;
                org.json.JSONObject body = new org.json.JSONObject()
                        .put("mode", modeKey)
                        .put("goal", goal != null ? goal : "")
                        .put("answers", new org.json.JSONArray())
                        .put("map_hud", false);
                okhttp3.Request r = new okhttp3.Request.Builder()
                        .url(relay.replaceAll("/+$", "") + "/v1/mode")
                        .header("Authorization", "Bearer " + token)
                        .post(okhttp3.RequestBody.create(body.toString(), okhttp3.MediaType.get("application/json")))
                        .build();
                new okhttp3.OkHttpClient().newCall(r).execute().close();
            } catch (Throwable ignored) { }
        }, "relay-mode-sync").start();
    }

    private void showVoiceLockDialog() {
        boolean enrolled = (call != null && call.isVoiceEnrolled())
                || tech.nullai.fieldkit.voice.OwnerPrintPrefs.load(this).isEnrolled();
        long blocked = call != null ? call.blockedBystanderCount() : 0L;
        String msg = "🎯 HOW WEARER-ONLY VOICE LOCK WORKS:\n\n"
                + "1. Why use it? It locks the microphone to YOUR vocal frequency so bystanders, TV audio, or coffee shop chatter won't confuse Muse or trigger random answers.\n\n"
                + "2. How to calibrate: Tap 'Calibrate My Voice Now', then say one clear sentence into your phone or glasses. Spotter immediately learns your tone.\n\n"
                + "3. Current Status:\n"
                + "   • Mode: " + (voiceLockEnabled ? "🔒 ACTIVE (My Voice Only)" : "🔓 OFF (Listening to All Voices)") + "\n"
                + "   • Voiceprint: " + (enrolled ? "Enrolled & Ready ✓" : "Not yet enrolled (Listening to all)") + "\n"
                + "   • Bystanders Blocked: " + blocked + "\n\n"
                + "💡 Tip: If you are alone or having trouble being heard, tap 'Turn Lock Off' for instant open-mic mode!";
        new android.app.AlertDialog.Builder(this)
                .setTitle("🔒 Voice Gate & Tutorial")
                .setMessage(msg)
                .setPositiveButton("🎙️ Calibrate My Voice", (d, w) -> {
                    voiceLockEnabled = true;
                    getSharedPreferences("fieldkit", MODE_PRIVATE).edit()
                            .putBoolean("voice_lock_enabled", true).apply();
                    if (call != null) {
                        call.setVoiceLockEnabled(true);
                        call.lockCurrentVoiceNow();
                    }
                    updateVoiceLockButtonUi();
                    showLiveCaptionBanner("🎙️ Say a sentence now to lock your voice!");
                })
                .setNeutralButton(voiceLockEnabled ? "🔓 Turn Lock Off (Open Mic)" : "🔒 Turn Lock On", (d, w) -> {
                    voiceLockEnabled = !voiceLockEnabled;
                    getSharedPreferences("fieldkit", MODE_PRIVATE).edit()
                            .putBoolean("voice_lock_enabled", voiceLockEnabled).apply();
                    if (call != null) call.setVoiceLockEnabled(voiceLockEnabled);
                    updateVoiceLockButtonUi();
                    showLiveCaptionBanner(voiceLockEnabled
                            ? "🔒 Wearer-Only Voice Lock ENABLED"
                            : "🔓 Voice Lock OFF — Open Mic active");
                })
                .setNegativeButton("Done", null)
                .show();
    }

    private void showLiveCaptionBanner(String line) {
        if (!captionsEnabled || liveCaptionsTv == null || line == null || line.trim().isEmpty()) return;
        liveCaptionsTv.setText(line);
        liveCaptionsTv.setVisibility(View.VISIBLE);
        main.removeCallbacks(hideLiveCaptionsTask);
        main.postDelayed(hideLiveCaptionsTask, 9000);
    }

    private void onStreamingTranscript(String text, boolean fromWearer) {
        if (text == null || text.trim().isEmpty()) return;
        String clean = text.trim();
        String prefix = fromWearer ? "You: " : "Muse: ";
        String line = prefix + clean;

        // Update live subtitle overlay
        showLiveCaptionBanner((fromWearer ? "🎙️ YOU: " : "🔊 MUSE AI: ") + clean);

        // Update rolling caption log: replace this speaker's previous streaming
        // snapshot wherever it sits (a "· notice" caption may intervene), so
        // growing partials never stack up as duplicate lines.
        String speakerKey = fromWearer ? "You" : "Muse";
        if (speakerKey.equals(lastStreamingSpeaker) && !lastStreamingCaptionLine.isEmpty()) {
            captions.remove(lastStreamingCaptionLine);
        }
        lastStreamingSpeaker = speakerKey;
        lastStreamingCaptionLine = line;
        addCaption(line);

        // Persist one turn per utterance: LiveCall sends cumulative snapshots,
        // so upsert while the snapshot grows instead of appending each partial.
        if (fromWearer) {
            WakePhrase.Result wp = WakePhrase.parse(clean);
            if (wp != null && wp.isCommand()) {
                long now = System.currentTimeMillis();
                if (wp.command != lastExecutedCallCommand || now - lastExecutedCallCommandMs > 5000L) {
                    lastExecutedCallCommand = wp.command;
                    lastExecutedCallCommandMs = now;
                    executeCallSpokenCommand(wp.command);
                }
            }
            if (!clean.equals(lastPersistedUserText)) {
                lastPersistedUserText = clean;
                ConversationStore.get(this).appendStreamingMessage(activeConvId, "You", clean);
            }
        } else {
            if (!clean.equals(lastPersistedMuseText)) {
                lastPersistedMuseText = clean;
                ConversationStore.get(this).appendStreamingMessage(activeConvId, "Muse", clean);
            }
        }
    }

    private void executeCallSpokenCommand(WakePhrase.Command cmd) {
        if (cmd == null) return;
        switch (cmd) {
            case PROTOCOL_ZERO:
                showLiveCaptionBanner("🛡️ ZOTH PROTOCOL ZERO: Emergency Lockdown Active");
                addCaption("· [TRIGGER] ZOTH PROTOCOL ZERO executed");
                if (personCardOverlay != null) personCardOverlay.setVisibility(View.GONE);
                if (call != null) call.sendContext("[EMERGENCY LOCKDOWN]: The wearer triggered Protocol Zero. Mute active chatter and enter silent security mode.");
                break;
            case GHOST_VEIL:
                showLiveCaptionBanner("👁️ OBSIDIAN GHOST VEIL: Silent Stealth Mode Active");
                addCaption("· [TRIGGER] OBSIDIAN GHOST VEIL active");
                if (call != null) call.setMuted(true);
                break;
            case SOUL_AWAKEN:
                showLiveCaptionBanner("⚡ ZOTH SOUL AWAKEN: 3D Presence Engaged");
                addCaption("· [TRIGGER] SOUL AWAKEN active");
                use3d = true;
                applyAvatarMode();
                if (call != null) call.sendText("I hear you, sovereign. What shall we conquer?");
                break;
            case HORIZON_SEVER:
                showLiveCaptionBanner("🛑 APERTURE HORIZON SEVER: Severing Link");
                addCaption("· [TRIGGER] HORIZON SEVER executed");
                if (call != null) call.hangUp();
                finish();
                break;
            case CHARACTER_LOCK:
                showLiveCaptionBanner("📸 CHRONO CHARACTER LOCK: Snapping Person");
                addCaption("· [TRIGGER] CHARACTER LOCK active");
                rememberPerson();
                break;
            case TIMELINE_ECHO:
                showLiveCaptionBanner("📜 VISION TIMELINE ECHO: Querying Memory");
                addCaption("· [TRIGGER] TIMELINE ECHO active");
                if (call != null) {
                    call.sendText("Summarize what was recently logged in your Vision Memory timeline in one short sentence.");
                }
                break;
            case ORACLE_WHISPER:
                selectCallMode(SpotterMode.MODE_GOD_VOICE);
                break;
            case SYNTAX_MATRIX:
                selectCallMode(SpotterMode.MODE_VIBE_CODER);
                break;
            case SOCIAL_RECON:
                selectCallMode(SpotterMode.MODE_INTEL);
                break;
            case SOVEREIGN_VECTOR:
                promptCallCustomObjective();
                break;
            case BROADCAST_START:
                showLiveCaptionBanner("🔴 ZOTH BROADCAST START: Launching RTMP Live Stream");
                addCaption("· [TRIGGER] BROADCAST START active");
                {
                    RtmpBroadcastManager.get(this).startActiveBroadcast(getCallRelay(), getCallToken(), (active, platform, detail) -> {
                        runOnUiThread(() -> {
                            showLiveCaptionBanner(active ? "🔴 RTMP BROADCAST LIVE (" + platform + ")" : "⚠️ RTMP: " + detail);
                            addCaption("· [RTMP] " + detail);
                            updateCallRtmpButtonUi();
                        });
                    });
                }
                break;
            case BROADCAST_STOP:
                showLiveCaptionBanner("⏹️ APERTURE BROADCAST STOP: Terminating Live Stream");
                addCaption("· [TRIGGER] BROADCAST STOP active");
                {
                    RtmpBroadcastManager.get(this).stopBroadcast(getCallRelay(), getCallToken(), (active, platform, detail) -> {
                        runOnUiThread(() -> {
                            showLiveCaptionBanner("⏹️ RTMP BROADCAST STOPPED");
                            addCaption("· [RTMP] Broadcast stopped.");
                            updateCallRtmpButtonUi();
                        });
                    });
                }
                break;
            default:
                break;
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
    protected void onResume() {
        super.onResume();
        DatGlasses.INSTANCE.attachActivity(this);
        DatGlasses.INSTANCE.forceDevMode(this);
        captionsEnabled = ConversationStore.get(this).isCaptionsEnabled();
        updateCaptionsButtonUi();
        updateCallRtmpButtonUi();
        DemoRecorder.get().setUiListener((recording, label) -> {
            if (recBtn != null) {
                recBtn.setText(recording ? label : "● REC");
                recBtn.setTextColor(recording ? 0xFFFF5252 : 0xFFFF8A8A);
            }
        });
    }

    @Override
    protected void onPause() {
        DatGlasses.INSTANCE.detachActivity(this);
        super.onPause();
    }

    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code == REQ_CHATS) {
            captionsEnabled = ConversationStore.get(this).isCaptionsEnabled();
            updateCaptionsButtonUi();
            if (result == RESULT_OK && data != null) {
                String action = data.getStringExtra(ConversationsActivity.EXTRA_ACTION);
                String convId = data.getStringExtra(ConversationsActivity.EXTRA_CONV_ID);
                if (convId != null) activeConvId = convId;
                if (ConversationsActivity.ACTION_NEW_CALL.equals(action)
                        || ConversationsActivity.ACTION_NEW_CHAT.equals(action)) {
                    captions.clear();
                    lastStreamingSpeaker = "";
                    lastStreamingCaptionLine = "";
                    log.setText("New conversation started.");
                    showLiveCaptionBanner("✨ Started new conversation thread");
                    if (call != null) {
                        call.sendText("(System: the wearer started a fresh conversation topic. Greet them briefly.)");
                    }
                }
            }
            return;
        }
        DatGlasses.INSTANCE.forceDevMode(this);
        DatGlasses.INSTANCE.handleIntent(data);
        if (code == 0 || code == REQ_DAT_CAM) {
            if (code == REQ_DAT_CAM) {
                DatGlasses.INSTANCE.parseCameraPermissionResult(result, data);
            }
            if (eyesGlasses && !DatGlasses.INSTANCE.isStreaming()) {
                DatGlasses.INSTANCE.connect();
            }
        }
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacks(ticker);
        main.removeCallbacks(hideLiveCaptionsTask);
        if (call != null) call.hangUp();
        call = null;
        LIVE = false;
        if (camFeed != null) {
            camFeed.stop();
            camFeed = null;
        }
        visionExec.shutdownNow();
        previewExec.shutdownNow();
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

    @Override
    public void onNewIntent(Intent intent) {
        if (LIVE || started) return;
        super.onNewIntent(intent);
    }

    private void open() {
        if (started) return;
        String relay = getIntent().getStringExtra(EXTRA_RELAY);
        String token = getIntent().getStringExtra(EXTRA_TOKEN);
        if (relay == null || token == null) {
            android.content.SharedPreferences p = getSharedPreferences("fieldkit", MODE_PRIVATE);
            if (relay == null) relay = p.getString("relay", null);
            if (token == null) token = p.getString("token", null);
        }
        if (relay == null || token == null) {
            LIVE = false;
            setState("No relay token — launch the call from the Fieldkit home screen.");
            return;
        }
        LIVE = true;
        started = true;
        previousGlassesListener = DatGlasses.INSTANCE.getListener();
        DatGlasses.INSTANCE.setListener(callEyes);
        String missionExtra = getIntent().getStringExtra(EXTRA_MISSION);
        String initialCtx = buildSavedPeopleContext();
        if (missionExtra != null && !missionExtra.trim().isEmpty()) {
            initialCtx = "[CURRENT ACTIVE MISSION & GOAL CONTEXT]:\n" + missionExtra.trim() + "\n\n" + initialCtx;
        }
        call = new LiveCall(this, relay, token, initialCtx, callListener);
        call.configureVoiceLock(this, voiceLockEnabled);
        if (videoOn) call.setVideoEnabled(true);
        call.start();
        updateVoiceLockButtonUi();
        Log.i("CallStats", "call open · relay " + relay + " · video " + use3d
                + " · mic open until hang-up");
    }

    private String buildSavedPeopleContext() {
        List<PeopleVault.Person> list = PeopleVault.get(this).all();
        if (list.isEmpty()) {
            return "[SAVED PEOPLE ROSTER]: None saved yet. If the wearer asks to identify someone, describe their attire and tell them to tap Save Person to build a 4-Angle Character Board.";
        }
        StringBuilder sb = new StringBuilder("[SAVED PEOPLE ROSTER (Wearer's On-Device 4-Angle Character Boards — you have full access to these saved people)]: ");
        int count = Math.min(12, list.size());
        for (int i = 0; i < count; i++) {
            PeopleVault.Person p = list.get(i);
            if (i > 0) sb.append(" | ");
            sb.append(p.name).append(" (Role: ").append(p.role);
            if (p.attire != null && !p.attire.isEmpty()) sb.append(", Attire: ").append(p.attire);
            if (p.buildSummary != null && !p.buildSummary.isEmpty()) sb.append(", Frame: ").append(p.buildSummary);
            if (p.notes != null && !p.notes.isEmpty()) sb.append(", Notes: ").append(p.notes);
            sb.append(", Seen ").append(p.seenCount).append("x)");
        }
        return sb.toString();
    }

    private void toggleMute() {
        muted = !muted;
        if (call != null) call.setMuted(muted);
        muteBtn.setText(muted ? "Mic muted" : "Mic on");
    }

    private void toggleEyes() {
        setEyes(!videoOn);
    }

    private void setEyes(boolean on) {
        videoOn = on;
        eyesBtn.setText(videoOn ? "Eyes on" : "Eyes off");
        muse2d.setVideoMode(videoOn);
        if (preview != null) {
            preview.setVisibility(videoOn ? View.VISIBLE : View.GONE);
        }
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
                }, 250);
                camFeed.start();
                setState("Eyes: the phone camera feeds the call.");
            }
        } else if (camFeed != null) {
            camFeed.stop();
            camFeed = null;
        }
    }

    private void togglePipExpanded() {
        if (preview == null) return;
        pipExpanded = !pipExpanded;
        android.view.ViewGroup.LayoutParams lp = preview.getLayoutParams();
        float d = getResources().getDisplayMetrics().density;
        if (pipExpanded) {
            lp.width = android.view.ViewGroup.LayoutParams.MATCH_PARENT;
            lp.height = android.view.ViewGroup.LayoutParams.MATCH_PARENT;
        } else {
            lp.width = Math.round(148 * d);
            lp.height = Math.round(196 * d);
        }
        preview.setLayoutParams(lp);
    }

    // ------------------------------------------------------------------ Eyes source, people, detections

    private Button sourceBtn, detectBtn;
    private ImageView preview;
    private TextView visionTv;
    private volatile boolean eyesGlasses, detectOn, visionBusy;
    private volatile byte[] lastJpeg;
    private long lastVisionAt, lastSummaryAt;
    private String lastSummary = "";
    private Detector detector;
    private final ExecutorService visionExec = Executors.newSingleThreadExecutor();
    private final ExecutorService previewExec = Executors.newSingleThreadExecutor();
    private final Map<String, Long> announced = new HashMap<>();

    private void switchSource(boolean glasses) {
        if (glasses == eyesGlasses && videoOn) return;
        eyesGlasses = glasses;
        lastJpeg = null;
        sourceBtn.setText(glasses ? "Glasses" : "Phone cam");
        if (glasses && !DatGlasses.INSTANCE.isStreaming()) {
            try { DatGlasses.INSTANCE.connect(); } catch (Throwable ignored) { }
        }
        setEyes(true);
        if (call != null && videoOn) {
            call.sendContext("(System Context: the wearer just switched your live camera to the "
                    + (glasses ? "Ray-Ban Meta glasses — first-person POV view." : "phone camera.") + ")");
        }
        addCaption("· eyes → " + (glasses ? "glasses" : "phone"));
    }

    private void toggleDetect() {
        detectOn = !detectOn;
        detectBtn.setText(detectOn ? "Detect on" : "Detect off");
        preview.setVisibility(videoOn ? View.VISIBLE : View.GONE);
        visionTv.setVisibility(detectOn ? View.VISIBLE : View.GONE);
    }

    private void pushRollingFrame(Bitmap bmp) {
        if (bmp == null) return;
        long now = System.currentTimeMillis();
        synchronized (rollingFrames) {
            if (!rollingFrames.isEmpty() && now - lastRollingFrameAt < 420) return;
            lastRollingFrameAt = now;
            if (rollingFrames.size() >= 4) rollingFrames.removeFirst();
            rollingFrames.addLast(bmp);
        }
    }

    private List<Bitmap> snapshotRollingFrames() {
        synchronized (rollingFrames) {
            return new ArrayList<>(rollingFrames);
        }
    }

    private void onEyesFrame(byte[] jpeg) {
        if (!videoOn) return;
        lastJpeg = jpeg;
        if (call != null) call.sendVideoFrame(jpeg);
        if (RtmpBroadcastManager.get(this).isLive()) {
            RtmpBroadcastManager.get(this).pushFrameAsync(getCallRelay(), getCallToken(), jpeg);
        }
        if (!detectOn) {
            DemoRecorder.get().onJpegFrame(jpeg, eyesGlasses);
        }
        long now = System.currentTimeMillis();
        if (!detectOn && now - lastPreviewPaintAt >= 80) {
            lastPreviewPaintAt = now;
            previewExec.execute(() -> {
                try {
                    Bitmap bmp = decodeFrame(jpeg);
                    if (bmp != null) {
                        pushRollingFrame(bmp);
                        main.post(() -> {
                            if (videoOn && !detectOn && preview != null) {
                                preview.setVisibility(View.VISIBLE);
                                preview.setImageBitmap(bmp);
                            }
                        });
                    }
                } catch (Throwable ignored) {}
            });
        }
        boolean recognise = !PeopleVault.get(this).isEmpty();
        if ((!detectOn && !recognise) || visionBusy || now - lastVisionAt < 450) return;
        lastVisionAt = now;
        visionBusy = true;
        visionExec.execute(() -> {
            try { runVision(jpeg); } catch (Throwable t) { Log.w("CallVision", "vision: " + t); }
            finally { visionBusy = false; }
        });
    }

    private Detector detector() {
        if (detector == null) detector = new Detector(this);
        return detector;
    }

    private static Bitmap decodeFrame(byte[] jpeg) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = 2;
        Bitmap bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, o);
        if (bmp != null && bmp.getWidth() > bmp.getHeight()) {
            Matrix m = new Matrix();
            m.postRotate(90);
            Bitmap rot = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
            if (rot != bmp) {
                bmp.recycle();
            }
            return rot;
        }
        return bmp;
    }

    private String pendingMatchId = "";
    private int pendingMatchStreak = 0;

    private void runVision(byte[] jpeg) {
        Bitmap bmp = decodeFrame(jpeg);
        if (bmp == null) return;
        pushRollingFrame(bmp);

        PeopleVault vault = PeopleVault.get(this);
        // Only run object/body detector when user explicitly toggled Detect HUD on
        Detector.Result r = detector().detect(bmp, detectOn, detectOn);

        // Cache largest genuine detected face + matching body for Character Board generation
        RectF primaryFace = null;
        for (RectF f : r.faces) {
            if (primaryFace == null || f.width() * f.height() > primaryFace.width() * primaryFace.height()) {
                primaryFace = f;
            }
        }
        if (primaryFace != null) {
            lastBestFaceBox = new RectF(primaryFace);
            RectF matchedBody = Detector.Result.findMatchingBody(primaryFace, r.bodies);
            lastBestBodyBox = matchedBody != null
                    ? new RectF(matchedBody)
                    : Detector.estimateBodyFromFace(bmp.getWidth(), bmp.getHeight(), primaryFace);
        } else {
            lastBestFaceBox = null;
            lastBestBodyBox = null;
        }

        // Strict Face-Anchored Recognition: ONLY run recognition when BlazeFace detects a real face.
        // Never guess a face from a body box or match on body/clothing alone.
        List<String> seen = new ArrayList<>();
        PeopleVault.RecognitionMatch rawMatch = null;
        for (RectF f : r.faces) {
            float[] faceVec = descriptor(bmp, f);
            if (faceVec == null) continue;
            RectF bBox = Detector.Result.findMatchingBody(f, r.bodies);
            if (bBox == null) bBox = Detector.estimateBodyFromFace(bmp.getWidth(), bmp.getHeight(), f);
            float[] bodyVec = bodyDescriptor(bmp, bBox);

            PeopleVault.RecognitionMatch m = vault.matchFaceAndBody(faceVec, bodyVec);
            if (m != null && m.person != null) {
                if (rawMatch == null || m.score > rawMatch.score) rawMatch = m;
            }
        }

        // Require 2 consecutive vision frames matching the same person to eliminate single-frame false positives
        PeopleVault.RecognitionMatch bestMatch = null;
        if (rawMatch != null && rawMatch.person != null) {
            if (rawMatch.person.id.equals(pendingMatchId)) {
                pendingMatchStreak++;
            } else {
                pendingMatchId = rawMatch.person.id;
                pendingMatchStreak = 1;
            }
            if (pendingMatchStreak >= 2) {
                bestMatch = rawMatch;
                if (!seen.contains(bestMatch.person.name)) seen.add(bestMatch.person.name);
            }
        } else {
            pendingMatchId = "";
            pendingMatchStreak = 0;
        }

        long now = System.currentTimeMillis();
        if (bestMatch != null && bestMatch.person != null) {
            vault.recordSighting(bestMatch.person.id, bestMatch.score,
                    eyesGlasses ? "Glasses Live Call" : "Phone Live Call");
        }

        List<String> fresh = new ArrayList<>();
        for (String n : seen) {
            Long at = announced.get(n);
            if (at == null || now - at > 15_000) { fresh.add(n); announced.put(n, now); }
        }
        String summary = r.summary();
        boolean hasMeaningfulDetection = !r.faces.isEmpty() || !r.hands.isEmpty() || !r.objects.isEmpty();
        boolean sendSummary = detectOn && hasMeaningfulDetection && !summary.equals(lastSummary) && now - lastSummaryAt > 12_000;
        if (sendSummary) { lastSummary = summary; lastSummaryAt = now; }
        Bitmap shown = detectOn ? drawBoxes(bmp, r) : null;
        if (shown != null) {
            DemoRecorder.get().onBitmapFrame(shown, eyesGlasses);
        }

        visionMemory.recordObservation(r, bestMatch, eyesGlasses ? "Meta Glasses" : "Phone Cam");

        final PeopleVault.RecognitionMatch matchedForUi = bestMatch;
        main.post(() -> {
            if (detectOn) {
                preview.setImageBitmap(shown);
                visionTv.setText(summary + (seen.isEmpty() ? "" : "\nin view: " + String.join(", ", seen))
                        + "  · " + r.ms + " ms");
            }
            updateRecognitionPopup(matchedForUi);
            if (call == null) return;

            // Push structured Vision Memory timeline into the Live context window
            SpotterMode.ModeInfo curMode = SpotterMode.getActiveMode(CallActivity.this);
            String curObj = SpotterMode.getActiveObjective(CallActivity.this);
            if (now - lastVisionMemoryPushAt > 5500L || matchedForUi != null) {
                lastVisionMemoryPushAt = now;
                call.sendContext(visionMemory.toContextPrompt(curMode.key, curObj));
            }

            // Check proactive objective trigger
            String triggerPrompt = visionMemory.checkObjectiveTrigger(curMode.key, curObj);
            if (triggerPrompt != null) {
                addCaption("· objective trigger: " + triggerPrompt);
                call.sendContext(triggerPrompt);
            }

            if (!fresh.isEmpty() && matchedForUi != null && matchedForUi.person != null) {
                PeopleVault.Person mp = matchedForUi.person;
                addCaption("· recognized: " + mp.name + " (" + mp.role + ")");
                call.sendContext("[ON-DEVICE RECOGNITION MATCH]: The person currently in view is "
                        + mp.name + " (Role: " + mp.role + ", Attire: " + mp.attire
                        + ", Build: " + mp.buildSummary
                        + (mp.notes != null && !mp.notes.isEmpty() ? ", Notes: " + mp.notes : "")
                        + "). Identify them by name if the wearer asks who is in front of them.");
            }
            if (sendSummary) {
                call.sendContext("[ON-DEVICE DETECTOR]: Camera currently sees " + summary + ".");
            }
        });
    }

    private void updateRecognitionPopup(PeopleVault.RecognitionMatch match) {
        if (personCardOverlay == null) return;
        long now = System.currentTimeMillis();
        if (match == null || match.person == null) {
            if (personCardOverlay.getVisibility() == View.VISIBLE && now - activePopupUpdatedAt > 2800) {
                personCardOverlay.setVisibility(View.GONE);
                activePopupPersonId = "";
            }
            return;
        }
        PeopleVault.Person p = match.person;
        if (p.id.equals(dismissedPersonId) && now < dismissedPersonUntil) {
            return;
        }
        activePopupUpdatedAt = now;
        if (p.id.equals(activePopupPersonId) && personCardOverlay.getVisibility() == View.VISIBLE) {
            return;
        }
        activePopupPersonId = p.id;
        CharacterBoard.populateRecognitionCard(
                this,
                personCardOverlay,
                p,
                match.score,
                match.faceScore,
                match.bodyScore,
                () -> {
                    dismissedPersonId = p.id;
                    dismissedPersonUntil = System.currentTimeMillis() + 30_000L;
                    personCardOverlay.setVisibility(View.GONE);
                    activePopupPersonId = "";
                },
                () -> CharacterBoard.showFullDossierDialog(this, p, () -> {
                    activePopupPersonId = "";
                    updateRecognitionPopup(match);
                })
        );
        personCardOverlay.setVisibility(View.VISIBLE);
    }

    private static Bitmap drawBoxes(Bitmap src, Detector.Result r) {
        Bitmap out = src.copy(Bitmap.Config.ARGB_8888, true);
        Canvas c = new Canvas(out);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(2f, out.getWidth() / 160f));
        // Body boxes in cyan
        p.setColor(0xFF4CC9F0);
        for (RectF b : r.bodies) c.drawRect(b, p);
        // Face boxes in emerald
        p.setColor(0xFF8FE3B0);
        for (RectF f : r.faces) c.drawRect(f, p);
        // Objects in amber
        p.setColor(0xFFFFC857);
        for (RectF f : r.objectBoxes) c.drawRect(f, p);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFF6EC8FF);
        for (Detector.Hand h : r.hands)
            for (float[] pt : h.points) c.drawCircle(pt[0] * out.getWidth(), pt[1] * out.getHeight(), p.getStrokeWidth() * 1.5f, p);
        return out;
    }

    private static float[] descriptor(Bitmap bmp, RectF f) {
        Rect c = padded(bmp, f, 0.2f);
        if (c.width() < 16 || c.height() < 16) return null;
        int[] px = new int[c.width() * c.height()];
        bmp.getPixels(px, 0, c.width(), c.left, c.top, c.width(), c.height());
        if (!PersonDescriptor.looksLikeValidFaceCrop(px, c.width(), c.height())) return null;
        return PersonDescriptor.fromCrop(px, c.width(), c.height());
    }

    private static float[] bodyDescriptor(Bitmap bmp, RectF b) {
        if (b == null) return null;
        Rect c = padded(bmp, b, 0.05f);
        if (c.width() < 8 || c.height() < 8) return null;
        int[] px = new int[c.width() * c.height()];
        bmp.getPixels(px, 0, c.width(), c.left, c.top, c.width(), c.height());
        return PersonDescriptor.fromBodyCrop(px, c.width(), c.height());
    }

    private static Rect padded(Bitmap bmp, RectF f, float frac) {
        float pw = f.width() * frac, ph = f.height() * frac;
        return new Rect(Math.max(0, (int) (f.left - pw)), Math.max(0, (int) (f.top - ph)),
                Math.min(bmp.getWidth(), (int) (f.right + pw)), Math.min(bmp.getHeight(), (int) (f.bottom + ph)));
    }

    /** Opens the 4-Angle Face + Body Character Board builder dialog. */
    private void rememberPerson() {
        byte[] jpeg = lastJpeg;
        if (jpeg == null || !videoOn) {
            setState("Turn Eyes on and point at the person first.");
            return;
        }
        visionExec.execute(() -> {
            Bitmap bmp = decodeFrame(jpeg);
            if (bmp == null) return;
            pushRollingFrame(bmp);
            Detector.Result r = detector().detect(bmp, false, true);
            RectF bestFace = null;
            for (RectF f : r.faces) {
                if (bestFace == null || f.width() * f.height() > bestFace.width() * bestFace.height()) {
                    bestFace = f;
                }
            }
            if (bestFace == null) bestFace = lastBestFaceBox;
            RectF bestBody = bestFace != null
                    ? Detector.Result.findMatchingBody(bestFace, r.bodies)
                    : (!r.bodies.isEmpty() ? r.bodies.get(0) : lastBestBodyBox);
            if (bestBody == null && bestFace != null) {
                bestBody = Detector.estimateBodyFromFace(bmp.getWidth(), bmp.getHeight(), bestFace);
            }
            if (bestFace == null && bestBody != null) {
                bestFace = Detector.estimateFaceFromBody(bmp.getWidth(), bmp.getHeight(), bestBody);
            }
            final RectF fBox = bestFace;
            final RectF bBox = bestBody;
            final List<Bitmap> frames = snapshotRollingFrames();
            final CharacterBoard.LiveFrameSupplier supplier = new CharacterBoard.LiveFrameSupplier() {
                @Override public Bitmap captureFreshFrame() {
                    byte[] cur = lastJpeg;
                    return cur != null ? decodeFrame(cur) : bmp;
                }
                @Override public Detector getDetector() {
                    return detector();
                }
            };
            main.post(() -> CharacterBoard.showSavePersonDialog(
                    this,
                    frames,
                    bmp,
                    fBox,
                    bBox,
                    eyesGlasses ? "Glasses Live Call" : "Phone Live Call",
                    supplier,
                    saved -> {
                        announced.put(saved.name, System.currentTimeMillis());
                        setState("Saved Character Board: " + saved.name + " [" + saved.role + "]");
                        addCaption("· saved character board: " + saved.name + " (" + saved.role + ")");
                        ConversationStore.get(this).appendMessage(activeConvId, "Vision",
                                "Saved 4-Angle Character Board for " + saved.name + " (" + saved.role + ") · " + saved.attire);
                        updateRecognitionPopup(new PeopleVault.RecognitionMatch(saved, 0.99, 0.99, 0.99));
                        if (call != null) {
                            call.sendContext(buildSavedPeopleContext());
                            call.sendText("(System: the wearer just saved the person in front of the camera as "
                                    + saved.name + " [" + saved.role + "], attire: " + saved.attire
                                    + (saved.notes != null && !saved.notes.isEmpty() ? ", notes: " + saved.notes : "")
                                    + ". Confirm briefly in one sentence that you remember " + saved.name + ".)");
                        }
                    }
            ));
        });
    }

    private void applyAvatarMode() {
        muse3d.setVisibility(use3d ? View.VISIBLE : View.GONE);
        muse2d.setVisibility(use3d ? View.GONE : View.VISIBLE);
        avatarBtn.setText(use3d ? "3D" : "2D");
        applyLevels();
    }

    private void applyLevels() {
        DemoRecorder.get().updateVoiceLevels(userLevel, museLevel);
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
        String text = sb.toString();
        log.setText(text);
        DemoRecorder.get().logCue("CALL", line);
        DemoRecorder.get().updateHud(
                eyesGlasses,
                true,
                state != null ? state.getText().toString() : "Muse Call",
                text,
                meter != null ? meter.getText().toString() : "",
                visionTv != null && visionTv.getVisibility() == View.VISIBLE ? visionTv.getText().toString() : "",
                use3d ? "3D Avatar" : "2D Avatar"
        );
    }

    private void showMeter() {
        if (meter == null) return;
        if (call == null) {
            meter.setText("mic —  ·  session not open");
            return;
        }
        String line = String.format(Locale.US,
                "mic %.2f  ·  up %d  ·  Muse %.2f  ·  down %d  ·  eyes %d%s",
                call.micLevel(), call.uplinkChunks(), museLevel, call.downlinkChunks(),
                call.videoFrames(), muted ? "  ·  muted" : "");
        meter.setText(line);
        DemoRecorder.get().updateHud(
                eyesGlasses,
                true,
                state != null ? state.getText().toString() : "Muse Call",
                log != null ? log.getText().toString() : "",
                line,
                visionTv != null && visionTv.getVisibility() == View.VISIBLE ? visionTv.getText().toString() : "",
                use3d ? "3D Avatar" : "2D Avatar"
        );
        long now = System.currentTimeMillis();
        if (now - lastMeterLog >= 2000) {
            lastMeterLog = now;
            Log.i("CallStats", line + "  ·  " + (state != null ? state.getText() : ""));
        }
    }

    private void setState(String text) {
        state.setText(text);
        DemoRecorder.get().updateHud(
                eyesGlasses,
                true,
                text,
                log != null ? log.getText().toString() : "",
                meter != null ? meter.getText().toString() : "",
                visionTv != null && visionTv.getVisibility() == View.VISIBLE ? visionTv.getText().toString() : "",
                use3d ? "3D Avatar" : "2D Avatar"
        );
    }
}
