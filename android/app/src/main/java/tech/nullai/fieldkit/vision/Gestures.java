package tech.nullai.fieldkit.vision;

import java.util.List;

/** Heuristic gestures from MediaPipe's 21 hand landmarks: which fingers are extended. */
public final class Gestures {
    private Gestures() { }

    private static final int[][] FINGERS = {{8, 6}, {12, 10}, {16, 14}, {20, 18}};   // tip, pip

    public static String classify(List<float[]> p) {
        if (p == null || p.size() < 21) return "";
        float[] wrist = p.get(0);
        boolean[] up = new boolean[4];
        int n = 0;
        for (int i = 0; i < 4; i++) {
            // extended = tip is further from the wrist than the middle joint (rotation-tolerant)
            up[i] = dist(p.get(FINGERS[i][0]), wrist) > dist(p.get(FINGERS[i][1]), wrist) * 1.15;
            if (up[i]) n++;
        }
        boolean thumbUp = dist(p.get(4), p.get(17)) > dist(p.get(3), p.get(17)) * 1.1
                && p.get(4)[1] < p.get(2)[1];
        if (n == 4) return "open_palm";
        if (n == 0) return thumbUp ? "thumbs_up" : "fist";
        if (n == 1 && up[0]) return "pointing";
        if (n == 2 && up[0] && up[1]) return "peace";
        return "";
    }

    static double dist(float[] a, float[] b) {
        double dx = a[0] - b[0], dy = a[1] - b[1];
        return Math.sqrt(dx * dx + dy * dy);
    }
}
