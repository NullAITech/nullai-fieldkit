package tech.nullai.fieldkit;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Rect;
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
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.util.Base64;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.common.HybridBinarizer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
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

    private EditText etRelay, etToken, etGoal;
    private TextView tvStep, tvStatus, tvGlasses, tvCompass, tvBraille;
    private TextureView preview;
    private Button btnConnect, btnSrcPhone, btnSrcGlasses;
    private boolean glassesMode = false;
    private boolean scanning = false;
    private boolean brailleOn = false;
    private long lastBrailleAt = 0;
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
    private ImageReader reader;
    private HandlerThread camThread;
    private Handler camHandler;
    private long lastFrameAt = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        etRelay = findViewById(R.id.etRelay);
        etToken = findViewById(R.id.etToken);
        etGoal = findViewById(R.id.etGoal);
        tvStep = findViewById(R.id.tvStep);
        tvStatus = findViewById(R.id.tvStatus);
        tvGlasses = findViewById(R.id.tvGlasses);
        tvCompass = findViewById(R.id.tvCompass);
        tvBraille = findViewById(R.id.tvBraille);
        preview = findViewById(R.id.preview);

        sensors = (SensorManager) getSystemService(SENSOR_SERVICE);
        Button btnEyes = findViewById(R.id.btnEyes);
        btnEyes.setOnClickListener(v -> {
            brailleOn = !brailleOn;
            tvBraille.setVisibility(brailleOn ? android.view.View.VISIBLE : android.view.View.GONE);
            if (!glassesMode) {
                preview.setVisibility(brailleOn ? android.view.View.GONE : android.view.View.VISIBLE);
            }
            btnEyes.setText(brailleOn ? "Eyes view: braille" : "Eyes view: photo");
        });
        btnConnect = findViewById(R.id.btnConnect);
        btnSrcPhone = findViewById(R.id.btnSrcPhone);
        btnSrcGlasses = findViewById(R.id.btnSrcGlasses);

        qrReader = new MultiFormatReader();
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, Arrays.asList(BarcodeFormat.QR_CODE));
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        qrReader.setHints(hints);

        Button btnPreset = findViewById(R.id.btnPreset);
        btnPreset.setOnClickListener(v -> {
            presetIdx = (presetIdx + 1) % PRESETS.length;
            btnPreset.setText("Preset: " + PRESETS[presetIdx][1]);
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

        btnSrcPhone.setOnClickListener(v -> setSource(false));
        btnSrcGlasses.setOnClickListener(v -> setSource(true));
        findViewById(R.id.btnScan).setOnClickListener(v -> {
            if (glassesMode) setSource(false);
            scanning = true;
            status("Point the camera at the relay QR code…");
        });
        setSource(false);

        SharedPreferences p = getSharedPreferences("fieldkit", MODE_PRIVATE);
        etRelay.setText(p.getString("relay", "http://192.168.1.20:8766"));
        etToken.setText(p.getString("token", ""));

        tts = new TextToSpeech(this, s -> {
            if (s == TextToSpeech.SUCCESS) tts.setLanguage(Locale.US);
        });

        DatGlasses.INSTANCE.init(this);
        DatGlasses.INSTANCE.setListener(new DatGlasses.Listener() {
            @Override public void onStatus(String text) { status(text); }

            @Override public void onJpeg(byte[] jpeg) {
                showBraille(jpeg);
                try {
                    JSONObject msg = new JSONObject();
                    msg.put("type", "frame");
                    msg.put("image", Base64.encodeToString(jpeg, Base64.NO_WRAP));
                    sendWs(msg);
                } catch (Exception ignored) { }
            }
        });

        btnConnect.setOnClickListener(v -> {
            savePrefs();
            if (glassesMode) {
                if (connected) {
                    disconnect();
                    DatGlasses.INSTANCE.disconnect();
                } else if (!DatGlasses.INSTANCE.isRegistered()) {
                    DatGlasses.INSTANCE.register(this);
                    status("Opening Meta AI registration. Finish there, then tap Connect glasses again.");
                } else {
                    DatGlasses.INSTANCE.connect();
                    connect();
                }
            } else if (connected) disconnect(); else connect();
        });
        findViewById(R.id.btnMission).setOnClickListener(v -> {
            savePrefs();
            startMission();
        });
        findViewById(R.id.btnNext).setOnClickListener(v -> nextStep());
        findViewById(R.id.btnTalk).setOnClickListener(v -> talk());

        preview.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) { openCamera(); }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) { }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) { closeCamera(); return true; }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture st) { }
        });

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO}, REQ_PERMS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] grants) {
        if (code == REQ_PERMS && preview.isAvailable()) openCamera();
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
            Toast.makeText(this, "Set relay address and token first.", Toast.LENGTH_SHORT).show();
            return;
        }
        String wsUrl = base.replaceFirst("^http", "ws") + "/v1/sessions";
        Request req = new Request.Builder().url(wsUrl)
                .header("Authorization", "Bearer " + token()).build();
        ws = http.newWebSocket(req, new WebSocketListener() {
            @Override public void onOpen(WebSocket webSocket, Response response) {
                connected = true;
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
                runOnUiThread(() -> btnConnect.setText("Connect"));
                status("Connection failed: " + t.getClass().getSimpleName());
            }

            @Override public void onClosed(WebSocket webSocket, int code, String reason) {
                connected = false;
                runOnUiThread(() -> btnConnect.setText("Connect"));
                status("Disconnected.");
            }
        });
    }

    private void disconnect() {
        if (ws != null) {
            try {
                ws.send(new JSONObject().put("type", "bye").toString());
            } catch (Exception ignored) { }
            ws.close(1000, "bye");
            ws = null;
        }
        connected = false;
        btnConnect.setText("Connect");
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
                        missionId = m.optString("id");
                        JSONArray steps = m.optJSONArray("steps");
                        String first = steps != null && steps.length() > 0
                                ? steps.getJSONObject(0).optString("text") : "";
                        runOnUiThread(() -> tvStep.setText("Step 1/" + (steps == null ? 0 : steps.length()) + ": " + first));
                        speak("Mission ready. Step one: " + first);
                        status("Mission started. Reconnecting session to attach it…");
                        // Re-hello with mission_id so coaching turns carry the step.
                        if (connected) { disconnect(); }
                        connect();
                    } catch (Exception e) {
                        status("Mission reply unreadable.");
                    }
                }
            });
        } catch (Exception e) {
            status("Mission failed to start.");
        }
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
    private void talk() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US);
        try {
            startActivityForResult(intent, REQ_SPEECH);
        } catch (Exception e) {
            Toast.makeText(this, "Speech input not available on this device.", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code == REQ_SPEECH && result == RESULT_OK && data != null) {
            ArrayList<String> heard = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (heard != null && !heard.isEmpty()) {
                String text = heard.get(0);
                etGoal.setText(text);
                try {
                    JSONObject msg = new JSONObject();
                    msg.put("type", "speech");
                    msg.put("text", text);
                    msg.put("final", true);
                    msg.put("speaker", "wearer");   // this phone's mic = the wearer
                    sendWs(msg);
                } catch (Exception ignored) { }
                status("Heard: " + text);
            }
        }
    }

    // ------------------------------------------------------------- camera frames
    private void openCamera() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        try {
            CameraManager mgr = (CameraManager) getSystemService(CAMERA_SERVICE);
            String camId = null;
            for (String id : mgr.getCameraIdList()) {
                Integer facing = mgr.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) { camId = id; break; }
            }
            if (camId == null) camId = mgr.getCameraIdList()[0];
            camThread = new HandlerThread("cam");
            camThread.start();
            camHandler = new Handler(camThread.getLooper());
            reader = ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 2);
            reader.setOnImageAvailableListener(r -> {
                Image img = r.acquireLatestImage();
                if (img == null) return;
                try {
                    if (scanning) {
                        String payload = tryDecodeQr(img);
                        if (payload != null) {
                            scanning = false;
                            runOnUiThread(() -> applyQr(payload));
                            return;
                        }
                    }
                    long now = System.currentTimeMillis();
                    if (connected && !glassesMode && now - lastFrameAt >= 1500) {
                        lastFrameAt = now;
                        byte[] jpeg = yuvToJpeg(img);
                        if (jpeg != null) {
                            showBraille(jpeg);
                            JSONObject msg = new JSONObject();
                            msg.put("type", "frame");
                            msg.put("image", Base64.encodeToString(jpeg, Base64.NO_WRAP));
                            sendWs(msg);
                        }
                    }
                } catch (Exception ignored) {
                } finally {
                    img.close();
                }
            }, camHandler);
            mgr.openCamera(camId, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice dev) {
                    camera = dev;
                    startPreview();
                }
                @Override public void onDisconnected(CameraDevice dev) { dev.close(); }
                @Override public void onError(CameraDevice dev, int error) { dev.close(); }
            }, camHandler);
        } catch (Exception e) {
            status("Camera unavailable: " + e.getClass().getSimpleName());
        }
    }

    private void startPreview() {
        try {
            SurfaceTexture st = preview.getSurfaceTexture();
            if (st == null || camera == null) return;
            st.setDefaultBufferSize(640, 480);
            Surface surface = new Surface(st);
            List<Surface> targets = Arrays.asList(surface, reader.getSurface());
            camera.createCaptureSession(targets, new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession session) {
                    try {
                        CaptureRequest.Builder b = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                        b.addTarget(surface);
                        b.addTarget(reader.getSurface());
                        session.setRepeatingRequest(b.build(), null, camHandler);
                    } catch (Exception ignored) { }
                }
                @Override public void onConfigureFailed(CameraCaptureSession session) { }
            }, camHandler);
        } catch (Exception ignored) { }
    }

    private void closeCamera() {
        try {
            if (camera != null) camera.close();
            if (reader != null) reader.close();
            if (camThread != null) camThread.quitSafely();
        } catch (Exception ignored) { }
        camera = null;
    }

    private byte[] yuvToJpeg(Image img) {
        try {
            Image.Plane[] planes = img.getPlanes();
            ByteBuffer y = planes[0].getBuffer(), u = planes[1].getBuffer(), v = planes[2].getBuffer();
            int total = y.remaining() + u.remaining() + v.remaining();
            byte[] nv21 = new byte[total];
            int pos = 0;
            pos += copy(y, nv21, pos);
            // VU interleaved for NV21
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
        preview.setVisibility(glasses ? android.view.View.GONE : android.view.View.VISIBLE);
        tvGlasses.setVisibility(glasses ? android.view.View.VISIBLE : android.view.View.GONE);
        btnConnect.setText(glasses ? "Connect glasses" : "Connect");
        status(glasses
                ? "Meta glasses mode. Frames come from your glasses through the relay; this phone is the controller."
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

    // ------------------------------------------------------------- compass + braille eyes view
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
        super.onResume();
        Sensor acc = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        Sensor mg = sensors.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        if (acc != null) sensors.registerListener(compassListener, acc, SensorManager.SENSOR_DELAY_UI);
        if (mg != null) sensors.registerListener(compassListener, mg, SensorManager.SENSOR_DELAY_UI);
    }

    @Override protected void onPause() {
        sensors.unregisterListener(compassListener);
        super.onPause();
    }

    private void showBraille(byte[] jpeg) {
        if (!brailleOn || jpeg == null) return;
        long now = System.currentTimeMillis();
        if (now - lastBrailleAt < 250) return;
        lastBrailleAt = now;
        Bitmap bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
        if (bmp == null) return;
        String art = brailleOf(bmp, 54);
        runOnUiThread(() -> tvBraille.setText(art));
    }

    /** Live eyes view: luminance -> ordered (Bayer 4x4) dither -> braille cells (2x4 dots). */
    static String brailleOf(Bitmap src, int cols) {
        int rows = Math.max(6, (int) (cols * (src.getHeight() / (float) src.getWidth()) * 0.5f));
        Bitmap small = Bitmap.createScaledBitmap(src, cols * 2, rows * 4, true);
        int[][] bayer = {{0, 8, 2, 10}, {12, 4, 14, 6}, {3, 11, 1, 9}, {15, 7, 13, 5}};
        int[][] dotBit = {{1, 2, 4, 64}, {8, 16, 32, 128}};
        StringBuilder sb = new StringBuilder();
        for (int cy = 0; cy < rows; cy++) {
            for (int cx = 0; cx < cols; cx++) {
                int bits = 0;
                for (int dx = 0; dx < 2; dx++) {
                    for (int dy = 0; dy < 4; dy++) {
                        int px = small.getPixel(cx * 2 + dx, cy * 4 + dy);
                        int lum = (299 * ((px >> 16) & 0xFF) + 587 * ((px >> 8) & 0xFF) + 114 * (px & 0xFF)) / 1000;
                        int thr = (int) ((bayer[(cy * 4 + dy) % 4][(cx * 2 + dx) % 4] + 0.5f) / 16f * 255f);
                        if (lum < thr) bits |= dotBit[dx][dy];
                    }
                }
                sb.append((char) (0x2800 + bits));
            }
            if (cy < rows - 1) sb.append('\n');
        }
        return sb.toString();
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
