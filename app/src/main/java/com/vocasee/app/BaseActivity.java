package com.vocasee.app;

import android.content.Context;
import android.content.res.Configuration;
import androidx.appcompat.app.AppCompatActivity;

public abstract class BaseActivity extends AppCompatActivity {

    @Override
    protected void attachBaseContext(Context newBase) {
        Configuration overrideConfig = new Configuration(newBase.getResources().getConfiguration());
        overrideConfig.fontScale = AppPreferences.getTextScale(newBase);
        Context context = newBase.createConfigurationContext(overrideConfig);
        super.attachBaseContext(context);
    }
}
