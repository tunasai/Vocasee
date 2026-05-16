package com.vocasee.app;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.util.Size;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.ScaleAnimation;
import android.widget.TextView;
import android.Manifest;
import android.content.pm.PackageManager;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
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

    private static final long NOT_YET_VISIBLE_INTERVAL_MS = 5000;
    private static final long INFERENCE_INTERVAL_MS       = 300;
    private static final int  MODEL_INPUT_SIZE            = 640;

    // Delay (ms) between stopping TTS and starting the mic.
    // Prevents TTS audio from bleeding into the recognizer and causing ERROR_AUDIO (5).
    private static final long MIC_START_DELAY_MS = 600;

    private static final List<String> PREDEFINED_OBJECTS = Arrays.asList(
            "keys", "eyeglasses", "headphones", "wallet", "mobile phone",
            "remote control", "book", "water bottle", "cup", "backpack",
            "charger", "body spray", "blank cards", "pills", "glasses case",
            "watch", "comb", "shoes", "flashlight", "nail clippers"
    );

    private static final String[][] ALIASES = {
            { "eyeglasses case", "glasses case"  },
            { "glasses case",    "glasses case"  },
            { "sunglasses case", "glasses case"  },
            { "sunglass case",   "glasses case"  },
            { "nail clipper",    "nail clippers" },
            { "nail cutter",     "nail clippers" },
            { "mobile phone",    "mobile phone"  },
            { "water bottle",    "water bottle"  },
            { "body spray",      "body spray"    },
            { "headphones",      "headphones"    },
            { "headphone",       "headphones"    },
            { "cellphone",       "mobile phone"  },
            { "cell phone",      "mobile phone"  },
            { "smartphone",      "mobile phone"  },
            { "android phone",   "mobile phone"  },
            { "medicine pills",  "pills"         },
            { "charger cable",   "charger"       },
            { "charging cable",  "charger"       },
            { "usb charger",     "charger"       },
            { "remote control",  "remote control"},
            { "tv remote",       "remote control"},
            { "coin purse",      "wallet"        },
            { "school bag",      "backpack"      },
            { "coffee cup",      "cup"           },
            { "blank card",      "blank cards"   },
            { "blank cards",     "blank cards"   },
            { "wrist watch",     "watch"         },
            { "hair comb",       "comb"          },
            { "flash light",     "flashlight"    },
            { "spectacles",      "eyeglasses"    },
            { "salamin",         "eyeglasses"    },
            { "glasses",         "eyeglasses"    },
            { "specs",           "eyeglasses"    },
            { "phone",           "mobile phone"  },
            { "cp",              "mobile phone"  },
            { "iphone",          "mobile phone"  },
            { "remote",          "remote control"},
            { "controller",      "remote control"},
            { "susi",            "keys"          },
            { "key",             "keys"          },
            { "car keys",        "keys"          },
            { "house keys",      "keys"          },
            { "torch",           "flashlight"    },
            { "lamp",            "flashlight"    },
            { "gamot",           "pills"         },
            { "medicine",        "pills"         },
            { "tablet",          "pills"         },
            { "medication",      "pills"         },
            { "capsule",         "pills"         },
            { "vitamins",        "pills"         },
            { "sapatos",         "shoes"         },
            { "shoe",            "shoes"         },
            { "wallets",         "wallet"        },
            { "pitaka",          "wallet"        },
            { "coin purse",      "wallet"        },
            { "bag",             "backpack"      },
            { "school bag",      "backpack"      },
            { "bottle",          "water bottle"  },
            { "tumbler",         "water bottle"  },
            { "flask",           "water bottle"  },
            { "mug",             "cup"           },
            { "glass",           "cup"           },
            { "books",           "book"          },
            { "textbook",        "book"          },
            { "perfume",         "body spray"    },
            { "deodorant",       "body spray"    },
            { "spray",           "body spray"    },
            { "card",            "blank cards"   },
            { "cards",           "blank cards"   },
    };

    private enum DetectionState { IDLE, SCANNING, FOUND, NOT_IN_CLASSES }

    private ActivityFinderBinding binding;
    private TextToSpeech          textToSpeech;
    private SpeechRecognizer      speechRecognizer;
    private BottomSheetDialog     voiceModal;
    private ExecutorService       cameraExecutor;
    private TFLiteDetector        detector;
    private Vibrator              vibrator;

    // Handler that always runs on the main thread — used for the mic-start delay.
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private boolean        isProcessingResult = false;
    private String         targetObject  = null;
    private String         targetTFLabel = null;
    private DetectionState currentState  = DetectionState.IDLE;
    private String         lastSpokenColor = null;
    private String         lastSpokenLabel = null;

    private long lastInferenceTime    = 0;
    private long lastNotYetVisibleTts = 0;

    private int            imageProxyWidth  = 0;
    private int            imageProxyHeight = 0;

    // Letterbox padding — set by applyLetterbox(), used by mapBoxToScreen()
    private float letterboxScale   = 1f;
    private int   letterboxPadLeft = 0;
    private int   letterboxPadTop  = 0;

    // ─── LIFECYCLE ────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityFinderBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        cameraExecutor = Executors.newSingleThreadExecutor();
        vibrator       = (Vibrator) getSystemService(VIBRATOR_SERVICE);

        checkPermissions();

        // Load the model in a background thread to prevent blocking the UI thread
        cameraExecutor.execute(() -> {
            try {
                detector = new TFLiteDetector(this, "model_float32.tflite");
                Log.d(TAG, "Model loaded successfully");
            } catch (IOException e) {
                Log.e(TAG, "Failed to load TFLite model", e);
                runOnUiThread(() -> speak("Detection model failed to load."));
            }
        });

        textToSpeech = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                textToSpeech.setLanguage(Locale.US);
                speak(getString(R.string.tts_ready));
            }
        });

        startCamera();
        binding.btnMic.setOnClickListener(v -> openVoiceModal());
    }

    private void checkPermissions() {
        String[] permissions = {Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO};
        List<String> list = new ArrayList<>();
        for (String p : permissions) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                list.add(p);
            }
        }
        if (!list.isEmpty()) {
            ActivityCompat.requestPermissions(this, list.toArray(new String[0]), 101);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 101) {
            boolean cameraGranted = false;
            for (int i = 0; i < permissions.length; i++) {
                if (permissions[i].equals(Manifest.permission.CAMERA)
                        && grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                    cameraGranted = true;
                }
            }
            if (cameraGranted) {
                startCamera();
            } else {
                speak("Camera permission is required to find objects.");
            }
        }
    }

    // ─── CAMERA ───────────────────────────────────────────────────────────

    private void startCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(this);

        future.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = future.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(binding.cameraPreview.getSurfaceProvider());

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setTargetResolution(new Size(640, 480))
                        .build();

                imageAnalysis.setAnalyzer(cameraExecutor, this::analyzeFrame);

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(
                        this, CameraSelector.DEFAULT_BACK_CAMERA,
                        preview, imageAnalysis);
                Log.d(TAG, "Camera bound to lifecycle successfully");

            } catch (Exception e) {
                Log.e(TAG, "Camera start failed", e);
                speak("Camera initialization failed.");
            }
        }, ContextCompat.getMainExecutor(this));
    }

    // ─── FRAME ANALYSIS ───────────────────────────────────────────────────

    private void analyzeFrame(ImageProxy imageProxy) {
        final DetectionState stateSnapshot  = currentState;
        final String         targetSnapshot = targetObject;
        final String         labelSnapshot  = targetTFLabel;

        imageProxyWidth  = imageProxy.getWidth();
        imageProxyHeight = imageProxy.getHeight();

        try {
            if (stateSnapshot == DetectionState.IDLE
                    || stateSnapshot == DetectionState.NOT_IN_CLASSES
                    || targetSnapshot == null
                    || labelSnapshot  == null
                    || detector       == null) {
                return;
            }

            long now = SystemClock.elapsedRealtime();
            if (now - lastInferenceTime < INFERENCE_INTERVAL_MS) return;
            lastInferenceTime = now;

            int rotationDeg = imageProxy.getImageInfo().getRotationDegrees();

            @androidx.annotation.OptIn(markerClass = androidx.camera.core.ExperimentalGetImage.class)
            Bitmap rawBitmap = imageProxy.toBitmap();

            Bitmap uprightBitmap = rotateBitmap(rawBitmap, rotationDeg);
            if (uprightBitmap != rawBitmap) rawBitmap.recycle();

            int uprightW = uprightBitmap.getWidth();
            int uprightH = uprightBitmap.getHeight();

            Bitmap modelInput = applyLetterbox(uprightBitmap, MODEL_INPUT_SIZE);

            List<TFLiteDetector.Detection> detections =
                    detector.detect(modelInput, labelSnapshot);

            if (detections.isEmpty()) {
                modelInput.recycle();
                if (stateSnapshot != DetectionState.FOUND
                        && now - lastNotYetVisibleTts >= NOT_YET_VISIBLE_INTERVAL_MS) {
                    lastNotYetVisibleTts = now;
                    runOnUiThread(() ->
                            speak(getString(R.string.tts_not_yet_visible, targetSnapshot)));
                }
                return;
            }

            TFLiteDetector.Detection best = detections.get(0);
            Rect   screenBox  = mapBoxToScreen(best.boundingBox, uprightW, uprightH);
            String colorName  = (stateSnapshot == DetectionState.FOUND)
                    ? lastSpokenColor
                    : getDominantColorName(modelInput, best.boundingBox);
            modelInput.recycle();

            final String finalColor = (colorName != null) ? colorName : "unknown";

            if (stateSnapshot != DetectionState.FOUND) {
                lastSpokenColor = finalColor;
            }

            runOnUiThread(() -> onObjectDiscovered(targetSnapshot, screenBox, finalColor));

        } finally {
            imageProxy.close();
        }
    }

    // ─── IMAGE CONVERSION ─────────────────────────────────────────────────

    private Bitmap rotateBitmap(Bitmap src, int degrees) {
        if (degrees == 0) return src;
        android.graphics.Matrix matrix = new android.graphics.Matrix();
        matrix.postRotate(degrees);
        return Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), matrix, true);
    }

    private Bitmap applyLetterbox(Bitmap src, int targetSize) {
        int srcW = src.getWidth();
        int srcH = src.getHeight();

        float scale  = Math.min((float) targetSize / srcW, (float) targetSize / srcH);
        int scaledW  = Math.round(srcW * scale);
        int scaledH  = Math.round(srcH * scale);
        int padLeft  = (targetSize - scaledW) / 2;
        int padTop   = (targetSize - scaledH) / 2;

        Bitmap output = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        canvas.drawColor(Color.rgb(114, 114, 114));

        Bitmap scaled = Bitmap.createScaledBitmap(src, scaledW, scaledH, true);
        canvas.drawBitmap(scaled, padLeft, padTop, null);
        if (scaled != src) scaled.recycle();
        src.recycle();

        letterboxScale   = scale;
        letterboxPadLeft = padLeft;
        letterboxPadTop  = padTop;

        return output;
    }

    // ─── COORDINATE MAPPING ───────────────────────────────────────────────

    private Rect mapBoxToScreen(RectF box, int uprightW, int uprightH) {
        float imgL = (box.left   - letterboxPadLeft) / letterboxScale;
        float imgT = (box.top    - letterboxPadTop)  / letterboxScale;
        float imgR = (box.right  - letterboxPadLeft) / letterboxScale;
        float imgB = (box.bottom - letterboxPadTop)  / letterboxScale;

        float normL = imgL / uprightW;
        float normT = imgT / uprightH;
        float normR = imgR / uprightW;
        float normB = imgB / uprightH;

        int screenW = binding.cameraPreview.getWidth();
        int screenH = binding.cameraPreview.getHeight();

        if (screenW == 0 || screenH == 0) {
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            screenW = dm.widthPixels;
            screenH = dm.heightPixels;
        }

        float scaleX   = (float) screenW / uprightW;
        float scaleY   = (float) screenH / uprightH;
        float scale    = Math.max(scaleX, scaleY);

        float previewW = uprightW * scale;
        float previewH = uprightH * scale;

        float offsetX  = (screenW - previewW) / 2f;
        float offsetY  = (screenH - previewH) / 2f;

        int pxL = (int) (normL * previewW + offsetX);
        int pxT = (int) (normT * previewH + offsetY);
        int pxR = (int) (normR * previewW + offsetX);
        int pxB = (int) (normB * previewH + offsetY);

        pxL = Math.max(0, Math.min(screenW, pxL));
        pxT = Math.max(0, Math.min(screenH, pxT));
        pxR = Math.max(0, Math.min(screenW, pxR));
        pxB = Math.max(0, Math.min(screenH, pxB));

        Log.d(TAG, String.format("MAPPING: Model[%.1f,%.1f] -> Screen[%d,%d] | ScreenSize: %dx%d",
                box.left, box.top, pxL, pxT, screenW, screenH));

        return new Rect(pxL, pxT, pxR, pxB);
    }

    // ─── OBJECT DISCOVERY ─────────────────────────────────────────────────

    private void onObjectDiscovered(String label, Rect screenBox, String color) {
        if (label == null || targetObject == null
                || !label.equalsIgnoreCase(targetObject)) {
            return;
        }

        if (currentState != DetectionState.FOUND) {
            setDetectionState(DetectionState.FOUND);
            updateTargetLabel(getString(R.string.target_found, label));

            lastSpokenLabel = label;
            lastSpokenColor = color;

            triggerHapticFeedback();
            speak(getString(R.string.tts_found, capitalize(label), color));

        } else {
            if (lastSpokenLabel == null || !lastSpokenLabel.equalsIgnoreCase(label)) {
                lastSpokenLabel = label;
                lastSpokenColor = color;
                speakQueued(capitalize(label) + " found. It is " + color + ".");
            } else if (lastSpokenColor == null || !lastSpokenColor.equalsIgnoreCase(color)) {
                lastSpokenColor = color;
                speakQueued("The color is " + color + ".");
            }
        }

        showBoundingBox(screenBox);
        binding.tvStatusText.setText(
                getString(R.string.status_found, capitalize(label)) + " (" + color + ")");
    }

    // ─── COLOR DETECTION ──────────────────────────────────────────────────

    private String getDominantColorName(Bitmap frame640, RectF box) {
        float shrinkX = (box.right - box.left) * 0.20f;
        float shrinkY = (box.bottom - box.top) * 0.20f;

        int left   = Math.max(0, (int) (box.left   + shrinkX));
        int top    = Math.max(0, (int) (box.top    + shrinkY));
        int right  = Math.min(frame640.getWidth(),  (int) (box.right  - shrinkX));
        int bottom = Math.min(frame640.getHeight(), (int) (box.bottom - shrinkY));

        int w = right - left;
        int h = bottom - top;
        if (w <= 0 || h <= 0) return "unknown";

        int   STEPS = 10;
        float stepX = w / (float) STEPS;
        float stepY = h / (float) STEPS;

        long totalR = 0, totalG = 0, totalB = 0;
        int  count  = 0;

        for (int row = 0; row < STEPS; row++) {
            for (int col = 0; col < STEPS; col++) {
                int px = left + (int) (col * stepX + stepX / 2);
                int py = top  + (int) (row * stepY + stepY / 2);
                px = Math.max(0, Math.min(px, frame640.getWidth()  - 1));
                py = Math.max(0, Math.min(py, frame640.getHeight() - 1));
                int pixel = frame640.getPixel(px, py);
                totalR += Color.red(pixel);
                totalG += Color.green(pixel);
                totalB += Color.blue(pixel);
                count++;
            }
        }

        int avgR = (int) (totalR / count);
        int avgG = (int) (totalG / count);
        int avgB = (int) (totalB / count);

        return mapToCommonColor(avgR, avgG, avgB);
    }

    private String mapToCommonColor(int r, int g, int b) {
        float[] hsv = new float[3];
        Color.RGBToHSV(r, g, b, hsv);
        float h = hsv[0];
        float s = hsv[1];
        float v = hsv[2];

        if (v < 0.25f) return "Black";
        if (v > 0.85f && s < 0.15f) return "White";
        if (s < 0.20f) return "Gray";
        if (h >= 10 && h <= 45 && v < 0.6f && s > 0.3f) return "Brown";

        String color;
        if      (h < 15  || h >= 345) color = "Red";
        else if (h < 35)               color = "Red-Orange";
        else if (h < 50)               color = "Orange";
        else if (h < 65)               color = "Yellow-Orange";
        else if (h < 85)               color = "Yellow";
        else if (h < 115)              color = "Yellow-Green";
        else if (h < 160)              color = "Green";
        else if (h < 190)              color = "Blue-Green";
        else if (h < 215)              color = "Sky Blue";
        else if (h < 255)              color = "Blue";
        else if (h < 290)              color = "Violet";
        else if (h < 330)              color = "Magenta";
        else                           color = "Pink";

        if (v < 0.45f) return "Dark "  + color;
        if (v > 0.75f) return "Light " + color;
        return color;
    }

    // ─── BOUNDING BOX UI ──────────────────────────────────────────────────

    private void showBoundingBox(Rect rect) {
        if (rect == null) return;
        runOnUiThread(() -> {
            int w = Math.max(10, rect.width());
            int h = Math.max(10, rect.height());

            Log.d(TAG, "showBoundingBox: left=" + rect.left + " top=" + rect.top
                    + " w=" + w + " h=" + h);

            ViewGroup.LayoutParams p = binding.detectionBox.getLayoutParams();
            p.width  = w;
            p.height = h;
            binding.detectionBox.setLayoutParams(p);

            binding.detectionBox.setTranslationX(rect.left);
            binding.detectionBox.setTranslationY(rect.top);
            binding.detectionBox.setAlpha(1.0f);
            binding.detectionBox.setVisibility(View.VISIBLE);

            binding.detectionLabel.setText(capitalize(targetObject));
            binding.detectionLabel.setTranslationX(rect.left);
            binding.detectionLabel.setTranslationY(Math.max(0, rect.top - 60));
            binding.detectionLabel.setAlpha(1.0f);
            binding.detectionLabel.setVisibility(View.VISIBLE);
        });
    }

    private void hideBoundingBox() {
        runOnUiThread(() -> {
            binding.detectionBox.setVisibility(View.GONE);
            binding.detectionLabel.setVisibility(View.GONE);
        });
    }

    // ─── VOICE MODAL ──────────────────────────────────────────────────────

    private void openVoiceModal() {
        // Guard: must be called on the main thread (SpeechRecognizer requirement).
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(this::openVoiceModal);
            return;
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                    this, new String[]{Manifest.permission.RECORD_AUDIO}, 101);
            return;
        }

        isProcessingResult = false;
        targetObject       = null;
        targetTFLabel      = null;
        lastSpokenLabel    = null;
        lastSpokenColor    = null;
        setDetectionState(DetectionState.IDLE);
        updateTargetLabel("");
        hideBoundingBox();

        // Stop TTS first, then wait MIC_START_DELAY_MS before opening the mic.
        // This prevents TTS audio from being picked up by the recognizer (ERROR_AUDIO / error 5).
        if (textToSpeech != null) textToSpeech.stop();

        View modalView = LayoutInflater.from(this)
                .inflate(R.layout.dialog_voice_modal, binding.getRoot(), false);

        TextView tvVoiceInput = modalView.findViewById(R.id.tvVoiceInput);
        View     btnCancel    = modalView.findViewById(R.id.btnVoiceCancel);

        animateWaveBars(modalView);

        voiceModal = new BottomSheetDialog(this);
        voiceModal.setContentView(modalView);
        voiceModal.show();

        btnCancel.setOnClickListener(v -> {
            mainHandler.removeCallbacksAndMessages(null); // cancel pending mic start
            stopListening();
            voiceModal.dismiss();
            updateMicState(false);
        });

        voiceModal.setOnDismissListener(d -> {
            mainHandler.removeCallbacksAndMessages(null);
            stopListening();
            updateMicState(false);
        });

        updateMicState(true);

        // Delay mic start so TTS audio doesn't bleed into the recognizer.
        mainHandler.postDelayed(() -> startListening(tvVoiceInput), MIC_START_DELAY_MS);
    }

    // ─── SPEECH RECOGNITION ───────────────────────────────────────────────

    /**
     * Creates and starts a SpeechRecognizer.
     *
     * IMPORTANT: SpeechRecognizer MUST be created and used on the main (UI) thread.
     * This method is always called via mainHandler.postDelayed(), so that contract is met.
     */
    private void startListening(TextView tvVoiceInput) {
        // Safety check — should never be false given our mainHandler usage, but belt-and-suspenders.
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Log.e(TAG, "startListening() called off main thread — rescheduling");
            mainHandler.post(() -> startListening(tvVoiceInput));
            return;
        }

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.e(TAG, "Speech recognition NOT available on this device");
            speak("Speech recognition is not available on this device.");
            return;
        }

        // Destroy any stale instance before creating a new one.
        destroySpeechRecognizer();

        // createSpeechRecognizer() must be called on the main thread.
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);

        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US.toString());
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 600L);
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 600L);
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 800L);

        speechRecognizer.setRecognitionListener(new RecognitionListener() {

            @Override
            public void onPartialResults(Bundle partialResults) {
                ArrayList<String> partial = partialResults
                        .getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (partial != null && !partial.isEmpty()) {
                    String spoken = partial.get(0).toLowerCase().trim();
                    runOnUiThread(() -> tvVoiceInput.setText(spoken));
                    // We deliberately do NOT act on partials to avoid triggering early
                    // (e.g. "glasses" before the user finishes saying "glasses case").
                }
            }

            @Override
            public void onResults(Bundle results) {
                if (isProcessingResult) return;

                ArrayList<String> matches =
                        results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (matches != null && !matches.isEmpty()) {
                    isProcessingResult = true;
                    final String spoken = matches.get(0).toLowerCase().trim();
                    runOnUiThread(() -> {
                        tvVoiceInput.setText(spoken);
                        if (voiceModal != null && voiceModal.isShowing()) {
                            voiceModal.dismiss();
                        }
                        handleVoiceResult(spoken);
                    });
                }
            }

            @Override
            public void onError(int error) {
                // Log the raw error code so you can diagnose issues in Logcat.
                Log.e(TAG, "SpeechRecognizer error code: " + error + " — " + speechErrorString(error));

                // Decide whether to auto-retry or give up.
                boolean shouldRetry = (error == SpeechRecognizer.ERROR_NO_MATCH
                        || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT);

                runOnUiThread(() -> {
                    if (shouldRetry && voiceModal != null && voiceModal.isShowing()) {
                        // Silently restart the recognizer — the user is still looking at the modal.
                        tvVoiceInput.setText(getString(R.string.voice_listening_hint));
                        // Destroy old instance before retrying (required on some devices).
                        destroySpeechRecognizer();
                        mainHandler.postDelayed(() -> startListening(tvVoiceInput), 300);
                    } else {
                        isProcessingResult = false;
                        String msg = speechErrorString(error);
                        tvVoiceInput.setText(msg);
                        speak(getString(R.string.tts_no_hear));
                        if (voiceModal != null && voiceModal.isShowing()) {
                            voiceModal.dismiss();
                        }
                    }
                });
            }

            // ── Unused callbacks (required by interface) ──────────────────
            @Override public void onReadyForSpeech(Bundle params)  {
                runOnUiThread(() -> tvVoiceInput.setText(getString(R.string.voice_listening_hint)));
            }
            @Override public void onBeginningOfSpeech()            {}
            @Override public void onRmsChanged(float rmsdB)        {}
            @Override public void onBufferReceived(byte[] buffer)   {}
            @Override public void onEndOfSpeech()                  {}
            @Override public void onEvent(int eventType, Bundle params) {}
        });

        speechRecognizer.startListening(intent);
        Log.d(TAG, "SpeechRecognizer.startListening() called");
    }

    /**
     * Returns a human-readable description of a SpeechRecognizer error code.
     * Useful both for Logcat debugging and (selectively) for user-facing messages.
     */
    private String speechErrorString(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_AUDIO:
                return "Audio recording error (ERROR_AUDIO 3) — mic may be busy";
            case SpeechRecognizer.ERROR_CLIENT:
                return "Client-side error (ERROR_CLIENT 5)";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                return "Missing RECORD_AUDIO permission (ERROR_INSUFFICIENT_PERMISSIONS 9)";
            case SpeechRecognizer.ERROR_NETWORK:
                return "Network error (ERROR_NETWORK 2) — check internet connection";
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                return "Network timeout (ERROR_NETWORK_TIMEOUT 1)";
            case SpeechRecognizer.ERROR_NO_MATCH:
                return "No speech match (ERROR_NO_MATCH 7) — will retry";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                return "Recognizer busy (ERROR_RECOGNIZER_BUSY 8)";
            case SpeechRecognizer.ERROR_SERVER:
                return "Server error (ERROR_SERVER 4)";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                return "No speech detected (ERROR_SPEECH_TIMEOUT 6) — will retry";
            case SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED:
                return "Language not supported (ERROR_LANGUAGE_NOT_SUPPORTED 12)";
            case SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE:
                return "Language unavailable offline (ERROR_LANGUAGE_UNAVAILABLE 13)";
            default:
                return "Unknown error code " + error;
        }
    }

    /**
     * Safely cancels and destroys the current SpeechRecognizer.
     * Must be called on the main thread.
     */
    private void destroySpeechRecognizer() {
        if (speechRecognizer != null) {
            try {
                speechRecognizer.cancel();
                speechRecognizer.destroy();
            } catch (Exception e) {
                Log.e(TAG, "Error destroying SpeechRecognizer", e);
            }
            speechRecognizer = null;
        }
    }

    private void stopListening() {
        // stopListening() may be called from any thread (e.g. onPause).
        // Delegate to main thread to satisfy SpeechRecognizer's threading requirement.
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(this::stopListening);
            return;
        }
        mainHandler.removeCallbacksAndMessages(null); // cancel any pending mic-start delay
        destroySpeechRecognizer();
    }

    // ─── VOICE RESULT ─────────────────────────────────────────────────────

    private void handleVoiceResult(String spoken) {
        String lower    = spoken.toLowerCase().trim();
        String resolved = resolveAlias(lower);

        String matched = null;

        // 1. Exact match
        for (String obj : PREDEFINED_OBJECTS) {
            if (resolved.equalsIgnoreCase(obj)) { matched = obj; break; }
        }

        // 2. Substring match
        if (matched == null) {
            for (String obj : PREDEFINED_OBJECTS) {
                if (resolved.contains(obj)) { matched = obj; break; }
            }
        }

        if (matched == null) {
            targetObject  = spoken;
            targetTFLabel = null;
            setDetectionState(DetectionState.NOT_IN_CLASSES);
            updateTargetLabel(getString(R.string.target_not_found, spoken));
            speak(getString(R.string.tts_not_predefined, spoken));
            return;
        }

        targetObject         = matched;
        targetTFLabel        = toTFLiteLabel(matched);
        lastNotYetVisibleTts = SystemClock.elapsedRealtime();
        setDetectionState(DetectionState.SCANNING);
        updateTargetLabel(getString(R.string.target_looking, matched));
        speak(getString(R.string.tts_scanning, matched));
    }

    private String resolveAlias(String spoken) {
        String lower = spoken.toLowerCase();
        for (String[] row : ALIASES) {
            if (lower.contains(row[0].toLowerCase())) return row[1];
        }
        return lower;
    }

    private String toTFLiteLabel(String canonicalLabel) {
        switch (canonicalLabel) {
            case "backpack":       return "backpack";
            case "book":           return "book";
            case "cup":            return "cup";
            case "eyeglasses":     return "glasses";
            case "headphones":     return "headphone";
            case "keys":           return "key";
            case "mobile phone":   return "phone";
            case "remote control": return "remote";
            case "wallet":         return "wallet";
            case "water bottle":   return "water bottle";
            case "body spray":     return "body spray";
            case "blank cards":    return "card";
            case "charger":        return "charger";
            case "comb":           return "comb";
            case "flashlight":     return "flashlight";
            case "glasses case":   return "glasses case";
            case "pills":          return "medicine";
            case "nail clippers":  return "nail clipper";
            case "shoes":          return "shoe";
            case "watch":          return "watch";
            default:               return canonicalLabel;
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
                listening ? R.drawable.mic_button_listening : R.drawable.mic_button_bg));
    }

    // ─── WAVE BARS ────────────────────────────────────────────────────────

    private void animateWaveBars(View modalView) {
        int[]   waveIds = {R.id.wave1, R.id.wave2, R.id.wave3, R.id.wave4, R.id.wave5,
                R.id.wave6, R.id.wave7, R.id.wave8, R.id.wave9, R.id.wave10};
        float[] scales  = {0.3f, 0.6f, 1.0f, 1.2f, 0.9f, 0.4f, 0.7f, 1.1f, 0.3f, 0.7f};

        for (int i = 0; i < waveIds.length; i++) {
            View bar = modalView.findViewById(waveIds[i]);
            if (bar == null) continue;
            ScaleAnimation anim = new ScaleAnimation(
                    1f, 1f, 0.2f, scales[i],
                    Animation.RELATIVE_TO_SELF, 0.5f,
                    Animation.RELATIVE_TO_SELF, 1.0f);
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

    private void speak(String text) {
        if (textToSpeech != null)
            textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, null);
    }

    private void speakQueued(String text) {
        if (textToSpeech != null)
            textToSpeech.speak(text, TextToSpeech.QUEUE_ADD, null, null);
    }

    private void triggerHapticFeedback() {
        if (vibrator == null || !vibrator.hasVibrator()) return;
        vibrator.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE));
    }

    // ─── LIFECYCLE ────────────────────────────────────────────────────────

    @Override
    protected void onDestroy() {
        if (textToSpeech != null) { textToSpeech.stop(); textToSpeech.shutdown(); }
        stopListening();
        if (detector != null) detector.close();
        if (cameraExecutor != null) cameraExecutor.shutdown();
        super.onDestroy();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopListening();
        if (voiceModal != null && voiceModal.isShowing()) voiceModal.dismiss();
    }
}