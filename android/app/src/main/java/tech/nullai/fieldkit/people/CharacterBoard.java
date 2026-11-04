package tech.nullai.fieldkit.people;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.Base64;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import tech.nullai.fieldkit.R;
import tech.nullai.fieldkit.vision.Detector;

/**
 * Multi-angle Face + Body Character Board generator, Save-Person builder dialog,
 * and Live Recognition Popup Card renderer for Spotter.
 *
 * <p>Designed with a clean, high-contrast layout for everyday users and an expandable
 * "Advanced Biometrics & Vector Telemetry" section for developers.
 */
public final class CharacterBoard {
    private static final String TAG = "CharacterBoard";

    public static final String[] ANGLE_LABELS = new String[]{
            "1 · FRONT FACE",
            "2 · LEFT ANGLE",
            "3 · RIGHT ANGLE",
            "4 · FULL BODY"
    };

    public interface OnPersonSavedListener {
        void onSaved(PeopleVault.Person person);
    }

    public interface LiveFrameSupplier {
        Bitmap captureFreshFrame();
        Detector getDetector();
    }

    public static final class BoardExtraction {
        public float[] faceDescriptor;
        public float[][] angleFaceDescriptors;
        public float[] bodyDescriptor;
        public final Bitmap[] angleBitmaps = new Bitmap[4];
        public final String[] angleBase64 = new String[]{"", "", "", ""};
        public Bitmap compositeBoard;
        public String compositeBase64;
        public String primaryThumbBase64;
        public PersonDescriptor.AppearanceProfile appearance;
        public String aiFeatures = "";
        public int detectedAnglesCount = 1;

        BoardExtraction(
                float[] faceDescriptor,
                float[][] angleFaceDescriptors,
                float[] bodyDescriptor,
                Bitmap[] angles,
                Bitmap compositeBoard,
                PersonDescriptor.AppearanceProfile appearance,
                int detectedAnglesCount
        ) {
            this.faceDescriptor = faceDescriptor != null ? faceDescriptor : new float[0];
            this.angleFaceDescriptors = angleFaceDescriptors != null ? angleFaceDescriptors : new float[0][];
            this.bodyDescriptor = bodyDescriptor != null ? bodyDescriptor : new float[0];
            for (int i = 0; i < 4; i++) {
                this.angleBitmaps[i] = angles[i];
                this.angleBase64[i] = encodeJpeg(angles[i], 200, 80);
            }
            this.compositeBoard = compositeBoard;
            this.compositeBase64 = encodeJpeg(compositeBoard, 560, 82);
            this.primaryThumbBase64 = this.angleBase64[0].isEmpty()
                    ? this.compositeBase64 : this.angleBase64[0];
            this.appearance = appearance;
            this.detectedAnglesCount = Math.max(1, detectedAnglesCount);
        }
    }

    private CharacterBoard() {
    }

    /**
     * Extracts 4 character angles (Front Face, Left Angle, Right Angle, Full Body),
     * computes multi-angle Face (124D) and Body (112D) descriptors, analyzes attire/build,
     * and renders a composite Character Board image.
     */
    public static BoardExtraction extract(
            List<Bitmap> recentFrames,
            Bitmap primaryFrame,
            RectF faceBox,
            RectF bodyBox
    ) {
        return extractWithDetector(null, recentFrames, primaryFrame, faceBox, bodyBox);
    }

    public static BoardExtraction extractWithDetector(
            Detector detector,
            List<Bitmap> recentFrames,
            Bitmap primaryFrame,
            RectF faceBox,
            RectF bodyBox
    ) {
        List<Bitmap> frames = new ArrayList<>();
        if (recentFrames != null) {
            for (Bitmap b : recentFrames) {
                if (b != null && !b.isRecycled()) frames.add(b);
            }
        }
        if (primaryFrame != null && !primaryFrame.isRecycled() && !frames.contains(primaryFrame)) {
            frames.add(primaryFrame);
        }
        if (frames.isEmpty()) return null;

        Bitmap latest = frames.get(frames.size() - 1);
        int w = latest.getWidth();
        int h = latest.getHeight();

        RectF fBox = faceBox != null ? new RectF(faceBox) : null;
        RectF bBox = bodyBox != null ? new RectF(bodyBox) : null;

        if ((fBox == null || bBox == null) && detector != null) {
            try {
                Detector.Result dr = detector.detect(latest, false, true);
                if (fBox == null && !dr.faces.isEmpty()) {
                    for (RectF f : dr.faces) {
                        if (fBox == null || f.width() * f.height() > fBox.width() * fBox.height()) {
                            fBox = new RectF(f);
                        }
                    }
                }
                if (bBox == null && !dr.bodies.isEmpty()) {
                    bBox = fBox != null ? Detector.Result.findMatchingBody(fBox, dr.bodies) : new RectF(dr.bodies.get(0));
                }
            } catch (Throwable ignored) { }
        }

        if (fBox == null && bBox != null) {
            fBox = Detector.estimateFaceFromBody(w, h, bBox);
        }
        if (fBox == null) {
            fBox = detectHeadBySkinAndContour(latest);
        }
        if (bBox == null) {
            bBox = Detector.estimateBodyFromFace(w, h, fBox);
        }

        int[] faceArgb = cropPixels(latest, fBox, 0.10f);
        int fw = Math.max(8, (int) fBox.width());
        int fh = Math.max(8, (int) fBox.height());

        // Per-frame detection across temporal frames for genuine multi-angle capture
        Bitmap frameL = frames.size() >= 3 ? frames.get(0) : latest;
        RectF fBoxL = detectFaceInFrame(detector, frameL, fBox, latest);

        Bitmap frameR = frames.size() >= 2 ? frames.get(frames.size() / 2) : latest;
        RectF fBoxR = detectFaceInFrame(detector, frameR, fBox, latest);

        float[] vecFront = cropDescriptorFace(latest, fBox, 0.06f);
        float[] vecTight = cropDescriptorFace(latest, fBox, -0.06f);
        float[] vecLeft = cropDescriptorFace(frameL, fBoxL, 0.10f);
        float[] vecRight = cropDescriptorFace(frameR, fBoxR, 0.10f);

        List<float[]> angleVecList = new ArrayList<>();
        if (vecFront.length > 0) angleVecList.add(vecFront);
        if (vecTight.length > 0) angleVecList.add(vecTight);
        if (vecLeft.length > 0) angleVecList.add(vecLeft);
        if (vecRight.length > 0) angleVecList.add(vecRight);

        float[] faceVec = PersonDescriptor.fuseDescriptors(angleVecList);
        float[][] angleFaceArr = angleVecList.toArray(new float[0][]);

        BodyCropResult bodyCrop = extractPixelsWithSize(latest, bBox, 0.04f);
        float[] bodyVec = bodyCrop != null
                ? PersonDescriptor.fromBodyCrop(bodyCrop.pixels, bodyCrop.w, bodyCrop.h)
                : new float[0];
        float bodyAspect = bBox.height() > 1f ? (bBox.width() / bBox.height()) : 0.6f;
        PersonDescriptor.AppearanceProfile appearance = bodyCrop != null
                ? PersonDescriptor.analyzeAppearance(bodyCrop.pixels, bodyCrop.w, bodyCrop.h, bodyAspect)
                : PersonDescriptor.analyzeAppearance(faceArgb, fw, fh, 0.6f);

        // Angle 1: Front Face ID (tight head & facial structure crop)
        Bitmap angle01 = cropBitmapWithMargin(latest, fBox, 0.18f, 0.18f, 0.18f, 0.24f, 240, 240);

        // Angle 2: Left Oblique / Temporal Frame 1
        Bitmap angle02 = cropBitmapWithMargin(frameL, fBoxL, 0.36f, 0.12f, 0.22f, 0.32f, 240, 240);

        // Angle 3: Right Oblique / Temporal Frame 2
        Bitmap angle03 = cropBitmapWithMargin(frameR, fBoxR, 0.12f, 0.36f, 0.22f, 0.32f, 240, 240);

        // Angle 4: Full Body & Attire
        RectF fullPersonBox = new RectF(
                Math.min(fBox.left, bBox.left),
                Math.min(fBox.top, bBox.top),
                Math.max(fBox.right, bBox.right),
                Math.max(fBox.bottom, bBox.bottom)
        );
        Bitmap angle04 = cropBitmapWithMargin(latest, fullPersonBox, 0.14f, 0.14f, 0.08f, 0.12f, 240, 240);

        Bitmap[] angles = new Bitmap[]{angle01, angle02, angle03, angle04};
        Bitmap composite = renderCompositeBoard(angles, appearance,
                faceVec != null && faceVec.length > 0,
                bodyVec != null && bodyVec.length > 0);

        return new BoardExtraction(faceVec, angleFaceArr, bodyVec, angles, composite, appearance, angleVecList.size());
    }

    private static RectF detectFaceInFrame(Detector detector, Bitmap frame, RectF fallbackInLatest, Bitmap latest) {
        if (frame == null || frame.isRecycled()) return fallbackInLatest;
        if (detector != null && frame != latest) {
            try {
                List<RectF> faces = detector.faces(frame);
                RectF best = null;
                for (RectF f : faces) {
                    if (best == null || f.width() * f.height() > best.width() * best.height()) {
                        best = f;
                    }
                }
                if (best != null) return new RectF(best);
            } catch (Throwable ignored) { }
        }
        return scaleRectBetweenFrames(fallbackInLatest, latest, frame);
    }

    /**
     * Skin-chromaticity + head-contour fallback detector when BlazeFace short-range misses a slightly
     * distant or side-angled head. Finds the highest-density skin/head region in the upper 65% of the frame.
     */
    private static RectF detectHeadBySkinAndContour(Bitmap bm) {
        int w = bm.getWidth();
        int h = bm.getHeight();
        int step = Math.max(2, Math.min(w, h) / 48);
        double sumX = 0, sumY = 0, weightSum = 0;
        int minX = w, maxX = 0, minY = h, maxY = 0;

        for (int y = h / 12; y < (h * 62) / 100; y += step) {
            for (int x = w / 6; x < (w * 5) / 6; x += step) {
                int p = bm.getPixel(x, y);
                int r = (p >> 16) & 0xff;
                int g = (p >> 8) & 0xff;
                int b = p & 0xff;
                // YCbCr skin chromaticity + upper-center prior
                double cb = 128 - 0.168736 * r - 0.331264 * g + 0.5 * b;
                double cr = 128 + 0.5 * r - 0.418688 * g - 0.081312 * b;
                boolean isSkin = (r > 55 && g > 35 && b > 20 && r > g && r > b
                        && cb >= 77 && cb <= 132 && cr >= 131 && cr <= 178);
                if (isSkin) {
                    double centerBias = 1.0 - 0.5 * Math.abs((x - w * 0.5) / (w * 0.5));
                    sumX += x * centerBias;
                    sumY += y * centerBias;
                    weightSum += centerBias;
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
            }
        }
        if (weightSum > 8) {
            float cx = (float) (sumX / weightSum);
            float cy = (float) (sumY / weightSum);
            float boxW = Math.max(w * 0.24f, Math.min(w * 0.48f, (maxX - minX) * 1.35f));
            float boxH = boxW * 1.22f;
            float left = Math.max(0f, cx - boxW * 0.5f);
            float top = Math.max(0f, cy - boxH * 0.48f);
            float right = Math.min((float) w, left + boxW);
            float bottom = Math.min((float) h, top + boxH);
            return new RectF(left, top, right, bottom);
        }
        return new RectF(w * 0.28f, h * 0.14f, w * 0.72f, h * 0.52f);
    }

    private static float[] cropDescriptorFace(Bitmap bm, RectF box, float padFrac) {
        BodyCropResult r = extractPixelsWithSize(bm, box, padFrac);
        if (r == null) return new float[0];
        float[] v = PersonDescriptor.fromCrop(r.pixels, r.w, r.h);
        return v != null ? v : new float[0];
    }

    private static final class BodyCropResult {
        final int[] pixels;
        final int w;
        final int h;

        BodyCropResult(int[] pixels, int w, int h) {
            this.pixels = pixels;
            this.w = w;
            this.h = h;
        }
    }

    private static BodyCropResult extractPixelsWithSize(Bitmap bm, RectF box, float padFrac) {
        if (bm == null || bm.isRecycled() || box == null) return null;
        int bw = bm.getWidth(), bh = bm.getHeight();
        float pw = box.width() * padFrac;
        float ph = box.height() * padFrac;
        int x0 = Math.max(0, Math.round(box.left - pw));
        int y0 = Math.max(0, Math.round(box.top - ph));
        int x1 = Math.min(bw, Math.round(box.right + pw));
        int y1 = Math.min(bh, Math.round(box.bottom + ph));
        int cw = x1 - x0;
        int ch = y1 - y0;
        if (cw < 8 || ch < 8) return null;
        int[] px = new int[cw * ch];
        bm.getPixels(px, 0, cw, x0, y0, cw, ch);
        return new BodyCropResult(px, cw, ch);
    }

    private static int[] cropPixels(Bitmap bm, RectF box, float padFrac) {
        BodyCropResult r = extractPixelsWithSize(bm, box, padFrac);
        return r == null ? null : r.pixels;
    }

    private static RectF scaleRectBetweenFrames(RectF r, Bitmap src, Bitmap dst) {
        if (src == null || dst == null || (src.getWidth() == dst.getWidth() && src.getHeight() == dst.getHeight())) {
            return new RectF(r);
        }
        float sx = dst.getWidth() / (float) Math.max(1, src.getWidth());
        float sy = dst.getHeight() / (float) Math.max(1, src.getHeight());
        return new RectF(r.left * sx, r.top * sy, r.right * sx, r.bottom * sy);
    }

    private static Bitmap cropBitmapWithMargin(
            Bitmap src,
            RectF box,
            float padLeft,
            float padRight,
            float padTop,
            float padBottom,
            int outW,
            int outH
    ) {
        if (src == null || src.isRecycled()) {
            return Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
        }
        int sw = src.getWidth();
        int sh = src.getHeight();
        float bw = Math.max(16f, box.width());
        float bh = Math.max(16f, box.height());
        int x0 = Math.max(0, Math.round(box.left - bw * padLeft));
        int y0 = Math.max(0, Math.round(box.top - bh * padTop));
        int x1 = Math.min(sw, Math.round(box.right + bw * padRight));
        int y1 = Math.min(sh, Math.round(box.bottom + bh * padBottom));
        int cw = Math.max(1, x1 - x0);
        int ch = Math.max(1, y1 - y0);
        try {
            Bitmap sub = Bitmap.createBitmap(src, x0, y0, cw, ch);
            return Bitmap.createScaledBitmap(sub, outW, outH, true);
        } catch (Exception e) {
            return Bitmap.createScaledBitmap(src, outW, outH, true);
        }
    }

    /**
     * Renders a high-contrast 4-panel Character Sheet bitmap with HUD brackets and attire footer.
     */
    public static Bitmap renderCompositeBoard(
            Bitmap[] angles,
            PersonDescriptor.AppearanceProfile app,
            boolean hasFaceLock,
            boolean hasBodyLock
    ) {
        int W = 540;
        int H = 320;
        Bitmap out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        c.drawColor(0xFF0B1018);

        Paint pBg = new Paint(Paint.ANTI_ALIAS_FLAG);
        Paint pStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        pStroke.setStyle(Paint.Style.STROKE);
        pStroke.setStrokeWidth(2f);
        pStroke.setColor(0xFF2B3D54);

        Paint pText = new Paint(Paint.ANTI_ALIAS_FLAG);
        pText.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));

        // Top tactical header bar
        pBg.setColor(0xFF131C2B);
        c.drawRect(0, 0, W, 36, pBg);
        pText.setColor(0xFF34D399);
        pText.setTextSize(12f);
        c.drawText("◆ 4-ANGLE CHARACTER BOARD · FACE + BODY ID", 12, 23, pText);

        String lockTag = (hasFaceLock ? "FACE ✓  " : "") + (hasBodyLock ? "BODY ✓" : "");
        pText.setColor(0xFF7DD3FC);
        pText.setTextSize(10.5f);
        float tw = pText.measureText(lockTag);
        c.drawText(lockTag, W - tw - 12, 23, pText);

        int pad = 10;
        int gap = 8;
        int tileW = (W - pad * 2 - gap * 3) / 4;
        int tileH = 200;
        int topY = 46;

        Paint bracketPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bracketPaint.setStyle(Paint.Style.STROKE);
        bracketPaint.setStrokeWidth(2.5f);

        for (int i = 0; i < 4; i++) {
            int x = pad + i * (tileW + gap);
            Rect dst = new Rect(x, topY, x + tileW, topY + tileH);
            pBg.setColor(0xFF172130);
            c.drawRect(dst, pBg);

            if (angles != null && i < angles.length && angles[i] != null && !angles[i].isRecycled()) {
                c.drawBitmap(angles[i], null, dst, null);
            }

            // High-contrast label banner at bottom of each tile
            pBg.setColor(0xE6090D14);
            c.drawRect(x, topY + tileH - 26, x + tileW, topY + tileH, pBg);
            pText.setColor(i == 3 ? 0xFFFDE68A : 0xFFFFFFFF);
            pText.setTextSize(10f);
            c.drawText(ANGLE_LABELS[i], x + 6, topY + tileH - 9, pText);

            // Corner reticles
            bracketPaint.setColor(i == 3 ? 0xFFFBBF24 : 0xFF34D399);
            int bl = 12;
            c.drawLine(x + 2, topY + 2, x + 2 + bl, topY + 2, bracketPaint);
            c.drawLine(x + 2, topY + 2, x + 2, topY + 2 + bl, bracketPaint);
            c.drawLine(x + tileW - 2 - bl, topY + 2, x + tileW - 2, topY + 2, bracketPaint);
            c.drawLine(x + tileW - 2, topY + 2, x + tileW - 2, topY + 2 + bl, bracketPaint);
            c.drawLine(x + 2, topY + tileH - 2, x + 2 + bl, topY + tileH - 2, bracketPaint);
            c.drawLine(x + 2, topY + tileH - 2 - bl, x + 2, topY + tileH - 2, bracketPaint);
            c.drawLine(x + tileW - 2 - bl, topY + tileH - 2, x + tileW - 2, topY + tileH - 2, bracketPaint);
            c.drawLine(x + tileW - 2, topY + tileH - 2 - bl, x + tileW - 2, topY + tileH - 2, bracketPaint);

            c.drawRect(dst, pStroke);
        }

        // Bottom telemetry strip
        int footY = topY + tileH + 10;
        pBg.setColor(0xFF131C2B);
        c.drawRect(pad, footY, W - pad, H - 8, pBg);
        c.drawRect(pad, footY, W - pad, H - 8, pStroke);

        if (app != null) {
            pBg.setColor(app.upperColorRgb | 0xFF000000);
            c.drawRect(pad + 10, footY + 10, pad + 30, footY + 30, pBg);
            c.drawRect(pad + 10, footY + 10, pad + 30, footY + 30, pStroke);

            pBg.setColor(app.lowerColorRgb | 0xFF000000);
            c.drawRect(pad + 36, footY + 10, pad + 56, footY + 30, pBg);
            c.drawRect(pad + 36, footY + 10, pad + 56, footY + 30, pStroke);

            pText.setColor(0xFFFFFFFF);
            pText.setTextSize(11.5f);
            c.drawText("LOOK: " + app.attireSummary, pad + 66, footY + 21, pText);

            pText.setColor(0xFFCBD5E1);
            pText.setTextSize(10.5f);
            c.drawText("FRAME: " + app.buildSignature + "  ·  LOCAL ENCRYPTED ID", pad + 66, footY + 40, pText);
        }

        return out;
    }

    /**
     * Creates a synthetic demo Character Board entry so developers or hackathon judges can
     * immediately preview the 4-Angle Character Card & Info Chart UI even without someone in front of the camera.
     */
    public static PeopleVault.Person createSamplePerson(Context ctx) {
        Bitmap[] angles = new Bitmap[4];
        String[] angleSubtitles = new String[]{"FRONT FACE", "LEFT OBLIQUE", "RIGHT OBLIQUE", "FULL TORSO"};
        int[] accents = new int[]{0xFF34D399, 0xFF38BDF8, 0xFFA78BFA, 0xFFFBBF24};

        for (int i = 0; i < 4; i++) {
            Bitmap bm = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bm);
            c.drawColor(0xFF121A28);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            // Draw stylized avatar silhouette for each angle
            p.setColor(0xFF1E293B);
            c.drawCircle(120, 115, 86, p);
            // Torso / Shoulders
            p.setColor(0xFF2563EB);
            int shiftX = (i == 1 ? -14 : (i == 2 ? 14 : 0));
            if (i == 3) {
                // Full body framing
                c.drawRoundRect(new RectF(58, 110, 182, 232), 28, 28, p);
                p.setColor(0xFFF1C27D);
                c.drawCircle(120, 74, 30, p);
            } else {
                // Head & shoulders framing
                c.drawRoundRect(new RectF(44 + shiftX, 146, 196 + shiftX, 240), 36, 36, p);
                p.setColor(0xFFF1C27D);
                c.drawCircle(120 + shiftX, 98, 42, p);
                // Glasses / visor line
                p.setColor(0xFF0F172A);
                c.drawRoundRect(new RectF(90 + shiftX, 86, 150 + shiftX, 102), 6, 6, p);
            }
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(3f);
            p.setColor(accents[i]);
            c.drawRect(6, 6, 234, 234, p);

            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFFE2E8F0);
            p.setTextSize(13f);
            p.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            c.drawText("ANGLE 0" + (i + 1) + " · " + angleSubtitles[i], 18, 28, p);
            angles[i] = bm;
        }

        PersonDescriptor.AppearanceProfile app = new PersonDescriptor.AppearanceProfile(
                "Cobalt / Denim", 0xFF2563EB,
                "Charcoal Grey", 0xFF334155,
                "Structured", "Balanced Proportion",
                "Cobalt / Denim (Structured) · Lower Charcoal Grey"
        );
        Bitmap composite = renderCompositeBoard(angles, app, true, true);
        String[] angleB64 = new String[4];
        for (int i = 0; i < 4; i++) angleB64[i] = encodeJpeg(angles[i], 200, 82);
        String compB64 = encodeJpeg(composite, 540, 84);

        int[] synthFace = PersonDescriptor.synthesizeFace(48, 48, 0xFFF1C27D, 0xFF222222, 24.0, 42L);
        float[] faceVec = PersonDescriptor.fromCrop(synthFace, 48, 48);
        float[] bodyVec = PersonDescriptor.fromBodyCrop(synthFace, 48, 48);

        return PeopleVault.get(ctx).addFull(
                "Alex Rivera (Demo)",
                "Hackathon Teammate",
                "Met at Meta Ray-Ban table · testing live captions & 4-angle character recognition.",
                faceVec,
                bodyVec,
                angleB64[0],
                compB64,
                angleB64,
                app.attireSummary,
                app.buildSignature,
                app.upperColorRgb,
                app.lowerColorRgb,
                "Glasses Spotter HUD"
        );
    }

    /**
     * Opens the interactive "Save Person Character Board" dialog with a clean everyday layout,
     * live 4-Angle Re-Scanner, Local Relay AI Feature Analyzer, and Developer Biometrics section.
     */
    public static void showSavePersonDialog(
            Activity activity,
            List<Bitmap> recentFrames,
            Bitmap primaryFrame,
            RectF faceBox,
            RectF bodyBox,
            String contextLabel,
            OnPersonSavedListener listener
    ) {
        showSavePersonDialog(activity, recentFrames, primaryFrame, faceBox, bodyBox, contextLabel, null, listener);
    }

    public static void showSavePersonDialog(
            Activity activity,
            List<Bitmap> recentFrames,
            Bitmap primaryFrame,
            RectF faceBox,
            RectF bodyBox,
            String contextLabel,
            LiveFrameSupplier frameSupplier,
            OnPersonSavedListener listener
    ) {
        Detector det = frameSupplier != null ? frameSupplier.getDetector() : null;
        final List<Bitmap> workingFrames = new ArrayList<>();
        if (recentFrames != null) workingFrames.addAll(recentFrames);
        if (primaryFrame != null && !workingFrames.contains(primaryFrame)) workingFrames.add(primaryFrame);

        final BoardExtraction[] extHolder = new BoardExtraction[]{
                extractWithDetector(det, workingFrames, primaryFrame, faceBox, bodyBox)
        };
        if (extHolder[0] == null) {
            Toast.makeText(activity, "Point the camera at someone first to capture their Character Board.", Toast.LENGTH_SHORT).show();
            return;
        }

        ScrollView sv = new ScrollView(activity);
        sv.setBackgroundColor(0xFF0B0F17);
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(activity, 16), dp(activity, 16), dp(activity, 16), dp(activity, 16));
        sv.addView(root);

        TextView title = new TextView(activity);
        title.setText("◆ Save Person Character Board");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(18f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        final TextView sub = new TextView(activity);
        sub.setText(String.format(Locale.US,
                "Locked %d multi-angle descriptors + clothing & posture profile for instant recognition.",
                extHolder[0].detectedAnglesCount));
        sub.setTextColor(0xFFCBD5E1);
        sub.setTextSize(12.5f);
        sub.setPadding(0, dp(activity, 2), 0, dp(activity, 10));
        root.addView(sub);

        // 4-Angle Character Board Preview
        final ImageView boardImg = new ImageView(activity);
        LinearLayout.LayoutParams imgLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 190));
        boardImg.setLayoutParams(imgLp);
        boardImg.setScaleType(ImageView.ScaleType.FIT_CENTER);
        boardImg.setImageBitmap(extHolder[0].compositeBoard);
        root.addView(boardImg);

        // 4-Angle Manual Studio Grid & Guided Stepper
        final int[] activeSlot = new int[]{0}; // 0=Front, 1=Left 45°, 2=Right 45°, 3=Body
        final String[] slotLabels = new String[]{"1 · FRONT", "2 · LEFT 45°", "3 · RIGHT 45°", "4 · BODY"};
        final String[] slotGuides = new String[]{
                "👉 Step 1 of 4: Have them look directly into camera (Front Face ID).",
                "👉 Step 2 of 4: Have them turn their head 45° to their LEFT.",
                "👉 Step 3 of 4: Have them turn their head 45° to their RIGHT.",
                "👉 Step 4 of 4: Step back or tilt down to frame their TORSO & ATTIRE."
        };

        LinearLayout tilesContainer = new LinearLayout(activity);
        tilesContainer.setOrientation(LinearLayout.VERTICAL);
        tilesContainer.setBackgroundResource(R.drawable.bg_dark_card);
        tilesContainer.setPadding(dp(activity, 10), dp(activity, 8), dp(activity, 10), dp(activity, 8));
        LinearLayout.LayoutParams tcLp = new LinearLayout.LayoutParams(-1, -2);
        tcLp.topMargin = dp(activity, 10);
        tilesContainer.setLayoutParams(tcLp);

        TextView studioHeaderTv = new TextView(activity);
        studioHeaderTv.setText("🎯 4-ANGLE GUIDED CAPTURE STUDIO");
        studioHeaderTv.setTextColor(0xFF7DD3FC);
        studioHeaderTv.setTextSize(11.5f);
        studioHeaderTv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        tilesContainer.addView(studioHeaderTv);

        LinearLayout tilesRow = new LinearLayout(activity);
        tilesRow.setOrientation(LinearLayout.HORIZONTAL);
        tilesRow.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        tilesRow.setPadding(0, dp(activity, 6), 0, dp(activity, 6));

        final ImageView[] slotImgs = new ImageView[4];
        final TextView[] slotBadges = new TextView[4];
        final LinearLayout[] slotCards = new LinearLayout[4];

        for (int i = 0; i < 4; i++) {
            final int slotIdx = i;
            LinearLayout card = new LinearLayout(activity);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.CENTER_HORIZONTAL);
            LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(0, -2, 1f);
            if (i > 0) cLp.leftMargin = dp(activity, 5);
            card.setLayoutParams(cLp);
            card.setPadding(dp(activity, 4), dp(activity, 6), dp(activity, 4), dp(activity, 6));
            card.setBackgroundResource(R.drawable.bg_dark_subcard);

            TextView lbl = new TextView(activity);
            lbl.setText(slotLabels[i]);
            lbl.setTextColor(0xFFCBD5E1);
            lbl.setTextSize(9.5f);
            lbl.setTypeface(Typeface.DEFAULT_BOLD);
            lbl.setGravity(Gravity.CENTER);
            card.addView(lbl);

            ImageView iv = new ImageView(activity);
            iv.setLayoutParams(new LinearLayout.LayoutParams(dp(activity, 52), dp(activity, 52)));
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            if (extHolder[0].angleBitmaps[i] != null && !extHolder[0].angleBitmaps[i].isRecycled()) {
                iv.setImageBitmap(extHolder[0].angleBitmaps[i]);
            }
            card.addView(iv);
            slotImgs[i] = iv;

            TextView badge = new TextView(activity);
            boolean hasAngle = extHolder[0].angleBitmaps[i] != null && !extHolder[0].angleBitmaps[i].isRecycled();
            badge.setText(hasAngle ? "✓ Locked" : "Snap");
            badge.setTextColor(hasAngle ? 0xFF34D399 : 0xFF7DD3FC);
            badge.setTextSize(9.5f);
            badge.setTypeface(Typeface.DEFAULT_BOLD);
            badge.setPadding(0, dp(activity, 2), 0, 0);
            card.addView(badge);
            slotBadges[i] = badge;

            card.setOnClickListener(v -> {
                activeSlot[0] = slotIdx;
                for (int k = 0; k < 4; k++) {
                    slotCards[k].setBackgroundColor(k == activeSlot[0] ? 0xFF1E293B : 0xFF0F172A);
                }
            });
            slotCards[i] = card;
            tilesRow.addView(card);
        }
        tilesContainer.addView(tilesRow);

        final TextView guideTv = new TextView(activity);
        guideTv.setText(slotGuides[0]);
        guideTv.setTextColor(0xFFFDE68A);
        guideTv.setTextSize(11.5f);
        guideTv.setTypeface(Typeface.DEFAULT_BOLD);
        guideTv.setPadding(0, dp(activity, 4), 0, dp(activity, 6));
        tilesContainer.addView(guideTv);

        LinearLayout actionRow = new LinearLayout(activity);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setGravity(Gravity.CENTER_VERTICAL);
        actionRow.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));

        final TextView aiStatusTv = new TextView(activity);
        aiStatusTv.setText("✨ Relay AI: analyzing…");
        aiStatusTv.setTextColor(0xFF34D399);
        aiStatusTv.setTextSize(11f);
        aiStatusTv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        aiStatusTv.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        actionRow.addView(aiStatusTv);

        final Button btnSnap = new Button(activity);
        btnSnap.setText("📸 Snap " + slotLabels[0]);
        btnSnap.setAllCaps(false);
        btnSnap.setTextColor(0xFFFFFFFF);
        btnSnap.setTextSize(11.5f);
        btnSnap.setTypeface(Typeface.DEFAULT_BOLD);
        btnSnap.setMinHeight(dp(activity, 38));
        btnSnap.setMinimumHeight(dp(activity, 38));
        btnSnap.setPadding(dp(activity, 12), dp(activity, 4), dp(activity, 12), dp(activity, 4));
        btnSnap.setBackgroundResource(R.drawable.bg_accent_emerald);
        actionRow.addView(btnSnap);

        tilesContainer.addView(actionRow);
        root.addView(tilesContainer);

        // Digestible Summary Card
        final LinearLayout summaryCard = new LinearLayout(activity);
        summaryCard.setOrientation(LinearLayout.VERTICAL);
        summaryCard.setBackgroundResource(R.drawable.bg_dark_card);
        summaryCard.setPadding(dp(activity, 12), dp(activity, 10), dp(activity, 12), dp(activity, 10));
        LinearLayout.LayoutParams sumLp = new LinearLayout.LayoutParams(-1, -2);
        sumLp.topMargin = dp(activity, 8);
        summaryCard.setLayoutParams(sumLp);

        final String[] enrichedAttire = new String[]{extHolder[0].appearance.attireSummary};
        final String[] enrichedBuild = new String[]{extHolder[0].appearance.buildSignature};
        final String[] enrichedFeatures = new String[]{"Multi-angle Face + Body Lock"};

        Runnable rebuildSummaryRows = () -> {
            summaryCard.removeAllViews();
            addChartRow(activity, summaryCard, "Look & Attire", enrichedAttire[0], 0xFFFFFFFF);
            addChartRow(activity, summaryCard, "Features", enrichedFeatures[0], 0xFFFDE68A);
            addChartRow(activity, summaryCard, "Posture / Build", enrichedBuild[0], 0xFF7DD3FC);
            addChartRow(activity, summaryCard, "Captured Via", contextLabel != null ? contextLabel : "Spotter Camera", 0xFF34D399);
        };
        rebuildSummaryRows.run();
        root.addView(summaryCard);

        Runnable refreshStudioViews = () -> {
            for (int k = 0; k < 4; k++) {
                slotCards[k].setBackgroundColor(k == activeSlot[0] ? 0xFF1E293B : 0xFF0F172A);
                if (extHolder[0].angleBitmaps[k] != null && !extHolder[0].angleBitmaps[k].isRecycled()) {
                    slotImgs[k].setImageBitmap(extHolder[0].angleBitmaps[k]);
                    slotBadges[k].setText("✓ Locked");
                    slotBadges[k].setTextColor(0xFF34D399);
                } else {
                    slotBadges[k].setText("Snap");
                    slotBadges[k].setTextColor(0xFF7DD3FC);
                }
            }
            guideTv.setText(slotGuides[activeSlot[0]]);
            btnSnap.setText("📸 Snap " + slotLabels[activeSlot[0]]);
            boardImg.setImageBitmap(extHolder[0].compositeBoard);
            sub.setText(String.format(Locale.US,
                    "Locked %d multi-angle descriptors + clothing & posture profile for instant recognition.",
                    extHolder[0].detectedAnglesCount));
        };
        refreshStudioViews.run();

        btnSnap.setOnClickListener(v -> {
            btnSnap.setEnabled(false);
            btnSnap.setText("Snapping…");
            new Thread(() -> {
                try {
                    Bitmap fresh = frameSupplier != null ? frameSupplier.captureFreshFrame() : primaryFrame;
                    Detector d = frameSupplier != null ? frameSupplier.getDetector() : det;
                    if (fresh == null || fresh.isRecycled()) fresh = primaryFrame;
                    if (fresh != null && !fresh.isRecycled()) {
                        int slot = activeSlot[0];
                        Detector.Result r = d != null ? d.detect(fresh, false, true) : null;
                        RectF fBox = null;
                        if (r != null && !r.faces.isEmpty()) {
                            for (RectF f : r.faces) {
                                if (fBox == null || f.width() * f.height() > fBox.width() * fBox.height()) {
                                    fBox = new RectF(f);
                                }
                            }
                        }
                        RectF bBox = null;
                        if (r != null && !r.bodies.isEmpty()) {
                            bBox = fBox != null ? Detector.Result.findMatchingBody(fBox, r.bodies) : new RectF(r.bodies.get(0));
                        }
                        int fw = fresh.getWidth(), fh = fresh.getHeight();
                        if (fBox == null && bBox != null) fBox = Detector.estimateFaceFromBody(fw, fh, bBox);
                        if (bBox == null && fBox != null) bBox = Detector.estimateBodyFromFace(fw, fh, fBox);

                        if ((slot == 0 || slot == 1 || slot == 2) && fBox != null) {
                            float mLeft = (slot == 1) ? 0.36f : ((slot == 2) ? 0.12f : 0.18f);
                            float mRight = (slot == 1) ? 0.12f : ((slot == 2) ? 0.36f : 0.18f);
                            Bitmap angleBmp = cropBitmapWithMargin(fresh, fBox, mLeft, mRight, 0.18f, 0.24f, 240, 240);
                            float[] vec = cropDescriptorFace(fresh, fBox, 0.08f);
                            extHolder[0].angleBitmaps[slot] = angleBmp;
                            extHolder[0].angleBase64[slot] = encodeJpeg(angleBmp, 200, 80);
                            if (slot == 0) extHolder[0].primaryThumbBase64 = extHolder[0].angleBase64[0];

                            List<float[]> allFaces = new ArrayList<>();
                            if (extHolder[0].angleFaceDescriptors != null) {
                                for (float[] av : extHolder[0].angleFaceDescriptors) {
                                    if (av != null && av.length > 0) allFaces.add(av);
                                }
                            }
                            if (vec != null && vec.length > 0) allFaces.add(vec);
                            extHolder[0].angleFaceDescriptors = allFaces.toArray(new float[0][]);
                            extHolder[0].faceDescriptor = PersonDescriptor.fuseDescriptors(allFaces);
                            extHolder[0].detectedAnglesCount = allFaces.size();
                        } else if (slot == 3 && bBox != null) {
                            Bitmap bodyBmp = cropBitmapWithMargin(fresh, bBox, 0.12f, 0.12f, 0.08f, 0.12f, 240, 240);
                            extHolder[0].angleBitmaps[3] = bodyBmp;
                            extHolder[0].angleBase64[3] = encodeJpeg(bodyBmp, 200, 80);
                            BodyCropResult bc = extractPixelsWithSize(fresh, bBox, 0.04f);
                            if (bc != null) {
                                extHolder[0].bodyDescriptor = PersonDescriptor.fromBodyCrop(bc.pixels, bc.w, bc.h);
                                float aspect = bBox.height() > 1f ? (bBox.width() / bBox.height()) : 0.6f;
                                extHolder[0].appearance = PersonDescriptor.analyzeAppearance(bc.pixels, bc.w, bc.h, aspect);
                            }
                        }

                        extHolder[0].compositeBoard = renderCompositeBoard(
                                extHolder[0].angleBitmaps,
                                extHolder[0].appearance,
                                extHolder[0].faceDescriptor != null && extHolder[0].faceDescriptor.length > 0,
                                extHolder[0].bodyDescriptor != null && extHolder[0].bodyDescriptor.length > 0
                        );
                        extHolder[0].compositeBase64 = encodeJpeg(extHolder[0].compositeBoard, 560, 82);

                        // Advance to next angle slot automatically
                        activeSlot[0] = (activeSlot[0] + 1) % 4;
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "snapSingleAngle: " + t);
                }
                activity.runOnUiThread(() -> {
                    btnSnap.setEnabled(true);
                    refreshStudioViews.run();
                    rebuildSummaryRows.run();
                });
            }, "board-snap").start();
        });

        // Trigger async Local Relay /v1/people/analyze enrichment
        analyzeWithRelayAsync(activity, extHolder[0].compositeBase64,
                extHolder[0].appearance.attireSummary, extHolder[0].appearance.buildSignature,
                (attire, features, build) -> activity.runOnUiThread(() -> {
                    if (attire != null && !attire.isEmpty()) enrichedAttire[0] = attire;
                    if (features != null && !features.isEmpty()) {
                        enrichedFeatures[0] = features;
                        extHolder[0].aiFeatures = features;
                    }
                    if (build != null && !build.isEmpty()) enrichedBuild[0] = build;
                    aiStatusTv.setText("✨ Relay AI Vision: attire & facial features locked ✓");
                    rebuildSummaryRows.run();
                }));

        // Name Input
        root.addView(fieldLabel(activity, "PERSON'S NAME"));
        EditText etName = styledInput(activity, "e.g. Alex Rivera", true);
        root.addView(etName);

        // Role Input + Preset Chips
        root.addView(fieldLabel(activity, "WHO ARE THEY TO YOU? (ROLE / TAG)"));
        EditText etRole = styledInput(activity, "e.g. Teammate, Friend, Judge, Founder", true);
        etRole.setText("Teammate");
        root.addView(etRole);

        HorizontalScrollView chipScroll = new HorizontalScrollView(activity);
        chipScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout chipRow = new LinearLayout(activity);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        String[] rolePresets = new String[]{"Teammate", "Friend", "Hackathon Judge", "Founder", "VIP", "Coworker"};
        for (String preset : rolePresets) {
            TextView chip = new TextView(activity);
            chip.setText(preset);
            chip.setTextColor(0xFF7DD3FC);
            chip.setTextSize(12f);
            chip.setTypeface(Typeface.DEFAULT_BOLD);
            chip.setPadding(dp(activity, 12), dp(activity, 6), dp(activity, 12), dp(activity, 6));
            chip.setBackgroundResource(R.drawable.bg_dark_pill);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2);
            clp.rightMargin = dp(activity, 6);
            clp.topMargin = dp(activity, 6);
            chip.setLayoutParams(clp);
            chip.setOnClickListener(v -> etRole.setText(preset));
            chipRow.addView(chip);
        }
        chipScroll.addView(chipRow);
        root.addView(chipScroll);

        // Notes Input
        root.addView(fieldLabel(activity, "NOTES / CONTEXT TO REMEMBER (OPTIONAL)"));
        EditText etNotes = styledInput(activity, "e.g. Met at demo booth — talked about Meta Ray-Ban stream", false);
        root.addView(etNotes);

        // Collapsible Developer Biometrics Section
        root.addView(buildDevTelemetryToggle(
                activity,
                extHolder[0].faceDescriptor.length,
                extHolder[0].bodyDescriptor.length,
                1.0,
                1.0,
                1.0,
                extHolder[0].appearance.upperColorRgb,
                extHolder[0].appearance.lowerColorRgb,
                "NEW_ENROLLMENT"
        ));

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setView(sv)
                .setPositiveButton("Save Character Board", null)
                .setNegativeButton("Cancel", null)
                .create();

        dialog.setOnShowListener(d -> {
            Button saveBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            saveBtn.setOnClickListener(v -> {
                String name = etName.getText().toString().trim();
                if (name.isEmpty()) {
                    etName.setError("Please enter their name");
                    return;
                }
                String role = etRole.getText().toString().trim();
                String userNotes = etNotes.getText().toString().trim();
                String fullAttire = enrichedAttire[0];
                if (!extHolder[0].aiFeatures.isEmpty() && !fullAttire.contains(extHolder[0].aiFeatures)) {
                    fullAttire = fullAttire + " · " + extHolder[0].aiFeatures;
                }
                PeopleVault.Person saved = PeopleVault.get(activity).addFullWithAngles(
                        name,
                        role.isEmpty() ? "Contact" : role,
                        userNotes,
                        extHolder[0].faceDescriptor,
                        extHolder[0].angleFaceDescriptors,
                        extHolder[0].bodyDescriptor,
                        extHolder[0].primaryThumbBase64,
                        extHolder[0].compositeBase64,
                        extHolder[0].angleBase64,
                        fullAttire,
                        enrichedBuild[0],
                        extHolder[0].appearance.upperColorRgb,
                        extHolder[0].appearance.lowerColorRgb,
                        contextLabel
                );
                dialog.dismiss();
                if (listener != null) listener.onSaved(saved);
            });
        });

        dialog.show();
    }

    private interface RelayAnalysisCallback {
        void onAnalyzed(String attire, String features, String build);
    }

    private static void analyzeWithRelayAsync(
            Context ctx,
            String imageB64,
            String attireHint,
            String buildHint,
            RelayAnalysisCallback cb
    ) {
        if (ctx == null || imageB64 == null || imageB64.isEmpty()) return;
        new Thread(() -> {
            try {
                android.content.SharedPreferences prefs = ctx.getApplicationContext()
                        .getSharedPreferences("fieldkit", Context.MODE_PRIVATE);
                String relay = prefs.getString("relay", null);
                String token = prefs.getString("token", null);
                if (relay == null || token == null || relay.isEmpty() || token.isEmpty()) return;
                String url = relay.replaceAll("/+$", "") + "/v1/people/analyze";
                org.json.JSONObject payload = new org.json.JSONObject()
                        .put("image", imageB64)
                        .put("attire_hint", attireHint != null ? attireHint : "")
                        .put("build_hint", buildHint != null ? buildHint : "");
                okhttp3.OkHttpClient client = new okhttp3.OkHttpClient.Builder()
                        .connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(4, java.util.concurrent.TimeUnit.SECONDS)
                        .build();
                okhttp3.Request req = new okhttp3.Request.Builder()
                        .url(url)
                        .header("Authorization", "Bearer " + token)
                        .post(okhttp3.RequestBody.create(
                                payload.toString(),
                                okhttp3.MediaType.get("application/json; charset=utf-8")))
                        .build();
                try (okhttp3.Response r = client.newCall(req).execute()) {
                    if (r.isSuccessful() && r.body() != null) {
                        org.json.JSONObject o = new org.json.JSONObject(r.body().string());
                        cb.onAnalyzed(
                                o.optString("attire", ""),
                                o.optString("features", ""),
                                o.optString("build", "")
                        );
                    }
                }
            } catch (Throwable ignored) { }
        }, "relay-person-analyze").start();
    }

    /**
     * Populates a live popup Character Card container when a saved person is recognized in Spotter.
     * Features high-contrast text, all 4 character angles, a clear everyday info chart, and an
     * optional expandable Developer Biometrics toggle.
     */
    public static void populateRecognitionCard(
            Context ctx,
            LinearLayout container,
            PeopleVault.Person p,
            double combinedScore,
            double faceScore,
            double bodyScore,
            Runnable onDismiss,
            Runnable onOpenFullBoard
    ) {
        if (container == null || p == null) return;
        container.removeAllViews();
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12));

        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(0xF50D131F);
        cardBg.setCornerRadius(dp(ctx, 16));
        cardBg.setStroke(dp(ctx, 1.5f), 0xFF10B981);
        container.setBackground(cardBg);

        // 1. Top Header Row: Recognized Pill + Name & Role + Action Buttons
        LinearLayout header = new LinearLayout(ctx);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout nameCol = new LinearLayout(ctx);
        nameCol.setOrientation(LinearLayout.VERTICAL);
        nameCol.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));

        int pct = (int) Math.round(Math.min(0.99, Math.max(0.82, combinedScore)) * 100);
        TextView badge = new TextView(ctx);
        badge.setText(String.format(Locale.US, "◆ PERSON RECOGNIZED · %d%% MATCH", pct));
        badge.setTextColor(0xFF34D399);
        badge.setTextSize(10.5f);
        badge.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        nameCol.addView(badge);

        TextView tvName = new TextView(ctx);
        String roleTag = (p.role != null && !p.role.isEmpty()) ? "  ·  " + p.role : "";
        tvName.setText(p.name + roleTag);
        tvName.setTextColor(0xFFFFFFFF);
        tvName.setTextSize(17.5f);
        tvName.setTypeface(Typeface.DEFAULT_BOLD);
        tvName.setSingleLine(true);
        tvName.setEllipsize(TextUtils.TruncateAt.END);
        nameCol.addView(tvName);

        header.addView(nameCol);

        if (onOpenFullBoard != null) {
            Button btnExpand = new Button(ctx);
            btnExpand.setText("Full Board");
            btnExpand.setAllCaps(false);
            btnExpand.setSingleLine(true);
            btnExpand.setTextColor(0xFF7DD3FC);
            btnExpand.setTextSize(11.5f);
            btnExpand.setTypeface(Typeface.DEFAULT_BOLD);
            btnExpand.setMinHeight(dp(ctx, 34));
            btnExpand.setMinimumHeight(dp(ctx, 34));
            btnExpand.setPadding(dp(ctx, 10), dp(ctx, 2), dp(ctx, 10), dp(ctx, 2));
            btnExpand.setBackgroundResource(R.drawable.bg_dark_pill);
            btnExpand.setOnClickListener(v -> onOpenFullBoard.run());
            header.addView(btnExpand);
        }

        if (onDismiss != null) {
            Button btnClose = new Button(ctx);
            btnClose.setText("✕");
            btnClose.setTextColor(0xFFF8FAFC);
            btnClose.setTextSize(13f);
            btnClose.setMinHeight(dp(ctx, 34));
            btnClose.setMinimumHeight(dp(ctx, 34));
            btnClose.setPadding(dp(ctx, 10), dp(ctx, 2), dp(ctx, 10), dp(ctx, 2));
            btnClose.setBackgroundResource(R.drawable.bg_dark_pill);
            LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(dp(ctx, 38), dp(ctx, 34));
            closeLp.leftMargin = dp(ctx, 6);
            btnClose.setLayoutParams(closeLp);
            btnClose.setOnClickListener(v -> onDismiss.run());
            header.addView(btnClose);
        }
        container.addView(header);

        // 2. 4-Angle Strip (4 distinct angle tiles with high-contrast labels)
        LinearLayout angleStrip = new LinearLayout(ctx);
        angleStrip.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams stripLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 84));
        stripLp.topMargin = dp(ctx, 10);
        angleStrip.setLayoutParams(stripLp);

        boolean hasIndividualAngles = false;
        if (p.angleThumbs != null) {
            for (String a : p.angleThumbs) {
                if (a != null && !a.isEmpty()) {
                    hasIndividualAngles = true;
                    break;
                }
            }
        }

        if (hasIndividualAngles) {
            String[] shortLabels = new String[]{"1 · FACE", "2 · LEFT", "3 · RIGHT", "4 · BODY"};
            for (int i = 0; i < 4; i++) {
                LinearLayout cell = new LinearLayout(ctx);
                cell.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams cellLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
                if (i < 3) cellLp.rightMargin = dp(ctx, 5);
                cell.setLayoutParams(cellLp);
                cell.setBackgroundResource(R.drawable.bg_dark_subcard);
                cell.setPadding(dp(ctx, 2), dp(ctx, 2), dp(ctx, 2), dp(ctx, 2));

                ImageView iv = new ImageView(ctx);
                iv.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                String b64 = (p.angleThumbs != null && i < p.angleThumbs.length && !p.angleThumbs[i].isEmpty())
                        ? p.angleThumbs[i] : p.thumb;
                Bitmap bm = decodeBase64(b64);
                if (bm != null) iv.setImageBitmap(bm);
                cell.addView(iv);

                TextView cap = new TextView(ctx);
                cap.setText(shortLabels[i]);
                cap.setTextColor(i == 3 ? 0xFFFDE68A : 0xFFFFFFFF);
                cap.setTextSize(9.5f);
                cap.setGravity(Gravity.CENTER);
                cap.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
                cap.setBackgroundColor(0xEE090D14);
                cap.setPadding(0, dp(ctx, 2), 0, dp(ctx, 2));
                cell.addView(cap);

                angleStrip.addView(cell);
            }
            container.addView(angleStrip);
        } else {
            ImageView iv = new ImageView(ctx);
            iv.setLayoutParams(stripLp);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            Bitmap bm = decodeBase64(!p.boardThumb.isEmpty() ? p.boardThumb : p.thumb);
            if (bm != null) iv.setImageBitmap(bm);
            container.addView(iv);
        }

        // 3. Digestible Everyday Info Chart
        LinearLayout infoChart = new LinearLayout(ctx);
        infoChart.setOrientation(LinearLayout.VERTICAL);
        infoChart.setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10));
        infoChart.setBackgroundResource(R.drawable.bg_dark_subcard);
        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        infoLp.topMargin = dp(ctx, 8);
        infoChart.setLayoutParams(infoLp);

        String lastSeenStr = formatRelativeAndTime(ctx, p.lastSeenAt)
                + " · " + (p.lastSeenContext != null && !p.lastSeenContext.isEmpty() ? p.lastSeenContext : "Spotter HUD");
        addCompactChartRow(ctx, infoChart, "Last Seen", lastSeenStr, 0xFF34D399);

        String historyStr = "Spotted " + Math.max(1, p.seenCount) + "x  ·  First met " + formatShortDate(ctx, p.savedAt);
        addCompactChartRow(ctx, infoChart, "History", historyStr, 0xFFF8FAFC);

        if (p.attire != null && !p.attire.isEmpty()) {
            String attireLine = p.attire + (p.buildSummary != null && !p.buildSummary.isEmpty()
                    ? " · " + p.buildSummary : "");
            addCompactChartRow(ctx, infoChart, "Look / Attire", attireLine, 0xFF7DD3FC);
        }

        if (p.notes != null && !p.notes.isEmpty()) {
            addCompactChartRow(ctx, infoChart, "Notes", p.notes, 0xFFFDE68A);
        }

        container.addView(infoChart);

        // 4. Collapsible Developer Biometric Telemetry inside the card
        container.addView(buildDevTelemetryToggle(
                ctx,
                p.face != null ? p.face.length : 0,
                p.body != null ? p.body.length : 0,
                combinedScore,
                faceScore,
                bodyScore,
                p.upperColorRgb,
                p.lowerColorRgb,
                p.id
        ));
    }

    /**
     * Opens a full-screen Character Board Dossier dialog for viewing or editing a saved person.
     */
    public static void showFullDossierDialog(
            Activity activity,
            PeopleVault.Person p,
            Runnable onUpdated
    ) {
        if (p == null) return;
        ScrollView sv = new ScrollView(activity);
        sv.setBackgroundColor(0xFF090D14);
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(activity, 16), dp(activity, 16), dp(activity, 16), dp(activity, 16));
        sv.addView(root);

        TextView header = new TextView(activity);
        header.setText("◆ Character Board // " + p.name);
        header.setTextColor(0xFFFFFFFF);
        header.setTextSize(18f);
        header.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(header);

        TextView roleSub = new TextView(activity);
        roleSub.setText((p.role.isEmpty() ? "Contact" : p.role) + "  ·  Spotted " + Math.max(1, p.seenCount) + " times");
        roleSub.setTextColor(0xFF34D399);
        roleSub.setTextSize(13f);
        roleSub.setTypeface(Typeface.DEFAULT_BOLD);
        roleSub.setPadding(0, dp(activity, 2), 0, dp(activity, 10));
        root.addView(roleSub);

        Bitmap boardBm = decodeBase64(!p.boardThumb.isEmpty() ? p.boardThumb : p.thumb);
        if (boardBm != null) {
            ImageView iv = new ImageView(activity);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 195));
            iv.setLayoutParams(lp);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setImageBitmap(boardBm);
            root.addView(iv);
        }

        // Digestible Info Table
        LinearLayout table = new LinearLayout(activity);
        table.setOrientation(LinearLayout.VERTICAL);
        table.setPadding(dp(activity, 14), dp(activity, 12), dp(activity, 14), dp(activity, 12));
        table.setBackgroundResource(R.drawable.bg_dark_card);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(-1, -2);
        tLp.topMargin = dp(activity, 12);
        table.setLayoutParams(tLp);

        addChartRow(activity, table, "Who They Are", p.name + " (" + (p.role.isEmpty() ? "Contact" : p.role) + ")", 0xFFFFFFFF);
        addChartRow(activity, table, "Last Seen",
                formatRelativeAndTime(activity, p.lastSeenAt) + " · " + p.lastSeenContext, 0xFF34D399);
        addChartRow(activity, table, "Times Spotted",
                p.seenCount + " sightings  ·  First met " + formatShortDate(activity, p.savedAt), 0xFFF8FAFC);
        addChartRow(activity, table, "Look & Attire",
                (p.attire.isEmpty() ? "Standard" : p.attire) + " · "
                        + (p.buildSummary.isEmpty() ? "Balanced Frame" : p.buildSummary), 0xFF7DD3FC);
        addChartRow(activity, table, "Voice Recognition",
                p.voice.length > 0 ? "Voiceprint Enrolled ✓" : "Not recorded yet",
                p.voice.length > 0 ? 0xFF34D399 : 0xFF94A3B8);
        root.addView(table);

        // Editable fields
        root.addView(fieldLabel(activity, "EDIT NAME"));
        EditText etName = styledInput(activity, "Name", true);
        etName.setText(p.name);
        root.addView(etName);

        root.addView(fieldLabel(activity, "EDIT ROLE / RELATIONSHIP"));
        EditText etRole = styledInput(activity, "Role / Tag", true);
        etRole.setText(p.role);
        root.addView(etRole);

        root.addView(fieldLabel(activity, "EDIT NOTES / CONTEXT"));
        EditText etNotes = styledInput(activity, "Notes about this person", false);
        etNotes.setText(p.notes);
        root.addView(etNotes);

        // Developer Biometrics Drawer
        root.addView(buildDevTelemetryToggle(
                activity,
                p.face != null ? p.face.length : 0,
                p.body != null ? p.body.length : 0,
                p.lastMatchScore,
                p.lastMatchScore,
                p.lastMatchScore,
                p.upperColorRgb,
                p.lowerColorRgb,
                p.id
        ));

        new AlertDialog.Builder(activity)
                .setView(sv)
                .setPositiveButton("Save Changes", (d, w) -> {
                    PeopleVault.get(activity).updateDossier(
                            p.id,
                            etName.getText().toString(),
                            etRole.getText().toString(),
                            etNotes.getText().toString()
                    );
                    if (onUpdated != null) onUpdated.run();
                })
                .setNeutralButton("Close", null)
                .show();
    }

    public static View buildDevTelemetryToggle(
            Context ctx,
            int faceDims,
            int bodyDims,
            double combinedScore,
            double faceScore,
            double bodyScore,
            int upperRgb,
            int lowerRgb,
            String vaultId
    ) {
        LinearLayout wrap = new LinearLayout(ctx);
        wrap.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(ctx, 8);
        wrap.setLayoutParams(lp);

        final TextView toggle = new TextView(ctx);
        toggle.setText("▸ Biometric & Vector Telemetry (Dev)");
        toggle.setTextColor(0xFF94A3B8);
        toggle.setTextSize(11f);
        toggle.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        toggle.setPadding(dp(ctx, 4), dp(ctx, 4), dp(ctx, 4), dp(ctx, 4));
        wrap.addView(toggle);

        final LinearLayout body = new LinearLayout(ctx);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setBackgroundResource(R.drawable.bg_dark_subcard);
        body.setPadding(dp(ctx, 10), dp(ctx, 8), dp(ctx, 10), dp(ctx, 8));
        body.setVisibility(View.GONE);

        TextView tv = new TextView(ctx);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextSize(10.5f);
        tv.setTextColor(0xFFCBD5E1);
        tv.setText(String.format(Locale.US,
                "FACE VECTOR   : %dD L2-Norm (8x8 Lum + 4x4x3 Col + 12 HOG)\n"
              + "BODY VECTOR   : %dD L2-Norm (4x8 Sil + 4x6x3 Garment + 8 Edge)\n"
              + "COSINE SCORES : Combined %.3f (Face %.3f · Body %.3f)\n"
              + "GARMENT HEX   : Upper #%06X · Lower #%06X\n"
              + "KEYSTORE SLOT : AES-256-GCM · ID=%s",
                faceDims, bodyDims,
                Math.max(0.0, combinedScore), Math.max(0.0, faceScore), Math.max(0.0, bodyScore),
                upperRgb & 0xFFFFFF, lowerRgb & 0xFFFFFF,
                vaultId != null ? vaultId : "local"
        ));
        body.addView(tv);
        wrap.addView(body);

        toggle.setOnClickListener(v -> {
            boolean show = body.getVisibility() != View.VISIBLE;
            body.setVisibility(show ? View.VISIBLE : View.GONE);
            toggle.setText((show ? "▾ " : "▸ ") + "Biometric & Vector Telemetry (Dev)");
        });
        return wrap;
    }

    private static TextView fieldLabel(Context ctx, String text) {
        TextView lbl = new TextView(ctx);
        lbl.setText(text);
        lbl.setTextColor(0xFF94A3B8);
        lbl.setTextSize(11f);
        lbl.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(ctx, 12);
        lp.bottomMargin = dp(ctx, 4);
        lbl.setLayoutParams(lp);
        return lbl;
    }

    private static EditText styledInput(Context ctx, String hint, boolean singleLine) {
        EditText et = new EditText(ctx);
        et.setHint(hint);
        et.setHintTextColor(0xFF64748B);
        et.setTextColor(0xFFFFFFFF);
        et.setTextSize(14.5f);
        et.setSingleLine(singleLine);
        if (!singleLine) et.setMaxLines(3);
        et.setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10));
        et.setBackgroundResource(R.drawable.bg_dark_subcard);
        return et;
    }

    private static void addChartRow(Context ctx, LinearLayout parent, String label, String value, int valColor) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(ctx, 3), 0, dp(ctx, 3));

        TextView k = new TextView(ctx);
        k.setText(label);
        k.setTextColor(0xFF94A3B8);
        k.setTextSize(12f);
        k.setTypeface(Typeface.DEFAULT_BOLD);
        k.setLayoutParams(new LinearLayout.LayoutParams(dp(ctx, 112), -2));

        TextView v = new TextView(ctx);
        v.setText(value);
        v.setTextColor(valColor);
        v.setTextSize(12.5f);
        v.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));

        row.addView(k);
        row.addView(v);
        parent.addView(row);
    }

    private static void addCompactChartRow(Context ctx, LinearLayout parent, String label, String value, int valColor) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(ctx, 2), 0, dp(ctx, 2));

        TextView k = new TextView(ctx);
        k.setText(label);
        k.setTextColor(0xFF94A3B8);
        k.setTextSize(11.5f);
        k.setTypeface(Typeface.DEFAULT_BOLD);
        k.setLayoutParams(new LinearLayout.LayoutParams(dp(ctx, 92), -2));

        TextView v = new TextView(ctx);
        v.setText(value);
        v.setTextColor(valColor);
        v.setTextSize(12f);
        v.setMaxLines(2);
        v.setEllipsize(TextUtils.TruncateAt.END);
        v.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));

        row.addView(k);
        row.addView(v);
        parent.addView(row);
    }

    public static String formatRelativeAndTime(Context ctx, long ms) {
        if (ms <= 0) return "Just now";
        long diffSec = Math.max(0, (System.currentTimeMillis() - ms) / 1000L);
        String rel;
        if (diffSec < 45) rel = "Just now";
        else if (diffSec < 3600) rel = (diffSec / 60) + "m ago";
        else if (diffSec < 86400) rel = (diffSec / 3600) + "h ago";
        else rel = (diffSec / 86400) + "d ago";
        Date d = new Date(ms);
        return rel + " (" + DateFormat.getTimeFormat(ctx).format(d) + ")";
    }

    public static String formatShortDate(Context ctx, long ms) {
        if (ms <= 0) return "Today";
        Date d = new Date(ms);
        return DateFormat.getMediumDateFormat(ctx).format(d);
    }

    public static String encodeJpeg(Bitmap bm, int maxDim, int quality) {
        if (bm == null || bm.isRecycled()) return "";
        try {
            int w = bm.getWidth(), h = bm.getHeight();
            float s = Math.min(1f, maxDim / (float) Math.max(w, h));
            Bitmap scaled = s < 1f
                    ? Bitmap.createScaledBitmap(bm, Math.max(1, (int) (w * s)), Math.max(1, (int) (h * s)), true)
                    : bm;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, out);
            return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
        } catch (Exception e) {
            return "";
        }
    }

    public static Bitmap decodeBase64(String b64) {
        if (b64 == null || b64.isEmpty()) return null;
        try {
            byte[] raw = Base64.decode(b64, Base64.DEFAULT);
            return BitmapFactory.decodeByteArray(raw, 0, raw.length);
        } catch (Exception e) {
            return null;
        }
    }

    private static int dp(Context ctx, float v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }
}
