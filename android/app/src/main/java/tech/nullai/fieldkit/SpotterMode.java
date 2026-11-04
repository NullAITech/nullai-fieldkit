package tech.nullai.fieldkit;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The 4 canonical Spotter Persona Modes:
 * 1. INTEL ("Person & Intel Recon"): Character Boards, facial recognition, social dossiers, VIP tracking.
 * 2. GOD_VOICE ("Conscious & Subconscious Voice"): God's voice in your ear, quiet inner guidance, higher intuition.
 * 3. VIBE_CODER ("Vibe Coder Wingman"): Screen pair-programmer, code review, debugging, terminal wingman.
 * 4. DIRECTIVE ("Custom Autonomous Directive"): Wearer specifies their exact objective (e.g. sell web services).
 */
public final class SpotterMode {

    public static final String MODE_INTEL = "intel";
    public static final String MODE_GOD_VOICE = "god_voice";
    public static final String MODE_VIBE_CODER = "vibe_coder";
    public static final String MODE_DIRECTIVE = "directive";

    public static final class ModeInfo {
        public final String key;
        public final String title;
        public final String icon;
        public final String tag;
        public final String defaultGoal;
        public final String promptDirective;

        public ModeInfo(String key, String title, String icon, String tag, String defaultGoal, String promptDirective) {
            this.key = key;
            this.title = title;
            this.icon = icon;
            this.tag = tag;
            this.defaultGoal = defaultGoal;
            this.promptDirective = promptDirective;
        }
    }

    public static final ModeInfo[] ALL_MODES = new ModeInfo[]{
            new ModeInfo(
                    MODE_INTEL,
                    "Intel & People",
                    "👥",
                    "Character Boards & Social Dossier",
                    "Recognize people, recall names & attire, and suggest smooth openers.",
                    "Goal mode INTEL: The wearer is networking, meeting people, or conducting social recon. "
                            + "Identify people in view using the Saved People roster and 4-Angle Character Boards. "
                            + "Suggest natural, confident openers tied to what is seen, note attire and expressions, "
                            + "and help remember names and follow-ups. Speak concisely into the wearer's ear."
            ),
            new ModeInfo(
                    MODE_GOD_VOICE,
                    "Subconscious",
                    "👁️",
                    "God's Voice in Your Ear · Intuition",
                    "Silent inner guardian offering quiet perspective and deep intuitive clarity.",
                    "Goal mode SUBCONSCIOUS (GOD'S VOICE): You are the wearer's inner voice, subconscious guardian, "
                            + "and higher intuition speaking softly into their ear through smart glasses. "
                            + "Do NOT chatter continuously. Speak ONLY when a critical intuitive insight, hidden pattern, "
                            + "moral clarity, or safety hazard is detected, or when the wearer pauses and speaks to you. "
                            + "Keep all replies under 16 words, calm, serene, and deeply grounding."
            ),
            new ModeInfo(
                    MODE_VIBE_CODER,
                    "Vibe Coder",
                    "⚡",
                    "Screen Pair Programmer & Debugger",
                    "Spot code errors, terminal commands, and architecture bugs on screens.",
                    "Goal mode VIBE CODER: You are an elite software engineering pair programmer looking directly "
                            + "at the wearer's monitors, laptop screens, IDEs, and terminals. "
                            + "Instantly spot syntax errors, tracebacks, git commands, and architecture improvements. "
                            + "When the wearer asks about code in view, provide the exact fix, command, or explanation punchily."
            ),
            new ModeInfo(
                    MODE_DIRECTIVE,
                    "Custom Goal",
                    "🎯",
                    "Wearer Autonomous Mission",
                    "Autonomous co-pilot executing the wearer's specific objective.",
                    "Goal mode DIRECTIVE: Help the wearer accomplish their custom stated mission. "
                            + "Actively watch the Vision Memory stream for landmarks, objects, or people matching the goal, "
                            + "and provide proactive guidance toward completing the mission."
            )
    };

    private SpotterMode() {}

    public static ModeInfo getMode(String key) {
        if (key != null) {
            for (ModeInfo m : ALL_MODES) {
                if (m.key.equalsIgnoreCase(key) || (m.key.equals(MODE_INTEL) && "network".equalsIgnoreCase(key))) {
                    return m;
                }
            }
        }
        return ALL_MODES[0];
    }

    public static ModeInfo getActiveMode(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences("fieldkit", Context.MODE_PRIVATE);
        String key = sp.getString("spotter_mode_key", MODE_INTEL);
        return getMode(key);
    }

    public static String getActiveObjective(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences("fieldkit", Context.MODE_PRIVATE);
        ModeInfo mi = getActiveMode(ctx);
        return sp.getString("spotter_goal_" + mi.key, mi.defaultGoal);
    }

    public static void setActiveMode(Context ctx, String key, String customGoal) {
        ModeInfo mi = getMode(key);
        SharedPreferences.Editor ed = ctx.getSharedPreferences("fieldkit", Context.MODE_PRIVATE).edit()
                .putString("spotter_mode_key", mi.key)
                .putString("goal_mode", mi.key)
                .putString("goal_mode_label", mi.title);
        if (customGoal != null && !customGoal.trim().isEmpty()) {
            ed.putString("spotter_goal_" + mi.key, customGoal.trim());
        }
        ed.apply();
    }
}
