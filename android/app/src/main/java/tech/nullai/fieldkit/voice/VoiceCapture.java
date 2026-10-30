package tech.nullai.fieldkit.voice;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;

/**
 * One-shot utterance capture for the voice gate.
 *
 * Records 16 kHz mono PCM16 from the mic until the speaker stops (energy-based
 * auto-stop with a hard ceiling), then hands back three things that all describe
 * the SAME audio: the samples, a {@link VoicePrint} of them, and a WAV file on
 * disk. The WAV exists so the platform recognizer can be handed exactly the
 * utterance that was fingerprinted (RecognizerIntent EXTRA_AUDIO_SOURCE),
 * instead of opening the mic a second time and describing some other audio.
 *
 * Honest limits:
 *  - Mono 16 kHz is what VoicePrint.fromPcm and the recognizer's file path take.
 *    Real-world mic gain varies by device, and VoicePrint keeps spectral SHAPE
 *    (per-frame band dB minus the frame mean), so level differences do not matter.
 *  - The print is a CONVENIENCE gate for the wearer's own voice. It is never
 *    authentication, and it never identifies anybody else: a non-matching
 *    speaker is one of "other" (a voice we have heard before) or "unknown".
 *  - Silence and background noise produce a short capture with a low
 *    {@link Result#quality}; the caller should treat that as "heard nothing"
 *    rather than as a speaker verdict.
 */
public final class VoiceCapture {

    public static final int RATE = 16000;
    private static final int BLOCK = UtteranceSegmenter.BLOCK_MS * (RATE / 1000);   // 100 ms
    private static final int MIN_MS = UtteranceSegmenter.MIN_MS;

    private VoiceCapture() {
    }

    public interface Done {
        void onCaptured(Result r);
    }

    /** Everything the caller needs, all describing one utterance. */
    public static final class Result {
        public final short[] pcm;
        public final float[] print;
        public final double quality;
        public final int ms;
        public final double peakDb;
        public final File wav;
        public final String error;

        Result(short[] pcm, float[] print, double quality, int ms, double peakDb, File wav, String error) {
            this.pcm = pcm;
            this.print = print;
            this.quality = quality;
            this.ms = ms;
            this.peakDb = peakDb;
            this.wav = wav;
            this.error = error;
        }

        /** True when there was enough voiced audio to judge. */
        public boolean spoken() {
            return error == null && ms >= MIN_MS && print != null;
        }

        public String describe() {
            if (error != null) return error;
            return ms + " ms, peak " + String.format(java.util.Locale.US, "%.1f", peakDb)
                    + " dB, quality " + String.format(java.util.Locale.US, "%.2f", quality);
        }
    }

    /** True when this device will let us open the mic right now. */
    public static boolean available(Context ctx) {
        return ctx != null && ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** Capture on a background thread; {@code done} is called there, not on the UI thread. */
    public static void capture(Context ctx, Done done) {
        Thread t = new Thread(() -> {
            Result r;
            try {
                r = record(ctx);
            } catch (Throwable e) {
                r = new Result(null, null, 0, 0, -90, null, "capture failed: " + e.getClass().getSimpleName());
            }
            try {
                done.onCaptured(r);
            } catch (Throwable ignored) {
            }
        }, "voice-capture");
        t.setDaemon(true);
        t.start();
    }

    private static Result record(Context ctx) {
        if (!available(ctx)) return new Result(null, null, 0, 0, -90, null, "microphone permission not granted");
        AudioRecord rec = open();
        if (rec == null) return new Result(null, null, 0, 0, -90, null, "microphone is busy or unavailable");

        // The segmentation gate lives in UtteranceSegmenter so this path and the
        // locked-screen listener agree on where speech starts and stops.
        UtteranceSegmenter seg = new UtteranceSegmenter();
        try {
            rec.startRecording();
            short[] buf = new short[BLOCK];
            while (!seg.done()) {
                int n = rec.read(buf, 0, buf.length);
                if (n <= 0) break;
                seg.feed(buf, n);
            }
        } catch (Throwable ignored) {
        } finally {
            try { rec.stop(); } catch (Throwable ignored) { }
            try { rec.release(); } catch (Throwable ignored) { }
        }

        if (!seg.spoken()) {
            return new Result(null, null, 0, Math.max(0, seg.voicedMs()), seg.peakDb(), null,
                    "nothing above the noise floor");
        }
        short[] pcm = seg.pcm();
        float[] print = VoicePrint.fromPcm(pcm, RATE);
        double q = VoicePrint.quality(print);
        File wav = writeWav(ctx, pcm, RATE, "utterance.wav");
        return new Result(pcm, print, q, seg.voicedMs(), seg.peakDb(), wav, null);
    }

    /** Open a 16 kHz mono PCM16 record, or null when the mic is busy. Shared with {@link LockedListener}. */
    static AudioRecord open() {
        int[] sources = new int[]{
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.MIC,
                MediaRecorder.AudioSource.UNPROCESSED,
        };
        for (int src : sources) {
            try {
                int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT);
                if (min <= 0) min = 4096;
                AudioRecord r = new AudioRecord.Builder()
                        .setAudioSource(src)
                        .setAudioFormat(new AudioFormat.Builder()
                                .setSampleRate(RATE)
                                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .build())
                        .setBufferSizeInBytes(Math.max(min, 8192))
                        .build();
                if (r.getState() == AudioRecord.STATE_INITIALIZED) return r;
                try { r.release(); } catch (Throwable ignored) { }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** 44-byte RIFF header + PCM16 LE, so a recognizer can be pointed at the file. */
    static File writeWav(Context ctx, short[] pcm, int rate, String name) {
        File out = new File(ctx.getCacheDir(), name);
        try (FileOutputStream fos = new FileOutputStream(out)) {
            int dataLen = pcm.length * 2;
            java.nio.ByteBuffer h = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            h.put("RIFF".getBytes("US-ASCII"));
            h.putInt(36 + dataLen);
            h.put("WAVE".getBytes("US-ASCII"));
            h.put("fmt ".getBytes("US-ASCII"));
            h.putInt(16);
            h.putShort((short) 1);                    // PCM
            h.putShort((short) 1);                    // mono
            h.putInt(rate);
            h.putInt(rate * 2);                       // byte rate
            h.putShort((short) 2);                    // block align
            h.putShort((short) 16);                   // bits
            h.put("data".getBytes("US-ASCII"));
            h.putInt(dataLen);
            fos.write(h.array());
            java.nio.ByteBuffer body = java.nio.ByteBuffer.allocate(dataLen).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            for (short s : pcm) body.putShort(s);
            fos.write(body.array());
            fos.flush();
            return out;
        } catch (Throwable e) {
            return null;
        }
    }

    /** Length of a WAV written by {@link #writeWav}, in ms - handy for logging. */
    static int wavMs(File f) {
        if (f == null || !f.isFile() || f.length() <= 44) return 0;
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            return (int) ((raf.length() - 44) / 2 * 1000 / RATE);
        } catch (Throwable e) {
            return 0;
        }
    }
}
