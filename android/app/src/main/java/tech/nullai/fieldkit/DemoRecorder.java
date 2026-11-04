package tech.nullai.fieldkit;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.Log;
import android.view.Surface;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Built-in Cinema Live Demo Recorder for Spotter (Zoth FieldKit).
 *
 * <p>Records a 720x1280 @ 24fps H.264 MP4 video combining the live POV camera feed
 * (Ray-Ban Meta smart glasses or phone camera) with a clean HUD overlay showing:
 * <ul>
 *   <li>Active camera source badge (Ray-Ban Meta 01QV vs Phone Camera)</li>
 *   <li>Live elapsed recording timer vs 03:00 hackathon target</li>
 *   <li>Current Mission Step or full-duplex Muse Call status + voice levels</li>
 *   <li>Live spoken AI Coach subtitles, wearer speech, and sensor telemetry</li>
 * </ul>
 *
 * <p>Works across Activity transitions (MainActivity <-> CallActivity) without
 * interrupting the recording or requiring MediaProjection screen-capture dialogs.
 * Exports directly to Google Photos / Gallery ({@code Movies/SpotterDemos/}) and writes
 * a companion timestamped Markdown cue sheet for instant X / hackathon submission.
 */
public final class DemoRecorder {

    private static final String TAG = "DemoRecorder";
    private static final int VIDEO_W = 720;
    private static final int VIDEO_H = 1280;
    private static final int FPS = 24;
    private static final int BITRATE = 3_800_000;
    private static final int IFRAME_INTERVAL_SEC = 1;
    private static final long FRAME_INTERVAL_MS = 1000L / FPS;

    public interface UiListener {
        void onDemoStateChanged(boolean recording, String label);
    }

    private static final DemoRecorder INSTANCE = new DemoRecorder();

    public static DemoRecorder get() {
        return INSTANCE;
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService decodeExec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "demo-frame-decode");
        t.setDaemon(true);
        return t;
    });

    private volatile boolean recording = false;
    private volatile long startedAtMs = 0L;
    private volatile int encodedFrames = 0;

    // Live HUD state updated by MainActivity and CallActivity
    private volatile Bitmap latestFrameBmp;
    private volatile long latestFrameAtMs = 0L;
    private volatile String sourceLabel = "RAY-BAN META GLASSES";
    private volatile boolean glassesActive = false;
    private volatile boolean inCallMode = false;
    private volatile String stepOrCallTitle = "Ready — say or type a mission goal";
    private volatile String statusOrTranscript = "Waiting for live stream…";
    private volatile String streamTelemetry = "24 fps · low-latency relay";
    private volatile String sensorTelemetry = "";
    private volatile String presetLabel = "Get It Done";
    private volatile float userVoiceLevel = 0f;
    private volatile float museVoiceLevel = 0f;

    private final List<String> cueLog = new ArrayList<>();
    private String lastLoggedStep = "";
    private String lastLoggedStatus = "";

    private HandlerThread encoderThread;
    private Handler encoderHandler;
    private MediaCodec encoder;
    private Surface inputSurface;
    private MediaMuxer muxer;
    private int videoTrackIndex = -1;
    private boolean muxerStarted = false;
    private File tempMp4File;
    private File cueMdFile;
    private String baseFileName;
    private Context appContext;
    private volatile UiListener uiListener;

    // Reusable paints
    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cardPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint accentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bmpPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final TextPaint titlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint subPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint badgePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint stepPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint bodyPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint monoPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);

    private DemoRecorder() {
        cardPaint.setColor(0xFF141821);
        cardPaint.setStyle(Paint.Style.FILL);

        borderPaint.setColor(0xFF2A3142);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(2f);

        titlePaint.setColor(0xFFF5F7FA);
        titlePaint.setTextSize(26f);
        titlePaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));

        subPaint.setColor(0xFF9BA8BE);
        subPaint.setTextSize(15f);

        badgePaint.setColor(0xFFFFFFFF);
        badgePaint.setTextSize(16f);
        badgePaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));

        stepPaint.setColor(0xFFF5F7FA);
        stepPaint.setTextSize(24f);
        stepPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));

        bodyPaint.setColor(0xFFD6E0F0);
        bodyPaint.setTextSize(19f);

        monoPaint.setColor(0xFF8FE3B0);
        monoPaint.setTextSize(15f);
        monoPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL));
    }

    public boolean isRecording() {
        return recording;
    }

    public void setUiListener(UiListener listener) {
        this.uiListener = listener;
        if (listener != null) {
            listener.onDemoStateChanged(recording, currentButtonLabel());
        }
    }

    public String currentButtonLabel() {
        if (!recording) return "● REC Demo";
        long sec = Math.max(0L, (System.currentTimeMillis() - startedAtMs) / 1000L);
        return String.format(Locale.US, "■ %02d:%02d", sec / 60, sec % 60);
    }

    /** Feed a raw JPEG frame from DatGlasses or Phone Camera. */
    public void onJpegFrame(final byte[] jpeg, final boolean fromGlasses) {
        if (jpeg == null || jpeg.length == 0) return;
        this.glassesActive = fromGlasses;
        this.sourceLabel = fromGlasses ? "RAY-BAN META 01QV · LIVE POV" : "PHONE CAMERA · LIVE POV";
        if (!recording) return;
        decodeExec.execute(() -> {
            try {
                BitmapFactory.Options o = new BitmapFactory.Options();
                Bitmap bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, o);
                if (bmp != null) {
                    if (!fromGlasses && bmp.getWidth() > bmp.getHeight()) {
                        Matrix m = new Matrix();
                        m.postRotate(90);
                        Bitmap rot = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
                        if (rot != bmp) bmp.recycle();
                        bmp = rot;
                    }
                    latestFrameBmp = bmp;
                    latestFrameAtMs = System.currentTimeMillis();
                }
            } catch (Throwable ignored) {}
        });
    }

    /** Feed an already-decoded Bitmap (e.g. CallActivity with detection boxes drawn). */
    public void onBitmapFrame(final Bitmap bmp, final boolean fromGlasses) {
        if (bmp == null) return;
        this.glassesActive = fromGlasses;
        this.sourceLabel = fromGlasses ? "RAY-BAN META 01QV · LIVE POV" : "PHONE CAMERA · LIVE POV";
        if (!recording) return;
        try {
            Bitmap src = bmp;
            if (!fromGlasses && src.getWidth() > src.getHeight()) {
                Matrix m = new Matrix();
                m.postRotate(90);
                src = Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
            }
            latestFrameBmp = src.copy(Bitmap.Config.ARGB_8888, false);
            latestFrameAtMs = System.currentTimeMillis();
        } catch (Throwable ignored) {}
    }

    public void updateHud(boolean glassesMode,
                          boolean callMode,
                          String stepText,
                          String statusText,
                          String streamText,
                          String sensorText,
                          String preset) {
        this.glassesActive = glassesMode;
        this.inCallMode = callMode;
        this.sourceLabel = glassesMode ? "RAY-BAN META 01QV · LIVE POV" : "PHONE CAMERA · LIVE POV";
        if (stepText != null && !stepText.trim().isEmpty()) {
            this.stepOrCallTitle = stepText.trim();
            if (recording && !this.stepOrCallTitle.equals(lastLoggedStep)) {
                lastLoggedStep = this.stepOrCallTitle;
                logCue("STEP", this.stepOrCallTitle);
            }
        }
        if (statusText != null && !statusText.trim().isEmpty()) {
            this.statusOrTranscript = statusText.trim();
            if (recording && !this.statusOrTranscript.equals(lastLoggedStatus)) {
                lastLoggedStatus = this.statusOrTranscript;
                logCue("STATUS", this.statusOrTranscript);
            }
        }
        if (streamText != null && !streamText.trim().isEmpty()) {
            this.streamTelemetry = streamText.trim();
        }
        if (sensorText != null) {
            this.sensorTelemetry = sensorText.trim();
        }
        if (preset != null && !preset.trim().isEmpty()) {
            this.presetLabel = preset.trim();
        }
    }

    public void updateVoiceLevels(float user, float muse) {
        this.userVoiceLevel = user;
        this.museVoiceLevel = muse;
    }

    public void logCue(String kind, String detail) {
        if (!recording || detail == null) return;
        long elapsedMs = Math.max(0L, System.currentTimeMillis() - startedAtMs);
        long min = (elapsedMs / 1000L) / 60L;
        long sec = (elapsedMs / 1000L) % 60L;
        long tenths = (elapsedMs % 1000L) / 100L;
        String line = String.format(Locale.US, "- `%02d:%02d.%d` **[%s]** %s",
                min, sec, tenths, kind, detail.replace('\n', ' ').trim());
        synchronized (cueLog) {
            if (cueLog.size() < 500) {
                cueLog.add(line);
            }
        }
    }

    public synchronized void toggle(Activity activity) {
        if (recording) {
            stop(activity);
        } else {
            start(activity);
        }
    }

    public synchronized void start(Activity activity) {
        if (recording) return;
        this.appContext = activity.getApplicationContext();
        try {
            File dir = new File(appContext.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "SpotterDemos");
            if (!dir.exists()) dir.mkdirs();
            String ts = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
            baseFileName = "spotter-demo-" + ts;
            tempMp4File = new File(dir, baseFileName + ".mp4");
            cueMdFile = new File(dir, baseFileName + "-cues.md");

            MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, VIDEO_W, VIDEO_H);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, BITRATE);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, FPS);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL_SEC);

            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            inputSurface = encoder.createInputSurface();
            encoder.start();

            muxer = new MediaMuxer(tempMp4File.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            videoTrackIndex = -1;
            muxerStarted = false;

            startedAtMs = System.currentTimeMillis();
            encodedFrames = 0;
            recording = true;
            synchronized (cueLog) {
                cueLog.clear();
                lastLoggedStep = "";
                lastLoggedStatus = "";
            }
            logCue("START", "Demo recording started (" + sourceLabel + " · " + DatGlasses.INSTANCE.diagnostics() + ")");

            encoderThread = new HandlerThread("demo-recorder");
            encoderThread.start();
            encoderHandler = new Handler(encoderThread.getLooper());
            encoderHandler.post(frameLoop);

            notifyUi();
            Toast.makeText(activity, "● Demo recording started (target ≤ 03:00)", Toast.LENGTH_SHORT).show();
            Log.i(TAG, "Started DemoRecorder -> " + tempMp4File.getAbsolutePath());
        } catch (Throwable t) {
            Log.e(TAG, "Failed to start DemoRecorder", t);
            recording = false;
            cleanupCodec();
            Toast.makeText(activity, "Demo recorder error: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    public synchronized void stop(final Activity activity) {
        if (!recording) return;
        recording = false;
        logCue("STOP", "Demo recording finished (" + encodedFrames + " frames)");
        notifyUi();

        final Handler h = encoderHandler;
        final HandlerThread ht = encoderThread;
        encoderHandler = null;
        encoderThread = null;

        if (h != null) {
            h.removeCallbacksAndMessages(null);
            h.post(() -> {
                try {
                    drainEncoder(true);
                } catch (Throwable t) {
                    Log.w(TAG, "Final drain note: " + t.getMessage());
                }
                cleanupCodec();
                final long durationSec = Math.max(1L, (System.currentTimeMillis() - startedAtMs) / 1000L);
                final int totalFrames = encodedFrames;
                final String cueText = writeCueSheet(durationSec, totalFrames);
                final Uri galleryUri = publishToGallery();
                if (ht != null) ht.quitSafely();
                mainHandler.post(() -> {
                    notifyUi();
                    if (activity != null && !activity.isFinishing() && !activity.isDestroyed()) {
                        showSavedDialog(activity, tempMp4File, cueMdFile, galleryUri, durationSec, totalFrames, cueText);
                    } else if (appContext != null) {
                        Toast.makeText(appContext,
                                "Demo saved to Movies/SpotterDemos/" + baseFileName + ".mp4",
                                Toast.LENGTH_LONG).show();
                    }
                });
            });
        }
    }

    private void notifyUi() {
        mainHandler.post(() -> {
            UiListener l = uiListener;
            if (l != null) {
                l.onDemoStateChanged(recording, currentButtonLabel());
            }
        });
    }

    private final Runnable frameLoop = new Runnable() {
        private int lastUiSec = -1;

        @Override
        public void run() {
            if (!recording) return;
            long loopStart = System.currentTimeMillis();
            try {
                renderFrameToSurface();
                drainEncoder(false);
                encodedFrames++;
                int sec = (int) ((loopStart - startedAtMs) / 1000L);
                if (sec != lastUiSec) {
                    lastUiSec = sec;
                    notifyUi();
                }
            } catch (Throwable t) {
                Log.w(TAG, "Frame encode warning: " + t.getMessage());
            }
            if (recording && encoderHandler != null) {
                long cost = System.currentTimeMillis() - loopStart;
                long delay = Math.max(8L, FRAME_INTERVAL_MS - cost);
                encoderHandler.postDelayed(this, delay);
            }
        }
    };

    private void renderFrameToSurface() {
        Surface s = inputSurface;
        if (s == null || !s.isValid()) return;
        Canvas c = null;
        try {
            c = s.lockCanvas(null);
            if (c != null) {
                drawCompositeHud(c);
            }
        } finally {
            if (c != null) {
                try { s.unlockCanvasAndPost(c); } catch (Throwable ignored) {}
            }
        }
    }

    private void drawCompositeHud(Canvas c) {
        long now = System.currentTimeMillis();
        long elapsedMs = Math.max(0L, now - startedAtMs);
        long sec = elapsedMs / 1000L;

        // 1. Background gradient
        bgPaint.setShader(new LinearGradient(0, 0, 0, VIDEO_H,
                0xFF0A0D14, 0xFF111622, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, VIDEO_W, VIDEO_H, bgPaint);

        // 2. Top Header Bar (y = 20..104)
        RectF headerRect = new RectF(20, 20, VIDEO_W - 20, 104);
        cardPaint.setColor(0xFF151A26);
        c.drawRoundRect(headerRect, 18f, 18f, cardPaint);
        borderPaint.setColor(0xFF2B354B);
        c.drawRoundRect(headerRect, 18f, 18f, borderPaint);

        titlePaint.setColor(0xFFF5F7FA);
        titlePaint.setTextSize(24f);
        c.drawText("ZOTH FIELDKIT · SPOTTER", 40, 58, titlePaint);
        subPaint.setColor(0xFF9BA8BE);
        subPaint.setTextSize(14f);
        c.drawText("Ray-Ban Meta Smart Glasses Multimodal Agent", 40, 84, subPaint);

        // REC Pill in Top-Right
        RectF recRect = new RectF(VIDEO_W - 224, 34, VIDEO_W - 36, 90);
        boolean pulse = ((elapsedMs / 500L) % 2L) == 0L;
        accentPaint.setColor(pulse ? 0xFFD92B3A : 0xFF9E1F2B);
        c.drawRoundRect(recRect, 28f, 28f, accentPaint);
        String recText = String.format(Locale.US, "● REC %02d:%02d/03:00", sec / 60, sec % 60);
        badgePaint.setColor(0xFFFFFFFF);
        badgePaint.setTextSize(15f);
        c.drawText(recText, VIDEO_W - 210, 68, badgePaint);

        // 3. Source & Telemetry Bar (y = 114..162)
        RectF sourceRect = new RectF(20, 114, 385, 158);
        boolean glassesStream = glassesActive && DatGlasses.INSTANCE.isStreaming();
        accentPaint.setColor(glassesActive
                ? (glassesStream ? 0xFF14533B : 0xFF5A4318)
                : 0xFF1B3B5C);
        c.drawRoundRect(sourceRect, 14f, 14f, accentPaint);
        monoPaint.setColor(glassesActive
                ? (glassesStream ? 0xFF8FE3B0 : 0xFFFFD166)
                : 0xFF8ECAE6);
        monoPaint.setTextSize(14f);
        c.drawText("◉ " + sourceLabel, 34, 141, monoPaint);

        RectF teleRect = new RectF(395, 114, VIDEO_W - 20, 158);
        cardPaint.setColor(0xFF141821);
        c.drawRoundRect(teleRect, 14f, 14f, cardPaint);
        monoPaint.setColor(0xFFB8C7DC);
        String shortTele = streamTelemetry.length() > 30
                ? streamTelemetry.substring(0, 30) : streamTelemetry;
        c.drawText(shortTele, 410, 141, monoPaint);

        // 4. Main Live POV Viewport (y = 170..870)
        RectF povRect = new RectF(20, 170, VIDEO_W - 20, 870);
        cardPaint.setColor(0xFF07090E);
        c.drawRoundRect(povRect, 22f, 22f, cardPaint);

        Bitmap bmp = latestFrameBmp;
        if (bmp != null && !bmp.isRecycled()) {
            // Fit-center / fill nicely inside povRect with 6px inset
            RectF inner = new RectF(26, 176, VIDEO_W - 26, 864);
            float bw = bmp.getWidth();
            float bh = bmp.getHeight();
            float scale = Math.min(inner.width() / bw, inner.height() / bh);
            float dw = bw * scale;
            float dh = bh * scale;
            float left = inner.centerX() - dw / 2f;
            float top = inner.centerY() - dh / 2f;
            RectF dst = new RectF(left, top, left + dw, top + dh);
            c.drawBitmap(bmp, new Rect(0, 0, bmp.getWidth(), bmp.getHeight()), dst, bmpPaint);
        } else {
            // Waiting for first camera/glasses frame placeholder
            titlePaint.setColor(0xFF8FE3B0);
            titlePaint.setTextSize(22f);
            c.drawText(glassesActive ? "RAY-BAN META 01QV POV STREAM" : "PHONE CAMERA POV STREAM",
                    160, 490, titlePaint);
            subPaint.setColor(0xFF9BA8BE);
            subPaint.setTextSize(16f);
            String diag = DatGlasses.INSTANCE.diagnostics();
            drawMultiline(c, diag, 60, 525, VIDEO_W - 120, subPaint, 3);
        }

        // HUD Reticle Corner Brackets inside POV Viewport
        borderPaint.setColor(glassesActive ? 0xAA8FE3B0 : 0xAA6EC8FF);
        borderPaint.setStrokeWidth(3f);
        c.drawRoundRect(povRect, 22f, 22f, borderPaint);

        // If in full-duplex Muse Call mode, draw voice activity orb in top-right of POV
        if (inCallMode) {
            float cx = VIDEO_W - 95f;
            float cy = 240f;
            float rMuse = 26f + Math.min(28f, museVoiceLevel * 45f);
            float rUser = 16f + Math.min(20f, userVoiceLevel * 35f);
            accentPaint.setColor(0x558FE3B0);
            c.drawCircle(cx, cy, rMuse, accentPaint);
            accentPaint.setColor(0xCC6EC8FF);
            c.drawCircle(cx, cy, rUser, accentPaint);
            badgePaint.setTextSize(12f);
            c.drawText("MUSE LIVE", cx - 34f, cy + 48f, badgePaint);
        }

        // 5. Mission Step / Call State Card (y = 884..1054)
        RectF stepRect = new RectF(20, 884, VIDEO_W - 20, 1054);
        cardPaint.setColor(0xFF151B26);
        c.drawRoundRect(stepRect, 18f, 18f, cardPaint);
        borderPaint.setColor(0xFF2E3B52);
        borderPaint.setStrokeWidth(2f);
        c.drawRoundRect(stepRect, 18f, 18f, borderPaint);

        monoPaint.setColor(0xFFFFD166);
        monoPaint.setTextSize(14f);
        String modeHeader = inCallMode
                ? "LIVE FULL-DUPLEX MUSE CALL · HANDS-FREE VOICE + EYES"
                : "ACTIVE MISSION STEP · PRESET: " + presetLabel.toUpperCase(Locale.US);
        c.drawText(modeHeader, 40, 914, monoPaint);

        stepPaint.setColor(0xFFF5F7FA);
        stepPaint.setTextSize(22f);
        drawMultiline(c, stepOrCallTitle, 40, 926, VIDEO_W - 80, stepPaint, 3);

        // 6. Coach Subtitle / Transcript & Sensor Card (y = 1068..1228)
        RectF statusRect = new RectF(20, 1068, VIDEO_W - 20, 1228);
        cardPaint.setColor(0xFF121722);
        c.drawRoundRect(statusRect, 18f, 18f, cardPaint);
        borderPaint.setColor(0xFF253044);
        c.drawRoundRect(statusRect, 18f, 18f, borderPaint);

        monoPaint.setColor(0xFF8FE3B0);
        monoPaint.setTextSize(14f);
        c.drawText("LIVE COACH / TRANSCRIPT & TELEMETRY", 40, 1096, monoPaint);

        bodyPaint.setColor(0xFFE2ECF8);
        bodyPaint.setTextSize(18f);
        drawMultiline(c, statusOrTranscript, 40, 1106, VIDEO_W - 80, bodyPaint, 3);

        if (sensorTelemetry != null && !sensorTelemetry.isEmpty()) {
            subPaint.setColor(0xFF8EA2BE);
            subPaint.setTextSize(14f);
            c.drawText(sensorTelemetry, 40, 1214, subPaint);
        }

        // 7. Footer Attribution (y = 1258)
        subPaint.setColor(0xFF6C7A91);
        subPaint.setTextSize(13f);
        c.drawText("Zoth FieldKit by NullAI · Meta Wearables DAT 1.0.1 · Muse Spark Multimodal", 40, 1258, subPaint);
    }

    private void drawMultiline(Canvas c, String text, float x, float y, int width, TextPaint paint, int maxLines) {
        if (text == null || text.isEmpty()) return;
        StaticLayout layout = StaticLayout.Builder
                .obtain(text, 0, text.length(), paint, Math.max(100, width))
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setMaxLines(maxLines)
                .setIncludePad(false)
                .build();
        c.save();
        c.translate(x, y);
        layout.draw(c);
        c.restore();
    }

    private void drainEncoder(boolean endOfStream) {
        if (encoder == null) return;
        if (endOfStream) {
            try { encoder.signalEndOfInputStream(); } catch (Throwable ignored) {}
        }
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int loops = 0;
        while (loops++ < 60) {
            int outIndex = encoder.dequeueOutputBuffer(info, endOfStream ? 10_000 : 0);
            if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) break;
            } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (!muxerStarted && muxer != null) {
                    MediaFormat newFormat = encoder.getOutputFormat();
                    videoTrackIndex = muxer.addTrack(newFormat);
                    muxer.start();
                    muxerStarted = true;
                }
            } else if (outIndex >= 0) {
                ByteBuffer encodedData = encoder.getOutputBuffer(outIndex);
                if (encodedData != null) {
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        info.size = 0;
                    }
                    if (info.size != 0 && muxerStarted && muxer != null) {
                        encodedData.position(info.offset);
                        encodedData.limit(info.offset + info.size);
                        info.presentationTimeUs = System.nanoTime() / 1000L;
                        muxer.writeSampleData(videoTrackIndex, encodedData, info);
                    }
                }
                encoder.releaseOutputBuffer(outIndex, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    break;
                }
            }
        }
    }

    private void cleanupCodec() {
        try {
            if (encoder != null) {
                encoder.stop();
                encoder.release();
            }
        } catch (Throwable ignored) {}
        encoder = null;
        try {
            if (inputSurface != null) inputSurface.release();
        } catch (Throwable ignored) {}
        inputSurface = null;
        try {
            if (muxer != null) {
                if (muxerStarted) muxer.stop();
                muxer.release();
            }
        } catch (Throwable ignored) {}
        muxer = null;
        muxerStarted = false;
    }

    private String writeCueSheet(long durationSec, int frames) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Zoth FieldKit (Spotter) — Live Demo Cue Sheet\n\n");
        sb.append("- **File**: `").append(baseFileName).append(".mp4`\n");
        sb.append("- **Duration**: `").append(durationSec).append("s` (").append(frames).append(" frames @ 24fps)\n");
        sb.append("- **Primary Source**: `").append(sourceLabel).append("`\n");
        sb.append("- **Glasses Diagnostics**: `").append(DatGlasses.INSTANCE.diagnostics()).append("`\n\n");
        sb.append("## Timestamped Event Timeline\n\n");
        synchronized (cueLog) {
            for (String line : cueLog) {
                sb.append(line).append("\n");
            }
        }
        String content = sb.toString();
        if (cueMdFile != null) {
            try (FileOutputStream fos = new FileOutputStream(cueMdFile)) {
                fos.write(content.getBytes(StandardCharsets.UTF_8));
            } catch (Throwable t) {
                Log.w(TAG, "Cue sheet write note: " + t.getMessage());
            }
        }
        return content;
    }

    private Uri publishToGallery() {
        if (appContext == null || tempMp4File == null || !tempMp4File.exists()) return null;
        try {
            ContentResolver resolver = appContext.getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME, baseFileName + ".mp4");
            values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            values.put(MediaStore.Video.Media.TITLE, "Spotter Live Demo " + baseFileName);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/SpotterDemos");
                values.put(MediaStore.Video.Media.IS_PENDING, 1);
            }
            Uri uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) return null;
            try (OutputStream os = resolver.openOutputStream(uri);
                 FileInputStream fis = new FileInputStream(tempMp4File)) {
                if (os != null) {
                    byte[] buf = new byte[65536];
                    int r;
                    while ((r = fis.read(buf)) > 0) {
                        os.write(buf, 0, r);
                    }
                    os.flush();
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues done = new ContentValues();
                done.put(MediaStore.Video.Media.IS_PENDING, 0);
                resolver.update(uri, done, null, null);
            }
            // Also write the markdown cue sheet into Downloads/SpotterDemos on Android Q+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && cueMdFile != null && cueMdFile.exists()) {
                try {
                    ContentValues cvMd = new ContentValues();
                    cvMd.put(MediaStore.Downloads.DISPLAY_NAME, baseFileName + "-cues.md");
                    cvMd.put(MediaStore.Downloads.MIME_TYPE, "text/markdown");
                    cvMd.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/SpotterDemos");
                    Uri mdUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cvMd);
                    if (mdUri != null) {
                        try (OutputStream mos = resolver.openOutputStream(mdUri);
                             FileInputStream mfis = new FileInputStream(cueMdFile)) {
                            if (mos != null) {
                                byte[] buf = new byte[16384];
                                int r;
                                while ((r = mfis.read(buf)) > 0) mos.write(buf, 0, r);
                            }
                        }
                    }
                } catch (Throwable ignored) {}
            }
            Log.i(TAG, "Published demo MP4 to Gallery: " + uri);
            return uri;
        } catch (Throwable t) {
            Log.w(TAG, "Gallery publish note: " + t.getMessage());
            return null;
        }
    }

    private void showSavedDialog(Activity activity,
                                 File mp4File,
                                 File mdFile,
                                 Uri galleryUri,
                                 long durationSec,
                                 int frames,
                                 String cueText) {
        long kb = (mp4File != null && mp4File.exists()) ? (mp4File.length() / 1024L) : 0L;
        String msg = "Saved " + durationSec + "s HD demo video (" + frames + " frames, " + kb + " KB)\n\n"
                + "• Gallery: Movies/SpotterDemos/" + baseFileName + ".mp4\n"
                + "• Cue Sheet: Downloads/SpotterDemos/" + baseFileName + "-cues.md";
        new AlertDialog.Builder(activity)
                .setTitle("🎬 Live Demo Recorded")
                .setMessage(msg)
                .setPositiveButton("↗ Share Video", (d, w) -> {
                    if (galleryUri != null) {
                        Intent share = new Intent(Intent.ACTION_SEND);
                        share.setType("video/mp4");
                        share.putExtra(Intent.EXTRA_STREAM, galleryUri);
                        share.putExtra(Intent.EXTRA_TEXT, "Zoth FieldKit (Spotter) — Live Ray-Ban Meta AI Demo");
                        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        activity.startActivity(Intent.createChooser(share, "Share Spotter Demo Video"));
                    } else {
                        Toast.makeText(activity, "Saved at: " + mp4File.getAbsolutePath(), Toast.LENGTH_LONG).show();
                    }
                })
                .setNeutralButton("▶ Play Video", (d, w) -> {
                    if (galleryUri != null) {
                        Intent view = new Intent(Intent.ACTION_VIEW);
                        view.setDataAndType(galleryUri, "video/mp4");
                        view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        try {
                            activity.startActivity(view);
                        } catch (Throwable t) {
                            Toast.makeText(activity, "Open Google Photos / Files to play.", Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNegativeButton("Cue Sheet", (d, w) -> {
                    new AlertDialog.Builder(activity)
                            .setTitle("Demo Cue Sheet (" + baseFileName + ")")
                            .setMessage(cueText)
                            .setPositiveButton("Close", null)
                            .show();
                })
                .show();
    }
}
