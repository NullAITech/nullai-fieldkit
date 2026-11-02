package tech.nullai.fieldkit.people;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
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
 * The wearer's saved people and voices (Nov 2, hackathon). Consent-first: an entry exists only
 * because the wearer pressed "Remember" and typed a name. Stored only on this phone, in app-private
 * storage, encrypted with an AES-256-GCM key held in the Android Keystore (never exported).
 * Nothing here is sent to any third party; only the name reaches the wearer's own AI session.
 */
public final class PeopleVault {
    private static final String TAG = "PeopleVault";
    private static final String ALIAS = "spotter_people_v1";
    private static final String FILE = "people.vault";
    public static final double VOICE_THRESHOLD = 0.88;

    public static final class Person {
        public String id, name, thumb = "";       // thumb: base64 JPEG of the frame crop at save time
        public long savedAt, voiceSavedAt;
        public float[] face = new float[0], voice = new float[0];
    }

    private static PeopleVault instance;
    private final File file;
    private final List<Person> people = new ArrayList<>();

    public static synchronized PeopleVault get(Context ctx) {
        if (instance == null) instance = new PeopleVault(ctx.getApplicationContext());
        return instance;
    }

    private PeopleVault(Context ctx) {
        file = new File(ctx.getFilesDir(), FILE);
        load();
    }

    public synchronized List<Person> all() { return new ArrayList<>(people); }

    public synchronized boolean isEmpty() { return people.isEmpty(); }

    public synchronized Person add(String name, float[] face, String thumb) {
        Person p = new Person();
        p.id = UUID.randomUUID().toString().substring(0, 8);
        p.name = name.trim();
        p.face = face == null ? new float[0] : face;
        p.thumb = thumb == null ? "" : thumb;
        p.savedAt = System.currentTimeMillis();
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
        if (probe == null) return null;
        List<float[]> known = new ArrayList<>();
        List<Person> who = new ArrayList<>();
        for (Person p : people) if (p.face.length == probe.length) { known.add(p.face); who.add(p); }
        PersonDescriptor.Match m = PersonDescriptor.best(probe, known,
                PersonDescriptor.DEFAULT_THRESHOLD, PersonDescriptor.MARGIN);
        return m.index < 0 ? null : who.get(m.index);
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
                p.thumb = o.optString("thumb", "");
                p.savedAt = o.optLong("saved_at");
                p.voiceSavedAt = o.optLong("voice_saved_at");
                p.face = floats(o.optJSONArray("face"));
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
                arr.put(new JSONObject().put("id", p.id).put("name", p.name).put("thumb", p.thumb)
                        .put("saved_at", p.savedAt).put("voice_saved_at", p.voiceSavedAt)
                        .put("face", json(p.face)).put("voice", json(p.voice)));
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
        } catch (Exception e) {
            Log.w(TAG, "vault save failed: " + e.getClass().getSimpleName());
        }
    }

    private static JSONArray json(float[] v) throws Exception {
        JSONArray a = new JSONArray();
        for (float f : v) a.put((double) f);
        return a;
    }

    private static float[] floats(JSONArray a) {
        if (a == null) return new float[0];
        float[] v = new float[a.length()];
        for (int i = 0; i < v.length; i++) v[i] = (float) a.optDouble(i);
        return v;
    }
}
