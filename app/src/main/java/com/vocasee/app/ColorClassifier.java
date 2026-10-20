package com.vocasee.app;

import java.util.Arrays;

/**
 * Offline dominant-color estimate for the central patch of a detected object.
 *
 * Pipeline: ARGB -> HSV, trim the brightest and darkest tails of the patch (specular
 * highlights, hard shadows), decide chromatic vs neutral by HSV chroma, then take the
 * peak of a circular hue histogram so red's 0/360 wrap is one neighborhood, not two.
 * Hue names come from the peak's circular mean; lightness only splits pairs that differ
 * by brightness alone (orange/brown, red/pink, gray/silver/white).
 *
 * Pure Java, deterministic, and allocation-light so it can run on the camera thread and
 * be unit tested on the JVM.
 */
final class ColorClassifier {

    static final String UNCLEAR = "unclear";

    /** Fraction of the detection box (per axis) sampled around its center. */
    static final float CORE_FRACTION = 0.40f;
    /** Upper bound on samples per axis inside the core patch. */
    static final int GRID = 24;

    private static final int MIN_SAMPLES = 24;
    // Tails trimmed by brightness rank within the patch. Highlights are usually small and
    // very bright, so the upper trim is a little larger than the lower one.
    private static final float LOW_TAIL = 0.10f;
    private static final float HIGH_TAIL = 0.15f;
    // HSV chroma (max - min channel). Very dark pixels have unreliable hue from sensor noise.
    private static final float MIN_CHROMA = 0.10f;
    private static final float MIN_CHROMATIC_SATURATION = 0.20f;
    private static final float MIN_CHROMATIC_VALUE = 0.18f;
    private static final float CHROMATIC_MAJORITY = 0.45f;
    private static final float NEUTRAL_MAJORITY = 0.75f;
    private static final int HUE_BINS = 36;
    private static final float HUE_BIN_DEGREES = 360f / HUE_BINS;
    private static final float PEAK_HALF_WIDTH_DEGREES = 20f;
    // A patterned object with no clear leading hue should not get a confident name.
    // An even split (e.g. half red, half blue) would otherwise flip between names frame to frame.
    private static final float MIN_PEAK_SHARE = 0.55f;

    private ColorClassifier() {}

    /** Classifies the first {@code count} ARGB pixels of {@code argb}. */
    static String classify(int[] argb, int count) {
        if (argb == null || count < MIN_SAMPLES) return UNCLEAR;
        count = Math.min(count, argb.length);

        float[] hue = new float[count];
        float[] sat = new float[count];
        float[] val = new float[count];
        float[] chroma = new float[count];
        for (int i = 0; i < count; i++) {
            int p = argb[i];
            int r = (p >> 16) & 0xFF;
            int g = (p >> 8) & 0xFF;
            int b = p & 0xFF;
            int max = Math.max(r, Math.max(g, b));
            int min = Math.min(r, Math.min(g, b));
            int delta = max - min;
            val[i] = max / 255f;
            chroma[i] = delta / 255f;
            sat[i] = max == 0 ? 0f : delta / (float) max;
            hue[i] = hueDegrees(r, g, b, max, delta);
        }

        // Rank-based trimming adapts to exposure: a uniformly white object keeps its
        // middle band, while a glint or a hard shadow on part of the patch is dropped.
        float[] sortedVal = Arrays.copyOf(val, count);
        Arrays.sort(sortedVal);
        float vLow = sortedVal[Math.min(count - 1, (int) (count * LOW_TAIL))];
        float vHigh = sortedVal[Math.max(0, (int) Math.ceil(count * (1f - HIGH_TAIL)) - 1)];

        int kept = 0;
        int chromaticCount = 0;
        int[] hueHistogram = new int[HUE_BINS];
        boolean[] chromatic = new boolean[count];
        boolean[] keep = new boolean[count];
        for (int i = 0; i < count; i++) {
            if (val[i] < vLow || val[i] > vHigh) continue;
            keep[i] = true;
            kept++;
            if (isChromatic(sat[i], val[i], chroma[i])) {
                chromatic[i] = true;
                chromaticCount++;
                hueHistogram[Math.min(HUE_BINS - 1, (int) (hue[i] / HUE_BIN_DEGREES))]++;
            }
        }
        if (kept < MIN_SAMPLES) return UNCLEAR;

        float chromaticShare = chromaticCount / (float) kept;
        if (chromaticShare >= CHROMATIC_MAJORITY) {
            return classifyChromatic(hue, sat, val, chromatic, count, chromaticCount, hueHistogram);
        }
        if (1f - chromaticShare >= NEUTRAL_MAJORITY) {
            return classifyNeutral(val, keep, chromatic, count);
        }
        return UNCLEAR;
    }

    static boolean isChromatic(float sat, float val, float chroma) {
        return chroma >= MIN_CHROMA && sat >= MIN_CHROMATIC_SATURATION && val >= MIN_CHROMATIC_VALUE;
    }

    private static String classifyChromatic(float[] hue, float[] sat, float[] val, boolean[] chromatic,
                                            int count, int chromaticCount, int[] histogram) {
        // Circular 3-bin smoothing: bin 35 neighbors bin 0, so red at 355 and 5 add up.
        int peakBin = 0;
        int peakScore = -1;
        for (int bin = 0; bin < HUE_BINS; bin++) {
            int score = histogram[(bin + HUE_BINS - 1) % HUE_BINS] + histogram[bin]
                    + histogram[(bin + 1) % HUE_BINS];
            if (score > peakScore) {
                peakScore = score;
                peakBin = bin;
            }
        }
        float peakCenter = (peakBin + 0.5f) * HUE_BIN_DEGREES;

        double sumSin = 0;
        double sumCos = 0;
        float[] memberSat = new float[chromaticCount];
        float[] memberVal = new float[chromaticCount];
        int members = 0;
        for (int i = 0; i < count; i++) {
            if (!chromatic[i] || circularDistance(hue[i], peakCenter) > PEAK_HALF_WIDTH_DEGREES) continue;
            double radians = Math.toRadians(hue[i]);
            sumSin += Math.sin(radians);
            sumCos += Math.cos(radians);
            memberSat[members] = sat[i];
            memberVal[members] = val[i];
            members++;
        }
        if (members < MIN_PEAK_SHARE * chromaticCount || members == 0) return UNCLEAR;

        float meanHue = (float) Math.toDegrees(Math.atan2(sumSin, sumCos));
        if (meanHue < 0f) meanHue += 360f;
        return nameChromatic(meanHue, median(memberSat, members), median(memberVal, members));
    }

    /** Calibrated HSV ranges. Hue in degrees [0, 360); saturation and value in [0, 1]. */
    static String nameChromatic(float h, float s, float v) {
        if (h >= 345f || h < 12f) {
            // Red wraps around 0. A light, washed-out red reads as pink.
            return v > 0.70f && s < 0.50f ? "pink" : "red";
        }
        if (h < 40f) {
            if (s < 0.40f && v >= 0.60f) return "beige";
            return v < 0.55f ? "brown" : "orange";
        }
        if (h < 68f) {
            if (s < 0.40f && v >= 0.60f) return "beige";
            return v < 0.45f ? "brown" : "yellow";
        }
        if (h < 165f) return "green";
        if (h < 255f) return "blue";
        if (h < 300f) return "purple";
        return "pink";
    }

    private static String classifyNeutral(float[] val, boolean[] keep, boolean[] chromatic, int count) {
        float[] neutralVal = new float[count];
        int n = 0;
        for (int i = 0; i < count; i++) {
            if (keep[i] && !chromatic[i]) neutralVal[n++] = val[i];
        }
        if (n == 0) return UNCLEAR;
        return nameNeutral(median(neutralVal, n));
    }

    static String nameNeutral(float v) {
        if (v < 0.22f) return "black";
        if (v >= 0.78f) return "white";
        if (v >= 0.58f) return "silver";
        return "gray";
    }

    static float hueDegrees(int r, int g, int b, int max, int delta) {
        if (delta == 0) return 0f;
        float h;
        if (max == r) {
            h = 60f * (((g - b) / (float) delta) % 6f);
        } else if (max == g) {
            h = 60f * (((b - r) / (float) delta) + 2f);
        } else {
            h = 60f * (((r - g) / (float) delta) + 4f);
        }
        return h < 0f ? h + 360f : h;
    }

    static float circularDistance(float a, float b) {
        float d = Math.abs(a - b) % 360f;
        return d > 180f ? 360f - d : d;
    }

    private static float median(float[] values, int n) {
        float[] copy = Arrays.copyOf(values, n);
        Arrays.sort(copy);
        return (n % 2 == 1) ? copy[n / 2] : (copy[n / 2 - 1] + copy[n / 2]) / 2f;
    }
}
