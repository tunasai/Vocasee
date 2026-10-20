package com.vocasee.app;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.view.View;
import android.widget.TextView;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.util.Log;

import java.io.File;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

public class VoiceFeedbackManager {

    // PROCESS 8 - SPOKEN FEEDBACK: Prioritize critical prompts and throttle repeated normal/ambient guidance.

    public enum Priority { CRITICAL, NORMAL, AMBIENT }

    public interface OnUtteranceDone {
        void onDone(String utteranceId);
    }

    private static class QueuedMessage {
        final String text;
        final String category;
        final String utteranceId;
        final Priority priority;
        // The microphone opens when this prompt finishes, so nothing may start speaking after it.
        final boolean listenAfter;
        final long enqueuedAt = SystemClock.elapsedRealtime();

        QueuedMessage(String text, String category, String utteranceId, Priority priority) {
            this(text, category, utteranceId, priority, false);
        }

        QueuedMessage(String text, String category, String utteranceId, Priority priority,
                      boolean listenAfter) {
            this.text = text;
            this.category = category;
            this.utteranceId = utteranceId;
            this.priority = priority;
            this.listenAfter = listenAfter;
        }
    }

    private static final long DEFAULT_AMBIENT_MIN_INTERVAL_MS = 3500;
    // A directional hand cue describes where the hand was when it was queued. Once it has
    // waited this long behind other speech it no longer matches the user's position.
    private static final String DIRECTIONAL_GUIDANCE_CATEGORY = "hand_guidance";
    private static final long DIRECTIONAL_GUIDANCE_STALE_MS = 2500;
    // "The object is to your left…" describes the camera view when it was queued; after
    // waiting behind the found announcement this long the view has usually changed.
    private static final String APPROACH_DIRECTION_CATEGORY = "approach_still_far";
    private static final long APPROACH_DIRECTION_STALE_MS = 5000;
    // TalkBack gives no "done" callback, so the next message waits for an estimate of how
    // long this one takes to read. The old 4.5 s cap let long sentences be cut off midway.
    private static final long ACCESSIBILITY_ANNOUNCEMENT_MIN_DELAY_MS = 900;
    private static final long ACCESSIBILITY_ANNOUNCEMENT_MAX_DELAY_MS = 12000;
    private static final long ACCESSIBILITY_ANNOUNCEMENT_MS_PER_CHAR = 65;
    // After a question finishes, the caller has this long to open its listening window
    // (beginListeningHold) before queued speech is allowed to resume.
    private static final long UNCLAIMED_LISTENING_HOLD_MS = 1500;
    private static final String WARMUP_UTTERANCE_ID = "vfm_warmup";
    private static final String TAG = "VoiceFeedbackManager";
    // If the engine never reports onDone/onError (service died, engine bug), the queue would
    // stay "speaking" forever and the app would go silent. The watchdog allows twice the
    // expected speaking time plus a margin before releasing it, so it never cuts off speech.
    private static final long SPEECH_WATCHDOG_MARGIN_MS = 4000;
    private static final long SPEECH_WATCHDOG_MAX_MS = 60000;

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private final ArrayDeque<QueuedMessage> queue = new ArrayDeque<>();
    private final Map<String, String> lastAnnouncedByCategory = new HashMap<>();
    private final Map<String, Long> lastSpokenTimeByCategory = new HashMap<>();

    // Zero-width space toggled onto a repeated live-region message so an identical
    // direction ("move left" twice) is still a text change TalkBack will announce.
    private static final String REPEAT_MARKER = "​";

    private TextToSpeech tts;
    private TextView liveRegionView;
    private TextView visibleStatusView;
    private AudioManager audioManager;
    private AudioFocusRequest duckingFocusRequest;
    private boolean holdingDuckingFocus = false;
    private Boolean ttsUsesAccessibilityAudio = null;
    private boolean ttsReady = false;
    // The engine could not start. Messages are then shown on screen (and read by TalkBack
    // when it runs) instead of being queued forever behind an engine that never speaks.
    private boolean ttsFailed = false;
    private final Object speechWatchdogToken = new Object();
    private boolean isSpeaking = false;
    private String activeUtteranceId;
    private String activeCategory;
    private Priority activePriority;
    private boolean activeListenAfter = false;
    // While the speech recognizer owns the microphone, no speech may start: the recognizer
    // ducks or mutes playback (cutting sentences off) and would transcribe the app's own voice.
    private boolean listeningHold = false;
    private boolean listeningHoldClaimed = false;
    private final Runnable releaseUnclaimedListeningHold = () -> {
        if (!listeningHoldClaimed) endListeningHold();
    };
    private OnUtteranceDone utteranceDoneListener;

    public VoiceFeedbackManager(Context context) {
        this.context = context.getApplicationContext();
    }

    public void initialize(Runnable onReady) {
        initialize(onReady, null);
    }

    /**
     * {@code onUnavailable} runs (before {@code onReady}) when the speech engine cannot start;
     * prompts then fall back to on-screen text and TalkBack announcements.
     */
    public void initialize(Runnable onReady, Runnable onUnavailable) {
        tts = new TextToSpeech(context, status -> {
            if (status == TextToSpeech.SUCCESS) {
                int languageResult = tts.setLanguage(Locale.US);
                if (languageResult == TextToSpeech.LANG_MISSING_DATA
                        || languageResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.w(TAG, "US English voice unavailable (" + languageResult
                            + "); using the engine's default voice");
                }
                preferOnDeviceVoice();
                refreshSpeechRate();
                // Callbacks arrive on a TTS binder thread; all queue state lives on the main thread.
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String utteranceId) {}
                    @Override public void onError(String utteranceId) {
                        if (WARMUP_UTTERANCE_ID.equals(utteranceId)) return;
                        mainHandler.post(() -> onUtteranceFinished(utteranceId));
                    }
                    @Override public void onDone(String utteranceId) {
                        if (WARMUP_UTTERANCE_ID.equals(utteranceId)) return;
                        mainHandler.post(() -> onUtteranceFinished(utteranceId));
                    }
                });
                prewarmEngine();
                mainHandler.post(() -> {
                    ttsReady = true;
                    if (onReady != null) onReady.run();
                    pump(false);
                });
            } else {
                Log.e(TAG, "Text-to-speech engine failed to initialize (status " + status + ")");
                mainHandler.post(() -> {
                    ttsFailed = true;
                    if (onUnavailable != null) onUnavailable.run();
                    if (onReady != null) onReady.run();
                    pump(false);
                });
            }
        });
    }

    /**
     * A network voice adds a server round trip before every sentence. Prefer an installed
     * voice for the same language so each prompt starts as soon as it is queued.
     */
    private void preferOnDeviceVoice() {
        try {
            Voice current = tts.getVoice();
            if (current == null || !current.isNetworkConnectionRequired()) return;
            Voice best = null;
            for (Voice voice : tts.getVoices()) {
                if (voice.isNetworkConnectionRequired()
                        || voice.getFeatures().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
                        || !voice.getLocale().getLanguage().equals(current.getLocale().getLanguage())) {
                    continue;
                }
                boolean sameCountry = voice.getLocale().getCountry()
                        .equals(current.getLocale().getCountry());
                boolean bestSameCountry = best != null && best.getLocale().getCountry()
                        .equals(current.getLocale().getCountry());
                if (best == null || (sameCountry && !bestSameCountry)
                        || (sameCountry == bestSameCountry && voice.getLatency() < best.getLatency())) {
                    best = voice;
                }
            }
            if (best != null) tts.setVoice(best);
        } catch (RuntimeException ignored) {
            // Some engines throw from getVoices(); the default voice still works.
        }
    }

    /**
     * The first synthesis loads the voice model, which delays the first real prompt by up
     * to a second. Synthesizing a short phrase to a scratch file loads it silently.
     */
    private void prewarmEngine() {
        File warmup = new File(context.getCacheDir(), "tts_warmup.wav");
        tts.synthesizeToFile("Ready.", new Bundle(), warmup, WARMUP_UTTERANCE_ID);
    }

    public void setOnUtteranceDoneListener(OnUtteranceDone listener) {
        utteranceDoneListener = listener;
    }

    /**
     * {@code liveRegion} is a dedicated, otherwise silent view whose text changes TalkBack
     * announces (assertive for guidance, so stale cues are cut off instead of queued).
     * {@code visibleStatus} mirrors the same text for sighted helpers; it is not a live region.
     */
    public void setAccessibilityAnnouncer(TextView liveRegion, TextView visibleStatus) {
        liveRegionView = liveRegion;
        visibleStatusView = visibleStatus;
    }

    public void refreshSpeechRate() {
        if (tts != null) tts.setSpeechRate(AppPreferences.getSpeechRate(context));
    }

    /**
     * TalkBack owns spoken output while touch exploration is active. Sending a second
     * TTS stream would make both voices overlap and is harder to understand.
     */
    public boolean isScreenReaderMode() {
        if (!AppPreferences.isTalkBackCompatibilityEnabled(context)) return false;
        return isTalkBackActive();
    }

    public boolean isTalkBackActive() {
        AccessibilityManager manager =
                (AccessibilityManager) context.getSystemService(Context.ACCESSIBILITY_SERVICE);
        return manager != null && manager.isEnabled() && manager.isTouchExplorationEnabled();
    }

    public void speakCritical(String text) {
        speakCritical(text, null);
    }

    public void speakCritical(String text, String utteranceId) {
        speakCritical(text, utteranceId, false);
    }

    /**
     * Speaks a question whose answer is collected by the speech recognizer. When it finishes
     * (onDone, not a timer) the listening hold starts at once, so no queued guidance can
     * begin in the gap before the microphone opens and then be cut off by it.
     */
    public void speakCriticalThenListen(String text, String utteranceId) {
        speakCritical(text, utteranceId, true);
    }

    private void speakCritical(String text, String utteranceId, boolean listenAfter) {
        if (!canSpeak(text)) return;
        runOnMain(() -> {
            // Queued guidance and statements are superseded. A queued question stays: its
            // answer flow only starts once it has been spoken.
            queue.removeIf(m -> !m.listenAfter);
            enqueue(new QueuedMessage(text, "critical_" + System.nanoTime(), utteranceId,
                    Priority.CRITICAL, listenAfter));
            // Lower-priority guidance is cut off, but a critical sentence already playing
            // finishes first; flushing it left users with half a prompt.
            pump(isSpeaking && activePriority != Priority.CRITICAL);
        });
    }

    /**
     * For a direct user action (stopping a search, moving through the tutorial): whatever is
     * playing describes the screen the user just left, so it is cut off immediately.
     */
    public void speakUrgent(String text) {
        if (!canSpeak(text)) return;
        runOnMain(() -> {
            queue.clear();
            enqueue(new QueuedMessage(text, "critical_" + System.nanoTime(), null,
                    Priority.CRITICAL));
            pump(true);
        });
    }

    /**
     * Hands a message to TalkBack instead of the voice guide (used with TalkBack on outside
     * of a search). Any app speech still playing is stopped so the two never overlap.
     */
    public void announceThroughTalkBack(String text) {
        if (text == null || text.trim().isEmpty()) return;
        runOnMain(() -> {
            queue.clear();
            clearActive();
            if (tts != null) tts.stop();
            abandonDuckingFocus();
            announceToAccessibility(text, Priority.CRITICAL);
        });
    }

    /** Stops all speech and keeps it stopped while the microphone is listening. */
    public void beginListeningHold() {
        runOnMain(() -> {
            mainHandler.removeCallbacks(releaseUnclaimedListeningHold);
            stopAll();
            listeningHold = true;
            listeningHoldClaimed = true;
        });
    }

    /** The microphone is closed; queued speech (such as the reply to an answer) may play. */
    public void endListeningHold() {
        runOnMain(() -> {
            mainHandler.removeCallbacks(releaseUnclaimedListeningHold);
            if (!listeningHold) return;
            listeningHold = false;
            listeningHoldClaimed = false;
            pump(false);
        });
    }

    public void speakNormal(String text, String category) {
        speakNormal(text, category, null);
    }

    public void speakNormal(String text, String category, String utteranceId) {
        if (!canSpeak(text)) return;
        runOnMain(() -> {
            // Guidance queued behind an open microphone would be stale once it closes.
            if (listeningHold) return;
            if (isActiveOrQueuedDuplicate(text, category)) return;
            // Directions such as "move left" are stale once a new instruction or question is ready.
            removeAmbientMessages();
            removeQueuedCategory(category);
            enqueue(new QueuedMessage(text, category, utteranceId, Priority.NORMAL));
            pump(supersedesActiveMessage(category));
        });
    }

    /**
     * A newer message of the same kind replaces the one being spoken instead of waiting:
     * through TalkBack (paced by an estimated duration, with no reliable "done" callback)
     * and, with app TTS, for live hand directions, which are wrong once the hand has moved.
     */
    private boolean supersedesActiveMessage(String category) {
        return isSpeaking && category.equals(activeCategory)
                && (isScreenReaderMode() || DIRECTIONAL_GUIDANCE_CATEGORY.equals(category));
    }

    public void speakAmbient(String text, String category) {
        speakAmbient(text, category, DEFAULT_AMBIENT_MIN_INTERVAL_MS);
    }

    public void speakAmbient(String text, String category, long minIntervalMs) {
        if (!canSpeak(text)) return;
        runOnMain(() -> {
            if (listeningHold) return;
            // In TalkBack mode, VocaSee still supplies useful direction commands through
            // the existing status output, but at a calmer rate so the reader can finish.
            long interval = isScreenReaderMode() ? Math.max(minIntervalMs, 6000L) : minIntervalMs;
            if (isActiveOrQueuedDuplicate(text, category)) return;

            long now = SystemClock.elapsedRealtime();
            Long lastTime = lastSpokenTimeByCategory.get(category);
            if (lastTime != null && now - lastTime < interval) return;

            boolean supersedes = supersedesActiveMessage(category);
            if (!supersedes && hasHigherPriorityPending()) return;

            lastSpokenTimeByCategory.put(category, now);
            removeQueuedCategory(category);
            enqueue(new QueuedMessage(text, category, null, Priority.AMBIENT));
            pump(supersedes);
        });
    }

    public String pickVariant(String[] variants) {
        return variants[random.nextInt(variants.length)];
    }

    public void resetDedupState() {
        runOnMain(() -> {
            lastAnnouncedByCategory.clear();
            lastSpokenTimeByCategory.clear();
        });
    }

    public void stopAll() {
        runOnMain(() -> {
            queue.clear();
            clearActive();
            if (tts != null) tts.stop();
            abandonDuckingFocus();
        });
    }

    private void clearActive() {
        mainHandler.removeCallbacksAndMessages(speechWatchdogToken);
        isSpeaking = false;
        activeUtteranceId = null;
        activeCategory = null;
        activePriority = null;
        activeListenAfter = false;
    }

    /** Queue state is main-thread only; camera and TTS callbacks are marshalled here. */
    private void runOnMain(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) action.run();
        else mainHandler.post(action);
    }

    /** Drops queued non-critical messages in the given categories and cuts off the active
     *  utterance if it belongs to one of them. Guidance becomes stale the moment the user
     *  advances, so letting the current sentence finish would still lag behind them. */
    public void cancelGuidanceMessages(String... categories) {
        java.util.List<String> cancelled = Arrays.asList(categories);
        runOnMain(() -> {
            queue.removeIf(m -> m.priority != Priority.CRITICAL && cancelled.contains(m.category));
            if (!isSpeaking || activeCategory == null || !cancelled.contains(activeCategory)) return;
            // tts.stop() reports onStop rather than onDone, so release the speaking state here.
            clearActive();
            if (tts != null && !isScreenReaderMode()) tts.stop();
            pump(false);
        });
    }

    public void shutdown() {
        mainHandler.removeCallbacks(releaseUnclaimedListeningHold);
        queue.clear();
        clearActive();
        abandonDuckingFocus();
        liveRegionView = null;
        visibleStatusView = null;
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
    }

    private boolean canSpeak(String text) {
        return text != null && !text.trim().isEmpty() && AppPreferences.isVoiceGuideEnabled(context);
    }

    /** First in, first out: criticals only ever share the queue with questions asked before them. */
    private void enqueue(QueuedMessage message) {
        queue.addLast(message);
    }

    private void removeQueuedCategory(String category) {
        queue.removeIf(m -> m.category.equals(category));
    }

    private void removeAmbientMessages() {
        queue.removeIf(m -> m.priority == Priority.AMBIENT);
    }

    private boolean isActiveOrQueuedDuplicate(String text, String category) {
        if (isSpeaking && category.equals(activeCategory)
                && text.equals(lastAnnouncedByCategory.get(category))) {
            return true;
        }
        for (QueuedMessage message : queue) {
            if (category.equals(message.category) && text.equals(message.text)) return true;
        }
        return false;
    }

    private static boolean isStale(QueuedMessage message, long now) {
        long age = now - message.enqueuedAt;
        if (DIRECTIONAL_GUIDANCE_CATEGORY.equals(message.category)) {
            return age > DIRECTIONAL_GUIDANCE_STALE_MS;
        }
        return APPROACH_DIRECTION_CATEGORY.equals(message.category)
                && age > APPROACH_DIRECTION_STALE_MS;
    }

    private boolean hasHigherPriorityPending() {
        for (QueuedMessage message : queue) {
            if (message.priority == Priority.CRITICAL || message.priority == Priority.NORMAL) return true;
        }
        return isSpeaking;
    }

    private void pump(boolean interrupt) {
        // Critical messages may interrupt; queued lower-priority guidance waits and is de-duplicated by category.
        // TalkBack and the text fallback do not need the engine, so only app speech waits for it.
        boolean textOnly = ttsFailed || isScreenReaderMode();
        if (!textOnly && (!ttsReady || tts == null)) return;
        if (listeningHold) return;
        if (isSpeaking && !interrupt) return;

        QueuedMessage next = queue.poll();
        long now = SystemClock.elapsedRealtime();
        while (next != null && isStale(next, now)) {
            next = queue.poll();
        }
        if (next == null) return;

        isSpeaking = true;
        lastAnnouncedByCategory.put(next.category, next.text);

        Bundle params = new Bundle();
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME,
                AppPreferences.getVolumePercent(context) / 100f);

        String utteranceId = next.utteranceId != null ? next.utteranceId : "vfm_" + System.nanoTime();
        activeUtteranceId = utteranceId;
        activeCategory = next.category;
        activePriority = next.priority;
        activeListenAfter = next.listenAfter;
        if (textOnly) {
            // TalkBack is the only voice: never start app TTS alongside it.
            deliverAsAnnouncement(next, utteranceId);
            return;
        }
        prepareTtsAudio();
        int mode = interrupt ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD;
        if (tts.speak(next.text, mode, params, utteranceId) != TextToSpeech.SUCCESS) {
            // No onDone/onError will follow a rejected utterance; without this the queue
            // would stay "speaking" forever. Show the message instead of dropping it.
            Log.w(TAG, "Text-to-speech rejected an utterance; showing it as text");
            deliverAsAnnouncement(next, utteranceId);
            return;
        }
        scheduleSpeechWatchdog(utteranceId, next.text);
    }

    /** Shows the message (read by TalkBack when it runs) and finishes it after a reading delay. */
    private void deliverAsAnnouncement(QueuedMessage message, String utteranceId) {
        announceToAccessibility(message.text, message.priority);
        mainHandler.postDelayed(() -> onUtteranceFinished(utteranceId),
                accessibilityAnnouncementDelay(message.text));
    }

    /** Twice the expected speaking time at this rate plus a margin, capped. */
    static long speechWatchdogTimeoutMs(int characters, float speechRate) {
        float rate = Math.max(0.25f, speechRate);
        long expectedMs = (long) (characters * ACCESSIBILITY_ANNOUNCEMENT_MS_PER_CHAR / rate);
        return Math.min(SPEECH_WATCHDOG_MAX_MS, SPEECH_WATCHDOG_MARGIN_MS + 2 * expectedMs);
    }

    private void scheduleSpeechWatchdog(String utteranceId, String text) {
        mainHandler.removeCallbacksAndMessages(speechWatchdogToken);
        long timeoutMs = speechWatchdogTimeoutMs(text.length(), AppPreferences.getSpeechRate(context));
        mainHandler.postAtTime(() -> {
            if (!utteranceId.equals(activeUtteranceId)) return;
            Log.w(TAG, "No completion callback for " + utteranceId + "; releasing the speech queue");
            onUtteranceFinished(utteranceId);
        }, speechWatchdogToken, SystemClock.uptimeMillis() + timeoutMs);
    }

    /**
     * App TTS while TalkBack runs (compatibility turned off) is the accessibility stream with
     * transient, duckable focus, so it shares output with the screen reader instead of
     * competing on the media stream. Without TalkBack the original media output is kept.
     */
    private void prepareTtsAudio() {
        boolean companion = isTalkBackActive();
        if (ttsUsesAccessibilityAudio == null || ttsUsesAccessibilityAudio != companion) {
            tts.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(companion ? AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY
                            : AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            ttsUsesAccessibilityAudio = companion;
        }
        if (companion && !holdingDuckingFocus) {
            if (audioManager == null) {
                audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            }
            if (duckingFocusRequest == null) {
                duckingFocusRequest = new AudioFocusRequest.Builder(
                        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build())
                        .build();
            }
            holdingDuckingFocus = audioManager != null
                    && audioManager.requestAudioFocus(duckingFocusRequest)
                    == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        }
    }

    private void abandonDuckingFocus() {
        if (!holdingDuckingFocus) return;
        holdingDuckingFocus = false;
        if (audioManager != null && duckingFocusRequest != null) {
            audioManager.abandonAudioFocusRequest(duckingFocusRequest);
        }
    }

    private void announceToAccessibility(String text, Priority priority) {
        TextView liveRegion = liveRegionView;
        if (liveRegion != null) {
            mainHandler.post(() -> {
                // The status bar shows detection states only. Spoken lines appear there just
                // when no voice engine can say them (the text fallback).
                if (visibleStatusView != null && ttsFailed) visibleStatusView.setText(text);
                // Assertive interrupts whatever TalkBack is reading (including a control's
                // "double tap to activate" hint) so directions are never queued behind it.
                liveRegion.setAccessibilityLiveRegion(priority == Priority.AMBIENT
                        ? View.ACCESSIBILITY_LIVE_REGION_POLITE
                        : View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE);
                CharSequence current = liveRegion.getText();
                liveRegion.setText(current != null && current.toString().equals(text)
                        ? text + REPEAT_MARKER : text);
            });
            return;
        }
        AccessibilityManager manager =
                (AccessibilityManager) context.getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (manager == null || !manager.isEnabled()) return;
        AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_ANNOUNCEMENT);
        event.setPackageName(context.getPackageName());
        event.setClassName(VoiceFeedbackManager.class.getName());
        event.getText().add(text);
        manager.sendAccessibilityEvent(event);
    }

    private long accessibilityAnnouncementDelay(String text) {
        long estimated = Math.max(ACCESSIBILITY_ANNOUNCEMENT_MIN_DELAY_MS,
                (long) text.length() * ACCESSIBILITY_ANNOUNCEMENT_MS_PER_CHAR);
        return Math.min(ACCESSIBILITY_ANNOUNCEMENT_MAX_DELAY_MS, estimated);
    }

    private void onUtteranceFinished(String utteranceId) {
        // QUEUE_FLUSH may still report completion for an older message. Ignore that stale
        // callback so it cannot mark the newer critical prompt as finished.
        if (activeUtteranceId == null || !activeUtteranceId.equals(utteranceId)) return;
        boolean listenAfter = activeListenAfter;
        clearActive();
        if (listenAfter) {
            // Hold before pumping, so nothing queued can start in the gap before the
            // listener opens the microphone. Released if no listening window claims it.
            listeningHold = true;
            listeningHoldClaimed = false;
            mainHandler.postDelayed(releaseUnclaimedListeningHold, UNCLAIMED_LISTENING_HOLD_MS);
        }
        if (utteranceDoneListener != null) utteranceDoneListener.onDone(utteranceId);
        pump(false);
        if (!isSpeaking) abandonDuckingFocus();
    }
}
