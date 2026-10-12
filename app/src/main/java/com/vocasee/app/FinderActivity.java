package com.vocasee.app;

import android.content.Intent;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;
import android.util.Size;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.ScaleAnimation;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.Manifest;
import android.content.pm.PackageManager;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.view.ViewCompat;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.Camera;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult;
import com.vocasee.app.databinding.ActivityFinderBinding;

import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.TermCriteria;
import org.opencv.imgproc.Imgproc;

import java.io.IOException;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// RESTORED STABLE VERSION
public class FinderActivity extends BaseActivity {

    /*
     * SYSTEM PROCESS MAP (Input -> Process -> Output)
     *  1. Load local preferences and initialize the on-device models.
     *  2. Capture a voice or touch-selected object request.
     *  3. Normalize the request, resolve aliases, and validate the supported class.
     *  4. Capture rear-camera frames with CameraX.
     *  5. Preprocess frames and run intent-filtered TFLite object detection.
     *  6. Stabilize detections and derive color and relative visual proximity.
     *  7. When close enough, compare the MediaPipe hand box with the object box.
     *  8. Generate visual, spoken, haptic, and tone feedback.
     *  9. Ask the user to confirm retrieval, then finish or continue the search.
     *
     * Detailed PROCESS comments below identify where each stage is implemented.
     */

    private static final String TAG = "FinderActivity";
    public static final String EXTRA_TARGET_OBJECT = "com.vocasee.app.EXTRA_TARGET_OBJECT";
    private static final long NOT_YET_VISIBLE_INTERVAL_MS = 5000;
    private static final long SEARCH_NOT_FOUND_TIMEOUT_MS = 60000;
    private static final long SEARCH_DETECTION_UI_GRACE_MS = 4000;
    private static final long SEARCH_HELP_INTERVAL_MS     = 8000;
    private static final long SEARCH_REMINDER_STOP_BEFORE_TIMEOUT_MS = 5000;
    private static final float LOW_LIGHT_LUMA_THRESHOLD = 52f;
    private static final float SCENE_CHANGE_LUMA_THRESHOLD = 8f;
    private static final long STILL_CAMERA_GUIDANCE_MS = 4500;
    private static final int LUMA_SAMPLE_COLUMNS = 8;
    private static final int LUMA_SAMPLE_ROWS = 6;
    private static final long INFERENCE_INTERVAL_MS       = 120;
    private static final int  DETECTION_LOST_MISS_FRAMES         = 20;
    // A hand naturally hides the target during retrieval. Keep its last verified
    // box alive long enough for hand guidance and the spoken confirmation to finish.
    private static final int  LOCKED_DETECTION_LOST_MISS_FRAMES  = 300;
    private static final long LOCKED_DETECTION_LOST_TIMEOUT_MS   = 30000;
    private static final int  HAND_GUIDANCE_LOST_MISS_FRAMES     = 600;
    private static final long HAND_GUIDANCE_RETENTION_MS         = 60000;
    private static final long CONFIRMATION_MIN_RETAIN_MS         = 5000;
    // The visible blue box must represent a recent live YOLO result. The longer
    // retained box is still available internally for hand guidance and haptics.
    private static final long LIVE_BOX_GRACE_MS                  = 7000;
    private static final float TRACKING_SMOOTHING_ALPHA    = 0.62f;
    private static final float TRACKING_FAST_ALPHA         = 1.0f;
    private static final float TRACKING_FAST_MOVE_RATIO    = 0.10f;
    private static final float TRACKING_FAST_SIZE_RATIO    = 0.18f;
    // Detector boxes commonly include a small background margin. Tighten only the
    // visible overlay; tracking, haptics, and hand guidance retain raw coordinates.
    private static final float VISUAL_BOX_INSET_RATIO       = 0.06f;

    private static final long MIC_START_DELAY_MS = 900;
    // TalkBack first announces the focused microphone control. Starting Android's
    // recognizer immediately can make it transcribe that speaker audio as a command.
    private static final long TALKBACK_MIC_START_DELAY_MS = 10000;
    private static final long STARTUP_PROMPT_DELAY_MS = 5000;
    private static final long MIC_RETRY_DELAY_MS = 500;
    private static final long MIC_READY_TIMEOUT_MS = 3500;

    private static final long  RADAR_FAR_INTERVAL_MS    = 900;
    private static final long  RADAR_CLOSE_INTERVAL_MS  = 60;
    private static final int   RADAR_MIN_AMPLITUDE      = 55;
    private static final int   RADAR_MAX_AMPLITUDE      = 190;
    private static final int   RADAR_CENTER_AMPLITUDE   = 255;
    private static final float RADAR_CENTER_TOLERANCE   = 0.14f;
    private static final float RADAR_MOVE_CLOSER_AREA   = 0.15f;
    private static final float RADAR_REACHED_PROXIMITY  = 0.30f;
    private static final float RADAR_FULL_WIDTH_RATIO   = 0.82f;
    private static final float RADAR_TOO_CLOSE_AREA      = 0.70f;
    private static final float RADAR_TOO_CLOSE_WIDTH     = 0.93f;
    // A detected object must remain close in consecutive live frames before VocaSee
    // asks the user to reach toward it. It never confirms retrieval from radar alone.
    private static final long  RADAR_REACH_STABLE_MS = 1200;
    private static final long  REACH_INSTRUCTION_REPEAT_MS = 30000;
    private static final long  MOVE_CLOSER_TTS_INTERVAL_MS = 8000;
    private static final boolean HAND_TO_OBJECT_GUIDANCE_ENABLED = true;
    private static final boolean HAND_RETRIEVAL_CONFIRMATION_ENABLED = true;
    private static final String HAND_LANDMARKER_TASK = "hand_landmarker.task";
    private static final float HAND_APPROACHING_DISTANCE = 0.24f;
    private static final float HAND_HOLDING_DISTANCE = 0.20f;
    private static final float HAND_FAR_DISTANCE = 0.42f;
    // A single camera cannot establish depth from overlap alone. These guards reject a
    // foreground hand that fills the view and require a short, stable contact pattern.
    private static final float HAND_CONTACT_MAX_AREA_RATIO = 20.0f;
    // Ask while the target is still partly visible. Waiting for near-total coverage
    // means the hand often hides the target before VocaSee can ask its question.
    private static final float HAND_RETRIEVAL_OBJECT_COVERAGE = 0.12f;
    private static final float HAND_COVERS_OBJECT_RATIO = 0.55f;
    private static final long HAND_CONTACT_STABLE_MS = 650;
    private static final long RETRIEVAL_READY_DELAY_MS = 1800;
    private static final long HAND_CONTACT_RETAIN_MS = 1700;
    private static final long HAND_MISSING_GRACE_MS = 900;
    private static final long HAND_OCCLUSION_MAX_AFTER_LAST_SEEN_MS = 3000;
    private static final long HAND_OCCLUSION_RETAIN_MS = 6500;
    // Start looking for a hand before the object reaches the final radar threshold.
    // This lets hand guidance be ready before the hand crosses the object.
    private static final float HAND_GUIDANCE_ACTIVATION_PROXIMITY = 0.16f;
    private static final float HAND_GUIDANCE_ACTIVATION_WIDTH_RATIO = 0.40f;
    private static final long HAND_GUIDANCE_TTS_INTERVAL_MS = 3000;
    private static final long HAND_GUIDANCE_REPEAT_SAME_MS = 6500;
    private static final long HAND_OCCLUSION_TTS_INTERVAL_MS = 4500;
    // Hand guidance remains responsive at this rate, while leaving enough CPU time
    // for the visible YOLO box to update on consecutive camera frames.
    private static final long HAND_DETECTION_INTERVAL_MS = 120;
    private static final long RESULT_PANEL_UPDATE_INTERVAL_MS = 350;
    private static final long NO_HAND_FALLBACK_DELAY_MS = 3000;
    private static final long NO_HAND_FALLBACK_INTERVAL_MS = 5000;
    private static final long HOLDING_CONFIRMATION_REPEAT_MS = 6500;
    private static final int HOLDING_CONFIRMATION_MAX_ATTEMPTS = 3;
    private static final long RETRIEVAL_PROMPT_DELAY_MS = 2500;
    private static final int COLOR_HISTORY_SIZE = 7;
    private static final int COLOR_STABLE_MIN_COUNT = 4;
    private static final int COLOR_MIN_SAMPLES = 8;
    private static final int COLOR_MIN_CLUSTER_SAMPLES = 3;
    private static final float COLOR_INNER_ELLIPSE_RADIUS = 0.42f;
    private static final float COLOR_DARK_PIXEL_THRESHOLD = 0.055f;
    private static final int COLOR_WHITE_CHANNEL_MIN = 210;
    private static final int COLOR_WHITE_MAX_CHANNEL_SPREAD = 18;
    private static final long OCR_INTERVAL_MS = 7000;
    private static final long OCR_INITIAL_DELAY_MS = 3500;
    private static final long COLOR_SAMPLE_INTERVAL_MS = 400;
    private static final String COLOR_UNCLEAR = "unclear";
    private static final String[][] ALIASES = {
            { "hair comb", "comb" },
            { "headphones", "headset" },
            { "headphone", "headset" },
            { "earphones", "headset" },
            { "walking cane", "cane" },
            { "walking stick", "cane" },
            { "spectacles", "glasses" },
            { "eyeglasses", "glasses" },
            { "keys", "key" },
            { "remote control", "remote" },
            { "tv remote", "remote" },
            { "bottle", "water bottle" },
            { "body mist", "body spray" },
            { "flash light", "flashlight" },
            { "spectacle case", "glasses case" },
            { "nail cutter", "nail clipper" },
            { "shoes", "shoe" },
            { "sneakers", "shoe" },
            { "wristwatch", "watch" },
            { "charger cable", "charger" },
            { "charging cable", "charger" },
            { "usb charger", "charger" },
            { "mobile phone", "phone" },
            { "cell phone", "phone" },
            { "cellphone", "phone" },
            { "smartphone", "phone" },
            { "gamot", "medicine" },
            { "pill", "medicine" },
            { "pills", "medicine" },
            { "tablet", "medicine" },
            { "capsule", "medicine" },
            { "medication", "medicine" }
    };

    private enum DetectionState { IDLE, SCANNING, FOUND, NOT_IN_CLASSES }
    private enum VoiceMode { OBJECT, CONFIRMATION }

    private ActivityFinderBinding binding;
    private VoiceFeedbackManager  voiceFeedback;
    private SpeechRecognizer      speechRecognizer;
    private BottomSheetDialog     voiceModal;
    private ExecutorService       cameraExecutor;
    private ExecutorService       handExecutor;
    private TFLiteDetector        detector;
    private volatile boolean      detectorLoadFailed;
    private OnDeviceTextRecognizer onDeviceTextRecognizer;
    private HandLandmarker        handLandmarker;
    private Vibrator              vibrator;
    private Camera                camera;
    private ToneGenerator         toneGenerator;
    private View[]                voiceWaveBars;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Handler hapticRadarHandler = new Handler(Looper.getMainLooper());
    private final Runnable hapticRadarRunnable = new Runnable() {
        @Override public void run() {
            pulseHapticRadar();
        }
    };
    private final Runnable searchReminderRunnable = new Runnable() {
        @Override public void run() {
            playSearchReminderIfNeeded();
        }
    };

    private boolean        isProcessingResult = false;
    private VoiceMode      voiceMode = VoiceMode.OBJECT;
    private long           voiceTurnToken = 0;
    private String         targetObject  = null;
    private String         targetTFLabel = null;
    private volatile DetectionState currentState  = DetectionState.IDLE;
    private String         lastSpokenColor = null;
    private String         lastSpokenLabel = null;
    private String         announcedFoundColor = null;
    private String         announcedFoundText = null;
    private long           foundFeedbackStartedAt = 0;
    private boolean        userSpeechStarted = false;
    private boolean        recognizerReadyForSpeech = false;
    private long           recognizerAttemptToken = 0;
    private boolean        openCvReady = false;
    private float          appliedTextScale;
    private int            tutorialStep = 0;
    private boolean        tutorialShowing = false;
    private boolean        talkBackVoiceGuideNoticeShown = false;
    private boolean        hapticRadarActive = false;
    private float          hapticRadarProximity = 0f;
    private float          hapticRadarWidthRatio = 0f;
    private boolean        hapticRadarCentered = false;
    private boolean        handGuidanceOwnsVibration = false;
    private boolean        handGuidanceIntroductionSpoken = false;
    private boolean        isTrackingLocked = false;
    private boolean        hasAskedHoldingConfirmation = false;
    private boolean        reachInstructionSent = false;
    private String         lastSpokenApproachBand = null;
    private String         lastHandGuidanceText = null;

    private long lastInferenceTime    = 0;
    private long lastModelInputDiagnosticSaveAt = 0;
    private long lastNotYetVisibleTts = 0;
    private volatile long lastDetectionSeenTime = 0;
    private long lastMoveCloserTts = 0;
    private long lastHandGuidanceTts = 0;
    private long lastHandOcclusionTts = 0;
    private long handContactStartedAt = 0;
    private long lastConfirmedHandOcclusionAt = 0;
    private long lastSceneMotionAt = 0;
    private float lastFrameLuma = 255f;
    private float[] lastLumaSamples = null;
    private final Set<String> usedSearchGuidanceKeys = new HashSet<>();
    private volatile long lastHandSeenTime = 0;
    private volatile long lastHandDetectionTime = 0;
    private long lastNoHandFallbackTts = 0;
    private long lastHoldingConfirmationPrompt = 0;
    private long radarRetrievalStartedAt = 0;
    private long lastReachInstructionAt = 0;
    private long lastLiveHandContactAt = 0;
    private long lastResultPanelUpdateTime = 0;
    private long lastOcrScanTime = 0;
    private long lastColorSampleTime = 0;
    private long searchStartedAt = 0;
    private long detectionSessionToken = 0;
    private int  consecutiveMissedDetections = 0;
    private boolean hasSpokenSearchTimeout = false;
    private volatile boolean hasDetectedTargetInCurrentSearch = false;
    private int holdingConfirmationAttempts = 0;
    private volatile Rect lastKnownTrackingBox = null;
    // Frozen only for hand guidance. The live detector box above it continues to move.
    private volatile Rect handGuidanceAnchorBox = null;
    private volatile String lastKnownTrackingLabel = null;
    private volatile String lastOcrText = null;
    private volatile boolean ocrInFlight = false;
    private String lastSpokenOcrText = null;
    private volatile float lastKnownTrackingConfidence = 0f;
    // Metrics in the uncropped camera frame. Do not derive retrieval distance from the
    // full-screen PreviewView because its center-crop scale can inflate a box area.
    private volatile float lastKnownTrackingProximity = 0f;
    private volatile float lastKnownTrackingWidthRatio = 0f;
    private volatile Rect lastKnownHandBox = null;
    private volatile boolean handInferenceInFlight = false;
    private final ArrayList<String> recentColorHistory = new ArrayList<>();
    private String stableColorName = COLOR_UNCLEAR;

    private float letterboxScale   = 1f;
    private int   letterboxPadLeft = 0;
    private int   letterboxPadTop  = 0;

    private boolean        awaitingAnotherConfirmation = false;
    private boolean        awaitingRepeatConfirmation = false;
    private boolean        awaitingHoldingConfirmation = false;
    private boolean        awaitingFindAnotherConfirmation = false;
    private String         suggestedRepeatObject = null;
    private final Random   promptRandom = new Random();

    // ─── LIFECYCLE ────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // PROCESS 1 - INITIALIZATION: Apply preferences and prepare detector, hand tracker, TTS, tones, and vibration.
        super.onCreate(savedInstanceState);
        binding = ActivityFinderBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        appliedTextScale = AppPreferences.getTextScale(this);
        openCvReady = OpenCVLoader.initDebug();
        if (!openCvReady) {
            Log.w(TAG, "OpenCV failed to initialize; color descriptions will remain conservative.");
        }

        cameraExecutor = Executors.newSingleThreadExecutor();
        handExecutor = Executors.newSingleThreadExecutor();
        initializeVibrator();
        refreshToneGenerator();

        checkPermissions();

        cameraExecutor.execute(() -> {
            try {
                detector = new TFLiteDetector(this, "model_24class_yolo26n.tflite");
                detector.setConfidenceThreshold(AppPreferences.getConfidenceThreshold(this));
                initializeHandLandmarker();
            } catch (IOException e) {
                detectorLoadFailed = true;
                Log.e(TAG, "Detection model failed to load", e);
            }
        });

        voiceFeedback = new VoiceFeedbackManager(this);
        voiceFeedback.setAccessibilityAnnouncementView(binding.tvStatusText);
        voiceFeedback.setOnUtteranceDoneListener(utteranceId -> {
            if ("startup_prompt".equals(utteranceId)) {
                mainHandler.postDelayed(() -> {
                    if (!tutorialShowing && currentState == DetectionState.IDLE
                            && (voiceModal == null || !voiceModal.isShowing())) {
                        openVoiceModalWithoutCancellingSearch(
                                awaitingRepeatConfirmation ? VoiceMode.CONFIRMATION : VoiceMode.OBJECT);
                    }
                }, 250);
            } else if ("holding_confirmation_prompt".equals(utteranceId)
                    || "find_another_confirmation_prompt".equals(utteranceId)) {
                mainHandler.postDelayed(() ->
                        openVoiceModalWithoutCancellingSearch(VoiceMode.CONFIRMATION), 250);
            } else if ("find_another_object_prompt".equals(utteranceId)) {
                mainHandler.postDelayed(() ->
                        openVoiceModalWithoutCancellingSearch(VoiceMode.OBJECT), 250);
            }
        });
        voiceFeedback.initialize(() -> {
            voiceFeedback.refreshSpeechRate();
            if (savedInstanceState == null
                    && !hasTargetObjectExtra(getIntent())
                    && AppPreferences.isTutorialDone(this)) {
                startVoiceGuideWithTalkBackNotice();
            }
        });

        startCamera();

        binding.btnMic.setOnClickListener(v -> openVoiceModal());
        binding.btnRecent.setOnClickListener(v ->
                startActivity(new Intent(FinderActivity.this, RecentSearchesActivity.class)));
        binding.btnObjects.setOnClickListener(v ->
                startActivity(new Intent(FinderActivity.this, ObjectListActivity.class)));
        binding.btnSettings.setOnClickListener(v ->
                startActivity(new Intent(FinderActivity.this, SettingsActivity.class)));
        binding.btnTutorialNext.setOnClickListener(v -> advanceFinderTutorial());
        binding.btnTutorialSkip.setOnClickListener(v -> finishFinderTutorial());
        binding.btnTutorialNext.setContentDescription(getString(R.string.cd_tutorial_next));
        binding.btnTutorialSkip.setContentDescription(getString(R.string.cd_tutorial_skip));

        boolean launchedWithTarget = hasTargetObjectExtra(getIntent());
        handleTargetObjectExtra(getIntent());
        if (savedInstanceState == null && !launchedWithTarget
                && !AppPreferences.isTutorialDone(this)) {
            mainHandler.postDelayed(() -> showFinderTutorialStep(0), 450);
        }
        
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleTargetObjectExtra(intent);
    }

    private boolean hasTargetObjectExtra(Intent intent) {
        return intent != null && intent.getStringExtra(EXTRA_TARGET_OBJECT) != null;
    }

    private void handleTargetObjectExtra(Intent intent) {
        // PROCESS 2 - TOUCH INPUT: Object-list/history selections enter the same search flow as a voice request.
        if (!hasTargetObjectExtra(intent)) return;
        String target = intent.getStringExtra(EXTRA_TARGET_OBJECT);
        if (target != null && !target.trim().isEmpty()) {
            startSearchForObject(target);
        }
        intent.removeExtra(EXTRA_TARGET_OBJECT);
    }

    private void checkPermissions() {
        String[] permissions = {Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO};
        List<String> needed = new ArrayList<>();
        for (String p : permissions) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                needed.add(p);
            }
        }
        if (!needed.isEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toArray(new String[0]), 101);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 101) {
            boolean ok = true;
            for (int res : grantResults) {
                if (res != PackageManager.PERMISSION_GRANTED) { ok = false; break; }
            }
            if (ok) startCamera();
            else speak("Permissions are required to find objects.");
        }
    }

    // ─── CAMERA ───────────────────────────────────────────────────────────

    private void startCamera() {
        // PROCESS 4 - CAMERA INPUT: CameraX supplies rear-camera preview frames and keeps only the latest frame.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);

        future.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = future.get();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(binding.cameraPreview.getSurfaceProvider());

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setTargetResolution(new Size(640, 480))
                        .build();

                imageAnalysis.setAnalyzer(cameraExecutor, this::analyzeFrame);

                cameraProvider.unbindAll();
                camera = cameraProvider.bindToLifecycle(
                        this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis);
                applyTorchForState();
            } catch (Exception e) {
                speak("Camera initialization failed.");
            }
        }, ContextCompat.getMainExecutor(this));
    }

    // ─── FRAME ANALYSIS ───────────────────────────────────────────────────

    private void initializeHandLandmarker() {
        // PROCESS 7 - HAND MODEL SETUP: Load the local MediaPipe task in video mode for one-hand tracking.
        if (!HAND_TO_OBJECT_GUIDANCE_ENABLED) {
            handLandmarker = null;
            return;
        }
        try {
            BaseOptions baseOptions = BaseOptions.builder()
                    .setModelAssetPath(HAND_LANDMARKER_TASK)
                    .build();
            HandLandmarker.HandLandmarkerOptions options =
                    HandLandmarker.HandLandmarkerOptions.builder()
                            .setBaseOptions(baseOptions)
                            .setRunningMode(RunningMode.VIDEO)
                            .setNumHands(1)
                            .setMinHandDetectionConfidence(0.4f)
                            .setMinHandPresenceConfidence(0.4f)
                            .setMinTrackingConfidence(0.5f)
                            .build();
            handLandmarker = HandLandmarker.createFromOptions(this, options);
        } catch (Exception e) {
            Log.w(TAG, "Hand landmarker failed to initialize", e);
            handLandmarker = null;
        }
    }

    private void analyzeFrame(ImageProxy imageProxy) {
        // PROCESS 5 - FRAME ANALYSIS: Rotate/letterbox the frame, run target-only detection, and select the best match.
        // PROCESS 6 - DERIVED DATA: Smooth/persist its box, sample approximate color, and estimate relative proximity.
        // PROCESS 7 - CONDITIONAL HAND TRACKING: Run only after the object appears close enough for reaching guidance.
        final DetectionState stateSnapshot  = currentState;
        final String         targetSnapshot = targetObject;
        final String         labelSnapshot  = targetTFLabel;
        final long           sessionSnapshot = detectionSessionToken;

        try {
            if (stateSnapshot == DetectionState.IDLE || stateSnapshot == DetectionState.NOT_IN_CLASSES || targetSnapshot == null || labelSnapshot == null || detector == null) return;

            long now = SystemClock.elapsedRealtime();
            if (now - lastInferenceTime < INFERENCE_INTERVAL_MS) return;
            lastInferenceTime = now;

            if (stateSnapshot == DetectionState.SCANNING) {
                updateSearchVisualSignals(imageProxy, now);
            }

            if (stateSnapshot == DetectionState.SCANNING && hasSearchTimedOut(now)) {
                runOnUiThread(() -> {
                    if (isActiveDetectionSession(sessionSnapshot)) {
                        stopSearchAfterTimeout(targetSnapshot);
                    }
                });
                return;
            }

            int rotationDeg = imageProxy.getImageInfo().getRotationDegrees();
            @androidx.annotation.OptIn(markerClass = androidx.camera.core.ExperimentalGetImage.class)
            Bitmap rawBitmap = imageProxy.toBitmap();
            Bitmap uprightBitmap = rotateBitmap(rawBitmap, rotationDeg);
            if (uprightBitmap != rawBitmap) rawBitmap.recycle();

            int uprightW = uprightBitmap.getWidth();
            int uprightH = uprightBitmap.getHeight();
            Bitmap modelInput = applyLetterbox(uprightBitmap,
                    detector.getInputWidth(), detector.getInputHeight());
            maybeSaveModelInputDiagnostic(modelInput, now);

            boolean continuingTargetTrack = lastKnownTrackingBox != null
                    && targetSnapshot.equalsIgnoreCase(lastKnownTrackingLabel);
            float normalConfidence = AppPreferences.getConfidenceThreshold(this);
            float frameConfidence = continuingTargetTrack
                    ? Math.min(normalConfidence, 0.20f) : normalConfidence;
            List<TFLiteDetector.Detection> detections = detector.detect(
                    modelInput, labelSnapshot, frameConfidence);
            if (!isActiveDetectionSession(sessionSnapshot)) {
                modelInput.recycle();
                uprightBitmap.recycle();
                return;
            }

            TFLiteDetector.Detection selectedDetection = detections.isEmpty() ? null
                    : selectContinuingDetection(detections, continuingTargetTrack,
                    uprightW, uprightH, normalConfidence);
            if (selectedDetection == null) {
                modelInput.recycle();
                consecutiveMissedDetections++;

                long timeSinceLastSeen = now - lastDetectionSeenTime;
                boolean hasArmedTrackingBox = lastKnownTrackingBox != null
                        && targetSnapshot != null
                        && targetSnapshot.equalsIgnoreCase(lastKnownTrackingLabel);
                boolean shouldUseTrackingGrace = isTrackingLocked || hasArmedTrackingBox;
                boolean handGuidanceActive = handGuidanceIntroductionSpoken;
                int missFrameLimit = handGuidanceActive
                        ? HAND_GUIDANCE_LOST_MISS_FRAMES
                        : shouldUseTrackingGrace
                        ? LOCKED_DETECTION_LOST_MISS_FRAMES
                        : DETECTION_LOST_MISS_FRAMES;
                boolean retainingForConfirmation = shouldRetainDetectionDuringConfirmation(now);
                boolean withinMissBudget = consecutiveMissedDetections < missFrameLimit;
                Rect persistentBox = lastKnownTrackingBox == null ? null : new Rect(lastKnownTrackingBox);
                Rect handReferenceBox = handGuidanceAnchorBox == null
                        ? persistentBox : new Rect(handGuidanceAnchorBox);
                Rect handBox = persistentBox == null ? null
                        : getHandBoxForGuidanceIfNeeded(uprightBitmap, uprightW, uprightH, handReferenceBox,
                                lastKnownTrackingProximity, lastKnownTrackingWidthRatio, now);
                boolean handOccludingTarget = isLikelyHandOcclusion(handReferenceBox, handBox);
                boolean retainingForRecentHandOcclusion = handBox != null
                        && now - lastConfirmedHandOcclusionAt <= HAND_OCCLUSION_RETAIN_MS;
                long retentionMs = handGuidanceActive
                        ? HAND_GUIDANCE_RETENTION_MS : LOCKED_DETECTION_LOST_TIMEOUT_MS;
                boolean withinTimeBudget = !shouldUseTrackingGrace
                        || timeSinceLastSeen < retentionMs
                        || retainingForConfirmation
                        || handOccludingTarget
                        || retainingForRecentHandOcclusion;

                if (persistentBox != null
                        && (retainingForConfirmation || withinMissBudget || handOccludingTarget
                        || retainingForRecentHandOcclusion)
                        && withinTimeBudget) {
                    String persistentLabel = lastKnownTrackingLabel == null ? targetSnapshot : lastKnownTrackingLabel;
                    float persistentConfidence = lastKnownTrackingConfidence;
                    uprightBitmap.recycle();
                    runOnUiThread(() -> {
                        if (!isActiveDetectionSession(sessionSnapshot)) return;
                        if (timeSinceLastSeen <= LIVE_BOX_GRACE_MS) {
                            showBoundingBox(persistentBox, persistentLabel, persistentConfidence);
                            updateHapticRadar(persistentBox, false,
                                    lastKnownTrackingProximity, lastKnownTrackingWidthRatio);
                        } else {
                            hideLiveBoundingBox();
                            // The retained tracking box is useful for recovery and hand
                            // guidance, but it is no longer a live target for the radar.
                            stopHapticRadar();
                        }
                        if (HAND_TO_OBJECT_GUIDANCE_ENABLED && handBox != null) {
                            // A retained box is only a last verified position, not new proof of contact.
                            updateHandGuidance(handReferenceBox, handBox, false);
                            if (handOccludingTarget) {
                                promptHandOcclusion();
                            }
                        } else {
                            clearHandGuidanceOverlayAndResumeRadar();
                        }
                    });
                    return;
                }

                uprightBitmap.recycle();
                runOnUiThread(() -> {
                    if (isActiveDetectionSession(sessionSnapshot)) {
                        clearTrackingState(targetSnapshot);
                    }
                });
                return;
            }

            TFLiteDetector.Detection best = selectedDetection;
            lastDetectionSeenTime = now;
            // Publish the hit before posting UI work so the timeout/reminder thread
            // cannot announce a miss while the detection callback is still queued.
            hasDetectedTargetInCurrentSearch = true;
            consecutiveMissedDetections = 0;
            Rect screenBox = smoothTrackingBox(mapBoxToScreen(best.boundingBox, uprightW, uprightH));
            String recognizedText = maybeRunOnDeviceOcr(uprightBitmap, best, targetSnapshot,
                    sessionSnapshot, now);
            RectF sourceBox = mapModelBoxToSource(best.boundingBox, uprightW, uprightH);
            float sourceProximity = clamp01((sourceBox.width() * sourceBox.height())
                    / Math.max(1f, uprightW * (float) uprightH));
            float sourceWidthRatio = clamp01(sourceBox.width() / Math.max(1f, uprightW));
            // Retain the first valid target immediately. The UI callback and hand model
            // run afterward, so waiting to cache it can lose the target to a moving hand.
            cacheTrackingBox(screenBox, targetSnapshot, best.confidence,
                    sourceProximity, sourceWidthRatio);
            String colorName = stableColorName;
            if (now - lastColorSampleTime >= COLOR_SAMPLE_INTERVAL_MS) {
                lastColorSampleTime = now;
                colorName = updateStableColorName(getDominantColorName(uprightBitmap, sourceBox));
            }
            modelInput.recycle();
            Rect handReferenceBox = handGuidanceAnchorBox == null
                    ? screenBox : new Rect(handGuidanceAnchorBox);
            Rect handBox = getHandBoxForGuidanceIfNeeded(uprightBitmap, uprightW, uprightH, handReferenceBox,
                    sourceProximity, sourceWidthRatio, now);
            if (isPotentialLiveHandOcclusion(handReferenceBox, handBox,
                    sourceProximity, sourceWidthRatio)) {
                lastConfirmedHandOcclusionAt = now;
            }
            uprightBitmap.recycle();

            final String finalColor = (colorName != null) ? colorName : COLOR_UNCLEAR;
            final String finalRecognizedText = recognizedText;
            lastSpokenColor = finalColor;

            runOnUiThread(() -> onObjectDiscovered(sessionSnapshot, targetSnapshot, screenBox, finalColor,
                    best.confidence, handBox, finalRecognizedText,
                    sourceProximity, sourceWidthRatio));

        } finally {
            imageProxy.close();
        }
    }

    private void onObjectDiscovered(long sessionSnapshot, String label, Rect screenBox, String color,
                                    float confidence, Rect handBox, String recognizedText,
                                    float sourceProximity, float sourceWidthRatio) {
        // PROCESS 8 - MULTIMODAL OUTPUT: Update visual overlays, speak results, play a cue, and start haptic guidance.
        if (!isActiveDetectionSession(sessionSnapshot)) return;
        if (targetObject == null || label == null || !label.equalsIgnoreCase(targetObject)) return;
        hasDetectedTargetInCurrentSearch = true;
        isTrackingLocked = true;
        cacheTrackingBox(screenBox, label, confidence, sourceProximity, sourceWidthRatio);
        if (currentState != DetectionState.FOUND) {
            stopSearchReminder();
            setDetectionState(DetectionState.FOUND);
            lastSpokenLabel = label;
            lastSpokenColor = color;
            triggerHapticFeedback();
            playSoundEffect(ToneGenerator.TONE_PROP_ACK);
            String framePosition = getFramePositionLabel(screenBox);
            updateDetectedResultPanel(label, color, confidence, framePosition, recognizedText);
            updateFoundStatus(label, color, recognizedText);
            boolean hasColor = AppPreferences.isColorDescriptionEnabled(this)
                    && isStableColorForFeedback(color);
            boolean hasText = isReadableOcrText(recognizedText);
            String foundText = "Found the " + label + "."
                    + (hasColor ? " It looks " + formatColorDescription(color) + "." : "")
                    + " Keep it in view; I will guide you closer.";
            if (hasColor) announcedFoundColor = color;
            if (hasText) {
                lastSpokenOcrText = recognizedText;
                announcedFoundText = recognizedText;
            }
            foundFeedbackStartedAt = SystemClock.elapsedRealtime();
            speak(foundText);
            // Keep the user's hands away from the camera until the target is visibly
            // close. The reach prompt is issued by the stable proximity gate below.
        } else {
            if (lastSpokenLabel == null || !lastSpokenLabel.equalsIgnoreCase(label)) {
                lastSpokenLabel = label;
                lastSpokenColor = color;
            }
            lastSpokenColor = color;
            maybeUpdateDetectedResultPanel(label, color, confidence, getFramePositionLabel(screenBox), recognizedText);
            updateFoundStatus(label, color, recognizedText);
            announceFoundDetailsIfNeeded(label, color, recognizedText);
        }
        showBoundingBox(screenBox, label, confidence);
        // Once the target is locked, a visible hand can be guided even before the
        // object is close enough for a retrieval confirmation.
        boolean handGuidanceCandidate = HAND_TO_OBJECT_GUIDANCE_ENABLED && handBox != null;
        // A visible hand owns the guidance loop. This prevents a radar confirmation from
        // firing first simply because the object momentarily fills a large part of the view.
        updateHapticRadar(screenBox, !handGuidanceCandidate, sourceProximity, sourceWidthRatio);
        if (handGuidanceCandidate) {
            Rect handReferenceBox = handGuidanceAnchorBox == null
                    ? screenBox : new Rect(handGuidanceAnchorBox);
            updateHandGuidance(handReferenceBox, handBox, true);
            maybeGiveNoHandFallbackGuidance(handBox, SystemClock.elapsedRealtime());
        } else {
            clearHandGuidanceOverlayAndResumeRadar();
            maybeGiveNoHandFallbackGuidance(handBox, SystemClock.elapsedRealtime());
            announceProximityBand(screenBox);
        }
    }

    private String maybeRunOnDeviceOcr(Bitmap sourceFrame, TFLiteDetector.Detection detection,
                                       String requestedLabel, long sessionSnapshot, long now) {
        // OCR is supplementary detail. Defer it until the primary detection feedback
        // is already stable so it cannot delay the object box or retrieval flow.
        if (currentState != DetectionState.FOUND
                || now - foundFeedbackStartedAt < OCR_INITIAL_DELAY_MS
                || sourceFrame == null || detection == null || ocrInFlight
                || now - lastOcrScanTime < OCR_INTERVAL_MS) {
            return lastOcrText;
        }

        RectF sourceBox = mapModelBoxToSource(detection.boundingBox,
                sourceFrame.getWidth(), sourceFrame.getHeight());
        int left = Math.max(0, Math.round(sourceBox.left));
        int top = Math.max(0, Math.round(sourceBox.top));
        int right = Math.min(sourceFrame.getWidth(), Math.round(sourceBox.right));
        int bottom = Math.min(sourceFrame.getHeight(), Math.round(sourceBox.bottom));
        if (right <= left || bottom <= top) return lastOcrText;

        lastOcrScanTime = now;
        ocrInFlight = true;
        Bitmap objectCrop = Bitmap.createBitmap(sourceFrame, left, top, right - left, bottom - top);
        if (onDeviceTextRecognizer == null) {
            onDeviceTextRecognizer = new OnDeviceTextRecognizer();
        }
        onDeviceTextRecognizer.recognize(objectCrop, text -> {
            ocrInFlight = false;
            if (!isReadableOcrText(text)) return;
            lastOcrText = text;
            runOnUiThread(() -> {
                if (!isActiveDetectionSession(sessionSnapshot)
                        || !requestedLabel.equalsIgnoreCase(targetObject)) return;
                if (lastKnownTrackingBox != null) {
                    maybeUpdateDetectedResultPanel(requestedLabel, lastSpokenColor,
                            lastKnownTrackingConfidence,
                            getFramePositionLabel(lastKnownTrackingBox), text);
                    updateFoundStatus(requestedLabel, lastSpokenColor, text);
                }
                announceFoundDetailsIfNeeded(requestedLabel, lastSpokenColor, text);
            });
        });
        return lastOcrText;
    }

    private boolean isReadableOcrText(String text) {
        return text != null && !text.trim().isEmpty();
    }

    private Rect smoothTrackingBox(Rect detectedBox) {
        // PROCESS 6A - SMOOTHING: Blend small box changes while allowing large movements to update immediately.
        if (lastKnownTrackingBox == null) {
            return new Rect(detectedBox);
        }
        float alpha = getTrackingSmoothingAlpha(lastKnownTrackingBox, detectedBox);
        int left = lerp(lastKnownTrackingBox.left, detectedBox.left, alpha);
        int top = lerp(lastKnownTrackingBox.top, detectedBox.top, alpha);
        int right = lerp(lastKnownTrackingBox.right, detectedBox.right, alpha);
        int bottom = lerp(lastKnownTrackingBox.bottom, detectedBox.bottom, alpha);
        return new Rect(
                Math.min(left, right - 1),
                Math.min(top, bottom - 1),
                Math.max(right, left + 1),
                Math.max(bottom, top + 1)
        );
    }

    private TFLiteDetector.Detection selectContinuingDetection(
            List<TFLiteDetector.Detection> detections, boolean continuingTrack,
            int frameWidth, int frameHeight, float normalConfidence) {
        if (!continuingTrack || lastKnownTrackingBox == null) return detections.get(0);

        Rect previous = new Rect(lastKnownTrackingBox);
        float diagonal = (float) Math.sqrt(
                Math.max(1f, binding.cameraPreview.getWidth() * (float) binding.cameraPreview.getWidth()
                        + binding.cameraPreview.getHeight() * (float) binding.cameraPreview.getHeight()));
        TFLiteDetector.Detection closest = null;
        float bestMatchScore = -Float.MAX_VALUE;
        for (TFLiteDetector.Detection candidate : detections) {
            Rect candidateBox = mapBoxToScreen(candidate.boundingBox, frameWidth, frameHeight);
            float intersection = getIntersectionArea(previous, candidateBox);
            float union = previous.width() * (float) previous.height()
                    + candidateBox.width() * (float) candidateBox.height() - intersection;
            float overlap = intersection / Math.max(1f, union);
            float dx = previous.exactCenterX() - candidateBox.exactCenterX();
            float dy = previous.exactCenterY() - candidateBox.exactCenterY();
            float distance = (float) Math.sqrt(dx * dx + dy * dy) / Math.max(1f, diagonal);
            if (overlap < 0.03f && distance > 0.16f) continue;

            float matchScore = overlap * 2f + candidate.confidence * 0.25f - distance;
            if (matchScore > bestMatchScore) {
                bestMatchScore = matchScore;
                closest = candidate;
            }
        }

        // A weak detection far from the locked object is more likely a class
        // confusion than a real track update. Require the normal threshold to jump.
        if (closest != null) return closest;
        TFLiteDetector.Detection strongest = detections.get(0);
        return strongest.confidence >= normalConfidence ? strongest : null;
    }

    private float getTrackingSmoothingAlpha(Rect previousBox, Rect detectedBox) {
        float dx = Math.abs(previousBox.exactCenterX() - detectedBox.exactCenterX());
        float dy = Math.abs(previousBox.exactCenterY() - detectedBox.exactCenterY());
        float screenW = Math.max(1f, binding.cameraPreview.getWidth());
        float screenH = Math.max(1f, binding.cameraPreview.getHeight());
        float movementRatio = (float) Math.sqrt(dx * dx + dy * dy)
                / (float) Math.sqrt(screenW * screenW + screenH * screenH);
        float previousArea = Math.max(1f, previousBox.width() * (float) previousBox.height());
        float detectedArea = Math.max(1f, detectedBox.width() * (float) detectedBox.height());
        float sizeRatio = Math.abs(detectedArea - previousArea) / Math.max(previousArea, detectedArea);
        return movementRatio > TRACKING_FAST_MOVE_RATIO || sizeRatio > TRACKING_FAST_SIZE_RATIO
                ? TRACKING_FAST_ALPHA
                : TRACKING_SMOOTHING_ALPHA;
    }

    private int lerp(int from, int to, float alpha) {
        return Math.round(from + ((to - from) * alpha));
    }

    private void cacheTrackingBox(Rect box, String label, float confidence,
                                  float sourceProximity, float sourceWidthRatio) {
        lastKnownTrackingBox = new Rect(box);
        if (handGuidanceAnchorBox == null) {
            handGuidanceAnchorBox = new Rect(box);
        }
        lastKnownTrackingLabel = label;
        lastKnownTrackingConfidence = confidence;
        lastKnownTrackingProximity = sourceProximity;
        lastKnownTrackingWidthRatio = sourceWidthRatio;
    }

    private Rect detectHandBox(Bitmap frame, int frameWidth, int frameHeight, long timestampMs) {
        // PROCESS 7A - HAND LOCALIZATION: Convert MediaPipe landmarks into a screen-space hand bounding box.
        if (handLandmarker == null) return null;
        try {
            MPImage image = new BitmapImageBuilder(frame).build();
            HandLandmarkerResult result = handLandmarker.detectForVideo(image, timestampMs);
            if (result == null || result.landmarks().isEmpty()) return null;
            return mapHandLandmarksToScreen(result.landmarks().get(0), frameWidth, frameHeight);
        } catch (Exception e) {
            Log.w(TAG, "Hand detection failed", e);
            return null;
        }
    }

    private Rect getHandBoxForGuidanceIfNeeded(Bitmap frame, int frameWidth, int frameHeight,
                                                Rect objectBox, float sourceProximity,
                                                float sourceWidthRatio, long now) {
        if (!HAND_TO_OBJECT_GUIDANCE_ENABLED) {
            lastKnownHandBox = null;
            return null;
        }
        if (!isCloseEnoughForHandGuidance(sourceProximity, sourceWidthRatio)) {
            lastKnownHandBox = null;
            return null;
        }

        // Keep MediaPipe off the YOLO camera worker. The live object box must not
        // wait for hand inference before it can adjust on the next camera frame.
        if (!handInferenceInFlight && now - lastHandDetectionTime >= HAND_DETECTION_INTERVAL_MS) {
            lastHandDetectionTime = now;
            handInferenceInFlight = true;
            Bitmap handFrame = frame.copy(Bitmap.Config.ARGB_8888, false);
            handExecutor.execute(() -> {
                try {
                    Rect handBox = detectHandBox(handFrame, frameWidth, frameHeight, now);
                    if (handBox != null && !handBox.isEmpty()) {
                        lastKnownHandBox = new Rect(handBox);
                        lastHandSeenTime = SystemClock.elapsedRealtime();
                    }
                } finally {
                    if (!handFrame.isRecycled()) handFrame.recycle();
                    handInferenceInFlight = false;
                }
            });
        }

        // MediaPipe can miss a hand for one blurred frame. Retain its last location briefly
        // so a hand crossing the target does not immediately hand control back to the radar.
        if (lastKnownHandBox != null && now - lastHandSeenTime <= HAND_MISSING_GRACE_MS) {
            return new Rect(lastKnownHandBox);
        }
        lastKnownHandBox = null;
        return null;
    }

    private Rect mapHandLandmarksToScreen(List<NormalizedLandmark> landmarks, int frameWidth, int frameHeight) {
        float minX = 1f, minY = 1f, maxX = 0f, maxY = 0f;
        for (NormalizedLandmark landmark : landmarks) {
            minX = Math.min(minX, landmark.x());
            minY = Math.min(minY, landmark.y());
            maxX = Math.max(maxX, landmark.x());
            maxY = Math.max(maxY, landmark.y());
        }

        int screenW = binding.cameraPreview.getWidth();
        int screenH = binding.cameraPreview.getHeight();
        if (screenW == 0) screenW = getResources().getDisplayMetrics().widthPixels;
        if (screenH == 0) screenH = getResources().getDisplayMetrics().heightPixels;

        float scale = Math.max((float) screenW / frameWidth, (float) screenH / frameHeight);
        float previewW = frameWidth * scale;
        float previewH = frameHeight * scale;
        float offsetX = (screenW - previewW) / 2f;
        float offsetY = (screenH - previewH) / 2f;
        int pad = dp(18);

        int left = Math.round(minX * previewW + offsetX) - pad;
        int top = Math.round(minY * previewH + offsetY) - pad;
        int right = Math.round(maxX * previewW + offsetX) + pad;
        int bottom = Math.round(maxY * previewH + offsetY) + pad;
        return new Rect(
                Math.max(0, left),
                Math.max(0, top),
                Math.min(screenW, right),
                Math.min(screenH, bottom));
    }

    private void updateHandGuidance(Rect objectBox, Rect handBox, boolean canPromptHoldingConfirmation) {
        // PROCESS 7B - HAND-TO-OBJECT GUIDANCE: Compare box centers/intersection and output directional instructions.
        if (!HAND_TO_OBJECT_GUIDANCE_ENABLED) {
            clearHandGuidanceOverlayAndResumeRadar();
            return;
        }
        if (handBox == null || handBox.isEmpty()) {
            handContactStartedAt = 0;
            clearHandGuidanceOverlayAndResumeRadar();
            return;
        }

        boolean handTooCloseToCamera = isHandTooCloseToCamera(objectBox, handBox);
        boolean isFirstHandGuidance = !handGuidanceIntroductionSpoken;
        String directionalGuidance = handTooCloseToCamera
                ? "Your hand is very close to the camera. Move it back slightly so I can keep tracking the object."
                : buildHandGuidance(objectBox, handBox);
        String guidance = isFirstHandGuidance
                ? "Hand detected. I will now guide your hand to the object. Keep the phone still."
                : directionalGuidance;
        binding.handGuidanceOverlay.showGuidance(objectBox, handBox, guidance);
        handGuidanceOwnsVibration = true;
        stopHapticRadar();

        // Start gathering strong contact evidence immediately, even while the first
        // hand-guidance sentence is playing. If the hand then hides the object, the
        // retained object box can complete this short stability window.
        boolean contactCanContinueThroughOcclusion = canPromptHoldingConfirmation
                || isHandCoveringObject(objectBox, handBox);
        boolean stableHandContact = contactCanContinueThroughOcclusion
                && hasStableHandObjectContact(objectBox, handBox);

        if (isFirstHandGuidance) {
            handGuidanceIntroductionSpoken = true;
            lastHandGuidanceTts = SystemClock.elapsedRealtime();
            lastHandGuidanceText = guidance;
            vibratePattern(45, 45);
            // This must follow the detection announcement instead of being discarded as
            // low-priority ambient speech while Text-to-Speech is still busy.
            speakQueued(guidance, "hand_guidance_start");
            return;
        }

        boolean recentLiveContact = lastLiveHandContactAt > 0
                && SystemClock.elapsedRealtime() - lastLiveHandContactAt <= HAND_CONTACT_RETAIN_MS;
        if (HAND_RETRIEVAL_CONFIRMATION_ENABLED
                && (stableHandContact || recentLiveContact)) {
            if (shouldAskHoldingConfirmationAgain()) {
                vibratePattern(45, 45, 45);
                promptHoldingConfirmation();
            }
            return;
        }

        long now = SystemClock.elapsedRealtime();
        float distance = getHandDistanceRatio(objectBox, handBox);
        vibratePattern(distance > HAND_FAR_DISTANCE ? 45 : 30);

        boolean guidanceChanged = !guidance.equals(lastHandGuidanceText);
        long repeatDelay = guidanceChanged
                ? HAND_GUIDANCE_TTS_INTERVAL_MS : HAND_GUIDANCE_REPEAT_SAME_MS;
        if (now - lastHandGuidanceTts >= repeatDelay
                && !awaitingHoldingConfirmation
                && !awaitingFindAnotherConfirmation) {
            lastHandGuidanceTts = now;
            lastHandGuidanceText = guidance;
            if (voiceFeedback != null) {
                voiceFeedback.speakNormal(guidance, "hand_guidance");
            }
        }
    }

    private void clearHandGuidanceOverlayAndResumeRadar() {
        binding.handGuidanceOverlay.clear();
        resumeRadarVibrationIfNeeded();
    }

    private void resumeRadarVibrationIfNeeded() {
        if (!handGuidanceOwnsVibration) return;
        handGuidanceOwnsVibration = false;
        if (currentState == DetectionState.FOUND && lastKnownTrackingBox != null) {
            updateHapticRadar(lastKnownTrackingBox, false,
                    lastKnownTrackingProximity, lastKnownTrackingWidthRatio);
        }
    }

    private void maybeGiveNoHandFallbackGuidance(Rect handBox, long now) {
        boolean handMissing = handBox == null || handBox.isEmpty();
        boolean closeEnough = hasReachedObjectProximity();
        boolean handMissingLongEnough = now - lastHandSeenTime >= NO_HAND_FALLBACK_DELAY_MS;

        if (!handMissing || !closeEnough || !handMissingLongEnough) return;
        if (awaitingHoldingConfirmation || awaitingFindAnotherConfirmation) return;
        if (now - lastNoHandFallbackTts < NO_HAND_FALLBACK_INTERVAL_MS) return;

        lastNoHandFallbackTts = now;
        promptReachForObject();
    }

    private boolean isLikelyHandOcclusion(Rect objectBox, Rect handBox) {
        if (objectBox == null || handBox == null || handBox.isEmpty()) return false;
        // Keep an old box briefly through real occlusion, but never indefinitely because
        // a large foreground hand happens to overlap a target that is farther away.
        if (SystemClock.elapsedRealtime() - lastDetectionSeenTime > HAND_OCCLUSION_MAX_AFTER_LAST_SEEN_MS
                || isHandTooCloseToCamera(objectBox, handBox)) {
            return false;
        }

        // Proximity is expected during reach guidance; call it occlusion only when
        // the hand actually covers most of the target box.
        return isHandCoveringObject(objectBox, handBox);
    }

    private boolean isPotentialLiveHandOcclusion(Rect objectBox, Rect handBox,
                                                 float sourceProximity, float sourceWidthRatio) {
        if (objectBox == null || handBox == null || handBox.isEmpty()
                || !isCloseEnoughForHandGuidance(sourceProximity, sourceWidthRatio)) {
            return false;
        }
        float objectArea = Math.max(1f, objectBox.width() * (float) objectBox.height());
        return getIntersectionArea(objectBox, handBox) / objectArea >= 0.30f;
    }

    private boolean hasStableHandObjectContact(Rect objectBox, Rect handBox) {
        if (!isPlausibleHandObjectContact(objectBox, handBox)) {
            handContactStartedAt = 0;
            return false;
        }

        long now = SystemClock.elapsedRealtime();
        if (handContactStartedAt == 0) handContactStartedAt = now;
        boolean stable = now - handContactStartedAt >= HAND_CONTACT_STABLE_MS;
        if (stable) lastLiveHandContactAt = now;
        return stable;
    }

    private boolean isPlausibleHandObjectContact(Rect objectBox, Rect handBox) {
        if (objectBox == null || handBox == null || handBox.isEmpty()
                || !handGuidanceIntroductionSpoken
                || foundFeedbackStartedAt <= 0
                || SystemClock.elapsedRealtime() - foundFeedbackStartedAt < RETRIEVAL_READY_DELAY_MS) {
            return false;
        }

        float objectArea = Math.max(1f, objectBox.width() * (float) objectBox.height());
        float overlapRatio = getIntersectionArea(objectBox, handBox) / objectArea;
        boolean handCoversObject = overlapRatio >= HAND_COVERS_OBJECT_RATIO;
        if (isHandTooCloseToCamera(objectBox, handBox) && !handCoversObject) {
            return false;
        }
        // A hand moving toward the object is guidance, not proof it has reached it.
        // Require a substantial actual overlap before starting the contact timer.
        return handCoversObject || overlapRatio >= 0.30f;
    }

    private boolean isHandCoveringObject(Rect objectBox, Rect handBox) {
        if (objectBox == null || handBox == null || handBox.isEmpty()) return false;
        float objectArea = Math.max(1f, objectBox.width() * (float) objectBox.height());
        return getIntersectionArea(objectBox, handBox) / objectArea >= HAND_COVERS_OBJECT_RATIO;
    }

    /**
     * A hand landmark box often surrounds the wrist and fingers, so requiring the
     * entire hand box to cover the object misses normal grasping. This inner-expanded
     * zone represents the point where the hand has reached the detected target.
     */
    private Rect getRetrievalContactZone(Rect objectBox) {
        Rect zone = new Rect(objectBox);
        int horizontalPad = Math.max(dp(12), Math.round(objectBox.width() * 0.20f));
        int verticalPad = Math.max(dp(12), Math.round(objectBox.height() * 0.20f));
        zone.inset(-horizontalPad, -verticalPad);
        return zone;
    }

    private boolean isHandTooCloseToCamera(Rect objectBox, Rect handBox) {
        if (objectBox == null || handBox == null || handBox.isEmpty()) return false;
        float objectArea = Math.max(1f, objectBox.width() * (float) objectBox.height());
        float handArea = Math.max(1f, handBox.width() * (float) handBox.height());
        return handArea > objectArea * HAND_CONTACT_MAX_AREA_RATIO;
    }

    private Rect expandObjectBox(Rect objectBox) {
        Rect expandedObject = new Rect(objectBox);
        int horizontalPad = Math.max(dp(18), Math.round(objectBox.width() * 0.12f));
        int verticalPad = Math.max(dp(18), Math.round(objectBox.height() * 0.12f));
        expandedObject.inset(-horizontalPad, -verticalPad);
        return expandedObject;
    }

    private float getIntersectionArea(Rect first, Rect second) {
        int left = Math.max(first.left, second.left);
        int top = Math.max(first.top, second.top);
        int right = Math.min(first.right, second.right);
        int bottom = Math.min(first.bottom, second.bottom);
        return right > left && bottom > top ? (right - left) * (float) (bottom - top) : 0f;
    }

    private boolean shouldAskHoldingConfirmationAgain() {
        if (awaitingHoldingConfirmation || awaitingFindAnotherConfirmation) return false;
        long now = SystemClock.elapsedRealtime();
        if (!hasAskedHoldingConfirmation) return true;
        return holdingConfirmationAttempts < HOLDING_CONFIRMATION_MAX_ATTEMPTS
                && now - lastHoldingConfirmationPrompt >= HOLDING_CONFIRMATION_REPEAT_MS;
    }

    private void promptHandOcclusion() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastHandOcclusionTts < HAND_OCCLUSION_TTS_INTERVAL_MS
                || awaitingHoldingConfirmation
                || awaitingFindAnotherConfirmation) {
            return;
        }
        lastHandOcclusionTts = now;
        if (voiceFeedback != null) {
            voiceFeedback.speakAmbient("The object is partly hidden. Keep your hand steady while I track its last position.",
                    "hand_occlusion", HAND_OCCLUSION_TTS_INTERVAL_MS);
        }
    }

    private String buildHandGuidance(Rect objectBox, Rect handBox) {
        float distance = getHandDistanceRatio(objectBox, handBox);
        String direction = getHandMoveDirection(objectBox, handBox);
        if (distance > HAND_FAR_DISTANCE) {
            return "Move your hand slightly " + direction + ".";
        }
        if (distance > HAND_APPROACHING_DISTANCE) {
            return "Almost there, a little " + direction + ".";
        }
        return "Very close, keep moving " + direction + ".";
    }

    private String getHandMoveDirection(Rect objectBox, Rect handBox) {
        float dx = objectBox.exactCenterX() - handBox.exactCenterX();
        float dy = objectBox.exactCenterY() - handBox.exactCenterY();
        if (Math.abs(dx) > Math.abs(dy)) {
            return dx > 0 ? "right" : "left";
        }
        return dy > 0 ? "lower" : "higher";
    }

    private float getHandDistanceRatio(Rect objectBox, Rect handBox) {
        float dx = objectBox.exactCenterX() - handBox.exactCenterX();
        float dy = objectBox.exactCenterY() - handBox.exactCenterY();
        float distance = (float) Math.sqrt(dx * dx + dy * dy);
        float screenW = Math.max(1f, binding.cameraPreview.getWidth());
        float screenH = Math.max(1f, binding.cameraPreview.getHeight());
        float diagonal = (float) Math.sqrt(screenW * screenW + screenH * screenH);
        return distance / diagonal;
    }

    private void clearTrackingState(String targetSnapshot) {
        // PROCESS 6B - TRACKING LOSS: Retain brief misses; after the miss budget, clear overlays and resume scanning.
        if (shouldRetainDetectionDuringConfirmation(SystemClock.elapsedRealtime())) {
            return;
        }
        boolean wasLocked = isTrackingLocked;
        String lastKnownView = getLastKnownViewDescription(lastKnownTrackingBox);
        stopHapticRadar();
        hideBoundingBox();
        binding.handGuidanceOverlay.clear();
        binding.detectedResultPanel.setVisibility(View.GONE);
        consecutiveMissedDetections = 0;
        lastMoveCloserTts = 0;
        lastSpokenApproachBand = null;
        handGuidanceIntroductionSpoken = false;
        lastHandGuidanceText = null;
        lastHandGuidanceTts = 0;
        lastKnownTrackingBox = null;
        handGuidanceAnchorBox = null;
        lastKnownTrackingLabel = null;
        lastKnownTrackingConfidence = 0f;
        lastKnownTrackingProximity = 0f;
        lastKnownTrackingWidthRatio = 0f;
        radarRetrievalStartedAt = 0;
        lastReachInstructionAt = 0;
        reachInstructionSent = false;
        lastLiveHandContactAt = 0;
        resetTrackingLock();
        if (currentState == DetectionState.FOUND) {
            setDetectionState(DetectionState.SCANNING);
            hasDetectedTargetInCurrentSearch = false;
            hasSpokenSearchTimeout = false;
            searchStartedAt = SystemClock.elapsedRealtime();
            lastNotYetVisibleTts = 0;
            if (wasLocked) {
                speak("I lost sight of the " + targetSnapshot + " for a moment. "
                        + "It was last seen " + lastKnownView + ". Keep the phone still, then gently return "
                        + "to the direction where the haptic pulses were strongest. I will keep searching.");
            }
            startSearchReminder();
        }
    }

    // ─── IMAGE UTILS ──────────────────────────────────────────────────────

    private boolean hasSearchTimedOut(long now) {
        return searchStartedAt > 0
                && !hasDetectedTargetInCurrentSearch
                && !hasRecentTargetDetection(now)
                && now - searchStartedAt >= SEARCH_NOT_FOUND_TIMEOUT_MS
                && currentState == DetectionState.SCANNING;
    }

    private boolean hasRecentTargetDetection(long now) {
        return lastDetectionSeenTime > 0
                && now - lastDetectionSeenTime <= SEARCH_DETECTION_UI_GRACE_MS;
    }

    private boolean isActiveDetectionSession(long sessionSnapshot) {
        return sessionSnapshot == detectionSessionToken
                && currentState != DetectionState.IDLE
                && currentState != DetectionState.NOT_IN_CLASSES
                && targetObject != null
                && targetTFLabel != null;
    }

    private boolean shouldRetainDetectionDuringConfirmation(long now) {
        if (!awaitingHoldingConfirmation || lastKnownTrackingBox == null) return false;
        return lastHoldingConfirmationPrompt <= 0
                || now - lastHoldingConfirmationPrompt <= CONFIRMATION_MIN_RETAIN_MS
                || voiceModal != null && voiceModal.isShowing();
    }

    private void invalidateDetectionSession() {
        detectionSessionToken++;
    }

    private void startSearchReminder() {
        stopSearchReminder();
        mainHandler.postDelayed(searchReminderRunnable, SEARCH_HELP_INTERVAL_MS);
    }

    private void stopSearchReminder() {
        mainHandler.removeCallbacks(searchReminderRunnable);
    }

    private void playSearchReminderIfNeeded() {
        if (currentState != DetectionState.SCANNING
                || searchStartedAt <= 0
                || hasDetectedTargetInCurrentSearch
                || hasRecentTargetDetection(SystemClock.elapsedRealtime())
                || targetObject == null
                || targetObject.trim().isEmpty()) {
            return;
        }

        long now = SystemClock.elapsedRealtime();
        long elapsed = now - searchStartedAt;
        long remaining = SEARCH_NOT_FOUND_TIMEOUT_MS - elapsed;
        if (remaining <= SEARCH_REMINDER_STOP_BEFORE_TIMEOUT_MS) {
            return;
        }

        speakAdaptiveGuidance(
                "search_reminder",
                "The requested object is not visible yet.",
                "Keep scanning for the " + targetObject + ". " + buildAdaptiveSearchGuidance(now),
                "search_reminder",
                SEARCH_HELP_INTERVAL_MS);
        mainHandler.postDelayed(searchReminderRunnable, SEARCH_HELP_INTERVAL_MS);
    }

    private void updateSearchVisualSignals(ImageProxy imageProxy, long now) {
        ImageProxy.PlaneProxy plane = imageProxy.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        int width = imageProxy.getWidth();
        int height = imageProxy.getHeight();
        int rowStride = plane.getRowStride();
        int pixelStride = plane.getPixelStride();
        float[] samples = new float[LUMA_SAMPLE_COLUMNS * LUMA_SAMPLE_ROWS];
        float total = 0f;
        int index = 0;

        for (int row = 0; row < LUMA_SAMPLE_ROWS; row++) {
            int y = Math.min(height - 1, (row * 2 + 1) * height / (LUMA_SAMPLE_ROWS * 2));
            for (int column = 0; column < LUMA_SAMPLE_COLUMNS; column++) {
                int x = Math.min(width - 1, (column * 2 + 1) * width / (LUMA_SAMPLE_COLUMNS * 2));
                int offset = y * rowStride + x * pixelStride;
                float luma = buffer.get(offset) & 0xFF;
                samples[index++] = luma;
                total += luma;
            }
        }

        lastFrameLuma = total / samples.length;
        if (lastLumaSamples == null) {
            lastSceneMotionAt = now;
        } else {
            float totalDifference = 0f;
            for (int i = 0; i < samples.length; i++) {
                totalDifference += Math.abs(samples[i] - lastLumaSamples[i]);
            }
            if (totalDifference / samples.length >= SCENE_CHANGE_LUMA_THRESHOLD) {
                lastSceneMotionAt = now;
            }
        }
        lastLumaSamples = samples;
    }

    private String buildAdaptiveSearchGuidance(long now) {
        // Do not keep speaking one instruction if the camera condition remains unchanged.
        // Each search uses the next useful, unspoken recommendation instead.
        if (lastFrameLuma < LOW_LIGHT_LUMA_THRESHOLD && useSearchGuidance("low_light")) {
            return "The camera view looks dim. Move toward better lighting or turn on the flashlight, then continue scanning.";
        }
        if (lastSceneMotionAt > 0
                && now - lastSceneMotionAt >= STILL_CAMERA_GUIDANCE_MS
                && useSearchGuidance("still_camera")) {
            return "I have not noticed much camera movement. Slowly pan across the nearby area, then tilt the phone slightly up and down.";
        }

        long elapsed = Math.max(0L, now - searchStartedAt);
        if (elapsed < 20000L && useSearchGuidance("nearby_surfaces")) {
            return "I have not seen it in this view. Scan nearby surfaces slowly, keeping each area in view for a moment.";
        }
        if (elapsed < 40000L && useSearchGuidance("height_and_distance")) {
            return "Try changing the camera height and distance. Check both close surfaces and the area slightly farther away.";
        }
        if (useSearchGuidance("likely_placement")) {
            return "I still have not seen the " + targetObject + ". " + getLikelyPlacementHint(targetObject)
                + " Then try a different nearby area.";
        }
        if (useSearchGuidance("edges_and_corners")) {
            return "Try scanning the edges of nearby surfaces and corners, holding the camera steady briefly at each position.";
        }
        if (useSearchGuidance("alternate_view")) {
            return "I have checked several views without a match. Safely reposition and scan a different nearby area.";
        }

        // A minute-long search normally uses only a few reminders. Reset only after every
        // distinct recommendation was already provided, rather than repeating one message.
        usedSearchGuidanceKeys.clear();
        return buildAdaptiveSearchGuidance(now);
    }

    private boolean useSearchGuidance(String key) {
        return usedSearchGuidanceKeys.add(key);
    }

    /** Delivers the current contextual guidance without competing with camera inference. */
    private void speakAdaptiveGuidance(String event, String observation, String fallback,
                                       String category, long minimumIntervalMs) {
        if (voiceFeedback == null) return;
        voiceFeedback.speakAmbient(fallback, category, minimumIntervalMs);
    }

    private String getLikelyPlacementHint(String objectName) {
        if (objectName == null) return "Check the surfaces where you last used it.";
        switch (objectName.toLowerCase(Locale.US)) {
            case "backpack":
            case "wallet":
            case "key":
            case "phone":
            case "charger":
            case "remote":
            case "watch":
                return "Check a nearby table, desk, bag, or charging area.";
            case "comb":
            case "body spray":
            case "glasses":
            case "glasses case":
            case "medicine":
            case "body scrub":
                return "Check bathroom or dressing surfaces, shelves, and nearby hooks.";
            case "book":
            case "cup":
            case "water bottle":
            case "card":
            case "flashlight":
            case "nail clipper":
            case "shoe":
            case "headset":
            case "cane":
            case "lighter":
            case "towel":
                return "Check beside chairs, walls, doors, and the area where it is usually placed.";
            default:
                return "Check the surfaces where you last used it.";
        }
    }

    private void stopSearchAfterTimeout(String targetSnapshot) {
        // PROCESS 8A - NOT-FOUND FEEDBACK: End a 60-second unsuccessful search with visual, voice, and vibration output.
        if (currentState != DetectionState.SCANNING) return;
        invalidateDetectionSession();
        voiceTurnToken++;
        stopSearchReminder();
        stopHapticRadar();
        resetDetectionTracking();
        resetTrackingLock();
        targetObject = null;
        targetTFLabel = null;
        lastSpokenColor = null;
        lastSpokenLabel = null;
        currentState = DetectionState.IDLE;
        binding.tvStatusText.setText(getString(R.string.status_detection_timeout));
        binding.statusDot.setBackgroundResource(R.drawable.dot_white);
        binding.scanningContainer.setVisibility(View.GONE);
        binding.detectedResultPanel.setVisibility(View.GONE);
        updateTargetLabel(targetSnapshot == null || targetSnapshot.trim().isEmpty()
                ? ""
                : getString(R.string.target_not_found, targetSnapshot));
        hideBoundingBox();
        applyTorchForState();
        vibratePattern(220);
        speak(getString(R.string.tts_detection_timeout));
    }

    private Bitmap rotateBitmap(Bitmap src, int degrees) {
        if (degrees == 0) return src;
        android.graphics.Matrix matrix = new android.graphics.Matrix();
        matrix.postRotate(degrees);
        return Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), matrix, true);
    }

    private Bitmap applyLetterbox(Bitmap src, int targetWidth, int targetHeight) {
        float scale = Math.min((float) targetWidth / src.getWidth(), (float) targetHeight / src.getHeight());
        int scaledW = Math.round(src.getWidth() * scale);
        int scaledH = Math.round(src.getHeight() * scale);
        int padLeft = (targetWidth - scaledW) / 2;
        int padTop = (targetHeight - scaledH) / 2;

        Bitmap output = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        canvas.drawColor(Color.rgb(114, 114, 114));
        Bitmap scaled = Bitmap.createScaledBitmap(src, scaledW, scaledH, true);
        canvas.drawBitmap(scaled, padLeft, padTop, null);
        if (scaled != src) scaled.recycle();
        
        letterboxScale = scale; letterboxPadLeft = padLeft; letterboxPadTop = padTop;
        return output;
    }

    /**
     * Debug-only evidence of the exact image supplied to TFLite. Compare this image
     * in Colab with the Android raw-score Logcat output before changing the model.
     */
    private void maybeSaveModelInputDiagnostic(Bitmap modelInput, long now) {
        if (now - lastModelInputDiagnosticSaveAt < 5000L) return;
        lastModelInputDiagnosticSaveAt = now;
        File root = getExternalFilesDir("debug");
        if (root == null || (!root.exists() && !root.mkdirs())) return;
        File output = new File(root, "vocasee_model_input.png");
        try (FileOutputStream stream = new FileOutputStream(output)) {
            if (modelInput.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                Log.d(TAG, "Saved exact YOLO input: " + output.getAbsolutePath());
            }
        } catch (IOException error) {
            Log.w(TAG, "Could not save YOLO input diagnostic.", error);
        }
    }

    private Rect mapBoxToScreen(RectF box, int uprightW, int uprightH) {
        // First remove the model letterbox and clamp in source-image coordinates.
        // Clamping only after PreviewView scaling can turn an out-of-frame prediction
        // into an oversized edge-to-edge UI box.
        RectF sourceBox = mapModelBoxToSource(box, uprightW, uprightH);

        int screenW = binding.cameraPreview.getWidth();
        int screenH = binding.cameraPreview.getHeight();
        if (screenW == 0) screenW = getResources().getDisplayMetrics().widthPixels;
        if (screenH == 0) screenH = getResources().getDisplayMetrics().heightPixels;

        float scale = Math.max((float) screenW / uprightW, (float) screenH / uprightH);
        float previewW = uprightW * scale; float previewH = uprightH * scale;
        float offsetX = (screenW - previewW) / 2f; float offsetY = (screenH - previewH) / 2f;

        int left = Math.round(sourceBox.left * scale + offsetX);
        int top = Math.round(sourceBox.top * scale + offsetY);
        int right = Math.round(sourceBox.right * scale + offsetX);
        int bottom = Math.round(sourceBox.bottom * scale + offsetY);

        left = Math.max(0, Math.min(screenW, left));
        top = Math.max(0, Math.min(screenH, top));
        right = Math.max(0, Math.min(screenW, right));
        bottom = Math.max(0, Math.min(screenH, bottom));

        return new Rect(left, top, right, bottom);
    }

    private RectF mapModelBoxToSource(RectF box, int sourceWidth, int sourceHeight) {
        float left = (box.left - letterboxPadLeft) / letterboxScale;
        float top = (box.top - letterboxPadTop) / letterboxScale;
        float right = (box.right - letterboxPadLeft) / letterboxScale;
        float bottom = (box.bottom - letterboxPadTop) / letterboxScale;
        return new RectF(
                Math.max(0f, Math.min(sourceWidth, left)),
                Math.max(0f, Math.min(sourceHeight, top)),
                Math.max(0f, Math.min(sourceWidth, right)),
                Math.max(0f, Math.min(sourceHeight, bottom)));
    }

    private String getDominantColorName(Bitmap sourceFrame, RectF box) {
        // Use the inner object area. Detector boxes include background, especially near their edges.
        if (sourceFrame == null || sourceFrame.isRecycled() || box == null) return COLOR_UNCLEAR;
        if (!openCvReady) return getFallbackColorName(sourceFrame, box);
        int left = Math.max(0, Math.round(box.left));
        int top = Math.max(0, Math.round(box.top));
        int right = Math.min(sourceFrame.getWidth(), Math.round(box.right));
        int bottom = Math.min(sourceFrame.getHeight(), Math.round(box.bottom));
        int w = right - left;
        int h = bottom - top;
        if (w <= 4 || h <= 4) return getFallbackColorName(sourceFrame, box);

        Mat rgba = new Mat();
        Mat rgb = new Mat();
        Mat correctedRgb = null;
        Mat lab = new Mat();
        Mat samples = null;
        Mat labels = new Mat();
        Mat centers = new Mat();
        Bitmap objectCrop = null;

        try {
            // Converting the crop instead of the full frame avoids processing unrelated pixels.
            objectCrop = Bitmap.createBitmap(sourceFrame, left, top, w, h);
            Utils.bitmapToMat(objectCrop, rgba);
            Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB);

            int steps = 18;
            int maxSamples = steps * steps;
            int[][] sampleRgb = new int[maxSamples][3];
            int sampleCount = 0;
            float[] hsv = new float[3];
            float cx = w / 2f;
            float cy = h / 2f;
            float rx = Math.max(1f, w * COLOR_INNER_ELLIPSE_RADIUS);
            float ry = Math.max(1f, h * COLOR_INNER_ELLIPSE_RADIUS);

            for (int row = 0; row < steps; row++) {
                for (int col = 0; col < steps; col++) {
                    int px = Math.max(0, Math.min(w - 1, Math.round((col + 0.5f) * w / steps)));
                    int py = Math.max(0, Math.min(h - 1, Math.round((row + 0.5f) * h / steps)));
                    float nx = (px - cx) / rx;
                    float ny = (py - cy) / ry;
                    if (nx * nx + ny * ny > 1f) continue;

                    double[] pixel = rgb.get(py, px);
                    if (pixel == null || pixel.length < 3) continue;
                    int r = clamp255(pixel[0]);
                    int g = clamp255(pixel[1]);
                    int b = clamp255(pixel[2]);
                    Color.RGBToHSV(r, g, b, hsv);
                    if (hsv[2] < COLOR_DARK_PIXEL_THRESHOLD) continue;

                    sampleRgb[sampleCount][0] = r;
                    sampleRgb[sampleCount][1] = g;
                    sampleRgb[sampleCount][2] = b;
                    sampleCount++;
                }
            }

            if (sampleCount < COLOR_MIN_SAMPLES) return getFallbackColorName(sourceFrame, box);

            // Do not white-balance from the object's own pixels. That turns genuinely
            // red/blue/green objects toward gray. Cluster the sampled camera colors as-is.
            correctedRgb = new Mat(sampleCount, 1, CvType.CV_8UC3);
            int correctedCount = 0;
            int brightNeutralCount = 0;
            byte[] correctedPixel = new byte[3];
            for (int i = 0; i < sampleCount; i++) {
                int r = sampleRgb[i][0];
                int g = sampleRgb[i][1];
                int b = sampleRgb[i][2];
                Color.RGBToHSV(r, g, b, hsv);
                if (hsv[2] < COLOR_DARK_PIXEL_THRESHOLD) continue;
                if (isBrightNeutralPixel(r, g, b)) brightNeutralCount++;
                correctedPixel[0] = (byte) r;
                correctedPixel[1] = (byte) g;
                correctedPixel[2] = (byte) b;
                correctedRgb.put(correctedCount, 0, correctedPixel);
                correctedCount++;
            }

            if (correctedCount < COLOR_MIN_SAMPLES) return getFallbackColorName(sourceFrame, box);

            // A substantial, neutral bright center is white, not "gray in shadow".
            if (brightNeutralCount >= Math.max(3, Math.round(correctedCount * 0.30f))) {
                return "white";
            }

            Mat correctedView = correctedRgb.rowRange(0, correctedCount);
            Imgproc.cvtColor(correctedView, lab, Imgproc.COLOR_RGB2Lab);
            samples = new Mat(correctedCount, 3, CvType.CV_32F);
            float[] labPixel = new float[3];
            for (int i = 0; i < correctedCount; i++) {
                double[] labValue = lab.get(i, 0);
                if (labValue == null || labValue.length < 3) continue;
                labPixel[0] = (float) labValue[0];
                labPixel[1] = (float) labValue[1];
                labPixel[2] = (float) labValue[2];
                samples.put(i, 0, labPixel);
            }

            TermCriteria criteria = new TermCriteria(
                    TermCriteria.EPS + TermCriteria.MAX_ITER, 8, 1.0);
            Core.kmeans(samples, 3, labels, criteria, 1, Core.KMEANS_PP_CENTERS, centers);
            String clusteredColor = nameDominantOpenCvLabCluster(labels, centers, correctedCount);
            return isStableColorForFeedback(clusteredColor)
                    ? clusteredColor
                    : COLOR_UNCLEAR;
        } catch (Exception e) {
            Log.w(TAG, "OpenCV color detection failed", e);
            return getFallbackColorName(sourceFrame, box);
        } finally {
            rgba.release();
            rgb.release();
            if (correctedRgb != null) correctedRgb.release();
            lab.release();
            if (samples != null) samples.release();
            labels.release();
            centers.release();
            if (objectCrop != null && !objectCrop.isRecycled()) objectCrop.recycle();
        }
    }

    private boolean isBrightNeutralPixel(int r, int g, int b) {
        int max = Math.max(r, Math.max(g, b));
        int min = Math.min(r, Math.min(g, b));
        return min >= COLOR_WHITE_CHANNEL_MIN && max - min <= COLOR_WHITE_MAX_CHANNEL_SPREAD;
    }

    private String nameDominantOpenCvLabCluster(Mat labels, Mat centers, int sampleCount) {
        int[] counts = new int[3];
        for (int i = 0; i < sampleCount; i++) {
            double[] label = labels.get(i, 0);
            if (label == null || label.length == 0) continue;
            int index = Math.max(0, Math.min(2, (int) label[0]));
            counts[index]++;
        }

        int best = -1;
        int minClusterSize = Math.max(COLOR_MIN_CLUSTER_SAMPLES, Math.round(sampleCount * 0.35f));
        for (int c = 0; c < 3; c++) {
            if (counts[c] < minClusterSize) continue;
            if (best < 0 || counts[c] > counts[best]) best = c;
        }
        if (best < 0) return COLOR_UNCLEAR;
        double[] center = centers.get(best, 0);
        if (center == null || center.length < 3) return COLOR_UNCLEAR;
        double[] lab = {
                center[0] * 100.0 / 255.0,
                center[1] - 128.0,
                center[2] - 128.0
        };
        return nearestColorNameFromLab(lab);
    }

    private String nearestColorNameFromLab(double[] lab) {
        double chroma = Math.sqrt(lab[1] * lab[1] + lab[2] * lab[2]);
        if (chroma < 8.5) {
            if (lab[0] < 28) return "black";
            if (lab[0] > 82) return "white";
            return lab[0] > 66 ? "silver" : "gray";
        }

        String[] names = {"red", "blue", "green", "yellow", "orange", "brown", "purple", "pink", "beige"};
        int[][] rgb = {
                {200, 45, 45}, {55, 95, 210}, {45, 150, 75},
                {230, 195, 45}, {225, 125, 35}, {125, 78, 42},
                {130, 75, 175}, {225, 115, 165}, {205, 180, 135}
        };
        String best = COLOR_UNCLEAR;
        double bestDistance = Double.MAX_VALUE;
        for (int i = 0; i < names.length; i++) {
            double distance = labDistance(lab, rgbToLab(rgb[i][0], rgb[i][1], rgb[i][2]));
            if (distance < bestDistance) {
                bestDistance = distance;
                best = names[i];
            }
        }
        // Basic color names are intentionally coarse. If the sampled color is far
        // from every supported category, do not confidently guess a label.
        return bestDistance <= 36.0 ? best : COLOR_UNCLEAR;
    }

    private String updateStableColorName(String rawColor) {
        if (rawColor == null || COLOR_UNCLEAR.equals(rawColor)) {
            return stableColorName;
        }
        recentColorHistory.add(rawColor);
        while (recentColorHistory.size() > COLOR_HISTORY_SIZE) {
            recentColorHistory.remove(0);
        }

        Map<String, Integer> counts = new HashMap<>();
        for (String color : recentColorHistory) {
            counts.put(color, counts.getOrDefault(color, 0) + 1);
        }
        String bestColor = stableColorName;
        int bestCount = 0;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > bestCount) {
                bestCount = entry.getValue();
                bestColor = entry.getKey();
            }
        }
        if (bestCount >= COLOR_STABLE_MIN_COUNT
                || (recentColorHistory.size() < COLOR_HISTORY_SIZE && bestCount >= 3)) {
            stableColorName = bestColor;
        }
        if (COLOR_UNCLEAR.equals(stableColorName) && !recentColorHistory.isEmpty()) {
            stableColorName = rawColor;
        }
        return stableColorName;
    }

    private String getFallbackColorName(Bitmap frame640, RectF box) {
        if (frame640 == null || frame640.isRecycled() || box == null) return COLOR_UNCLEAR;

        int frameW = frame640.getWidth();
        int frameH = frame640.getHeight();
        if (frameW <= 0 || frameH <= 0) return COLOR_UNCLEAR;

        float insetX = Math.max(1f, box.width() * 0.22f);
        float insetY = Math.max(1f, box.height() * 0.22f);
        int left = Math.max(0, Math.min(frameW - 1, Math.round(box.left + insetX)));
        int top = Math.max(0, Math.min(frameH - 1, Math.round(box.top + insetY)));
        int right = Math.max(left + 1, Math.min(frameW, Math.round(box.right - insetX)));
        int bottom = Math.max(top + 1, Math.min(frameH, Math.round(box.bottom - insetY)));
        if (right <= left || bottom <= top) {
            left = Math.max(0, Math.min(frameW - 1, Math.round(box.left)));
            top = Math.max(0, Math.min(frameH - 1, Math.round(box.top)));
            right = Math.max(left + 1, Math.min(frameW, Math.round(box.right)));
            bottom = Math.max(top + 1, Math.min(frameH, Math.round(box.bottom)));
        }
        if (right <= left || bottom <= top) return COLOR_UNCLEAR;

        int[] counts = new int[13];
        int samples = 0;
        int brightNeutralCount = 0;
        float[] hsv = new float[3];
        int steps = 14;
        float cx = (left + right) / 2f;
        float cy = (top + bottom) / 2f;
        float rx = Math.max(1f, (right - left) * COLOR_INNER_ELLIPSE_RADIUS);
        float ry = Math.max(1f, (bottom - top) * COLOR_INNER_ELLIPSE_RADIUS);
        for (int row = 0; row < steps; row++) {
            for (int col = 0; col < steps; col++) {
                int px = Math.max(0, Math.min(frameW - 1,
                        left + Math.round((col + 0.5f) * (right - left) / steps)));
                int py = Math.max(0, Math.min(frameH - 1,
                        top + Math.round((row + 0.5f) * (bottom - top) / steps)));
                float nx = (px - cx) / rx;
                float ny = (py - cy) / ry;
                if (nx * nx + ny * ny > 1f) continue;
                int pixel = frame640.getPixel(px, py);
                int r = Color.red(pixel);
                int g = Color.green(pixel);
                int b = Color.blue(pixel);
                Color.RGBToHSV(r, g, b, hsv);
                if (hsv[2] < COLOR_DARK_PIXEL_THRESHOLD) continue;
                counts[colorBucketIndex(r, g, b, hsv[0], hsv[1], hsv[2])]++;
                if (isBrightNeutralPixel(r, g, b)) brightNeutralCount++;
                samples++;
            }
        }

        if (samples == 0) {
            return COLOR_UNCLEAR;
        }

        if (brightNeutralCount >= Math.max(3, Math.round(samples * 0.30f))) {
            return "white";
        }

        int best = 0;
        for (int i = 1; i < counts.length; i++) {
            if (counts[i] > counts[best]) best = i;
        }
        String[] names = {
                "black", "white", "gray", "red", "blue", "green", "yellow",
                "orange", "brown", "purple", "pink", "beige", "silver"
        };
        String votedColor = names[best];
        int voteCount = counts[best];
        if (voteCount < Math.max(5, Math.round(samples * 0.35f))) return COLOR_UNCLEAR;
        return votedColor;
    }

    private String colorNameForBucket(int bucket) {
        String[] names = {
                "black", "white", "gray", "red", "blue", "green", "yellow",
                "orange", "brown", "purple", "pink", "beige", "silver"
        };
        if (bucket < 0 || bucket >= names.length) return COLOR_UNCLEAR;
        return names[bucket];
    }

    private int colorBucketIndex(int r, int g, int b, float hue, float sat, float val) {
        if (val < 0.20f) return 0;
        if (sat < 0.12f && val > 0.80f) return 1;
        if (sat < 0.15f) return val > 0.64f ? 12 : 2;
        if (hue >= 25f && hue <= 55f && val > 0.55f && sat < 0.45f && r >= g && g >= b) return 11;
        if (hue >= 18f && hue <= 45f && val < 0.65f) return 8;
        if (hue < 15f || hue >= 345f) return 3;
        if (hue < 35f) return 7;
        if (hue < 75f) return 6;
        if (hue < 165f) return 5;
        if (hue < 255f) return 4;
        if (hue < 295f) return 9;
        if (hue < 345f) return 10;
        return 2;
    }

    private void resetColorStability() {
        recentColorHistory.clear();
        stableColorName = COLOR_UNCLEAR;
    }

    private double[] rgbToLab(int r, int g, int b) {
        double sr = pivotRgb(r / 255.0);
        double sg = pivotRgb(g / 255.0);
        double sb = pivotRgb(b / 255.0);

        double x = (sr * 0.4124564 + sg * 0.3575761 + sb * 0.1804375) / 0.95047;
        double y = (sr * 0.2126729 + sg * 0.7151522 + sb * 0.0721750);
        double z = (sr * 0.0193339 + sg * 0.1191920 + sb * 0.9503041) / 1.08883;

        double fx = pivotXyz(x);
        double fy = pivotXyz(y);
        double fz = pivotXyz(z);
        return new double[] {
                Math.max(0, 116 * fy - 16),
                500 * (fx - fy),
                200 * (fy - fz)
        };
    }

    private double pivotRgb(double value) {
        return value <= 0.04045 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4);
    }

    private double pivotXyz(double value) {
        return value > 0.008856 ? Math.cbrt(value) : (7.787 * value) + (16.0 / 116.0);
    }

    private double labDistance(double[] a, double[] b) {
        double dl = a[0] - b[0];
        double da = a[1] - b[1];
        double db = a[2] - b[2];
        return Math.sqrt(dl * dl + da * da + db * db);
    }

    private int clamp255(double value) {
        return (int) Math.max(0, Math.min(255, Math.round(value)));
    }

    private void showBoundingBox(Rect rect, String label, float confidence) {
        // PROCESS 8B - VISUAL FEEDBACK: Draw the target box and optionally show its confidence percentage.
        runOnUiThread(() -> {
            Rect visualBox = getTightVisualBox(rect);
            ViewGroup.LayoutParams p = binding.detectionBox.getLayoutParams();
            p.width = Math.max(1, visualBox.width()); p.height = Math.max(1, visualBox.height());
            binding.detectionBox.setLayoutParams(p);
            binding.detectionBox.setTranslationX(visualBox.left); binding.detectionBox.setTranslationY(visualBox.top);
            binding.detectionBox.setVisibility(View.VISIBLE);
            
            if (AppPreferences.isConfidenceOverlayEnabled(this)) {
                int percent = Math.round(confidence * 100f);
                binding.detectionLabel.setText(capitalize(label) + " " + percent + "%");
            } else {
                binding.detectionLabel.setText(capitalize(label));
            }
            binding.detectionLabel.setTranslationX(visualBox.left);
            binding.detectionLabel.setTranslationY(Math.max(0, visualBox.top - 50));
            binding.detectionLabel.setVisibility(View.VISIBLE);
        });
    }

    private Rect getTightVisualBox(Rect source) {
        if (source == null || source.isEmpty()) return new Rect();
        int insetX = Math.max(0, Math.round(source.width() * VISUAL_BOX_INSET_RATIO));
        int insetY = Math.max(0, Math.round(source.height() * VISUAL_BOX_INSET_RATIO));
        // Never reduce a small valid object box to an unusable sliver.
        insetX = Math.min(insetX, Math.max(0, (source.width() - dp(24)) / 2));
        insetY = Math.min(insetY, Math.max(0, (source.height() - dp(24)) / 2));
        return new Rect(source.left + insetX, source.top + insetY,
                source.right - insetX, source.bottom - insetY);
    }

    private void hideBoundingBox() {
        runOnUiThread(() -> {
            hideLiveBoundingBox();
            binding.handGuidanceOverlay.clear();
        });
    }

    /** Hides only the stale visual detector overlay; hand guidance may still continue. */
    private void hideLiveBoundingBox() {
        binding.detectionBox.setVisibility(View.GONE);
        binding.detectionLabel.setVisibility(View.GONE);
    }

    // ─── SPEECH RECOGNITION ───────────────────────────────────────────────

    private void startListening(TextView tvVoiceInput, long myTurn) {
        // PROCESS 2 - VOICE INPUT: Android SpeechRecognizer converts the spoken request or confirmation to text.
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(() -> startListening(tvVoiceInput, myTurn));
            return;
        }
        if (myTurn != voiceTurnToken) return;
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            tvVoiceInput.setText(getString(R.string.voice_recognition_unavailable));
            return;
        }

        destroySpeechRecognizer();
        userSpeechStarted = false;
        recognizerReadyForSpeech = false;
        final long attemptToken = ++recognizerAttemptToken;
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US.toString());
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT,
                voiceMode == VoiceMode.CONFIRMATION ? "Say yes or no" : "Say one object name");
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 900L);
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L);
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L);

        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) {
                if (myTurn != voiceTurnToken || attemptToken != recognizerAttemptToken) return;
                recognizerReadyForSpeech = true;
                runOnUiThread(() -> tvVoiceInput.setText(
                        voiceMode == VoiceMode.CONFIRMATION
                                ? "Listening... say yes or no"
                                : getString(R.string.voice_listening_hint)));
            }
            @Override public void onPartialResults(Bundle partialResults) {
                if (myTurn != voiceTurnToken || attemptToken != recognizerAttemptToken) return;
                ArrayList<String> partial = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (partial != null && !partial.isEmpty()) {
                    userSpeechStarted = true;
                    // Partial hypotheses change repeatedly and can make TalkBack
                    // interrupt the speaker on every recognition update.
                    if (voiceFeedback == null || !voiceFeedback.isTalkBackActive()) {
                        runOnUiThread(() -> tvVoiceInput.setText(partial.get(0)));
                    }
                }
            }
            @Override public void onResults(Bundle results) {
                if (isProcessingResult) return;
                if (myTurn != voiceTurnToken || attemptToken != recognizerAttemptToken) return;
                ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (matches != null && !matches.isEmpty()) {
                    isProcessingResult = true;
                    String spoken = voiceMode == VoiceMode.CONFIRMATION
                            ? resolveConfirmationSpeech(matches)
                            : chooseBestSpeechMatch(matches);
                    runOnUiThread(() -> {
                        if (voiceModal != null) voiceModal.dismiss();
                        handleVoiceResult(spoken);
                    });
                } else {
                    retryActiveListening(tvVoiceInput, getString(R.string.voice_waiting), myTurn);
                }
            }
            @Override public void onError(int error) {
                if (myTurn != voiceTurnToken || attemptToken != recognizerAttemptToken) return;
                isProcessingResult = false;
                if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                    tvVoiceInput.setText(getString(R.string.voice_permission_required));
                    return;
                }

                String message = userSpeechStarted
                        ? getString(R.string.voice_try_again)
                        : getString(R.string.voice_waiting);
                retryActiveListening(tvVoiceInput, message, myTurn);
            }
            @Override public void onBeginningOfSpeech() {
                if (myTurn != voiceTurnToken || attemptToken != recognizerAttemptToken) return;
                recognizerReadyForSpeech = true;
                userSpeechStarted = true;
                runOnUiThread(() -> tvVoiceInput.setText(
                        voiceMode == VoiceMode.CONFIRMATION
                                ? "Listening..."
                                : getString(R.string.voice_listening_object)));
            }
            @Override public void onRmsChanged(float rmsdB) {
                runOnUiThread(() -> updateVoiceWaveBars(rmsdB));
            }
            @Override public void onBufferReceived(byte[] buffer) {}
            @Override public void onEndOfSpeech() {
                runOnUiThread(() -> tvVoiceInput.setText(getString(R.string.voice_processing)));
            }
            @Override public void onEvent(int eventType, Bundle params) {}
        });
        try {
            speechRecognizer.startListening(intent);
        } catch (RuntimeException error) {
            Log.w(TAG, "Speech recognizer could not start; retrying.", error);
            retryActiveListening(tvVoiceInput, getString(R.string.voice_waiting), myTurn);
            return;
        }
        mainHandler.postDelayed(() -> {
            if (attemptToken == recognizerAttemptToken
                    && myTurn == voiceTurnToken
                    && !recognizerReadyForSpeech) {
                retryActiveListening(tvVoiceInput, getString(R.string.voice_waiting), myTurn);
            }
        }, MIC_READY_TIMEOUT_MS);
    }

    private void retryActiveListening(TextView tvVoiceInput, String message, long myTurn) {
        runOnUiThread(() -> {
            if (voiceModal == null || !voiceModal.isShowing() || myTurn != voiceTurnToken) return;
            tvVoiceInput.setText(message);
            long turnAtRetry = voiceTurnToken;
            destroySpeechRecognizer();
            mainHandler.postDelayed(() -> {
                if (voiceModal != null && voiceModal.isShowing() && turnAtRetry == voiceTurnToken) {
                    startListening(tvVoiceInput, turnAtRetry);
                }
            }, MIC_RETRY_DELAY_MS);
        });
    }

    private void openVoiceModal() {
        if (Looper.myLooper() != Looper.getMainLooper()) { mainHandler.post(this::openVoiceModal); return; }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return;

        cancelActiveSearch();
        openVoiceModalWithoutCancellingSearch(VoiceMode.OBJECT);
    }

    private void openVoiceModalWithoutCancellingSearch(VoiceMode mode) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(() -> openVoiceModalWithoutCancellingSearch(mode));
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return;

        this.voiceMode = mode;
        final long myTurn = ++voiceTurnToken;
        isProcessingResult = false;
        if (voiceFeedback != null) voiceFeedback.stopAll();
        View modalView = LayoutInflater.from(this).inflate(R.layout.dialog_voice_modal, binding.getRoot(), false);
        String paneTitle = getString(mode == VoiceMode.CONFIRMATION
                ? R.string.voice_confirmation_title : R.string.voice_search_title);
        ViewCompat.setAccessibilityPaneTitle(modalView, paneTitle);
        TextView tvVoiceInput = modalView.findViewById(R.id.tvVoiceInput);
        View btnCancel = modalView.findViewById(R.id.btnVoiceCancel);
        bindVoiceWaveBars(modalView);
        voiceModal = new BottomSheetDialog(this);
        voiceModal.setTitle(paneTitle);
        voiceModal.setContentView(modalView);
        voiceModal.setCancelable(false);
        voiceModal.show();
        playSoundEffect(ToneGenerator.TONE_PROP_BEEP);
        vibratePattern(60);

        btnCancel.setOnClickListener(v -> {
            stopListening();
            voiceModal.dismiss();
        });

        voiceModal.setOnDismissListener(d -> {
            stopListening();
            voiceWaveBars = null;
        });

        tvVoiceInput.setText(getMicPreparationText());
        mainHandler.postDelayed(() -> {
            if (voiceModal != null && voiceModal.isShowing() && myTurn == voiceTurnToken) {
                startListening(tvVoiceInput, myTurn);
            }
        }, getMicStartDelayMs());
    }

    private long getMicStartDelayMs() {
        return voiceFeedback != null && voiceFeedback.isTalkBackActive()
                ? TALKBACK_MIC_START_DELAY_MS : MIC_START_DELAY_MS;
    }

    private String getMicPreparationText() {
        if (voiceFeedback != null && voiceFeedback.isTalkBackActive()) {
            return voiceMode == VoiceMode.CONFIRMATION
                    ? "Then say yes or no."
                    : "Then say an object name.";
        }
        return getString(R.string.voice_preparing);
    }

    private void cancelActiveSearch() {
        invalidateDetectionSession();
        voiceTurnToken++;
        stopSearchReminder();
        stopHapticRadar();
        resetDetectionTracking();
        resetTrackingLock();
        targetObject = null;
        targetTFLabel = null;
        lastSpokenColor = null;
        lastSpokenLabel = null;
        currentState = DetectionState.IDLE;
        runOnUiThread(() -> {
            binding.tvStatusText.setText(getString(R.string.status_idle));
            binding.scanningContainer.setVisibility(View.GONE);
            binding.detectedResultPanel.setVisibility(View.GONE);
            updateTargetLabel("");
            hideBoundingBox();
            applyTorchForState();
        });
    }

    private void resetDetectionTracking() {
        stopSearchReminder();
        lastDetectionSeenTime = 0;
        consecutiveMissedDetections = 0;
        lastMoveCloserTts = 0;
        lastNotYetVisibleTts = 0;
        lastSceneMotionAt = 0;
        lastFrameLuma = 255f;
        lastLumaSamples = null;
        usedSearchGuidanceKeys.clear();
        searchStartedAt = 0;
        hasSpokenSearchTimeout = false;
        hasDetectedTargetInCurrentSearch = false;
        hapticRadarWidthRatio = 0f;
        lastHandSeenTime = SystemClock.elapsedRealtime();
        lastNoHandFallbackTts = 0;
        lastKnownTrackingBox = null;
        handGuidanceAnchorBox = null;
        lastKnownTrackingLabel = null;
        lastKnownTrackingConfidence = 0f;
        lastKnownTrackingProximity = 0f;
        lastKnownTrackingWidthRatio = 0f;
        lastKnownHandBox = null;
        resetColorStability();
        lastHandDetectionTime = 0;
        lastResultPanelUpdateTime = 0;
        lastOcrScanTime = 0;
        lastColorSampleTime = 0;
        lastOcrText = null;
        lastSpokenOcrText = null;
        announcedFoundColor = null;
        announcedFoundText = null;
        foundFeedbackStartedAt = 0;
        ocrInFlight = false;
        lastHandOcclusionTts = 0;
        handContactStartedAt = 0;
        lastLiveHandContactAt = 0;
        lastConfirmedHandOcclusionAt = 0;
        handGuidanceOwnsVibration = false;
        handGuidanceIntroductionSpoken = false;
        lastHandGuidanceText = null;
        reachInstructionSent = false;
        lastSpokenApproachBand = null;
        radarRetrievalStartedAt = 0;
        lastReachInstructionAt = 0;
    }

    private void resetTrackingLock() {
        isTrackingLocked = false;
        hasAskedHoldingConfirmation = false;
        awaitingHoldingConfirmation = false;
        awaitingFindAnotherConfirmation = false;
        lastHoldingConfirmationPrompt = 0;
        holdingConfirmationAttempts = 0;
        handContactStartedAt = 0;
        lastLiveHandContactAt = 0;
        radarRetrievalStartedAt = 0;
        lastReachInstructionAt = 0;
    }

    private void completeLockedTracking() {
        // PROCESS 9 - SEARCH COMPLETION: Stop tracking after the user confirms the presented object.
        invalidateDetectionSession();
        voiceTurnToken++;
        stopSearchReminder();
        stopHapticRadar();
        resetDetectionTracking();
        resetTrackingLock();
        targetObject = null;
        targetTFLabel = null;
        lastSpokenColor = null;
        lastSpokenLabel = null;
        currentState = DetectionState.IDLE;
        runOnUiThread(() -> {
            binding.tvStatusText.setText(getString(R.string.status_idle));
            binding.scanningContainer.setVisibility(View.GONE);
            binding.detectedResultPanel.setVisibility(View.GONE);
            updateTargetLabel("");
            hideBoundingBox();
            applyTorchForState();
        });
    }

    private void bindVoiceWaveBars(View modalView) {
        voiceWaveBars = new View[] {
                modalView.findViewById(R.id.wave1),
                modalView.findViewById(R.id.wave2),
                modalView.findViewById(R.id.wave3),
                modalView.findViewById(R.id.wave4),
                modalView.findViewById(R.id.wave5),
                modalView.findViewById(R.id.wave6),
                modalView.findViewById(R.id.wave7),
                modalView.findViewById(R.id.wave8),
                modalView.findViewById(R.id.wave9),
                modalView.findViewById(R.id.wave10)
        };
        updateVoiceWaveBars(0f);
    }

    private void updateVoiceWaveBars(float rmsDb) {
        if (voiceWaveBars == null) return;
        float normalized = Math.max(0f, Math.min(1f, (rmsDb + 2f) / 12f));
        for (int i = 0; i < voiceWaveBars.length; i++) {
            float wave = (float) Math.sin((SystemClock.uptimeMillis() / 115f) + i * 0.9f);
            float scale = 0.35f + normalized * (0.65f + 0.25f * wave);
            View bar = voiceWaveBars[i];
            ViewGroup.LayoutParams params = bar.getLayoutParams();
            params.height = dp(Math.max(12, Math.round(16 + (46 * scale))));
            bar.setLayoutParams(params);
            bar.setAlpha(0.45f + normalized * 0.55f);
        }
    }

    private void handleVoiceResult(String spoken) {
        // PROCESS 3A - VOICE ROUTING: Interpret confirmation answers, settings commands, or a new object request.
        String lower = normalizeSpokenCommand(spoken);

        if (awaitingHoldingConfirmation) {
            awaitingHoldingConfirmation = false;
            if (isAffirmative(lower)) {
                completeLockedTracking();
                awaitingFindAnotherConfirmation = true;
                speakThenListen("Awesome! Would you like to find something else?",
                        "find_another_confirmation_prompt", VoiceMode.CONFIRMATION);
                return;
            }
            if (isNegative(lower)) {
                lastHoldingConfirmationPrompt = SystemClock.elapsedRealtime();
                speak("No worries, I'll keep tracking it for you.");
                return;
            }
            awaitingHoldingConfirmation = true;
            speakThenListen("I did not catch that. Is this the " + targetObject
                            + " you are looking for?",
                    "holding_confirmation_prompt", VoiceMode.CONFIRMATION);
            return;
        }

        if (awaitingFindAnotherConfirmation) {
            awaitingFindAnotherConfirmation = false;
            if (isAffirmative(lower)) {
                speakThenListen("Awesome, what object would you like to find?",
                        "find_another_object_prompt", VoiceMode.OBJECT);
                return;
            }
            if (isNegative(lower)) {
                speak("Got it. Let me know whenever you're ready to search again.");
                return;
            }
            awaitingFindAnotherConfirmation = true;
            speakThenListen("Would you like to find something else?",
                    "find_another_confirmation_prompt", VoiceMode.CONFIRMATION);
            return;
        }

        if (awaitingRepeatConfirmation) {
            awaitingRepeatConfirmation = false;
            if ((lower.contains("yes") || lower.contains("yeah") || lower.contains("sure"))
                    && suggestedRepeatObject != null) {
                startSearchForObject(suggestedRepeatObject);
                return;
            }
            if (lower.contains("no") || lower.contains("not")) {
                speak("No problem. What would you like to look for instead?");
                mainHandler.postDelayed(() -> openVoiceModalWithoutCancellingSearch(VoiceMode.OBJECT), 900);
                return;
            }
        }

        if (awaitingAnotherConfirmation) {
            awaitingAnotherConfirmation = false;
            if (lower.contains("yes") || lower.contains("yeah")) {
                speak(getString(R.string.tts_ready));
                return;
            } else {
                setDetectionState(DetectionState.IDLE);
                speak(getString(R.string.tts_instructions_restart));
                return;
            }
        }

        if (lower.contains("settings")) {
            startActivity(new Intent(this, SettingsActivity.class));
            return;
        }

        String resolved = resolveAlias(lower);
        String matched = null;
        startSearchForObject(resolved);
    }

    private String chooseBestSpeechMatch(ArrayList<String> matches) {
        for (String candidate : matches) {
            String normalized = normalizeSpokenCommand(candidate);
            if (normalized.contains("settings")) return normalized;
            String resolved = resolveAlias(normalized);
            for (String obj : ObjectCatalog.PREDEFINED_OBJECTS) {
                if (resolved.equalsIgnoreCase(obj) || resolved.contains(obj)) {
                    return resolved;
                }
            }
        }
        return normalizeSpokenCommand(matches.get(0));
    }

    private String resolveConfirmationSpeech(ArrayList<String> matches) {
        for (String candidate : matches) {
            String normalized = normalizeSpokenCommand(candidate);
            if (isAffirmative(normalized) || isNegative(normalized)) {
                return normalized;
            }
        }
        return normalizeSpokenCommand(matches.get(0));
    }

    private String normalizeSpokenCommand(String spoken) {
        String lower = spoken == null ? "" : spoken.toLowerCase(Locale.US).trim();
        return lower
                .replace("please", "")
                .replace("find my", "")
                .replace("find the", "")
                .replace("find a", "")
                .replace("find", "")
                .replace("look for my", "")
                .replace("look for the", "")
                .replace("look for", "")
                .replace("search for my", "")
                .replace("search for the", "")
                .replace("search for", "")
                .replace("where is my", "")
                .replace("where is the", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private boolean isAffirmative(String lower) {
        return lower.contains("yes") || lower.contains("yeah") || lower.contains("yep")
                || lower.contains("sure") || lower.contains("okay") || lower.contains("ok")
                || lower.contains("i am") || lower.contains("got it") || lower.contains("holding");
    }

    private boolean isNegative(String lower) {
        return lower.contains("no") || lower.contains("not") || lower.contains("nope")
                || lower.contains("don't") || lower.contains("do not");
    }

    private void startSearchForObject(String spoken) {
        // PROCESS 3B - REQUEST VALIDATION: Resolve aliases, reject unsupported classes, or start target-only scanning.
        invalidateDetectionSession();
        String lower = spoken == null ? "" : spoken.toLowerCase().trim();
        if (isAffirmative(lower) || isNegative(lower)) {
            return;
        }

        String resolved = resolveAlias(lower);
        String matched = null;
        for (String obj : ObjectCatalog.PREDEFINED_OBJECTS) {
            if (resolved.equalsIgnoreCase(obj) || resolved.contains(obj)) { matched = obj; break; }
        }
        stopHapticRadar();
        resetDetectionTracking();
        resetTrackingLock();
        if (voiceFeedback != null) voiceFeedback.resetDedupState();

        if (matched == null) {
            targetObject = spoken; targetTFLabel = null;
            setDetectionState(DetectionState.NOT_IN_CLASSES);
            vibratePattern(80, 80, 80, 80, 80);
            speak(getString(R.string.tts_not_predefined, spoken));
            return;
        }

        if (detector == null) {
            setDetectionState(DetectionState.IDLE);
            speak(detectorLoadFailed
                    ? "Detection is unavailable. Please restart the app and try again."
                    : "The detection model is still preparing. Please try again in a moment.");
            return;
        }

        targetObject = matched; targetTFLabel = toTFLiteLabel(matched);
        AppPreferences.addRecentSearch(this, matched);
        invalidateDetectionSession();
        searchStartedAt = SystemClock.elapsedRealtime();
        hasSpokenSearchTimeout = false;
        hasDetectedTargetInCurrentSearch = false;
        setDetectionState(DetectionState.SCANNING);
        speak(getString(R.string.tts_scanning, matched));
        startSearchReminder();
        startCamera();
    }

    private String resolveAlias(String spoken) {
        for (String[] row : ALIASES) {
            if (spoken.contains(row[0])) return row[1];
        }
        return spoken;
    }

    private String toTFLiteLabel(String canonical) {
        return canonical;
    }

    private void setDetectionState(DetectionState state) {
        currentState = state;
        runOnUiThread(() -> {
            switch (state) {
                case IDLE:
                    stopHapticRadar();
                    binding.tvStatusText.setText(getString(R.string.status_idle));
                    binding.statusDot.setBackgroundResource(R.drawable.dot_white);
                    binding.scanningContainer.setVisibility(View.GONE);
                    updateTargetLabel("");
                    binding.detectedResultPanel.setVisibility(View.GONE);
                    hideBoundingBox();
                    applyTorchForState();
                    break;
                case SCANNING:
                    stopHapticRadar();
                    binding.tvStatusText.setText(getString(R.string.status_scanning, targetObject));
                    binding.tvScanningLabel.setText(getString(R.string.status_scanning, targetObject));
                    binding.statusDot.setBackgroundResource(R.drawable.dot_blue_glow);
                    binding.scanningContainer.setVisibility(View.VISIBLE);
                    updateTargetLabel(getString(R.string.target_looking, targetObject));
                    binding.detectedResultPanel.setVisibility(View.GONE);
                    hideBoundingBox();
                    applyTorchForState();
                    break;
                case FOUND:
                    binding.statusDot.setBackgroundResource(R.drawable.dot_blue_glow);
                    binding.scanningContainer.setVisibility(View.GONE);
                    updateTargetLabel("");
                    applyTorchForState();
                    break;
                case NOT_IN_CLASSES:
                    stopHapticRadar();
                    binding.tvStatusText.setText(getString(R.string.status_not_in_classes, targetObject));
                    binding.scanningContainer.setVisibility(View.GONE);
                    updateTargetLabel(getString(R.string.target_not_found, targetObject));
                    binding.detectedResultPanel.setVisibility(View.GONE);
                    hideBoundingBox();
                    applyTorchForState();
                    break;
            }
        });
    }

    private void showFinderTutorialStep(int index) {
        tutorialShowing = true;
        tutorialStep = index;
        binding.tutorialOverlay.setVisibility(View.VISIBLE);
        hideTutorialHighlights();

        String title;
        String body;
        String tts;
        View highlightedView;

        switch (index) {
            case 0:
                title = "Find an object";
                body = "To find an object, tap the microphone button. Then say one object name, such as charger or medicine.";
                tts = "To find an object, tap the microphone button, then say one object name, "
                        + "such as charger or medicine. Tap next to continue.";
                highlightedView = binding.tutorialHighlightMic;
                positionTutorialBubble(
                        android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL,
                        0, 0, 0, 126, "▼",
                        android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL,
                        0, 0, 0, 96);
                break;
            case 1:
                title = "Understand feedback";
                body = "When the object is found, VocaSee shows a box, speaks its proximity, vibrates faster as you get closer, and can show hand proximity guidance.";
                tts = "When the object is found, VocaSee shows a box, speaks its proximity, "
                        + "vibrates faster as you get closer, and can show hand proximity guidance. "
                        + "Tap next to continue.";
                highlightedView = binding.tutorialHighlightDetection;
                positionTutorialBubble(
                        android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL,
                        0, 0, 0, 126, "▼",
                        android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL,
                        0, 120, 0, 0);
                break;
            case 2:
                title = "Adjust settings";
                body = "Tap the settings icon for voice guide, vibration, sensitivity, flashlight, and display options.";
                tts = "Tap the settings icon for voice guide, vibration, sensitivity, flashlight, "
                        + "and display options. Tap done to finish the tutorial.";
                highlightedView = binding.tutorialHighlightSettings;
                positionTutorialBubble(
                        android.view.Gravity.TOP | android.view.Gravity.END,
                        0, 96, 16, 0, "▲",
                        android.view.Gravity.TOP | android.view.Gravity.END,
                        0, 70, 16, 0);
                break;
            default:
                title = "Adjust settings";
                body = "Tap the settings icon for voice guide, vibration, sensitivity, flashlight, and privacy options.";
                tts = "Tap the settings icon for voice guide, vibration, sensitivity, flashlight, and privacy options.";
                highlightedView = binding.tutorialHighlightSettings;
                positionTutorialBubble(
                        android.view.Gravity.TOP | android.view.Gravity.END,
                        0, 96, 16, 0, "▲",
                        android.view.Gravity.TOP | android.view.Gravity.END,
                        0, 72, 16, 0);
                break;
        }

        highlightedView.setVisibility(View.VISIBLE);
        animateTutorialHighlight(highlightedView);
        binding.tvTutorialTitle.setText(title);
        binding.tvTutorialBody.setText(body);
        binding.btnTutorialNext.setText(index == 2 ? getString(R.string.done) : getString(R.string.next));
        binding.btnTutorialNext.setContentDescription(index == 2
                ? getString(R.string.cd_tutorial_done)
                : getString(R.string.cd_tutorial_next));
        // VoiceFeedbackManager routes the same message through TalkBack when it is
        // active, so do not announce it twice from the tutorial card.
        if (voiceFeedback == null || !voiceFeedback.isTalkBackActive()) {
            binding.tutorialCard.announceForAccessibility(title + ". " + body);
        }
        binding.btnTutorialNext.requestFocus();
        speak(tts);
    }

    private void hideTutorialHighlights() {
        binding.tutorialHighlightMic.clearAnimation();
        binding.tutorialHighlightDetection.clearAnimation();
        binding.tutorialHighlightRecent.clearAnimation();
        binding.tutorialHighlightObjects.clearAnimation();
        binding.tutorialHighlightSettings.clearAnimation();
        binding.tutorialHighlightMic.setVisibility(View.GONE);
        binding.tutorialHighlightDetection.setVisibility(View.GONE);
        binding.tutorialHighlightRecent.setVisibility(View.GONE);
        binding.tutorialHighlightObjects.setVisibility(View.GONE);
        binding.tutorialHighlightSettings.setVisibility(View.GONE);
    }

    private void positionTutorialBubble(
            int bubbleGravity, int bubbleStart, int bubbleTop, int bubbleEnd, int bubbleBottom,
            String pointer, int pointerGravity, int pointerStart, int pointerTop, int pointerEnd, int pointerBottom) {
        FrameLayout.LayoutParams bubbleParams =
                (FrameLayout.LayoutParams) binding.tutorialCard.getLayoutParams();
        bubbleParams.gravity = bubbleGravity;
        bubbleParams.setMargins(dp(bubbleStart), dp(bubbleTop), dp(bubbleEnd), dp(bubbleBottom));
        binding.tutorialCard.setLayoutParams(bubbleParams);

        FrameLayout.LayoutParams pointerParams =
                (FrameLayout.LayoutParams) binding.tvTutorialPointer.getLayoutParams();
        pointerParams.gravity = pointerGravity;
        pointerParams.setMargins(dp(pointerStart), dp(pointerTop), dp(pointerEnd), dp(pointerBottom));
        binding.tvTutorialPointer.setLayoutParams(pointerParams);
        binding.tvTutorialPointer.setText(pointer);
    }

    private void animateTutorialHighlight(View target) {
        ScaleAnimation animation = new ScaleAnimation(
                0.96f, 1.08f, 0.96f, 1.08f,
                Animation.RELATIVE_TO_SELF, 0.5f,
                Animation.RELATIVE_TO_SELF, 0.5f);
        animation.setDuration(720);
        animation.setRepeatCount(Animation.INFINITE);
        animation.setRepeatMode(Animation.REVERSE);
        target.startAnimation(animation);
    }

    private void advanceFinderTutorial() {
        if (tutorialStep < 2) {
            showFinderTutorialStep(tutorialStep + 1);
        } else {
            finishFinderTutorial();
        }
    }

    private void finishFinderTutorial() {
        tutorialShowing = false;
        binding.tutorialOverlay.setVisibility(View.GONE);
        hideTutorialHighlights();
        AppPreferences.setTutorialDone(this, true);
        if (voiceFeedback != null) voiceFeedback.stopAll();
        scheduleStartupPrompt();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void stopListening() {
        if (Looper.myLooper() != Looper.getMainLooper()) { mainHandler.post(this::stopListening); return; }
        voiceTurnToken++;
        destroySpeechRecognizer();
    }

    private void destroySpeechRecognizer() {
        recognizerAttemptToken++;
        recognizerReadyForSpeech = false;
        if (speechRecognizer != null) {
            speechRecognizer.cancel();
            speechRecognizer.destroy();
            speechRecognizer = null;
        }
    }
    private String capitalize(String s) { if(s==null||s.isEmpty()) return s; return s.substring(0,1).toUpperCase()+s.substring(1); }
    private void speak(String text) { speak(text, null); }
    private void speak(String text, String id) {
        if (voiceFeedback != null) voiceFeedback.speakCritical(text, id);
    }

    private void startVoiceGuideWithTalkBackNotice() {
        if (voiceFeedback == null
                || !voiceFeedback.isTalkBackActive()
                || voiceFeedback.isScreenReaderMode()
                || !AppPreferences.isVoiceGuideEnabled(this)
                || talkBackVoiceGuideNoticeShown) {
            scheduleStartupPrompt();
            return;
        }

        talkBackVoiceGuideNoticeShown = true;
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("VocaSee Voice Guide")
                .setMessage("VocaSee provides live spoken detection guidance. For the clearest guidance during a search, turn off TalkBack. You can use TalkBack Compatibility in Settings when you want TalkBack to read key updates instead.")
                .setNegativeButton("Continue with Voice Guide",
                        (dialog, which) -> scheduleStartupPrompt())
                .setPositiveButton("Accessibility Settings", (dialog, which) ->
                        startActivity(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)))
                .show();
    }

    private void scheduleStartupPrompt() {
        mainHandler.postDelayed(() -> {
            if (!isFinishing() && !isDestroyed()
                    && !tutorialShowing && currentState == DetectionState.IDLE) {
                speak(getStartupPrompt(), "startup_prompt");
            }
        }, STARTUP_PROMPT_DELAY_MS);
    }
    private void speakQueued(String text) {
        speakQueued(text, null);
    }

    private void speakQueued(String text, String id) {
        if (voiceFeedback != null) {
            String category = id != null ? id : "normal_" + System.nanoTime();
            voiceFeedback.speakNormal(text, category, id);
        }
    }

    private void speakThenListen(String text, String id, VoiceMode mode) {
        if (AppPreferences.isVoiceGuideEnabled(this)) {
            speak(text, id);
        } else {
            mainHandler.postDelayed(() -> openVoiceModalWithoutCancellingSearch(mode), 250);
        }
    }

    private void updateTargetLabel(String text) {
        runOnUiThread(() -> {
            binding.tvTargetLabel.setText(text);
            binding.tvTargetLabel.setVisibility(
                    text == null || text.trim().isEmpty() ? View.GONE : View.VISIBLE);
        });
    }

    private void updateDetectedResultPanel(String label, String color, float confidence, String framePosition) {
        updateDetectedResultPanel(label, color, confidence, framePosition, lastOcrText);
    }

    private void updateDetectedResultPanel(String label, String color, float confidence,
                                           String framePosition, String recognizedText) {
        // PROCESS 8C - RESULT SUMMARY: Present target name, relative proximity, approximate color, and confidence.
        lastResultPanelUpdateTime = SystemClock.elapsedRealtime();
        int percent = Math.round(confidence * 100f);
        boolean hasColor = AppPreferences.isColorDescriptionEnabled(this)
                && isStableColorForFeedback(color);
        boolean hasText = isReadableOcrText(recognizedText);
        binding.tvDetectedSummary.setText(capitalize(label) + " found\n"
                + "Color: " + (hasColor ? formatColorDescription(color) : "undetermined") + "\n"
                + "Visible text: " + (hasText ? recognizedText : "none") + "\n"
                + "Proximity: " + capitalize(framePosition) + " - " + percent + "% confidence");
        binding.detectedResultPanel.setVisibility(View.VISIBLE);
    }

    private void updateFoundStatus(String label, String color, String recognizedText) {
        boolean includeColor = AppPreferences.isColorDescriptionEnabled(this)
                && isStableColorForFeedback(color);
        boolean includeText = isReadableOcrText(recognizedText);
        binding.tvStatusText.setText("Object found. Color: "
                + (includeColor ? formatColorDescription(color) : "undetermined")
                + ". Visible text: " + (includeText ? recognizedText : "none") + ".");
    }

    private void announceFoundDetailsIfNeeded(String label, String color, String recognizedText) {
        if (currentState != DetectionState.FOUND || voiceFeedback == null
                || awaitingHoldingConfirmation || awaitingFindAnotherConfirmation) {
            return;
        }

        boolean newColor = AppPreferences.isColorDescriptionEnabled(this)
                && isStableColorForFeedback(color)
                && announcedFoundColor == null;
        boolean newText = isReadableOcrText(recognizedText) && announcedFoundText == null;
        if (newColor || newText) {
            String colorDescription = AppPreferences.isColorDescriptionEnabled(this)
                    && isStableColorForFeedback(color)
                    ? formatColorDescription(color) : "undetermined";
            if (newColor) announcedFoundColor = color;
            if (newText) {
                announcedFoundText = recognizedText;
                lastSpokenOcrText = recognizedText;
            }
            voiceFeedback.speakNormal("Object update. The color is " + colorDescription
                    + ". " + (newText ? "Visible text is " + recognizedText + "."
                    : "No visible text."), "found_details", "found_details");
        }
    }

    private void maybeUpdateDetectedResultPanel(String label, String color, float confidence, String framePosition) {
        maybeUpdateDetectedResultPanel(label, color, confidence, framePosition, lastOcrText);
    }

    private void maybeUpdateDetectedResultPanel(String label, String color, float confidence,
                                                String framePosition, String recognizedText) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastResultPanelUpdateTime < RESULT_PANEL_UPDATE_INTERVAL_MS) return;
        lastResultPanelUpdateTime = now;
        updateDetectedResultPanel(label, color, confidence, framePosition, recognizedText);
    }

    private String formatColorDescription(String color) {
        if (!isStableColorForFeedback(color)) {
            return "unclear";
        }
        if ("light-colored".equalsIgnoreCase(color)) {
            return "light colored";
        }
        return color.toLowerCase(Locale.US);
    }

    private boolean isStableColorForFeedback(String color) {
        return color != null
                && !color.trim().isEmpty()
                && !"unknown".equalsIgnoreCase(color)
                && !COLOR_UNCLEAR.equalsIgnoreCase(color);
    }

    private String getFramePositionLabel(Rect box) {
        float ratio = estimateProximityRatio(box);
        if (ratio >= RADAR_REACHED_PROXIMITY) return "very close";
        if (ratio >= RADAR_MOVE_CLOSER_AREA) return "getting close";
        return "still far";
    }

    private String getLastKnownViewDescription(Rect box) {
        if (box == null) return "in the previous camera view";

        int frameWidth = binding.cameraPreview.getWidth();
        int frameHeight = binding.cameraPreview.getHeight();
        if (frameWidth <= 0) frameWidth = getResources().getDisplayMetrics().widthPixels;
        if (frameHeight <= 0) frameHeight = getResources().getDisplayMetrics().heightPixels;

        float x = box.exactCenterX() / Math.max(1f, frameWidth);
        float y = box.exactCenterY() / Math.max(1f, frameHeight);
        String horizontal = x < 0.34f ? "left" : (x > 0.66f ? "right" : "center");
        String vertical = y < 0.34f ? "upper" : (y > 0.66f ? "lower" : "middle");

        if ("center".equals(horizontal) && "middle".equals(vertical)) {
            return "near the center of the camera view";
        }
        if ("center".equals(horizontal)) return "in the " + vertical + " part of the camera view";
        if ("middle".equals(vertical)) return "on the " + horizontal + " side of the camera view";
        return "in the " + vertical + " " + horizontal + " part of the camera view";
    }

    private void announceProximityBand(Rect box) {
        if (voiceFeedback == null || handGuidanceOwnsVibration
                || awaitingHoldingConfirmation || awaitingFindAnotherConfirmation) {
            return;
        }
        String band = getFramePositionLabel(box);
        boolean tooClose = hapticRadarProximity >= RADAR_TOO_CLOSE_AREA
                || hapticRadarWidthRatio >= RADAR_TOO_CLOSE_WIDTH;
        if (tooClose) band = "too close";
        if (band.equals(lastSpokenApproachBand)) return;
        lastSpokenApproachBand = band;
        String guidance = "too close".equals(band)
                ? "The object is very close. Take a small step back until you can see the whole object and its surface."
                : "very close".equals(band)
                ? "The object is close. Hold the phone steady and reach slowly toward it."
                : "The object is getting closer. Keep it in view and move slowly toward its surface.";
        voiceFeedback.speakNormal(guidance, "approach_progress");
    }

    private boolean isCloseEnoughForHandGuidance() {
        return hapticRadarProximity >= HAND_GUIDANCE_ACTIVATION_PROXIMITY
                || hapticRadarWidthRatio >= HAND_GUIDANCE_ACTIVATION_WIDTH_RATIO;
    }

    private boolean isCloseEnoughForHandGuidance(float proximity, float widthRatio) {
        boolean closeEnough = proximity >= HAND_GUIDANCE_ACTIVATION_PROXIMITY
                || widthRatio >= HAND_GUIDANCE_ACTIVATION_WIDTH_RATIO;
        boolean tooClose = proximity >= RADAR_TOO_CLOSE_AREA
                || widthRatio >= RADAR_TOO_CLOSE_WIDTH;
        return closeEnough && !tooClose;
    }

    private float estimateProximityRatio(Rect box) {
        // PROCESS 6D - RELATIVE PROXIMITY: Use object-box area/frame area; this is not a physical distance measurement.
        int frameWidth = binding.cameraPreview.getWidth();
        int frameHeight = binding.cameraPreview.getHeight();
        if (frameWidth <= 0) frameWidth = getResources().getDisplayMetrics().widthPixels;
        if (frameHeight <= 0) frameHeight = getResources().getDisplayMetrics().heightPixels;

        float boxWidth = Math.max(0f, box.width());
        float boxHeight = Math.max(0f, box.height());
        float frameArea = Math.max(1f, frameWidth * (float) frameHeight);
        return clamp01((boxWidth * boxHeight) / frameArea);
    }

    private String articleFor(String label) {
        if (label == null || label.isEmpty()) return "a";
        char first = Character.toLowerCase(label.charAt(0));
        return "aeiou".indexOf(first) >= 0 ? "an" : "a";
    }

    private String getStartupPrompt() {
        awaitingRepeatConfirmation = false;
        suggestedRepeatObject = null;
        if (!AppPreferences.isRecentReminderEnabled(this)) {
            return getString(R.string.tts_ready);
        }

        List<String> frequent = AppPreferences.getFrequentSearches(this);
        List<String> recent = AppPreferences.getRecentSearches(this);
        if (!frequent.isEmpty()) {
            suggestedRepeatObject = frequent.get(0);
            awaitingRepeatConfirmation = true;
            return buildDynamicFrequentPrompt(suggestedRepeatObject);
        }
        if (!recent.isEmpty()) {
            suggestedRepeatObject = recent.get(0);
            awaitingRepeatConfirmation = true;
            return buildDynamicRecentPrompt(suggestedRepeatObject);
        }
        return getString(R.string.tts_ready);
    }

    private String buildDynamicRecentPrompt(String objectName) {
        String[] templates = {
                "Welcome back. You recently looked for %s. Would you like to find it again?",
                "Good to see you again. Should I help you find %s again?",
                "You recently searched for %s. Say yes to find it again, or say another object."
        };
        return String.format(Locale.US, templates[promptRandom.nextInt(templates.length)], objectName);
    }

    private String buildDynamicFrequentPrompt(String objectName) {
        String[] templates = {
                "Welcome back. Your most searched item is %s. Do you want to find it again?",
                "You often look for %s. Should I search for it now?",
                "I can help you find %s again. Say yes, or say a different object."
        };
        return String.format(Locale.US, templates[promptRandom.nextInt(templates.length)], objectName);
    }

    private void initializeVibrator() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager vibratorManager = getSystemService(VibratorManager.class);
            vibrator = vibratorManager != null ? vibratorManager.getDefaultVibrator() : null;
        } else {
            vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        }
    }

    private void triggerHapticFeedback() {
        vibratePattern(90, 80, 90);
    }

    private void vibratePattern(long... pattern) {
        if (!AppPreferences.isVibrationEnabled(this) || vibrator == null || !vibrator.hasVibrator()) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && pattern.length == 1) {
            vibrator.vibrate(VibrationEffect.createOneShot(pattern[0], VibrationEffect.DEFAULT_AMPLITUDE));
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1));
        } else if (pattern.length > 0) {
            vibrator.vibrate(pattern[0]);
        }
    }

    private void updateHapticRadar(Rect screenBox, boolean isLiveDetection,
                                   float sourceProximity, float sourceWidthRatio) {
        // PROCESS 8D - HAPTIC STATE: Convert box size and center alignment into radar proximity/alignment values.
        if (!AppPreferences.isVibrationEnabled(this) || vibrator == null || !vibrator.hasVibrator()) {
            stopHapticRadar();
            return;
        }

        int frameWidth = binding.cameraPreview.getWidth();
        int frameHeight = binding.cameraPreview.getHeight();
        if (frameWidth <= 0) frameWidth = getResources().getDisplayMetrics().widthPixels;
        if (frameHeight <= 0) frameHeight = getResources().getDisplayMetrics().heightPixels;

        hapticRadarProximity = clamp01(sourceProximity);
        hapticRadarWidthRatio = clamp01(sourceWidthRatio);

        float normalizedCenterX = screenBox.exactCenterX() / Math.max(1f, frameWidth);
        float normalizedCenterY = screenBox.exactCenterY() / Math.max(1f, frameHeight);
        hapticRadarCentered = Math.abs(normalizedCenterX - 0.5f) <= RADAR_CENTER_TOLERANCE
                && Math.abs(normalizedCenterY - 0.5f) <= RADAR_CENTER_TOLERANCE;

        if (isLiveDetection && !handGuidanceOwnsVibration) {
            maybePromptMoveCloser();
            long now = SystemClock.elapsedRealtime();
            if (isTrackingLocked && hasReachedObjectProximity()) {
                if (radarRetrievalStartedAt == 0) radarRetrievalStartedAt = now;
            } else {
                radarRetrievalStartedAt = 0;
            }
            if (isTrackingLocked
                    && radarRetrievalStartedAt > 0
                    && now - radarRetrievalStartedAt >= RADAR_REACH_STABLE_MS
                    && now - foundFeedbackStartedAt >= RETRIEVAL_PROMPT_DELAY_MS) {
                promptReachForObject();
            }
        } else if (!hasReachedObjectProximity()) {
            radarRetrievalStartedAt = 0;
        }

        if (!hapticRadarActive && !handGuidanceOwnsVibration) {
            hapticRadarActive = true;
            hapticRadarHandler.removeCallbacks(hapticRadarRunnable);
            hapticRadarHandler.post(hapticRadarRunnable);
        }
    }

    private void maybePromptMoveCloser() {
        long now = SystemClock.elapsedRealtime();
        if (hapticRadarProximity >= RADAR_MOVE_CLOSER_AREA
                || now - lastMoveCloserTts < MOVE_CLOSER_TTS_INTERVAL_MS
                || "still far".equals(lastSpokenApproachBand)
                || awaitingHoldingConfirmation
                || awaitingFindAnotherConfirmation) {
            return;
        }
        lastMoveCloserTts = now;
        lastSpokenApproachBand = "still far";
        speakQueued(buildApproachDirection(lastKnownTrackingBox), "approach_still_far");
    }

    private boolean hasReachedObjectProximity() {
        if (hapticRadarProximity >= RADAR_TOO_CLOSE_AREA
                || hapticRadarWidthRatio >= RADAR_TOO_CLOSE_WIDTH) return false;
        boolean areaReached = hapticRadarProximity >= RADAR_REACHED_PROXIMITY
                || (hapticRadarCentered && hapticRadarProximity >= RADAR_REACHED_PROXIMITY * 0.72f);
        boolean widthReached = hapticRadarWidthRatio >= RADAR_FULL_WIDTH_RATIO;
        return areaReached || widthReached;
    }

    private void promptReachForObject() {
        if (targetObject == null || reachInstructionSent
                || awaitingHoldingConfirmation || awaitingFindAnotherConfirmation) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastReachInstructionAt < REACH_INSTRUCTION_REPEAT_MS) return;
        lastReachInstructionAt = now;
        reachInstructionSent = true;
        speakQueued("The " + targetObject + " is close. Keep the phone steady, then reach slowly "
                + "toward the object. I will guide your hand.", "reach_instruction");
    }

    private String buildApproachDirection(Rect box) {
        if (box == null) return "Move slowly toward the nearby surface while keeping the camera steady.";
        int frameWidth = binding.cameraPreview.getWidth();
        int frameHeight = binding.cameraPreview.getHeight();
        if (frameWidth <= 0) frameWidth = getResources().getDisplayMetrics().widthPixels;
        if (frameHeight <= 0) frameHeight = getResources().getDisplayMetrics().heightPixels;
        float x = box.exactCenterX() / Math.max(1f, frameWidth);
        float y = box.exactCenterY() / Math.max(1f, frameHeight);
        if (x < 0.35f) return "The object is to your left. Turn the camera left toward it, then move closer slowly.";
        if (x > 0.65f) return "The object is to your right. Turn the camera right toward it, then move closer slowly.";
        if (y < 0.34f) return "The object is above center. Tilt the camera up toward it, then move closer slowly.";
        if (y > 0.66f) return "The object is below center. Tilt the camera down toward it, then move closer slowly.";
        return "The object is ahead. Move slowly toward its surface, keeping it near the center of the camera.";
    }

    private void promptHoldingConfirmation() {
        // This question is reached only through stable hand-to-object contact, not radar.
        if (awaitingHoldingConfirmation || awaitingFindAnotherConfirmation) return;
        if (!HAND_RETRIEVAL_CONFIRMATION_ENABLED) return;
        hasAskedHoldingConfirmation = true;
        awaitingHoldingConfirmation = true;
        lastHoldingConfirmationPrompt = SystemClock.elapsedRealtime();
        holdingConfirmationAttempts++;
        String prompt = "Your hand is at the " + targetObject + ". Is this the "
                + targetObject + " you are looking for?";
        // A live contact cue should not wait behind a long detection description or
        // hand introduction; otherwise the hand can cover the object first.
        speakThenListen(prompt,
                "holding_confirmation_prompt", VoiceMode.CONFIRMATION);
    }


    private void pulseHapticRadar() {
        // PROCESS 8E - DYNAMIC VIBRATION: Closer boxes pulse faster/stronger; centered boxes use maximum amplitude.
        if (!hapticRadarActive || !AppPreferences.isVibrationEnabled(this)
                || vibrator == null || !vibrator.hasVibrator()) {
            stopHapticRadar();
            return;
        }

        long interval = Math.round(RADAR_FAR_INTERVAL_MS
                - (hapticRadarProximity * (RADAR_FAR_INTERVAL_MS - RADAR_CLOSE_INTERVAL_MS)));
        interval = Math.max(RADAR_CLOSE_INTERVAL_MS, Math.min(RADAR_FAR_INTERVAL_MS, interval));

        int amplitude = hapticRadarCentered
                ? RADAR_CENTER_AMPLITUDE
                : Math.round(RADAR_MIN_AMPLITUDE
                        + hapticRadarProximity * (RADAR_MAX_AMPLITUDE - RADAR_MIN_AMPLITUDE));
        amplitude = Math.max(1, Math.min(255, amplitude));
        long duration = hapticRadarCentered
                ? 55
                : Math.round(28 + (hapticRadarProximity * 34));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(duration, amplitude));
        } else {
            vibrator.vibrate(duration);
        }
        hapticRadarHandler.postDelayed(hapticRadarRunnable, interval);
    }

    private void stopHapticRadar() {
        hapticRadarActive = false;
        hapticRadarHandler.removeCallbacks(hapticRadarRunnable);
        if (vibrator != null) vibrator.cancel();
    }

    private float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private void playSoundEffect(int tone) {
        // PROCESS 8F - AUDITORY CUE: Emit short listening/detection tones when sound effects are enabled.
        if (AppPreferences.isSoundEffectsEnabled(this) && toneGenerator != null) {
            toneGenerator.startTone(tone, 180);
        }
    }

    private void refreshToneGenerator() {
        if (toneGenerator != null) toneGenerator.release();
        toneGenerator = new ToneGenerator(
                AudioManager.STREAM_NOTIFICATION, AppPreferences.getVolumePercent(this));
    }

    private void applyTorchForState() {
        if (camera == null || !camera.getCameraInfo().hasFlashUnit()) return;
        boolean enable = AppPreferences.isAutoFlashlightEnabled(this)
                && (currentState == DetectionState.SCANNING
                || currentState == DetectionState.FOUND);
        camera.getCameraControl().enableTorch(enable);
    }

    @Override
    protected void onResume() {
        super.onResume();
        float currentTextScale = AppPreferences.getTextScale(this);
        if (Math.abs(currentTextScale - appliedTextScale) > 0.001f) {
            recreate();
            return;
        }
        if (voiceFeedback != null) voiceFeedback.refreshSpeechRate();
        refreshToneGenerator();
        if (detector != null) {
            detector.setConfidenceThreshold(AppPreferences.getConfidenceThreshold(this));
        }
        applyTorchForState();
    }

    @Override
    protected void onPause() {
        stopHapticRadar();
        mainHandler.removeCallbacksAndMessages(null);
        stopListening();
        if (voiceModal != null && voiceModal.isShowing()) {
            voiceModal.dismiss();
        }
        mainHandler.removeCallbacksAndMessages(null);
        if (camera != null && camera.getCameraInfo().hasFlashUnit()) {
            camera.getCameraControl().enableTorch(false);
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        stopHapticRadar();
        if (voiceFeedback != null) voiceFeedback.shutdown();
        stopListening();
        if (detector != null) detector.close();
        if (onDeviceTextRecognizer != null) onDeviceTextRecognizer.close();
        if (handLandmarker != null) handLandmarker.close();
        if (cameraExecutor != null) cameraExecutor.shutdown();
        if (handExecutor != null) handExecutor.shutdown();
        if (toneGenerator != null) toneGenerator.release();
        super.onDestroy();
    }
}
