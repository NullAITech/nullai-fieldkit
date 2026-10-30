package tech.nullai.fieldkit.people;

/**
 * Appearance descriptor for a face crop: how Spotter re-spots a person it has already met.
 *
 * Composition (all cheap, all on-device, no model download):
 *   - 8x8 grey grid                (layout of the face: hair line, glasses, facial hair)
 *   - 4x4x3 colour grid            (hair/skin/clothing tone, corrected against the frame's own
 *                                   average so a different room light does not read as a new person)
 *   - 12-bin gradient orientation  (structure: the coarse shape of the face)
 * The vector is L2-normalised, so matching is a cosine score.
 *
 * This is heuristic re-identification, not face recognition: it answers "have I probably seen
 * this person before?" well enough to prompt the wearer, and the identity itself always comes
 * from the wearer or from conversation. Two different people in similar clothes can collide,
 * which is exactly why the app asks instead of asserting.
 */
public final class PersonDescriptor {

    public static final int GREY = 8;
    public static final int COL = 4;
    public static final int GRAD = 12;
    public static final int DIMS = GREY * GREY + COL * COL * 3 + GRAD;
    public static final double DEFAULT_THRESHOLD = 0.86;

    private PersonDescriptor() {
    }

    /** @param argb face crop, @param w/@param h its pixel size. */
    public static float[] fromCrop(int[] argb, int w, int h) {
        if (argb == null || w < COL || h < COL) return null;

        // frame average colour, used to make the colour grid light-invariant
        double fr = 0, fg = 0, fb = 0;
        for (int p : argb) {
            fr += (p >> 16) & 0xff;
            fg += (p >> 8) & 0xff;
            fb += p & 0xff;
        }
        fr /= argb.length; fg /= argb.length; fb /= argb.length;

        float[] v = new float[DIMS];

        // grey grid
        for (int gy = 0; gy < GREY; gy++) {
            for (int gx = 0; gx < GREY; gx++) {
                double s = 0, n = 0;
                for (int y = gy * h / GREY; y < (gy + 1) * h / GREY; y++) {
                    for (int x = gx * w / GREY; x < (gx + 1) * w / GREY; x++) {
                        int p = argb[y * w + x];
                        s += lum(p);
                        n++;
                    }
                }
                v[gy * GREY + gx] = (float) (n > 0 ? s / n : 0);
            }
        }

        // colour grid, light-corrected
        int base = GREY * GREY;
        for (int cy = 0; cy < COL; cy++) {
            for (int cx = 0; cx < COL; cx++) {
                double r = 0, g = 0, b = 0, n = 0;
                for (int y = cy * h / COL; y < (cy + 1) * h / COL; y++) {
                    for (int x = cx * w / COL; x < (cx + 1) * w / COL; x++) {
                        int p = argb[y * w + x];
                        r += ((p >> 16) & 0xff) - fr;
                        g += ((p >> 8) & 0xff) - fg;
                        b += (p & 0xff) - fb;
                        n++;
                    }
                }
                int k = base + (cy * COL + cx) * 3;
                v[k] = (float) (n > 0 ? r / n : 0);
                v[k + 1] = (float) (n > 0 ? g / n : 0);
                v[k + 2] = (float) (n > 0 ? b / n : 0);
            }
        }

        // gradient orientation histogram
        int gbase = base + COL * COL * 3;
        double[] bins = new double[GRAD];
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                double dx = lum(argb[y * w + x + 1]) - lum(argb[y * w + x - 1]);
                double dy = lum(argb[(y + 1) * w + x]) - lum(argb[(y - 1) * w + x]);
                double mag = Math.abs(dx) + Math.abs(dy);
                if (mag < 12) continue;
                double ang = Math.atan2(dy, dx);                   // -pi..pi
                if (ang < 0) ang += Math.PI;                       // 0..pi (unsigned)
                int bin = (int) (ang / Math.PI * GRAD) % GRAD;
                bins[bin] += mag;
            }
        }
        double sum = 0;
        for (double d : bins) sum += d;
        for (int i = 0; i < GRAD; i++) v[gbase + i] = (float) (sum > 0 ? bins[i] / sum : 0);

        // grey grid dominates the norm; scale so colour/structure keep comparable weight
        for (int i = 0; i < base; i++) v[i] *= 0.06f;
        for (int i = base; i < gbase; i++) v[i] *= 0.5f;
        for (int i = gbase; i < DIMS; i++) v[i] *= 3.0f;

        return normalize(v);
    }

    public static double similarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) return -1;
        double d = 0;
        for (int i = 0; i < a.length; i++) d += a[i] * b[i];
        return d;
    }

    /** Best match above threshold, or -1 with the runner-up score for the log. */
    public static final class Match {
        public final int index;
        public final double score;
        public final double runnerUp;

        Match(int index, double score, double runnerUp) {
            this.index = index;
            this.score = score;
            this.runnerUp = runnerUp;
        }

        public boolean confident(double margin) {
            return index >= 0 && score - runnerUp >= margin;
        }
    }

    public static Match best(float[] probe, java.util.List<float[]> known, double threshold, double margin) {
        int bestIdx = -1;
        double bestScore = -1, second = -1;
        for (int i = 0; i < known.size(); i++) {
            double s = similarity(probe, known.get(i));
            if (s > bestScore) {
                second = bestScore;
                bestScore = s;
                bestIdx = i;
            } else if (s > second) {
                second = s;
            }
        }
        if (bestScore < threshold || bestScore - second < margin) return new Match(-1, bestScore, Math.max(second, 0));
        return new Match(bestIdx, bestScore, Math.max(second, 0));
    }

    public static final double MARGIN = 0.01;

    private static double lum(int p) {
        return (0.299 * ((p >> 16) & 0xff) + 0.587 * ((p >> 8) & 0xff) + 0.114 * (p & 0xff)) / 255.0;
    }

    private static float[] normalize(float[] v) {
        double n = 0;
        for (float x : v) n += x * x;
        n = Math.sqrt(n);
        if (n > 1e-9) for (int i = 0; i < v.length; i++) v[i] /= (float) n;
        return v;
    }

    /** Test/harness helper: a synthetic face with the given tone, hair line and texture phase. */
    public static int[] synthesizeFace(int w, int h, int skin, int hair, double featureX, long seed) {
        java.util.Random rnd = new java.util.Random(seed);
        int[] px = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                boolean isHair = y < h * 0.22;
                int c = isHair ? hair : skin;
                double shade = 1.0;
                if (!isHair) {
                    double d = Math.abs(x - featureX) / (double) w;
                    if (d < 0.12) shade = 0.55;                 // eye/glasses band
                    if (Math.abs(y - h * 0.60) < h * 0.06) shade = 0.8;   // mouth line
                }
                int r = (int) (((c >> 16) & 0xff) * shade);
                int g = (int) (((c >> 8) & 0xff) * shade);
                int b = (int) ((c & 0xff) * shade);
                r = clamp(r + (int) (rnd.nextGaussian() * 4));
                g = clamp(g + (int) (rnd.nextGaussian() * 4));
                b = clamp(b + (int) (rnd.nextGaussian() * 4));
                px[y * w + x] = 0xff000000 | (r << 16) | (g << 8) | b;
            }
        }
        return px;
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : Math.min(v, 255);
    }
}
