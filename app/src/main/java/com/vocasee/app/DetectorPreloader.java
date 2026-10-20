package com.vocasee.app;

/**
 * Hands the detector LoadingActivity loads to FinderActivity, so the model is not loaded
 * twice at startup. Holds at most one detector; the receiver owns and closes it.
 */
final class DetectorPreloader {

    private static TFLiteDetector preloaded;

    private DetectorPreloader() {}

    static synchronized void offer(TFLiteDetector detector) {
        if (preloaded != null && preloaded != detector) preloaded.close();
        preloaded = detector;
    }

    /** Returns the preloaded detector, or null if there is none; the caller must close it. */
    static synchronized TFLiteDetector take() {
        TFLiteDetector detector = preloaded;
        preloaded = null;
        return detector;
    }
}
