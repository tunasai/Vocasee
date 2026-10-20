package com.vocasee.app;

/**
 * Adaptive brightness and local-contrast enhancement for the detector input, in plain Java
 * on ARGB pixel arrays (no Android types, so it is unit-testable).
 *
 * <p>Works on luminance only and rescales R, G and B by the same factor, which keeps hue and
 * saturation (the effect of equalizing the V channel in HSV). Steps:
 * <ol>
 *   <li>Gamma: brightens dark scenes, tones down washed-out ones.</li>
 *   <li>CLAHE (contrast-limited adaptive histogram equalization): restores edges and rims in
 *       flat or glary regions. The clip limit stops glare highlights and sensor noise from
 *       being amplified.</li>
 * </ol>
 * Run it on the downscaled model-size image, never the full camera frame, to keep it cheap.
 */
final class ImageEnhancer {

    enum Condition { NORMAL, DARK, BRIGHT, LOW_CONTRAST }

    static final float DARK_MEAN_LUMA = 70f;
    static final float BRIGHT_MEAN_LUMA = 185f;
    static final float LOW_CONTRAST_STD = 32f;
    static final float DARK_GAMMA = 0.65f;
    static final float BRIGHT_GAMMA = 1.4f;
    // Clip limit as a multiple of the average histogram bin height. Higher is stronger.
    static final float CLIP_DIFFICULT = 3.0f;
    static final float CLIP_GENTLE = 2.0f;
    static final int TILES = 8;

    /** Mean and standard deviation of luminance over a sample of the pixels. */
    static float[] measure(int[] pixels, int count) {
        int step = Math.max(1, count / 4096);
        double sum = 0;
        double sumSquares = 0;
        int samples = 0;
        for (int i = 0; i < count; i += step) {
            int y = luma(pixels[i]);
            sum += y;
            sumSquares += (double) y * y;
            samples++;
        }
        if (samples == 0) return new float[] { 0f, 0f };
        double mean = sum / samples;
        double variance = Math.max(0, sumSquares / samples - mean * mean);
        return new float[] { (float) mean, (float) Math.sqrt(variance) };
    }

    static Condition classify(float meanLuma, float stdLuma) {
        if (meanLuma < DARK_MEAN_LUMA) return Condition.DARK;
        if (meanLuma > BRIGHT_MEAN_LUMA) return Condition.BRIGHT;
        if (stdLuma < LOW_CONTRAST_STD) return Condition.LOW_CONTRAST;
        return Condition.NORMAL;
    }

    /**
     * Enhances {@code pixels} (width x height, ARGB) in place. {@code gentle} is for a normal
     * scene with a glare-prone target: CLAHE only, at a softer clip limit.
     */
    static void enhance(int[] pixels, int width, int height, Condition condition, boolean gentle) {
        int count = width * height;
        if (count <= 0 || pixels.length < count) return;
        if (condition == Condition.NORMAL && !gentle) return;

        int[] gammaLut = buildGammaLut(condition == Condition.DARK ? DARK_GAMMA
                : condition == Condition.BRIGHT ? BRIGHT_GAMMA : 1f);
        int[] original = new int[count];
        int[] adjusted = new int[count];
        for (int i = 0; i < count; i++) {
            original[i] = luma(pixels[i]);
            adjusted[i] = gammaLut[original[i]];
        }

        float clip = condition == Condition.NORMAL ? CLIP_GENTLE : CLIP_DIFFICULT;
        int[] equalized = clahe(adjusted, width, height, clip);

        for (int i = 0; i < count; i++) {
            int pixel = pixels[i];
            int target = equalized[i];
            int source = original[i];
            int r, g, b;
            if (source <= 0) {
                r = g = b = target;
            } else {
                float ratio = target / (float) source;
                r = clamp255(Math.round(((pixel >> 16) & 0xFF) * ratio));
                g = clamp255(Math.round(((pixel >> 8) & 0xFF) * ratio));
                b = clamp255(Math.round((pixel & 0xFF) * ratio));
            }
            pixels[i] = (pixel & 0xFF000000) | (r << 16) | (g << 8) | b;
        }
    }

    static int[] buildGammaLut(float gamma) {
        int[] lut = new int[256];
        for (int v = 0; v < 256; v++) {
            lut[v] = gamma == 1f ? v : clamp255(Math.round(255f * (float) Math.pow(v / 255f, gamma)));
        }
        return lut;
    }

    /** CLAHE over a TILES x TILES grid with bilinear interpolation between tile mappings. */
    static int[] clahe(int[] luma, int width, int height, float clipFactor) {
        int tilesX = Math.min(TILES, width);
        int tilesY = Math.min(TILES, height);
        float tileW = width / (float) tilesX;
        float tileH = height / (float) tilesY;
        int[][] maps = new int[tilesX * tilesY][];

        for (int ty = 0; ty < tilesY; ty++) {
            int y0 = Math.round(ty * tileH);
            int y1 = Math.round((ty + 1) * tileH);
            for (int tx = 0; tx < tilesX; tx++) {
                int x0 = Math.round(tx * tileW);
                int x1 = Math.round((tx + 1) * tileW);
                int[] histogram = new int[256];
                int pixels = 0;
                for (int y = y0; y < y1; y++) {
                    int row = y * width;
                    for (int x = x0; x < x1; x++) {
                        histogram[luma[row + x]]++;
                        pixels++;
                    }
                }
                maps[ty * tilesX + tx] = tileMapping(histogram, pixels, clipFactor);
            }
        }

        int[] output = new int[width * height];
        for (int y = 0; y < height; y++) {
            float fy = (y + 0.5f) / tileH - 0.5f;
            int ty0 = Math.max(0, Math.min(tilesY - 1, (int) Math.floor(fy)));
            int ty1 = Math.min(tilesY - 1, ty0 + 1);
            float ay = Math.max(0f, Math.min(1f, fy - ty0));
            for (int x = 0; x < width; x++) {
                float fx = (x + 0.5f) / tileW - 0.5f;
                int tx0 = Math.max(0, Math.min(tilesX - 1, (int) Math.floor(fx)));
                int tx1 = Math.min(tilesX - 1, tx0 + 1);
                float ax = Math.max(0f, Math.min(1f, fx - tx0));
                int v = luma[y * width + x];
                float top = maps[ty0 * tilesX + tx0][v] * (1f - ax) + maps[ty0 * tilesX + tx1][v] * ax;
                float bottom = maps[ty1 * tilesX + tx0][v] * (1f - ax) + maps[ty1 * tilesX + tx1][v] * ax;
                output[y * width + x] = clamp255(Math.round(top * (1f - ay) + bottom * ay));
            }
        }
        return output;
    }

    private static int[] tileMapping(int[] histogram, int pixels, float clipFactor) {
        int[] map = new int[256];
        if (pixels == 0) {
            for (int v = 0; v < 256; v++) map[v] = v;
            return map;
        }
        int clipLimit = Math.max(1, Math.round(clipFactor * pixels / 256f));
        int excess = 0;
        for (int v = 0; v < 256; v++) {
            if (histogram[v] > clipLimit) {
                excess += histogram[v] - clipLimit;
                histogram[v] = clipLimit;
            }
        }
        int perBin = excess / 256;
        int remainder = excess % 256;
        // Spread the leftover evenly over the range; piling it on the darkest bins would
        // shift the whole mapping darker.
        int stride = remainder > 0 ? Math.max(1, 256 / remainder) : 0;
        int cumulative = 0;
        for (int v = 0; v < 256; v++) {
            boolean getsRemainder = remainder > 0 && v % stride == 0 && v / stride < remainder;
            cumulative += histogram[v] + perBin + (getsRemainder ? 1 : 0);
            map[v] = clamp255(Math.round(cumulative * 255f / pixels));
        }
        return map;
    }

    static int luma(int pixel) {
        return (77 * ((pixel >> 16) & 0xFF) + 150 * ((pixel >> 8) & 0xFF) + 29 * (pixel & 0xFF)) >> 8;
    }

    private static int clamp255(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private ImageEnhancer() {}
}
