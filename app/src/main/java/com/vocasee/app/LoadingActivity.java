package com.vocasee.app;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;

import com.vocasee.app.databinding.ActivityLoadingBinding;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class LoadingActivity extends BaseActivity {

    private ActivityLoadingBinding binding;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ExecutorService executor;
    private int progress = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Apply theme early
        int mode = AppPreferences.getThemeMode(this);
        if (mode == 0) AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
        else if (mode == 1) AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        else AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);

        super.onCreate(savedInstanceState);
        binding = ActivityLoadingBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        executor = Executors.newSingleThreadExecutor();
        animateProgress();
        loadModelThenContinue();
    }

    private void animateProgress() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (progress < 90) {
                    progress += 5;
                    updateProgress(progress);
                    handler.postDelayed(this, 120);
                }
            }
        }, 120);
    }

    private void loadModelThenContinue() {
        executor.execute(() -> {
            TFLiteDetector warmupDetector = null;
            try {
                warmupDetector = new TFLiteDetector(this, "model_24class_yolo26n.tflite");
            } catch (IOException ignored) {
                // Finder reports a model issue only if the user starts a detection search.
            } finally {
                if (warmupDetector != null) warmupDetector.close();
            }

            handler.postDelayed(() -> {
                updateProgress(100);
                Intent intent;
                if (AppPreferences.isTutorialDone(this)) {
                    intent = new Intent(this, FinderActivity.class);
                } else {
                    intent = new Intent(this, WelcomeActivity.class);
                }
                startActivity(intent);
                finish();
            }, 500);
        });
    }

    private void updateProgress(int value) {
        progress = value;
        binding.loadingProgress.setProgress(value);
        binding.tvProgress.setText(value + "%");
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (executor != null) executor.shutdownNow();
        super.onDestroy();
    }
}
