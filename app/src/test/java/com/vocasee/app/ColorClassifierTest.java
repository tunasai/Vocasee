package com.vocasee.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Random;

public class ColorClassifierTest {

    private static final int N = ColorClassifier.GRID * ColorClassifier.GRID;

    private static int argb(int r, int g, int b) {
        return 0xFF000000 | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    /** Fills a patch with one color plus deterministic sensor-like noise. */
    private static int[] patch(int r, int g, int b) {
        Random random = new Random(42);
        int[] pixels = new int[N];
        for (int i = 0; i < N; i++) {
            pixels[i] = argb(r + random.nextInt(17) - 8, g + random.nextInt(17) - 8,
                    b + random.nextInt(17) - 8);
        }
        return pixels;
    }

    private static String classify(int[] pixels) {
        return ColorClassifier.classify(pixels, pixels.length);
    }

    @Test
    public void namesSolidColors() {
        assertEquals("red", classify(patch(200, 30, 30)));
        assertEquals("orange", classify(patch(230, 120, 30)));
        assertEquals("brown", classify(patch(120, 70, 30)));
        assertEquals("yellow", classify(patch(230, 210, 40)));
        assertEquals("green", classify(patch(40, 160, 60)));
        assertEquals("blue", classify(patch(40, 80, 200)));
        assertEquals("purple", classify(patch(120, 50, 170)));
        assertEquals("pink", classify(patch(230, 120, 170)));
        assertEquals("beige", classify(patch(225, 205, 165)));
    }

    @Test
    public void namesNeutrals() {
        assertEquals("white", classify(patch(235, 235, 230)));
        assertEquals("white", classify(patch(240, 230, 215))); // warm white balance
        assertEquals("silver", classify(patch(175, 178, 182)));
        assertEquals("gray", classify(patch(110, 110, 110)));
        assertEquals("black", classify(patch(22, 22, 25)));
    }

    @Test
    public void redWrapsAroundZeroDegrees() {
        int[] pixels = new int[N];
        for (int i = 0; i < N; i++) {
            // Alternate hue ~354 (blue-tinted red) and ~6 (orange-tinted red).
            pixels[i] = i % 2 == 0 ? argb(210, 20, 40) : argb(210, 40, 20);
        }
        assertEquals("red", classify(pixels));
    }

    @Test
    public void shadowGradientKeepsHue() {
        int[] pixels = new int[N];
        for (int i = 0; i < N; i++) {
            float light = 0.35f + 0.65f * i / N; // dark shadow to full light
            pixels[i] = argb(Math.round(40 * light), Math.round(80 * light), Math.round(210 * light));
        }
        assertEquals("blue", classify(pixels));
    }

    @Test
    public void hardShadowAndSpecularHighlightAreIgnored() {
        int[] pixels = patch(200, 30, 30);
        for (int i = 0; i < N; i++) {
            if (i % 4 == 0) pixels[i] = argb(255, 255, 255);    // 25% glare
            else if (i % 10 == 1) pixels[i] = argb(8, 8, 8);    // 10% hard shadow
        }
        assertEquals("red", classify(pixels));
    }

    @Test
    public void dominantHueWinsButEvenSplitIsUnclear() {
        int[] mostlyGreen = patch(40, 160, 60);
        for (int i = 0; i < N; i += 4) mostlyGreen[i] = argb(40, 80, 200);
        assertEquals("green", classify(mostlyGreen));

        int[] split = new int[N];
        for (int i = 0; i < N; i++) split[i] = i % 2 == 0 ? argb(200, 30, 30) : argb(40, 80, 200);
        assertEquals(ColorClassifier.UNCLEAR, classify(split));
    }

    @Test
    public void tooFewSamplesIsUnclear() {
        assertEquals(ColorClassifier.UNCLEAR, ColorClassifier.classify(patch(200, 30, 30), 10));
        assertEquals(ColorClassifier.UNCLEAR, ColorClassifier.classify(null, 0));
    }

    @Test
    public void fullPatchClassifiesWellUnderAMillisecond() {
        int[] pixels = patch(230, 120, 30);
        for (int i = 0; i < 2000; i++) classify(pixels); // warm up the JIT
        int runs = 5000;
        long start = System.nanoTime();
        for (int i = 0; i < runs; i++) classify(pixels);
        double avgMicros = (System.nanoTime() - start) / 1000.0 / runs;
        System.out.println("ColorClassifier avg per " + N + "-pixel patch: " + avgMicros + " us");
        assertTrue("classification too slow: " + avgMicros + " us", avgMicros < 1000);
    }
}
