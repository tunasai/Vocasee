package com.vocasee.app;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;

import androidx.appcompat.app.AppCompatActivity;

public class LoadingActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_loading);

        new Handler().postDelayed(() -> {
            Intent intent = new Intent(LoadingActivity.this, WelcomeActivity.class);
            startActivity(intent);
            finish();
        }, 2500);
    }
}