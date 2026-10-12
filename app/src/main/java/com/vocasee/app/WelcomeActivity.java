package com.vocasee.app;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.view.View;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.vocasee.app.databinding.ActivityWelcomeBinding;

import java.util.List;
import java.util.Locale;

public class WelcomeActivity extends BaseActivity {

    private static final int PERMISSION_REQUEST_CODE = 100;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private ActivityWelcomeBinding binding;
    private TextToSpeech textToSpeech;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityWelcomeBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        View.OnClickListener startListener = v -> checkPermissionsAndProceed();
        binding.main.setOnClickListener(startListener);
        binding.welcomeScroll.setOnClickListener(startListener);
        binding.tvTapToStart.setOnClickListener(startListener);

        showWelcomeHub();

        textToSpeech = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                textToSpeech.setLanguage(Locale.US);
                textToSpeech.setSpeechRate(AppPreferences.getSpeechRate(this));
                speak(getString(R.string.tts_welcome_hub));
            }
        });
    }

    private void showWelcomeHub() {
        handler.removeCallbacksAndMessages(null);
        binding.tvStepLabel.setVisibility(View.GONE);
        binding.stepDots.setVisibility(View.GONE);
        binding.tutorialProgress.setVisibility(View.GONE);
        binding.featureBox.setVisibility(View.VISIBLE);
        binding.objectListBox.setVisibility(View.VISIBLE);
        binding.tvTutorialTitle.setText(getString(R.string.welcome_title_plain));
        binding.tvTutorialDescription.setText(getString(R.string.welcome_description));
        List<String> recent = AppPreferences.getRecentSearches(this);
        if (AppPreferences.isRecentReminderEnabled(this) && !recent.isEmpty()) {
            binding.recentWelcomeBox.setVisibility(View.VISIBLE);
            binding.tvWelcomeRecent.setText(buildRecentSummary(recent));
        } else {
            binding.recentWelcomeBox.setVisibility(View.GONE);
        }
        binding.tvTapToStart.setVisibility(View.VISIBLE);
    }

    private String buildRecentSummary(List<String> recent) {
        int count = Math.min(3, recent.size());
        StringBuilder summary = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) summary.append(i == count - 1 ? " and " : ", ");
            summary.append(recent.get(i));
        }
        return summary.toString();
    }

    private void checkPermissionsAndProceed() {
        boolean audioGranted = ContextCompat.checkSelfPermission(this,
                Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        boolean cameraGranted = ContextCompat.checkSelfPermission(this,
                Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;

        if (!audioGranted || !cameraGranted) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA},
                    PERMISSION_REQUEST_CODE);
        } else {
            goToFinder();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            goToFinder();
        }
    }

    private void goToFinder() {
        startActivity(new Intent(this, FinderActivity.class));
        finish();
    }

    private void speak(String text) {
        if (textToSpeech != null && AppPreferences.isVoiceGuideEnabled(this)) {
            Bundle params = new Bundle();
            params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME,
                    AppPreferences.getVolumePercent(this) / 100f);
            textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, params, "welcome_hub");
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (textToSpeech != null) {
            textToSpeech.stop();
            textToSpeech.shutdown();
        }
        super.onDestroy();
    }
}
