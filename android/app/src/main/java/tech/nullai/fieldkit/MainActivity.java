package tech.nullai.fieldkit;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SurfaceTexture;
import android.graphics.YuvImage;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.util.Base64;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import tech.nullai.fieldkit.people.PersonDescriptor;
import tech.nullai.fieldkit.voice.LockedListener;
import tech.nullai.fieldkit.voice.OwnerPrintStore;
import tech.nullai.fieldkit.voice.OwnerPrintPrefs;
import tech.nullai.fieldkit.voice.SpeakerGate;
import tech.nullai.fieldkit.voice.VoiceCapture;
import tech.nullai.fieldkit.voice.VoicePrint;
import tech.nullai.fieldkit.voice.WakePhrase;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.common.HybridBinarizer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * FieldKit phone client (hackathon): talks to FieldKit Relay.
 * - WS /v1/sessions: camera frames + speech in, coaching replies out (spoken).
 * - REST /v1/missions: one goal -> checklist; advance as steps complete.
 */
public class MainActivity extends Activity {
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final int REQ_PERMS = 7;
    private static final int REQ_SPEECH = 9;
    private static final int REQ_DAT_CAM = 4101;   // Meta AI glasses camera permission

    // ---- voice gate + appearance descriptor (voice/VoiceCapture, people/PersonDescriptor)
    /** SharedPreferences key holding the enrolled takes. A convenience gate, never a password. */
    private static final String P_OWNER_PRINT = "owner_print";
    private SpeakerGate voiceGate = new SpeakerGate();
    private SharedPreferences prefs;
    private TextView tvVoice;
    /** Verdict for the utterance being recognised: wearer / other / unknown. Never a name. */
    private volatile String lastSpeaker = "unknown";
    /** Descriptor of the middle of the frame - a yes/no prompt for him, never an identification. */
    private volatile float[] cropDesc;
    private volatile long cropDescAt = 0L;
    private boolean fileRecognizeTried = false;
    /** Screen-off utterances waiting to be transcribed, oldest first. */
    private final ArrayDeque<File> keptQueue = new ArrayDeque<>();
    /** The kept utterance whose transcript the recognizer is producing right now, if any. */
    private File keptBeingTranscribed = null;
    private float[][] enrolTakes = new float[3][];
    private int enrolAt = 0;
    private int enrolRetry = 0;
    private Button btnMission;
    private String pendingMissionId = "", pendingFirst = "";
    private int pendingTotal = 0;

    private EditText etRelay, etToken, etGoal;
    private TextView tvStep, tvStatus, tvGlasses, tvCompass, tvSound;
    private TextureView preview;
    private ImageView ivGlasses;
    private Button btnConnect, btnSrcPhone, btnSrcGlasses, btnFab, btnFull, btnPreset;
    private boolean glassesMode = false;
    private boolean fullscreen = false;
    private byte[] pendingGlassesFrame = null;
    private boolean frameHoldNoted = false;
    private boolean scanning = false;
    private long lastQrAt = 0;
    private long lastRelayGlassesSentAt = 0;
    private boolean glassesStreamingShown = false;
    private SoundField soundField;
    private volatile int lastHeadingDeg = -1;
    private long lastSoundSentAt = 0;
    private MultiFormatReader qrReader;
    private SensorManager sensors;
    private final float[] grav = new float[3], mag = new float[3];
    private boolean hasGrav, hasMag;
    private static final String[][] PRESETS = {
            {"get-it-done", "Get It Done"}, {"safety-first", "Safety First"},
            {"teach-me", "Teach Me"}, {"social", "Social"}};
    private int presetIdx = 0;
    private long lastCompassSentAt = 0;

    private final OkHttpClient http = new OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS).build();
    private WebSocket ws;
    private TextToSpeech tts;
    private String missionId = "";
    private boolean connected = false;

    private CameraDevice camera;
    private CameraCaptureSession captureSession;
    private ImageReader reader;
    private HandlerThread camThread;
    private Handler camHandler;
    private long lastFrameAt = 0;
    private Size previewSize = new Size(1280, 720);
    private boolean cameraOpening = false;

    // ---- screen-off session: the foreground service owns the relay + camera link
    private volatile boolean headless = false;   // capturing frames with no preview surface
    private boolean autoReconnect = false;       // stays true until the user disconnects
    private boolean pendingRegistration = false; // DAT registration launched, awaiting approval
    private boolean registrationWaitLogged = false;
    private int reconnectAttempts = 0;
    private long framesSent = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        etRelay = findViewById(R.id.etRelay);
        etToken = findViewById(R.id.etToken);
        etGoal = findViewById(R.id.etGoal);
        tvStep = findViewById(R.id.tvStep);
        tvStatus = findViewById(R.id.tvStatus);
        tvGlasses = findViewById(R.id.tvGlasses);
        tvCompass = findViewById(R.id.tvCompass);
        tvSound = findViewById(R.id.tvSound);
        preview = findViewById(R.id.preview);
        ivGlasses = findViewById(R.id.ivGlasses);
        sensors = (SensorManager) getSystemService(SENSOR_SERVICE);
        soundField = new SoundField((relativeDeg, levelDb, stereo) -> {
            int h = lastHeadingDeg;
            int world = h >= 0 ? (h + relativeDeg + 360) % 360 : -1;
            String arrow = Math.abs(relativeDeg) < 12 ? "↑ ahead"
                    : (relativeDeg < 0 ? "↖ " + (-relativeDeg) + "° L" : "↗ " + relativeDeg + "° R");
            final String line = stereo
                    ? String.format(Locale.US, "Sound %s · %.0f dB%s",
                            arrow, levelDb, world >= 0 ? " · ~" + world + "°" : "")
                    : String.format(Locale.US, "Sound %.0f dB (mono mic)", levelDb);
            runOnUiThread(() -> tvSound.setText(line));
            long nowMs = System.currentTimeMillis();
            if (connected && nowMs - lastSoundSentAt >= 1000) {
                lastSoundSentAt = nowMs;
                try {
                    JSONObject m = new JSONObject();
                    m.put("type", "sound");
                    m.put("heading", world);
                    m.put("relative", relativeDeg);
                    m.put("source", glassesMode ? "glasses" : "phone");
                    m.put("level_db", Math.round(levelDb * 10) / 10.0);
                    sendWs(m);
                } catch (Exception ignored) { }
            }
        });
        btnConnect = findViewById(R.id.btnConnect);
        btnSrcPhone = findViewById(R.id.btnSrcPhone);
        btnSrcGlasses = findViewById(R.id.btnSrcGlasses);

        qrReader = new MultiFormatReader();
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, Arrays.asList(BarcodeFormat.QR_CODE));
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        qrReader.setHints(hints);

        final TextView brailleWordmark = findViewById(R.id.brailleWordmark);
        final TextView brailleSpin = findViewById(R.id.brailleSpin);
        final String[] frames = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏", "⣾", "⣽", "⣻", "⢿", "⡿", "⣟", "⣯", "⣷"};
        final android.os.Handler spinH = new android.os.Handler(android.os.Looper.getMainLooper());
        final int[] fi = {0};
        final Matrix shaderMatrix = new Matrix();
        final Runnable spin = new Runnable() {
            @Override public void run() {
                int step = fi[0]++;
                if (brailleSpin != null) {
                    brailleSpin.setText(frames[(step / 2) % frames.length]);
                    applyHolographicMonotoneShader(brailleSpin, step, shaderMatrix);
                }
                if (brailleWordmark != null) {
                    applyHolographicMonotoneShader(brailleWordmark, step, shaderMatrix);
                }
                spinH.postDelayed(this, 45);
            }
        };
        spinH.post(spin);

        btnFab = findViewById(R.id.btnFab);
        btnFull = findViewById(R.id.btnFull);
        btnFull.setOnClickListener(v -> setFullscreen(true));
        btnFab.setOnClickListener(v -> showOptionsMenu());
        btnPreset = findViewById(R.id.btnPreset);
        btnPreset.setOnClickListener(v -> {
            presetIdx = (presetIdx + 1) % PRESETS.length;
            btnPreset.setText("◈ Preset: " + PRESETS[presetIdx][1]);
            try {
                JSONObject m = new JSONObject();
                m.put("type", "preset");
                m.put("name", PRESETS[presetIdx][0]);
                sendWs(m);
            } catch (Exception ignored) { }
            status("Preset: " + PRESETS[presetIdx][1] + " — what the coach optimizes for.");
        });
        findViewById(R.id.btnShh).setOnClickListener(v -> {
            try {
                sendWs(new JSONObject().put("type", "pause"));
            } catch (Exception ignored) { }
            status("Paused. Talk to resume.");
        });

        SharedPreferences p = getSharedPreferences("fieldkit", MODE_PRIVATE);
        etRelay.setText(p.getString("relay", "http://192.168.1.20:8766"));
        etToken.setText(p.getString("token", ""));

        tts = new TextToSpeech(this, s -> {
            if (s == TextToSpeech.SUCCESS) tts.setLanguage(Locale.US);
        });

        DatGlasses.INSTANCE.init(this);
        DatGlasses.INSTANCE.attachActivity(this);
        DatGlasses.INSTANCE.handleIntent(getIntent());
        DatGlasses.INSTANCE.setListener(new DatGlasses.Listener() {
            @Override public void onStatus(String text) { status(text); }

            @Override public void onJpeg(byte[] jpeg) {
                Bitmap bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
                if (bmp != null) {
                    runOnUiThread(() -> {
                        ivGlasses.setImageBitmap(bmp);
                        ivGlasses.setVisibility(android.view.View.VISIBLE);
                        tvGlasses.setVisibility(android.view.View.GONE);
                        if (!glassesStreamingShown) {
                            glassesStreamingShown = true;
                            status("Glasses streaming — frames are flowing.");
                        }
                    });
                }
                long now = System.currentTimeMillis();
                if (now - lastRelayGlassesSentAt < 450) return;
                lastRelayGlassesSentAt = now;
                try {
                    JSONObject msg = new JSONObject();
                    msg.put("type", "frame");
                    msg.put("source", "glasses");
                    msg.put("image", Base64.encodeToString(jpeg, Base64.NO_WRAP));
                    if (connected) {
                        sendWs(msg);
                    } else {
                        // Glasses frames decode fine locally but the relay never
                        // saw them — hold the newest and flush on connect.
                        pendingGlassesFrame = jpeg;
                        if (!frameHoldNoted) {
                            frameHoldNoted = true;
                            status("Glasses streaming live on phone — tap Connect to forward to relay.");
                        }
                    }
                } catch (Exception ignored) { }
            }
        });

        btnSrcPhone.setOnClickListener(v -> setSource(false));
        btnSrcGlasses.setOnClickListener(v -> setSource(true));
        findViewById(R.id.btnScan).setOnClickListener(v -> {
            if (glassesMode) setSource(false);
            scanning = true;
            status("Point the camera at the relay QR code…");
        });

        boolean startInGlasses = getIntent() != null && getIntent().getBooleanExtra("glasses", false);
        setSource(startInGlasses);

        btnConnect.setOnClickListener(v -> connectToggle());
        btnMission = findViewById(R.id.btnMission);
        btnMission.setOnClickListener(v -> {
            savePrefs();
            if (!pendingMissionId.isEmpty()) beginMission(); else startMission();
        });
        findViewById(R.id.btnNext).setOnClickListener(v -> nextStep());
        findViewById(R.id.btnTalk).setOnClickListener(v -> talk());
        tvVoice = findViewById(R.id.tvVoice);
        prefs = getSharedPreferences("fieldkit", MODE_PRIVATE);
        findViewById(R.id.btnVoice).setOnClickListener(v -> voiceTap());
        findViewById(R.id.btnPerson).setOnClickListener(v -> personTap());
        loadVoicePrint();
        refreshVoiceLabel();

        preview.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                if (headless) { headless = false; closeCamera(); }   // returning from a screen-off session
                openCamera();
                configureTransform(w, h);
            }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
                configureTransform(w, h);
            }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                // Screen locked or app backgrounded mid-session: the relay link is supposed
                // to be the only thing that ends a session, so keep observing through the
                // ImageReader instead of tearing the camera down.
                if (SpotterService.isAlive() && connected && !glassesMode) {
                    if (!headless) reopenHeadless();
                    return true;
                }
                closeCamera();
                return true;
            }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture st) { }
        });

        java.util.List<String> need = new java.util.ArrayList<>();
        for (String perm : new String[]{Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
                "android.permission.BLUETOOTH_CONNECT",       // DAT finds glasses via bonded BT
                "android.permission.POST_NOTIFICATIONS"}) {   // screen-off session status
            if (checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) need.add(perm);
        }
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), REQ_PERMS);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        DatGlasses.INSTANCE.handleIntent(intent);
        if (intent != null && intent.hasExtra("glasses")) {
            setSource(intent.getBooleanExtra("glasses", false));
        }
    }

    /**
     * Applies a smooth monotone grey + holographic chrome/pearl specular gradient
     * to the existing Braille wordmark and loading spinner.
     */
    private void applyHolographicMonotoneShader(TextView tv, int step, Matrix matrix) {
        int w = Math.max(80, tv.getWidth());
        LinearGradient grad = new LinearGradient(
                0f, 0f, w, 0f,
                new int[]{0xFF141519, 0xFF565B66, 0xFFDCE4F2, 0xFFF4F6FA, 0xFFDAD4DF, 0xFF484C56, 0xFF141519},
                new float[]{0.0f, 0.28f, 0.45f, 0.50f, 0.56f, 0.74f, 1.0f},
                Shader.TileMode.CLAMP
        );
        float shift = ((step * 5.5f) % (w * 2.2f)) - w * 0.6f;
        matrix.reset();
        matrix.setTranslate(shift, 0f);
        grad.setLocalMatrix(matrix);
        tv.getPaint().setShader(grad);
        tv.invalidate();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] grants) {
        super.onRequestPermissionsResult(code, perms, grants);
        if (code == REQ_PERMS) {
            if (glassesMode) {
                startGlassesFlow();
            } else if (preview.isAvailable()) {
                openCamera();
            }
        }
    }

    private void savePrefs() {
        getSharedPreferences("fieldkit", MODE_PRIVATE).edit()
                .putString("relay", relayBase()).putString("token", token()).apply();
    }

    private String relayBase() {
        return etRelay.getText().toString().trim().replaceAll("/+$", "");
    }

    private String token() {
        return etToken.getText().toString().trim();
    }

    private void status(String s) {
        runOnUiThread(() -> tvStatus.setText(s));
        // Mirror the headset-free status line into the ongoing notification so a session
        // running with the screen off is still legible from the lock screen.
        if (SpotterService.isAlive()) SpotterService.update(this, s);
    }

    private void speak(String s) {
        if (s != null && !s.trim().isEmpty() && tts != null) {
            tts.speak(s, TextToSpeech.QUEUE_FLUSH, null, "fk");
        }
    }

    // ------------------------------------------------------------- sessions WS
    private void connect() {
        String base = relayBase();
        if (base.isEmpty() || token().isEmpty()) {
            if (!glassesMode) {
                Toast.makeText(this, "Set relay address and token first.", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        // The session is supposed to outlive the screen: run it under a foreground
        // service so Android cannot reclaim the process the moment the screen locks.
        if (!SpotterService.isAlive()) {
            SpotterService.start(this, (glassesMode ? "Glasses" : "Phone camera")
                    + " observing — screen off is fine.");
        }
        autoReconnect = true;
        String wsUrl = base.replaceFirst("^http", "ws") + "/v1/sessions";
        Request req = new Request.Builder().url(wsUrl)
                .header("Authorization", "Bearer " + token()).build();
        ws = http.newWebSocket(req, new WebSocketListener() {
            @Override public void onOpen(WebSocket webSocket, Response response) {
                connected = true;
                reconnectAttempts = 0;
                runOnUiThread(() -> btnConnect.setText("Disconnect"));
                try {
                    JSONObject hello = new JSONObject();
                    hello.put("type", "hello");
                    hello.put("provider", "spark");
                    hello.put("source", glassesMode ? "glasses" : "phone");
                    hello.put("guide", true);
                    hello.put("preset", PRESETS[presetIdx][0]);
                    if (!missionId.isEmpty()) hello.put("mission_id", missionId);
                    webSocket.send(hello.toString());
                    if (pendingGlassesFrame != null) {
                        JSONObject held = new JSONObject();
                        held.put("type", "frame");
                        held.put("source", "glasses");
                        held.put("image", Base64.encodeToString(pendingGlassesFrame, Base64.NO_WRAP));
                        webSocket.send(held.toString());
                        pendingGlassesFrame = null;
                        frameHoldNoted = false;
                    }
                } catch (Exception ignored) { }
                status("Connected. Say a goal, then tap Start mission.");
            }

            @Override public void onMessage(WebSocket webSocket, String text) {
                try {
                    JSONObject ev = new JSONObject(text);
                    String type = ev.optString("type");
                    if ("done".equals(type)) {
                        speak(ev.optString("text"));
                    } else if ("say".equals(type)) {
                        String line = ev.optString("text");
                        runOnUiThread(() -> tvStep.setText(line));
                        speak(line);
                    } else if ("observation".equals(type)) {
                        status("Eyes: " + ev.optString("text"));
                    } else if ("state".equals(type) && ev.has("mission")) {
                        JSONObject m = ev.getJSONObject("mission");
                        String label = m.optBoolean("complete")
                                ? "Mission complete"
                                : "Step " + m.optInt("current") + "/" + m.optInt("total")
                                        + ": " + m.optString("step");
                        runOnUiThread(() -> tvStep.setText(label));
                    } else if ("error".equals(type)) {
                        status("Relay: " + ev.optString("detail"));
                    } else if ("ready".equals(type)) {
                        status("Session ready (" + ev.optString("model") + ").");
                    }
                } catch (Exception ignored) { }
            }

            @Override public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                connected = false;
                runOnUiThread(() -> btnConnect.setText(glassesMode ? "Connect glasses" : "Connect"));
                if (!glassesMode || !DatGlasses.INSTANCE.isStreaming()) {
                    status("Connection failed: " + t.getClass().getSimpleName());
                }
                scheduleReconnect();
            }

            @Override public void onClosed(WebSocket webSocket, int code, String reason) {
                connected = false;
                runOnUiThread(() -> btnConnect.setText(glassesMode ? "Connect glasses" : "Connect"));
                if (!glassesMode || !DatGlasses.INSTANCE.isStreaming()) {
                    status("Disconnected.");
                }
                scheduleReconnect();
            }
        });
    }

    /**
     * A dropped relay link is the one failure this app is allowed to have, so it heals
     * itself with capped backoff while the session is meant to be live (Tailscale flap,
     * screen off, Wi-Fi roam). Never reconnects after an explicit disconnect.
     */
    private void scheduleReconnect() {
        if (!autoReconnect) return;
        final int attempt = ++reconnectAttempts;
        long delayMs = Math.min(30000L, 1000L << Math.min(attempt, 5));
        status("Relay link lost — retrying in " + (delayMs / 1000) + "s (attempt " + attempt + ").");
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (autoReconnect && !connected) connect();
        }, delayMs);
    }

    private void disconnect() {
        autoReconnect = false;
        SpotterService.stop(this);
        if (ws != null) {
            try {
                ws.send(new JSONObject().put("type", "bye").toString());
            } catch (Exception ignored) { }
            ws.close(1000, "bye");
            ws = null;
        }
        connected = false;
        btnConnect.setText(glassesMode ? "Connect glasses" : "Connect");
        status("Disconnected.");
    }

    private void sendWs(JSONObject obj) {
        if (ws != null && connected) ws.send(obj.toString());
    }

    // ------------------------------------------------------------- missions REST
    private Request.Builder authed(String path) {
        return new Request.Builder().url(relayBase() + path)
                .header("Authorization", "Bearer " + token());
    }

    private void startMission() {
        String goal = etGoal.getText().toString().trim();
        if (goal.isEmpty()) {
            Toast.makeText(this, "Type or say a goal first.", Toast.LENGTH_SHORT).show();
            return;
        }
        status("Planning with Muse Wonder…");
        try {
            JSONObject body = new JSONObject();
            body.put("goal", goal);
            body.put("provider", "wonder");
            http.newCall(authed("/v1/missions")
                    .post(RequestBody.create(body.toString(), JSON)).build()).enqueue(new okhttp3.Callback() {
                @Override public void onFailure(okhttp3.Call call, java.io.IOException e) {
                    status("Mission failed: " + e.getClass().getSimpleName());
                }

                @Override public void onResponse(okhttp3.Call call, Response resp) throws java.io.IOException {
                    String text = resp.body() != null ? resp.body().string() : "";
                    if (!resp.isSuccessful()) {
                        status("Mission failed (" + resp.code() + "). Check relay/token.");
                        return;
                    }
                    try {
                        JSONObject m = new JSONObject(text);
                        pendingMissionId = m.optString("id");
                        JSONArray steps = m.optJSONArray("steps");
                        pendingFirst = steps != null && steps.length() > 0
                                ? steps.getJSONObject(0).optString("text") : "";
                        pendingTotal = steps == null ? 0 : steps.length();
                        StringBuilder review = new StringBuilder("Your plan — review, then Begin:\n");
                        if (steps != null) {
                            for (int i = 0; i < steps.length(); i++) {
                                review.append(i + 1).append(". ")
                                        .append(steps.getJSONObject(i).optString("text")).append("\n");
                            }
                        }
                        final String reviewText = review.toString().trim();
                        runOnUiThread(() -> {
                            tvStep.setText(reviewText);
                            btnMission.setText("▶ Begin");
                        });
                        speak("Plan ready. Review the steps, then tap Begin when you're ready.");
                        status("Planned with Wonder. Edit the goal + Start to re-plan, or Begin.");
                    } catch (Exception e) {
                        status("Mission reply unreadable.");
                    }
                }
            });
        } catch (Exception e) {
            status("Mission failed to start.");
        }
    }

    private void startGlassesFlow() {
        if (checkSelfPermission("android.permission.BLUETOOTH_CONNECT") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.BLUETOOTH_CONNECT"}, REQ_PERMS);
            status("Grant Bluetooth permission to connect Meta Ray-Ban glasses.");
            return;
        }
        if (DatGlasses.INSTANCE.isStreaming() || DatGlasses.INSTANCE.isConnecting()) {
            return;
        }
        // DAT contract: "Registration must complete before a session can start."
        // Registering is a distinct step from connecting. Skipping it leaves the SDK
        // at ACDC UNREGISTERED, Wearables.devices empty, and createSession failing
        // with "No wearable devices have been discovered or registered".
        if (!DatGlasses.INSTANCE.isRegistered()) {
            pendingRegistration = true;
            registrationWaitLogged = false;
            status("Registering Spotter with your glasses \u2014 approve the card in Meta AI.");
            speak("Approve Spotter in Meta AI, then I can see through your glasses.");
            DatGlasses.INSTANCE.register(this);
            return;
        }
        DatGlasses.INSTANCE.checkCameraPermission(allowed -> runOnUiThread(() -> {
            if (allowed) {
                DatGlasses.INSTANCE.connect();
            } else {
                try {
                    status("Asking Meta AI for glasses camera permission… approve it there.");
                    startActivityForResult(DatGlasses.INSTANCE.createCameraPermissionIntent(MainActivity.this), REQ_DAT_CAM);
                } catch (Exception e) {
                    DatGlasses.INSTANCE.connect();
                }
            }
        }));
    }

    private void connectToggle() {
        savePrefs();
        if (glassesMode) {
            if (connected && DatGlasses.INSTANCE.isStreaming()) {
                disconnect();
                DatGlasses.INSTANCE.disconnect();
            } else {
                if (!DatGlasses.INSTANCE.isStreaming() && !DatGlasses.INSTANCE.isConnecting()) {
                    startGlassesFlow();
                }
                if (!connected && !relayBase().isEmpty() && !token().isEmpty()) {
                    connect();
                }
            }
        } else if (connected) {
            disconnect();
        } else {
            connect();
        }
    }

    private void setFullscreen(boolean on) {
        fullscreen = on;
        View content = findViewById(R.id.contentCol);
        View card = findViewById(R.id.previewCard);
        if (on) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            ViewGroup col = (ViewGroup) content;
            for (int i = 0; i < col.getChildCount(); i++) {
                View ch = col.getChildAt(i);
                if (ch.getId() != R.id.previewCard) ch.setVisibility(View.GONE);
            }
            ViewGroup.LayoutParams lp = card.getLayoutParams();
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
            card.setLayoutParams(lp);
            btnFab.setVisibility(View.VISIBLE);
            btnFull.setVisibility(View.GONE);
            if (preview != null) configureTransform(preview.getWidth(), preview.getHeight());
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
            ViewGroup col = (ViewGroup) content;
            for (int i = 0; i < col.getChildCount(); i++) col.getChildAt(i).setVisibility(View.VISIBLE);
            ViewGroup.LayoutParams lp = card.getLayoutParams();
            lp.height = (int) (200 * getResources().getDisplayMetrics().density);
            card.setLayoutParams(lp);
            btnFab.setVisibility(View.GONE);
            btnFull.setVisibility(View.VISIBLE);
            if (preview != null) configureTransform(preview.getWidth(), preview.getHeight());
        }
    }

    private void showOptionsMenu() {
        String[] items = new String[]{
                "Exit full screen",
                "Camera source: " + (glassesMode ? "Meta glasses" : "Phone camera") + " (switch)",
                "Model preset: " + PRESETS[presetIdx][1] + " (next)",
                connected ? "Disconnect" : "Connect",
                "Register glasses in Meta AI",
                "Pause coaching (Shh)"};
        new AlertDialog.Builder(this)
                .setTitle("Spotter options")
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0: setFullscreen(false); break;
                        case 1: setSource(!glassesMode); break;
                        case 2: if (btnPreset != null) btnPreset.performClick(); break;
                        case 3: connectToggle(); break;
                        case 4: DatGlasses.INSTANCE.register(MainActivity.this); break;
                        case 5: findViewById(R.id.btnShh).performClick(); break;
                    }
                }).show();
    }

    private void beginMission() {
        missionId = pendingMissionId;
        pendingMissionId = "";
        btnMission.setText("▶ Start");
        tvStep.setText("Step 1/" + pendingTotal + ": " + pendingFirst);
        speak("Mission ready. Step one: " + pendingFirst);
        status("Mission started. Reconnecting session to attach it…");
        if (connected) { disconnect(); }
        connect();
    }

    private void nextStep() {
        if (missionId.isEmpty()) {
            Toast.makeText(this, "Start a mission first.", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            JSONObject body = new JSONObject();
            body.put("type", "advance");
            http.newCall(authed("/v1/missions/" + missionId + "/events")
                    .post(RequestBody.create(body.toString(), JSON)).build()).enqueue(new okhttp3.Callback() {
                @Override public void onFailure(okhttp3.Call call, java.io.IOException e) {
                    status("Advance failed: " + e.getClass().getSimpleName());
                }

                @Override public void onResponse(okhttp3.Call call, Response resp) throws java.io.IOException {
                    String text = resp.body() != null ? resp.body().string() : "";
                    if (!resp.isSuccessful()) {
                        status("Advance failed (" + resp.code() + ").");
                        return;
                    }
                    try {
                        JSONObject out = new JSONObject(text);
                        JSONObject m = out.optJSONObject("mission");
                        String say = out.optString("say");
                        String label = (m != null && m.optBoolean("complete"))
                                ? "Mission complete"
                                : "Step " + (m == null ? "?" : m.optInt("current")) + ": " + say;
                        runOnUiThread(() -> tvStep.setText(label));
                        speak(say);
                    } catch (Exception e) {
                        status("Advance reply unreadable.");
                    }
                }
            });
        } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------- speech in
    // ---------------------------------------------------------------- voice gate
    /**
     * Talk is the wearer's key. His voice is a CONVENIENCE gate, never an
     * authentication: the gate only decides whether a turn came from him or from
     * somebody standing next to him, and the relay refuses to let a bystander's
     * voice silence the coach. No voice and no frame is ever used to name anyone:
     * names only ever come from what he says or types.
     */
    private void talk() {
        if (soundField != null) soundField.stop();   // free the mic for our capture
        if (!VoiceCapture.available(this)) {
            listen(null);                            // no mic permission: the plain recognizer
            return;
        }
        status("Listening \u2014 speak, then pause.");
        VoiceCapture.capture(this, this::onUtterance);
    }

    /** Called off the UI thread with the utterance the gate just listened to. */
    private void onUtterance(VoiceCapture.Result r) {
        if (r == null || !r.spoken()) {
            lastSpeaker = "unknown";
            runOnUiThread(() -> {
                status(r == null ? "Capture failed." : "Heard nothing: " + r.describe());
                listen(null);
            });
            return;
        }
        SpeakerGate.Verdict v = voiceGate.isEnrolled() && r.print != null ? voiceGate.verify(r.print) : null;
        lastSpeaker = v == null ? "unknown" : v.accept ? "wearer" : v.knownVisitor ? "other" : "unknown";
        String note = "Heard " + lastSpeaker + " \u2014 " + r.describe();
        runOnUiThread(() -> status(note));
        listen(r.wav);
    }

    /**
     * Transcribe the utterances the screen-off loop kept, oldest first.
     *
     * The recognizer is a single Activity, so they go over one at a time: the next is handed
     * over when the previous result arrives. Nothing is transcribed twice, because each kept
     * WAV is deleted as it is dealt with.
     */
    private void drainKeptUtterances() {
        keptQueue.clear();
        keptQueue.addAll(LockedListener.keptOnDisk(this));
        if (keptQueue.isEmpty()) return;
        status("Screen-off audio waiting to be transcribed: " + keptQueue.size() + ".");
        transcribeNextKept();
    }

    private void transcribeNextKept() {
        File f = keptQueue.poll();
        if (f == null) {
            keptBeingTranscribed = null;
            return;
        }
        keptBeingTranscribed = f;
        // Everything that reached the queue cleared the print gate while the screen was off, so
        // the voice is the wearer's - but the utterance is from a moment ago, not from now.
        lastSpeaker = "wearer";
        listen(f);
    }

    /**
     * Hand the recognizer the SAME utterance we just fingerprinted, so the transcript
     * and the gate verdict describe one piece of audio. Devices that ignore
     * EXTRA_AUDIO_SOURCE fall back to the live recognizer (and we stop trusting the
     * verdict for that turn, because the audio is then no longer the audio we measured).
     */
    private void listen(File wav) {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US);
        fileRecognizeTried = false;
        if (wav != null && wav.isFile() && wav.length() > 44) {
            intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, Uri.fromFile(wav));
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING,
                        android.media.AudioFormat.ENCODING_PCM_16BIT);
                intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, VoiceCapture.RATE);
                intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1);
            }
            fileRecognizeTried = true;
        }
        try {
            startActivityForResult(intent, REQ_SPEECH);
        } catch (Exception e) {
            Toast.makeText(this, "Speech input not available on this device.", Toast.LENGTH_SHORT).show();
        }
    }

    /** The wearer types a name for the person in frame. The ONLY way a name ever enters memory.
     *
     * Nothing here looks at a face. The name exists because he typed it, it is bound to the
     * descriptor the camera just saw, and the relay will only ever use it to greet that person the
     * way he asked. A stranger stays a stranger until he says otherwise. */
    private void personTap() {
        final float[] d = cropDesc;
        if (d == null || System.currentTimeMillis() - cropDescAt > 30000) {
            Toast.makeText(this, "Aim the camera at them first, then name them.",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        final EditText field = new EditText(this);
        field.setHint("their name, as you know it");
        field.setSingleLine(true);
        field.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        new AlertDialog.Builder(this)
                .setTitle("Remember this person")
                .setMessage("Spotter never works anyone out from their face. A name exists only "
                        + "because you typed it, and it is only used to greet them the way you asked.")
                .setView(field)
                .setPositiveButton("Remember", (dlg, w) -> {
                    String name = field.getText().toString().trim();
                    if (name.isEmpty()) {
                        return;
                    }
                    etGoal.setText(name);
                    try {
                        JSONObject msg = new JSONObject();
                        msg.put("type", "speech");
                        msg.put("text", "remember this person: " + name);
                        msg.put("final", true);
                        msg.put("speaker", "wearer");
                        msg.put("command", "person_enroll");
                        msg.put("name", name);
                        JSONArray arr = new JSONArray();
                        for (float f : d) {
                            arr.put((double) f);
                        }
                        msg.put("descriptor", arr);
                        sendWs(msg);
                    } catch (Exception ignored) {
                    }
                    status("Remembered as " + name + " \u2014 only your own words can do that.");
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void voiceTap() {
        if (voiceGate.isEnrolled()) {
            new AlertDialog.Builder(this)
                    .setTitle("Voice gate")
                    .setMessage("Your voice is enrolled: " + voiceGate.describe()
                            + "\n\nThis is a convenience, never a password. It only decides whether a turn "
                            + "came from you or from somebody else, so only your own voice can silence the coach. "
                            + "It never identifies anybody, and it never names anyone.")
                    .setPositiveButton("Re-take (3 takes)", (d, w) -> enrolVoice())
                    .setNeutralButton("Forget my voice", (d, w) -> forgetVoice())
                    .setNegativeButton("Close", null)
                    .show();
            return;
        }
        enrolVoice();
    }

    /** Three takes, enrolled together, so one bad microphone moment cannot claim the gate. */
    private void enrolVoice() {
        if (!VoiceCapture.available(this)) {
            Toast.makeText(this, "Microphone permission is needed to enrol your voice.", Toast.LENGTH_SHORT).show();
            return;
        }
        enrolAt = 0;
        enrolRetry = 0;
        enrolTakes = new float[3][];
        nextEnrolTake();
    }

    private void nextEnrolTake() {
        status("Voice take " + (enrolAt + 1) + " of 3 \u2014 say a full sentence, then pause.");
        VoiceCapture.capture(this, r -> {
            if (r == null || !r.spoken() || r.quality < 0.20) {
                enrolRetry++;
                final String why = r == null ? "capture failed" : r.describe();
                final int left = 2 - enrolRetry;
                runOnUiThread(() -> {
                    if (left >= 0) {
                        status("That take would not do (" + why + "). Listening again \u2014 " + left + " retries left.");
                        nextEnrolTake();
                    } else {
                        status("Enrolment stopped: " + why + ". Tap Voice to try again.");
                    }
                });
                return;
            }
            enrolTakes[enrolAt] = r.print;
            enrolAt++;
            enrolRetry = 0;
            if (enrolAt < 3) {
                runOnUiThread(this::nextEnrolTake);
                return;
            }
            List<float[]> takes = new ArrayList<>(Arrays.asList(enrolTakes));
            runOnUiThread(() -> {
                voiceGate.enroll(takes);      // also calibrates the threshold from these takes
                saveVoicePrint();
                refreshVoiceLabel();
                status("Voice enrolled (" + voiceGate.describe() + "). Talk now labels a turn as you or someone else.");
            });
        });
    }

    private void forgetVoice() {
        voiceGate = new SpeakerGate();
        enrolTakes = new float[3][];
        if (prefs != null) prefs.edit().remove(P_OWNER_PRINT).apply();
        OwnerPrintPrefs.forget(this);       // the service reads this key too
        refreshVoiceLabel();
        status("Voice print forgotten. Talk treats every turn as unknown until you enrol again.");
    }

    private void saveVoicePrint() {
        if (prefs == null) return;
        // Same codec the service loads with (voice/OwnerPrintStore): one format, both sides.
        prefs.edit().putString(P_OWNER_PRINT, OwnerPrintStore.encode(enrolTakes)).apply();
    }

    private void loadVoicePrint() {
        try {
            if (prefs == null) return;
            String raw = prefs.getString(P_OWNER_PRINT, "");
            if (raw == null || raw.isEmpty()) return;
            List<float[]> takes = OwnerPrintStore.decode(raw);
            if (takes.isEmpty()) return;
            voiceGate.enroll(takes);          // re-enrolling the takes re-calibrates the same threshold
            enrolTakes = takes.toArray(new float[0][]);
        } catch (Exception ignored) { }
    }

    private void refreshVoiceLabel() {
        if (tvVoice == null) return;
        tvVoice.setText(voiceGate.isEnrolled()
                ? "\u25c9 Voice gate: enrolled \u2014 " + voiceGate.describe()
                : "\u25ce Voice gate: not enrolled. Tap Voice to teach it yours (3 takes).");
    }

    /**
     * Descriptor of the middle of the frame - where a person facing the wearer sits.
     * There is NO face detection here and none is wanted: Spotter never identifies
     * anyone by their face. This is only a "have I met this before" prompt that the
     * wearer answers yes or no to, and a name only ever comes from him.
     */
    private float[] centreCrop(Image img) {
        try {
            Image.Plane[] planes = img.getPlanes();
            ByteBuffer y = planes[0].getBuffer();
            int w = img.getWidth(), h = img.getHeight();
            int cw = w / 3, ch = h / 3, x0 = (w - cw) / 2, y0 = (h - ch) / 2;
            int[] argb = new int[cw * ch];
            for (int yy = 0; yy < ch; yy++) {
                int sy = y0 + yy;
                for (int xx = 0; xx < cw; xx++) {
                    int Y = y.get(sy * planes[0].getRowStride()
                            + (x0 + xx) * planes[0].getPixelStride()) & 0xFF;
                    int uvRow = sy / 2, uvCol = (x0 + xx) / 2;
                    int U = planes[1].getBuffer().get(uvRow * planes[1].getRowStride()
                            + uvCol * planes[1].getPixelStride()) & 0xFF;
                    int V = planes[2].getBuffer().get(uvRow * planes[2].getRowStride()
                            + uvCol * planes[2].getPixelStride()) & 0xFF;
                    int c = Math.max(0, Y - 16), d = U - 128, e = V - 128;
                    int r = clamp((298 * c + 409 * e + 128) >> 8);
                    int g = clamp((298 * c - 100 * d - 208 * e + 128) >> 8);
                    int b = clamp((298 * c + 516 * d + 128) >> 8);
                    argb[yy * cw + xx] = 0xFF000000 | (r << 16) | (g << 8) | b;
                }
            }
            return PersonDescriptor.fromCrop(argb, cw, ch);
        } catch (Throwable e) {
            return null;
        }
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : Math.min(v, 255);
    }


    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        DatGlasses.INSTANCE.handleIntent(data);
        if (code == REQ_DAT_CAM) {
            boolean granted = DatGlasses.INSTANCE.parseCameraPermissionResult(result, data);
            if (!granted) {
                status("Glasses camera permission response received — connecting session…");
            } else {
                status("Glasses camera allowed. Opening session…");
            }
            DatGlasses.INSTANCE.connect();
            if (!connected && !relayBase().isEmpty() && !token().isEmpty()) {
                connect();
            }
            return;
        }
        if (code == REQ_SPEECH) {
            // A kept screen-off utterance and a live turn share REQ_SPEECH. Which one this is
            // decides what happens when there is no transcript: a live turn may retry, a kept
            // one must not.
            File kept = keptBeingTranscribed;
            ArrayList<String> heard = (result == RESULT_OK && data != null)
                    ? data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS) : null;
            if (heard == null || heard.isEmpty()) {
                if (kept != null) {
                    // Do NOT fall through to the live recognizer here: that would record whatever
                    // the phone hears NOW and file it under the moment the gate accepted before.
                    keptBeingTranscribed = null;
                    LockedListener.discard(kept);
                    status("Screen-off audio: no transcript for " + kept.getName() + ".");
                    transcribeNextKept();
                } else if (fileRecognizeTried) {
                    // The device ignored EXTRA_AUDIO_SOURCE. Speak again into the live recognizer;
                    // the speaker verdict is dropped because this is no longer the audio we measured.
                    fileRecognizeTried = false;
                    lastSpeaker = "unknown";
                    status("Retrying with the live recognizer.");
                    listen(null);
                } else {
                    status("Heard nothing.");
                }
                return;
            }
            fileRecognizeTried = false;
            String text = heard.get(0);
            if (kept == null) etGoal.setText(text);
            try {
                JSONObject msg = new JSONObject();
                msg.put("type", "speech");
                msg.put("text", text);
                msg.put("final", true);
                msg.put("speaker", lastSpeaker);
                WakePhrase.Result wp = WakePhrase.parse(text);
                if (wp != null && wp.isCommand()) {
                    msg.put("command", wp.command.name().toLowerCase(Locale.US));
                }
                // A descriptor belongs to the frame in front of the camera NOW. For audio from
                // minutes ago that is the wrong person, so the kept utterance is sent bare.
                float[] d = kept == null ? cropDesc : null;
                if (d != null && System.currentTimeMillis() - cropDescAt < 4000) {
                    JSONArray arr = new JSONArray();
                    for (float f : d) arr.put((double) f);
                    msg.put("descriptor", arr);
                }
                sendWs(msg);
            } catch (Exception ignored) { }
            if (kept == null) {
                status("Heard (" + lastSpeaker + "): " + text);
            } else {
                keptBeingTranscribed = null;
                LockedListener.discard(kept);
                status("From while the screen was off: " + text);
                transcribeNextKept();
            }
        }
    }

    // ------------------------------------------------------------- camera frames
    private void openCamera() {
        openCameraInternal(false);
    }

    /**
     * Re-open the camera with the ImageReader as the only target. The TextureView surface
     * dies with the screen; a reader-only session keeps delivering the YUV frames the relay
     * and the QR scanner need, with no preview window attached.
     */
    private void reopenHeadless() {
        headless = true;
        closeCamera();
        openCameraInternal(true);
    }

    private void openCameraInternal(boolean withoutPreview) {
        if (glassesMode || camera != null || cameraOpening) return;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        if (!withoutPreview && (preview == null || !preview.isAvailable())) return;
        headless = withoutPreview;
        cameraOpening = true;
        try {
            CameraManager mgr = (CameraManager) getSystemService(CAMERA_SERVICE);
            String camId = null;
            for (String id : mgr.getCameraIdList()) {
                Integer facing = mgr.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) { camId = id; break; }
            }
            if (camId == null) camId = mgr.getCameraIdList()[0];
            StreamConfigurationMap map = mgr.getCameraCharacteristics(camId)
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            previewSize = choosePreviewSize(map == null ? null : map.getOutputSizes(SurfaceTexture.class));
            if (camThread == null) {
                camThread = new HandlerThread("cam");
                camThread.start();
                camHandler = new Handler(camThread.getLooper());
            }
            reader = ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 2);
            reader.setOnImageAvailableListener(r -> {
                Image img = r.acquireLatestImage();
                if (img == null) return;
                try {
                    long now = System.currentTimeMillis();
                    if (scanning && now - lastQrAt >= 200) {
                        lastQrAt = now;
                        String payload = tryDecodeQr(img);
                        if (payload != null) {
                            scanning = false;
                            runOnUiThread(() -> applyQr(payload));
                            return;
                        }
                    }
                    if (now - cropDescAt >= 2000) {
                        cropDescAt = now;
                        float[] d = centreCrop(img);
                        if (d != null) cropDesc = d;
                    }
                    if (connected && !glassesMode && now - lastFrameAt >= 1500) {
                        lastFrameAt = now;
                        byte[] jpeg = yuvToJpeg(img);
                        if (jpeg != null) {
                            JSONObject msg = new JSONObject();
                            msg.put("type", "frame");
                            msg.put("source", "phone");
                            msg.put("image", Base64.encodeToString(jpeg, Base64.NO_WRAP));
                            sendWs(msg);
                            framesSent++;
                            if (headless && framesSent % 20 == 0) {
                                SpotterService.update(MainActivity.this,
                                        "Observing with the screen off — " + framesSent + " frames relayed.");
                            }
                        }
                    }
                } catch (Exception ignored) {
                } finally {
                    img.close();
                }
            }, camHandler);
            mgr.openCamera(camId, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice dev) {
                    cameraOpening = false;
                    camera = dev;
                    startPreview();
                }
                @Override public void onDisconnected(CameraDevice dev) {
                    cameraOpening = false;
                    dev.close();
                    if (camera == dev) camera = null;
                }
                @Override public void onError(CameraDevice dev, int error) {
                    cameraOpening = false;
                    dev.close();
                    if (camera == dev) camera = null;
                    status("Camera error " + error + ". Reopen Phone camera mode to retry.");
                }
            }, camHandler);
        } catch (Exception e) {
            cameraOpening = false;
            status("Camera unavailable: " + e.getClass().getSimpleName());
        }
    }

    private Size choosePreviewSize(Size[] choices) {
        if (choices == null || choices.length == 0) return new Size(1280, 720);
        Size fallback = choices[0];
        Size best = null;
        long bestScore = Long.MAX_VALUE;
        for (Size s : choices) {
            if (s.getWidth() == 640 && s.getHeight() == 480) fallback = s;
            if (s.getWidth() > 1280 || s.getHeight() > 1280) continue;
            double ratio = s.getWidth() / (double) Math.max(1, s.getHeight());
            long score = Math.abs(s.getWidth() * s.getHeight() - 1280L * 720L)
                    + (long) (Math.abs(ratio - 16.0 / 9.0) * 1_000_000);
            if (score < bestScore) {
                bestScore = score;
                best = s;
            }
        }
        return best != null ? best : fallback;
    }

    private void configureTransform(int viewWidth, int viewHeight) {
        if (preview == null || previewSize == null || viewWidth <= 0 || viewHeight <= 0) return;
        int rotation = getWindowManager().getDefaultDisplay().getRotation();
        Matrix matrix = new Matrix();
        RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
        RectF bufferRect = new RectF(0, 0, previewSize.getHeight(), previewSize.getWidth());
        float centerX = viewRect.centerX();
        float centerY = viewRect.centerY();
        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
            float scale = Math.min(
                    (float) viewHeight / previewSize.getHeight(),
                    (float) viewWidth / previewSize.getWidth());
            matrix.postScale(scale, scale, centerX, centerY);
            matrix.postRotate(90 * (rotation - 2), centerX, centerY);
        } else if (rotation == Surface.ROTATION_180) {
            matrix.postRotate(180, centerX, centerY);
        } else {
            float scale = Math.min(
                    (float) viewWidth / previewSize.getWidth(),
                    (float) viewHeight / previewSize.getHeight());
            matrix.postScale(scale, scale, centerX, centerY);
        }
        preview.setTransform(matrix);
    }

    private void startPreview() {
        try {
            if (camera == null || reader == null) return;
            Surface surface = null;
            if (!headless) {
                SurfaceTexture st = preview == null ? null : preview.getSurfaceTexture();
                if (st == null) return;
                st.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
                configureTransform(preview.getWidth(), preview.getHeight());
                surface = new Surface(st);
            }
            final Surface previewTarget = surface;
            List<Surface> targets = new ArrayList<>();
            if (previewTarget != null) targets.add(previewTarget);
            targets.add(reader.getSurface());
            final boolean readerOnly = previewTarget == null;
            camera.createCaptureSession(targets, new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession session) {
                    captureSession = session;
                    try {
                        CaptureRequest.Builder b = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                        if (previewTarget != null) b.addTarget(previewTarget);
                        b.addTarget(reader.getSurface());
                        if (readerOnly) {
                            // Screen-off capture: match the JPEG writer's exposure needs
                            // without a preview surface to drive AE.
                            b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new android.util.Range<>(15, 30));
                        }
                        b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                        b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
                        session.setRepeatingRequest(b.build(), null, camHandler);
                    } catch (Exception e) {
                        status("Camera preview failed: " + e.getClass().getSimpleName());
                    }
                }
                @Override public void onConfigureFailed(CameraCaptureSession session) {
                    status("Camera preview could not start. Close other camera apps and retry.");
                }
            }, camHandler);
        } catch (Exception e) {
            status("Camera preview failed: " + e.getClass().getSimpleName());
        }
    }

    private void closeCamera() {
        cameraOpening = false;
        try {
            if (captureSession != null) captureSession.close();
            if (camera != null) camera.close();
            if (reader != null) reader.close();
            if (camThread != null) camThread.quitSafely();
        } catch (Exception ignored) { }
        captureSession = null;
        camera = null;
        reader = null;
        camThread = null;
        camHandler = null;
    }

    private byte[] yuvToJpeg(Image img) {
        try {
            Image.Plane[] planes = img.getPlanes();
            ByteBuffer y = planes[0].getBuffer(), u = planes[1].getBuffer(), v = planes[2].getBuffer();
            int total = y.remaining() + u.remaining() + v.remaining();
            byte[] nv21 = new byte[total];
            int pos = 0;
            pos += copy(y, nv21, pos);
            byte[] ub = new byte[u.remaining()], vb = new byte[v.remaining()];
            u.get(ub); v.get(vb);
            int uvCount = Math.min(ub.length, vb.length);
            for (int i = 0; i < uvCount; i++) {
                nv21[pos++] = vb[i];
                nv21[pos++] = ub[i];
            }
            YuvImage yuv = new YuvImage(nv21, ImageFormat.NV21, img.getWidth(), img.getHeight(), null);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            yuv.compressToJpeg(new Rect(0, 0, img.getWidth(), img.getHeight()), 70, out);
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    private int copy(ByteBuffer src, byte[] dst, int pos) {
        int n = src.remaining();
        src.get(dst, pos, n);
        return n;
    }

    // ------------------------------------------------------------- camera source + QR pairing
    private void setSource(boolean glasses) {
        glassesMode = glasses;
        btnSrcPhone.setBackgroundResource(glasses ? android.R.color.transparent : R.drawable.bg_seg_on);
        btnSrcGlasses.setBackgroundResource(glasses ? R.drawable.bg_seg_on : android.R.color.transparent);
        if (glasses) {
            closeCamera();
            preview.setVisibility(android.view.View.GONE);
            if (!DatGlasses.INSTANCE.isStreaming()) {
                ivGlasses.setVisibility(android.view.View.GONE);
                tvGlasses.setVisibility(android.view.View.VISIBLE);
                tvGlasses.setText("Meta glasses\nConnecting live camera stream…");
                glassesStreamingShown = false;
            } else {
                ivGlasses.setVisibility(android.view.View.VISIBLE);
                tvGlasses.setVisibility(android.view.View.GONE);
            }
            // Immediately initiate Meta Ray-Ban glasses stream upon selecting Meta glasses
            startGlassesFlow();
        } else {
            DatGlasses.INSTANCE.disconnect();
            glassesStreamingShown = false;
            ivGlasses.setVisibility(android.view.View.GONE);
            tvGlasses.setVisibility(android.view.View.GONE);
            preview.setVisibility(android.view.View.VISIBLE);
            if (preview.isAvailable()) openCamera();
        }
        btnConnect.setText(connected ? "Disconnect" : (glasses ? "Connect glasses" : "Connect"));
        if (connected) {
            try {
                JSONObject hello = new JSONObject();
                hello.put("type", "hello");
                hello.put("provider", "spark");
                hello.put("source", glasses ? "glasses" : "phone");
                hello.put("guide", true);
                hello.put("preset", PRESETS[presetIdx][0]);
                if (!missionId.isEmpty()) hello.put("mission_id", missionId);
                sendWs(hello);
            } catch (Exception ignored) { }
        }
        status(glasses
                ? "Meta glasses mode. Connecting live Ray-Ban camera stream…"
                : "Phone camera mode. This phone's camera is the eyes.");
    }

    private String tryDecodeQr(Image img) {
        try {
            Image.Plane y = img.getPlanes()[0];
            ByteBuffer buf = y.getBuffer().duplicate();
            buf.rewind();
            int w = img.getWidth(), h = img.getHeight(), stride = y.getRowStride();
            byte[] data = new byte[w * h];
            byte[] row = new byte[stride];
            for (int r = 0; r < h; r++) {
                int n = Math.min(stride, buf.remaining());
                buf.get(row, 0, n);
                System.arraycopy(row, 0, data, r * w, Math.min(w, n));
            }
            PlanarYUVLuminanceSource src = new PlanarYUVLuminanceSource(
                    data, w, h, 0, 0, w, h, false);
            String text = qrReader.decodeWithState(new BinaryBitmap(new HybridBinarizer(src))).getText();
            qrReader.reset();
            return text;
        } catch (Exception e) {
            try { qrReader.reset(); } catch (Exception ignored) { }
            return null;
        }
    }

    private void applyQr(String payload) {
        try {
            JSONObject o = new JSONObject(payload);
            String relay = o.optString("relay");
            String tok = o.optString("token");
            if (relay.isEmpty() || tok.isEmpty()) throw new Exception("bad payload");
            etRelay.setText(relay);
            etToken.setText(tok);
            savePrefs();
            status("Paired with " + o.optString("name", "FieldKit Relay") + ". Connecting…");
            if (!connected) connect();
        } catch (Exception e) {
            status("That code was not a FieldKit pairing code.");
        }
    }

    // ------------------------------------------------------------- compass
    private final SensorEventListener compassListener = new SensorEventListener() {
        @Override public void onSensorChanged(SensorEvent e) {
            if (e.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
                System.arraycopy(e.values, 0, grav, 0, 3);
                hasGrav = true;
            } else if (e.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) {
                System.arraycopy(e.values, 0, mag, 0, 3);
                hasMag = true;
            }
            if (!hasGrav || !hasMag) return;
            float[] r = new float[9], o = new float[3];
            if (SensorManager.getRotationMatrix(r, null, grav, mag)) {
                SensorManager.getOrientation(r, o);
                int az = (int) ((Math.toDegrees(o[0]) + 360) % 360);
                lastHeadingDeg = az;
                String[] winds = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
                String wind = winds[(int) Math.round(az / 45.0) % 8];
                tvCompass.setText(String.format(Locale.US, "Heading %03d° %s", az, wind));
                long nowMs = System.currentTimeMillis();
                if (connected && nowMs - lastCompassSentAt >= 1000) {
                    lastCompassSentAt = nowMs;
                    try {
                        JSONObject m = new JSONObject();
                        m.put("type", "compass");
                        m.put("heading", az);
                        m.put("source", glassesMode ? "glasses" : "phone");
                        sendWs(m);
                    } catch (Exception ignored) { }
                }
            }
        }

        @Override public void onAccuracyChanged(Sensor s, int accuracy) { }
    };

    @Override protected void onResume() {
        DatGlasses.INSTANCE.attachActivity(this);
        super.onResume();
        // One microphone, and the screen-on path gets it: the service stops reading while
        // the app is in front so the Talk button's one-shot capture can open the device.
        SpotterService.setListening(this, false);
        String heardOff = SpotterService.lastHeard();
        if (!heardOff.isEmpty()) status("While the screen was off: " + heardOff);
        // The screen-off loop keeps the wearer's own utterances as WAVs and deliberately does not
        // transcribe them - Android's recognizer needs an Activity. Back in front, that audio goes
        // to the recognizer through the same EXTRA_AUDIO_SOURCE path the Talk button uses, so what
        // is transcribed is the audio the print gate already accepted, not whatever the phone
        // happens to hear now.
        drainKeptUtterances();
        // Returning from the Meta AI registration card: continue the glasses flow the
        // moment the SDK reports REGISTERED, which is what unblocks the session.
        if (pendingRegistration && glassesMode) {
            if (DatGlasses.INSTANCE.isRegistered()) {
                pendingRegistration = false;
                status("Glasses registered \u2014 opening the glasses camera\u2026");
                startGlassesFlow();
            } else if (!registrationWaitLogged) {
                registrationWaitLogged = true;
                status("Waiting on Meta AI to register Spotter. Diag: "
                        + DatGlasses.INSTANCE.diagnostics());
            }
        }
        if (headless && !glassesMode) {
            // Screen is back: put the preview surface back onto the session.
            headless = false;
            closeCamera();
            if (preview != null && preview.isAvailable()) openCamera();
        }
        Sensor acc = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        Sensor mg = sensors.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        if (acc != null) sensors.registerListener(compassListener, acc, SensorManager.SENSOR_DELAY_UI);
        if (mg != null) sensors.registerListener(compassListener, mg, SensorManager.SENSOR_DELAY_UI);
        if (soundField != null) soundField.start(this);
    }

    @Override protected void onPause() {
        DatGlasses.INSTANCE.detachActivity(this);
        // A live session keeps the mic and the compass running: FieldKit's whole point is
        // that it goes on observing once the screen goes dark. The foreground service holds
        // the camera + microphone types that make background capture legal on Android 14.
        boolean keepSession = SpotterService.isAlive() && connected;
        if (!keepSession) {
            if (soundField != null) soundField.stop();
            sensors.unregisterListener(compassListener);
        }
        super.onPause();
        if (keepSession) {
            SpotterService.update(this, (glassesMode ? "Glasses" : "Phone camera")
                    + " observing with the screen off — " + relayBase());
            // Screen going dark: now the listener may take the microphone. The gate it
            // loads is the enrolled print, so a bystander's voice is discarded, not stored.
            SpotterService.setListening(this, true);
        }
    }

    @Override
    protected void onDestroy() {
        closeCamera();
        DatGlasses.INSTANCE.disconnect();
        disconnect();
        if (tts != null) tts.shutdown();
        super.onDestroy();
    }
}
