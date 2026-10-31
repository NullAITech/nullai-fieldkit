package tech.nullai.fieldkit;

import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import java.util.Collections;

/**
 * The phone's camera as a JPEG source for the call, with no preview surface.
 *
 * <p>Camera2 in TEMPLATE_RECORD with three buffers: two buffers make the
 * producer stall while an encode is busy, which is exactly the stutter this
 * avoids. The frames come off an {@link ImageFormat#JPEG} reader, so the
 * camera's own encoder does the work in hardware — nothing is encoded on the
 * callback thread and nothing touches the UI thread.
 */
public final class CamFeed {

    public interface Sink {
        void onJpeg(byte[] jpeg, int width, int height);
    }

    private static final String TAG = "CamFeed";
    private static final int JPEG_QUALITY = 90;

    private final Context ctx;
    private final Sink sink;
    private final long gapMs;

    private HandlerThread thread;
    private Handler handler;
    private CameraDevice device;
    private CameraCaptureSession session;
    private ImageReader reader;
    private long lastAt;

    public CamFeed(Context ctx, Sink sink, long gapMs) {
        this.ctx = ctx.getApplicationContext();
        this.sink = sink;
        this.gapMs = gapMs;
    }

    public void start() {
        if (device != null || reader != null) return;
        thread = new HandlerThread("cam-feed");
        thread.start();
        handler = new Handler(thread.getLooper());
        try {
            CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
            if (cm == null) return;
            String id = null;
            for (String candidate : cm.getCameraIdList()) {
                id = candidate;
                break;
            }
            if (id == null) return;
            final Size size = pickSize(cm, id);
            reader = ImageReader.newInstance(size.getWidth(), size.getHeight(), ImageFormat.JPEG, 3);
            reader.setOnImageAvailableListener(r -> {
                Image img = null;
                try {
                    img = r.acquireLatestImage();
                } catch (Exception ignored) { }
                if (img == null) return;
                long now = System.currentTimeMillis();
                if (now - lastAt >= gapMs) {
                    lastAt = now;
                    byte[] jpeg = null;
                    try {
                        java.nio.ByteBuffer buf = img.getPlanes()[0].getBuffer();
                        jpeg = new byte[buf.remaining()];
                        buf.get(jpeg);
                    } catch (Exception e) {
                        Log.w(TAG, "frame read failed: " + e);
                    }
                    if (jpeg != null && jpeg.length > 0) {
                        try {
                            sink.onJpeg(jpeg, img.getWidth(), img.getHeight());
                        } catch (Exception e) {
                            Log.w(TAG, "sink rejected frame: " + e);
                        }
                    }
                }
                try { img.close(); } catch (Exception ignored) { }
            }, handler);
            cm.openCamera(id, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice camera) {
                    device = camera;
                    try {
                        camera.createCaptureSession(Collections.singletonList((Surface) reader.getSurface()),
                                new CameraCaptureSession.StateCallback() {
                                    @Override public void onConfigured(CameraCaptureSession s) {
                                        session = s;
                                        try {
                                            CaptureRequest.Builder b =
                                                    camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                                            b.addTarget(reader.getSurface());
                                            b.set(CaptureRequest.CONTROL_MODE,
                                                    android.hardware.camera2.CameraMetadata.CONTROL_MODE_AUTO);
                                            b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                                                    new android.util.Range<>(15, 30));
                                            // The camera encodes; we only ask for the quality.
                                            b.set(CaptureRequest.JPEG_QUALITY, (byte) JPEG_QUALITY);
                                            s.setRepeatingRequest(b.build(), null, handler);
                                        } catch (Exception e) {
                                            Log.w(TAG, "repeating request failed: " + e);
                                        }
                                    }

                                    @Override public void onConfigureFailed(CameraCaptureSession s) {
                                        Log.w(TAG, "capture session config failed");
                                    }
                                }, handler);
                    } catch (Exception e) {
                        Log.w(TAG, "capture session failed: " + e);
                    }
                }

                @Override public void onDisconnected(CameraDevice camera) {
                    camera.close();
                    device = null;
                }

                @Override public void onError(CameraDevice camera, int error) {
                    Log.w(TAG, "camera error " + error);
                    camera.close();
                    device = null;
                }
            }, handler);
        } catch (Exception e) {
            Log.w(TAG, "open failed: " + e);
        }
    }

    /** The JPEG output size closest to 720p that is still a stream, not a photo. */
    private Size pickSize(CameraManager cm, String id) {
        Size fallback = new Size(1280, 720);
        try {
            StreamConfigurationMap map = cm.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size[] sizes = map == null ? null : map.getOutputSizes(ImageFormat.JPEG);
            if (sizes == null || sizes.length == 0) return fallback;
            Size best = fallback;
            long bestScore = Long.MAX_VALUE;
            for (Size s : sizes) {
                long score = Math.abs(s.getWidth() - 1280L) * 4L + Math.abs(s.getHeight() - 720L) * 9L;
                if (s.getWidth() > 1920 || s.getWidth() < 640) score += 100000L;
                if (score < bestScore) {
                    bestScore = score;
                    best = s;
                }
            }
            return best;
        } catch (Exception e) {
            return fallback;
        }
    }

    public void stop() {
        try { if (session != null) session.close(); } catch (Exception ignored) { }
        session = null;
        try { if (device != null) device.close(); } catch (Exception ignored) { }
        device = null;
        try { if (reader != null) reader.close(); } catch (Exception ignored) { }
        reader = null;
        if (thread != null) {
            thread.quitSafely();
            thread = null;
        }
        handler = null;
    }
}
