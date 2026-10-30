package tech.nullai.fieldkit.voice;

import java.util.ArrayDeque;

/**
 * Turns a stream of 100 ms PCM16 blocks into ONE utterance.
 *
 * This is the part of listening that does not need Android, so it lives on its own:
 * both the one-shot capture ({@link VoiceCapture}, screen on) and the locked-screen
 * listener ({@link LockedListener}, screen off) feed blocks in here, which is what
 * stops the two paths from disagreeing about when speech started or ended.
 *
 * Honest limits:
 *  - It is an ENERGY gate, not speech recognition. A door slam, a car horn or a
 *    loud TV all pass the onset test. Deciding "this is the wearer" is the print's
 *    job ({@link VoicePrint}); deciding "these are words" needs the recognizer.
 *  - The thresholds are fixed, not adapted to the room. On a loud street the gate
 *    will segment constantly; that is a false-positive problem, and false positives
 *    here cost a buffered file, never a command.
 *  - One utterance per instance: the caller resets or allocates a new one.
 */
public final class UtteranceSegmenter {

    /** Feed blocks of this length; everything downstream assumes it. */
    public static final int BLOCK_MS = 100;

    /** Speech onset threshold. Same value the one-shot capture has always used. */
    public static final double START_DB = -42.0;

    /** Level that counts as trailing silence. */
    public static final double STOP_DB = -48.0;

    /** Shorter than this is not an utterance. */
    public static final int MIN_MS = 400;

    /** Pause that ends the utterance. */
    public static final int STOP_SILENCE_MS = 700;

    /** Hard ceiling on listening for one utterance. */
    public static final int MAX_MS = 12000;

    /** Audio kept from just before onset, so the first syllable is not clipped. */
    public static final int LEAD_MS = 300;

    /** What the last {@link #feed} did. */
    public enum Status {
        /** Before onset: heard nothing above the floor yet. */
        IDLE,
        /** Onset passed, still collecting. */
        VOICED,
        /** Finished, and there is enough audio to judge. */
        DONE,
        /** Finished, and there is not enough audio to judge. */
        TOO_SHORT
    }

    private final double startDb;
    private final double stopDb;
    private final int minMs;
    private final int stopSilenceMs;
    private final int maxMs;
    private final int leadBlocks;

    private final ArrayDeque<short[]> preRoll = new ArrayDeque<>();
    private final java.util.ArrayList<short[]> blocks = new java.util.ArrayList<>();

    private int streamMs;      // every block fed, onsets ignored
    private int segmentMs;     // blocks since onset, trailing silence included
    private int voicedMs;      // blocks at or above STOP_DB; the only thing that counts as speech
    private int silenceMs;     // consecutive blocks under STOP_DB since onset
    private double peakDb = -90;
    private boolean started;
    private boolean done;
    private boolean truncated;      // stopped because maxMs was reached
    private String reason = "";

    public UtteranceSegmenter() {
        this(START_DB, STOP_DB, MIN_MS, STOP_SILENCE_MS, MAX_MS, LEAD_MS);
    }

    /** Same gate with different numbers - used by the tests and by odd acoustic settings. */
    public UtteranceSegmenter(double startDb, double stopDb, int minMs, int stopSilenceMs,
                              int maxMs, int leadMs) {
        this.startDb = startDb;
        this.stopDb = stopDb;
        this.minMs = Math.max(BLOCK_MS, minMs);
        this.stopSilenceMs = Math.max(0, stopSilenceMs);
        this.maxMs = Math.max(this.minMs, maxMs);
        this.leadBlocks = Math.max(0, leadMs / BLOCK_MS);
    }

    /** True once onset has passed. */
    public boolean started() {
        return started;
    }

    /** True once this utterance is finished (either way). */
    public boolean done() {
        return done;
    }

    /**
     * True when {@link #done()} and there is enough audio to judge. A segment cut off
     * at {@link #MAX_MS} still counts: the first twelve seconds of a monologue are
     * worth keeping, they are just not the whole thing.
     */
    public boolean spoken() {
        return done && voicedMs >= minMs && pcm() != null;
    }

    /** Why it stopped: trailing silence, max length, or nothing above the floor. */
    public String reason() {
        return reason;
    }

    public int streamMs() {
        return streamMs;
    }

    /**
     * Milliseconds of ACTUAL voicing: blocks at or above {@link #STOP_DB}, trailing silence
     * excluded. This is what {@link #MIN_MS} is measured against - counting the pause as
     * speech would let a 200 ms bang followed by silence be filed as an utterance.
     */
    public int voicedMs() {
        return voicedMs;
    }

    /** Milliseconds since onset, trailing silence included. */
    public int segmentMs() {
        return segmentMs;
    }

    public int silenceMs() {
        return silenceMs;
    }

    public double peakDb() {
        return peakDb;
    }

    /**
     * The utterance: the pre-roll followed by every block from onset to the stop.
     * Null until the segment is finished.
     */
    public short[] pcm() {
        if (!done || blocks.isEmpty()) return null;
        int len = 0;
        for (short[] b : blocks) len += b.length;
        if (len == 0) return null;
        short[] out = new short[len];
        int at = 0;
        for (short[] b : blocks) {
            System.arraycopy(b, 0, out, at, b.length);
            at += b.length;
        }
        return out;
    }

    public void reset() {
        preRoll.clear();
        blocks.clear();
        streamMs = segmentMs = voicedMs = silenceMs = 0;
        peakDb = -90;
        started = done = truncated = false;
        reason = "";
    }

    /**
     * Push one block. Returns the new status; callers stop feeding once it is
     * {@link Status#DONE} or {@link Status#TOO_SHORT}.
     */
    public Status feed(short[] block, int n) {
        if (block == null) return status();
        int take = Math.min(Math.max(0, n), block.length);
        if (take == 0) return status();
        if (done) return status();

        streamMs += BLOCK_MS;
        double db = dbOf(block, take);
        if (db > peakDb) peakDb = db;

        if (!started) {
            if (db > startDb) {
                started = true;
                // The blocks just before onset carry the attack of the first syllable.
                for (short[] p : preRoll) blocks.add(p);
                blocks.add(java.util.Arrays.copyOf(block, take));
                segmentMs += BLOCK_MS;
                if (db >= stopDb) voicedMs += BLOCK_MS;
                else silenceMs = BLOCK_MS;
            } else {
                preRoll.addLast(java.util.Arrays.copyOf(block, take));
                while (preRoll.size() > leadBlocks) preRoll.removeFirst();
                if (streamMs >= maxMs) {
                    done = true;
                    truncated = true;
                    reason = "nothing above the noise floor";
                }
                return status();
            }
        } else {
            blocks.add(java.util.Arrays.copyOf(block, take));
            segmentMs += BLOCK_MS;
            if (db >= stopDb) {
                voicedMs += BLOCK_MS;
                silenceMs = 0;
            } else {
                silenceMs += BLOCK_MS;
            }
        }

        if (silenceMs >= stopSilenceMs) {
            // Enough of a pause to call it: either a finished utterance or a bang that
            // never became speech. Either way the next utterance must not be swallowed.
            done = true;
            reason = voicedMs >= minMs ? "trailing silence" : "nothing above the noise floor";
        } else if (streamMs >= maxMs) {
            done = true;
            truncated = true;
            reason = "max length";
        }
        return status();
    }

    private Status status() {
        if (!done) return started ? Status.VOICED : Status.IDLE;
        if (!spoken()) {
            if (reason == null || reason.isEmpty()) reason = "nothing above the noise floor";
            return Status.TOO_SHORT;
        }
        return Status.DONE;
    }

    /** RMS of a block in dBFS, floored at -90. */
    public static double dbOf(short[] buf, int n) {
        double sum = 0;
        int take = Math.min(Math.max(0, n), buf == null ? 0 : buf.length);
        for (int i = 0; i < take; i++) {
            double v = buf[i] / 32768.0;
            sum += v * v;
        }
        double rms = Math.sqrt(sum / Math.max(1, take));
        return Math.max(-90, Math.min(0, 20 * Math.log10(Math.max(1e-6, rms))));
    }
}
