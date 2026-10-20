package com.vocasee.app;

import static org.junit.Assert.assertArrayEquals;

import org.junit.Test;

public class PreviewMappingTest {

    private static final float EPS = 0.01f;

    // Portrait phone: a 640x480 sensor buffer rotated 90 degrees is a 480x640 upright frame.
    // A 1000x2000 view shows a 320x640 band of it (x 80..400). In buffer coordinates that
    // band is rows 80..400, which is what CameraX reports as the viewport crop rect.
    @Test
    public void portraitCropRectBecomesCenteredUprightBand() {
        float[] visible = PreviewMapping.uprightVisibleRegion(0, 80, 640, 400, 640, 480, 90);
        assertArrayEquals(new float[] { 80, 0, 400, 640 }, visible, EPS);
    }

    @Test
    public void visibleBandFillsTheViewExactly() {
        float[] visible = { 80, 0, 400, 640 };
        assertArrayEquals(new float[] { 0, 0, 1000, 2000 },
                PreviewMapping.sourceToScreen(new float[] { 80, 0, 400, 640 }, visible, 1000, 2000), EPS);
        // The frame center stays the view center, and sizes scale uniformly (no stretch).
        assertArrayEquals(new float[] { 375, 875, 625, 1125 },
                PreviewMapping.sourceToScreen(new float[] { 200, 280, 280, 360 }, visible, 1000, 2000), EPS);
    }

    @Test
    public void objectOutsideTheVisibleBandMapsOffScreen() {
        float[] visible = { 80, 0, 400, 640 };
        float[] mapped = PreviewMapping.sourceToScreen(new float[] { 0, 100, 60, 200 }, visible, 1000, 2000);
        assertArrayEquals(new float[] { -250, 312.5f, -62.5f, 625 }, mapped, EPS);
    }

    @Test
    public void withoutCropMatchesTheFormerCenterCropMapping() {
        float[] visible = PreviewMapping.uprightVisibleRegion(0, 0, 640, 480, 640, 480, 90);
        assertArrayEquals(new float[] { 0, 0, 480, 640 }, visible, EPS);
        float[] box = { 100, 150, 300, 400 };
        int screenW = 1080, screenH = 2340;
        // The previous code: scale to cover the view, centered, over the whole frame.
        float scale = Math.max(screenW / 480f, screenH / 640f);
        float offsetX = (screenW - 480 * scale) / 2f;
        float offsetY = (screenH - 640 * scale) / 2f;
        float[] expected = { box[0] * scale + offsetX, box[1] * scale + offsetY,
                box[2] * scale + offsetX, box[3] * scale + offsetY };
        assertArrayEquals(expected, PreviewMapping.sourceToScreen(box, visible, screenW, screenH), EPS);
    }

    @Test
    public void emptyCropMeansWholeFrame() {
        assertArrayEquals(new float[] { 0, 0, 640, 480 },
                PreviewMapping.uprightVisibleRegion(0, 0, 0, 0, 640, 480, 0), EPS);
    }

    @Test
    public void otherRotationsMatchTheBitmapRotation() {
        // Crop columns 100..300, rows 50..250 of a 640x480 buffer.
        assertArrayEquals(new float[] { 100, 50, 300, 250 },
                PreviewMapping.uprightVisibleRegion(100, 50, 300, 250, 640, 480, 0), EPS);
        // 180: (x, y) -> (W - x, H - y)
        assertArrayEquals(new float[] { 340, 230, 540, 430 },
                PreviewMapping.uprightVisibleRegion(100, 50, 300, 250, 640, 480, 180), EPS);
        // 270 (counter-clockwise): (x, y) -> (y, W - x)
        assertArrayEquals(new float[] { 50, 340, 250, 540 },
                PreviewMapping.uprightVisibleRegion(100, 50, 300, 250, 640, 480, 270), EPS);
        // 90 (clockwise): (x, y) -> (H - y, x)
        assertArrayEquals(new float[] { 230, 100, 430, 300 },
                PreviewMapping.uprightVisibleRegion(100, 50, 300, 250, 640, 480, 90), EPS);
    }
}
