import tech.nullai.fieldkit.people.PersonDescriptor;
import tech.nullai.fieldkit.voice.SpeakerGate;
import tech.nullai.fieldkit.voice.VoicePrint;
import tech.nullai.fieldkit.voice.WakePhrase;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * JVM harness for the on-device voice/person core. Runs without Android or a device, so the
 * matching logic is provable on the desktop before it is trusted on the phone.
 *
 *   javac -d out $(find .../voice .../people -name '*.java') VoiceCoreTest.java
 *   java -cp out VoiceCoreTest
 */
public class VoiceCoreTest {

    static int pass = 0, fail = 0;

    static void ok(boolean cond, String what) {
        if (cond) { pass++; System.out.println("  PASS  " + what); }
        else { fail++; System.out.println("  FAIL  " + what); }
    }

    public static void main(String[] args) {
        System.out.println("== WakePhrase: the commands must fire ==");
        expect(WakePhrase.Command.GUIDE_ON, "spotter guide mode activate");
        expect(WakePhrase.Command.GUIDE_ON, "Spotter, guide mode, activate!");
        expect(WakePhrase.Command.GUIDE_ON, "hey spotter guide mode activate please");
        expect(WakePhrase.Command.GUIDE_ON, "Zoth spotter guide mode activate");
        expect(WakePhrase.Command.GUIDE_OFF, "spotter guide mode stand down");
        expect(WakePhrase.Command.QUIET_ON, "spotter go quiet");
        expect(WakePhrase.Command.QUIET_OFF, "spotter resume listening");
        expect(WakePhrase.Command.SIGHT_CHECK, "spotter sight check");
        expect(WakePhrase.Command.NOTE, "spotter witness note");
        expect(WakePhrase.Command.PERSON_MARK, "spotter mark person");
        expect(WakePhrase.Command.PERSON_ENROLL, "spotter enroll person");
        expect(WakePhrase.Command.PERSON_WHO, "spotter who is this");
        expect(WakePhrase.Command.SOUL_SYNC, "spotter soul sync");

        System.out.println("\n== WakePhrase: ordinary conversation must NOT fire ==");
        reject("so the spotter said the guide was fine and I should activate it later");
        reject("the spotter's guide said to activate the mode");
        reject("I'll see you now");
        reject("we should guide the mode of the car later");
        reject("the spotter just told me to mark the person on the list");
        reject("if I say spotter guide mode activate what happens");
        reject("spotter guide mode would activate if I pressed the button");
        reject("he is a spotter for the weightlifting meet");
        reject("my spotter is not going to make it today");
        reject("I saw the spotter standing by the guide rails");
        reject("who is this guy and why is he here");
        reject("let me sync up the soul of this track later");
        reject("I'm heading inside now, so spotter guide mode activate would be a weird thing to say");
        reject("he asked me whether spotter guide mode activate was a real command");

        System.out.println("\n== WakePhrase: bare anchor is attention, not a command ==");
        WakePhrase.Result a = WakePhrase.parse("spotter");
        ok(a.anchored && !a.isCommand(), "bare \"spotter\" -> anchored, no command");
        WakePhrase.Result b = WakePhrase.parse("hey spotter are you there");
        ok(b.anchored && !b.isCommand(), "\"hey spotter are you there\" -> anchored, no command");
        WakePhrase.Result c = WakePhrase.parse("just talking to my friend");
        ok(!c.anchored && !c.isCommand(), "unrelated speech -> nothing at all");
        System.out.println("  vocabulary: " + WakePhrase.vocabularyForPrompt());

        System.out.println("\n== SpeakerGate: owner vs other voice ==");
        int sr = 16000;
        short[] ownerA = VoicePrint.synthesize(118, 700, 1400, sr, 11);
        short[] ownerB = VoicePrint.synthesize(124, 740, 1400, sr, 12);
        short[] otherC = VoicePrint.synthesize(205, 1850, 1400, sr, 13);
        short[] noisy = VoicePrint.synthesize(120, 720, 1200, sr, 14);
        float[] pOwnerA = VoicePrint.fromPcm(ownerA, sr);
        float[] pOwnerB = VoicePrint.fromPcm(ownerB, sr);
        float[] pOther = VoicePrint.fromPcm(otherC, sr);
        float[] pNoisy = VoicePrint.fromPcm(noisy, sr);

        ok(pOwnerA != null && pOther != null, "prints computed from PCM (dims=" + VoicePrint.DIMS + ")");
        double same = VoicePrint.similarity(pOwnerA, pOwnerB);
        double diff = VoicePrint.similarity(pOwnerA, pOther);
        System.out.printf("  same-speaker sim=%.3f  other-speaker sim=%.3f%n", same, diff);
        ok(same > diff + 0.15, "owner-vs-owner scores clearly above owner-vs-other");
        ok(diff < SpeakerGate.DEFAULT_THRESHOLD, "other speaker falls below the gate threshold");

        SpeakerGate gate = new SpeakerGate();
        SpeakerGate.Verdict v1 = gate.verify(pOwnerA);
        ok(v1.accept, "un-enrolled gate lets the first speaker in (bootstrap) -> " + v1);
        gate.enroll(Arrays.asList(pOwnerA, pOwnerB));
        ok(gate.verify(pOwnerA).accept, "enrolled owner accepted -> " + gate.verify(pOwnerA));
        SpeakerGate.Verdict vOther = gate.verify(pOther);
        ok(!vOther.accept, "different voice refused the wake phrase -> " + vOther);
        ok(gate.verify(pNoisy).score > 0.5, "perturbed owner print still scores high -> " + gate.describe());

        // Measured limit, printed on purpose: two voices that differ ONLY in pitch and sit in the
        // same formant region are the hard case. Whatever this number is, it is the honest ceiling.
        short[] nearA = VoicePrint.synthesize(200, 1850, 1400, sr, 31);
        short[] nearB = VoicePrint.synthesize(232, 1800, 1400, sr, 32);
        double hard = VoicePrint.similarity(VoicePrint.fromPcm(nearA, sr), VoicePrint.fromPcm(nearB, sr));
        System.out.printf("  LIMIT  pitch-only difference (200Hz vs 232Hz): sim=%.3f%n", hard);
        ok(hard < same, "hard pair still scores below the same-speaker pair");

        System.out.println("\n== PersonDescriptor: re-spot the same person ==");
        int w = 64, h = 64;
        int[] alice = PersonDescriptor.synthesizeFace(w, h, 0xd8a878, 0x2b1b12, 0.45, 21);
        int[] alice2 = PersonDescriptor.synthesizeFace(w, h, 0xd8a878, 0x2b1b12, 0.46, 22);   // same person, new frame
        int[] bob = PersonDescriptor.synthesizeFace(w, h, 0x8a5a3b, 0x6b6b6b, 0.60, 23);     // different person
        float[] dAlice = PersonDescriptor.fromCrop(alice, w, h);
        float[] dAlice2 = PersonDescriptor.fromCrop(alice2, w, h);
        float[] dBob = PersonDescriptor.fromCrop(bob, w, h);

        double sSame = PersonDescriptor.similarity(dAlice, dAlice2);
        double sDiff = PersonDescriptor.similarity(dAlice, dBob);
        System.out.printf("  same-person sim=%.3f  different-person sim=%.3f%n", sSame, sDiff);
        ok(sSame > sDiff + 0.10, "same person scores clearly above a different person");

        List<float[]> known = new ArrayList<>(Arrays.asList(dAlice));
        PersonDescriptor.Match m = PersonDescriptor.best(dAlice2, known, PersonDescriptor.DEFAULT_THRESHOLD, PersonDescriptor.MARGIN);
        ok(m.index == 0, "known face re-matched at index 0 (score " + String.format("%.3f", m.score) + ")");
        PersonDescriptor.Match mUnknown = PersonDescriptor.best(dBob, known, PersonDescriptor.DEFAULT_THRESHOLD, PersonDescriptor.MARGIN);
        ok(mUnknown.index == -1, "stranger is NOT matched to a stored person (score "
                + String.format("%.3f", mUnknown.score) + " < " + PersonDescriptor.DEFAULT_THRESHOLD + ")");

        System.out.println("\n== RESULT: " + pass + " passed, " + fail + " failed ==");
        if (fail > 0) System.exit(1);
    }

    static void expect(WakePhrase.Command want, String utterance) {
        WakePhrase.Result r = WakePhrase.parse(utterance);
        ok(r.isCommand() && r.command == want, "\"" + utterance + "\" -> " + want + " (got " + r + ")");
    }

    static void reject(String utterance) {
        WakePhrase.Result r = WakePhrase.parse(utterance);
        ok(!r.isCommand(), "no command from \"" + utterance + "\" (got " + r + ")");
    }
}
