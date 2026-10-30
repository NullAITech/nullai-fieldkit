package tech.nullai.fieldkit;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

/**
 * Design-only braille motif: rows of Unicode U+2800 2x4 sub-pixel braille cells
 * with smooth monotone grey fades and a hyper-quality holographic specular shine wave.
 * Pure decoration — never near camera or glasses frames.
 */
public final class BrailleStripView extends View {
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint specPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // Deterministic progression of U+2800 braille density cells
    private static final int[] CELL_MASKS = {
            0x01, 0x03, 0x07, 0x0F, 0x1F, 0x3F, 0x7F, 0xFF,
            0xFE, 0xFC, 0xF8, 0xF0, 0xE0, 0xC0, 0x80, 0x49,
            0x92, 0x24, 0x55, 0xAA, 0xDB, 0xEE, 0x77, 0x33
    };

    // Standard U+2800 2x4 dot bit positions: [row 0..3][col 0..1]
    private static final int[][] DOT_BITS = {
            {0x01, 0x08},
            {0x02, 0x10},
            {0x04, 0x20},
            {0x40, 0x80}
    };

    private float phase = 0f;

    public BrailleStripView(Context context) {
        super(context);
        init();
    }

    public BrailleStripView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public BrailleStripView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        dotPaint.setStyle(Paint.Style.FILL);
        specPaint.setStyle(Paint.Style.FILL);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        int cellRows = 2;
        float cellH = h / (float) cellRows;
        float cellW = Math.max(8f, cellH * 0.62f);
        int cellCols = Math.max(1, (int) (w / cellW));

        float subW = cellW / 2.2f;
        float subH = cellH / 4.2f;
        float baseRadius = Math.max(1.05f, Math.min(subW, subH) * 0.39f);

        // Two counter-propagating holographic light ribbons
        float sweepCenter1 = ((phase * 0.016f) % 1.4f) - 0.2f;
        float sweepCenter2 = 1.2f - ((phase * 0.010f) % 1.4f);

        for (int cy = 0; cy < cellRows; cy++) {
            for (int cx = 0; cx < cellCols; cx++) {
                float u = cx / (float) Math.max(1, cellCols - 1);
                float v = cy / (float) Math.max(1, cellRows);

                // Smooth monotone grey wave + deterministic cell pattern
                double fadeWave = 0.5 + 0.5 * Math.sin(u * 8.5 - phase * 0.08 + cy * 1.3);
                int maskIdx = (int) Math.abs((cx * 13 + cy * 7 + (int) (phase * 0.25f)) % CELL_MASKS.length);
                int mask = CELL_MASKS[maskIdx];

                float diag = u + v * 0.12f;
                float dist1 = Math.abs(diag - sweepCenter1);
                float dist2 = Math.abs(diag - sweepCenter2);

                float holo1 = (float) Math.exp(-(dist1 * dist1) / 0.0055f);
                float holo2 = (float) Math.exp(-(dist2 * dist2) / 0.0110f) * 0.50f;
                float holo = Math.min(1f, holo1 + holo2);
                float ridgeOffset = (diag - sweepCenter1) / 0.065f;

                for (int sy = 0; sy < 4; sy++) {
                    for (int sx = 0; sx < 2; sx++) {
                        boolean active = (mask & DOT_BITS[sy][sx]) != 0;
                        float px = cx * cellW + (sx + 0.5f) * (cellW * 0.44f) + cellW * 0.06f;
                        float py = cy * cellH + (sy + 0.5f) * (cellH * 0.23f) + cellH * 0.04f;

                        double subWave = 0.5 + 0.5 * Math.cos((cx * 2 + sx) * 0.26 - phase * 0.07 + (cy * 4 + sy) * 0.42);
                        float greyMix = (float) (0.58 * fadeWave + 0.42 * subWave);

                        if (!active) {
                            // Subtle inactive sub-pixel grid dot in monotone light grey
                            dotPaint.setColor(Color.argb(38, 150, 155, 165));
                            canvas.drawCircle(px, py, baseRadius * 0.58f, dotPaint);
                            continue;
                        }

                        // Monotone grey fade from deep obsidian (#111215) to cool slate (#9EA4B0)
                        int baseGrey = (int) (17 + (1.0f - greyMix) * 142);
                        int r = baseGrey;
                        int g = baseGrey;
                        int b = baseGrey + 3;

                        if (holo > 0.04f) {
                            // Hyper-quality holographic micro-dispersion (silver-cyan / pearl-violet / platinum)
                            int prismR = (int) (22 * Math.sin(ridgeOffset * 2.6f));
                            int prismG = (int) (16 * Math.cos(ridgeOffset * 2.1f));
                            int prismB = (int) (30 * Math.cos(ridgeOffset * 2.6f - 0.65f));

                            // Lift into a crisp metallic chrome tone with high contrast on white
                            int chromeBase = 92;
                            r = clamp((int) (r + (chromeBase + prismR - r) * holo));
                            g = clamp((int) (g + (chromeBase + prismG - g) * holo));
                            b = clamp((int) (b + (chromeBase + prismB - b) * holo));
                        }

                        int alpha = clampAlpha((int) (115 + 135 * greyMix + 40 * holo));
                        dotPaint.setColor(Color.argb(alpha, r, g, b));
                        float rad = baseRadius * (0.86f + 0.24f * greyMix + 0.14f * holo);
                        canvas.drawCircle(px, py, rad, dotPaint);

                        // Inner specular glint pin-point for holographic foil depth
                        if (holo > 0.18f) {
                            specPaint.setColor(Color.argb((int) (220 * holo), 244, 248, 255));
                            canvas.drawCircle(
                                    px - rad * 0.22f,
                                    py - rad * 0.22f,
                                    rad * 0.42f * holo,
                                    specPaint
                            );
                        }
                    }
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

    private static int clampAlpha(int a) {
        return Math.max(0, Math.min(255, a));
    }
}
