package com.vocasee.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.Log;

import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.Tensor;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class TFLiteDetector {

    private static final String TAG = "TFLiteDetector";
    // ObjectCatalog order is the class-index order used by the bundled YOLO26n model.
    private static final String[] LABELS = ObjectCatalog.PREDEFINED_OBJECTS.toArray(new String[0]);
    private static final int BOX_VALUES = 4;
    private static final int NUM_CLASSES = LABELS.length;
    // The Ultralytics predict default is 0.25. Keep the detector's fallback close
    // to that value because this custom indoor model can produce valid scores below 0.42.
    private static final float DEFAULT_CONFIDENCE_THRESH = 0.30f;
    private static final float IOU_THRESH = 0.45f;

    private enum OutputFormat { RAW_CLASS_SCORES, NMS_DETECTIONS }

    private final Object lock = new Object();
    private Interpreter interpreter;
    private volatile boolean isClosed;
    private volatile float confidenceThreshold = DEFAULT_CONFIDENCE_THRESH;
    private long lastDiagnosticLogAt;

    private final int inputWidth;
    private final int inputHeight;
    private final boolean inputChannelsFirst;
    private final DataType inputDataType;
    private final float inputScale;
    private final int inputZeroPoint;
    private final ByteBuffer inputBuffer;
    private final int[] pixelBuffer;

    private final DataType outputDataType;
    private final float outputScale;
    private final int outputZeroPoint;
    private final ByteBuffer outputBuffer;
    private final int outputBoxCount;
    private final int outputValuesPerBox;
    private final boolean channelsFirst;
    private final OutputFormat outputFormat;

    public static class Detection {
        public final String label;
        public final float confidence;
        // Coordinates are in the model input pixel space.
        public final RectF boundingBox;

        public Detection(String label, float confidence, RectF boundingBox) {
            this.label = label;
            this.confidence = confidence;
            this.boundingBox = boundingBox;
        }
    }

    public TFLiteDetector(Context context, String modelFileName) throws IOException {
        Interpreter.Options options = new Interpreter.Options();
        // The hand model runs alongside YOLO during retrieval. Reserving all cores for
        // one inference can starve the live preview and make the box appear frozen, so use
        // at most half the cores. At two threads the model ran at only about 2.5 fps, too few
        // frames for a stable box; up to four cuts that latency on typical 8-core phones.
        int cores = Runtime.getRuntime().availableProcessors();
        options.setNumThreads(Math.max(2, Math.min(4, cores / 2)));
        interpreter = new Interpreter(loadModelFile(context, modelFileName), options);

        Tensor inputTensor = interpreter.getInputTensor(0);
        Tensor outputTensor = interpreter.getOutputTensor(0);
        int[] inputShape = inputTensor.shape();
        int[] outputShape = outputTensor.shape();
        Log.d(TAG, "Model loaded: " + modelFileName);
        Log.d(TAG, "Input: " + java.util.Arrays.toString(inputShape) + " " + inputTensor.dataType());
        Log.d(TAG, "Output: " + java.util.Arrays.toString(outputShape) + " " + outputTensor.dataType());

        boolean nhwcInput = inputShape.length == 4 && inputShape[0] == 1 && inputShape[3] == 3
                && inputShape[1] > 0 && inputShape[2] > 0;
        boolean nchwInput = inputShape.length == 4 && inputShape[0] == 1 && inputShape[1] == 3
                && inputShape[2] > 0 && inputShape[3] > 0;
        if (!nhwcInput && !nchwInput) {
            closeAndThrow("Unsupported input tensor " + java.util.Arrays.toString(inputShape)
                    + ". Expected [1, height, width, 3] or [1, 3, height, width].");
        }
        if (outputShape.length != 3 || outputShape[0] != 1) {
            closeAndThrow("Unsupported output tensor " + java.util.Arrays.toString(outputShape)
                    + ". Expected a three-dimensional YOLO output.");
        }

        inputChannelsFirst = nchwInput;
        inputHeight = inputChannelsFirst ? inputShape[2] : inputShape[1];
        inputWidth = inputChannelsFirst ? inputShape[3] : inputShape[2];
        inputDataType = inputTensor.dataType();
        inputScale = inputTensor.quantizationParams().getScale();
        inputZeroPoint = inputTensor.quantizationParams().getZeroPoint();
        if (inputDataType != DataType.FLOAT32 && inputDataType != DataType.UINT8
                && inputDataType != DataType.INT8) {
            closeAndThrow("Unsupported input data type " + inputDataType + ".");
        }

        channelsFirst = outputShape[1] <= outputShape[2];
        outputValuesPerBox = channelsFirst ? outputShape[1] : outputShape[2];
        outputBoxCount = channelsFirst ? outputShape[2] : outputShape[1];
        int requiredRawValues = BOX_VALUES + NUM_CLASSES;
        if (outputValuesPerBox >= requiredRawValues && outputBoxCount > 0) {
            outputFormat = OutputFormat.RAW_CLASS_SCORES;
        } else if (outputValuesPerBox == 6 && outputBoxCount > 0) {
            // Standard YOLO26n export supplies pre-filtered rows of
            // [left, top, right, bottom, confidence, classIndex].
            outputFormat = OutputFormat.NMS_DETECTIONS;
        } else {
            closeAndThrow("Unsupported output tensor " + java.util.Arrays.toString(outputShape)
                    + ". Expected raw class scores or NMS detections with six values.");
            throw new AssertionError("Unreachable after closing unsupported model output.");
        }

        outputDataType = outputTensor.dataType();
        outputScale = outputTensor.quantizationParams().getScale();
        outputZeroPoint = outputTensor.quantizationParams().getZeroPoint();
        if (outputDataType != DataType.FLOAT32 && outputDataType != DataType.UINT8
                && outputDataType != DataType.INT8) {
            closeAndThrow("Unsupported output data type " + outputDataType + ".");
        }

        inputBuffer = ByteBuffer.allocateDirect(inputTensor.numBytes()).order(ByteOrder.nativeOrder());
        outputBuffer = ByteBuffer.allocateDirect(outputTensor.numBytes()).order(ByteOrder.nativeOrder());
        pixelBuffer = new int[inputWidth * inputHeight];
    }

    public int getInputWidth() {
        return inputWidth;
    }

    public int getInputHeight() {
        return inputHeight;
    }

    public void setConfidenceThreshold(float value) {
        confidenceThreshold = Math.max(0.05f, Math.min(0.95f, value));
    }

    public List<Detection> detect(Bitmap bitmap, String targetLabel) {
        return detect(bitmap, targetLabel, confidenceThreshold);
    }

    /** Uses a per-frame threshold so an established target can survive a brief confidence dip. */
    public List<Detection> detect(Bitmap bitmap, String targetLabel, float minimumConfidence) {
        synchronized (lock) {
            float frameThreshold = Math.max(0.05f, Math.min(0.95f, minimumConfidence));
            List<Detection> detections = new ArrayList<>();
            try {
                if (isClosed || interpreter == null || bitmap == null) return detections;
                writeInput(bitmap);
                outputBuffer.rewind();
                interpreter.run(inputBuffer, outputBuffer);
                outputBuffer.rewind();

                logRawClassScoresPeriodically();

                int targetClass = getTargetClass(targetLabel);
                if (targetLabel != null && targetClass < 0) {
                    Log.e(TAG, "Target label not in model classes: " + targetLabel);
                    return detections;
                }

                YoloDecoding.Output output = this::outputValue;
                List<YoloDecoding.Candidate> candidates = outputFormat == OutputFormat.NMS_DETECTIONS
                        ? YoloDecoding.decodeNms(output, outputBoxCount, NUM_CLASSES, targetClass,
                                frameThreshold, inputWidth, inputHeight, IOU_THRESH)
                        : YoloDecoding.decodeRaw(output, outputBoxCount, NUM_CLASSES, targetClass,
                                frameThreshold, inputWidth, inputHeight, IOU_THRESH);
                for (YoloDecoding.Candidate candidate : candidates) {
                    detections.add(new Detection(LABELS[candidate.classIndex], candidate.confidence,
                            new RectF(candidate.left, candidate.top, candidate.right, candidate.bottom)));
                }
                return detections;
            } catch (Exception exception) {
                Log.e(TAG, "Detection error", exception);
                return detections;
            }
        }
    }

    private void writeInput(Bitmap bitmap) {
        Bitmap resized = bitmap.getWidth() == inputWidth && bitmap.getHeight() == inputHeight
                ? bitmap : Bitmap.createScaledBitmap(bitmap, inputWidth, inputHeight, true);
        inputBuffer.rewind();
        resized.getPixels(pixelBuffer, 0, inputWidth, 0, 0, inputWidth, inputHeight);
        if (resized != bitmap) resized.recycle();
        if (inputChannelsFirst) {
            // This model uses NCHW: write the full red plane, then green, then blue.
            for (int channel = 0; channel < 3; channel++) {
                int shift = 16 - (channel * 8);
                for (int pixel : pixelBuffer) {
                    writeInputChannel((pixel >> shift) & 0xFF);
                }
            }
        } else {
            for (int pixel : pixelBuffer) {
                writeInputChannel((pixel >> 16) & 0xFF);
                writeInputChannel((pixel >> 8) & 0xFF);
                writeInputChannel(pixel & 0xFF);
            }
        }
        inputBuffer.rewind();
    }

    private void writeInputChannel(int channel) {
        float normalized = channel / 255f;
        if (inputDataType == DataType.FLOAT32) {
            inputBuffer.putFloat(normalized);
            return;
        }
        float scale = inputScale > 0f ? inputScale : 1f / 255f;
        int quantized = Math.round(normalized / scale) + inputZeroPoint;
        if (inputDataType == DataType.UINT8) {
            inputBuffer.put((byte) Math.max(0, Math.min(255, quantized)));
        } else {
            inputBuffer.put((byte) Math.max(-128, Math.min(127, quantized)));
        }
    }

    private int getTargetClass(String targetLabel) {
        if (targetLabel == null) return -1;
        for (int index = 0; index < NUM_CLASSES; index++) {
            if (LABELS[index].equals(targetLabel)) return index;
        }
        return -1;
    }

    private float outputValue(int valueIndex, int boxIndex) {
        int flatIndex = YoloDecoding.flatIndex(
                channelsFirst, valueIndex, boxIndex, outputBoxCount, outputValuesPerBox);
        if (outputDataType == DataType.FLOAT32) return outputBuffer.getFloat(flatIndex * 4);
        int raw = outputBuffer.get(flatIndex);
        if (outputDataType == DataType.UINT8) raw &= 0xFF;
        return (raw - outputZeroPoint) * outputScale;
    }

    /**
     * Temporary deployment diagnostic. It reports raw scores before target filtering
     * and NMS, so a Logcat capture can distinguish a model-input problem from a
     * label-mapping or confidence-filter problem.
     */
    private void logRawClassScoresPeriodically() {
        // Off by default; enable with: adb shell setprop log.tag.<TAG> DEBUG
        if (!Log.isLoggable(TAG, Log.DEBUG)) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastDiagnosticLogAt < 1000L) return;
        lastDiagnosticLogAt = now;

        if (outputFormat == OutputFormat.NMS_DETECTIONS) {
            int detections = 0;
            float topConfidence = 0f;
            for (int boxIndex = 0; boxIndex < outputBoxCount; boxIndex++) {
                float confidence = outputValue(4, boxIndex);
                if (confidence > 0f) {
                    detections++;
                    topConfidence = Math.max(topConfidence, confidence);
                }
            }
            Log.d(TAG, "YOLO26n NMS rows: " + detections + ", top confidence="
                    + String.format(Locale.US, "%.3f", topConfidence));
            return;
        }

        StringBuilder report = new StringBuilder("Raw YOLO top scores: ");
        for (int classIndex = 0; classIndex < NUM_CLASSES; classIndex++) {
            float topScore = -Float.MAX_VALUE;
            int topBoxIndex = -1;
            for (int boxIndex = 0; boxIndex < outputBoxCount; boxIndex++) {
                float score = outputValue(BOX_VALUES + classIndex, boxIndex);
                if (score > topScore) {
                    topScore = score;
                    topBoxIndex = boxIndex;
                }
            }
            if (classIndex > 0) report.append(" | ");
            report.append(LABELS[classIndex])
                    .append('=')
                    .append(String.format(Locale.US, "%.3f", topScore))
                    .append("@")
                    .append(topBoxIndex);
        }
        Log.d(TAG, report.toString());
    }

    private MappedByteBuffer loadModelFile(Context context, String fileName) throws IOException {
        try (android.content.res.AssetFileDescriptor fd = context.getAssets().openFd(fileName);
             FileInputStream stream = new FileInputStream(fd.getFileDescriptor())) {
            FileChannel channel = stream.getChannel();
            return channel.map(FileChannel.MapMode.READ_ONLY, fd.getStartOffset(), fd.getDeclaredLength());
        }
    }

    private void closeAndThrow(String message) throws IOException {
        if (interpreter != null) {
            interpreter.close();
            interpreter = null;
        }
        throw new IOException(message);
    }

    public void close() {
        synchronized (lock) {
            isClosed = true;
            if (interpreter != null) {
                interpreter.close();
                interpreter = null;
            }
        }
    }
}
