package tech.nullai.fieldkit;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

/**
 * Design-only dither motif: a quiet 1-bit dot field for headers and cards.
 * It never touches camera or glasses frames; the stream stays a real image.
 */
public final class DitherStripView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int phase = 0;

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
        float cell = Math.max(7f, w / 72f);
        int cols = Math.max(1, (int) (w / cell));
        int rows = Math.max(1, (int) (h / cell));
        int[][] bayer = {{0, 8, 2, 10}, {12, 4, 14, 6}, {3, 11, 1, 9}, {15, 7, 13, 5}};
        for (int y = 0; y < rows; y++) {
            for (int x = 0; x < cols; x++) {
                double wave = 0.5 + 0.5 * Math.sin((x + phase) * 0.32 + y * 0.7);
                double edge = 1.0 - Math.abs(y - (rows - 1) / 2.0) / Math.max(1.0, rows / 1.4);
                double v = Math.max(0, Math.min(1, wave * 0.55 + edge * 0.45));
                int threshold = bayer[y % 4][x % 4];
                if (v * 16 <= threshold) continue;
                float radius = cell * (0.13f + 0.20f * (float) v);
                paint.setColor((x + phase) % 29 == 0 ? Color.rgb(184, 142, 38) : Color.rgb(25, 25, 28));
                paint.setAlpha((x + phase) % 29 == 0 ? 190 : 118);
                canvas.drawCircle(x * cell + cell / 2f, y * cell + cell / 2f, radius, paint);
            }
        }
        if (isShown()) {
            phase = (phase + 1) % 64;
            postInvalidateDelayed(140);
        }
    }
}
