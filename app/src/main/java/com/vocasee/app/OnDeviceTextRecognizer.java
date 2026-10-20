package com.vocasee.app;

import android.graphics.Bitmap;
import android.util.Log;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

/** Small wrapper around ML Kit's bundled, fully on-device Latin OCR model. */
public final class OnDeviceTextRecognizer {

    private static final String TAG = "OnDeviceTextRecognizer";
    private static final int MAX_OUTPUT_LENGTH = 80;

    public interface Callback {
        void onResult(String text);
    }

    private final TextRecognizer recognizer =
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);

    public void recognize(Bitmap bitmap, Callback callback) {
        if (bitmap == null || bitmap.isRecycled()) {
            callback.onResult(null);
            return;
        }
        InputImage image = InputImage.fromBitmap(bitmap, 0);
        recognizer.process(image)
                .addOnSuccessListener(result -> {
                    String text = normalize(result.getText());
                    recycle(bitmap);
                    callback.onResult(text);
                })
                .addOnFailureListener(error -> {
                    Log.w(TAG, "On-device OCR could not read the object label", error);
                    recycle(bitmap);
                    callback.onResult(null);
                });
    }

    public void close() {
        recognizer.close();
    }

    private static String normalize(String text) {
        if (text == null) return null;
        String normalized = text.replaceAll("\\s+", " ").trim();
        if (normalized.isEmpty()) return null;
        return normalized.length() <= MAX_OUTPUT_LENGTH
                ? normalized
                : normalized.substring(0, MAX_OUTPUT_LENGTH).trim();
    }

    private static void recycle(Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
    }
}
