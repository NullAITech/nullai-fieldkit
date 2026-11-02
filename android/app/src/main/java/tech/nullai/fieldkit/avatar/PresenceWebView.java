package tech.nullai.fieldkit.avatar;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.util.AttributeSet;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

/**
 * Spotter Presence (built Nov 2 2026 at the hackathon): the animated AI face
 * shared with the /pwa — braille, ASCII, Bayer-dither or lattice rendering of
 * a 2D face or a 3D point-cloud head, gold on black. Mouth follows the real
 * Live-call level from {@link #setLevels(float, float)}.
 *
 * <p>Runs presence.js from app assets (copied from zoth_relay/pwa at build
 * time). Network loads are blocked; everything is local. Long-press cycles
 * the render style (saved in prefs).
 */
public class PresenceWebView extends WebView {
    static final String[] STYLES = {"braille", "ascii", "dither", "lattice"};
    private final String dim;
    private boolean ready;
    private float lastUser = -1f, lastMuse = -1f;
    private boolean speaking, lastSpeaking;

    public PresenceWebView(Context c, AttributeSet a, String dim) {
        super(c, a);
        this.dim = dim;
        init();
    }

    private SharedPreferences prefs() {
        return getContext().getSharedPreferences("fieldkit", Context.MODE_PRIVATE);
    }

    public String style() { return prefs().getString("presence_style", "braille"); }

    @SuppressLint("SetJavaScriptEnabled")
    private void init() {
        setBackgroundColor(Color.rgb(6, 7, 10));
        WebSettings s = getSettings();
        s.setJavaScriptEnabled(true);
        s.setBlockNetworkLoads(true);
        s.setAllowContentAccess(false);
        setVerticalScrollBarEnabled(false);
        setHorizontalScrollBarEnabled(false);
        setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView v, String url) {
                ready = true;
                applyStyle();
            }
        });
        setOnLongClickListener(v -> {
            String cur = style();
            int i = 0;
            for (int k = 0; k < STYLES.length; k++) if (STYLES[k].equals(cur)) i = k;
            String next = STYLES[(i + 1) % STYLES.length];
            prefs().edit().putString("presence_style", next).apply();
            applyStyle();
            Toast.makeText(getContext(), "Presence style: " + next, Toast.LENGTH_SHORT).show();
            return true;
        });
        loadUrl("file:///android_asset/presence/presence.html");
    }

    private void applyStyle() {
        if (!ready) return;
        evaluateJavascript("window.presence&&(presence.setMode('" + dim + "'),presence.setStyle('"
                + style() + "'))", null);
    }

    public void setLevels(float user, float muse) {
        if (Math.abs(user - lastUser) < 0.01f && Math.abs(muse - lastMuse) < 0.01f
                && speaking == lastSpeaking) return;
        lastUser = user; lastMuse = muse; lastSpeaking = speaking;
        if (!ready) return;
        evaluateJavascript(String.format(java.util.Locale.US,
                "window.muse&&window.muse(%.3f,%.3f,%s)", muse, user, speaking ? "true" : "false"), null);
    }

    public void setSpeaking(boolean s) { speaking = s; }

    /** Thinking/idle hint from the call state; optional. */
    public void setThinking(boolean t) {
        if (ready) evaluateJavascript("window.presence&&presence.setState('" + (t ? "thinking" : "idle") + "')", null);
    }
}
