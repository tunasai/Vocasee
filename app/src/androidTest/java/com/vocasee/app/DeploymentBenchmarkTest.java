package com.vocasee.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.AssetFileDescriptor;
import android.os.BatteryManager;
import android.os.Build;
import android.os.PowerManager;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.Tensor;

import java.io.BufferedInputStream;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Thesis deployment benchmark for the nine VocaSee TFLite model artifacts.
 *
 * This is deliberately separate from FinderActivity: it measures only the synchronous
 * TensorFlow Lite inference call on the physical Android device, without camera, UI,
 * speech, OCR, hand tracking, or throttling overhead. Results are written as CSV/text
 * files to the target app's external files directory for preservation in the appendix.
 */
@RunWith(AndroidJUnit4.class)
public class DeploymentBenchmarkTest {

    private static final String TAG = "VocaSeeBenchmark";
    private static final int THREAD_COUNT = 4;
    private static final int WARMUP_SAMPLES = 30;
    private static final int MEASURED_SAMPLES = 100;
    private static final int REPETITIONS_PER_PROCESS = 1;
    private static final long INTER_SAMPLE_PAUSE_MS = 120L;
    private static final long COOLDOWN_BETWEEN_SESSIONS_MS = 30_000L;

    private static final ModelSpec[] MODELS = {
            new ModelSpec("70/20/10", "YOLOv5n", false, "split70_yolov5n.tflite"),
            new ModelSpec("70/20/10", "YOLOv8n", false, "split70_yolov8n.tflite"),
            new ModelSpec("70/20/10", "YOLO11n", false, "split70_yolo11n.tflite"),
            new ModelSpec("70/20/10", "YOLO26n", false, "split70_yolo26n.tflite"),
            new ModelSpec("80/10/10", "YOLOv5n", false, "split80_yolov5n.tflite"),
            new ModelSpec("80/10/10", "YOLOv8n", false, "split80_yolov8n.tflite"),
            new ModelSpec("80/10/10", "YOLO11n", false, "split80_yolo11n.tflite"),
            new ModelSpec("80/10/10", "YOLO11n", true, "split80_yolo11n_refined.tflite"),
            new ModelSpec("80/10/10", "YOLO26n", false, "split80_yolo26n.tflite")
    };

    @Test
    public void benchmarkAllDeploymentModels() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ModelSpec[] selectedModels = selectedModelsFromRunnerArguments();
        int repetitionLabel = repetitionLabelFromRunnerArguments();
        runBenchmark(context, selectedModels, repetitionLabel);
    }

    /** Benchmarks a supplied TFLite export staged in app-specific external storage. */
    @Test
    public void benchmarkExternalDatasetSplitModel() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        android.os.Bundle arguments = InstrumentationRegistry.getArguments();
        String modelFileName = arguments.getString("model_file", "").trim();
        String modelName = arguments.getString("model_name", "").trim();
        String datasetSplit = arguments.getString("dataset_split", "80/10/10").trim();
        boolean refined = Boolean.parseBoolean(arguments.getString("refined", "false").trim());
        if (!Arrays.asList("yolov5n_24class.tflite", "yolov8n_24class.tflite",
                "yolo11n_24class.tflite", "yolo26n_24class.tflite",
                "yolo26n_150e_24class.tflite").contains(modelFileName)) {
            throw new IllegalArgumentException("Unrecognized 24-class model_file: " + modelFileName);
        }
        if (modelName.isEmpty()) {
            throw new IllegalArgumentException("model_name is required");
        }
        if (!Arrays.asList("70/20/10", "80/10/10").contains(datasetSplit)) {
            throw new IllegalArgumentException("Unsupported dataset_split: " + datasetSplit);
        }
        int repetitionLabel = repetitionLabelFromRunnerArguments();
        File externalRoot = context.getExternalFilesDir(null);
        assertNotNull("External files directory is unavailable", externalRoot);
        File modelFile = new File(new File(externalRoot, "new24class"), modelFileName);
        if (!modelFile.isFile()) {
            throw new IllegalStateException("Staged model file is missing: " + modelFile);
        }
        ModelSpec model = new ModelSpec(datasetSplit, modelName, refined,
                modelFileName, modelFile.getAbsolutePath());
        runBenchmark(context, new ModelSpec[]{model}, repetitionLabel);
    }

    private void runBenchmark(Context context, ModelSpec[] selectedModels,
                              int repetitionLabel) throws Exception {
        PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "VocaSee:DeploymentBenchmark");
        wakeLock.acquire(60L * 60L * 1000L);
        Intent foregroundIntent = new Intent(context, WelcomeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        context.startActivity(foregroundIntent);
        Thread.sleep(1_000L);
        File externalRoot = context.getExternalFilesDir(null);
        assertNotNull("External files directory is unavailable", externalRoot);

        String safeModelName = selectedModels.length == 1
                ? selectedModels[0].assetFile.replace(".tflite", "") : "all_models";
        File outputDirectory = new File(externalRoot,
                "deployment_benchmark_" + safeModelName + "_r" + repetitionLabel
                        + "_" + utcTimestampForFile());
        if (!outputDirectory.mkdirs() && !outputDirectory.isDirectory()) {
            throw new IllegalStateException("Could not create " + outputDirectory);
        }

        File rawFile = new File(outputDirectory, "03_raw_inference_results.csv");
        File inventoryFile = new File(outputDirectory, "02_model_inventory.csv");
        File summaryByRunFile = new File(outputDirectory, "04a_summary_by_run.csv");
        File overallSummaryFile = new File(outputDirectory, "04b_summary_overall.csv");
        File protocolFile = new File(outputDirectory, "05_benchmark_protocol.txt");
        File logFile = new File(outputDirectory, "06_benchmark_completion_log.txt");
        File deviceFile = new File(outputDirectory, "01_device_information.txt");

        writeDeviceInformation(context, deviceFile);
        writeProtocol(protocolFile);
        writeInventoryHeader(inventoryFile);

        List<ModelResult> allResults = new ArrayList<>();
        try (BufferedWriter raw = writer(rawFile);
             BufferedWriter log = writer(logFile)) {
            raw.write("timestamp_utc,dataset_split,model,refined,asset_file,repetition,sample,inference_ns,inference_ms,battery_percent,battery_temperature_c,thermal_status\n");
            raw.flush();
            log.write("VocaSee Android deployment benchmark\n");
            log.write("Started UTC: " + utcTimestamp() + "\n");
            log.write("Output directory: " + outputDirectory.getAbsolutePath() + "\n\n");

            int sessionNumber = 0;
            int totalSessions = selectedModels.length * REPETITIONS_PER_PROCESS;
            for (int localRepetition = 1;
                 localRepetition <= REPETITIONS_PER_PROCESS; localRepetition++) {
                for (ModelSpec model : orderedModelsForRepetition(
                        repetitionLabel, selectedModels)) {
                    sessionNumber++;
                    if (sessionNumber > 1) {
                        Thread.sleep(COOLDOWN_BETWEEN_SESSIONS_MS);
                    }

                    String startMessage = String.format(Locale.US,
                            "START session=%d/%d split=%s model=%s refined=%s repetition=%d UTC=%s",
                            sessionNumber, totalSessions, model.datasetSplit, model.modelName,
                            model.refined, repetitionLabel, utcTimestamp());
                    Log.i(TAG, startMessage);
                    log.write(startMessage + "\n");
                    log.flush();

                    ModelResult result = runSession(context, model, repetitionLabel, raw,
                            inventoryFile);
                    allResults.add(result);

                    String completionMessage = String.format(Locale.US,
                            "COMPLETE split=%s model=%s refined=%s repetition=%d mean_ms=%.3f p95_ms=%.3f derived_fps=%.3f",
                            model.datasetSplit, model.modelName, model.refined, repetitionLabel,
                            result.statistics.meanMs, result.statistics.p95Ms,
                            result.statistics.derivedFps);
                    Log.i(TAG, completionMessage);
                    log.write(completionMessage + "\n");
                    log.flush();
                }
            }

            log.write("\nCompleted UTC: " + utcTimestamp() + "\n");
            log.write("Recorded measurements: "
                    + (selectedModels.length * REPETITIONS_PER_PROCESS * MEASURED_SAMPLES)
                    + "\n");
        }

        writeSummaryByRun(summaryByRunFile, allResults);
        writeOverallSummary(overallSummaryFile, allResults, selectedModels);
        writeChecksums(outputDirectory);

        Log.i(TAG, "RESULT_DIRECTORY=" + outputDirectory.getAbsolutePath());
        Log.i(TAG, "BENCHMARK_FINISHED measurements="
                + (selectedModels.length * REPETITIONS_PER_PROCESS * MEASURED_SAMPLES));
        if (wakeLock.isHeld()) wakeLock.release();
    }

    @Test
    public void smokeTestAllModels() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "VocaSee:DeploymentBenchmarkSmokeTest");
        wakeLock.acquire(5L * 60L * 1000L);
        try {
            for (ModelSpec model : MODELS) {
                Interpreter.Options options = new Interpreter.Options()
                        .setNumThreads(THREAD_COUNT)
                        .setUseXNNPACK(false);
                try (Interpreter interpreter = new Interpreter(
                    loadModel(context, model), options)) {
                    assertEquals(1, interpreter.getInputTensorCount());
                    assertEquals(1, interpreter.getOutputTensorCount());
                    ByteBuffer input = createDeterministicInput(interpreter.getInputTensor(0));
                    ByteBuffer output = ByteBuffer.allocateDirect(
                            interpreter.getOutputTensor(0).numBytes())
                            .order(ByteOrder.nativeOrder());
                    input.rewind();
                    output.rewind();
                    long startNs = android.os.SystemClock.elapsedRealtimeNanos();
                    interpreter.run(input, output);
                    double elapsedMs = (android.os.SystemClock.elapsedRealtimeNanos() - startNs)
                            / 1_000_000.0;
                    Log.i(TAG, String.format(Locale.US,
                            "SMOKE_OK split=%s model=%s refined=%s inference_ms=%.3f",
                            model.datasetSplit, model.modelName, model.refined, elapsedMs));
                }
            }
        } finally {
            if (wakeLock.isHeld()) wakeLock.release();
        }
    }

    private static ModelResult runSession(Context context, ModelSpec model, int repetition,
                                          BufferedWriter raw, File inventoryFile)
            throws Exception {
        Interpreter.Options options = new Interpreter.Options()
                .setNumThreads(THREAD_COUNT)
                .setUseXNNPACK(false);
        List<Double> measurementsMs = new ArrayList<>(MEASURED_SAMPLES);

        try (Interpreter interpreter = new Interpreter(loadModel(context, model), options)) {
            Log.i(TAG, "INTERPRETER_READY asset=" + model.assetFile);
            assertEquals("The benchmark expects one input tensor for " + model.assetFile,
                    1, interpreter.getInputTensorCount());
            assertEquals("The benchmark expects one output tensor for " + model.assetFile,
                    1, interpreter.getOutputTensorCount());

            Tensor inputTensor = interpreter.getInputTensor(0);
            Tensor outputTensor = interpreter.getOutputTensor(0);
            appendInventory(context, inventoryFile, model, inputTensor, outputTensor);
            Log.i(TAG, "INVENTORY_READY asset=" + model.assetFile);
            ByteBuffer input = createDeterministicInput(inputTensor);
            ByteBuffer output = ByteBuffer.allocateDirect(outputTensor.numBytes())
                    .order(ByteOrder.nativeOrder());

            for (int sample = 0; sample < WARMUP_SAMPLES; sample++) {
                input.rewind();
                output.rewind();
                interpreter.run(input, output);
                Log.i(TAG, "WARMUP_PROGRESS asset=" + model.assetFile
                        + " completed=" + (sample + 1));
                Thread.sleep(INTER_SAMPLE_PAUSE_MS);
            }
            Log.i(TAG, String.format(Locale.US,
                    "WARMUP_COMPLETE split=%s model=%s refined=%s repetition=%d",
                    model.datasetSplit, model.modelName, model.refined, repetition));

            BatterySnapshot battery = batterySnapshot(context);
            int thermalStatus = thermalStatus(context);
            for (int sample = 1; sample <= MEASURED_SAMPLES; sample++) {
                input.rewind();
                output.rewind();
                long startNs = android.os.SystemClock.elapsedRealtimeNanos();
                interpreter.run(input, output);
                long durationNs = android.os.SystemClock.elapsedRealtimeNanos() - startNs;
                double durationMs = durationNs / 1_000_000.0;
                measurementsMs.add(durationMs);

                raw.write(String.format(Locale.US,
                        "%s,%s,%s,%s,%s,%d,%d,%d,%.6f,%d,%.1f,%d\n",
                        utcTimestamp(), model.datasetSplit, model.modelName, model.refined,
                        model.assetFile, repetition, sample, durationNs, durationMs,
                        battery.percent, battery.temperatureC, thermalStatus));
                if (sample % 10 == 0) raw.flush();
                Thread.sleep(INTER_SAMPLE_PAUSE_MS);
            }
            raw.flush();
        }

        return new ModelResult(model, repetition, Statistics.from(measurementsMs));
    }

    private static ModelSpec[] selectedModelsFromRunnerArguments() {
        String requestedAsset = InstrumentationRegistry.getArguments()
                .getString("model_asset", "").trim();
        if (requestedAsset.isEmpty()) return MODELS.clone();
        for (ModelSpec model : MODELS) {
            if (model.assetFile.equals(requestedAsset)) return new ModelSpec[]{model};
        }
        throw new IllegalArgumentException("Unknown model_asset argument: " + requestedAsset);
    }

    private static int repetitionLabelFromRunnerArguments() {
        String value = InstrumentationRegistry.getArguments()
                .getString("repetition_label", "1").trim();
        int repetition = Integer.parseInt(value);
        if (repetition < 1) {
            throw new IllegalArgumentException("repetition_label must be at least 1");
        }
        return repetition;
    }

    private static ModelSpec[] orderedModelsForRepetition(int repetition,
                                                           ModelSpec[] selectedModels) {
        ModelSpec[] ordered = selectedModels.clone();
        if (repetition == 2) {
            for (int left = 0, right = ordered.length - 1; left < right; left++, right--) {
                ModelSpec temporary = ordered[left];
                ordered[left] = ordered[right];
                ordered[right] = temporary;
            }
        } else if (repetition == 3) {
            ModelSpec[] rotated = new ModelSpec[ordered.length];
            int offset = 4;
            for (int index = 0; index < ordered.length; index++) {
                rotated[index] = ordered[(index + offset) % ordered.length];
            }
            ordered = rotated;
        }
        return ordered;
    }

    private static ByteBuffer createDeterministicInput(Tensor tensor) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(tensor.numBytes())
                .order(ByteOrder.nativeOrder());
        DataType type = tensor.dataType();
        int elementCount = tensor.numElements();
        if (type == DataType.FLOAT32) {
            for (int index = 0; index < elementCount; index++) {
                buffer.putFloat((index % 256) / 255.0f);
            }
        } else if (type == DataType.UINT8) {
            for (int index = 0; index < elementCount; index++) {
                buffer.put((byte) (index % 256));
            }
        } else if (type == DataType.INT8) {
            for (int index = 0; index < elementCount; index++) {
                buffer.put((byte) ((index % 256) - 128));
            }
        } else {
            throw new IllegalArgumentException("Unsupported input type " + type
                    + " for shape " + Arrays.toString(tensor.shape()));
        }
        buffer.rewind();
        return buffer;
    }

    private static MappedByteBuffer loadModel(Context context, ModelSpec model) throws Exception {
        if (model.externalModelPath != null) {
            try (FileInputStream stream = new FileInputStream(model.externalModelPath);
                 FileChannel channel = stream.getChannel()) {
                return channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size());
            }
        }
        String assetFile = model.assetFile;
        try (AssetFileDescriptor descriptor = context.getAssets().openFd(assetFile);
             FileInputStream stream = new FileInputStream(descriptor.getFileDescriptor());
             FileChannel channel = stream.getChannel()) {
            return channel.map(FileChannel.MapMode.READ_ONLY,
                    descriptor.getStartOffset(), descriptor.getDeclaredLength());
        }
    }

    private static void writeInventoryHeader(File file) throws Exception {
        try (BufferedWriter output = writer(file)) {
            output.write("dataset_split,model,refined,asset_file,size_bytes,size_decimal_mb,sha256,input_shape,input_type,output_shape,output_type,threads\n");
        }
    }

    private static void appendInventory(Context context, File file, ModelSpec model,
                                        Tensor input, Tensor outputTensor) throws Exception {
        long size;
        String sha256;
        if (model.externalModelPath != null) {
            File externalModel = new File(model.externalModelPath);
            size = externalModel.length();
            sha256 = sha256OfFile(externalModel);
        } else {
            try (AssetFileDescriptor descriptor = context.getAssets().openFd(model.assetFile)) {
                size = descriptor.getDeclaredLength();
            }
            sha256 = "see_host_model_checksums";
        }
        try (BufferedWriter output = new BufferedWriter(new FileWriter(file, true))) {
            output.write(String.format(Locale.US,
                    "%s,%s,%s,%s,%d,%.6f,%s,%s,%s,%s,%s,%d\n",
                    model.datasetSplit, model.modelName, model.refined, model.assetFile,
                    size, size / 1_000_000.0, sha256,
                    csv(Arrays.toString(input.shape())), input.dataType(),
                    csv(Arrays.toString(outputTensor.shape())), outputTensor.dataType(),
                    THREAD_COUNT));
        }
    }

    private static void writeSummaryByRun(File file, List<ModelResult> results) throws Exception {
        try (BufferedWriter output = writer(file)) {
            output.write("dataset_split,model,refined,asset_file,repetition,n,mean_ms,standard_deviation_ms,median_ms,p95_ms,min_ms,max_ms,derived_fps\n");
            for (ModelResult result : results) {
                writeSummaryRow(output, result.model, result.repetition, result.statistics);
            }
        }
    }

    private static void writeOverallSummary(File file, List<ModelResult> results,
                                            ModelSpec[] selectedModels) throws Exception {
        try (BufferedWriter output = writer(file)) {
            output.write("dataset_split,model,refined,asset_file,repetition,n,mean_ms,standard_deviation_ms,median_ms,p95_ms,min_ms,max_ms,derived_fps\n");
            for (ModelSpec model : selectedModels) {
                List<Double> combined = new ArrayList<>(
                        MEASURED_SAMPLES * REPETITIONS_PER_PROCESS);
                for (ModelResult result : results) {
                    if (result.model.assetFile.equals(model.assetFile)) {
                        combined.addAll(result.statistics.rawValuesMs);
                    }
                }
                writeSummaryRow(output, model, 0, Statistics.from(combined));
            }
        }
    }

    private static void writeSummaryRow(BufferedWriter output, ModelSpec model, int repetition,
                                        Statistics statistics) throws Exception {
        output.write(String.format(Locale.US,
                "%s,%s,%s,%s,%d,%d,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f\n",
                model.datasetSplit, model.modelName, model.refined, model.assetFile,
                repetition, statistics.count, statistics.meanMs, statistics.standardDeviationMs,
                statistics.medianMs, statistics.p95Ms, statistics.minimumMs,
                statistics.maximumMs, statistics.derivedFps));
    }

    private static void writeDeviceInformation(Context context, File file) throws Exception {
        ActivityManager activityManager = (ActivityManager)
                context.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
        activityManager.getMemoryInfo(memoryInfo);
        BatterySnapshot battery = batterySnapshot(context);

        String appVersion = context.getPackageManager()
                .getPackageInfo(context.getPackageName(), 0).versionName;
        try (BufferedWriter output = writer(file)) {
            output.write("Captured UTC: " + utcTimestamp() + "\n");
            output.write("Manufacturer: " + Build.MANUFACTURER + "\n");
            output.write("Brand: " + Build.BRAND + "\n");
            output.write("Model: " + Build.MODEL + "\n");
            output.write("Device: " + Build.DEVICE + "\n");
            output.write("Android release: " + Build.VERSION.RELEASE + "\n");
            output.write("Android SDK: " + Build.VERSION.SDK_INT + "\n");
            output.write("Supported ABIs: " + Arrays.toString(Build.SUPPORTED_ABIS) + "\n");
            output.write("Total memory bytes: " + memoryInfo.totalMem + "\n");
            output.write("Available memory bytes at capture: " + memoryInfo.availMem + "\n");
            output.write("Battery percent at capture: " + battery.percent + "\n");
            output.write(String.format(Locale.US, "Battery temperature C at capture: %.1f\n",
                    battery.temperatureC));
            output.write("Thermal status at capture: " + thermalStatus(context) + "\n");
            output.write("Application id: " + context.getPackageName() + "\n");
            output.write("Application version: " + appVersion + "\n");
            output.write("TensorFlow Lite dependency: 2.17.0\n");
            output.write("TFLite CPU threads: " + THREAD_COUNT + "\n");
            output.write("XNNPACK enabled: false\n");
        }
    }

    private static void writeProtocol(File file) throws Exception {
        try (BufferedWriter output = writer(file)) {
            output.write("VocaSee Android Deployment Benchmark Protocol\n\n");
            output.write("Purpose: Compare the deployed TFLite artifacts using model-only inference latency, derived throughput, and deployed file size.\n");
            output.write("Execution: Physical Android device; TensorFlow Lite Interpreter; synchronous single-input/single-output inference; batch size 1.\n");
            output.write("Threads: " + THREAD_COUNT + " CPU threads for every model.\n");
            output.write("CPU backend: TensorFlow Lite standard CPU kernels with XNNPACK disabled because repeated XNNPACK inference deadlocked on the test device.\n");
            output.write("Warm-up: " + WARMUP_SAMPLES + " unrecorded inferences before every measured session.\n");
            output.write("Measurement: " + MEASURED_SAMPLES + " timed inferences per repetition.\n");
            output.write("Repetitions: One labeled repetition per isolated Android test process; the host runs three processes per model.\n");
            output.write("Cooldown: " + COOLDOWN_BETWEEN_SESSIONS_MS / 1000
                    + " seconds between measured sessions.\n");
            output.write("Inter-sample scheduling pause: " + INTER_SAMPLE_PAUSE_MS
                    + " ms outside the timed Interpreter.run() boundary, matching the production app's inference interval.\n");
            output.write("Input: Deterministic, identical tensor values appropriate to each model's declared input data type.\n");
            output.write("Timing boundary: Immediately before and after Interpreter.run(), using SystemClock.elapsedRealtimeNanos().\n");
            output.write("FPS definition: Derived inference FPS = 1000 / mean inference latency in milliseconds. It is not camera-pipeline FPS.\n");
            output.write("Model size definition: Declared Android asset length in bytes and decimal megabytes (bytes / 1,000,000).\n");
            output.write("Excluded overhead: Camera acquisition, bitmap preprocessing, output decoding, NMS, OCR, hand tracking, UI, speech, sound, and vibration.\n");
            output.write("Order control: Model order is forward in repetition 1, reversed in repetition 2, and rotated in repetition 3 to reduce order bias.\n");
        }
    }

    private static void writeChecksums(File directory) throws Exception {
        File checksumFile = new File(directory, "07_result_file_checksums_sha256.txt");
        File[] files = directory.listFiles(file -> file.isFile()
                && !file.getName().equals(checksumFile.getName()));
        if (files == null) return;
        Arrays.sort(files, (first, second) -> first.getName().compareTo(second.getName()));
        try (BufferedWriter output = writer(checksumFile)) {
            for (File file : files) {
                output.write(sha256OfFile(file) + "  " + file.getName() + "\n");
            }
        }
    }

    private static BatterySnapshot batterySnapshot(Context context) {
        BatteryManager manager = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
        int percent = manager == null ? -1
                : manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        Intent status = context.registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        int rawTemperature = status == null ? -1
                : status.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1);
        return new BatterySnapshot(percent,
                rawTemperature < 0 ? Double.NaN : rawTemperature / 10.0);
    }

    private static int thermalStatus(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return -1;
        PowerManager manager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return manager == null ? -1 : manager.getCurrentThermalStatus();
    }

    private static String sha256OfAsset(Context context, String assetFile) throws Exception {
        try (InputStream input = new BufferedInputStream(context.getAssets().open(assetFile))) {
            return sha256(input);
        }
    }

    private static String sha256OfFile(File file) throws Exception {
        try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
            return sha256(input);
        }
    }

    private static String sha256(InputStream input) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            digest.update(buffer, 0, read);
        }
        StringBuilder hexadecimal = new StringBuilder();
        for (byte value : digest.digest()) {
            hexadecimal.append(String.format(Locale.US, "%02x", value & 0xff));
        }
        return hexadecimal.toString();
    }

    private static BufferedWriter writer(File file) throws Exception {
        return new BufferedWriter(new FileWriter(file, false));
    }

    private static String csv(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private static String utcTimestamp() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date());
    }

    private static String utcTimestampForFile() {
        SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd_HHmmss'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date());
    }

    private static final class ModelSpec {
        final String datasetSplit;
        final String modelName;
        final boolean refined;
        final String assetFile;
        final String externalModelPath;

        ModelSpec(String datasetSplit, String modelName, boolean refined, String assetFile) {
            this(datasetSplit, modelName, refined, assetFile, null);
        }

        ModelSpec(String datasetSplit, String modelName, boolean refined, String assetFile,
                  String externalModelPath) {
            this.datasetSplit = datasetSplit;
            this.modelName = modelName;
            this.refined = refined;
            this.assetFile = assetFile;
            this.externalModelPath = externalModelPath;
        }
    }

    private static final class ModelResult {
        final ModelSpec model;
        final int repetition;
        final Statistics statistics;

        ModelResult(ModelSpec model, int repetition, Statistics statistics) {
            this.model = model;
            this.repetition = repetition;
            this.statistics = statistics;
        }
    }

    private static final class BatterySnapshot {
        final int percent;
        final double temperatureC;

        BatterySnapshot(int percent, double temperatureC) {
            this.percent = percent;
            this.temperatureC = temperatureC;
        }
    }

    private static final class Statistics {
        final List<Double> rawValuesMs;
        final int count;
        final double meanMs;
        final double standardDeviationMs;
        final double medianMs;
        final double p95Ms;
        final double minimumMs;
        final double maximumMs;
        final double derivedFps;

        private Statistics(List<Double> rawValuesMs, double meanMs,
                           double standardDeviationMs, double medianMs, double p95Ms,
                           double minimumMs, double maximumMs) {
            this.rawValuesMs = rawValuesMs;
            this.count = rawValuesMs.size();
            this.meanMs = meanMs;
            this.standardDeviationMs = standardDeviationMs;
            this.medianMs = medianMs;
            this.p95Ms = p95Ms;
            this.minimumMs = minimumMs;
            this.maximumMs = maximumMs;
            this.derivedFps = meanMs == 0.0 ? Double.NaN : 1000.0 / meanMs;
        }

        static Statistics from(List<Double> values) {
            if (values.isEmpty()) {
                throw new IllegalArgumentException("No measurements were supplied");
            }
            List<Double> copied = new ArrayList<>(values);
            List<Double> sorted = new ArrayList<>(values);
            Collections.sort(sorted);

            double sum = 0.0;
            for (double value : values) sum += value;
            double mean = sum / values.size();

            double squaredDifferences = 0.0;
            for (double value : values) {
                double difference = value - mean;
                squaredDifferences += difference * difference;
            }
            double standardDeviation = values.size() < 2 ? 0.0
                    : Math.sqrt(squaredDifferences / (values.size() - 1));
            return new Statistics(copied, mean, standardDeviation,
                    percentileInclusive(sorted, 0.50), percentileInclusive(sorted, 0.95),
                    sorted.get(0), sorted.get(sorted.size() - 1));
        }

        private static double percentileInclusive(List<Double> sorted, double percentile) {
            if (sorted.size() == 1) return sorted.get(0);
            double rank = percentile * (sorted.size() - 1);
            int lower = (int) Math.floor(rank);
            int upper = (int) Math.ceil(rank);
            if (lower == upper) return sorted.get(lower);
            double fraction = rank - lower;
            return sorted.get(lower)
                    + fraction * (sorted.get(upper) - sorted.get(lower));
        }
    }
}
