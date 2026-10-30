package tech.nullai.fieldkit;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

/**
 * Design-only Ordered Bayer 8x8 dither motif with smooth monotone grey fades
 * and a hyper-quality holographic specular shine sweep.
 * Never touches camera or glasses frames; the stream stays a real image.
 */
public final class DitherStripView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float phase = 0f;

    private static final int[][] BAYER8 = {
            { 0, 32,  8, 40,  2, 34, 10, 42},
            {48, 16, 56, 24, 50, 18, 58, 26},
            {12, 44,  4, 36, 14, 46,  6, 38},
            {60, 28, 52, 20, 62, 30, 54, 22},
            { 3, 35, 11, 43,  1, 33,  9, 41},
            {51, 19, 59, 27, 49, 17, 57, 25},
            {15, 47,  7, 39, 13, 45,  5, 37},
            {63, 31, 55, 23, 61, 29, 53, 21}
    };

    public DitherStripView(Context context) {
        super(context);
    }

    public DitherStripView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public DitherStripView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        float cell = Math.max(6.0f, w / 84f);
        int cols = Math.max(1, (int) (w / cell));
        int rows = Math.max(1, (int) (h / cell));

        float sweep = ((phase * 0.015f) % 1.4f) - 0.2f;

        for (int y = 0; y < rows; y++) {
            for (int x = 0; x < cols; x++) {
                float u = x / (float) Math.max(1, cols - 1);
                float v = y / (float) Math.max(1, rows - 1);

                double wave1 = 0.5 + 0.5 * Math.sin((x + phase * 0.65f) * 0.24 + y * 0.65);
                double wave2 = 0.5 + 0.5 * Math.cos((x - phase * 0.40f) * 0.16 - y * 0.5);
                double edge = 1.0 - Math.abs(y - (rows - 1) / 2.0) / Math.max(1.0, rows / 1.35);
                double density = Math.max(0.0, Math.min(1.0, (wave1 * 0.42 + wave2 * 0.28 + edge * 0.30)));

                int threshold = BAYER8[y & 7][x & 7];
                if (density * 64.0 <= threshold) continue;

                float dist = Math.abs((u + v * 0.08f) - sweep);
                float holo = (float) Math.exp(-(dist * dist) / 0.0060f);
                float dispersion = ((u + v * 0.08f) - sweep) / 0.07f;

                // Monotone grey fade from deep ink (#121316) through graphite (#585E6A) to silver mist (#AFB6C2)
                int baseGrey = (int) (18 + (1.0 - density) * 145);

                int r = baseGrey;
                int g = baseGrey;
                int b = baseGrey + 3;

                float cx = x * cell + cell * 0.5f;
                float cy = y * cell + cell * 0.5f;
                float radius = cell * (0.14f + 0.21f * (float) density + 0.07f * holo);

                if (holo > 0.05f) {
                    int shiftR = (int) (20 * Math.sin(dispersion * 2.4f));
                    int shiftG = (int) (15 * Math.cos(dispersion * 1.9f));
                    int shiftB = (int) (28 * Math.cos(dispersion * 2.4f - 0.6f));
                    int specChrome = 96;
                    r = clamp((int) (r + (specChrome + shiftR - r) * holo));
                    g = clamp((int) (g + (specChrome + shiftG - g) * holo));
                    b = clamp((int) (b + (specChrome + shiftB - b) * holo));
                }

                int alpha = Math.max(50, Math.min(245, (int) (95 + 130 * density + 35 * holo)));
                paint.setColor(Color.argb(alpha, r, g, b));
                canvas.drawCircle(cx, cy, radius, paint);

                if (holo > 0.20f) {
                    glintPaint.setColor(Color.argb((int) (210 * holo), 245, 249, 255));
                    canvas.drawCircle(
                            cx - radius * 0.22f,
                            cy - radius * 0.22f,
                            radius * 0.42f * holo,
                            glintPaint
                    );
                }
            }
        }

        if (isShown()) {
            phase = (phase + 1f) % 4096f;
            postInvalidateDelayed(32);
        }
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }
}
