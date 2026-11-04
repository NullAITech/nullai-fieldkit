package tech.nullai.fieldkit;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Persistent Conversation & Caption Store for Spotter (Zoth FieldKit).
 *
 * <p>Tracks active and past conversations across both Mission Coach (MainActivity)
 * and Full-Duplex Muse Calls (CallActivity), stores live AI/user captions, and lets
 * the wearer browse transcripts or start a fresh conversation at any time.
 */
public final class ConversationStore {

    private static final String TAG = "ConversationStore";
    private static final String FILE = "conversations_v1.json";
    private static final String PREFS = "fieldkit";
    private static final String KEY_CAPTIONS = "live_captions_enabled";
    private static final int MAX_CONVERSATIONS = 50;
    private static final int MAX_TURNS_PER_CONV = 200;

    public static final class Turn {
        public long timestamp;
        public String speaker; // "Muse", "Coach", "You", "Vision", "System"
        public String text;

        public Turn(long timestamp, String speaker, String text) {
            this.timestamp = timestamp;
            this.speaker = speaker;
            this.text = text;
        }
    }

    public static final class Conversation {
        public String id;
        public String title;
        public String mode;    // "Mission Coach" | "Muse Live Call"
        public String source;  // "Ray-Ban Meta" | "Phone Camera"
        public long createdAt;
        public long updatedAt;
        public final List<Turn> turns = new ArrayList<>();
    }

    private static ConversationStore instance;
    private final Context appCtx;
    private final File file;
    private final List<Conversation> items = new ArrayList<>();
    private String activeId = "";

    public static synchronized ConversationStore get(Context ctx) {
        if (instance == null) {
            instance = new ConversationStore(ctx.getApplicationContext());
        }
        return instance;
    }

    private ConversationStore(Context appCtx) {
        this.appCtx = appCtx;
        this.file = new File(appCtx.getFilesDir(), FILE);
        load();
        if (items.isEmpty()) {
            startNew("Mission Coach", "Ray-Ban Meta", "Spotter Session");
        } else {
            activeId = items.get(0).id;
        }
    }

    public boolean isCaptionsEnabled() {
        SharedPreferences sp = appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return sp.getBoolean(KEY_CAPTIONS, true);
    }

    public void setCaptionsEnabled(boolean enabled) {
        appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_CAPTIONS, enabled).apply();
    }

    public boolean toggleCaptions() {
        boolean next = !isCaptionsEnabled();
        setCaptionsEnabled(next);
        return next;
    }

    public synchronized List<Conversation> all() {
        return new ArrayList<>(items);
    }

    public synchronized Conversation active() {
        Conversation c = find(activeId);
        if (c == null) {
            if (!items.isEmpty()) {
                c = items.get(0);
                activeId = c.id;
            } else {
                c = startNew("Mission Coach", "Ray-Ban Meta", "Spotter Session");
            }
        }
        return c;
    }

    public synchronized Conversation getActiveOrCreate(String defaultTitle, String modeKey) {
        Conversation c = active();
        if (c != null) return c;
        String modeLabel = "call".equalsIgnoreCase(modeKey) ? "Muse Live Call" : "Mission Coach";
        return startNew(modeLabel, "Ray-Ban Meta", defaultTitle);
    }

    public synchronized void appendMessage(String convId, String speaker, String text) {
        if (convId != null && !convId.isEmpty()) {
            setActiveId(convId);
        }
        Conversation c = active();
        appendTurn(speaker, text, c != null ? c.mode : "Mission Coach", c != null ? c.source : "Ray-Ban Meta");
    }

    /** Streaming transcripts arrive as growing cumulative snapshots of one turn:
     *  replace the last same-speaker turn while it is still a prefix of the new
     *  snapshot instead of persisting every partial as its own turn. */
    public synchronized void appendStreamingMessage(String convId, String speaker, String text) {
        if (text == null || text.trim().isEmpty()) return;
        if (convId != null && !convId.isEmpty()) setActiveId(convId);
        Conversation c = active();
        if (c == null) return;
        String clean = text.trim();
        if (!c.turns.isEmpty()) {
            Turn last = c.turns.get(c.turns.size() - 1);
            if (last.speaker.equals(speaker)
                    && (clean.startsWith(last.text) || last.text.startsWith(clean))) {
                last.text = clean;
                last.timestamp = System.currentTimeMillis();
                c.updatedAt = last.timestamp;
                save();
                return;
            }
        }
        appendMessage(convId, speaker, clean);
    }

    public synchronized void setActiveId(String id) {
        Conversation c = find(id);
        if (c != null) {
            activeId = c.id;
        }
    }

    public synchronized Conversation startNew(String mode, String source, String title) {
        Conversation c = new Conversation();
        c.id = UUID.randomUUID().toString().substring(0, 8);
        c.mode = (mode == null || mode.isEmpty()) ? "Mission Coach" : mode;
        c.source = (source == null || source.isEmpty()) ? "Ray-Ban Meta" : source;
        String ts = new SimpleDateFormat("MMM d, HH:mm", Locale.US).format(new Date());
        c.title = (title == null || title.trim().isEmpty())
                ? (c.mode + " · " + ts) : title.trim();
        c.createdAt = System.currentTimeMillis();
        c.updatedAt = c.createdAt;
        items.add(0, c);
        while (items.size() > MAX_CONVERSATIONS) {
            items.remove(items.size() - 1);
        }
        activeId = c.id;
        save();
        return c;
    }

    public synchronized void appendTurn(String speaker, String text, String mode, String source) {
        if (text == null || text.trim().isEmpty()) return;
        String clean = text.trim();
        Conversation c = active();
        if (mode != null && !mode.isEmpty()) c.mode = mode;
        if (source != null && !source.isEmpty()) c.source = source;

        // Avoid duplicate consecutive turns with identical speaker + text
        if (!c.turns.isEmpty()) {
            Turn last = c.turns.get(c.turns.size() - 1);
            if (last.speaker.equals(speaker) && last.text.equals(clean)) {
                return;
            }
        }

        // Auto-upgrade generic title from first meaningful user/goal prompt
        if (c.turns.size() <= 2 && ("You".equals(speaker) || "Goal".equals(speaker))) {
            if (c.title.startsWith("Spotter Session") || c.title.startsWith("Mission Coach") || c.title.startsWith("Muse Live Call")) {
                c.title = clean.length() > 42 ? clean.substring(0, 42) + "…" : clean;
            }
        }

        c.turns.add(new Turn(System.currentTimeMillis(), speaker, clean));
        while (c.turns.size() > MAX_TURNS_PER_CONV) {
            c.turns.remove(0);
        }
        c.updatedAt = System.currentTimeMillis();
        // Bubble active conversation to top
        items.remove(c);
        items.add(0, c);
        save();
    }

    public synchronized void delete(String id) {
        items.removeIf(c -> c.id.equals(id));
        if (activeId.equals(id)) {
            activeId = items.isEmpty() ? "" : items.get(0).id;
        }
        save();
    }

    public synchronized void clearAll() {
        items.clear();
        startNew("Mission Coach", "Ray-Ban Meta", "Fresh Spotter Session");
    }

    public synchronized Conversation find(String id) {
        if (id == null) return null;
        for (Conversation c : items) {
            if (c.id.equals(id)) return c;
        }
        return null;
    }

    public static String formatTranscript(Conversation c) {
        if (c == null || c.turns.isEmpty()) {
            return "No messages recorded in this conversation yet.\nSpeak to Muse or start a mission to populate live captions.";
        }
        SimpleDateFormat tf = new SimpleDateFormat("HH:mm:ss", Locale.US);
        StringBuilder sb = new StringBuilder();
        for (Turn t : c.turns) {
            sb.append("[").append(tf.format(new Date(t.timestamp))).append("] ")
              .append(t.speaker).append(": ")
              .append(t.text).append("\n\n");
        }
        return sb.toString().trim();
    }

    private void load() {
        if (!file.exists()) return;
        try {
            String raw = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            JSONObject root = new JSONObject(raw);
            activeId = root.optString("active_id", "");
            JSONArray arr = root.optJSONArray("conversations");
            if (arr == null) return;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Conversation c = new Conversation();
                c.id = o.optString("id", UUID.randomUUID().toString().substring(0, 8));
                c.title = o.optString("title", "Spotter Session");
                c.mode = o.optString("mode", "Mission Coach");
                c.source = o.optString("source", "Ray-Ban Meta");
                c.createdAt = o.optLong("created_at", System.currentTimeMillis());
                c.updatedAt = o.optLong("updated_at", c.createdAt);
                JSONArray turns = o.optJSONArray("turns");
                if (turns != null) {
                    for (int j = 0; j < turns.length(); j++) {
                        JSONObject to = turns.getJSONObject(j);
                        c.turns.add(new Turn(
                                to.optLong("ts", c.createdAt),
                                to.optString("speaker", "Muse"),
                                to.optString("text", "")
                        ));
                    }
                }
                items.add(c);
            }
        } catch (Throwable t) {
            Log.w(TAG, "load note: " + t.getMessage());
        }
    }

    private void save() {
        try {
            JSONObject root = new JSONObject();
            root.put("active_id", activeId);
            JSONArray arr = new JSONArray();
            for (Conversation c : items) {
                JSONObject o = new JSONObject();
                o.put("id", c.id);
                o.put("title", c.title);
                o.put("mode", c.mode);
                o.put("source", c.source);
                o.put("created_at", c.createdAt);
                o.put("updated_at", c.updatedAt);
                JSONArray turns = new JSONArray();
                for (Turn t : c.turns) {
                    turns.put(new JSONObject()
                            .put("ts", t.timestamp)
                            .put("speaker", t.speaker)
                            .put("text", t.text));
                }
                o.put("turns", turns);
                arr.put(o);
            }
            root.put("conversations", arr);
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(root.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable t) {
            Log.w(TAG, "save note: " + t.getMessage());
        }
    }
}
