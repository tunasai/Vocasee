package com.vocasee.app;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.ScaleAnimation;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.common.util.concurrent.ListenableFuture;
import com.vocasee.app.databinding.ActivityFinderBinding;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FinderActivity extends AppCompatActivity {

    private static final String TAG = "FinderActivity";

    // ── 20 predefined classes per Valipoor et al. (2024) ──────────────────
    private static final List<String> PREDEFINED_OBJECTS = Arrays.asList(
            "keys", "eyeglasses", "headphones", "wallet", "mobile phone",
            "remote control", "book", "water bottle", "cup", "backpack",
            "charger", "body spray", "blank cards", "pills", "glasses case",
            "watch", "comb", "shoes", "flashlight", "nail clippers"
    );

    // ── Spoken aliases → canonical class name ─────────────────────────────
    private static final String[][] ALIASES = {
            { "medicine",        "pills"         },
            { "tablet",          "pills"         },
            { "medication",      "pills"         },
            { "spectacles",      "eyeglasses"    },
            { "glasses",         "eyeglasses"    },
            { "specs",           "eyeglasses"    },
            { "phone",           "mobile phone"  },
            { "cellphone",       "mobile phone"  },
            { "cell phone",      "mobile phone"  },
            { "smartphone",      "mobile phone"  },
            { "remote",          "remote control"},
            { "sunglasses case", "glasses case"  },
            { "torch",           "flashlight"    },
            { "nail cutter",     "nail clippers" },
    };

    // ── Detection states (3 TTS modes per scope) ──────────────────────────
    private enum DetectionState { IDLE, SCANNING, FOUND, NOT_IN_CLASSES }

    private ActivityFinderBinding binding;
    private TextToSpeech textToSpeech;
    private SpeechRecognizer speechRecognizer;
    private BottomSheetDialog voiceModal;
    private ExecutorService cameraExecutor;

    private String targetObject = null;
    private DetectionState currentState = DetectionState.IDLE;

    // Holds the latest Bitmap converted from ImageProxy for color extraction
    private volatile Bitmap latestFrameBitmap = null;

    // 4a — TFLite detector field
    private TFLiteDetector detector;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityFinderBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        cameraExecutor = Executors.newSingleThreadExecutor();

        // 4b — Initialize TFLite model
        try {
            detector = new TFLiteDetector(this, "best_float32.tflite");
        } catch (IOException e) {
            Log.e(TAG, "Failed to load TFLite model", e);
        }

        textToSpeech = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                textToSpeech.setLanguage(Locale.US);
                speak(getString(R.string.tts_ready));
            }
        });

        startCamera();

        binding.btnMic.setOnClickListener(v -> openVoiceModal());

        binding.btnMenu.setOnClickListener(v ->
                startActivity(new Intent(FinderActivity.this, SettingsActivity.class)));

        binding.btnSettings.setOnClickListener(v ->
                startActivity(new Intent(FinderActivity.this, SettingsActivity.class)));
    }

    // ─── CAMERA ───────────────────────────────────────────────────────────

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(this);

        future.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = future.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(binding.cameraPreview.getSurfaceProvider());

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                imageAnalysis.setAnalyzer(cameraExecutor, this::analyzeFrame);

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(
                        this,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageAnalysis
                );

            } catch (Exception e) {
                Log.e(TAG, "Camera start failed", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    // ─── FRAME ANALYSIS ───────────────────────────────────────────────────

    // 4c — Full TFLite-integrated analyzeFrame (stub removed)
    private void analyzeFrame(ImageProxy imageProxy) {
        try {
            if (currentState != DetectionState.SCANNING
                    || targetObject == null
                    || detector == null) {
                return;
            }

            // Convert frame to Bitmap
            Bitmap frame = imageProxyToBitmap(imageProxy);
            latestFrameBitmap = frame;

            // Run TFLite inference
            List<TFLiteDetector.Detection> detections = detector.detect(frame);

            // Check if any detection matches the target object
            for (TFLiteDetector.Detection det : detections) {
                // Map TFLite label → canonical app label for comparison
                String canonical = toCanonicalLabel(det.label);
                if (canonical.equals(targetObject)) {
                    // Scale bounding box from 640x640 back to actual frame size
                    float scaleX = (float) imageProxy.getWidth()  / 640f;
                    float scaleY = (float) imageProxy.getHeight() / 640f;

                    Rect box = new Rect(
                            (int) (det.boundingBox.left   * scaleX),
                            (int) (det.boundingBox.top    * scaleY),
                            (int) (det.boundingBox.right  * scaleX),
                            (int) (det.boundingBox.bottom * scaleY)
                    );

                    final Bitmap frameCopy = frame;
                    final Rect finalBox    = box;
                    runOnUiThread(() -> onObjectFound(targetObject, frameCopy, finalBox));
                    return; // stop after first match
                }
            }

        } finally {
            imageProxy.close(); // always close or camera pipeline stalls
        }
    }

    /**
     * Converts an ImageProxy (YUV_420_888) from CameraX into an ARGB Bitmap
     * so we can run pixel-level color analysis on it.
     */
    private Bitmap imageProxyToBitmap(ImageProxy imageProxy) {
        ImageProxy.PlaneProxy[] planes = imageProxy.getPlanes();

        java.nio.ByteBuffer yBuffer = planes[0].getBuffer();
        java.nio.ByteBuffer uBuffer = planes[1].getBuffer();
        java.nio.ByteBuffer vBuffer = planes[2].getBuffer();

        int ySize = yBuffer.remaining();
        int uSize = uBuffer.remaining();
        int vSize = vBuffer.remaining();

        byte[] nv21 = new byte[ySize + uSize + vSize];
        yBuffer.get(nv21, 0, ySize);
        vBuffer.get(nv21, ySize, vSize);
        uBuffer.get(nv21, ySize + vSize, uSize);

        android.graphics.YuvImage yuvImage = new android.graphics.YuvImage(
                nv21,
                android.graphics.ImageFormat.NV21,
                imageProxy.getWidth(),
                imageProxy.getHeight(),
                null
        );

        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        yuvImage.compressToJpeg(
                new Rect(0, 0, imageProxy.getWidth(), imageProxy.getHeight()),
                80,
                out
        );
        byte[] jpegBytes = out.toByteArray();

        return android.graphics.BitmapFactory.decodeByteArray(
                jpegBytes, 0, jpegBytes.length);
    }

    // ─── OBJECT FOUND ─────────────────────────────────────────────────────

    private void onObjectFound(String label, Bitmap frame, Rect boundingBox) {
        String color = (frame != null && boundingBox != null)
                ? getDominantColor(frame, boundingBox)
                : "unknown color";

        setDetectionState(DetectionState.FOUND);
        updateTargetLabel(getString(R.string.target_found, label));
        showBoundingBox(boundingBox);

        // TTS mode 2 — confirmed detection with color attribute
        String name = capitalize(label);
        speak(getString(R.string.tts_found, name, color, name));
    }

    // ─── COLOR DETECTION (HSV dominant color) ─────────────────────────────

    private String getDominantColor(Bitmap frame, Rect boundingBox) {
        int left   = Math.max(0, boundingBox.left);
        int top    = Math.max(0, boundingBox.top);
        int right  = Math.min(frame.getWidth(),  boundingBox.right);
        int bottom = Math.min(frame.getHeight(), boundingBox.bottom);

        int width  = right  - left;
        int height = bottom - top;

        if (width <= 0 || height <= 0) return "unknown color";

        Bitmap cropped = Bitmap.createBitmap(frame, left, top, width, height);

        int red = 0, orange = 0, yellow = 0, green = 0, blue = 0,
                purple = 0, pink = 0, white = 0, gray = 0, black = 0;

        float[] hsv = new float[3];

        for (int x = 0; x < cropped.getWidth(); x++) {
            for (int y = 0; y < cropped.getHeight(); y++) {
                int pixel = cropped.getPixel(x, y);
                Color.colorToHSV(pixel, hsv);

                float h = hsv[0];
                float s = hsv[1];
                float v = hsv[2];

                if      (v < 0.20f)                  black++;
                else if (v > 0.80f && s < 0.15f)     white++;
                else if (s < 0.20f)                  gray++;
                else if (h < 15f || h >= 345f)       red++;
                else if (h < 40f)                    orange++;
                else if (h < 65f)                    yellow++;
                else if (h < 165f)                   green++;
                else if (h < 250f)                   blue++;
                else if (h < 290f)                   purple++;
                else                                 pink++;
            }
        }

        int[]    counts = { red, orange, yellow, green, blue, purple, pink, white, gray, black };
        String[] names  = { "red", "orange", "yellow", "green", "blue", "purple", "pink", "white", "gray", "black" };

        int maxIdx = 0;
        for (int i = 1; i < counts.length; i++) {
            if (counts[i] > counts[maxIdx]) maxIdx = i;
        }

        cropped.recycle();
        return names[maxIdx];
    }

    // ─── VOICE MODAL ──────────────────────────────────────────────────────

    private void openVoiceModal() {
        View modalView = LayoutInflater.from(this)
                .inflate(R.layout.dialog_voice_modal, null);

        TextView tvVoiceInput = modalView.findViewById(R.id.tvVoiceInput);
        View btnCancel        = modalView.findViewById(R.id.btnVoiceCancel);

        animateWaveBars(modalView);

        voiceModal = new BottomSheetDialog(this);
        voiceModal.setContentView(modalView);
        voiceModal.show();

        btnCancel.setOnClickListener(v -> {
            stopListening();
            voiceModal.dismiss();
            updateMicState(false);
        });

        voiceModal.setOnDismissListener(d -> {
            stopListening();
            updateMicState(false);
        });

        updateMicState(true);
        startListening(tvVoiceInput);
    }

    // ─── SPEECH RECOGNITION ───────────────────────────────────────────────

    private void startListening(TextView tvVoiceInput) {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);

        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);

        speechRecognizer.setRecognitionListener(new RecognitionListener() {

            @Override
            public void onPartialResults(Bundle partialResults) {
                ArrayList<String> partial = partialResults
                        .getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (partial != null && !partial.isEmpty()) {
                    runOnUiThread(() -> tvVoiceInput.setText(partial.get(0)));
                }
            }

            @Override
            public void onResults(Bundle results) {
                ArrayList<String> matches = results
                        .getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (matches != null && !matches.isEmpty()) {
                    final String spoken = matches.get(0).toLowerCase().trim();
                    runOnUiThread(() -> {
                        tvVoiceInput.setText(spoken);
                        if (voiceModal != null) voiceModal.dismiss();
                        handleVoiceResult(spoken);
                    });
                }
            }

            @Override
            public void onError(int error) {
                runOnUiThread(() -> {
                    tvVoiceInput.setText(getString(R.string.voice_no_hear));
                    speak(getString(R.string.tts_no_hear));
                });
            }

            @Override public void onReadyForSpeech(Bundle p) {}
            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float v) {}
            @Override public void onBufferReceived(byte[] b) {}
            @Override public void onEndOfSpeech() {}
            @Override public void onEvent(int t, Bundle b) {}
        });

        speechRecognizer.startListening(intent);
    }

    private void stopListening() {
        if (speechRecognizer != null) {
            speechRecognizer.stopListening();
            speechRecognizer.destroy();
            speechRecognizer = null;
        }
    }

    // ─── VOICE RESULT → SCAN LOGIC ────────────────────────────────────────

    private void handleVoiceResult(String spoken) {
        String resolved = resolveAlias(spoken);

        String matchedTemp = null;
        for (String obj : PREDEFINED_OBJECTS) {
            if (resolved.contains(obj)) {
                matchedTemp = obj;
                break;
            }
        }
        final String matched = matchedTemp;

        if (matched == null) {
            // TTS mode 3 — not in predefined classes
            targetObject = spoken;
            setDetectionState(DetectionState.NOT_IN_CLASSES);
            updateTargetLabel(getString(R.string.target_not_found, spoken));
            speak(getString(R.string.tts_not_predefined, spoken));
            return;
        }

        // TTS mode 1 — scanning, not yet visible
        targetObject = matched;
        setDetectionState(DetectionState.SCANNING);
        updateTargetLabel(getString(R.string.target_looking, matched));
        speak(getString(R.string.tts_scanning, matched));
    }

    private String resolveAlias(String spoken) {
        for (String[] alias : ALIASES) {
            if (spoken.contains(alias[0])) {
                return spoken.replace(alias[0], alias[1]);
            }
        }
        return spoken;
    }

    // 4d — TFLite label → canonical app label
    private String toCanonicalLabel(String tfliteLabel) {
        switch (tfliteLabel) {
            case "glasses":  return "eyeglasses";
            case "headphone": return "headphones";
            case "key":      return "keys";
            case "phone":    return "mobile phone";
            case "remote":   return "remote control";
            default:         return tfliteLabel;
        }
    }

    // ─── UI STATE ─────────────────────────────────────────────────────────

    private void setDetectionState(DetectionState state) {
        currentState = state;
        runOnUiThread(() -> {
            switch (state) {
                case IDLE:
                    binding.tvStatusText.setText(getString(R.string.status_idle));
                    binding.statusDot.setBackgroundResource(R.drawable.dot_white);
                    binding.scanningContainer.setVisibility(View.GONE);
                    hideBoundingBox();
                    break;

                case SCANNING:
                    binding.tvStatusText.setText(
                            getString(R.string.status_scanning, targetObject));
                    binding.statusDot.setBackgroundResource(R.drawable.dot_amber);
                    binding.scanningContainer.setVisibility(View.VISIBLE);
                    hideBoundingBox();
                    break;

                case FOUND:
                    binding.tvStatusText.setText(
                            getString(R.string.status_found, capitalize(targetObject)));
                    binding.statusDot.setBackgroundResource(R.drawable.dot_green);
                    binding.scanningContainer.setVisibility(View.GONE);
                    break;

                case NOT_IN_CLASSES:
                    binding.tvStatusText.setText(
                            getString(R.string.status_not_in_classes, targetObject));
                    binding.statusDot.setBackgroundResource(R.drawable.dot_red);
                    binding.scanningContainer.setVisibility(View.GONE);
                    hideBoundingBox();
                    break;
            }
        });
    }

    private void updateTargetLabel(String text) {
        runOnUiThread(() -> binding.tvTargetLabel.setText(text));
    }

    private void updateMicState(boolean listening) {
        runOnUiThread(() -> binding.btnMic.setBackgroundResource(
                listening
                        ? R.drawable.mic_button_listening
                        : R.drawable.mic_button_bg));
    }

    private void showBoundingBox(Rect rect) {
        runOnUiThread(() -> {
            binding.detectionBox.setVisibility(View.VISIBLE);
            binding.detectionLabel.setVisibility(View.VISIBLE);
            binding.detectionLabel.setText(targetObject);

            if (rect != null) {
                binding.detectionBox.setX(rect.left);
                binding.detectionBox.setY(rect.top);
                binding.detectionBox.getLayoutParams().width  = rect.width();
                binding.detectionBox.getLayoutParams().height = rect.height();
                binding.detectionBox.requestLayout();
            } else {
                binding.detectionBox.setX(200);
                binding.detectionBox.setY(400);
            }
        });
    }

    private void hideBoundingBox() {
        runOnUiThread(() -> {
            binding.detectionBox.setVisibility(View.GONE);
            binding.detectionLabel.setVisibility(View.GONE);
        });
    }

    // ─── WAVE BAR ANIMATION ───────────────────────────────────────────────

    private void animateWaveBars(View modalView) {
        int[] waveIds = {
                R.id.wave1, R.id.wave2, R.id.wave3, R.id.wave4, R.id.wave5,
                R.id.wave6, R.id.wave7, R.id.wave8, R.id.wave9, R.id.wave10
        };
        float[] scales = { 0.3f, 0.6f, 1.0f, 1.2f, 0.9f, 0.4f, 0.7f, 1.1f, 0.3f, 0.7f };

        for (int i = 0; i < waveIds.length; i++) {
            View bar = modalView.findViewById(waveIds[i]);
            if (bar == null) continue;

            ScaleAnimation anim = new ScaleAnimation(
                    1f, 1f,
                    0.2f, scales[i],
                    Animation.RELATIVE_TO_SELF, 0.5f,
                    Animation.RELATIVE_TO_SELF, 1.0f
            );
            anim.setDuration(600 + (i * 80L));
            anim.setRepeatCount(Animation.INFINITE);
            anim.setRepeatMode(Animation.REVERSE);
            anim.setStartOffset(i * 80L);
            bar.startAnimation(anim);
        }
    }

    // ─── HELPERS ──────────────────────────────────────────────────────────

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.substring(0, 1).toUpperCase() + s.substring(1);
    }

    // ─── TTS ──────────────────────────────────────────────────────────────

    private void speak(String text) {
        if (textToSpeech != null) {
            textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, null);
        }
    }

    // ─── LIFECYCLE ────────────────────────────────────────────────────────

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (textToSpeech != null) {
            textToSpeech.stop();
            textToSpeech.shutdown();
        }
        stopListening();
        if (detector != null) detector.close(); // 4e — release TFLite interpreter
        if (latestFrameBitmap != null) {
            latestFrameBitmap.recycle();
            latestFrameBitmap = null;
        }
        cameraExecutor.shutdown();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopListening();
        if (voiceModal != null && voiceModal.isShowing()) {
            voiceModal.dismiss();
        }
    }
}