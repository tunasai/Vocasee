package com.vocasee.app;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.tasks.Task;
import com.google.firebase.auth.AuthCredential;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.GoogleAuthProvider;
import com.vocasee.app.databinding.ActivityLoginBinding;

import java.util.Locale;

public class LoginActivity extends AppCompatActivity {

    private static final String TAG = "LoginActivity";
    private static final int PERMISSION_REQUEST_CODE = 100;

    private ActivityLoginBinding binding;
    private TextToSpeech textToSpeech;
    private FirebaseAuth firebaseAuth;
    private GoogleSignInClient googleSignInClient;

    private final ActivityResultLauncher<Intent> signInLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                    result -> handleGoogleSignInResult(result));

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);

        binding = ActivityLoginBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        ViewCompat.setOnApplyWindowInsetsListener(binding.main, (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        // Initialize Firebase Auth
        firebaseAuth = FirebaseAuth.getInstance();

        // Session check is handled in LoadingActivity — no signOut() here

        // Configure Google Sign-In safely
        String webClientId = "";
        try {
            webClientId = getString(R.string.default_web_client_id);
        } catch (Exception e) {
            Log.e(TAG, "default_web_client_id not found in strings.xml", e);
        }

        if (!webClientId.isEmpty()) {
            GoogleSignInOptions gso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                    .requestIdToken(webClientId)
                    .requestEmail()
                    .build();
            googleSignInClient = GoogleSignIn.getClient(this, gso);
        }

        // Initialize TTS
        textToSpeech = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                textToSpeech.setLanguage(Locale.US);
                speak("Login page. Tap the first button to sign in with Google, or the second button to continue as guest.");
            }
        });

        // Google Sign-In button
        binding.btnGoogleSignIn.setOnClickListener(v -> {
            if (googleSignInClient != null) {
                speak("Opening Google Sign-In.");
                Intent signInIntent = googleSignInClient.getSignInIntent();
                signInLauncher.launch(signInIntent);
            } else {
                Toast.makeText(this, "Google Sign-In not configured yet. Please complete Firebase setup.", Toast.LENGTH_LONG).show();
            }
        });

        // Guest button
        binding.btnGuestLogin.setOnClickListener(v -> {
            speak("Continuing as guest.");
            checkPermissionsAndProceed();
        });
    }

    private void handleGoogleSignInResult(ActivityResult result) {
        Task<GoogleSignInAccount> task = GoogleSignIn.getSignedInAccountFromIntent(result.getData());
        try {
            GoogleSignInAccount account = task.getResult(ApiException.class);
            speak("Google account found. Signing you in.");
            firebaseAuthWithGoogle(account.getIdToken());
        } catch (ApiException e) {
            Log.w(TAG, "Google sign-in failed", e);
            speak("Google sign-in failed. Please try again.");
            Toast.makeText(this, "Google Sign-In failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void firebaseAuthWithGoogle(String idToken) {
        AuthCredential credential = GoogleAuthProvider.getCredential(idToken, null);
        firebaseAuth.signInWithCredential(credential)
                .addOnCompleteListener(this, task -> {
                    if (task.isSuccessful()) {
                        FirebaseUser user = firebaseAuth.getCurrentUser();
                        speak("Welcome, " + (user != null ? user.getDisplayName() : "user") + ". Opening finder.");
                        checkPermissionsAndProceed();
                    } else {
                        Log.w(TAG, "Firebase auth failed", task.getException());
                        speak("Sign-in failed. Please try again.");
                        Toast.makeText(this, "Authentication failed: " +
                                        (task.getException() != null ? task.getException().getMessage() : "unknown error"),
                                Toast.LENGTH_LONG).show();
                    }
                });
    }

    private void checkPermissionsAndProceed() {
        boolean audioGranted = ContextCompat.checkSelfPermission(this,
                Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        boolean cameraGranted = ContextCompat.checkSelfPermission(this,
                Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;

        if (!audioGranted || !cameraGranted) {
            ActivityCompat.requestPermissions(this,
                    new String[]{
                            Manifest.permission.RECORD_AUDIO,
                            Manifest.permission.CAMERA
                    },
                    PERMISSION_REQUEST_CODE);
        } else {
            goToFinder();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                speak("Permissions granted. Opening finder.");
            } else {
                speak("Some permissions denied. Some features may not work.");
            }
            goToFinder();
        }
    }

    private void goToFinder() {
        Intent intent = new Intent(LoginActivity.this, FinderActivity.class);
        startActivity(intent);
        finish();
    }

    private void speak(String text) {
        if (textToSpeech != null) {
            textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, null);
        }
    }

    @Override
    protected void onDestroy() {
        if (textToSpeech != null) {
            textToSpeech.stop();
            textToSpeech.shutdown();
        }
        super.onDestroy();
    }
}