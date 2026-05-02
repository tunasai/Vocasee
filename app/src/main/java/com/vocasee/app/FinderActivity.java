package com.vocasee.app;

import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.view.View;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.vocasee.app.databinding.ActivityFinderBinding;

import java.util.ArrayList;
import java.util.Locale;

public class FinderActivity extends AppCompatActivity {

    private ActivityFinderBinding binding;
    private TextToSpeech textToSpeech;
    private SpeechRecognizer speechRecognizer;
    private boolean isListening = false;
    private boolean tutorialShown = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);

        binding = ActivityFinderBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        ViewCompat.setOnApplyWindowInsetsListener(binding.main, (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        // Initialize Text-to-Speech
        textToSpeech = new TextToSpeech(this, new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int status) {
                if (status == TextToSpeech.SUCCESS) {
                    textToSpeech.setLanguage(Locale.US);
                    if (tutorialShown) {
                        speak("Welcome to Finder Mode. Tutorial: Tap the microphone button and say the name of the object you want to find. The app will guide you to its location using voice instructions. Tap anywhere to start.");
                    }
                }
            }
        });

        // Initialize Speech Recognizer
        setupSpeechRecognizer();

        // Tutorial overlay click (if you have it in your layout)
        if (binding.tutorialOverlay != null) {
            binding.tutorialOverlay.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    binding.tutorialOverlay.setVisibility(View.GONE);
                    tutorialShown = false;
                    speak("Tutorial closed. Tap the microphone button to start finding objects.");
                }
            });
        }

        // Microphone button (if you have it)
        if (binding.btnMicrophone != null) {
            binding.btnMicrophone.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (!isListening) {
                        startListening();
                    }
                }
            });
        }

        // Menu button (if you have it)
        if (binding.btnMenu != null) {
            binding.btnMenu.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    speak("Menu opened. Options: Settings, Logout.");
                    Toast.makeText(FinderActivity.this, "Menu - Settings, Logout", Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    private void setupSpeechRecognizer() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override
            public void onReadyForSpeech(Bundle params) {
                if (binding.tvStatus != null) {
                    binding.tvStatus.setText("Listening...");
                    binding.tvStatus.setVisibility(View.VISIBLE);
                }
            }

            @Override
            public void onBeginningOfSpeech() {
                isListening = true;
            }

            @Override
            public void onRmsChanged(float rmsdB) {}

            @Override
            public void onBufferReceived(byte[] buffer) {}

            @Override
            public void onEndOfSpeech() {
                isListening = false;
                if (binding.tvStatus != null) {
                    binding.tvStatus.setVisibility(View.GONE);
                }
            }

            @Override
            public void onError(int error) {
                isListening = false;
                if (binding.tvStatus != null) {
                    binding.tvStatus.setVisibility(View.GONE);
                }
                String errorMessage = getErrorText(error);
                speak("Sorry, " + errorMessage + ". Please try again.");
                Toast.makeText(FinderActivity.this, errorMessage, Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onResults(Bundle results) {
                ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (matches != null && !matches.isEmpty()) {
                    String spokenText = matches.get(0);
                    if (binding.tvStatus != null) {
                        binding.tvStatus.setText("Searching for: " + spokenText);
                        binding.tvStatus.setVisibility(View.VISIBLE);
                    }
                    searchForObject(spokenText);
                }
            }

            @Override
            public void onPartialResults(Bundle partialResults) {}

            @Override
            public void onEvent(int eventType, Bundle params) {}
        });
    }

    private void startListening() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Say the object name...");

        speak("Listening. Please say the object you want to find.");

        try {
            speechRecognizer.startListening(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Speech recognition not available", Toast.LENGTH_SHORT).show();
        }
    }

    private void searchForObject(final String objectName) {
        speak("Searching for " + objectName);

        // Simulate object search with delay
        binding.main.postDelayed(new Runnable() {
            @Override
            public void run() {
                // Mock object finding (replace with actual camera/ML logic later)
                String result = findObjectMock(objectName.toLowerCase());
                speak(result);
                if (binding.tvStatus != null) {
                    binding.tvStatus.setText(result);

                    // Hide status after a few seconds
                    binding.tvStatus.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            binding.tvStatus.setVisibility(View.GONE);
                        }
                    }, 5000);
                }
            }
        }, 1500);
    }

    private String findObjectMock(String objectName) {
        // Mock database of objects and locations
        if (objectName.contains("key") || objectName.contains("keys")) {
            return "I found your keys on the kitchen counter, 2 feet to your right.";
        } else if (objectName.contains("phone")) {
            return "I found your phone on the coffee table, 3 feet ahead.";
        } else if (objectName.contains("glass") || objectName.contains("glasses")) {
            return "I found your glasses on the bedside table, behind you.";
        } else if (objectName.contains("wallet")) {
            return "I found your wallet in the drawer, left side.";
        } else if (objectName.contains("remote")) {
            return "I found your remote control on the sofa, to your left.";
        } else if (objectName.contains("bottle")) {
            return "I found a bottle on the table, straight ahead.";
        } else {
            return "I could not find " + objectName + " in the current view. Please move the camera around slowly.";
        }
    }

    private String getErrorText(int errorCode) {
        switch (errorCode) {
            case SpeechRecognizer.ERROR_AUDIO:
                return "Audio recording error";
            case SpeechRecognizer.ERROR_CLIENT:
                return "Client side error";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                return "Insufficient permissions";
            case SpeechRecognizer.ERROR_NETWORK:
                return "Network error";
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                return "Network timeout";
            case SpeechRecognizer.ERROR_NO_MATCH:
                return "No speech match found";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                return "Recognition service busy";
            case SpeechRecognizer.ERROR_SERVER:
                return "Server error";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                return "No speech input";
            default:
                return "Speech recognition error";
        }
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
        if (speechRecognizer != null) {
            speechRecognizer.destroy();
        }
        super.onDestroy();
    }
}