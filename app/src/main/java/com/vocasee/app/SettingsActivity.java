package com.vocasee.app;

import android.content.Intent;
import android.content.ActivityNotFoundException;
import android.net.Uri;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.view.View;
import android.widget.AdapterView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;

import com.vocasee.app.databinding.ActivitySettingsBinding;

public class SettingsActivity extends BaseActivity {

    private ActivitySettingsBinding binding;
    private SwitchCompat switchVibration;
    private SwitchCompat switchVoiceGuide;
    private SwitchCompat switchTalkBackCompatibility;
    private SwitchCompat switchSoundEffects;
    private SwitchCompat switchAutoFlashlight;
    private Spinner spinnerThemeMode;
    private SeekBar seekTextSize;
    private TextView tvTextSizeValue;
    private SwitchCompat switchConfidenceOverlay;
    private SwitchCompat switchColorDescription;
    private SwitchCompat switchRecentReminder;
    private SwitchCompat switchSaveHistory;
    private TextView tvVibrationState;
    private TextView tvVoiceGuideState;
    private TextView tvTalkBackCompatibilityState;
    private TextView tvSoundEffectsState;
    private TextView tvAutoFlashlightState;
    private TextView tvConfidenceOverlayState;
    private TextView tvColorDescriptionState;
    private TextView tvRecentReminderState;
    private TextView tvSaveHistoryState;
    private ToneGenerator toneGenerator;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivitySettingsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        refreshToneGenerator();

        switchVibration = findViewById(R.id.switchVibration);
        switchVoiceGuide = findViewById(R.id.switchVoiceGuide);
        switchTalkBackCompatibility = findViewById(R.id.switchTalkBackCompatibility);
        switchSoundEffects = findViewById(R.id.switchSoundEffects);
        switchAutoFlashlight = findViewById(R.id.switchAutoFlashlight);
        spinnerThemeMode = findViewById(R.id.spinnerThemeMode);
        seekTextSize = findViewById(R.id.seekTextSize);
        tvTextSizeValue = findViewById(R.id.tvTextSizeValue);
        switchConfidenceOverlay = findViewById(R.id.switchConfidenceOverlay);
        switchColorDescription = findViewById(R.id.switchColorDescription);
        switchRecentReminder = findViewById(R.id.switchRecentReminder);
        switchSaveHistory = findViewById(R.id.switchSaveHistory);
        tvVibrationState = findViewById(R.id.tvVibrationState);
        tvVoiceGuideState = findViewById(R.id.tvVoiceGuideState);
        tvTalkBackCompatibilityState = findViewById(R.id.tvTalkBackCompatibilityState);
        tvSoundEffectsState = findViewById(R.id.tvSoundEffectsState);
        tvAutoFlashlightState = findViewById(R.id.tvAutoFlashlightState);
        tvConfidenceOverlayState = findViewById(R.id.tvConfidenceOverlayState);
        tvColorDescriptionState = findViewById(R.id.tvColorDescriptionState);
        tvRecentReminderState = findViewById(R.id.tvRecentReminderState);
        tvSaveHistoryState = findViewById(R.id.tvSaveHistoryState);

        binding.btnBack.setOnClickListener(v -> finish());
        
        spinnerThemeMode.setSelection(AppPreferences.getThemeMode(this));
        seekTextSize.setProgress(AppPreferences.getTextSizePercent(this));
        
        switchVibration.setChecked(AppPreferences.isVibrationEnabled(this));
        switchVoiceGuide.setChecked(AppPreferences.isVoiceGuideEnabled(this));
        switchTalkBackCompatibility.setChecked(AppPreferences.isTalkBackCompatibilityEnabled(this));
        switchSoundEffects.setChecked(AppPreferences.isSoundEffectsEnabled(this));
        switchAutoFlashlight.setChecked(AppPreferences.isAutoFlashlightEnabled(this));
        switchConfidenceOverlay.setChecked(AppPreferences.isConfidenceOverlayEnabled(this));
        switchColorDescription.setChecked(AppPreferences.isColorDescriptionEnabled(this));
        switchRecentReminder.setChecked(AppPreferences.isRecentReminderEnabled(this));
        switchSaveHistory.setChecked(AppPreferences.isSaveHistoryEnabled(this));

        binding.seekSpeechSpeed.setProgress(AppPreferences.getSpeechSpeedPercent(this));
        binding.seekSensitivity.setProgress(AppPreferences.getSensitivityPercent(this));
        updateValueLabels();

        spinnerThemeMode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (AppPreferences.getThemeMode(SettingsActivity.this) != position) {
                    AppPreferences.setThemeMode(SettingsActivity.this, position);
                    applyTheme(position);
                    recreate();
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        seekTextSize.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                AppPreferences.setTextSizePercent(SettingsActivity.this, progress);
                updateValueLabels();
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                recreate();
            }
        });

        switchVibration.setOnCheckedChangeListener((buttonView, checked) -> {
            AppPreferences.setVibrationEnabled(this, checked);
            updateSwitchStateLabels();
            if (checked) {
                Vibrator vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
                if (vibrator != null && vibrator.hasVibrator()) {
                    vibrator.vibrate(VibrationEffect.createOneShot(
                            120, VibrationEffect.DEFAULT_AMPLITUDE));
                }
            }
        });
        switchVoiceGuide.setOnCheckedChangeListener((buttonView, checked) -> {
                AppPreferences.setVoiceGuideEnabled(this, checked);
                updateSwitchStateLabels();
        });
        switchTalkBackCompatibility.setOnCheckedChangeListener((buttonView, checked) -> {
            AppPreferences.setTalkBackCompatibilityEnabled(this, checked);
            updateSwitchStateLabels();
        });
        switchSoundEffects.setOnCheckedChangeListener((buttonView, checked) -> {
            AppPreferences.setSoundEffectsEnabled(this, checked);
            updateSwitchStateLabels();
            if (checked && toneGenerator != null) {
                toneGenerator.startTone(ToneGenerator.TONE_PROP_ACK, 180);
            }
        });
        switchAutoFlashlight.setOnCheckedChangeListener((buttonView, checked) -> {
                AppPreferences.setAutoFlashlightEnabled(this, checked);
                updateSwitchStateLabels();
        });
        switchConfidenceOverlay.setOnCheckedChangeListener((buttonView, checked) -> {
                AppPreferences.setConfidenceOverlayEnabled(this, checked);
                updateSwitchStateLabels();
        });
        switchColorDescription.setOnCheckedChangeListener((buttonView, checked) -> {
                AppPreferences.setColorDescriptionEnabled(this, checked);
                updateSwitchStateLabels();
        });
        switchRecentReminder.setOnCheckedChangeListener((buttonView, checked) -> {
            AppPreferences.setRecentReminderEnabled(this, checked);
            updateSwitchStateLabels();
        });
        switchSaveHistory.setOnCheckedChangeListener((buttonView, checked) -> {
            AppPreferences.setSaveHistoryEnabled(this, checked);
            updateSwitchStateLabels();
        });

        binding.seekSpeechSpeed.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                AppPreferences.setSpeechSpeedPercent(SettingsActivity.this, progress);
                updateValueLabels();
            }
        });

        binding.seekSensitivity.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                AppPreferences.setSensitivityPercent(SettingsActivity.this, progress);
                updateValueLabels();
            }
        });

        binding.rowReplayTutorial.setOnClickListener(v -> {
            AppPreferences.setTutorialDone(this, false);
            Intent intent = new Intent(this, FinderActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
            finish();
        });

        binding.rowClearHistory.setOnClickListener(v ->
                new AlertDialog.Builder(this)
                        .setTitle(R.string.settings_clear_history)
                        .setMessage(R.string.clear_history_confirm)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(R.string.clear_search_history, (dialog, which) -> {
                            AppPreferences.clearRecentSearches(this);
                            Toast.makeText(this, R.string.history_cleared, Toast.LENGTH_SHORT).show();
                        })
                        .show());

        binding.rowContact.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_SENDTO);
            intent.setData(Uri.parse("mailto:vocasee.support@example.com"));
            intent.putExtra(Intent.EXTRA_SUBJECT, "VocaSee Support");
            try {
                startActivity(Intent.createChooser(intent, "Contact Developers"));
            } catch (ActivityNotFoundException e) {
                Toast.makeText(this, "No email app is installed.", Toast.LENGTH_LONG).show();
            }
        });
    }

    private void updateValueLabels() {
        binding.tvSpeechSpeedValue.setText(String.format("%.2fx", AppPreferences.getSpeechRate(this)));
        binding.tvSensitivityValue.setText(AppPreferences.getSensitivityPercent(this) + "%");
        if (tvTextSizeValue != null) {
            tvTextSizeValue.setText(AppPreferences.getTextSizePercent(this) + "%");
        }
        updateSwitchStateLabels();
    }

    private void updateSwitchStateLabels() {
        tvVibrationState.setText(onOff(switchVibration.isChecked()));
        tvVoiceGuideState.setText(onOff(switchVoiceGuide.isChecked()));
        tvTalkBackCompatibilityState.setText(onOff(switchTalkBackCompatibility.isChecked()));
        tvSoundEffectsState.setText(onOff(switchSoundEffects.isChecked()));
        tvAutoFlashlightState.setText(onOff(switchAutoFlashlight.isChecked()));
        tvConfidenceOverlayState.setText(onOff(switchConfidenceOverlay.isChecked()));
        tvColorDescriptionState.setText(onOff(switchColorDescription.isChecked()));
        tvRecentReminderState.setText(onOff(switchRecentReminder.isChecked()));
        tvSaveHistoryState.setText(onOff(switchSaveHistory.isChecked()));
    }

    private String onOff(boolean enabled) {
        return enabled ? "On" : "Off";
    }

    private void applyTheme(int mode) {
        switch (mode) {
            case 0: // Light
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
                break;
            case 1: // Dark
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
                break;
            case 2: // System
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
                break;
        }
    }

    private abstract static class SimpleSeekListener implements SeekBar.OnSeekBarChangeListener {
        @Override public void onStartTrackingTouch(SeekBar seekBar) {}
        @Override public void onStopTrackingTouch(SeekBar seekBar) {}
    }

    private void refreshToneGenerator() {
        if (toneGenerator != null) toneGenerator.release();
        toneGenerator = new ToneGenerator(
                AudioManager.STREAM_NOTIFICATION, AppPreferences.getVolumePercent(this));
    }

    @Override
    protected void onDestroy() {
        if (toneGenerator != null) toneGenerator.release();
        super.onDestroy();
    }
}
