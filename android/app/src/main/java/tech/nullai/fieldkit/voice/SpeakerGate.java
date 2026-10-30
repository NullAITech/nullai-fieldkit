package tech.nullai.fieldkit.voice;

import java.util.List;

/**
 * Decides whether a spoken wake phrase came from the enrolled owner.
 *
 * Two deliberate design choices:
 *  - The owner print adapts slowly (EMA) so the gate follows a voice through a cold, a
 *    different room or a different microphone, instead of drifting away from the user.
 *  - A "visitor" print is kept for the runner-up speaker, so a household member who
 *    repeatedly says the wake phrase gets noticed ("that was not you") rather than
 *    silently locking the user out.
 *
 * Never treat ACCEPT as proof of identity - it gates convenience, not access.
 */
public final class SpeakerGate {

    public static final double DEFAULT_THRESHOLD = 0.88;
    /** Floor for an auto-calibrated threshold - never accept on a weak match, whatever enrolment said. */
    public static final double CALIBRATION_FLOOR = 0.80;

    private float[] owner;
    private float[] visitor;
    private int accepts;
    private int rejects;
    private double threshold;

    public SpeakerGate() {
        this(DEFAULT_THRESHOLD);
    }

    public SpeakerGate(double threshold) {
        this.threshold = threshold;
    }

    /**
     * Enrol from several takes and let the takes set the threshold: a microphone/room where the
     * owner's own samples agree only at 0.91 must not be held to a hardcoded 0.95, and a clean
     * setup that agrees at 0.99 deserves tighter gating. Floored so calibration can never go slack.
     */
    public synchronized void enroll(List<float[]> takes) {
        if (takes == null || takes.isEmpty()) return;
        float[] avg = VoicePrint.average(takes);
        if (avg == null) return;
        double worst = 1.0;
        for (float[] t : takes) worst = Math.min(worst, VoicePrint.similarity(avg, t));
        if (takes.size() >= 2) {
            double calibrated = Math.max(CALIBRATION_FLOOR, worst - 0.06);
            threshold = Math.min(DEFAULT_THRESHOLD, calibrated);
        }
        owner = avg;
        accepts = 0;
        rejects = 0;
    }

    public synchronized void enroll(float[] print) {
        if (print == null) return;
        if (owner == null) owner = print.clone();
        else for (int i = 0; i < owner.length; i++) owner[i] = (float) (0.7 * owner[i] + 0.3 * print[i]);
        accepts = 0;
    }

    public synchronized boolean isEnrolled() {
        return owner != null;
    }

    public synchronized float[] ownerPrint() {
        return owner == null ? null : owner.clone();
    }

    public synchronized double score(float[] print) {
        return VoicePrint.similarity(owner, print);
    }

    public static final class Verdict {
        public final boolean accept;
        public final double score;
        public final boolean knownVisitor;
        public final String reason;

        Verdict(boolean accept, double score, boolean knownVisitor, String reason) {
            this.accept = accept;
            this.score = score;
            this.knownVisitor = knownVisitor;
            this.reason = reason;
        }

        @Override
        public String toString() {
            return (accept ? "OWNER" : knownVisitor ? "VISITOR" : "UNKNOWN")
                    + " score=" + String.format(java.util.Locale.US, "%.3f", score) + " (" + reason + ")";
        }
    }

    /** @param print the print of the utterance that carried the wake phrase. */
    public synchronized Verdict verify(float[] print) {
        if (print == null) return new Verdict(false, -1, false, "no audio");
        if (owner == null) return new Verdict(true, -1, false, "not enrolled - first speaker becomes owner");
        double s = VoicePrint.similarity(owner, print);
        if (s >= threshold) {
            accepts++;
            if (accepts >= 3) enroll(print);            // slow adaptation, owner only
            return new Verdict(true, s, false, "owner");
        }
        double vs = VoicePrint.similarity(visitor, print);
        if (visitor != null && vs >= threshold) {
            rejects++;
            return new Verdict(false, s, true, "recurring other speaker");
        }
        if (visitor == null || s > vs) visitor = print.clone();
        rejects++;
        return new Verdict(false, s, false, "below threshold");
    }

    public synchronized String describe() {
        return "owner=" + (owner != null ? "yes" : "no")
                + " accepts=" + accepts + " rejects=" + rejects
                + " threshold=" + String.format(java.util.Locale.US, "%.2f", threshold);
    }
}
