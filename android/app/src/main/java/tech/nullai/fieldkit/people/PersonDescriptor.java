package tech.nullai.fieldkit.people;

import java.util.Locale;

/**
 * Dual-modality Face + Body Appearance Descriptor for Spotter.
 *
 * Face Descriptor (124 dims, on-device):
 *   - 8x8 grey grid                (layout of the face: hair line, glasses, facial hair)
 *   - 4x4x3 colour grid            (hair/skin/clothing tone, corrected against frame average)
 *   - 12-bin gradient orientation  (coarse facial geometry)
 *
 * Body / Torso Descriptor (112 dims, on-device):
 *   - 4x8 vertical luminance silhouette (shoulder width, torso taper, posture)
 *   - 4x6x3 upper/lower attire chromaticity grid (shirt/jacket vs lower torso colour)
 *   - 8-bin structural edge histogram (lapels, straps, stripes, garment folds)
 *
 * All vectors are L2-normalised so matching is fast cosine similarity.
 */
public final class PersonDescriptor {

    public static final int GREY = 8;
    public static final int COL = 4;
    public static final int GRAD = 12;
    public static final int DIMS = GREY * GREY + COL * COL * 3 + GRAD;

    public static final int BODY_W = 4;
    public static final int BODY_H = 8;
    public static final int BODY_COL_W = 4;
    public static final int BODY_COL_H = 6;
    public static final int BODY_GRAD = 8;
    public static final int BODY_DIMS = BODY_W * BODY_H + BODY_COL_W * BODY_COL_H * 3 + BODY_GRAD;

    public static final double DEFAULT_THRESHOLD = 0.82;
    public static final double COMBINED_THRESHOLD = 0.80;
    public static final double MIN_FACE_THRESHOLD = 0.78;
    public static final double MARGIN = 0.035;

    private PersonDescriptor() {
    }

    /** Verifies a crop has genuine facial texture/contrast and is not a flat wall, shadow, or ceiling. */
    public static boolean looksLikeValidFaceCrop(int[] argb, int w, int h) {
        if (argb == null || w < 16 || h < 16) return false;
        double sum = 0, sumSq = 0;
        int n = argb.length;
        for (int p : argb) {
            double l = lum(p);
            sum += l;
            sumSq += l * l;
        }
        double mean = sum / n;
        double variance = Math.max(0.0, (sumSq / n) - (mean * mean));
        double std = Math.sqrt(variance);
        // Reject ultra-dark shadows, blown-out lights, or low-contrast flat walls/furniture
        return mean >= 0.08 && mean <= 0.93 && std >= 0.055;
    }

    /** @param argb face crop, @param w/@param h its pixel size. */
    public static float[] fromCrop(int[] argb, int w, int h) {
        if (argb == null || w < COL || h < COL) return null;

        // Center-weighted illumination reference (inner 65% oval of the crop so background walls don't skew skin/hair tone)
        double fr = 0, fg = 0, fb = 0, fn = 0;
        int x0 = w / 6, x1 = Math.max(x0 + 1, w - w / 6);
        int y0 = h / 6, y1 = Math.max(y0 + 1, h - h / 6);
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                int p = argb[y * w + x];
                fr += (p >> 16) & 0xff;
                fg += (p >> 8) & 0xff;
                fb += p & 0xff;
                fn++;
            }
        }
        if (fn <= 0) {
            for (int p : argb) {
                fr += (p >> 16) & 0xff;
                fg += (p >> 8) & 0xff;
                fb += p & 0xff;
            }
            fn = argb.length;
        }
        fr /= fn; fg /= fn; fb /= fn;

        float[] v = new float[DIMS];

        // 8x8 grey grid — strictly zero-mean & contrast-normalized so positive DC bias never causes false matches!
        double[] gridLum = new double[GREY * GREY];
        double gridMean = 0;
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
                double cLum = (n > 0 ? s / n : 0.5);
                gridLum[gy * GREY + gx] = cLum;
                gridMean += cLum;
            }
        }
        gridMean /= (GREY * GREY);
        double gridVar = 0;
        for (double gl : gridLum) gridVar += (gl - gridMean) * (gl - gridMean);
        double gridStd = Math.sqrt(gridVar / (GREY * GREY)) + 0.035;

        for (int gy = 0; gy < GREY; gy++) {
            for (int gx = 0; gx < GREY; gx++) {
                boolean isCorner = (gx == 0 || gx == GREY - 1) && (gy == 0 || gy == GREY - 1);
                boolean isCoreFace = (gx >= 1 && gx <= 6 && gy >= 1 && gy <= 6);
                float zoneWeight = isCorner ? 0.35f : (isCoreFace ? 1.25f : 0.80f);
                v[gy * GREY + gx] = (float) (((gridLum[gy * GREY + gx] - gridMean) / gridStd) * zoneWeight);
            }
        }

        // colour grid, light-corrected (zero-centered against inner face mean)
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
                v[k] = (float) (n > 0 ? (r / n) / 28.0 : 0);
                v[k + 1] = (float) (n > 0 ? (g / n) / 28.0 : 0);
                v[k + 2] = (float) (n > 0 ? (b / n) / 28.0 : 0);
            }
        }

        // gradient orientation histogram — zero-centered around uniform (1/GRAD) so random edges don't correlate at +0.85
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
        double uniform = 1.0 / GRAD;
        for (int i = 0; i < GRAD; i++) {
            v[gbase + i] = (float) (sum > 0 ? ((bins[i] / sum) - uniform) * 6.0 : 0);
        }

        // Balanced modality weighting across structure (64D), chromaticity (48D), and geometry (12D)
        for (int i = 0; i < base; i++) v[i] *= 0.22f;
        for (int i = base; i < gbase; i++) v[i] *= 0.32f;
        for (int i = gbase; i < DIMS; i++) v[i] *= 1.15f;

        return normalize(v);
    }

    /** Fuses multiple angle descriptors into a single L2-normalised centroid descriptor. */
    public static float[] fuseDescriptors(java.util.List<float[]> vectors) {
        if (vectors == null || vectors.isEmpty()) return new float[0];
        int dim = -1;
        for (float[] v : vectors) {
            if (v != null && v.length > 0) {
                dim = v.length;
                break;
            }
        }
        if (dim <= 0) return new float[0];
        float[] sum = new float[dim];
        int count = 0;
        for (float[] v : vectors) {
            if (v == null || v.length != dim) continue;
            for (int i = 0; i < dim; i++) sum[i] += v[i];
            count++;
        }
        if (count == 0) return new float[0];
        return normalize(sum);
    }

    /** Returns the highest cosine similarity across the fused centroid and any individual saved angle vectors. */
    public static double bestMultiAngleSimilarity(float[] probe, float[] centroid, float[][] angleVectors) {
        if (probe == null || probe.length == 0) return -1;
        double best = similarity(probe, centroid);
        if (angleVectors != null) {
            for (float[] ang : angleVectors) {
                if (ang != null && ang.length == probe.length) {
                    double s = similarity(probe, ang);
                    if (s > best) best = s;
                }
            }
        }
        return best;
    }

    /**
     * Extracts a 112-dim L2-normalised body/torso descriptor from a body crop.
     */
    public static float[] fromBodyCrop(int[] argb, int w, int h) {
        if (argb == null || w < BODY_COL_W || h < BODY_H) return null;

        double fr = 0, fg = 0, fb = 0;
        for (int p : argb) {
            fr += (p >> 16) & 0xff;
            fg += (p >> 8) & 0xff;
            fb += p & 0xff;
        }
        fr /= argb.length; fg /= argb.length; fb /= argb.length;

        float[] v = new float[BODY_DIMS];

        // 4x8 vertical silhouette grid (zero-mean & contrast-normalized)
        double[] bodyLum = new double[BODY_W * BODY_H];
        double bodyMean = 0;
        for (int gy = 0; gy < BODY_H; gy++) {
            for (int gx = 0; gx < BODY_W; gx++) {
                double s = 0, n = 0;
                for (int y = gy * h / BODY_H; y < (gy + 1) * h / BODY_H; y++) {
                    for (int x = gx * w / BODY_W; x < (gx + 1) * w / BODY_W; x++) {
                        s += lum(argb[y * w + x]);
                        n++;
                    }
                }
                double cLum = (n > 0 ? s / n : 0.5);
                bodyLum[gy * BODY_W + gx] = cLum;
                bodyMean += cLum;
            }
        }
        bodyMean /= (BODY_W * BODY_H);
        double bodyVar = 0;
        for (double bl : bodyLum) bodyVar += (bl - bodyMean) * (bl - bodyMean);
        double bodyStd = Math.sqrt(bodyVar / (BODY_W * BODY_H)) + 0.04;
        for (int i = 0; i < BODY_W * BODY_H; i++) {
            v[i] = (float) ((bodyLum[i] - bodyMean) / bodyStd);
        }

        // 4x6x3 attire colour grid (zero-centered)
        int base = BODY_W * BODY_H;
        for (int cy = 0; cy < BODY_COL_H; cy++) {
            for (int cx = 0; cx < BODY_COL_W; cx++) {
                double r = 0, g = 0, b = 0, n = 0;
                for (int y = cy * h / BODY_COL_H; y < (cy + 1) * h / BODY_COL_H; y++) {
                    for (int x = cx * w / BODY_COL_W; x < (cx + 1) * w / BODY_COL_W; x++) {
                        int p = argb[y * w + x];
                        r += ((p >> 16) & 0xff) - fr;
                        g += ((p >> 8) & 0xff) - fg;
                        b += (p & 0xff) - fb;
                        n++;
                    }
                }
                int k = base + (cy * BODY_COL_W + cx) * 3;
                v[k] = (float) (n > 0 ? (r / n) / 32.0 : 0);
                v[k + 1] = (float) (n > 0 ? (g / n) / 32.0 : 0);
                v[k + 2] = (float) (n > 0 ? (b / n) / 32.0 : 0);
            }
        }

        // 8-bin garment contour orientation histogram (zero-centered around uniform)
        int gbase = base + BODY_COL_W * BODY_COL_H * 3;
        double[] bins = new double[BODY_GRAD];
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                double dx = lum(argb[y * w + x + 1]) - lum(argb[y * w + x - 1]);
                double dy = lum(argb[(y + 1) * w + x]) - lum(argb[(y - 1) * w + x]);
                double mag = Math.abs(dx) + Math.abs(dy);
                if (mag < 10) continue;
                double ang = Math.atan2(dy, dx);
                if (ang < 0) ang += Math.PI;
                int bin = (int) (ang / Math.PI * BODY_GRAD) % BODY_GRAD;
                bins[bin] += mag;
            }
        }
        double sum = 0;
        for (double d : bins) sum += d;
        double uniform = 1.0 / BODY_GRAD;
        for (int i = 0; i < BODY_GRAD; i++) {
            v[gbase + i] = (float) (sum > 0 ? ((bins[i] / sum) - uniform) * 5.0 : 0);
        }

        for (int i = 0; i < base; i++) v[i] *= 0.25f;
        for (int i = base; i < gbase; i++) v[i] *= 0.35f;
        for (int i = gbase; i < BODY_DIMS; i++) v[i] *= 1.10f;

        return normalize(v);
    }

    public static double similarity(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) return -1;
        double d = 0;
        for (int i = 0; i < a.length; i++) d += a[i] * b[i];
        return d;
    }

    /**
     * Fuses Face similarity (76%) and Body/Torso similarity (24%) when a real face match is present.
     * Never matches on body alone (which would trigger on chairs/coats when no face is visible).
     */
    public static double combinedSimilarity(float[] faceProbe, float[] bodyProbe,
                                            float[] faceKnown, float[] bodyKnown) {
        double sf = similarity(faceProbe, faceKnown);
        if (sf < MIN_FACE_THRESHOLD) return -1;
        double sb = similarity(bodyProbe, bodyKnown);
        if (sb >= 0) {
            return sf * 0.76 + sb * 0.24;
        }
        return sf;
    }

    /** Human-readable appearance telemetry extracted from a person's face + body crops. */
    public static final class AppearanceProfile {
        public final String upperColorName;
        public final int upperColorRgb;
        public final String lowerColorName;
        public final int lowerColorRgb;
        public final String patternStyle;
        public final String buildSignature;
        public final String attireSummary;

        public AppearanceProfile(String upperColorName, int upperColorRgb,
                                 String lowerColorName, int lowerColorRgb,
                                 String patternStyle, String buildSignature,
                                 String attireSummary) {
            this.upperColorName = upperColorName;
            this.upperColorRgb = upperColorRgb;
            this.lowerColorName = lowerColorName;
            this.lowerColorRgb = lowerColorRgb;
            this.patternStyle = patternStyle;
            this.buildSignature = buildSignature;
            this.attireSummary = attireSummary;
        }
    }

    /**
     * Analyzes the body crop to describe attire colors, garment texture, and build/framing.
     */
    public static AppearanceProfile analyzeAppearance(int[] bodyArgb, int bw, int bh, float bodyAspect) {
        if (bodyArgb == null || bw < 4 || bh < 4) {
            return new AppearanceProfile("Charcoal", 0xFF2B303A, "Slate", 0xFF39414E,
                    "Solid", "Standard Frame", "Charcoal top · Slate lower · Standard frame");
        }
        // Sample center 60% horizontally, upper half (15%..55%) vs lower half (55%..92%)
        int x0 = (int) (bw * 0.20f), x1 = Math.max(x0 + 1, (int) (bw * 0.80f));
        int uy0 = (int) (bh * 0.12f), uy1 = Math.max(uy0 + 1, (int) (bh * 0.55f));
        int ly0 = (int) (bh * 0.55f), ly1 = Math.max(ly0 + 1, (int) (bh * 0.92f));

        long ur = 0, ug = 0, ub = 0, un = 0;
        double edgeEnergy = 0;
        for (int y = uy0; y < uy1; y++) {
            for (int x = x0; x < x1; x++) {
                int p = bodyArgb[y * bw + x];
                ur += (p >> 16) & 0xff;
                ug += (p >> 8) & 0xff;
                ub += p & 0xff;
                un++;
                if (x + 1 < x1) {
                    edgeEnergy += Math.abs(lum(p) - lum(bodyArgb[y * bw + x + 1]));
                }
            }
        }
        int uRgb = un > 0
                ? (0xFF000000 | ((int) (ur / un) << 16) | ((int) (ug / un) << 8) | (int) (ub / un))
                : 0xFF2E3540;

        long lr = 0, lg = 0, lb = 0, ln = 0;
        for (int y = ly0; y < ly1; y++) {
            for (int x = x0; x < x1; x++) {
                int p = bodyArgb[y * bw + x];
                lr += (p >> 16) & 0xff;
                lg += (p >> 8) & 0xff;
                lb += p & 0xff;
                ln++;
            }
        }
        int lRgb = ln > 0
                ? (0xFF000000 | ((int) (lr / ln) << 16) | ((int) (lg / ln) << 8) | (int) (lb / ln))
                : 0xFF232832;

        String uName = colorName(uRgb);
        String lName = colorName(lRgb);
        double avgEdge = un > 0 ? edgeEnergy / un : 0;
        String pattern = avgEdge > 0.085 ? "Textured / Patterned" : (avgEdge > 0.045 ? "Structured" : "Clean Solid");
        String build;
        if (bodyAspect < 0.48f) build = "Tall / Lean Silhouette";
        else if (bodyAspect > 0.78f) build = "Broad Shoulder Frame";
        else build = "Balanced Proportion";

        String summary = String.format(Locale.US, "%s (%s) · Lower %s", uName, pattern, lName);
        return new AppearanceProfile(uName, uRgb, lName, lRgb, pattern, build, summary);
    }

    public static String colorName(int rgb) {
        int r = (rgb >> 16) & 0xff;
        int g = (rgb >> 8) & 0xff;
        int b = rgb & 0xff;
        int max = Math.max(r, Math.max(g, b));
        int min = Math.min(r, Math.min(g, b));
        int d = max - min;
        double l = (0.299 * r + 0.587 * g + 0.114 * b);
        if (d < 22) {
            if (l < 45) return "Jet Black";
            if (l < 95) return "Charcoal Grey";
            if (l < 155) return "Heather Grey";
            if (l < 205) return "Silver / Ash";
            return "Ivory White";
        }
        if (r > g && r >= b) {
            if (g > b + 25) return l > 140 ? "Amber / Camel" : "Warm Brown";
            return l < 90 ? "Burgundy" : "Crimson / Coral";
        }
        if (g >= r && g >= b) {
            if (b > r + 20) return "Teal / Sage";
            return l < 95 ? "Forest Olive" : "Emerald / Mint";
        }
        if (r > g + 25) return "Indigo / Violet";
        return l < 90 ? "Midnight Navy" : "Cobalt / Denim";
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
