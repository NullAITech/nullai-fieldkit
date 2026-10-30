package tech.nullai.fieldkit.voice;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Wake-command grammar for Spotter.
 *
 * Design rule: a command fires only when a RARE ANCHOR is followed by a required
 * token sequence, in order, with at most one filler word between required tokens and
 * the whole match inside a short window. The bare anchor is never a command - it is an
 * attention ping.
 *
 * That combination is what makes always-listening safe: ordinary conversation almost
 * never produces "anchor + 2-3 order-sensitive command tokens", possessives and
 * conditionals are explicitly rejected, and a second, rarer anchor ("zoth") is accepted
 * for every command when the room is noisy.
 */
public final class WakePhrase {

    public enum Command {
        NONE,
        GUIDE_ON, GUIDE_OFF,
        QUIET_ON, QUIET_OFF,
        SIGHT_CHECK,
        NOTE,
        PERSON_MARK, PERSON_ENROLL, PERSON_WHO,
        SOUL_SYNC
    }

    /** Words that turn a match back into ordinary talk ("the spotter's guide said to activate it"). */
    private static final Set<String> CONTAMINANTS = new HashSet<>(Arrays.asList(
            "if", "unless", "when", "whenever", "would", "should", "could", "did", "does",
            "not", "dont", "didnt", "doesnt", "cant", "wont", "maybe", "might", "wondering",
            "said", "says", "asked", "tell", "told", "asked", "s", "is", "was", "were", "be",
            "guidebook", "yuan", "decided", "wanted", "trying", "tried"
    ));

    /** Words that may sit between required tokens ("spotter guide mode, please, activate"). */
    private static final Set<String> FILLERS = new HashSet<>(Arrays.asList(
            "please", "now", "then", "uh", "um", "er", "ah", "hey", "ok", "okay", "just", "go", "and", "to"
    ));

    /**
     * The anchor must ADDRESS the device: it has to open the utterance or follow one of these.
     * This is what stops "if I say spotter guide mode activate what happens" from firing.
     */
    private static final Set<String> ADDRESS = new HashSet<>(Arrays.asList(
            "hey", "ok", "okay", "yo", "zoth", "alright", "and", "so", "now", "um", "uh", "please"
    ));

    private static final String[] ANCHORS_PRIMARY = {"spotter"};
    private static final String[] ANCHORS_RARE = {"zoth"};

    private static final int MAX_SPAN = 7;          // tokens from anchor to end of match
    private static final double MIN_CONFIDENCE = 0.80;

    private static final class Spec {
        final Command cmd;
        final String[] tokens;
        final String[] rareOk;   // tokens accepted as the final token (say/see/start variants)

        Spec(Command cmd, String[] tokens, String... rareOk) {
            this.cmd = cmd;
            this.tokens = tokens;
            this.rareOk = rareOk;
        }
    }

    private static final List<Spec> SPECS = new ArrayList<>();
    static {
        SPECS.add(new Spec(Command.GUIDE_ON, new String[]{"guide", "mode", "activate"}, "on"));
        SPECS.add(new Spec(Command.GUIDE_OFF, new String[]{"guide", "mode", "stand", "down"}, "off"));
        SPECS.add(new Spec(Command.QUIET_ON, new String[]{"go", "quiet"}, "quiet"));
        SPECS.add(new Spec(Command.QUIET_OFF, new String[]{"resume", "listening"}, "resume"));
        SPECS.add(new Spec(Command.SIGHT_CHECK, new String[]{"sight", "check"}));
        SPECS.add(new Spec(Command.NOTE, new String[]{"witness", "note"}, "note"));
        SPECS.add(new Spec(Command.PERSON_MARK, new String[]{"mark", "person"}));
        SPECS.add(new Spec(Command.PERSON_ENROLL, new String[]{"enroll", "person"}));
        SPECS.add(new Spec(Command.PERSON_WHO, new String[]{"who", "is", "this"}, "this"));
        SPECS.add(new Spec(Command.SOUL_SYNC, new String[]{"soul", "sync"}, "sync"));
    }

    public static final class Result {
        public final Command command;
        public final double confidence;
        public final boolean anchored;      // anchor word heard, no command matched
        public final String matchedText;

        Result(Command command, double confidence, boolean anchored, String matchedText) {
            this.command = command;
            this.confidence = confidence;
            this.anchored = anchored;
            this.matchedText = matchedText;
        }

        public boolean isCommand() {
            return command != Command.NONE && confidence >= MIN_CONFIDENCE;
        }

        @Override
        public String toString() {
            return command + (isCommand() ? "" : "(below-threshold)")
                    + " conf=" + String.format(Locale.US, "%.2f", confidence)
                    + (anchored ? " [anchor-heard]" : "");
        }
    }

    private WakePhrase() {
    }

    public static List<String> tokenize(String utterance) {
        if (utterance == null) return Collections.emptyList();
        String s = utterance.toLowerCase(Locale.US).replaceAll("[^a-z0-9']+", " ").trim();
        if (s.isEmpty()) return Collections.emptyList();
        return Arrays.asList(s.split("\\s+"));
    }

    /** Parse one transcript. Returns NONE with anchored=false when nothing was heard. */
    public static Result parse(String utterance) {
        List<String> t = tokenize(utterance);
        if (t.isEmpty()) return new Result(Command.NONE, 0.0, false, "");

        int anchor = -1;
        boolean rare = false;
        for (int i = 0; i < t.size(); i++) {
            String w = t.get(i);
            if (matches(ANCHORS_PRIMARY, w)) { anchor = i; break; }
            if (matches(ANCHORS_RARE, w)) { anchor = i; rare = true; break; }
        }
        if (anchor < 0) return new Result(Command.NONE, 0.0, false, "");

        // A long utterance that merely contains the anchor is conversation, not a command,
        // unless the anchor leads it ("spotter, ..." / "hey spotter ...").
        boolean leads = anchor <= 1;
        if (!leads && t.size() - anchor > MAX_SPAN) {
            return new Result(Command.NONE, 0.0, true, join(t, anchor, t.size()));
        }

        Result best = new Result(Command.NONE, 0.0, true, "");
        for (Spec spec : SPECS) {
            Result r = match(spec, t, anchor, rare);
            if (r != null && r.confidence > best.confidence) best = r;
        }
        if (best.command == Command.NONE) {
            return new Result(Command.NONE, 0.0, true, join(t, anchor, Math.min(t.size(), anchor + MAX_SPAN)));
        }
        return best;
    }

    /**
     * Nothing in the window after the anchor may be a conditional/negation, and the anchor itself
     * must address the device - not be mentioned mid-sentence.
     */
    private static Result match(Spec spec, List<String> t, int anchor, boolean rare) {
        if (anchor > 0 && !ADDRESS.contains(t.get(anchor - 1))) return null;   // "say spotter ..." is talk
        int i = anchor + 1;
        int end = Math.min(t.size(), anchor + MAX_SPAN);
        int needed = spec.tokens.length;
        int matched = 0;
        int fillers = 0;
        int lastHit = anchor;
        int startOfMatch = -1;

        while (i < end && matched < needed) {
            String w = t.get(i);
            String want = spec.tokens[matched];
            boolean lastToken = (matched == needed - 1);
            boolean hit = w.equals(want) || (lastToken && matches(spec.rareOk, w));

            if (hit) {
                if (startOfMatch < 0) startOfMatch = i;
                matched++;
                lastHit = i;
                i++;
                continue;
            }
            if (CONTAMINANTS.contains(w)) return null;      // talk about the command, not a command
            if (FILLERS.contains(w)) {
                fillers++;
                if (fillers > 2) return null;               // "please, now, uh, then, ..." = not a command
                i++;
                continue;
            }
            // Unknown word between required tokens: allowed only if the remaining tokens still fit.
            if (i - lastHit > 1) return null;
            i++;
        }
        if (matched < needed) return null;

        int span = lastHit - anchor;
        double conf = 1.0;
        if (span == needed) conf = 1.0;                     // perfectly contiguous
        else if (span <= needed + 1) conf = 0.93;
        else if (span <= needed + 2) conf = 0.85;
        else conf = 0.75;
        if (rare) conf = Math.min(1.0, conf + 0.02);        // rarer anchor, slightly more trust
        return new Result(spec.cmd, conf, true, join(t, Math.max(0, startOfMatch - 1), lastHit + 1));
    }

    private static boolean matches(String[] set, String w) {
        for (String s : set) if (s.equals(w)) return true;
        return false;
    }

    private static String join(List<String> t, int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int i = Math.max(0, from); i < Math.min(t.size(), to); i++) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(t.get(i));
        }
        return sb.toString();
    }

    /** One-line vocabulary for the model's context, so it knows which utterances were commands. */
    public static String vocabularyForPrompt() {
        StringBuilder sb = new StringBuilder();
        for (Spec s : SPECS) {
            if (sb.length() > 0) sb.append(" | ");
            sb.append(String.join(" ", s.tokens));
        }
        return "spotter " + sb;
    }
}
