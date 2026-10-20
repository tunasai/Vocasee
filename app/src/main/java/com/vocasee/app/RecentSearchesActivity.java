package com.vocasee.app;

import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.content.res.AppCompatResources;

import com.vocasee.app.databinding.ActivityRecentSearchesBinding;

import java.util.List;

public class RecentSearchesActivity extends BaseActivity {

    private ActivityRecentSearchesBinding binding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityRecentSearchesBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        binding.btnBack.setOnClickListener(v -> finish());
        binding.btnClearHistory.setOnClickListener(v -> {
            AppPreferences.clearRecentSearches(this);
            Toast.makeText(this, R.string.history_cleared, Toast.LENGTH_SHORT).show();
            renderRecentSearches();
        });
        renderRecentSearches();
    }

    private void renderRecentSearches() {
        binding.recentContainer.removeAllViews();
        List<String> recent = AppPreferences.getRecentSearches(this);
        binding.emptyRecent.setVisibility(recent.isEmpty() ? View.VISIBLE : View.GONE);
        binding.btnClearHistory.setEnabled(!recent.isEmpty());
        for (String objectName : recent) {
            binding.recentContainer.addView(createRecentRow(objectName));
        }
    }

    private View createRecentRow(String objectName) {
        TextView row = new TextView(this);
        row.setText(capitalize(objectName));
        row.setTextSize(16f);
        row.setTextColor(getThemeTextColor());
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        Drawable historyIcon = AppCompatResources.getDrawable(this, R.drawable.ic_history);
        if (historyIcon != null) {
            historyIcon.setTint(getThemeTextColor());
        }
        row.setCompoundDrawablesWithIntrinsicBounds(historyIcon, null, null, null);
        row.setCompoundDrawablePadding(dp(12));
        row.setPadding(dp(16), 0, dp(16), 0);
        row.setMinHeight(dp(56));
        row.setBackgroundResource(R.drawable.settings_row_bg);
        row.setOnClickListener(v -> openFinderFor(objectName));
        return row;
    }

    private void openFinderFor(String objectName) {
        Intent intent = new Intent(this, FinderActivity.class);
        intent.putExtra(FinderActivity.EXTRA_TARGET_OBJECT, objectName);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
        finish();
    }

    private int getThemeTextColor() {
        android.util.TypedValue value = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.textColorPrimary, value, true);
        return value.resourceId != 0 ? getColor(value.resourceId) : value.data;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.substring(0, 1).toUpperCase() + s.substring(1);
    }
}
