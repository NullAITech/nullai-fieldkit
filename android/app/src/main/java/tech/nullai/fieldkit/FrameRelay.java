package tech.nullai.fieldkit;

import android.os.Handler;
import android.os.Looper;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Zero-lag frame hand-off shared by both camera sources.
 *
 * <p>The old path did base64 + JSON on the camera callback thread and pushed
 * cameras through a fixed 450 ms gate, so the relay regularly received a
 * half-second-old picture and capture stalled while encoding. This class keeps
 * a single newest-frame slot: a producer never blocks, a frame that has not
 * been sent yet is simply overwritten by the next one, and encoding happens on
 * a private worker so capture stays hot at full rate regardless of link speed.
 *
 * <p>Pacing follows the measured pipeline cost instead of a hard-coded
 * interval: a slow stage widens the gap so nothing queues up (the staleness a
 * viewer feels is queueing, not frame rate), a fast stage tightens it back to
 * the source rate. A frame is counted only once the sink accepted it.
 */
public final class FrameRelay {

    /** Pushes one frame onward. {@code false} means "relay not connected — hold it". */
    public interface Sink {
        boolean send(String source, byte[] jpeg, int width, int height);
    }

    /** Receives a readable stats line on the main thread, at most once a second. */
    public interface Stats {
        void onStats(String text);
    }

    private static final long MIN_GAP_MS = 33;     // ~30 fps: the sources we target run at 30, so never pace below it
    private static final long MAX_GAP_MS = 600;
    private static final long SLOW_STAGE_MS = 120; // widen the gap above this
    private static final long FAST_STAGE_MS = 55;  // tighten it below this

    private final Sink sink;
    private final Stats stats;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicReference<Pending> slot = new AtomicReference<>();
    private final Object lock = new Object();
    private volatile boolean running = true;
    private volatile boolean forceOnce = false;

    private long gapMs = MIN_GAP_MS;
    private long lastSendAt = 0L;

    private int sent;
    private long bytes;
    private long stageSum;
    private long stageMax;
    private int heldCount;
    private String lastDims = "";
    private long reportAt = System.currentTimeMillis();

    private static final class Pending {
        final String source;
        final byte[] jpeg;
        final int width, height;
        final long captureAt;
        Pending(String s, byte[] j, int w, int h, long t) {
            source = s; jpeg = j; width = w; height = h; captureAt = t;
        }
    }

    public FrameRelay(Sink sink, Stats stats) {
        this.sink = sink;
        this.stats = stats;
        Thread t = new Thread(this::loop, "frame-relay");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY + 1);
        t.start();
    }

    /**
     * Offers the newest frame: never blocks, never queues. Overwriting a frame
     * that has not gone out yet is what keeps the stream live rather than
     * merely smooth.
     */
    public void submit(String source, byte[] jpeg, int width, int height, long captureAt) {
        if (width > 0 && height > 0) lastDims = width + "×" + height;
        slot.set(new Pending(source, jpeg, width, height, captureAt));
        synchronized (lock) { lock.notifyAll(); }
    }

    /** Send the held frame at once — used the moment the relay socket opens. */
    public void flush() {
        forceOnce = true;
        synchronized (lock) { lock.notifyAll(); }
    }

    public void stop() {
        running = false;
        synchronized (lock) { lock.notifyAll(); }
    }

    private void loop() {
        while (running) {
            Pending p = slot.getAndSet(null);
            if (p == null) {
                synchronized (lock) {
                    try { lock.wait(250); } catch (InterruptedException ignored) { }
                }
                continue;
            }
            long wait = gapMs - (System.currentTimeMillis() - lastSendAt);
            if (!forceOnce && wait > 0) {
                // Hold this one, but stay responsive so a fresher frame can win.
                try { Thread.sleep(Math.min(wait, 40)); } catch (InterruptedException ignored) { }
                slot.compareAndSet(null, p);
                continue;
            }
            forceOnce = false;
            long t0 = System.currentTimeMillis();
            boolean ok;
            try {
                ok = sink.send(p.source, p.jpeg, p.width, p.height);
            } catch (Exception e) {
                ok = false;
            }
            long stage = System.currentTimeMillis() - t0;
            long end = System.currentTimeMillis();
            if (ok) {
                lastSendAt = end;
                sent++;
                bytes += p.jpeg.length;
                stageSum += stage;
                if (stage > stageMax) stageMax = stage;
                if (stage > SLOW_STAGE_MS && gapMs < MAX_GAP_MS) gapMs = Math.min(MAX_GAP_MS, gapMs * 5 / 4);
                else if (stage < FAST_STAGE_MS && gapMs > MIN_GAP_MS) gapMs = Math.max(MIN_GAP_MS, gapMs * 85 / 100);
            } else {
                heldCount++;
                slot.compareAndSet(null, p);   // never displace a newer frame
                try { Thread.sleep(120); } catch (InterruptedException ignored) { }
            }
            maybeReport(end);
        }
    }

    private void maybeReport(long now) {
        if (stats == null || now - reportAt < 1000) return;
        final long window = now - reportAt;
        final int sentN = sent;
        final long byteN = bytes;
        final long avg = sentN > 0 ? stageSum / sentN : 0;
        final long max = stageMax;
        final int held = heldCount;
        final String dims = lastDims;
        final long gap = gapMs;
        reportAt = now;
        sent = 0; bytes = 0; stageSum = 0; stageMax = 0; heldCount = 0;
        if (sentN == 0 && held == 0) return;
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.US, "%.1f fps", sentN * 1000.0 / Math.max(1, window)));
        sb.append(" · stage ").append(avg).append(" ms");
        if (max > 0) sb.append(" (peak ").append(max).append(')');
        if (sentN > 0) {
            sb.append(" · ").append(String.format(Locale.US, "%.2f MB/s", byteN / 1048576.0));
            sb.append(" · ").append(byteN / sentN / 1024).append(" KB/frame");
        }
        if (held > 0) sb.append(" · ").append(held).append(" held");
        if (!dims.isEmpty()) sb.append(" · ").append(dims);
        sb.append(" · gap ").append(gap).append(" ms");
        final String text = sb.toString();
        main.post(() -> stats.onStats(text));
    }
}
