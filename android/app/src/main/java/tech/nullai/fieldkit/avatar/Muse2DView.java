package tech.nullai.fieldkit.avatar;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/**
 * Muse as a 2D character for the phone call screen.
 *
 * <p>Hand-drawn on the Canvas so it costs nothing to ship and scales to any
 * screen: pearl/chrome head with a holographic visor, eyes that blink and track
 * the call, a mouth that actually moves with the model's audio, and two rings
 * that pulse with each side's voice — cyan for the wearer, pearl for Muse.
 */
public class Muse2DView extends View {

    private static final int INK = 0xFF0B0C10;
    private static final int PEARL = 0xFFDCE4F2;
    private static final int CYAN = 0xFF6FE3FF;
    private static final int CORE = 0xFF14161C;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF oval = new RectF();

    private float museLevel;
    private float userLevel;
    private boolean speaking;
    private boolean videoMode;
    private String status = "";

    private long t0 = System.currentTimeMillis();
    private float blinkNext = 2.2f;

    public Muse2DView(Context c) { super(c); }
    public Muse2DView(Context c, AttributeSet a) { super(c, a); }

    public void setLevels(float user, float muse) {
        this.userLevel = clamp(user);
        this.museLevel = clamp(muse);
        invalidate();
    }

    public void setSpeaking(boolean s) { this.speaking = s; invalidate(); }

    public void setVideoMode(boolean v) { this.videoMode = v; invalidate(); }

    public void setStatus(String s) {
        if (s == null) return;
        status = s;
        invalidate();
    }

    private static float clamp(float v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        float t = (System.currentTimeMillis() - t0) / 1000f;

        // ---- backdrop: deep well + breathing halo
        p.setStyle(Paint.Style.FILL);
        p.setShader(new LinearGradient(0, 0, 0, h, 0xFF101218, INK, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, p);
        p.setShader(null);

        float cx = w / 2f;
        float cy = h * (videoMode ? 0.44f : 0.47f);
        float headW = Math.min(w * 0.62f, h * 0.30f);
        float headH = headW * 1.16f;

        float energy = Math.max(museLevel, userLevel * 0.85f);
        for (int i = 3; i >= 1; i--) {
            float rr = headW * (1.15f + i * 0.30f) * (1f + energy * 0.05f * i);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1.4f + energy * 1.6f);
            p.setColor(withAlpha(userLevel > museLevel ? CYAN : PEARL,
                    (int) (10 + 26 * energy * (4 - i) / 3f)));
            oval.set(cx - rr, cy - rr * 0.94f, cx + rr, cy + rr * 0.94f);
            canvas.drawOval(oval, p);
        }

        // ---- voice rings: left = wearer, right = Muse
        drawRing(canvas, cx - headW * 1.22f, cy, headW * 0.30f, userLevel, CYAN, t, 1f);
        drawRing(canvas, cx + headW * 1.22f, cy, headW * 0.30f, museLevel, PEARL, t, -1.35f);

        // ---- shoulders
        p.setStyle(Paint.Style.FILL);
        p.setShader(new RadialGradient(cx, cy + headH * 1.02f, headW * 0.95f,
                0xFF23272F, 0x00000000, Shader.TileMode.CLAMP));
        oval.set(cx - headW * 1.02f, cy + headH * 0.62f, cx + headW * 1.02f, cy + headH * 2.05f);
        canvas.drawOval(oval, p);
        p.setShader(null);

        // ---- head: pearl chrome
        oval.set(cx - headW / 2f, cy - headH / 2f, cx + headW / 2f, cy + headH / 2f);
        p.setShader(new RadialGradient(cx - headW * 0.22f, cy - headH * 0.30f, headW * 1.05f,
                new int[]{0xFFF7FAFF, 0xFFD3DCEB, 0xFF9AA6BA, 0xFF4A5261},
                new float[]{0f, 0.42f, 0.76f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawOval(oval, p);
        p.setShader(null);

        // jaw taper
        p.setColor(0xFFE7EDF7);
        path.reset();
        path.moveTo(cx - headW * 0.34f, cy + headH * 0.16f);
        path.quadTo(cx, cy + headH * 0.62f, cx + headW * 0.34f, cy + headH * 0.16f);
        path.quadTo(cx, cy + headH * 0.30f, cx - headW * 0.34f, cy + headH * 0.16f);
        path.close();
        canvas.drawPath(path, p);

        // ---- visor with a slow holographic sweep
        RectF visor = new RectF(cx - headW * 0.44f, cy - headH * 0.30f,
                cx + headW * 0.44f, cy + headH * 0.02f);
        p.setColor(CORE);
        canvas.drawRoundRect(visor, headH * 0.15f, headH * 0.15f, p);
        float sweep = ((t * 0.35f) % 1f);
        p.setShader(new LinearGradient(visor.left + visor.width() * (sweep - 0.18f), visor.top,
                visor.left + visor.width() * (sweep + 0.18f), visor.bottom,
                new int[]{0x0016D8FF, 0x553FD0FF, 0x0016D8FF}, null, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(visor, headH * 0.15f, headH * 0.15f, p);
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.2f);
        p.setColor(0x40AFC4E0);
        canvas.drawRoundRect(visor, headH * 0.15f, headH * 0.15f, p);
        p.setStyle(Paint.Style.FILL);

        // ---- eyes
        float blink = blinkScale(t);
        float eyeY = cy - headH * 0.14f;
        float eyeW = headW * 0.15f, eyeH = headH * 0.07f * blink;
        float look = (speaking ? -1f : 0f) * headW * 0.012f;
        for (int s = -1; s <= 1; s += 2) {
            float ex = cx + s * headW * 0.19f + look;
            p.setColor(0x33FFFFFF);
            oval.set(ex - eyeW, eyeY - eyeH, ex + eyeW, eyeY + eyeH);
            canvas.drawOval(oval, p);
            p.setColor(CYAN);
            oval.set(ex - eyeW * 0.78f, eyeY - eyeH * 0.78f, ex + eyeW * 0.78f, eyeY + eyeH * 0.78f);
            canvas.drawOval(oval, p);
            p.setColor(0xFF0A0D12);
            oval.set(ex - eyeW * 0.34f, eyeY - eyeH * 0.34f, ex + eyeW * 0.34f, eyeY + eyeH * 0.34f);
            canvas.drawOval(oval, p);
        }

        // ---- mouth moves with Muse's audio
        float open = headH * (0.012f + museLevel * 0.10f);
        float my = cy + headH * 0.20f;
        p.setColor(0xFF2A2F38);
        oval.set(cx - headW * 0.16f, my - open * 0.9f, cx + headW * 0.16f, my + open * 1.1f);
        canvas.drawOval(oval, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(headH * 0.014f);
        p.setColor(withAlpha(PEARL, 150 + (int) (105 * museLevel)));
        path.reset();
        path.moveTo(cx - headW * 0.20f, my - headH * 0.015f);
        path.quadTo(cx, my + headH * 0.055f + open, cx + headW * 0.20f, my - headH * 0.015f);
        canvas.drawPath(path, p);

        // ---- microphone halo / camera notch hint in video mode
        if (videoMode) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2f);
            p.setColor(0x33DCE4F2);
            oval.set(cx - headW * 0.06f, cy - headH * 0.62f, cx + headW * 0.06f, cy - headH * 0.50f);
            canvas.drawOval(oval, p);
        }
        p.setStyle(Paint.Style.FILL);

        // ---- dual voice bars
        drawBars(canvas, w, h, t);

        // ---- status line
        if (!status.isEmpty()) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFFA9B4C6);
            p.setTextAlign(Paint.Align.CENTER);
            p.setTextSize(Math.max(22f, h * 0.022f));
            canvas.drawText(status, cx, h * 0.955f, p);
            p.setColor(withAlpha(CYAN, speaking ? 200 : 90));
            canvas.drawCircle(cx, h * 0.93f, h * 0.004f + museLevel * h * 0.006f, p);
        }

        postInvalidateOnAnimation();
    }

    private void drawRing(Canvas canvas, float cx, float cy, float r, float level,
                          int color, float t, float dir) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2f + level * 4f);
        p.setColor(withAlpha(color, 40 + (int) (200 * level)));
        oval.set(cx - r, cy - r, cx + r, cy + r);
        canvas.drawArc(oval, t * (28f * dir), 90f + level * 150f, false, p);
        p.setStrokeWidth(1f);
        p.setColor(withAlpha(color, 26));
        canvas.drawArc(oval, -t * (16f * dir), 250f, false, p);
        p.setStyle(Paint.Style.FILL);
    }

    private void drawBars(Canvas canvas, float w, float h, float t) {
        int n = 22;
        float barW = w * 0.010f, gap = w * 0.007f;
        float baseY = h * 0.855f, maxH = h * 0.075f;
        float totalW = n * barW + (n - 1) * gap;
        float x = (w - totalW) / 2f;
        for (int i = 0; i < n; i++) {
            float phase = t * 4.2f + i * 0.55f;
            boolean museSide = i >= n / 2;
            float lvl = museSide ? museLevel : userLevel;
            float wobble = (float) (Math.sin(phase) * 0.35 + Math.sin(phase * 1.7f + 1f) * 0.2);
            float bh = 3f + maxH * (0.06f + lvl * (0.7f + wobble));
            int col = museSide ? PEARL : CYAN;
            p.setColor(withAlpha(col, 70 + (int) (170 * lvl)));
            RectF r = new RectF(x, baseY - bh, x + barW, baseY + bh * 0.25f);
            canvas.drawRoundRect(r, barW / 2f, barW / 2f, p);
            x += barW + gap;
        }
    }

    private float blinkScale(float t) {
        if (t > blinkNext) {
            blinkNext = t + 2.4f + (float) Math.random() * 2.2f;
        }
        float dt = blinkNext - t;
        if (dt > 0.22f) return 1f;              // open
        return Math.max(0.08f, Math.abs(dt - 0.11f) / 0.11f);
    }

    private static int withAlpha(int color, int a) {
        return (color & 0x00FFFFFF) | (Math.max(0, Math.min(255, a)) << 24);
    }
}
