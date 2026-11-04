package tech.nullai.fieldkit.vision;

import android.graphics.RectF;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import tech.nullai.fieldkit.people.PeopleVault;

/**
 * Continuous Vision Memory sliding window.
 *
 * <p>Pre-digests raw video stream frames into a timestamped, chronological log of
 * environmental context, person sightings, recognized Character Boards, screen contents,
 * and social/physical interactions.
 *
 * <p>This allows the conversational Muse Live model to ground its responses immediately
 * without doing heavy visual reasoning during user speech turns, completely eliminating
 * voice-to-text lag.
 */
public final class VisionMemory {

    public static final class VisionEvent {
        public final long timestampMs;
        public final String timeFormatted;
        public final String type;       // "PERSON_RECOGNIZED", "PERSON_SEEN", "OBJECTS", "SCENE", "CODE_SCREEN"
        public final String summary;
        public final String details;
        public final int faceCount;
        public final int bodyCount;
        public final String recognizedName;

        public VisionEvent(long ts, String type, String summary, String details,
                           int faceCount, int bodyCount, String recognizedName) {
            this.timestampMs = ts;
            this.timeFormatted = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(ts));
            this.type = type;
            this.summary = summary;
            this.details = details;
            this.faceCount = faceCount;
            this.bodyCount = bodyCount;
            this.recognizedName = recognizedName == null ? "" : recognizedName;
        }

        public String formatRelative(long nowMs) {
            long diffSec = Math.max(0, (nowMs - timestampMs) / 1000L);
            String rel = diffSec == 0 ? "now" : "-" + diffSec + "s";
            return "• [" + timeFormatted + " (" + rel + ")]: " + summary
                    + (details.isEmpty() ? "" : " · " + details);
        }
    }

    private static final int MAX_EVENTS = 16;
    private final ArrayDeque<VisionEvent> events = new ArrayDeque<>();
    private final Object lock = new Object();

    private String lastSummaryHash = "";
    private long lastEventTimeMs = 0L;
    private long lastProactiveTriggerMs = 0L;
    private String lastRecognizedPersonId = "";

    public VisionMemory() {
    }

    /**
     * Records a vision observation from on-device MediaPipe Detector and PeopleVault matcher.
     */
    public void recordObservation(Detector.Result r, PeopleVault.RecognitionMatch match, String cameraSource) {
        if (r == null) return;
        long now = System.currentTimeMillis();

        String recognizedName = (match != null && match.person != null) ? match.person.name : "";
        String recognizedRole = (match != null && match.person != null) ? match.person.role : "";
        String recognizedAttire = (match != null && match.person != null) ? match.person.attire : "";

        int faces = r.faces != null ? r.faces.size() : 0;
        int bodies = r.bodies != null ? r.bodies.size() : 0;
        int objects = r.objects != null ? r.objects.size() : 0;

        // Detect code/screen keywords from object detector
        boolean codeScreen = false;
        if (r.objects != null) {
            for (String obj : r.objects) {
                String o = obj.toLowerCase(Locale.ROOT);
                if (o.contains("laptop") || o.contains("tv") || o.contains("monitor") || o.contains("screen")) {
                    codeScreen = true;
                    break;
                }
            }
        }

        String type;
        String summary;
        String details;

        if (!recognizedName.isEmpty()) {
            type = "PERSON_RECOGNIZED";
            summary = "PERSON RECOGNIZED: " + recognizedName + " (" + recognizedRole + ") · "
                    + Math.round(match.score * 100) + "% match";
            details = "Attire: " + recognizedAttire + (match.person.notes.isEmpty() ? "" : " · Notes: " + match.person.notes);
        } else if (faces > 0 || bodies > 0) {
            type = "PERSON_SEEN";
            summary = (faces > 0 ? faces + " face(s)" : bodies + " person(s)") + " in frame (" + cameraSource + ")";
            details = objects > 0 ? "Nearby objects: " + String.join(", ", r.objects) : "Approaching / in field of view";
        } else if (codeScreen) {
            type = "CODE_SCREEN";
            summary = "Screen / Workstation in view (" + String.join(", ", r.objects) + ")";
            details = "Display surface detected; active for Vibe Coder / tech assistance";
        } else if (objects > 0) {
            type = "OBJECTS";
            summary = "Objects detected: " + String.join(", ", r.objects);
            details = "Ambient indoor/outdoor scene";
        } else {
            type = "SCENE";
            summary = "Clear ambient scene (" + cameraSource + ")";
            details = "No persons or key objects in view";
        }

        String hash = type + ":" + recognizedName + ":" + faces + ":" + bodies + ":" + (objects > 0 ? r.objects.get(0) : "");

        synchronized (lock) {
            // Only append if state changed or >= 7 seconds have elapsed to keep memory compact
            if (hash.equals(lastSummaryHash) && (now - lastEventTimeMs < 7000L)) {
                return;
            }

            lastSummaryHash = hash;
            lastEventTimeMs = now;

            VisionEvent ev = new VisionEvent(now, type, summary, details, faces, bodies, recognizedName);
            events.addLast(ev);
            while (events.size() > MAX_EVENTS) {
                events.removeFirst();
            }
        }
    }

    /**
     * Builds a structured chronological Vision Memory block for injection into the model's context window.
     */
    public String toContextPrompt(String activeMode, String activeObjective) {
        long now = System.currentTimeMillis();
        List<VisionEvent> copy;
        synchronized (lock) {
            copy = new ArrayList<>(events);
        }
        if (copy.isEmpty()) {
            return "[VISION MEMORY]: Camera stream initialized; waiting for visual landmarks.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("[VISION MEMORY · CHRONOLOGICAL TIMELINE]:\n");
        int start = Math.max(0, copy.size() - 8);
        for (int i = start; i < copy.size(); i++) {
            sb.append(copy.get(i).formatRelative(now)).append("\n");
        }

        if (activeMode != null && !activeMode.isEmpty()) {
            sb.append("[ACTIVE SPOTTER MODE]: ").append(activeMode);
            if (activeObjective != null && !activeObjective.trim().isEmpty()) {
                String safeObj = activeObjective.replaceAll("[\\r\\n\\[\\]]+", " ").trim();
                sb.append(" · Objective: \"").append(safeObj).append("\"");
            }
            sb.append("\nGround all spoken replies in this visual memory timeline.");
        }
        return sb.toString().trim();
    }

    /**
     * Evaluates whether an objective-relevant event occurred that warrants a proactive spoken prompt.
     * Rate-limited to at most once per 20 seconds.
     */
    public String checkObjectiveTrigger(String activeMode, String activeObjective) {
        long now = System.currentTimeMillis();
        VisionEvent latest = null;
        synchronized (lock) {
            if (now - lastProactiveTriggerMs < 20_000L) return null;
            if (!events.isEmpty()) latest = events.peekLast();
            if (latest == null) return null;

            String mode = activeMode == null ? "intel" : activeMode.toLowerCase(Locale.ROOT);

            // 1. Intel / Network mode: fire when a person is recognized or appears
            if ("intel".equals(mode) || "network".equals(mode)) {
                if (!latest.recognizedName.isEmpty() && !latest.recognizedName.equals(lastRecognizedPersonId)) {
                    lastRecognizedPersonId = latest.recognizedName;
                    lastProactiveTriggerMs = now;
                    return "[OBJECTIVE TRIGGER: INTEL]: Person recognized in front of wearer: "
                            + latest.summary + ". Discreetly whisper who they are and a natural opener.";
                } else if (latest.faceCount > 0 && lastRecognizedPersonId.isEmpty() && (now - latest.timestampMs < 3000L)) {
                    lastProactiveTriggerMs = now;
                    return "[OBJECTIVE TRIGGER: NETWORKING]: Unrecognized person currently in view. "
                            + "Discreetly offer a friendly opener or remind wearer they can say Save Person.";
                }
            }

            // 2. Vibe Coder mode: screen / workstation spotted
            if ("vibe_coder".equals(mode) || "code".equals(mode)) {
                if ("CODE_SCREEN".equals(latest.type) && (now - latest.timestampMs < 4000L)) {
                    lastProactiveTriggerMs = now;
                    return "[OBJECTIVE TRIGGER: VIBE CODER]: Code screen / workstation detected in view. "
                            + "Offer to inspect the current code or syntax if the wearer has a question.";
                }
            }

            // 3. Subconscious / God's Voice mode: tranquil or transitioning
            if ("god_voice".equals(mode) || "subconscious".equals(mode)) {
                // Only trigger if an unrecognized stranger is standing close or wearer enters a new space
                if (latest.faceCount > 0 && (now - latest.timestampMs < 3000L)) {
                    lastProactiveTriggerMs = now;
                    return "[OBJECTIVE TRIGGER: SUBCONSCIOUS]: A soul has entered the wearer's immediate space. "
                            + "Offer one calm, grounding sentence of perspective.";
                }
            }

            // 4. Custom directive: check if latest event contains objective keywords
            if ("directive".equals(mode) || "custom".equals(mode)) {
                if (activeObjective != null && !activeObjective.isEmpty()) {
                    String safeObj = activeObjective.replaceAll("[\\r\\n\\[\\]]+", " ").trim();
                    String[] words = safeObj.toLowerCase(Locale.ROOT).split("\\s+");
                    for (String w : words) {
                        if (w.length() >= 4 && latest.summary.toLowerCase(Locale.ROOT).contains(w)) {
                            lastProactiveTriggerMs = now;
                            return "[OBJECTIVE TRIGGER: DIRECTIVE MATCH]: Visual match for \"" + w + "\" ("
                                    + latest.summary + "). Advise wearer on their next step toward: " + safeObj;
                        }
                    }
                }
            }
        }

        return null;
    }
}
