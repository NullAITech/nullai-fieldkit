package tech.nullai.fieldkit.voice;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Where the enrolled print lives on disk, so the service can load it without the activity.
 * {@code MODE_PRIVATE} - the print is the wearer's voice and stays inside the app.
 */
public final class OwnerPrintPrefs {

    /** The only key this file writes. Kept identical to the original activity key. */
    public static final String KEY = "owner_print";
    public static final String FILE = "fieldkit";

    private OwnerPrintPrefs() {
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** The gate, enrolled when a usable print is stored, otherwise not enrolled. */
    public static SpeakerGate load(Context ctx) {
        if (ctx == null) return new SpeakerGate();
        try {
            return OwnerPrintStore.gate(prefs(ctx).getString(KEY, ""));
        } catch (Throwable t) {
            return new SpeakerGate();
        }
    }

    public static void save(Context ctx, float[][] takes) {
        if (ctx == null) return;
        try {
            prefs(ctx).edit().putString(KEY, OwnerPrintStore.encode(takes)).apply();
        } catch (Throwable ignored) {
        }
    }

    public static void forget(Context ctx) {
        if (ctx == null) return;
        try {
            prefs(ctx).edit().remove(KEY).apply();
        } catch (Throwable ignored) {
        }
    }
}
