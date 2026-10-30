package tech.nullai.fieldkit.voice;

import java.util.ArrayList;
import java.util.List;

/**
 * The enrolled voice print as a string, and back again.
 *
 * The print has to outlive the screen: {@code MainActivity} enrols it and
 * {@link LockedListener} needs the very same gate while the phone is in a pocket, in a
 * different process object. This is the one codec both sides use - two copies of this
 * format would drift and the gate would quietly stop matching.
 *
 * The value is a CONVENIENCE gate, never authentication: it decides whether a wake
 * phrase came from the person wearing the device, and a miss only means a turn is
 * treated as someone else's.
 *
 * Format (unchanged from the first version, so existing enrolments keep working):
 * takes separated by '|', cells by ',', each take one float per mel-band.
 */
public final class OwnerPrintStore {

    private OwnerPrintStore() {
    }

    /** Encode the takes. Null takes become empty fields, which {@link #decode} skips. */
    public static String encode(float[][] takes) {
        if (takes == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < takes.length; i++) {
            if (i > 0) sb.append('|');
            float[] t = takes[i];
            if (t == null) continue;
            for (int j = 0; j < t.length; j++) {
                if (j > 0) sb.append(',');
                sb.append(t[j]);
            }
        }
        return sb.toString();
    }

    /**
     * Decode takes, keeping only the well-formed ones.
     *
     * A corrupt or half-written preference must not throw inside enrolment and must not
     * enrol a NaN: one NaN cell makes every similarity a NaN, and a NaN comparison is
     * false, which would silently reject the wearer forever. Unusable cells are dropped
     * and a row of the wrong width is discarded rather than padded - {@link VoicePrint}
     * compares band by band, so a short row would mismatch every band.
     */
    public static List<float[]> decode(String raw) {
        List<float[]> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String part : raw.split("\\|")) {
            if (part.isEmpty()) continue;
            String[] cells = part.split(",");
            float[] f = new float[cells.length];
            boolean bad = false;
            for (int i = 0; i < cells.length; i++) {
                float v;
                try {
                    v = Float.parseFloat(cells[i].trim());
                } catch (NumberFormatException e) {
                    bad = true;
                    break;
                }
                if (!isUsable(v)) {
                    bad = true;
                    break;
                }
                f[i] = v;
            }
            if (bad || f.length == 0) continue;
            if (!out.isEmpty() && f.length != out.get(0).length) continue;
            out.add(f);
        }
        return out;
    }

    /**
     * Rebuild the gate from a stored value. An empty, corrupt or absent value yields a
     * gate that is simply not enrolled - the honest state, not a broken one.
     */
    public static SpeakerGate gate(String raw) {
        SpeakerGate g = new SpeakerGate();
        List<float[]> takes = decode(raw);
        if (!takes.isEmpty()) g.enroll(takes);
        return g;
    }

    /** A cell is usable when it is a real number: not NaN, not infinite. */
    private static boolean isUsable(float v) {
        return !Float.isNaN(v) && !Float.isInfinite(v);
    }
}
