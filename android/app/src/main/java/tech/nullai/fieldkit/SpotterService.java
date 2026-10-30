package tech.nullai.fieldkit;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;

import tech.nullai.fieldkit.voice.LockedListener;
import tech.nullai.fieldkit.voice.OwnerPrintPrefs;
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
    private static final String ACTION_LISTEN_ON = "tech.nullai.fieldkit.action.LISTEN_ON";
    private static final String ACTION_LISTEN_OFF = "tech.nullai.fieldkit.action.LISTEN_OFF";

    /** Mirrors service state so the activity knows what survives a screen lock. */
    private static volatile boolean alive = false;
    private static volatile String lastText = "";
    /** Last utterance the locked listener gated in, as one honest line. Empty until then. */
    private static volatile String lastHeard = "";
    private static volatile int heardCount = 0;
    private static volatile int discardedCount = 0;

    public static boolean isAlive() {
        return alive;
    }

    public static String lastText() {
        return lastText;
    }

    /** What the locked listener last heard, or "" when it has heard nothing yet. */
    public static String lastHeard() {
        return lastHeard;
    }

    /** Utterances accepted by the print gate and kept; and utterances dropped as not-you. */
    public static int heardCount() {
        return heardCount;
    }

    public static int discardedCount() {
        return discardedCount;
    }

    /**
     * Hand the microphone to the locked listener, or give it back.
     *
     * There is ONE microphone: while the screen is on, the one-shot capture behind the
     * Talk button needs it, so the activity calls this with false on resume and true when
     * it goes to the background. A record opened twice does not fail loudly - it returns
     * an error from read() - so the handover has to be explicit rather than hoped for.
     * Unknown action with no intent: start listening, which is what a fresh session does.
     */
    public static void setListening(Context ctx, boolean on) {
        if (!alive || ctx == null) return;
        Intent i = new Intent(ctx, SpotterService.class)
                .setAction(on ? ACTION_LISTEN_ON : ACTION_LISTEN_OFF);
        try {
            ctx.startService(i);
        } catch (Throwable ignored) {
        }
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
    /** Reads the microphone with the screen off. Null whenever it is not wanted. */
    private LockedListener listener;

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
        startListening();
    }

    /**
     * Start the locked listener, or explain in the notification why it is not running.
     * A missing microphone permission is a normal state, not a crash: the app still works
     * with the screen on, it just cannot hear you with the screen off.
     */
    private void startListening() {
        if (listener != null && listener.isRunning()) return;
        if (!LockedListener.available(this)) {
            updateNotice("Spotter is observing (screen-off listening needs the microphone permission).");
            return;
        }
        // The gate is reloaded from disk rather than shared with the activity: the service
        // can be alive when the activity is gone, and the print is the one owned by both.
        listener = LockedListener.start(this, OwnerPrintPrefs.load(this), r -> {
            if (r != null && r.wearer) {
                heardCount++;
                lastHeard = r.describe() + (r.wav == null ? "" : ", kept " + r.wav.getName());
            } else {
                discardedCount++;
                lastHeard = r == null ? "discarded" : r.describe();
            }
            updateNotice(noticeLine());
        });
        if (listener == null) {
            updateNotice("Spotter is observing (the microphone is busy - listening starts when it frees up).");
        } else {
            updateNotice(noticeLine());
        }
    }

    private void stopListening() {
        LockedListener l = listener;
        listener = null;
        if (l != null) l.stop();
    }

    /**
     * The status line. States plainly whether the locked listener is up, because
     * "it hears you in your pocket" is only true when this says it is.
     */
    private String noticeLine() {
        String base = "Spotter is observing.";
        if (listener != null && listener.isRunning()) {
            base = "Listening with the screen off - " + heardCount + " kept, "
                    + listener.rejected() + " not you.";
        }
        if (!lastHeard.isEmpty()) base = base + " Last: " + lastHeard;
        return base;
    }

    /** Rewrite the foreground notification without going through a new start command. */
    private void updateNotice(String text) {
        lastText = text;
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIF_ID, buildNotification(text));
        } catch (Throwable ignored) {
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_LISTEN_OFF.equals(intent.getAction())) {
            stopListening();
            updateNotice("Spotter is observing (screen-off listening paused - the app is in front).");
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_LISTEN_ON.equals(intent.getAction())) {
            startListening();
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
        stopListening();
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
