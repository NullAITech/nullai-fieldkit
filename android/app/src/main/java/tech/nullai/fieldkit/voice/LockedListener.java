package tech.nullai.fieldkit.voice;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioRecord;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.util.ArrayDeque;

/**
 * Listens while the screen is off and the app is in the background.
 *
 * {@link VoiceCapture} is ONE-SHOT and needs an Activity in front of it: it is only ever
 * opened from the button or a resumed screen. {@link SpotterService} keeps the socket,
 * the camera and the wake lock alive with the screen off, but until now nothing was
 * reading the microphone, so "it hears you while it is in your pocket" was not true.
 * This is that missing read loop.
 *
 * What it does with an utterance, and what it deliberately does not:
 *  - SEGMENTS it ({@link UtteranceSegmenter}) and checks the wearer's print
 *    ({@link SpeakerGate}). Both are on-device, cheap, and run in the service's process.
 *  - It does NOT transcribe. Speech recognition on Android needs an Activity and does not
 *    run with the screen off, so the listener keeps the utterance as a WAV in the app cache
 *    and hands the path to the callback; the guide turn happens when the app is next up.
 *    Pretending otherwise would mean shipping a claim we cannot honour.
 *  - It does NOT keep other people's voices. An utterance that fails the print gate is
 *    deleted immediately: the buffer holds one file, and the file on disk is the wearer's.
 *
 * Privacy contract, unchanged from the rest of the voice stack: the gate is a CONVENIENCE
 * gate, never authentication, and this class never identifies anyone by face.
 */
public final class LockedListener {

    /** One utterance, as handed to the callback. */
    public static final class Result {
        /** 16 kHz mono PCM16, pre-roll included. */
        public final short[] pcm;
        public final float[] print;
        public final double quality;
        /** Print similarity to the enrolled wearer, or -1 when not enrolled. */
        public final double score;
        public final boolean wearer;
        public final int ms;
        public final File wav;
        public final String error;

        Result(short[] pcm, float[] print, double quality, double score, boolean wearer,
               int ms, File wav, String error) {
            this.pcm = pcm;
            this.print = print;
            this.quality = quality;
            this.score = score;
            this.wearer = wearer;
            this.ms = ms;
            this.wav = wav;
            this.error = error;
        }

        public String describe() {
            if (error != null) return error;
            return (wearer ? "wearer" : "not the wearer") + ", " + ms + " ms, score "
                    + String.format(java.util.Locale.US, "%.3f", score);
        }
    }

    /** Called on the listener thread, once per gated utterance. Keep it short. */
    public interface Heard {
        void onUtterance(Result r);
    }

    /**
     * logcat tag for the screen-off path. This loop is the one part of the app that cannot
     * be exercised without a phone, so it says out loud what it is doing: whether the mic
     * opened, what each utterance scored, and how many were refused.
     */
    public static final String TAG = "SpotterVoice";

    /** Utterances kept while nobody is looking. One file only, and the newest wins. */
    private static final int MAX_QUEUED = 3;

    private final Context ctx;
    private final SpeakerGate gate;
    private final Heard heard;
    private final File cacheDir;

    private Thread thread;
    private volatile boolean running;
    private volatile int dropped;
    private final ArrayDeque<File> pending = new ArrayDeque<>();

    private LockedListener(Context ctx, SpeakerGate gate, Heard heard) {
        this.ctx = ctx.getApplicationContext();
        this.gate = gate;
        this.heard = heard;
        this.cacheDir = this.ctx.getCacheDir();
    }

    /** True when this device will let us read the mic right now. */
    public static boolean available(Context ctx) {
        return ctx != null && ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Start listening. Returns null when the mic is unavailable or permission is missing -
     * a null is a normal outcome on a phone that has not been granted the mic, so the
     * caller must fall back to the one-shot path rather than treat it as a crash.
     */
    public static LockedListener start(Context ctx, SpeakerGate gate, Heard heard) {
        if (!available(ctx) || heard == null) return null;
        LockedListener l = new LockedListener(ctx, gate, heard);
        if (!l.spawn()) return null;
        return l;
    }

    private boolean spawn() {
        AudioRecord rec = VoiceCapture.open();
        if (rec == null) {
            Log.w(TAG, "screen-off listening NOT started: the microphone is held by someone else");
            return false;
        }
        running = true;
        thread = new Thread(() -> loop(rec), "spotter-locked-listener");
        thread.setDaemon(true);
        thread.start();
        Log.i(TAG, "screen-off listening started (16 kHz mono PCM16, gate "
                + (gate == null ? "absent - every utterance is kept" : "enrolled"));
        return true;
    }

    /** Stop listening and release the mic. Safe to call twice. */
    public void stop() {
        boolean was = running;
        running = false;
        Thread t = thread;
        if (t != null) {
            try { t.join(1500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
        thread = null;
        if (was) Log.i(TAG, "screen-off listening stopped (" + dropped + " discarded as not-you)");
    }

    public boolean isRunning() {
        return running;
    }

    /** Utterances the gate rejected and that were deleted without reaching the callback. */
    public int rejected() {
        return dropped;
    }

    /** Paths handed to the callback that are still on disk, newest last. */
    public java.util.List<File> pendingFiles() {
        synchronized (pending) {
            return new java.util.ArrayList<>(pending);
        }
    }

    /** Call once the app has consumed a pending file, so the cache does not grow. */
    public void forget(File f) {
        synchronized (pending) {
            pending.remove(f);
        }
        if (f != null && f.exists() && !f.delete()) f.deleteOnExit();
    }

    private void loop(AudioRecord rec) {
        final int block = UtteranceSegmenter.BLOCK_MS * (VoiceCapture.RATE / 1000);
        short[] buf = new short[block];
        UtteranceSegmenter seg = new UtteranceSegmenter();
        try {
            rec.startRecording();
            while (running) {
                int n = rec.read(buf, 0, buf.length);
                if (n <= 0) {
                    // -3 (ERROR_INVALID_OPERATION) also shows up when another AudioRecord
                    // grabbed the mic (the one-shot capture, a call). Back off, do not spin.
                    if (n == AudioRecord.ERROR_INVALID_OPERATION || n == AudioRecord.ERROR_BAD_VALUE) {
                        SystemClock.sleep(250);
                    }
                    continue;
                }
                seg.feed(buf, n);
                if (seg.done()) {
                    if (seg.spoken()) deliver(seg);
                    seg.reset();
                }
                if (!running) break;
            }
        } catch (Throwable ignored) {
        } finally {
            try { rec.stop(); } catch (Throwable ignored) { }
            try { rec.release(); } catch (Throwable ignored) { }
        }
    }

    private void deliver(UtteranceSegmenter seg) {
        short[] pcm = seg.pcm();
        if (pcm == null) return;
        float[] print = VoicePrint.fromPcm(pcm, VoiceCapture.RATE);
        double quality = VoicePrint.quality(print);

        double score = -1;
        boolean wearer = true;
        if (gate != null) {
            score = gate.score(print);
            wearer = gate.verify(print).accept;
        }

        if (!wearer) {
            // Not the wearer: keep nothing. A bystander's voice is not ours to store.
            dropped++;
            Log.i(TAG, "discarded a voice that failed the print gate: " + seg.voicedMs()
                    + " ms, score " + String.format(java.util.Locale.US, "%.3f", score));
            try {
                heard.onUtterance(new Result(null, print, quality, score, false, seg.voicedMs(),
                        null, "not the wearer - discarded"));
            } catch (Throwable ignored) {
            }
            return;
        }

        File wav = VoiceCapture.writeWav(ctx, pcm, VoiceCapture.RATE,
                "locked-" + System.currentTimeMillis() + ".wav");
        synchronized (pending) {
            pending.addLast(wav);
            while (pending.size() > MAX_QUEUED) {
                File old = pending.removeFirst();
                if (old != null && old.exists() && !old.delete()) old.deleteOnExit();
            }
        }
        Log.i(TAG, "kept " + seg.voicedMs() + " ms of the wearer's speech, score "
                + String.format(java.util.Locale.US, "%.3f", score) + " -> "
                + (wav == null ? "NOT written to disk" : wav.getName()));
        try {
            heard.onUtterance(new Result(pcm, print, quality, score, true, seg.voicedMs(), wav,
                    seg.reason().equals("max length") ? "kept: hit the length ceiling" : null));
        } catch (Throwable ignored) {
        }
    }

    /** One line for the service's notification or a logcat line. */
    public String describe() {
        int n;
        synchronized (pending) {
            n = pending.size();
        }
        return "locked listener " + (running ? "on" : "off") + ", " + n + " waiting, "
                + dropped + " discarded" + (cacheDir == null ? "" : ", cache " + cacheDir.getAbsolutePath());
    }
}
