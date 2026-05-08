package com.vocasee.app;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;

import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

public class LoadingActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_loading);

        new Handler().postDelayed(() -> {
            FirebaseUser currentUser = FirebaseAuth.getInstance().getCurrentUser();

            if (currentUser != null) {
                // Already signed in → go straight to FinderActivity
                startActivity(new Intent(LoadingActivity.this, FinderActivity.class));
            } else {
                // Not signed in → go to WelcomeActivity
                startActivity(new Intent(LoadingActivity.this, WelcomeActivity.class));
            }
            finish();

        }, 2500);
    }
}