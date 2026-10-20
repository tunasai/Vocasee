package com.vocasee.app;

import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.appcompat.content.res.AppCompatResources;

import com.vocasee.app.databinding.ActivityObjectListBinding;

public class ObjectListActivity extends BaseActivity {

    private ActivityObjectListBinding binding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityObjectListBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        binding.btnBack.setOnClickListener(v -> finish());
        for (String objectName : ObjectCatalog.PREDEFINED_OBJECTS) {
            binding.objectContainer.addView(createObjectRow(objectName));
        }
    }

    private View createObjectRow(String objectName) {
        TextView row = new TextView(this);
        row.setText(capitalize(objectName));
        row.setTextSize(16f);
        row.setTextColor(getThemeTextColor());
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        Drawable objectIcon = AppCompatResources.getDrawable(this, iconFor(objectName));
        if (objectIcon != null) {
            objectIcon.setTint(getThemeTextColor());
        }
        row.setCompoundDrawablesWithIntrinsicBounds(objectIcon, null, null, null);
        row.setCompoundDrawablePadding(dp(12));
        row.setPadding(dp(16), 0, dp(16), 0);
        row.setMinHeight(dp(56));
        row.setBackgroundResource(R.drawable.settings_row_bg);
        row.setOnClickListener(v -> openFinderFor(objectName));
        return row;
    }

    /** The row icon for a catalog object; the search icon if a new class has none yet. */
    @DrawableRes
    static int iconFor(String objectName) {
        switch (objectName) {
            case "backpack":     return R.drawable.ic_backpack;
            case "book":         return R.drawable.ic_book;
            case "cup":          return R.drawable.ic_cup;
            case "glasses":      return R.drawable.ic_glasses;
            case "headset":      return R.drawable.ic_headset;
            case "key":          return R.drawable.ic_key;
            case "phone":        return R.drawable.ic_phone;
            case "remote":       return R.drawable.ic_remote;
            case "wallet":       return R.drawable.ic_wallet;
            case "water bottle": return R.drawable.ic_water_bottle;
            case "body spray":   return R.drawable.ic_body_spray;
            case "card":         return R.drawable.ic_card;
            case "charger":      return R.drawable.ic_charger;
            case "comb":         return R.drawable.ic_comb;
            case "flashlight":   return R.drawable.ic_flashlight;
            case "glasses case": return R.drawable.ic_glasses_case;
            case "medicine":     return R.drawable.ic_medicine;
            case "nail clipper": return R.drawable.ic_nail_clipper;
            case "shoe":         return R.drawable.ic_shoe;
            case "watch":        return R.drawable.ic_watch;
            case "body scrub":   return R.drawable.ic_body_scrub;
            case "cane":         return R.drawable.ic_cane;
            case "lighter":      return R.drawable.ic_lighter;
            case "towel":        return R.drawable.ic_towel;
            default:             return R.drawable.ic_search;
        }
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
