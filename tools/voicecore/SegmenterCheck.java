import tech.nullai.fieldkit.voice.UtteranceSegmenter;
import tech.nullai.fieldkit.voice.VoicePrint;

/**
 * JVM harness for {@link UtteranceSegmenter} - the onset/segment gate shared by the one-shot
 * capture and the locked-screen listener.
 *
 * Deliberately Android-free: the gate is the only part of listening that can be proven on a
 * desktop, and the two callers must not be able to disagree about where speech starts.
 *
 *   tools/voicecore/run.sh
 */
public class SegmenterCheck {

    static int pass = 0, fail = 0;

    static void ok(boolean cond, String what) {
        if (cond) {
            pass++;
            System.out.println("  PASS  " + what);
        } else {
            fail++;
            System.out.println("  FAIL  " + what);
        }
    }

    static final int SR = 16000;
    static final int BLK = 1600;            // 100 ms at 16 kHz

    public static void main(String[] args) {
        short[] quiet = new short[BLK];                                     // digital silence
        short[] speech = VoicePrint.synthesize(122.0, 900.0, 100, SR, 7L);
        short[] speech2 = VoicePrint.synthesize(210.0, 1800.0, 100, SR, 11L);

        System.out.println("levels: silence " + fmt(UtteranceSegmenter.dbOf(quiet, quiet.length))
                + " dB, synthetic speech " + fmt(UtteranceSegmenter.dbOf(speech, speech.length))
                + " dB, longer " + fmt(UtteranceSegmenter.dbOf(speech2, speech2.length)) + " dB");
        ok(UtteranceSegmenter.dbOf(quiet, BLK) <= -89.9, "digital silence sits on the -90 dB floor");
        ok(UtteranceSegmenter.dbOf(speech, speech.length) > UtteranceSegmenter.START_DB,
                "the synthetic syllable is above the onset threshold (otherwise nothing below is meaningful)");

        System.out.println("\n== a room with no speech is not an utterance ==");
        {
            UtteranceSegmenter s = new UtteranceSegmenter();
            for (int i = 0; i < 20; i++) s.feed(quiet, BLK);
            ok(!s.started(), "20 blocks of silence never trigger onset");
            ok(!s.done(), "nothing to finish: still listening");
            ok(s.pcm() == null, "no audio offered before a segment completes");
            ok(!s.spoken(), "spoken() is false while listening");
        }

        System.out.println("\n== one utterance: lead-in, onset, trailing silence ==");
        {
            UtteranceSegmenter s = new UtteranceSegmenter();
            for (int i = 0; i < 6; i++) s.feed(quiet, BLK);          // 600 ms of room tone
            ok(!s.started(), "still idle through the room tone");
            for (int i = 0; i < 10; i++) s.feed(speech, BLK);         // 1 s of speech
            ok(s.started() && !s.done(), "onset passes at the first voiced block");
            for (int i = 0; i < 3; i++) s.feed(quiet, BLK);           // 300 ms pause - not enough
            ok(!s.done(), "a 300 ms pause does not end the utterance");
            for (int i = 0; i < 5; i++) s.feed(quiet, BLK);           // pause now 800 ms
            ok(s.done(), "800 ms of trailing silence ends it");
            ok(s.spoken(), "the segment has enough audio to judge");
            ok("trailing silence".equals(s.reason()), "reason says why it stopped: " + s.reason());
            ok(s.voicedMs() == 1000, "voiced ms counts speech only, not the pause: " + s.voicedMs());
            ok(s.segmentMs() == 1700, "segment ms runs from onset through the pause: " + s.segmentMs());
            short[] pcm = s.pcm();
            ok(pcm != null && pcm.length % BLK == 0, "pcm is whole blocks: " + (pcm == null ? -1 : pcm.length));
            int blocks = pcm == null ? 0 : pcm.length / BLK;
            int want = s.segmentMs() / UtteranceSegmenter.BLOCK_MS
                    + UtteranceSegmenter.LEAD_MS / UtteranceSegmenter.BLOCK_MS;
            ok(blocks == want, "pcm keeps every post-onset block plus the lead-in (" + blocks
                    + " = " + (s.segmentMs() / UtteranceSegmenter.BLOCK_MS) + " + "
                    + (UtteranceSegmenter.LEAD_MS / UtteranceSegmenter.BLOCK_MS)
                    + "), so the first syllable and the tail are both intact");
            ok(s.feed(speech, BLK) == UtteranceSegmenter.Status.DONE, "a finished segment stays finished");
            float[] print = VoicePrint.fromPcm(pcm, SR);
            ok(VoicePrint.quality(print) > 0.2, "the assembled utterance yields a usable print (quality "
                    + fmt(VoicePrint.quality(print)) + ")");
        }

        System.out.println("\n== a bang is not an utterance ==");
        {
            UtteranceSegmenter s = new UtteranceSegmenter();
            for (int i = 0; i < 2; i++) s.feed(quiet, BLK);
            for (int i = 0; i < 2; i++) s.feed(speech2, BLK);          // 200 ms, under MIN_MS
            for (int i = 0; i < 10; i++) s.feed(quiet, BLK);
            ok(s.done(), "the gate closes on the short burst");
            ok(!s.spoken(), "200 ms of noise is not enough to judge - discarded, not filed");
            ok(s.voicedMs() == 200, "only the 200 ms burst counted as voicing, not the silence after it: "
                    + s.voicedMs() + " ms");
            ok(s.feed(quiet, BLK) == UtteranceSegmenter.Status.TOO_SHORT, "status reports TOO_SHORT");
        }

        System.out.println("\n== a monologue stops at the ceiling ==");
        {
            UtteranceSegmenter s = new UtteranceSegmenter();
            int fed = 0;
            while (!s.done() && fed < 200) {
                s.feed(speech, BLK);
                fed++;
            }
            ok(s.done(), "continuous speech terminates the segment");
            ok("max length".equals(s.reason()), "at the length ceiling, not on silence: " + s.reason());
            ok(s.streamMs() == UtteranceSegmenter.MAX_MS, "ceiling is " + s.streamMs() + " ms");
            ok(s.spoken(), "the first " + s.voicedMs() + " ms are still kept rather than thrown away");
        }

        System.out.println("\n== the gate is reusable and tunable ==");
        {
            UtteranceSegmenter s = new UtteranceSegmenter();
            for (int i = 0; i < 2; i++) s.feed(quiet, BLK);
            for (int i = 0; i < 6; i++) s.feed(speech, BLK);
            for (int i = 0; i < 8; i++) s.feed(quiet, BLK);
            ok(s.done() && s.spoken(), "first utterance captured");
            s.reset();
            ok(!s.done() && !s.started() && s.pcm() == null && s.voicedMs() == 0,
                    "reset() returns it to a fresh listener");
            for (int i = 0; i < 2; i++) s.feed(quiet, BLK);
            for (int i = 0; i < 6; i++) s.feed(speech, BLK);
            for (int i = 0; i < 8; i++) s.feed(quiet, BLK);
            ok(s.done() && s.spoken(), "and it captures the next one");

            UtteranceSegmenter tight = new UtteranceSegmenter(-12.0, -36.0, 400, 700, 12000, 300);
            for (int i = 0; i < 2; i++) tight.feed(quiet, BLK);
            for (int i = 0; i < 6; i++) tight.feed(speech, BLK);
            for (int i = 0; i < 8; i++) tight.feed(quiet, BLK);
            ok(!tight.started(), "a stricter onset threshold never fires on this syllable ("
                    + fmt(UtteranceSegmenter.dbOf(speech, speech.length)) + " dB < -12 start)");
            ok(!tight.spoken(), "so nothing is captured: the threshold is a real knob");
        }

        System.out.println("\n== RESULT: " + pass + " passed, " + fail + " failed ==");
        if (fail > 0) System.exit(1);
    }

    static String fmt(double d) {
        return String.format(java.util.Locale.US, "%.1f", d);
    }
}
