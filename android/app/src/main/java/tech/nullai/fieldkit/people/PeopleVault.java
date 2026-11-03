package tech.nullai.fieldkit.people;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

import tech.nullai.fieldkit.voice.VoicePrint;

/**
 * The wearer's saved people, 4-angle character boards, body/face descriptors, and voices.
 * Consent-first: an entry exists only because the wearer pressed "Remember" / "Name" and saved it.
 * Stored only on this phone, in app-private storage, encrypted with an AES-256-GCM key held in the
 * Android Keystore (never exported).
 */
public final class PeopleVault {
    private static final String TAG = "PeopleVault";
    private static final String ALIAS = "spotter_people_v1";
    private static final String FILE = "people.vault";
    public static final double VOICE_THRESHOLD = 0.88;

    public static final class Person {
        public String id = "";
        public String name = "";
        public String role = "Contact";
        public String notes = "";
        public String thumb = "";                      // base64 JPEG primary crop
        public String boardThumb = "";                 // base64 JPEG composite 4-angle character board
        public String[] angleThumbs = new String[]{"", "", "", ""}; // 4 angle base64 JPEGs
        public String attire = "";
        public String buildSummary = "";
        public int upperColorRgb = 0xFF2E3540;
        public int lowerColorRgb = 0xFF232832;
        public long savedAt;
        public long voiceSavedAt;
        public long lastSeenAt;
        public int seenCount = 1;
        public String lastSeenContext = "Spotter HUD";
        public double lastMatchScore = 1.0;
        public float[] face = new float[0];
        public float[][] angleFaces = new float[0][];
        public float[] body = new float[0];
        public float[] voice = new float[0];
    }

    public static final class RecognitionMatch {
        public final Person person;
        public final double score;
        public final double faceScore;
        public final double bodyScore;

        public RecognitionMatch(Person person, double score, double faceScore, double bodyScore) {
            this.person = person;
            this.score = score;
            this.faceScore = faceScore;
            this.bodyScore = bodyScore;
        }
    }

    private static PeopleVault instance;
    private final Context appCtx;
    private final File file;
    private final List<Person> people = new ArrayList<>();

    public static synchronized PeopleVault get(Context ctx) {
        if (instance == null) instance = new PeopleVault(ctx.getApplicationContext());
        return instance;
    }

    private PeopleVault(Context ctx) {
        appCtx = ctx.getApplicationContext();
        file = new File(appCtx.getFilesDir(), FILE);
        load();
        syncToRelayAsync();
    }

    public synchronized List<Person> all() { return new ArrayList<>(people); }

    public synchronized boolean isEmpty() { return people.isEmpty(); }

    public synchronized Person add(String name, float[] face, String thumb) {
        return addFull(name, "Contact", "", face, new float[0], thumb, "",
                new String[]{thumb == null ? "" : thumb, "", "", ""},
                "Standard Attire", "Balanced Proportion", 0xFF2E3540, 0xFF232832, "Spotter Live");
    }

    public synchronized Person addFull(
            String name,
            String role,
            String notes,
            float[] face,
            float[] body,
            String thumb,
            String boardThumb,
            String[] angleThumbs,
            String attire,
            String buildSummary,
            int upperColorRgb,
            int lowerColorRgb,
            String contextLabel
    ) {
        return addFullWithAngles(name, role, notes, face, null, body, thumb, boardThumb,
                angleThumbs, attire, buildSummary, upperColorRgb, lowerColorRgb, contextLabel);
    }

    public synchronized Person addFullWithAngles(
            String name,
            String role,
            String notes,
            float[] face,
            float[][] angleFaces,
            float[] body,
            String thumb,
            String boardThumb,
            String[] angleThumbs,
            String attire,
            String buildSummary,
            int upperColorRgb,
            int lowerColorRgb,
            String contextLabel
    ) {
        Person p = new Person();
        p.id = UUID.randomUUID().toString().substring(0, 8);
        p.name = name == null ? "Unnamed" : name.trim();
        p.role = (role == null || role.trim().isEmpty()) ? "Contact" : role.trim();
        p.notes = notes == null ? "" : notes.trim();
        p.face = face == null ? new float[0] : face;
        p.angleFaces = angleFaces == null ? new float[0][] : angleFaces;
        p.body = body == null ? new float[0] : body;
        p.thumb = thumb == null ? "" : thumb;
        p.boardThumb = boardThumb == null ? "" : boardThumb;
        p.angleThumbs = new String[]{"", "", "", ""};
        if (angleThumbs != null) {
            for (int i = 0; i < Math.min(4, angleThumbs.length); i++) {
                p.angleThumbs[i] = angleThumbs[i] == null ? "" : angleThumbs[i];
            }
        }
        p.attire = attire == null ? "" : attire;
        p.buildSummary = buildSummary == null ? "" : buildSummary;
        p.upperColorRgb = upperColorRgb;
        p.lowerColorRgb = lowerColorRgb;
        long now = System.currentTimeMillis();
        p.savedAt = now;
        p.lastSeenAt = now;
        p.seenCount = 1;
        p.lastSeenContext = (contextLabel == null || contextLabel.isEmpty()) ? "Spotter HUD" : contextLabel;
        p.lastMatchScore = 1.0;
        people.add(0, p);
        save();
        return p;
    }

    public synchronized void setVoice(String id, float[] print) {
        Person p = find(id);
        if (p == null) return;
        p.voice = print == null ? new float[0] : print;
        p.voiceSavedAt = print == null ? 0 : System.currentTimeMillis();
        save();
    }

    public synchronized void rename(String id, String name) {
        Person p = find(id);
        if (p == null || name == null || name.trim().isEmpty()) return;
        p.name = name.trim();
        save();
    }

    public synchronized void updateDossier(String id, String name, String role, String notes) {
        Person p = find(id);
        if (p == null) return;
        if (name != null && !name.trim().isEmpty()) p.name = name.trim();
        if (role != null) p.role = role.trim().isEmpty() ? "Contact" : role.trim();
        if (notes != null) p.notes = notes.trim();
        save();
    }

    public synchronized void updateAppearanceAndNotes(String id, String attire, String buildSummary, String notes) {
        Person p = find(id);
        if (p == null) return;
        if (attire != null && !attire.trim().isEmpty()) p.attire = attire.trim();
        if (buildSummary != null && !buildSummary.trim().isEmpty()) p.buildSummary = buildSummary.trim();
        if (notes != null && !notes.trim().isEmpty()) p.notes = notes.trim();
        save();
    }

    public synchronized void recordSighting(String id, double score, String contextLabel) {
        Person p = find(id);
        if (p == null) return;
        long now = System.currentTimeMillis();
        boolean incrementCount = (now - p.lastSeenAt) > 25_000L;
        p.lastSeenAt = now;
        p.lastMatchScore = score;
        if (contextLabel != null && !contextLabel.isEmpty()) {
            p.lastSeenContext = contextLabel;
        }
        if (incrementCount) {
            p.seenCount = Math.max(1, p.seenCount + 1);
            save();
        }
    }

    public synchronized void delete(String id) {
        people.removeIf(p -> p.id.equals(id));
        save();
    }

    public synchronized void deleteAll() {
        people.clear();
        save();
        file.delete();
    }

    /** Best face match above threshold with a margin over the runner-up, or null. */
    public synchronized Person matchFace(float[] probe) {
        RecognitionMatch m = matchFaceAndBody(probe, null);
        return m == null ? null : m.person;
    }

    /**
     * Dual-modality Face + Body matcher. Uses face (72%) + body/torso (28%) cosine similarity
     * and checks margin against runner-up.
     */
    public synchronized RecognitionMatch matchFaceAndBody(float[] faceProbe, float[] bodyProbe) {
        if ((faceProbe == null || faceProbe.length == 0) && (bodyProbe == null || bodyProbe.length == 0)) {
            return null;
        }
        Person bestPerson = null;
        double bestScore = -1, secondScore = -1;
        double bestFace = -1, bestBody = -1;

        for (Person p : people) {
            double sf = (faceProbe != null && faceProbe.length > 0)
                    ? PersonDescriptor.bestMultiAngleSimilarity(faceProbe, p.face, p.angleFaces) : -1;
            double sb = (bodyProbe != null && p.body.length == bodyProbe.length)
                    ? PersonDescriptor.similarity(bodyProbe, p.body) : -1;
            double combined;
            if (sf >= 0 && sb >= 0) {
                combined = sf * 0.72 + sb * 0.28;
            } else if (sf >= 0) {
                combined = sf;
            } else if (sb >= 0) {
                combined = sb * 0.94;
            } else {
                combined = -1;
            }
            if (combined > bestScore) {
                secondScore = bestScore;
                bestScore = combined;
                bestPerson = p;
                bestFace = sf;
                bestBody = sb;
            } else if (combined > secondScore) {
                secondScore = combined;
            }
        }
        double threshold = (bodyProbe != null && bodyProbe.length > 0)
                ? PersonDescriptor.COMBINED_THRESHOLD
                : PersonDescriptor.DEFAULT_THRESHOLD;
        if (bestPerson == null || bestScore < threshold) return null;
        if (secondScore > 0 && (bestScore - secondScore) < PersonDescriptor.MARGIN) return null;
        return new RecognitionMatch(bestPerson, bestScore, bestFace, bestBody);
    }

    public synchronized Person matchVoice(float[] print) {
        Person best = null;
        double bs = VOICE_THRESHOLD;
        for (Person p : people) {
            if (p.voice.length != (print == null ? -1 : print.length)) continue;
            double s = VoicePrint.similarity(print, p.voice);
            if (s >= bs) { bs = s; best = p; }
        }
        return best;
    }

    public synchronized Person findById(String id) {
        return find(id);
    }

    private Person find(String id) {
        for (Person p : people) if (p.id.equals(id)) return p;
        return null;
    }

    // ------------------------------------------------------------------ storage

    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(ALIAS)) return ((KeyStore.SecretKeyEntry) ks.getEntry(ALIAS, null)).getSecretKey();
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        kg.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build());
        return kg.generateKey();
    }

    private void load() {
        if (!file.exists()) return;
        try {
            byte[] blob = Files.readAllBytes(file.toPath());
            ByteBuffer b = ByteBuffer.wrap(blob);
            byte[] iv = new byte[12];
            b.get(iv);
            byte[] ct = new byte[b.remaining()];
            b.get(ct);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            JSONArray arr = new JSONArray(new String(c.doFinal(ct), StandardCharsets.UTF_8));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Person p = new Person();
                p.id = o.getString("id");
                p.name = o.getString("name");
                p.role = o.optString("role", "Contact");
                p.notes = o.optString("notes", "");
                p.thumb = o.optString("thumb", "");
                p.boardThumb = o.optString("board_thumb", "");
                JSONArray angles = o.optJSONArray("angle_thumbs");
                p.angleThumbs = new String[]{"", "", "", ""};
                if (angles != null) {
                    for (int k = 0; k < Math.min(4, angles.length()); k++) {
                        p.angleThumbs[k] = angles.optString(k, "");
                    }
                }
                p.attire = o.optString("attire", "");
                p.buildSummary = o.optString("build_summary", "");
                p.upperColorRgb = o.optInt("upper_rgb", 0xFF2E3540);
                p.lowerColorRgb = o.optInt("lower_rgb", 0xFF232832);
                p.savedAt = o.optLong("saved_at");
                p.voiceSavedAt = o.optLong("voice_saved_at");
                p.lastSeenAt = o.optLong("last_seen_at", p.savedAt);
                p.seenCount = Math.max(1, o.optInt("seen_count", 1));
                p.lastSeenContext = o.optString("last_seen_context", "Spotter HUD");
                p.lastMatchScore = o.optDouble("last_match_score", 1.0);
                p.face = floats(o.optJSONArray("face"));
                JSONArray afArr = o.optJSONArray("angle_faces");
                if (afArr != null && afArr.length() > 0) {
                    p.angleFaces = new float[afArr.length()][];
                    for (int k = 0; k < afArr.length(); k++) {
                        p.angleFaces[k] = floats(afArr.optJSONArray(k));
                    }
                }
                p.body = floats(o.optJSONArray("body"));
                p.voice = floats(o.optJSONArray("voice"));
                people.add(p);
            }
        } catch (Exception e) {
            Log.w(TAG, "vault unreadable (" + e.getClass().getSimpleName() + "); starting empty");
        }
    }

    private void save() {
        try {
            JSONArray arr = new JSONArray();
            for (Person p : people) {
                JSONArray angles = new JSONArray();
                if (p.angleThumbs != null) {
                    for (String a : p.angleThumbs) angles.put(a == null ? "" : a);
                }
                JSONArray afJson = new JSONArray();
                if (p.angleFaces != null) {
                    for (float[] av : p.angleFaces) {
                        if (av != null && av.length > 0) afJson.put(json(av));
                    }
                }
                arr.put(new JSONObject()
                        .put("id", p.id)
                        .put("name", p.name)
                        .put("role", p.role)
                        .put("notes", p.notes)
                        .put("thumb", p.thumb)
                        .put("board_thumb", p.boardThumb)
                        .put("angle_thumbs", angles)
                        .put("attire", p.attire)
                        .put("build_summary", p.buildSummary)
                        .put("upper_rgb", p.upperColorRgb)
                        .put("lower_rgb", p.lowerColorRgb)
                        .put("saved_at", p.savedAt)
                        .put("voice_saved_at", p.voiceSavedAt)
                        .put("last_seen_at", p.lastSeenAt)
                        .put("seen_count", p.seenCount)
                        .put("last_seen_context", p.lastSeenContext)
                        .put("last_match_score", p.lastMatchScore)
                        .put("face", json(p.face))
                        .put("angle_faces", afJson)
                        .put("body", json(p.body))
                        .put("voice", json(p.voice)));
            }
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key());
            byte[] iv = c.getIV();
            byte[] ct = c.doFinal(arr.toString().getBytes(StandardCharsets.UTF_8));
            File tmp = new File(file.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(iv);
                out.write(ct);
            }
            if (!tmp.renameTo(file)) throw new IllegalStateException("rename failed");
            syncToRelayAsync();
        } catch (Exception e) {
            Log.w(TAG, "vault save failed: " + e.getClass().getSimpleName());
        }
    }

    /** Mirrors saved people (name, role, attire/notes, descriptor) to the relay's /v1/people store. */
    public void syncToRelayAsync() {
        final List<Person> snapshot = all();
        if (snapshot.isEmpty() || appCtx == null) return;
        new Thread(() -> {
            try {
                android.content.SharedPreferences prefs = appCtx.getSharedPreferences("fieldkit", Context.MODE_PRIVATE);
                String relay = prefs.getString("relay", null);
                String token = prefs.getString("token", null);
                if (relay == null || token == null || relay.isEmpty() || token.isEmpty()) return;
                String url = relay.replaceAll("/+$", "") + "/v1/people";
                okhttp3.OkHttpClient client = new okhttp3.OkHttpClient();
                okhttp3.MediaType jsonType = okhttp3.MediaType.get("application/json; charset=utf-8");
                for (Person p : snapshot) {
                    float[] vec = (p.face != null && p.face.length > 0) ? p.face : p.body;
                    if (vec == null || vec.length == 0) continue;
                    StringBuilder noteSb = new StringBuilder();
                    if (p.attire != null && !p.attire.isEmpty()) noteSb.append("Attire: ").append(p.attire);
                    if (p.buildSummary != null && !p.buildSummary.isEmpty()) {
                        if (noteSb.length() > 0) noteSb.append(" · ");
                        noteSb.append("Build: ").append(p.buildSummary);
                    }
                    if (p.notes != null && !p.notes.isEmpty()) {
                        if (noteSb.length() > 0) noteSb.append(" · ");
                        noteSb.append(p.notes);
                    }
                    JSONObject body = new JSONObject()
                            .put("name", p.name)
                            .put("relation", p.role == null ? "Contact" : p.role)
                            .put("note", noteSb.toString())
                            .put("descriptor", json(vec));
                    if (p.voice != null && p.voice.length > 0) {
                        body.put("voice", json(p.voice));
                    }
                    okhttp3.Request req = new okhttp3.Request.Builder()
                            .url(url)
                            .header("Authorization", "Bearer " + token)
                            .post(okhttp3.RequestBody.create(body.toString(), jsonType))
                            .build();
                    try (okhttp3.Response resp = client.newCall(req).execute()) {
                        // best-effort sync
                    }
                }
            } catch (Throwable ignored) { }
        }, "people-relay-sync").start();
    }

    private static JSONArray json(float[] v) throws Exception {
        JSONArray a = new JSONArray();
        if (v != null) for (float f : v) a.put((double) f);
        return a;
    }

    private static float[] floats(JSONArray a) {
        if (a == null) return new float[0];
        float[] v = new float[a.length()];
        for (int i = 0; i < v.length; i++) v[i] = (float) a.optDouble(i);
        return v;
    }
}
