package tech.nullai.fieldkit.vision;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.util.Log;

import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.components.containers.Category;
import com.google.mediapipe.tasks.components.containers.Detection;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.facedetector.FaceDetector;
import com.google.mediapipe.tasks.vision.facedetector.FaceDetectorResult;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult;
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector;
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetectorResult;

import java.util.ArrayList;
import java.util.List;

/**
 * Lightweight on-device detections (Nov 2, hackathon): MediaPipe Tasks (Apache-2.0) face
 * detection (BlazeFace short-range), hand landmarks (21 points x 2 hands) and an
 * EfficientDet-Lite0 object detector. Everything runs on the phone; nothing is uploaded except
 * the short text summary the wearer's own AI session receives.
 *
 * Gestures are a heuristic over the hand landmarks (which fingers are extended), not a
 * trained gesture classifier.
 */
public final class Detector implements AutoCloseable {
    private static final String TAG = "Detector";

    public static final class Hand {
        public final List<float[]> points = new ArrayList<>();   // normalised x,y
        public String gesture = "";
    }

    public static final class Result {
        public final List<RectF> faces = new ArrayList<>();       // pixel boxes in the frame
        public final List<RectF> bodies = new ArrayList<>();      // full/upper body person boxes
        public final List<Hand> hands = new ArrayList<>();
        public final List<String> objects = new ArrayList<>();
        public final List<RectF> objectBoxes = new ArrayList<>();
        public int width, height;
        public long ms;

        /** e.g. "1 face, 1 body, 2 hands (open palm), objects: cup, laptop" — counts and labels only. */
        public String summary() {
            StringBuilder b = new StringBuilder();
            b.append(faces.size()).append(faces.size() == 1 ? " face" : " faces");
            if (!bodies.isEmpty()) {
                b.append(", ").append(bodies.size()).append(bodies.size() == 1 ? " body" : " bodies");
            }
            b.append(", ").append(hands.size()).append(hands.size() == 1 ? " hand" : " hands");
            List<String> g = gestures();
            if (!g.isEmpty()) b.append(" (").append(String.join(", ", g)).append(")");
            if (!objects.isEmpty()) b.append(", objects: ").append(String.join(", ", objects));
            return b.toString();
        }

        public List<String> gestures() {
            List<String> g = new ArrayList<>();
            for (Hand h : hands) if (!h.gesture.isEmpty()) g.add(h.gesture.replace('_', ' '));
            return g;
        }

        public static RectF findMatchingBody(RectF face, List<RectF> bodies) {
            return Detector.findMatchingBody(face, bodies);
        }
    }

    private FaceDetector faces;
    private HandLandmarker hands;
    private ObjectDetector objects;

    public Detector(Context ctx) {
        Context app = ctx.getApplicationContext();
        try {
            faces = FaceDetector.createFromOptions(app, FaceDetector.FaceDetectorOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath("models/blaze_face_short_range.tflite").build())
                    .setRunningMode(RunningMode.IMAGE).setMinDetectionConfidence(0.35f).build());
        } catch (Throwable t) { Log.w(TAG, "face detector unavailable: " + t); }
        try {
            hands = HandLandmarker.createFromOptions(app, HandLandmarker.HandLandmarkerOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath("models/hand_landmarker.task").build())
                    .setRunningMode(RunningMode.IMAGE).setNumHands(2).build());
        } catch (Throwable t) { Log.w(TAG, "hand landmarker unavailable: " + t); }
        try {
            objects = ObjectDetector.createFromOptions(app, ObjectDetector.ObjectDetectorOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath("models/efficientdet_lite0.tflite").build())
                    .setRunningMode(RunningMode.IMAGE).setMaxResults(6).setScoreThreshold(0.38f).build());
        } catch (Throwable t) { Log.w(TAG, "object detector unavailable: " + t); }
    }

    /** Faces only (cheap): used for recognition and for "remember this person". */
    public List<RectF> faces(Bitmap bmp) {
        List<RectF> out = new ArrayList<>();
        if (faces == null || bmp == null) return out;
        try {
            FaceDetectorResult r = faces.detect(new BitmapImageBuilder(bmp).build());
            for (Detection d : r.detections()) out.add(new RectF(d.boundingBox()));
        } catch (Throwable t) { Log.w(TAG, "face detect failed: " + t); }
        return out;
    }

    public Result detect(Bitmap bmp, boolean withHands, boolean withObjects) {
        long t0 = System.currentTimeMillis();
        Result res = new Result();
        if (bmp == null) return res;
        res.width = bmp.getWidth();
        res.height = bmp.getHeight();
        MPImage img = new BitmapImageBuilder(bmp).build();
        res.faces.addAll(faces(bmp));
        if (withHands && hands != null) {
            try {
                HandLandmarkerResult r = hands.detect(img);
                for (List<NormalizedLandmark> lm : r.landmarks()) {
                    Hand h = new Hand();
                    for (NormalizedLandmark p : lm) h.points.add(new float[]{p.x(), p.y()});
                    h.gesture = Gestures.classify(h.points);
                    res.hands.add(h);
                }
            } catch (Throwable t) { Log.w(TAG, "hand detect failed: " + t); }
        }
        if (withObjects && objects != null) {
            try {
                ObjectDetectorResult r = objects.detect(img);
                for (Detection d : r.detections()) {
                    List<Category> c = d.categories();
                    if (c.isEmpty()) continue;
                    String name = c.get(0).categoryName();
                    if ("person".equals(name)) {
                        res.bodies.add(new RectF(d.boundingBox()));
                        continue;
                    }
                    if (!res.objects.contains(name)) res.objects.add(name);
                    res.objectBoxes.add(new RectF(d.boundingBox()));
                }
            } catch (Throwable t) { Log.w(TAG, "object detect failed: " + t); }
        }
        // Ensure every detected face also has a corresponding body/torso box if EfficientDet missed close-up torso
        for (RectF f : res.faces) {
            if (findMatchingBody(f, res.bodies) == null) {
                res.bodies.add(estimateBodyFromFace(bmp.getWidth(), bmp.getHeight(), f));
            }
        }
        res.ms = System.currentTimeMillis() - t0;
        return res;
    }

    /** Finds the detected person body box that best encloses or aligns with the given face box. */
    public static RectF findMatchingBody(RectF face, List<RectF> bodies) {
        if (face == null || bodies == null) return null;
        float fcx = face.centerX();
        float fcy = face.centerY();
        RectF best = null;
        float bestArea = 0f;
        for (RectF b : bodies) {
            if (fcx >= b.left - face.width() * 0.5f && fcx <= b.right + face.width() * 0.5f
                    && fcy >= b.top - face.height() * 0.5f && fcy <= b.bottom) {
                float area = b.width() * b.height();
                if (area > bestArea) {
                    bestArea = area;
                    best = b;
                }
            }
        }
        return best;
    }

    /** Estimates upper/full body torso box anchored beneath a detected face box. */
    public static RectF estimateBodyFromFace(int imgW, int imgH, RectF face) {
        if (face == null) return new RectF(0, 0, imgW, imgH);
        float cx = face.centerX();
        float fw = face.width();
        float fh = face.height();
        float left = Math.max(0f, cx - fw * 1.75f);
        float right = Math.min((float) imgW, cx + fw * 1.75f);
        float top = Math.max(0f, face.bottom - fh * 0.15f);
        float bottom = Math.min((float) imgH, face.bottom + fh * 3.4f);
        if (bottom - top < fh * 0.8f) {
            top = Math.max(0f, face.top);
            bottom = imgH;
        }
        return new RectF(left, top, right, bottom);
    }

    /** Estimates head/face box from the top portion of a detected person body box when BlazeFace misses a side angle. */
    public static RectF estimateFaceFromBody(int imgW, int imgH, RectF body) {
        if (body == null) return null;
        float bw = body.width();
        float bh = body.height();
        if (bw < 16f || bh < 24f) return null;
        float cx = body.centerX();
        float headW = Math.max(16f, Math.min(bw * 0.52f, bh * 0.34f));
        float headH = Math.max(16f, Math.min(bh * 0.30f, headW * 1.25f));
        float left = Math.max(0f, cx - headW * 0.5f);
        float right = Math.min((float) imgW, cx + headW * 0.5f);
        float top = Math.max(0f, body.top + bh * 0.02f);
        float bottom = Math.min((float) imgH, top + headH);
        return new RectF(left, top, right, bottom);
    }

    @Override public void close() {
        try { if (faces != null) faces.close(); } catch (Throwable ignored) { }
        try { if (hands != null) hands.close(); } catch (Throwable ignored) { }
        try { if (objects != null) objects.close(); } catch (Throwable ignored) { }
        faces = null; hands = null; objects = null;
    }
}
