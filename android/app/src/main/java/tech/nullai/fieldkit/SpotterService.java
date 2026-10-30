package tech.nullai.fieldkit;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/**
 * Keeps a Spotter session alive with the screen locked or the phone asleep.
 *
 * Why it exists: a FieldKit session is a long-lived WebSocket to the relay plus
 * a live camera stream. As a plain background activity Android tears all of that
 * down the moment the screen locks. The relay link (Tailscale) is supposed to be
 * the only thing whose loss ends a session, so the session has to be owned by a
 * foreground service instead:
 *
 *   - type camera + microphone, because a phone-camera session keeps capturing
 *     YUV frames with the screen off and the subconscious layer keeps listening;
 *   - a PARTIAL_WAKE_LOCK so the CPU does not suspend and the socket stays warm;
 *   - a WifiLock so the radio stays on the Tailscale path instead of dozing.
 *
 * The notification is the honest status line: it shows the last thing the app
 * reported, and tapping it returns to the app.
 */
public class SpotterService extends Service {

    private static final String CHANNEL_ID = "spotter_session";
    private static final int NOTIF_ID = 8770;
    private static final String EXTRA_TEXT = "text";
    private static final String ACTION_STOP = "tech.nullai.fieldkit.action.STOP";

    /** Mirrors service state so the activity knows what survives a screen lock. */
    private static volatile boolean alive = false;
    private static volatile String lastText = "";

    public static boolean isAlive() {
        return alive;
    }

    public static String lastText() {
        return lastText;
    }

    /** Bring the session up (or refresh it). Safe to call repeatedly. */
    public static void start(Context ctx, String text) {
        if (ctx == null) return;
        Intent i = new Intent(ctx, SpotterService.class).putExtra(EXTRA_TEXT, text);
        try {
            ctx.startForegroundService(i);
        } catch (Throwable ignored) {
            // Background-start restrictions: the caller is expected to be visible.
        }
    }

    /**
     * Update the status line of a session that is already running.
     * Uses startService rather than startForegroundService: the service is already in the
     * foreground, so this is not a background foreground-service start (which Android 12+
     * would refuse when the app is off-screen).
     */
    public static void update(Context ctx, String text) {
        if (!alive || ctx == null) return;
        Intent i = new Intent(ctx, SpotterService.class).putExtra(EXTRA_TEXT, text);
        try {
            ctx.startService(i);
        } catch (Throwable ignored) {
        }
    }

    public static void stop(Context ctx) {
        if (ctx == null) return;
        try {
            ctx.stopService(new Intent(ctx, SpotterService.class));
        } catch (Throwable ignored) {
        }
    }

    private PowerManager.WakeLock wake;
    private WifiManager.WifiLock wifi;

    @Override
    public void onCreate() {
        super.onCreate();
        alive = true;

        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null) {
                wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "spotter:session");
                wake.setReferenceCounted(false);
                wake.acquire();
            }
        } catch (Throwable ignored) {
        }

        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wm != null) {
                wifi = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "spotter:wifi");
                wifi.setReferenceCounted(false);
                wifi.acquire();
            }
        } catch (Throwable ignored) {
        }

        ensureChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }

        String text = intent == null ? null : intent.getStringExtra(EXTRA_TEXT);
        if (text == null || text.trim().isEmpty()) text = "Spotter is observing.";
        lastText = text;

        Notification n = buildNotification(text);
        boolean shown = false;
        if (Build.VERSION.SDK_INT >= 34) {
            int types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                    | ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    | ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                    | ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
            try {
                startForeground(NOTIF_ID, n, types);
                shown = true;
            } catch (Throwable ignored) {
                // e.g. a runtime permission for one of the types is missing.
            }
        }
        if (!shown) {
            try {
                startForeground(NOTIF_ID, n);
            } catch (Throwable ignored) {
            }
        }
        // Not sticky: restarting a camera/microphone foreground service from the
        // background is not permitted, and a dead relay link is not recoverable
        // from inside the service anyway.
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        alive = false;
        try {
            if (wake != null && wake.isHeld()) wake.release();
        } catch (Throwable ignored) {
        }
        try {
            if (wifi != null && wifi.isHeld()) wifi.release();
        } catch (Throwable ignored) {
        }
        wake = null;
        wifi = null;
        try {
            stopForeground(true);
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return;
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Spotter session", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Shown while Spotter is observing with the screen off.");
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        } catch (Throwable ignored) {
        }
    }

    private Notification buildNotification(String text) {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("Spotter")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setContentIntent(returnIntent())
                .addAction(0, "Stop", stopIntent());
        return b.build();
    }

    private PendingIntent returnIntent() {
        Intent i = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getActivity(this, 0, i, flags);
    }

    private PendingIntent stopIntent() {
        Intent i = new Intent(this, SpotterService.class).setAction(ACTION_STOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getService(this, 1, i, flags);
    }
}
