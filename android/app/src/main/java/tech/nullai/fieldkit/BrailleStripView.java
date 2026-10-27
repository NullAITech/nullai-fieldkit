package tech.nullai.fieldkit;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

/**
 * Design-only braille motif: quiet rows of braille cells, deterministic from
 * position (same pattern every launch). Pure decoration — never near frames.
 */
public final class BrailleStripView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    // A calm deterministic shuffle of braille cells (U+2800 block).
    private static final String CELLS = "⠁⠃⠉⠙⠑⠋⠛⠓⠊⠚⠞⠧⠺⠏⠟⠗⠎⠞⠥⠭⠽⠵⠮⠾⠿⣿";

    public BrailleStripView(Context context) {
        super(context);
    }

    public BrailleStripView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public BrailleStripView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        paint.setTextSize(Math.max(14f, h * 0.52f));
        paint.setColor(Color.rgb(25, 25, 28));
        paint.setAlpha(90);
        float cell = paint.getTextSize() * 0.72f;
        int cols = Math.max(1, (int) (w / cell));
        int rows = Math.max(1, (int) (h / (paint.getTextSize() * 1.05f)));
        for (int y = 0; y < rows; y++) {
            for (int x = 0; x < cols; x++) {
                int idx = (x * 31 + y * 17) % CELLS.length();
                boolean gold = (x * 7 + y * 13) % 37 == 0;
                paint.setAlpha(gold ? 170 : 90);
                paint.setColor(gold ? Color.rgb(184, 142, 38) : Color.rgb(25, 25, 28));
                canvas.drawText(String.valueOf(CELLS.charAt(idx)),
                        x * cell, (y + 1) * paint.getTextSize(), paint);
            }
        }
    }
}
