package tech.nullai.fieldkit.live;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.NoiseSuppressor;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * A real full-duplex voice call with Muse over Gemini Live.
 *
 * <p>Shape of a call:
 * <ol>
 *   <li>mint a single-use token from the relay,</li>
 *   <li>open the constrained Live WebSocket,</li>
 *   <li>send {@code setup}, wait for {@code setupComplete},</li>
 *   <li>stream the microphone continuously at 16 kHz PCM16 and play the model's
 *       24 kHz PCM16 as it arrives.</li>
 * </ol>
 *
 * <p><b>The microphone is never gated.</b> Capture starts when the call connects
 * and stops only in {@link #hangUp()}. There is no VAD, no push-to-talk and no
 * "muse is speaking so mute the user" logic: both sides can talk at the same
 * time, exactly like a phone call. When the model reports
 * {@code serverContent.interrupted}, the queued *output* audio is dropped
 * instantly so Muse stops mid-sentence, while capture keeps running.
 */
public final class LiveCall {

    private static final String TAG = "LiveCall";

    public interface Listener {
        void onState(String state);
        void onReady();
        /** level 0..1 for the given side, for the avatar's mouth/rings. */
        void onLevel(boolean muse, float level);
        void onText(String text, boolean fromUser);
        void onEnded(String reason);
    }

    public static final int IN_RATE = 16000;
    public static final int OUT_RATE = 24000;
    /** 40 ms of 16 kHz mono PCM16 — small enough to feel live, big enough to be cheap. */
    private static final int IN_CHUNK = 1280;
    private static final int VIDEO_MAX_FPS = 3;
    private static final int MAX_RECONNECTS = 4;

    private final String relayBase;
    private final String agentToken;
    private final String initialMission;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService videoExec = Executors.newSingleThreadExecutor();
    private volatile boolean videoSendBusy = false;

    private final OkHttpClient http;
    private WebSocket ws;
    private volatile boolean running;
    private volatile boolean setUp;
    private volatile boolean hungUp;
    private volatile String resumeHandle;
    private volatile int reconnects;
    private volatile boolean muted;
    private boolean videoEnabled = true;

    private AudioRecord record;
    private Thread micThread;
    private AudioTrack track;
    private Thread playThread;
    private AcousticEchoCanceler aec;
    private NoiseSuppressor ns;

    private final ArrayDeque<byte[]> outQueue = new ArrayDeque<>();
    private final Object outLock = new Object();
    private volatile boolean playing = true;
    private volatile long lastVideoMs;

    private int levelUpdates;
    private volatile float micLevel;
    private volatile long uplinkChunks, downlinkChunks, videoFrames;
    private final StringBuilder museTurnBuf = new StringBuilder();
    private final StringBuilder userTurnBuf = new StringBuilder();

    public LiveCall(String relayBase, String agentToken, Listener listener) {
        this(relayBase, agentToken, "", listener);
    }

    public LiveCall(String relayBase, String agentToken, String initialMission, Listener listener) {
        this.relayBase = relayBase;
        this.agentToken = agentToken;
        this.initialMission = initialMission == null ? "" : initialMission;
        this.listener = listener;
        this.http = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)   // a call stays open
                .pingInterval(20, TimeUnit.SECONDS)      // keep NAT/Tailscale paths warm
                .build();
    }

    // ---------------------------------------------------------------- lifecycle

    public void start() {
        if (running) return;
        running = true;
        hungUp = false;
        speak("Connecting…");
        new Thread(this::openOnce, "live-open").start();
    }

    /** The ONE place capture ends. Everything else keeps the mic hot. */
    public void hangUp() {
        if (hungUp) return;
        hungUp = true;
        running = false;
        speak("Ending call…");
        try { if (ws != null) ws.close(1000, "hangup"); } catch (Exception ignored) { }
        try { videoExec.shutdownNow(); } catch (Exception ignored) { }
        stopAudio();
        main.post(() -> {
            listener.onLevel(false, 0f);
            listener.onLevel(true, 0f);
            listener.onEnded("You ended the call");
        });
    }

    public boolean isRunning() { return running && !hungUp; }

    // ---------------------------------------------------------------- call stats

    /** Chunks of microphone PCM actually handed to the session (never gated). */
    public long uplinkChunks() { return uplinkChunks; }

    /** Audio chunks the model sent back and that we queued for playback. */
    public long downlinkChunks() { return downlinkChunks; }

    public long videoFrames() { return videoFrames; }

    /** Latest mic RMS 0..1 — the mic is open and this is what it is hearing. */
    public float micLevel() { return micLevel; }

    /** Voice input is intentionally NOT gated; mute only silences what we send. */
    public void setMuted(boolean m) { muted = m; }

    public void setVideoEnabled(boolean on) { videoEnabled = on; }

    // ------------------------------------------------------------------ connect

    private void openOnce() {
        if (!running) return;
        try {
            LiveTokenClient.Session s = LiveTokenClient.mint(relayBase, agentToken, initialMission);
            setUp = false;
            speak("Calling Muse…");
            Request req = new Request.Builder().url(s.connectUrl()).build();
            final String setup = withResumption(s.setupFrame());
            ws = http.newWebSocket(req, new WebSocketListener() {
                @Override public void onOpen(WebSocket webSocket, Response response) {
                    Log.i(TAG, "live ws open (" + response.code() + ")");
                    webSocket.send(setup);
                    startAudio();
                }

                @Override public void onMessage(WebSocket webSocket, String text) {
                    handleFrame(text);
                }

                // Gemini Live delivers JSON in binary frames as well as text.
                @Override public void onMessage(WebSocket webSocket, ByteString bytes) {
                    handleFrame(bytes.utf8());
                }

                @Override public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                    if (hungUp) return;
                    Log.w(TAG, "live ws failure: " + t);
                    retry("link dropped: " + t.getMessage());
                }

                @Override public void onClosed(WebSocket webSocket, int code, String reason) {
                    if (hungUp) return;
                    retry("link closed (" + code + ")");
                }
            });
        } catch (Exception e) {
            retry("could not reach the relay: " + e.getMessage());
        }
    }

    /** Adds the resumption handle when we are re-joining a session after a drop. */
    private String withResumption(String setupFrame) {
        String h = resumeHandle;
        if (h == null || h.isEmpty()) return setupFrame;
        try {
            JSONObject o = new JSONObject(setupFrame);
            o.getJSONObject("setup").put("sessionResumption", new JSONObject().put("handle", h));
            return o.toString();
        } catch (Exception e) {
            return setupFrame;
        }
    }

    private void retry(String why) {
        if (hungUp || !running) return;
        stopAudio();
        setUp = false;
        if (reconnects >= MAX_RECONNECTS) {
            running = false;
            speak("Call dropped.");
            main.post(() -> listener.onEnded(why));
            return;
        }
        reconnects++;
        long waitMs = 600L * reconnects;
        speak("Reconnecting… (" + reconnects + "/" + MAX_RECONNECTS + ")");
        main.postDelayed(this::openOnce, waitMs);
    }

    // -------------------------------------------------------------------- audio

    private void startAudio() {
        try {
            if (record == null) {
                int minBuf = AudioRecord.getMinBufferSize(IN_RATE,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                record = new AudioRecord.Builder()
                        .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                        .setAudioFormat(new AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(IN_RATE)
                                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                                .build())
                        .setBufferSizeInBytes(Math.max(minBuf, IN_CHUNK * 10))
                        .build();
                if (AcousticEchoCanceler.isAvailable()) {
                    aec = AcousticEchoCanceler.create(record.getAudioSessionId());
                    if (aec != null) aec.setEnabled(true);   // keeps Muse's voice out of the mic
                }
                if (NoiseSuppressor.isAvailable()) {
                    ns = NoiseSuppressor.create(record.getAudioSessionId());
                    if (ns != null) ns.setEnabled(true);
                }
                record.startRecording();
                micThread = new Thread(this::micLoop, "live-mic");
                micThread.setPriority(Thread.MAX_PRIORITY);
                micThread.start();
            }
            if (track == null) {
                int minOut = AudioTrack.getMinBufferSize(OUT_RATE,
                        AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
                AudioTrack.Builder tb = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .setFlags(AudioAttributes.FLAG_LOW_LATENCY)
                                .build())
                        .setAudioFormat(new AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(OUT_RATE)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .build())
                        .setBufferSizeInBytes(Math.max(minOut, OUT_RATE / 10 * 2))
                        .setTransferMode(AudioTrack.MODE_STREAM);
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    tb.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY);
                }
                track = tb.build();
                track.play();
                playThread = new Thread(this::playLoop, "live-play");
                playThread.setPriority(Thread.MAX_PRIORITY);
                playThread.start();
            }
        } catch (Exception e) {
            Log.e(TAG, "audio start failed", e);
            speak("Microphone unavailable: " + e.getMessage());
        }
    }

    /** Continuous capture: runs from connect until hangUp, with no gating. */
    private void micLoop() {
        byte[] buf = new byte[IN_CHUNK];
        while (running && record != null) {
            int n;
            try {
                n = record.read(buf, 0, buf.length);
            } catch (Exception e) {
                break;
            }
            if (n <= 0) continue;
            float lvl = rms(buf, n);
            micLevel = lvl;
            if ((levelUpdates++ & 1) == 0) main.post(() -> listener.onLevel(false, lvl));
            if (!setUp || muted) continue;
            byte[] chunk = (n == buf.length) ? buf.clone() : java.util.Arrays.copyOf(buf, n);
            sendAudio(chunk);
        }
    }

    private void sendAudio(byte[] pcm) {
        WebSocket w = ws;
        if (w == null || !setUp) return;
        try {
            JSONObject blob = new JSONObject()
                    .put("data", Base64.encodeToString(pcm, Base64.NO_WRAP))
                    .put("mimeType", "audio/pcm;rate=" + IN_RATE);
            w.send(new JSONObject().put("realtimeInput", new JSONObject().put("audio", blob)).toString());
            uplinkChunks++;
        } catch (Exception e) {
            Log.w(TAG, "audio send failed: " + e);
        }
    }

    private void playLoop() {
        while (playing) {
            byte[] chunk;
            synchronized (outLock) {
                while (playing && outQueue.isEmpty()) {
                    try { outLock.wait(200); } catch (InterruptedException e) { return; }
                }
                if (!playing) return;
                chunk = outQueue.poll();
            }
            try {
                if (track != null && chunk != null) track.write(chunk, 0, chunk.length);
            } catch (Exception e) {
                Log.w(TAG, "playback failed: " + e);
            }
        }
    }

    private void speak(String s) {
        main.post(() -> listener.onState(s));
    }

    private void stopAudio() {
        playing = false;
        synchronized (outLock) { outQueue.clear(); outLock.notifyAll(); }
        if (record != null) {
            try { record.stop(); } catch (Exception ignored) { }
            try { record.release(); } catch (Exception ignored) { }
            record = null;
        }
        if (aec != null) { try { aec.release(); } catch (Exception ignored) { } aec = null; }
        if (ns != null) { try { ns.release(); } catch (Exception ignored) { } ns = null; }
        if (track != null) {
            try { track.pause(); track.flush(); track.release(); } catch (Exception ignored) { }
            track = null;
        }
        micThread = null;
        playThread = null;
    }

    // ------------------------------------------------------------------ frames

    /** Sends one JPEG from the phone camera / glasses to Muse (up to 3 fps, non-blocking). */
    public void sendVideoFrame(byte[] jpeg) {
        sendVideoFrameInternal(jpeg, false);
    }

    /** Forces immediate transmission of the current frame (e.g. on camera switch or call start). */
    public void sendVideoFrameImmediate(byte[] jpeg) {
        sendVideoFrameInternal(jpeg, true);
    }

    private void sendVideoFrameInternal(byte[] jpeg, boolean force) {
        WebSocket w = ws;
        if (w == null || !setUp || !videoEnabled || jpeg == null || jpeg.length == 0) return;
        long now = System.currentTimeMillis();
        if (!force && (videoSendBusy || now - lastVideoMs < 1000 / VIDEO_MAX_FPS)) return;
        lastVideoMs = now;
        videoSendBusy = true;
        final byte[] snapshot = jpeg;
        try {
            videoExec.execute(() -> {
                try {
                    WebSocket sock = ws;
                    if (sock == null || !setUp || !videoEnabled) return;
                    byte[] compact = compactJpegForLive(snapshot);
                    JSONObject blob = new JSONObject()
                            .put("data", Base64.encodeToString(compact, Base64.NO_WRAP))
                            .put("mimeType", "image/jpeg");
                    sock.send(new JSONObject().put("realtimeInput", new JSONObject().put("video", blob)).toString());
                    videoFrames++;
                } catch (Exception e) {
                    Log.w(TAG, "video send failed: " + e);
                } finally {
                    videoSendBusy = false;
                }
            });
        } catch (Exception ignored) {
            videoSendBusy = false;
        }
    }

    /** Downscales large JPEGs to <=480px @ Q72 (~16-24 KB) so frames arrive with minimal latency. */
    private static byte[] compactJpegForLive(byte[] jpeg) {
        if (jpeg == null || jpeg.length <= 36_000) return jpeg;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, bounds);
            int w = bounds.outWidth, h = bounds.outHeight;
            if (w <= 0 || h <= 0) return jpeg;
            int sample = 1;
            while (Math.max(w / sample, h / sample) > 640) {
                sample *= 2;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            Bitmap bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, opts);
            if (bmp == null) return jpeg;
            int bw = bmp.getWidth(), bh = bmp.getHeight();
            int maxDim = Math.max(bw, bh);
            if (maxDim > 480) {
                float scale = 480f / maxDim;
                Bitmap scaled = Bitmap.createScaledBitmap(
                        bmp,
                        Math.max(1, Math.round(bw * scale)),
                        Math.max(1, Math.round(bh * scale)),
                        true
                );
                if (scaled != bmp) bmp.recycle();
                bmp = scaled;
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream(24_576);
            bmp.compress(Bitmap.CompressFormat.JPEG, 72, bos);
            bmp.recycle();
            byte[] out = bos.toByteArray();
            return out.length > 0 && out.length < jpeg.length ? out : jpeg;
        } catch (Throwable t) {
            return jpeg;
        }
    }

    /** Sends a text turn that prompts Muse to reply immediately (turnComplete=true). */
    public void sendText(String text) {
        sendClientContent(text, true);
    }

    /**
     * Injects background context (Saved People roster, live Face+Body Character Board recognition,
     * camera switches, detector telemetry) into the active Live session without interrupting ongoing
     * speech (turnComplete=false).
     */
    public void sendContext(String text) {
        sendClientContent(text, false);
    }

    private void sendClientContent(String text, boolean turnComplete) {
        WebSocket w = ws;
        if (w == null || !setUp || text == null || text.trim().isEmpty()) return;
        String clean = text.trim();
        try {
            JSONObject part = new JSONObject().put("text", clean);
            JSONObject turn = new JSONObject()
                    .put("role", "user")
                    .put("parts", new JSONArray().put(part));
            JSONObject clientContent = new JSONObject()
                    .put("turns", new JSONArray().put(turn))
                    .put("turnComplete", turnComplete);
            w.send(new JSONObject().put("clientContent", clientContent).toString());
        } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------------ frames in

    private void handleFrame(String raw) {
        if (raw == null || raw.isEmpty()) return;
        try {
            JSONObject o = new JSONObject(raw);
            if (o.has("setupComplete")) {
                setUp = true;
                reconnects = 0;
                speak("Live");
                main.post(listener::onReady);
                return;
            }
            JSONObject sru = o.optJSONObject("sessionResumptionUpdate");
            if (sru != null) {
                String h = sru.optString("newHandle", "");
                if (!h.isEmpty()) resumeHandle = h;
            }
            if (o.has("goAway")) {
                speak("Muse is switching sessions…");
                retry("session goAway");
                return;
            }
            JSONObject sc = o.optJSONObject("serverContent");
            if (sc == null) return;

            if (sc.optBoolean("interrupted", false)) {
                // Barge-in: drop the queued model audio immediately. The mic is
                // untouched — the wearer can talk over Muse and keep talking.
                synchronized (outLock) { outQueue.clear(); }
                if (track != null) { try { track.pause(); track.flush(); track.play(); } catch (Exception ignored) { } }
                main.post(() -> listener.onLevel(true, 0f));
            }

            JSONObject turn = sc.optJSONObject("modelTurn");
            if (turn != null) {
                org.json.JSONArray parts = turn.optJSONArray("parts");
                for (int i = 0; parts != null && i < parts.length(); i++) {
                    JSONObject p = parts.optJSONObject(i);
                    if (p == null) continue;
                    JSONObject inl = p.optJSONObject("inlineData");
                    if (inl != null) {
                        String data = inl.optString("data", "");
                        if (data.isEmpty()) continue;
                        byte[] pcm = Base64.decode(data, Base64.DEFAULT);
                        float lvl = rms(pcm, pcm.length);
                        synchronized (outLock) {
                            outQueue.add(pcm);          // bounded by playback speed
                            downlinkChunks++;
                            if (outQueue.size() > 120) outQueue.poll();
                            outLock.notifyAll();
                        }
                        main.post(() -> listener.onLevel(true, lvl));
                    }
                    String t = p.optString("text", "");
                    if (!t.isEmpty()) {
                        museTurnBuf.append(t);
                        final String cur = museTurnBuf.toString().trim();
                        main.post(() -> listener.onText(cur, false));
                    }
                }
            }
            JSONObject ot = sc.optJSONObject("outputTranscription");
            if (ot != null && ot.has("text")) {
                String t = ot.optString("text", "");
                if (!t.isEmpty()) {
                    museTurnBuf.append(t);
                    final String cur = museTurnBuf.toString().trim();
                    main.post(() -> listener.onText(cur, false));
                }
            }
            JSONObject it = sc.optJSONObject("inputTranscription");
            if (it != null && it.has("text")) {
                String t = it.optString("text", "");
                if (!t.isEmpty()) {
                    userTurnBuf.append(t);
                    final String cur = userTurnBuf.toString().trim();
                    main.post(() -> listener.onText(cur, true));
                    userTurnBuf.setLength(0);
                }
            }
            if (sc.optBoolean("turnComplete", false) || sc.optBoolean("interrupted", false)) {
                museTurnBuf.setLength(0);
                userTurnBuf.setLength(0);
                main.post(() -> listener.onLevel(true, 0f));
            }
        } catch (Exception e) {
            Log.w(TAG, "frame parse: " + e.getMessage());
        }
    }

    private static float rms(byte[] pcm, int n) {
        if (n < 2) return 0f;
        double sum = 0;
        for (int i = 0; i + 1 < n; i += 2) {
            short s = (short) ((pcm[i] & 0xFF) | (pcm[i + 1] << 8));
            sum += (double) s * s;
        }
        double r = Math.sqrt(sum / (n / 2.0)) / 32768.0;
        // Map speech-level RMS (~0.005..0.25) onto a usable 0..1 for the avatar.
        float v = (float) Math.min(1.0, Math.max(0.0, (r - 0.004) * 18.0));
        return v;
    }
}
