package tech.nullai.fieldkit;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

/**
 * Stereo sound-direction estimate (v1, honest limits).
 *
 * Uses the phone's stereo microphones (48 kHz PCM): inter-channel time
 * difference (cross-correlation, +/-24 samples ~ 17 cm path) + level
 * difference, smoothed. Result is a RELATIVE bearing in degrees (-90..90,
 * 0 = device forward). This is an estimate that varies by device mic layout;
 * it is used for voice-focus gating and display, never as proof of location.
 * Falls back to mono (relative = 0, stereo = false) when stereo is absent.
 */
public final class SoundField {

    public interface Listener {
        void onEstimate(int relativeDeg, float levelDb, boolean stereo);
    }

    private static final int RATE = 48000;
    private static final int MAX_LAG = 24;      // samples ~ max inter-mic delay
    private static final double MIN_RMS = 0.003; // ~ -50 dBFS noise gate

    private final Listener listener;
    private volatile boolean running;
    private Thread thread;
    private AudioRecord rec;
    private double smoothDeg = 0;

    public SoundField(Listener listener) {
        this.listener = listener;
    }

    public synchronized void start(Context ctx) {
        if (running) return;
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) return;
        running = true;
        thread = new Thread(this::loop, "sound-field");
        thread.start();
    }

    public synchronized void stop() {
        running = false;
        AudioRecord r = rec;
        if (r != null) {
            try { r.stop(); } catch (Exception ignored) { }
        }
        if (thread != null) thread.interrupt();
    }

    private AudioRecord open(int source, int channelMask) {
        try {
            int min = AudioRecord.getMinBufferSize(RATE, channelMask, AudioFormat.ENCODING_PCM_16BIT);
            if (min <= 0) return null;
            AudioRecord r = new AudioRecord.Builder()
                    .setAudioSource(source)
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(RATE)
                            .setChannelMask(channelMask)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .build())
                    .setBufferSizeInBytes(Math.max(min, 8192))
                    .build();
            return r.getState() == AudioRecord.STATE_INITIALIZED ? r : null;
        } catch (Exception e) {
            return null;
        }
    }

    private void loop() {
        rec = open(MediaRecorder.AudioSource.UNPROCESSED, AudioFormat.CHANNEL_IN_STEREO);
        boolean stereo = rec != null;
        if (rec == null) rec = open(MediaRecorder.AudioSource.VOICE_RECOGNITION, AudioFormat.CHANNEL_IN_STEREO);
        stereo = rec != null;
        if (rec == null) {
            rec = open(MediaRecorder.AudioSource.MIC, AudioFormat.CHANNEL_IN_MONO);
            stereo = false;
        }
        if (rec == null) { running = false; return; }
        try {
            rec.startRecording();
            short[] buf = new short[4096 * (stereo ? 2 : 1)];
            while (running) {
                int n = rec.read(buf, 0, buf.length);
                if (n <= 0) continue;
                analyze(buf, n, stereo);
            }
        } catch (Exception ignored) {
        } finally {
            try { rec.release(); } catch (Exception ignored) { }
            rec = null;
        }
    }

    private void analyze(short[] buf, int n, boolean stereo) {
        if (!stereo) {
            double sum = 0;
            for (int i = 0; i < n; i++) { double v = buf[i] / 32768.0; sum += v * v; }
            double rms = Math.sqrt(sum / Math.max(1, n));
            if (rms >= MIN_RMS) {
                listener.onEstimate(0, db(rms), false);
            }
            return;
        }
        int frames = n / 2;
        double[] left = new double[frames], right = new double[frames];
        double sum = 0;
        for (int i = 0; i < frames; i++) {
            left[i] = buf[2 * i] / 32768.0;
            right[i] = buf[2 * i + 1] / 32768.0;
            sum += left[i] * left[i] + right[i] * right[i];
        }
        double rms = Math.sqrt(sum / Math.max(1, 2 * frames));
        if (rms < MIN_RMS) return;
        int bestLag = 0;
        double best = -2;
        for (int lag = -MAX_LAG; lag <= MAX_LAG; lag++) {
            double corr = 0, el = 0, er = 0;
            for (int i = MAX_LAG; i < frames - MAX_LAG; i++) {
                double a = left[i], b = right[i + lag];
                corr += a * b; el += a * a; er += b * b;
            }
            double norm = corr / Math.sqrt(Math.max(1e-12, el * er));
            if (norm > best) { best = norm; bestLag = lag; }
        }
        double ratio = Math.max(-1, Math.min(1, bestLag / (double) MAX_LAG));
        double deg = Math.toDegrees(Math.asin(ratio));
        smoothDeg = 0.65 * smoothDeg + 0.35 * deg;
        listener.onEstimate((int) Math.round(smoothDeg), db(rms), true);
    }

    private static float db(double rms) {
        return (float) Math.max(-90, Math.min(0, 20 * Math.log10(Math.max(1e-6, rms))));
    }
}
