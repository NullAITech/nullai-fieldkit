import tech.nullai.fieldkit.voice.OwnerPrintStore;
import tech.nullai.fieldkit.voice.SpeakerGate;
import tech.nullai.fieldkit.voice.VoicePrint;

import java.util.List;

/**
 * Checks the stored-print codec: the enrolled voice print is written by the activity and
 * read back by the screen-off listener, in a different process object, so the format is
 * the one thing that must not drift.
 *
 * Android-free on purpose - it runs in tools/voicecore/run.sh with no device and no SDK.
 */
public class OwnerPrintCheck {

    static int pass = 0;
    static int fail = 0;

    static void ok(boolean cond, String what) {
        if (cond) {
            pass++;
        } else {
            fail++;
            System.out.println("  FAIL " + what);
        }
    }

    /**
     * A REAL print, built the way the device builds one: synthesize a voiced sound, then
     * run the same front end over it. Hand-shaped floats would have the wrong width
     * ({@link VoicePrint#DIMS}) and the gate's own band-by-band comparison would be
     * meaningless, so the checks would pass without proving anything.
     */
    static float[] take(long seed) {
        short[] pcm = VoicePrint.synthesize(120 + (int) (seed % 40), 0.4, 900, 16000, seed);
        return VoicePrint.fromPcm(pcm, 16000);
    }

    public static void main(String[] args) {
        System.out.println("=== OwnerPrintCheck ===");
        float[][] takes = {take(1), take(3), take(7)};

        // Round trip: what the activity saves is what the listener loads.
        List<float[]> back = OwnerPrintStore.decode(OwnerPrintStore.encode(takes));
        ok(back.size() == 3, "three takes survive a round trip, got " + back.size());
        ok(back.size() == 3 && back.get(0).length == VoicePrint.DIMS, "band count survives");
        boolean same = back.size() == 3;
        for (int t = 0; same && t < 3; t++)
            for (int i = 0; i < VoicePrint.DIMS; i++)
                if (Math.abs(back.get(t)[i] - takes[t][i]) > 1e-6) same = false;
        ok(same, "every cell survives the round trip exactly");

        // The gate built from the stored value behaves like the gate built from the takes.
        SpeakerGate fromDisk = OwnerPrintStore.gate(OwnerPrintStore.encode(takes));
        SpeakerGate inMemory = new SpeakerGate();
        inMemory.enroll(java.util.Arrays.asList(takes[0], takes[1], takes[2]));
        double a = inMemory.verify(take(11)).score;
        double b = fromDisk.verify(take(11)).score;
        ok(Math.abs(a - b) < 1e-9, "gate from disk scores identically (" + a + " vs " + b + ")");
        ok(fromDisk.verify(takes[0]).accept, "the wearer's own take is accepted after a reload");

        // Empty and absent values are NOT enrolled - the honest state, not a broken one.
        ok(OwnerPrintStore.decode("").isEmpty(), "empty string decodes to no takes");
        ok(OwnerPrintStore.decode(null).isEmpty(), "null decodes to no takes");
        ok(!OwnerPrintStore.gate("").isEnrolled(), "gate from empty is not enrolled");
        ok(!OwnerPrintStore.gate(null).isEnrolled(), "gate from null is not enrolled");
        ok(OwnerPrintStore.encode(null).isEmpty(), "encoding null gives empty");

        // A NaN cell must not poison the print: one NaN makes every similarity NaN, and a
        // NaN comparison is false, so the wearer would be rejected forever.
        float[] nan = take(1);
        nan[5] = Float.NaN;
        List<float[]> clean = OwnerPrintStore.decode(OwnerPrintStore.encode(new float[][]{nan}));
        ok(clean.isEmpty(), "a take holding NaN is dropped, not enrolled");
        ok(!OwnerPrintStore.gate(OwnerPrintStore.encode(new float[][]{nan})).isEnrolled(),
                "gate from a NaN take is not enrolled");
        float[] inf = take(11);
        inf[2] = Float.POSITIVE_INFINITY;
        ok(OwnerPrintStore.decode(OwnerPrintStore.encode(new float[][]{inf})).isEmpty(),
                "a take holding infinity is dropped");

        // Garbage in the preference (a half-written value, a foreign string) must not throw.
        ok(OwnerPrintStore.decode("junk,,,").isEmpty(), "garbage decodes to no takes");
        ok(OwnerPrintStore.decode("1,2,3|4,5,6").size() == 2, "two clean takes decode");
        ok(OwnerPrintStore.decode("1,2,3|4,5").size() == 1,
                "a take of the wrong width is discarded, not padded");
        ok(!OwnerPrintStore.gate("nan,nan,nan").isEnrolled(), "a literal nan row is not enrolled");

        // Null takes in the middle must not shift the remaining ones.
        float[][] withHole = {takes[0], null, takes[1]};
        List<float[]> holed = OwnerPrintStore.decode(OwnerPrintStore.encode(withHole));
        ok(holed.size() == 2, "a null take is skipped, got " + holed.size());

        // A single take is a legitimate print (someone who enrolled once).
        ok(OwnerPrintStore.gate(OwnerPrintStore.encode(new float[][]{takes[0]})).isEnrolled(),
                "one take is enough to enrol");

        // The codec is the same shape VoicePrint speaks: no level, only shape.
        ok(VoicePrint.quality(takes[0]) > 0, "a synthesized take has non-zero quality");

        System.out.println("OwnerPrintCheck: " + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}
