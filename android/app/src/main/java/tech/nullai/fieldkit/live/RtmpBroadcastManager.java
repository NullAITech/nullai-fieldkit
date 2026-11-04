package tech.nullai.fieldkit.live;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.IOException;
import java.util.Locale;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import tech.nullai.fieldkit.R;

/**
 * RTMP Live Broadcast Manager for streaming Ray-Ban Meta glasses POV directly to
 * X (Twitter), Twitch, YouTube Live, or OBS Studio.
 */
public final class RtmpBroadcastManager {

    private static final String TAG = "RtmpBroadcast";
    private static final String PREF_NAME = "fieldkit_rtmp";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final MediaType JPEG = MediaType.parse("image/jpeg");

    private static volatile RtmpBroadcastManager INSTANCE;

    public static RtmpBroadcastManager get(Context ctx) {
        if (INSTANCE == null) {
            synchronized (RtmpBroadcastManager.class) {
                if (INSTANCE == null) {
                    INSTANCE = new RtmpBroadcastManager(ctx.getApplicationContext());
                }
            }
        }
        return INSTANCE;
    }

    private final Context appCtx;
    private final SharedPreferences prefs;
    private final OkHttpClient http = new OkHttpClient();
    private final Handler main = new Handler(Looper.getMainLooper());

    private volatile boolean isLive = false;
    private volatile String activePlatform = "";
    private volatile String activeDestination = "";
    private volatile long broadcastStartedMs = 0L;
    private volatile long lastFramePushedMs = 0L;

    public interface StatusCallback {
        void onResult(boolean active, String platform, String detail);
    }

    private RtmpBroadcastManager(Context ctx) {
        this.appCtx = ctx;
        this.prefs = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public boolean isLive() {
        return isLive;
    }

    public String getActivePlatform() {
        return activePlatform;
    }

    public String getButtonBadgeText() {
        if (!isLive) return "📡 RTMP";
        return "🔴 " + (activePlatform.isEmpty() ? "LIVE" : activePlatform.toUpperCase(Locale.ROOT));
    }

    /**
     * Checks status from relay server.
     */
    public void checkStatusAsync(String relayBase, StatusCallback cb) {
        if (relayBase == null || relayBase.isEmpty()) return;
        String url = relayBase.replaceAll("/+$", "") + "/v1/rtmp";
        Request req = new Request.Builder().url(url).get().build();
        http.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                if (cb != null) main.post(() -> cb.onResult(isLive, activePlatform, e.getMessage()));
            }

            @Override public void onResponse(Call call, Response resp) throws IOException {
                try {
                    String body = resp.body() != null ? resp.body().string() : "{}";
                    JSONObject json = new JSONObject(body);
                    boolean active = json.optBoolean("active", false);
                    String preset = json.optString("preset", "");
                    isLive = active;
                    activePlatform = preset;
                    if (cb != null) main.post(() -> cb.onResult(active, preset, json.optString("destination", "")));
                } catch (Exception e) {
                    if (cb != null) main.post(() -> cb.onResult(isLive, activePlatform, e.getMessage()));
                } finally {
                    resp.close();
                }
            }
        });
    }

    /**
     * Starts RTMP broadcast.
     */
    public void startBroadcast(String relayBase, String token, String preset, String streamKey,
                               String customUrl, int fps, String bitrate, StatusCallback cb) {
        String base = relayBase.replaceAll("/+$", "");
        String url = base + "/v1/rtmp/start";

        try {
            JSONObject body = new JSONObject();
            if (preset != null && !preset.isEmpty()) body.put("preset", preset);
            if (streamKey != null && !streamKey.isEmpty()) body.put("stream_key", streamKey);
            if (customUrl != null && !customUrl.isEmpty()) body.put("destination", customUrl);
            body.put("fps", fps);
            body.put("bitrate", bitrate);

            Request.Builder b = new Request.Builder()
                    .url(url)
                    .post(RequestBody.create(body.toString(), JSON));
            if (token != null && !token.isEmpty()) {
                b.addHeader("Authorization", "Bearer " + token);
            }

            http.newCall(b.build()).enqueue(new Callback() {
                @Override public void onFailure(Call call, IOException e) {
                    main.post(() -> {
                        isLive = false;
                        if (cb != null) cb.onResult(false, preset, "Failed: " + e.getMessage());
                    });
                }

                @Override public void onResponse(Call call, Response resp) throws IOException {
                    try {
                        String resStr = resp.body() != null ? resp.body().string() : "{}";
                        JSONObject json = new JSONObject(resStr);
                        boolean ok = resp.isSuccessful() && json.optBoolean("ok", false);
                        if (ok) {
                            isLive = true;
                            activePlatform = preset.isEmpty() ? "custom" : preset;
                            activeDestination = json.optString("destination", "");
                            broadcastStartedMs = System.currentTimeMillis();
                            // Save successful settings
                            prefs.edit()
                                    .putString("last_preset", preset)
                                    .putString("key_" + preset, streamKey)
                                    .putInt("fps", fps)
                                    .putString("bitrate", bitrate)
                                    .apply();
                        } else {
                            isLive = false;
                        }
                        String detail = json.optString("detail", json.optString("destination", resp.message()));
                        main.post(() -> {
                            if (cb != null) cb.onResult(isLive, activePlatform, detail);
                        });
                    } catch (Exception e) {
                        main.post(() -> {
                            isLive = false;
                            if (cb != null) cb.onResult(false, preset, "Parse error: " + e.getMessage());
                        });
                    } finally {
                        resp.close();
                    }
                }
            });
        } catch (Exception e) {
            if (cb != null) cb.onResult(false, preset, e.getMessage());
        }
    }

    /**
     * Starts broadcast using saved preset and stream key.
     */
    public void startActiveBroadcast(String relayBase, String token, StatusCallback cb) {
        String savedPreset = prefs.getString("last_preset", "x");
        String key = prefs.getString("key_" + savedPreset, "");
        int fps = prefs.getInt("fps", 24);
        String bitrate = prefs.getString("bitrate", "2500k");
        startBroadcast(relayBase, token, savedPreset, key, "", fps, bitrate, cb);
    }

    /**
     * Stops RTMP broadcast.
     */
    public void stopBroadcast(String relayBase, String token, StatusCallback cb) {
        String base = relayBase.replaceAll("/+$", "");
        String url = base + "/v1/rtmp/stop";

        Request.Builder b = new Request.Builder()
                .url(url)
                .post(RequestBody.create(new byte[0], null));
        if (token != null && !token.isEmpty()) {
            b.addHeader("Authorization", "Bearer " + token);
        }

        http.newCall(b.build()).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                main.post(() -> {
                    isLive = false;
                    if (cb != null) cb.onResult(false, "", "Stop failed: " + e.getMessage());
                });
            }

            @Override public void onResponse(Call call, Response resp) {
                isLive = false;
                activePlatform = "";
                activeDestination = "";
                main.post(() -> {
                    if (cb != null) cb.onResult(false, "", "Broadcast stopped.");
                });
                resp.close();
            }
        });
    }

    /**
     * Pushes a frame to the relay for RTMP ingest and OBS MJPEG feed (throttled to ~20 fps).
     */
    public void pushFrameAsync(String relayBase, String token, byte[] jpeg) {
        if (jpeg == null || jpeg.length == 0 || relayBase == null || relayBase.isEmpty()) return;
        long now = System.currentTimeMillis();
        if (now - lastFramePushedMs < 45L) return; // Cap at ~22 fps
        lastFramePushedMs = now;

        String url = relayBase.replaceAll("/+$", "") + "/v1/camera/frame";
        Request.Builder b = new Request.Builder()
                .url(url)
                .post(RequestBody.create(jpeg, JPEG));
        if (token != null && !token.isEmpty()) {
            b.addHeader("Authorization", "Bearer " + token);
        }

        http.newCall(b.build()).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {}
            @Override public void onResponse(Call call, Response resp) {
                resp.close();
            }
        });
    }

    /**
     * Displays the complete Live Broadcast Studio modal dialog.
     */
    public void showBroadcastDialog(Activity activity, String relayBase, String token, Runnable onStatusChanged) {
        final AlertDialog[] dialogHolder = new AlertDialog[1];
        String savedPreset = prefs.getString("last_preset", "x");
        final String[] selectedPreset = new String[]{savedPreset};

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF060913);
        root.setPadding(dp(activity, 20), dp(activity, 20), dp(activity, 20), dp(activity, 20));

        // Header Title
        TextView title = new TextView(activity);
        title.setText("📡 LIVE BROADCAST STUDIO");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(17f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView sub = new TextView(activity);
        sub.setText("Stream live Ray-Ban Meta glasses POV directly to X, Twitch, YouTube, or OBS.");
        sub.setTextColor(0xFF94A3B8);
        sub.setTextSize(12f);
        sub.setPadding(0, dp(activity, 4), 0, dp(activity, 14));
        root.addView(sub);

        // Platform Presets Bar
        TextView platformLabel = label(activity, "DESTINATION PLATFORM");
        root.addView(platformLabel);

        LinearLayout presetRow = new LinearLayout(activity);
        presetRow.setOrientation(LinearLayout.HORIZONTAL);
        presetRow.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));

        String[][] presetsData = new String[][]{
                {"x", "𝕏 X Live"},
                {"twitch", "👾 Twitch"},
                {"youtube", "▶ YouTube"},
                {"obs", "🎬 OBS"}
        };

        Button[] presetButtons = new Button[4];
        final EditText etKey = new EditText(activity);

        for (int i = 0; i < presetsData.length; i++) {
            final String pKey = presetsData[i][0];
            final String pLabel = presetsData[i][1];
            Button b = new Button(activity);
            b.setText(pLabel);
            b.setTextSize(10.5f);
            b.setTypeface(Typeface.DEFAULT_BOLD);
            b.setAllCaps(false);
            b.setSingleLine(true);
            b.setPadding(0, 0, 0, 0);
            b.setMinWidth(0);
            b.setMinimumWidth(0);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(activity, 38), 1f);
            if (i < 3) lp.rightMargin = dp(activity, 4);
            b.setLayoutParams(lp);
            presetButtons[i] = b;

            b.setOnClickListener(v -> {
                selectedPreset[0] = pKey;
                for (Button pb : presetButtons) {
                    pb.setBackgroundResource(R.drawable.bg_dark_pill);
                    pb.setTextColor(0xFFCBD5E1);
                }
                b.setBackgroundResource(R.drawable.bg_accent_emerald);
                b.setTextColor(0xFF34D399);
                etKey.setText(prefs.getString("key_" + pKey, ""));
            });
            presetRow.addView(b);
        }
        root.addView(presetRow);

        // Stream Key Input
        TextView keyLabel = label(activity, "STREAM KEY");
        keyLabel.setPadding(0, dp(activity, 12), 0, dp(activity, 4));
        root.addView(keyLabel);

        etKey.setHint("Paste your stream key here");
        etKey.setHintTextColor(0xFF64748B);
        etKey.setTextColor(0xFFFFFFFF);
        etKey.setTextSize(13f);
        etKey.setBackgroundColor(0xFF0F172A);
        etKey.setPadding(dp(activity, 12), dp(activity, 10), dp(activity, 12), dp(activity, 10));
        etKey.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        etKey.setText(prefs.getString("key_" + selectedPreset[0], ""));
        root.addView(etKey);

        // OBS Direct Browser URL Helper
        LinearLayout obsBox = new LinearLayout(activity);
        obsBox.setOrientation(LinearLayout.VERTICAL);
        obsBox.setBackgroundColor(0xFF0F172A);
        obsBox.setPadding(dp(activity, 12), dp(activity, 10), dp(activity, 12), dp(activity, 10));
        LinearLayout.LayoutParams obsLp = new LinearLayout.LayoutParams(-1, -2);
        obsLp.topMargin = dp(activity, 12);
        obsBox.setLayoutParams(obsLp);

        TextView obsTitle = new TextView(activity);
        obsTitle.setText("🎬 OBS Studio Direct Browser / Media Source URL:");
        obsTitle.setTextColor(0xFF38BDF8);
        obsTitle.setTextSize(11.5f);
        obsTitle.setTypeface(Typeface.DEFAULT_BOLD);
        obsBox.addView(obsTitle);

        final String obsUrl = relayBase.replaceAll("/+$", "") + "/v1/camera/live.mjpg";
        final String obsOverlayUrl = relayBase.replaceAll("/+$", "") + "/overlay?token=" + (token != null ? token : "");

        TextView obsLink = new TextView(activity);
        obsLink.setText(obsUrl);
        obsLink.setTextColor(0xFFFDE68A);
        obsLink.setTextSize(11f);
        obsLink.setTypeface(Typeface.MONOSPACE);
        obsBox.addView(obsLink);

        Button btnCopyObs = new Button(activity);
        btnCopyObs.setText("📋 Copy OBS Live Feed URL");
        btnCopyObs.setTextSize(11f);
        btnCopyObs.setAllCaps(false);
        btnCopyObs.setTextColor(0xFFFFFFFF);
        btnCopyObs.setBackgroundResource(R.drawable.bg_dark_pill);
        btnCopyObs.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(activity, 34)));
        btnCopyObs.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("OBS URL", obsUrl));
                Toast.makeText(activity, "Copied OBS Video Feed URL to clipboard!", Toast.LENGTH_SHORT).show();
            }
        });
        obsBox.addView(btnCopyObs);
        root.addView(obsBox);

        // Status Banner
        final TextView statusTv = new TextView(activity);
        statusTv.setText(isLive ? "🔴 BROADCASTING LIVE" : "⚪ Stream idle — ready to broadcast");
        statusTv.setTextColor(isLive ? 0xFFFCA5A5 : 0xFF94A3B8);
        statusTv.setTextSize(12f);
        statusTv.setTypeface(Typeface.DEFAULT_BOLD);
        statusTv.setGravity(Gravity.CENTER);
        statusTv.setPadding(0, dp(activity, 12), 0, dp(activity, 12));
        root.addView(statusTv);

        // Action Buttons Row
        LinearLayout btnRow = new LinearLayout(activity);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));

        final Button btnStart = new Button(activity);
        btnStart.setText(isLive ? "⏹ Stop Broadcast" : "▶ Go Live (Start)");
        btnStart.setTextColor(0xFFFFFFFF);
        btnStart.setTextSize(13f);
        btnStart.setTypeface(Typeface.DEFAULT_BOLD);
        btnStart.setAllCaps(false);
        btnStart.setBackgroundResource(isLive ? R.drawable.bg_accent_rose : R.drawable.bg_accent_emerald);
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(0, dp(activity, 44), 1f);
        sLp.rightMargin = dp(activity, 8);
        btnStart.setLayoutParams(sLp);

        btnStart.setOnClickListener(v -> {
            if (isLive) {
                btnStart.setEnabled(false);
                btnStart.setText("Stopping…");
                stopBroadcast(relayBase, token, (active, platform, detail) -> {
                    btnStart.setEnabled(true);
                    btnStart.setText("▶ Go Live (Start)");
                    btnStart.setBackgroundResource(R.drawable.bg_accent_emerald);
                    statusTv.setText("⚪ Stream stopped.");
                    statusTv.setTextColor(0xFF94A3B8);
                    if (onStatusChanged != null) onStatusChanged.run();
                });
            } else {
                String key = etKey.getText().toString().trim();
                if (key.isEmpty()) {
                    Toast.makeText(activity, "Please enter your Stream Key first", Toast.LENGTH_SHORT).show();
                    return;
                }
                btnStart.setEnabled(false);
                btnStart.setText("Connecting…");
                startBroadcast(relayBase, token, selectedPreset[0], key, "", 20, "2500k", (active, platform, detail) -> {
                    btnStart.setEnabled(true);
                    if (active) {
                        btnStart.setText("⏹ Stop Broadcast");
                        btnStart.setBackgroundResource(R.drawable.bg_accent_rose);
                        statusTv.setText("🔴 LIVE ON " + platform.toUpperCase(Locale.ROOT) + " · 20 FPS");
                        statusTv.setTextColor(0xFFFCA5A5);
                        Toast.makeText(activity, "Streaming Live to " + platform.toUpperCase(Locale.ROOT) + "!", Toast.LENGTH_SHORT).show();
                    } else {
                        btnStart.setText("▶ Go Live (Start)");
                        btnStart.setBackgroundResource(R.drawable.bg_accent_emerald);
                        statusTv.setText("❌ Failed: " + detail);
                        statusTv.setTextColor(0xFFF87171);
                    }
                    if (onStatusChanged != null) onStatusChanged.run();
                });
            }
        });
        btnRow.addView(btnStart);

        Button btnClose = new Button(activity);
        btnClose.setText("Close");
        btnClose.setTextColor(0xFFCBD5E1);
        btnClose.setTextSize(13f);
        btnClose.setAllCaps(false);
        btnClose.setBackgroundResource(R.drawable.bg_dark_pill);
        btnClose.setLayoutParams(new LinearLayout.LayoutParams(dp(activity, 80), dp(activity, 44)));
        btnClose.setOnClickListener(v -> {
            if (dialogHolder[0] != null) dialogHolder[0].dismiss();
        });
        btnRow.addView(btnClose);

        root.addView(btnRow);

        // Select initial preset button
        for (int i = 0; i < presetsData.length; i++) {
            if (presetsData[i][0].equals(selectedPreset[0])) {
                presetButtons[i].setBackgroundResource(R.drawable.bg_accent_emerald);
                presetButtons[i].setTextColor(0xFF34D399);
            } else {
                presetButtons[i].setBackgroundResource(R.drawable.bg_dark_pill);
                presetButtons[i].setTextColor(0xFFCBD5E1);
            }
        }

        AlertDialog d = new AlertDialog.Builder(activity)
                .setView(root)
                .create();
        dialogHolder[0] = d;
        d.show();
    }

    private static TextView label(Context ctx, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextColor(0xFF94A3B8);
        tv.setTextSize(11f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setPadding(0, dp(ctx, 4), 0, dp(ctx, 4));
        return tv;
    }

    private static int dp(Context ctx, float v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }
}
