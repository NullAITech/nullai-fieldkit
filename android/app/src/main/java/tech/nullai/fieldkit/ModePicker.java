package tech.nullai.fieldkit;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.widget.EditText;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;

/**
 * Goal-mode onboarding (built Nov 2 2026 at the hackathon): a quick guided
 * follow-through asking what Spotter is for today — network & meet people,
 * repair/fix this, adventure/"tell me where to go", or custom — plus one or
 * two follow-up questions. Saved locally and POSTed to the relay's /v1/mode,
 * which folds it into the Guide/voice prompt and the mission planner.
 * Mirrors zoth_relay/modes.py (keys and questions).
 */
public final class ModePicker {
    static final String[][] MODES = {
        {"network", "Network & meet people", "Where are you (event, bar, campus, conference)?", "What kind of people do you want to meet?"},
        {"repair", "Repair / fix this", "What are you fixing (make/model if you know)?", "What is it doing wrong?"},
        {"adventure", "Adventure / tell me where to go", "Walking, biking or driving?", "What sounds fun: food, views, history, nightlife, nature?"},
        {"custom", "Custom", "In one sentence, what do you want Spotter to help with?"},
    };

    public interface Done { void onMode(String key, String label); }

    private ModePicker() { }

    public static String label(SharedPreferences p) {
        return p.getString("goal_mode_label", "");
    }

    public static void show(Activity a, OkHttpClient http, String base, String token, Done done) {
        String[] labels = new String[MODES.length];
        for (int i = 0; i < MODES.length; i++) labels[i] = MODES[i][1];
        new AlertDialog.Builder(a)
                .setTitle("What should Spotter help with today?")
                .setItems(labels, (d, which) -> ask(a, http, base, token, MODES[which], 2, new ArrayList<>(), done))
                .setNegativeButton("Later", null)
                .show();
    }

    private static void ask(Activity a, OkHttpClient http, String base, String token, String[] mode,
                            int q, List<String> answers, Done done) {
        if (q >= mode.length) { save(a, http, base, token, mode, answers, done); return; }
        EditText et = new EditText(a);
        et.setSingleLine(true);
        new AlertDialog.Builder(a)
                .setTitle(mode[1])
                .setMessage(mode[q])
                .setView(et)
                .setPositiveButton("Next", (d, w) -> {
                    answers.add(et.getText().toString().trim());
                    ask(a, http, base, token, mode, q + 1, answers, done);
                })
                .setNeutralButton("Skip", (d, w) -> {
                    answers.add("");
                    ask(a, http, base, token, mode, q + 1, answers, done);
                })
                .show();
    }

    private static void save(Activity a, OkHttpClient http, String base, String token, String[] mode,
                             List<String> answers, Done done) {
        boolean map = "adventure".equals(mode[0]);
        String goal = "custom".equals(mode[0]) && !answers.isEmpty() ? answers.get(0) : "";
        a.getSharedPreferences("fieldkit", Activity.MODE_PRIVATE).edit()
                .putString("goal_mode", mode[0]).putString("goal_mode_label", mode[1])
                .putBoolean("map_hud", map).apply();
        if (done != null) done.onMode(mode[0], mode[1]);
        if (base == null || base.isEmpty() || token == null || token.isEmpty()) return;
        try {
            JSONObject body = new JSONObject().put("mode", mode[0]).put("goal", goal)
                    .put("answers", new JSONArray(answers)).put("map_hud", map);
            Request r = new Request.Builder().url(base + "/v1/mode")
                    .header("Authorization", "Bearer " + token)
                    .post(RequestBody.create(body.toString(), MediaType.get("application/json")))
                    .build();
            http.newCall(r).enqueue(new okhttp3.Callback() {
                @Override public void onFailure(okhttp3.Call c, java.io.IOException e) { }
                @Override public void onResponse(okhttp3.Call c, okhttp3.Response resp) { resp.close(); }
            });
        } catch (Exception ignored) { }
    }
}
