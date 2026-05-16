package com.vocasee.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.util.Log;

import org.tensorflow.lite.Interpreter;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;

public class TFLiteDetector {

    private static final String TAG = "TFLiteDetector";

    private static final String[] LABELS = {
            "backpack",      // 0
            "book",          // 1
            "cup",           // 2
            "glasses",       // 3
            "headphone",     // 4
            "key",           // 5
            "phone",         // 6
            "remote",        // 7
            "wallet",        // 8
            "water bottle",  // 9
            "body spray",    // 10
            "card",          // 11
            "charger",       // 12
            "comb",          // 13
            "flashlight",    // 14
            "glasses case",  // 15
            "medicine",      // 16
            "nail clipper",  // 17
            "shoe",          // 18
            "watch"          // 19
    };

    private static final int   INPUT_SIZE        = 640;
    private static final float CONFIDENCE_THRESH = 0.35f;
    private static final float IOU_THRESH        = 0.45f;
    private static final int   NUM_CLASSES       = LABELS.length;
    private static final int   NUM_BOXES         = 8400;

    private final Object lock    = new Object();
    private Interpreter  interpreter;
    private volatile boolean isClosed = false;

    private final ByteBuffer inputBuffer;
    private final int[]      pixelBuffer;
    private final float[][][] output;

    public static class Detection {
        public final String label;
        public final float  confidence;
        // IMPORTANT: box coords are in INPUT_SIZE (640) pixel space, NOT normalized
        public final RectF  boundingBox;

        public Detection(String label, float confidence, RectF boundingBox) {
            this.label       = label;
            this.confidence  = confidence;
            this.boundingBox = boundingBox;
        }
    }

    public TFLiteDetector(Context context, String modelFileName) throws IOException {
        MappedByteBuffer modelBuffer = loadModelFile(context, modelFileName);
        Interpreter.Options options  = new Interpreter.Options();
        options.setNumThreads(4);
        interpreter  = new Interpreter(modelBuffer, options);
        inputBuffer  = ByteBuffer.allocateDirect(1 * INPUT_SIZE * INPUT_SIZE * 3 * 4);
        inputBuffer.order(ByteOrder.nativeOrder());
        pixelBuffer  = new int[INPUT_SIZE * INPUT_SIZE];
        output       = new float[1][4 + NUM_CLASSES][NUM_BOXES];
        Log.d(TAG, "Model loaded: " + modelFileName);
        
        // Log tensor shapes for debugging
        int[] inputShape = interpreter.getInputTensor(0).shape();
        int[] outputShape = interpreter.getOutputTensor(0).shape();
        Log.d(TAG, "Input shape: " + java.util.Arrays.toString(inputShape));
        Log.d(TAG, "Output shape: " + java.util.Arrays.toString(outputShape));
    }

    private MappedByteBuffer loadModelFile(Context context, String fileName) throws IOException {
        try (android.content.res.AssetFileDescriptor fd = context.getAssets().openFd(fileName);
             FileInputStream stream = new FileInputStream(fd.getFileDescriptor())) {
            FileChannel channel = stream.getChannel();
            return channel.map(FileChannel.MapMode.READ_ONLY,
                    fd.getStartOffset(), fd.getDeclaredLength());
        }
    }

    /**
     * Returns detections with boundingBox coords in INPUT_SIZE (640) pixel space.
     * cx, cy, w, h from YOLO are already in 640px space — keep them as-is.
     * Do NOT divide by INPUT_SIZE here; the caller handles coordinate mapping.
     */
    public List<Detection> detect(Bitmap bitmap, String targetLabel) {
        synchronized (lock) {
            List<Detection> detections = new ArrayList<>();
            try {
                if (isClosed || interpreter == null || bitmap == null) return detections;

                // Resize input to 640x640
                Bitmap resized = Bitmap.createScaledBitmap(
                        bitmap, INPUT_SIZE, INPUT_SIZE, true);

                inputBuffer.rewind();
                resized.getPixels(pixelBuffer, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE);
                if (resized != bitmap) {
                    resized.recycle();
                }

                for (int pixel : pixelBuffer) {
                    inputBuffer.putFloat(((pixel >> 16) & 0xFF) / 255.0f);
                    inputBuffer.putFloat(((pixel >>  8) & 0xFF) / 255.0f);
                    inputBuffer.putFloat(( pixel        & 0xFF) / 255.0f);
                }

                // Use the pre-allocated member 'output' instead of creating a new one every frame
                interpreter.run(inputBuffer, output);

                int classToMonitor = -1;
                if (targetLabel != null) {
                    for (int c = 0; c < NUM_CLASSES; c++) {
                        if (LABELS[c].equals(targetLabel)) {
                            classToMonitor = c;
                            break;
                        }
                    }
                    if (classToMonitor == -1) {
                        Log.e(TAG, "Target label NOT FOUND in model labels: " + targetLabel);
                        // If we have a target but it's not in the model, we shouldn't 
                        // fall back to generic mode. We should return empty.
                        return detections;
                    }
                }

                float maxScoreInFrame = 0f;
                int   bestClassInFrame = -1;

                for (int i = 0; i < NUM_BOXES; i++) {
                    float score;
                    int classIdx;

                    if (classToMonitor != -1) {
                        // Intent-based: only look at the requested class
                        score = output[0][4 + classToMonitor][i];
                        classIdx = classToMonitor;
                    } else {
                        // Generic mode (only used if targetLabel is null)
                        score = 0f;
                        classIdx = -1;
                        for (int c = 0; c < NUM_CLASSES; c++) {
                            if (output[0][4 + c][i] > score) {
                                score = output[0][4 + c][i];
                                classIdx = c;
                            }
                        }
                    }

                    if (score > maxScoreInFrame) {
                        maxScoreInFrame = score;
                        bestClassInFrame = classIdx;
                    }

                    if (score < CONFIDENCE_THRESH || classIdx < 0) continue;

                    String detectedLabel = LABELS[classIdx];

                    float cx = output[0][0][i] * INPUT_SIZE;
                    float cy = output[0][1][i] * INPUT_SIZE;
                    float w  = output[0][2][i] * INPUT_SIZE;
                    float h  = output[0][3][i] * INPUT_SIZE;

                    Log.d(TAG, "DETECTED TARGET: " + detectedLabel + " (" + score + ") at [" + cx + "," + cy + "]");

                    RectF box = new RectF(
                            cx - w / 2f,
                            cy - h / 2f,
                            cx + w / 2f,
                            cy + h / 2f
                    );

                    detections.add(new Detection(detectedLabel, score, box));
                }

                if (maxScoreInFrame > 0.05f && bestClassInFrame >= 0) {
                    String prefix = (classToMonitor != -1) ? "Target [" + targetLabel + "]" : "Best Guess";
                    Log.d(TAG, "Inference complete. " + prefix + " max score: " + String.format("%.2f", maxScoreInFrame));
                }

                return applyNMS(detections);

            } catch (Exception e) {
                Log.e(TAG, "Detection error", e);
                return detections;
            }
        }
    }

    private List<Detection> applyNMS(List<Detection> detections) {
        detections.sort((a, b) -> Float.compare(b.confidence, a.confidence));
        List<Detection> result  = new ArrayList<>();
        boolean[]       removed = new boolean[detections.size()];
        for (int i = 0; i < detections.size(); i++) {
            if (removed[i]) continue;
            Detection best = detections.get(i);
            result.add(best);
            for (int j = i + 1; j < detections.size(); j++) {
                if (removed[j]) continue;
                if (!detections.get(j).label.equals(best.label)) continue;
                if (iou(best.boundingBox, detections.get(j).boundingBox) > IOU_THRESH)
                    removed[j] = true;
            }
        }
        return result;
    }

    private float iou(RectF a, RectF b) {
        float iL = Math.max(a.left,   b.left);
        float iT = Math.max(a.top,    b.top);
        float iR = Math.min(a.right,  b.right);
        float iB = Math.min(a.bottom, b.bottom);
        float iW = Math.max(0, iR - iL);
        float iH = Math.max(0, iB - iT);
        float iArea = iW * iH;
        float aArea = (a.right - a.left) * (a.bottom - a.top);
        float bArea = (b.right - b.left) * (b.bottom - b.top);
        return iArea / (aArea + bArea - iArea + 1e-6f);
    }

    public void close() {
        synchronized (lock) {
            isClosed = true;
            try {
                if (interpreter != null) { interpreter.close(); interpreter = null; }
            } catch (Exception e) {
                Log.e(TAG, "Close error", e);
            }
        }
    }
}