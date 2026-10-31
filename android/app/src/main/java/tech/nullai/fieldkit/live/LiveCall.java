package tech.nullai.fieldkit.live;

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

import java.util.ArrayDeque;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

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
    private static final int VIDEO_MAX_FPS = 1;
    private static final int MAX_RECONNECTS = 4;

    private final String relayBase;
    private final String agentToken;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());

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

    public LiveCall(String relayBase, String agentToken, Listener listener) {
        this.relayBase = relayBase;
        this.agentToken = agentToken;
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
            LiveTokenClient.Session s = LiveTokenClient.mint(relayBase, agentToken);
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
                track = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build())
                        .setAudioFormat(new AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(OUT_RATE)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .build())
                        .setBufferSizeInBytes(Math.max(minOut, OUT_RATE / 5 * 2))
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build();
                track.play();
                playThread = new Thread(this::playLoop, "live-play");
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

    /** Sends one JPEG from the phone camera / glasses to Muse (<= 1 fps). */
    public void sendVideoFrame(byte[] jpeg) {
        WebSocket w = ws;
        if (w == null || !setUp || !videoEnabled || jpeg == null || jpeg.length == 0) return;
        long now = System.currentTimeMillis();
        if (now - lastVideoMs < 1000 / VIDEO_MAX_FPS) return;
        lastVideoMs = now;
        try {
            JSONObject blob = new JSONObject()
                    .put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP))
                    .put("mimeType", "image/jpeg");
            w.send(new JSONObject().put("realtimeInput", new JSONObject().put("video", blob)).toString());
            videoFrames++;
        } catch (Exception e) {
            Log.w(TAG, "video send failed: " + e);
        }
    }

    /** Typed turn / caption text into the live session. */
    public void sendText(String text) {
        WebSocket w = ws;
        if (w == null || !setUp || text == null || text.trim().isEmpty()) return;
        try {
            w.send(new JSONObject()
                    .put("realtimeInput", new JSONObject().put("text", text.trim())).toString());
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
                    if (!t.isEmpty()) main.post(() -> listener.onText(t, false));
                }
            }
            if (sc.optBoolean("turnComplete", false)) {
                main.post(() -> listener.onLevel(true, 0f));
            }
            JSONObject it = sc.optJSONObject("inputTranscription");
            if (it != null && it.has("text")) {
                String t = it.optString("text", "");
                if (!t.isEmpty()) main.post(() -> listener.onText(t, true));
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
