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

    // Must match your YAML class order exactly
    private static final String[] LABELS = {
            "backpack",     // 0
            "book",         // 1
            "cup",          // 2
            "glasses",      // 3
            "headphone",    // 4
            "key",          // 5
            "phone",        // 6
            "remote",       // 7
            "wallet",       // 8
            "water bottle"  // 9
    };

    private static final int   INPUT_SIZE        = 640;   // imgsz used during training
    private static final float CONFIDENCE_THRESH = 0.45f; // minimum score to count as detection
    private static final float IOU_THRESH        = 0.45f; // NMS overlap threshold
    private static final int   NUM_CLASSES       = LABELS.length; // 10
    // YOLOv11n output shape: [1, 4 + NUM_CLASSES, num_boxes]
    // For imgsz=640: num_boxes = 8400
    private static final int   NUM_BOXES         = 8400;

    private final Interpreter interpreter;

    // ── Result container ──────────────────────────────────────────────────

    public static class Detection {
        public final String label;
        public final float  confidence;
        public final RectF  boundingBox; // pixel coords relative to INPUT_SIZE

        public Detection(String label, float confidence, RectF boundingBox) {
            this.label       = label;
            this.confidence  = confidence;
            this.boundingBox = boundingBox;
        }
    }

    // ── Constructor ───────────────────────────────────────────────────────

    public TFLiteDetector(Context context, String modelFileName) throws IOException {
        MappedByteBuffer modelBuffer = loadModelFile(context, modelFileName);
        Interpreter.Options options  = new Interpreter.Options();
        options.setNumThreads(4); // use 4 CPU threads for inference
        interpreter = new Interpreter(modelBuffer, options);
        Log.d(TAG, "TFLite model loaded: " + modelFileName);
    }

    private MappedByteBuffer loadModelFile(Context context, String fileName) throws IOException {
        // Opens the .tflite file from assets/ as a memory-mapped buffer
        android.content.res.AssetFileDescriptor fd =
                context.getAssets().openFd(fileName);
        FileInputStream stream = new FileInputStream(fd.getFileDescriptor());
        FileChannel channel    = stream.getChannel();
        return channel.map(
                FileChannel.MapMode.READ_ONLY,
                fd.getStartOffset(),
                fd.getDeclaredLength()
        );
    }

    // ── Inference ─────────────────────────────────────────────────────────

    /**
     * Runs YOLOv11n inference on a single Bitmap frame.
     * Returns a list of detections that pass confidence + NMS filtering,
     * or an empty list if nothing is detected.
     *
     * @param bitmap Camera frame — will be scaled to 640x640 internally
     * @return List of Detection objects sorted by confidence (highest first)
     */
    public List<Detection> detect(Bitmap bitmap) {
        // 1. Resize frame to model input size
        Bitmap resized = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true);

        // 2. Convert Bitmap → ByteBuffer (float32, normalized 0–1)
        ByteBuffer inputBuffer = ByteBuffer.allocateDirect(
                1 * INPUT_SIZE * INPUT_SIZE * 3 * 4); // batch=1, HxW, RGB, float32
        inputBuffer.order(ByteOrder.nativeOrder());

        int[] pixels = new int[INPUT_SIZE * INPUT_SIZE];
        resized.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE);
        resized.recycle();

        for (int pixel : pixels) {
            inputBuffer.putFloat(((pixel >> 16) & 0xFF) / 255.0f); // R
            inputBuffer.putFloat(((pixel >> 8)  & 0xFF) / 255.0f); // G
            inputBuffer.putFloat(( pixel        & 0xFF) / 255.0f); // B
        }

        // 3. Prepare output buffer
        // YOLOv11n output: [1, 4 + num_classes, 8400]
        float[][][] output = new float[1][4 + NUM_CLASSES][NUM_BOXES];
        interpreter.run(inputBuffer, output);

        // 4. Parse raw output into Detection objects
        List<Detection> detections = new ArrayList<>();
        float[][][] out = output;

        for (int i = 0; i < NUM_BOXES; i++) {
            // Find class with highest score for this box
            float maxScore = 0f;
            int   maxClass = -1;
            for (int c = 0; c < NUM_CLASSES; c++) {
                float score = out[0][4 + c][i];
                if (score > maxScore) {
                    maxScore = score;
                    maxClass = c;
                }
            }

            if (maxScore < CONFIDENCE_THRESH) continue;

            // YOLO outputs cx, cy, w, h (center format, normalized 0–1)
            float cx = out[0][0][i] * INPUT_SIZE;
            float cy = out[0][1][i] * INPUT_SIZE;
            float w  = out[0][2][i] * INPUT_SIZE;
            float h  = out[0][3][i] * INPUT_SIZE;

            RectF box = new RectF(
                    cx - w / 2f, // left
                    cy - h / 2f, // top
                    cx + w / 2f, // right
                    cy + h / 2f  // bottom
            );

            detections.add(new Detection(LABELS[maxClass], maxScore, box));
        }

        // 5. Apply Non-Maximum Suppression to remove duplicate boxes
        return applyNMS(detections);
    }

    // ── Non-Maximum Suppression ───────────────────────────────────────────

    /**
     * Removes overlapping boxes for the same class, keeping only the
     * highest-confidence one when overlap (IoU) exceeds IOU_THRESH.
     */
    private List<Detection> applyNMS(List<Detection> detections) {
        // Sort by confidence descending
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
                if (iou(best.boundingBox, detections.get(j).boundingBox) > IOU_THRESH) {
                    removed[j] = true;
                }
            }
        }
        return result;
    }

    private float iou(RectF a, RectF b) {
        float interLeft   = Math.max(a.left,   b.left);
        float interTop    = Math.max(a.top,    b.top);
        float interRight  = Math.min(a.right,  b.right);
        float interBottom = Math.min(a.bottom, b.bottom);

        float interW = Math.max(0, interRight  - interLeft);
        float interH = Math.max(0, interBottom - interTop);
        float interArea = interW * interH;

        float aArea = (a.right - a.left) * (a.bottom - a.top);
        float bArea = (b.right - b.left) * (b.bottom - b.top);

        return interArea / (aArea + bArea - interArea + 1e-6f);
    }

    // ── Cleanup ───────────────────────────────────────────────────────────

    public void close() {
        if (interpreter != null) interpreter.close();
    }
}