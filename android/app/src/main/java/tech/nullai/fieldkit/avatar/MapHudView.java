package tech.nullai.fieldkit.avatar;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.View;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

/**
 * Mini-map HUD + compass (built Nov 2 2026 at the hackathon). Heading comes
 * from the rotation-vector sensor, position from GPS/network location; both
 * are pushed into maphud.js (shared with /pwa). Tiles: openstreetmap.org, no
 * API key; "© OpenStreetMap contributors" is always shown. Visible when the
 * goal mode turns the HUD on (pref "map_hud"); long-press hides it.
 */
public class MapHudView extends WebView implements SensorEventListener, LocationListener {
    private boolean ready, running, locOn, asked;
    private float heading = -1f;
    private long lastHeadingPush;
    private final float[] rot = new float[9], ori = new float[3];

    public MapHudView(Context c) { this(c, null); }

    @SuppressLint("SetJavaScriptEnabled")
    public MapHudView(Context c, AttributeSet a) {
        super(c, a);
        setBackgroundColor(Color.TRANSPARENT);
        getSettings().setJavaScriptEnabled(true);
        setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView v, String url) { ready = true; }
        });
        setOnLongClickListener(v -> {
            prefs().edit().putBoolean("map_hud", false).apply();
            refresh();
            Toast.makeText(getContext(), "Map HUD hidden — turn it on in Goal mode", Toast.LENGTH_SHORT).show();
            return true;
        });
        loadUrl("file:///android_asset/presence/maphud.html");
    }

    private android.content.SharedPreferences prefs() {
        return getContext().getSharedPreferences("fieldkit", Context.MODE_PRIVATE);
    }

    /** Show/hide per prefs; call from onResume. */
    public void refresh() {
        boolean on = prefs().getBoolean("map_hud", false);
        setVisibility(on ? View.VISIBLE : View.GONE);
        if (on) start(); else stop();
    }

    @SuppressLint("MissingPermission")
    private void start() {
        if (running) return;
        running = true;
        SensorManager sm = (SensorManager) getContext().getSystemService(Context.SENSOR_SERVICE);
        Sensor rv = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        if (rv != null) sm.registerListener(this, rv, SensorManager.SENSOR_DELAY_UI);
        startLocation();
    }

    @SuppressLint("MissingPermission")
    private void startLocation() {
        if (locOn || !running) return;
        Context c = getContext();
        if (c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            if (!asked && c instanceof Activity) ((Activity) c).requestPermissions(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, 7301);
            asked = true;
            return; // compass still works; GPS starts on the next refresh() after grant
        }
        LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return;
        locOn = true;
        for (String p : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
            try {
                if (lm.isProviderEnabled(p)) {
                    lm.requestLocationUpdates(p, 3000L, 3f, this, Looper.getMainLooper());
                    Location last = lm.getLastKnownLocation(p);
                    if (last != null) onLocationChanged(last);
                }
            } catch (Exception ignored) { }
        }
    }

    public void stop() {
        if (!running) return;
        running = false;
        locOn = false;
        SensorManager sm = (SensorManager) getContext().getSystemService(Context.SENSOR_SERVICE);
        if (sm != null) sm.unregisterListener(this);
        LocationManager lm = (LocationManager) getContext().getSystemService(Context.LOCATION_SERVICE);
        try { if (lm != null) lm.removeUpdates(this); } catch (Exception ignored) { }
    }

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); refresh(); }

    @Override public void onWindowFocusChanged(boolean f) {
        super.onWindowFocusChanged(f);
        if (f) { refresh(); startLocation(); }  // picks up a just-granted location permission
    }

    @Override protected void onDetachedFromWindow() { stop(); super.onDetachedFromWindow(); }

    @Override public void onSensorChanged(SensorEvent e) {
        SensorManager.getRotationMatrixFromVector(rot, e.values);
        SensorManager.getOrientation(rot, ori);
        float h = (float) ((Math.toDegrees(ori[0]) + 360.0) % 360.0);
        long now = System.currentTimeMillis();
        if (now - lastHeadingPush < 66 || (Math.abs(h - heading) < 1f)) return;
        heading = h; lastHeadingPush = now;
        if (ready) evaluateJavascript(String.format(java.util.Locale.US,
                "window.maphud&&maphud.setHeading(%.1f)", h), null);
    }

    @Override public void onAccuracyChanged(Sensor s, int a) { }

    @Override public void onLocationChanged(Location l) {
        if (ready) evaluateJavascript(String.format(java.util.Locale.US,
                "window.maphud&&maphud.setPosition(%.6f,%.6f,%.0f)",
                l.getLatitude(), l.getLongitude(), (double) l.getAccuracy()), null);
    }

    /** Last compass heading in degrees, or -1 if none yet. */
    public float heading() { return heading; }
}
