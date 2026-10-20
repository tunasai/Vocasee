package com.vocasee.app;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ImageEnhancerTest {

    // Realistic detector-input size, so each CLAHE tile has enough pixels for clipping.
    private static final int W = 320;
    private static final int H = 240;

    @Test
    public void classifiesLightingConditions() {
        assertEquals(ImageEnhancer.Condition.DARK, ImageEnhancer.classify(40f, 50f));
        assertEquals(ImageEnhancer.Condition.BRIGHT, ImageEnhancer.classify(220f, 50f));
        assertEquals(ImageEnhancer.Condition.LOW_CONTRAST, ImageEnhancer.classify(120f, 10f));
        assertEquals(ImageEnhancer.Condition.NORMAL, ImageEnhancer.classify(120f, 60f));
    }

    @Test
    public void normalSceneIsLeftUntouched() {
        int[] pixels = gradient(30, 220);
        int[] copy = pixels.clone();
        ImageEnhancer.enhance(pixels, W, H, ImageEnhancer.Condition.NORMAL, false);
        assertArrayEquals(copy, pixels);
    }

    @Test
    public void darkSceneGetsBrighter() {
        int[] pixels = gradient(5, 60);
        float before = ImageEnhancer.measure(pixels, pixels.length)[0];
        ImageEnhancer.enhance(pixels, W, H, ImageEnhancer.Condition.DARK, false);
        float after = ImageEnhancer.measure(pixels, pixels.length)[0];
        assertTrue("mean " + before + " -> " + after, after > before + 20f);
    }

    @Test
    public void faintEdgesBecomeStronger() {
        // Faint stripes, like the low-contrast rim of a clear cup against a wall.
        int[] pixels = new int[W * H];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int v = 120 + ((x / 4) % 2) * 10;
                pixels[y * W + x] = 0xFF000000 | (v << 16) | (v << 8) | v;
            }
        }
        float before = edgeStrength(pixels);
        ImageEnhancer.enhance(pixels, W, H, ImageEnhancer.Condition.LOW_CONTRAST, false);
        float after = edgeStrength(pixels);
        assertTrue("edge " + before + " -> " + after, after > before * 1.25f);
    }

    /** Mean absolute luminance step between horizontal neighbours. */
    private static float edgeStrength(int[] pixels) {
        long total = 0;
        for (int y = 0; y < H; y++) {
            for (int x = 1; x < W; x++) {
                total += Math.abs(ImageEnhancer.luma(pixels[y * W + x])
                        - ImageEnhancer.luma(pixels[y * W + x - 1]));
            }
        }
        return total / (float) (H * (W - 1));
    }

    @Test
    public void uniformImageStaysUniformAndKeepsAlpha() {
        int[] pixels = new int[W * H];
        java.util.Arrays.fill(pixels, 0xFF808080);
        ImageEnhancer.enhance(pixels, W, H, ImageEnhancer.Condition.LOW_CONTRAST, false);
        for (int pixel : pixels) {
            assertEquals(pixels[0], pixel);
            assertEquals(0xFF, pixel >>> 24);
        }
    }

    @Test
    public void keepsHueByScalingChannelsTogether() {
        int[] pixels = new int[W * H];
        for (int i = 0; i < pixels.length; i++) {
            int v = 10 + (i % W) * 30 / W;     // dark reddish ramp, red = 2x green
            pixels[i] = 0xFF000000 | ((2 * v) << 16) | (v << 8);
        }
        ImageEnhancer.enhance(pixels, W, H, ImageEnhancer.Condition.DARK, false);
        int checked = 0;
        for (int pixel : pixels) {
            int r = (pixel >> 16) & 0xFF;
            int g = (pixel >> 8) & 0xFF;
            if (r >= 250 || g < 10) continue;   // clipped channels cannot keep the ratio
            assertTrue("r=" + r + " g=" + g, Math.abs(r / (float) g - 2f) < 0.25f);
            checked++;
        }
        assertTrue(checked > pixels.length / 2);
    }

    private static int[] gradient(int low, int high) {
        int[] pixels = new int[W * H];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int v = low + (high - low) * x / (W - 1);
                pixels[y * W + x] = 0xFF000000 | (v << 16) | (v << 8) | v;
            }
        }
        return pixels;
    }
}
