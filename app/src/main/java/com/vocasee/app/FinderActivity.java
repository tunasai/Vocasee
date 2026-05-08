package com.vocasee.app;

import android.content.Intent;
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
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.common.util.concurrent.ListenableFuture;
import com.vocasee.app.databinding.ActivityFinderBinding;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FinderActivity extends AppCompatActivity {

    private static final String TAG = "FinderActivity";

    private static final List<String> PREDEFINED_OBJECTS = Arrays.asList(
            "keys", "eyeglasses", "headphones", "wallet", "mobile phone",
            "remote control", "book", "water bottle", "cup", "backpack",
            "charger", "body spray", "blank cards", "scissors", "umbrella",
            "watch", "shoes", "hat", "bag", "pen"
    );

    private ActivityFinderBinding binding;
    private TextToSpeech textToSpeech;
    private SpeechRecognizer speechRecognizer;
    private BottomSheetDialog voiceModal;
    private ExecutorService cameraExecutor;

    // targetObject is accessed across methods — keep as field
    private String targetObject = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityFinderBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        cameraExecutor = Executors.newSingleThreadExecutor();

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

    // ─── CAMERA ───────────────────────────────

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(this);

        future.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = future.get();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(binding.cameraPreview.getSurfaceProvider());
                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(
                        this, CameraSelector.DEFAULT_BACK_CAMERA, preview);
            } catch (Exception e) {
                Log.e(TAG, "Camera start failed", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    // ─── VOICE MODAL ──────────────────────────

    private void openVoiceModal() {
        View modalView = LayoutInflater.from(this)
                .inflate(R.layout.dialog_voice_modal, null);

        TextView tvVoiceInput = modalView.findViewById(R.id.tvVoiceInput);
        View btnCancel = modalView.findViewById(R.id.btnVoiceCancel);

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

    // ─── SPEECH RECOGNITION ───────────────────

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

    // ─── VOICE RESULT → SCAN LOGIC ────────────

    private void handleVoiceResult(String spoken) {
        // Two-step: assign to temp, then copy to effectively-final for lambda
        String matchedTemp = null;
        for (String obj : PREDEFINED_OBJECTS) {
            if (spoken.contains(obj)) {
                matchedTemp = obj;
                break;
            }
        }
        final String matched = matchedTemp; // effectively final — safe for lambda

        if (matched == null) {
            targetObject = spoken;
            setDetectionState("not_found");
            updateTargetLabel(getString(R.string.target_not_found, spoken));
            speak(getString(R.string.tts_not_predefined, spoken));
            return;
        }

        targetObject = matched;
        setDetectionState("scanning");
        updateTargetLabel(getString(R.string.target_looking, matched));
        speak(getString(R.string.tts_scanning, matched));

        // Simulated detection — replace with ML Kit result later
        binding.getRoot().postDelayed(() -> simulateDetection(matched), 3000);
    }

    private void simulateDetection(String object) {
        String detectedColor = "black"; // replace with real ML Kit attribute later

        setDetectionState("found");
        updateTargetLabel(getString(R.string.target_found, object));
        showBoundingBox();

        String name = object.substring(0, 1).toUpperCase() + object.substring(1);
        speak(name + " found — " + detectedColor + " " + object
                + ". It is in the center of the frame.");
    }

    // ─── UI STATE ─────────────────────────────

    private void setDetectionState(String state) {
        runOnUiThread(() -> {
            switch (state) {
                case "idle":
                    binding.tvStatusText.setText(getString(R.string.status_idle));
                    binding.statusDot.setBackgroundResource(R.drawable.dot_white);
                    binding.scanningContainer.setVisibility(View.GONE);
                    hideBoundingBox();
                    break;

                case "scanning":
                    binding.tvStatusText.setText(
                            getString(R.string.status_scanning, targetObject));
                    binding.statusDot.setBackgroundResource(R.drawable.dot_amber);
                    binding.scanningContainer.setVisibility(View.VISIBLE);
                    hideBoundingBox();
                    break;

                case "found":
                    String name = targetObject.substring(0, 1).toUpperCase()
                            + targetObject.substring(1);
                    binding.tvStatusText.setText(
                            getString(R.string.status_found, name));
                    binding.statusDot.setBackgroundResource(R.drawable.dot_green);
                    binding.scanningContainer.setVisibility(View.GONE);
                    break;

                case "not_found":
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

    private void showBoundingBox() {
        runOnUiThread(() -> {
            binding.detectionBox.setVisibility(View.VISIBLE);
            binding.detectionLabel.setVisibility(View.VISIBLE);
            binding.detectionLabel.setText(targetObject);
            // Coordinates will be replaced by real ML Kit bounding box
            binding.detectionBox.setX(200);
            binding.detectionBox.setY(400);
        });
    }

    private void hideBoundingBox() {
        runOnUiThread(() -> {
            binding.detectionBox.setVisibility(View.GONE);
            binding.detectionLabel.setVisibility(View.GONE);
        });
    }

    // ─── WAVE BAR ANIMATION ───────────────────

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

    // ─── TTS ──────────────────────────────────

    private void speak(String text) {
        if (textToSpeech != null) {
            textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, null);
        }
    }

    // ─── LIFECYCLE ────────────────────────────

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (textToSpeech != null) {
            textToSpeech.stop();
            textToSpeech.shutdown();
        }
        stopListening();
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