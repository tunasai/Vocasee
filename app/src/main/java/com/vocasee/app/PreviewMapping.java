package com.vocasee.app;

/**
 * Coordinate mapping between the camera analysis frame and the on-screen PreviewView.
 * Pure arithmetic on {left, top, right, bottom} arrays so it can be unit tested.
 */
final class PreviewMapping {

    private PreviewMapping() {}

    /**
     * Converts the analysis crop rect (buffer coordinates, as CameraX reports it for the
     * shared viewport) to the upright frame produced by rotating the buffer clockwise by
     * {@code rotationDeg}. A missing or full-size crop means the whole frame is visible.
     */
    static float[] uprightVisibleRegion(int cropLeft, int cropTop, int cropRight, int cropBottom,
                                        int bufferW, int bufferH, int rotationDeg) {
        boolean noCrop = cropRight <= cropLeft || cropBottom <= cropTop
                || (cropRight - cropLeft == bufferW && cropBottom - cropTop == bufferH);
        float l = noCrop ? 0 : cropLeft;
        float t = noCrop ? 0 : cropTop;
        float r = noCrop ? bufferW : cropRight;
        float b = noCrop ? bufferH : cropBottom;
        switch (((rotationDeg % 360) + 360) % 360) {
            case 90:  return new float[] { bufferH - b, l, bufferH - t, r };
            case 180: return new float[] { bufferW - r, bufferH - b, bufferW - l, bufferH - t };
            case 270: return new float[] { t, bufferW - r, b, bufferW - l };
            default:  return new float[] { l, t, r, b };
        }
    }

    /**
     * Maps a box in upright frame pixels to PreviewView pixels. PreviewView (FILL_CENTER)
     * scales the visible region until it covers the view and centers it; with the shared
     * viewport that region already has the view's aspect ratio, so nothing is stretched.
     */
    static float[] sourceToScreen(float[] source, float[] visible, int screenW, int screenH) {
        float visibleW = Math.max(1f, visible[2] - visible[0]);
        float visibleH = Math.max(1f, visible[3] - visible[1]);
        float scale = Math.max(screenW / visibleW, screenH / visibleH);
        float offsetX = (screenW - visibleW * scale) / 2f - visible[0] * scale;
        float offsetY = (screenH - visibleH * scale) / 2f - visible[1] * scale;
        return new float[] {
                source[0] * scale + offsetX, source[1] * scale + offsetY,
                source[2] * scale + offsetX, source[3] * scale + offsetY };
    }
}
