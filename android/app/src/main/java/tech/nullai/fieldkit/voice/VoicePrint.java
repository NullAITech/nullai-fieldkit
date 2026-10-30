package tech.nullai.fieldkit.voice;

import java.util.List;

/**
 * Compact voice signature ("voiceprint") computed on-device from 16 kHz mono PCM.
 *
 * Pipeline per frame: DC removal -> pre-emphasis -> Hann -> magnitude DFT -> N log-spaced
 * triangular bands -> log energy. Across frames we keep the mean AND the standard deviation
 * of every band (the std carries the speaking-rate / articulation information), plus two
 * prosody scalars: autocorrelation pitch and zero-crossing rate. The vector is mean-centred
 * and L2-normalised, so comparison is a cosine score in [-1, 1].
 *
 * Honest limits: this is a light spectral signature, not a security biometric. It separates
 * one familiar voice from a different voice in a quiet room well enough to gate hands-free
 * commands, and it is deliberately combined with the explicit wake phrase rather than
 * trusted alone. Ship it as a convenience gate, never as authentication.
 */
public final class VoicePrint {

    public static final int BANDS = 20;
    public static final int FRAME = 256;
    public static final int HOP = 128;
    public static final int MAX_FRAMES = 90;
    public static final int DIMS = BANDS * 2 + 2;
    public static final double MIN_RMS = 220.0;      // ignore near-silent frames

    private VoicePrint() {
    }

    /** @return DIMS-long normalised vector, or null when there was not enough voiced audio. */
    public static float[] fromPcm(short[] pcm, int sampleRate) {
        if (pcm == null || pcm.length < FRAME * 2) return null;
        int frames = Math.min(MAX_FRAMES, (pcm.length - FRAME) / HOP + 1);
        double[][] edges = bandEdges(sampleRate);

        double[] sum = new double[BANDS];
        double[] sumSq = new double[BANDS];
        double zcrSum = 0;
        double pitchSum = 0;
        int used = 0;

        double[] win = hann(FRAME);
        double[] re = new double[FRAME];
        double[] im = new double[FRAME];

        for (int f = 0; f < frames; f++) {
            int off = f * HOP;
            double rms = rms(pcm, off, FRAME);
            if (rms < MIN_RMS) continue;

            double prev = 0;
            for (int i = 0; i < FRAME; i++) {
                double x = pcm[off + i] / 32768.0;
                double e = x - 0.97 * prev;      // pre-emphasis
                prev = x;
                re[i] = e * win[i];
                im[i] = 0;
            }
            dft(re, im);

            double[] power = new double[FRAME / 2];
            for (int k = 0; k < FRAME / 2; k++) power[k] = re[k] * re[k] + im[k] * im[k];

            double[] bandDb = new double[BANDS];
            double frameMean = 0;
            for (int b = 0; b < BANDS; b++) {
                double e = bandEnergy(power, edges[b], sampleRate);
                bandDb[b] = Math.log(e + 1e-9);
                frameMean += bandDb[b];
            }
            frameMean /= BANDS;
            for (int b = 0; b < BANDS; b++) {
                double v = bandDb[b] - frameMean;      // channel/level compensation: keep spectral SHAPE
                sum[b] += v;
                sumSq[b] += v * v;
            }
            zcrSum += zcr(pcm, off, FRAME);
            pitchSum += pitch(pcm, off, sampleRate);
            used++;
        }
        if (used < 8) return null;

        float[] v = new float[DIMS];
        for (int b = 0; b < BANDS; b++) {
            double m = sum[b] / used;
            double sd = Math.sqrt(Math.max(0, sumSq[b] / used - m * m));
            v[b] = (float) m;
            v[BANDS + b] = (float) sd;
        }
        v[BANDS * 2] = (float) (pitchSum / used);
        v[BANDS * 2 + 1] = (float) (zcrSum / used);

        // Group-normalise before the final normalise: without this the 20 log-band means swamp the
        // std and prosody dims (they carry the articulation and pitch that actually identify a voice).
        groupNormalize(v, 0, BANDS);
        groupNormalize(v, BANDS, BANDS * 2);
        groupNormalize(v, BANDS * 2, DIMS);
        return normalize(v);
    }

    private static void groupNormalize(float[] v, int from, int to) {
        double n = 0;
        for (int i = from; i < to; i++) n += v[i] * v[i];
        n = Math.sqrt(n);
        if (n > 1e-9) for (int i = from; i < to; i++) v[i] /= (float) n;
    }

    /** Average several enrolment samples into one print, then renormalise. */
    public static float[] average(List<float[]> prints) {
        float[] acc = new float[DIMS];
        int n = 0;
        for (float[] p : prints) {
            if (p == null || p.length != DIMS) continue;
            for (int i = 0; i < DIMS; i++) acc[i] += p[i];
            n++;
        }
        if (n == 0) return null;
        for (int i = 0; i < DIMS; i++) acc[i] /= n;
        return normalize(acc);
    }

    public static double similarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) return -1;
        double d = 0;
        for (int i = 0; i < a.length; i++) d += a[i] * b[i];
        return d;                                  // both are L2-normalised
    }

    /** 0..1 quality read of a single print: how far it sits from the print of white noise. */
    public static double quality(float[] print) {
        if (print == null) return 0;
        double energy = 0;
        for (int i = 0; i < BANDS; i++) energy += print[i] * print[i];
        return Math.min(1.0, Math.sqrt(energy));
    }

    // ------------------------------------------------------------------ internals

    private static float[] normalize(float[] v) {
        double m = 0;
        for (float x : v) m += x;
        m /= v.length;
        double n = 0;
        for (int i = 0; i < v.length; i++) {
            v[i] -= (float) m;                     // mean-centre: cosine becomes correlation
            n += v[i] * v[i];
        }
        n = Math.sqrt(n);
        if (n > 1e-9) for (int i = 0; i < v.length; i++) v[i] /= (float) n;
        return v;
    }

    private static double[] hann(int n) {
        double[] w = new double[n];
        for (int i = 0; i < n; i++) w[i] = 0.5 * (1 - Math.cos(2 * Math.PI * i / (n - 1)));
        return w;
    }

    private static double rms(short[] pcm, int off, int n) {
        double s = 0;
        for (int i = 0; i < n; i++) {
            double x = pcm[off + i];
            s += x * x;
        }
        return Math.sqrt(s / n);
    }

    private static double zcr(short[] pcm, int off, int n) {
        int c = 0;
        for (int i = 1; i < n; i++) if ((pcm[off + i - 1] < 0) != (pcm[off + i] < 0)) c++;
        return (double) c / n;
    }

    /** Normalised autocorrelation pitch estimate (0 when unvoiced). */
    private static double pitch(short[] pcm, int off, int sr) {
        int minLag = sr / 400, maxLag = sr / 70;
        if (maxLag >= FRAME) maxLag = FRAME - 1;
        double best = 0;
        int bestLag = 0;
        double e0 = 0;
        for (int i = 0; i < FRAME; i++) e0 += (double) pcm[off + i] * pcm[off + i];
        if (e0 <= 0) return 0;
        for (int lag = minLag; lag <= maxLag; lag++) {
            double s = 0;
            for (int i = 0; i + lag < FRAME; i++) s += (double) pcm[off + i] * pcm[off + i + lag];
            double norm = s / e0;
            if (norm > best) {
                best = norm;
                bestLag = lag;
            }
        }
        if (best < 0.3 || bestLag == 0) return 0;
        return (double) sr / bestLag / 400.0;      // scaled into ~0..1
    }

    private static double[][] bandEdges(int sr) {
        double fmin = 90.0, fmax = Math.min(7000.0, sr * 0.45);
        double[][] edges = new double[BANDS][2];
        double step = Math.pow(fmax / fmin, 1.0 / BANDS);
        int binHz = sr / FRAME;
        for (int b = 0; b < BANDS; b++) {
            edges[b][0] = Math.max(1, Math.round(fmin * Math.pow(step, b)) / binHz);
            edges[b][1] = Math.max(edges[b][0] + 1, Math.round(fmin * Math.pow(step, b + 1)) / binHz);
        }
        return edges;
    }

    private static double bandEnergy(double[] power, double[] edge, int sr) {
        int a = (int) edge[0], b = (int) edge[1];
        if (b >= power.length) b = power.length - 1;
        if (a < 1) a = 1;
        double s = 0;
        for (int k = a; k <= b; k++) s += power[k];
        return s / Math.max(1, (b - a + 1));
    }

    /** In-place radix-2 DFT (real+imag), n must be a power of two. */
    private static void dft(double[] re, double[] im) {
        int n = re.length;
        int bits = Integer.numberOfTrailingZeros(n);
        for (int i = 0; i < n; i++) {
            int j = Integer.reverse(i) >>> (32 - bits);
            if (j > i) {
                double t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2 * Math.PI / len;
            double wr = Math.cos(ang), wi = Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                double cr = 1, ci = 0;
                for (int k = 0; k < len / 2; k++) {
                    int a = i + k, b = i + k + len / 2;
                    double tr = re[b] * cr - im[b] * ci;
                    double ti = re[b] * ci + im[b] * cr;
                    re[b] = re[a] - tr;
                    im[b] = im[a] - ti;
                    re[a] += tr;
                    im[a] += ti;
                    double nr = cr * wr - ci * wi;
                    ci = cr * wi + ci * wr;
                    cr = nr;
                }
            }
        }
    }

    /** Synthesise a test "voice": harmonic stack with formant emphasis. Test/harness use. */
    public static short[] synthesize(double f0, double formant, int ms, int sr, long seed) {
        int n = sr * ms / 1000;
        short[] out = new short[n];
        java.util.Random rnd = new java.util.Random(seed);
        for (int i = 0; i < n; i++) {
            double t = (double) i / sr;
            double v = 0;
            for (int h = 1; h <= 24; h++) {
                double f = f0 * h;
                if (f > sr * 0.45) break;
                double emphasis = Math.exp(-Math.pow((f - formant) / 500.0, 2));
                v += Math.sin(2 * Math.PI * f * t + h * 0.7) * (1.0 / h) * (0.4 + 1.6 * emphasis);
            }
            v += rnd.nextGaussian() * 0.02;
            out[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, v * 4000));
        }
        return out;
    }
}
