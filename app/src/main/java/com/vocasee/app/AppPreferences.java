package com.vocasee.app;

import android.content.Context;

import androidx.datastore.preferences.core.MutablePreferences;
import androidx.datastore.preferences.core.Preferences;
import androidx.datastore.preferences.core.PreferencesFactory;
import androidx.datastore.preferences.core.PreferencesKeys;
import androidx.datastore.preferences.rxjava3.RxPreferenceDataStoreBuilder;
import androidx.datastore.rxjava3.RxDataStore;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.Disposable;

public final class AppPreferences {

    // PROCESS 1 - LOCAL PREFERENCE INPUT: DataStore keeps accessibility, detection, history, and feedback settings.

    private static final String PREFS = "vocasee_preferences";

    private static final String KEY_TUTORIAL_DONE = "tutorial_done";
    private static final String KEY_VIBRATION = "vibration_enabled";
    private static final String KEY_VOICE_GUIDE = "voice_guide_enabled";
    private static final String KEY_TALKBACK_COMPATIBILITY = "talkback_compatibility_enabled";
    private static final String KEY_SOUND_EFFECTS = "sound_effects_enabled";
    private static final String KEY_VOLUME = "volume_percent";
    private static final String KEY_SPEECH_SPEED = "speech_speed_percent";
    private static final String KEY_SENSITIVITY = "detection_sensitivity_percent";
    private static final String KEY_AUTO_FLASHLIGHT = "auto_flashlight_enabled";
    private static final String KEY_CONFIDENCE_OVERLAY = "confidence_overlay_enabled";
    private static final String KEY_COLOR_DESCRIPTION = "color_description_enabled";
    private static final String KEY_RECENT_SEARCHES = "recent_searches";
    private static final String KEY_SEARCH_COUNTS = "search_counts";
    private static final String KEY_RECENT_REMINDER = "recent_reminder_enabled";
    private static final String KEY_SAVE_HISTORY = "save_history_enabled";
    private static final String KEY_THEME_MODE = "theme_mode"; // 0: Light, 1: Dark, 2: System
    private static final String KEY_TEXT_SIZE = "text_size_percent";
    private static final int MAX_RECENT_SEARCHES = 8;

    private static RxDataStore<Preferences> dataStore;
    private static volatile Preferences cachedPreferences;
    private static volatile boolean cacheReady = false;
    private static Disposable cacheSubscription;
    // Writes sent to DataStore but not yet finished. While any are in flight the cache holds
    // the newest values, and a DataStore emission may still be an older snapshot.
    private static int pendingWrites = 0;

    private AppPreferences() {}

    private static RxDataStore<Preferences> dataStore(Context context) {
        synchronized (AppPreferences.class) {
            if (dataStore == null) {
                dataStore = new RxPreferenceDataStoreBuilder(
                        context.getApplicationContext(), PREFS).build();
                startCacheSubscription();
            }
            return dataStore;
        }
    }

    private static void startCacheSubscription() {
        cacheSubscription = dataStore.data().subscribe(
                prefs -> {
                    synchronized (AppPreferences.class) {
                        if (pendingWrites > 0) return;
                        cachedPreferences = prefs;
                        cacheReady = true;
                    }
                },
                throwable -> {
                    // Keep the last known snapshot. Getters fall back to defaults if it is empty.
                }
        );
    }

    private static Preferences snapshot(Context context) {
        dataStore(context);
        if (cacheReady) return cachedPreferences;
        try {
            Preferences prefs = dataStore(context).data().firstOrError().blockingGet();
            synchronized (AppPreferences.class) {
                // A write made while this read was blocking is newer; keep it.
                if (!cacheReady) {
                    cachedPreferences = prefs;
                    cacheReady = true;
                }
                return cachedPreferences;
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean getBoolean(Context context, String key, boolean defaultValue) {
        Preferences prefs = snapshot(context);
        if (prefs == null) return defaultValue;
        Boolean value = prefs.get(PreferencesKeys.booleanKey(key));
        return value != null ? value : defaultValue;
    }

    private static void putBoolean(Context context, String key, boolean value) {
        update(context, mutable -> mutable.set(PreferencesKeys.booleanKey(key), value));
    }

    private static int getInt(Context context, String key, int defaultValue) {
        Preferences prefs = snapshot(context);
        if (prefs == null) return defaultValue;
        Integer value = prefs.get(PreferencesKeys.intKey(key));
        return value != null ? value : defaultValue;
    }

    private static void putInt(Context context, String key, int value) {
        update(context, mutable -> mutable.set(PreferencesKeys.intKey(key), value));
    }

    private static String getString(Context context, String key, String defaultValue) {
        Preferences prefs = snapshot(context);
        if (prefs == null) return defaultValue;
        String value = prefs.get(PreferencesKeys.stringKey(key));
        return value != null ? value : defaultValue;
    }

    private static String getString(Preferences prefs, String key) {
        String value = prefs.get(PreferencesKeys.stringKey(key));
        return value != null ? value : "";
    }

    private static void putString(Context context, String key, String value) {
        update(context, mutable -> mutable.set(PreferencesKeys.stringKey(key), value));
    }

    private static void removeStrings(Context context, String... keys) {
        update(context, mutable -> {
            for (String key : keys) {
                mutable.remove(PreferencesKeys.stringKey(key));
            }
        });
    }

    private interface MutationFn {
        void apply(MutablePreferences mutable);
    }

    /**
     * Applies one change to the in-memory cache now and to DataStore as a single
     * transaction. The function reads current values from the preferences it is given,
     * so a read-modify-write cannot lose a concurrent update.
     */
    private static void update(Context context, MutationFn fn) {
        RxDataStore<Preferences> store = dataStore(context);
        // Load stored values first, so the cache never holds only this one key.
        snapshot(context);
        synchronized (AppPreferences.class) {
            Preferences base = cachedPreferences;
            MutablePreferences mutable = base != null
                    ? base.toMutablePreferences()
                    : PreferencesFactory.createMutable();
            fn.apply(mutable);
            cachedPreferences = mutable;
            cacheReady = true;
            pendingWrites++;
        }
        store.updateDataAsync(preferences -> {
            MutablePreferences mutable = preferences.toMutablePreferences();
            fn.apply(mutable);
            return Single.just(mutable);
        }).subscribe(AppPreferences::onWriteFinished, throwable -> onWriteFinished(null));
    }

    private static void onWriteFinished(Preferences written) {
        synchronized (AppPreferences.class) {
            pendingWrites = Math.max(0, pendingWrites - 1);
            // DataStore applies writes in order, so the last one to finish holds them all.
            if (pendingWrites == 0 && written != null) {
                cachedPreferences = written;
                cacheReady = true;
            }
        }
    }

    public static boolean isTutorialDone(Context context) {
        return getBoolean(context, KEY_TUTORIAL_DONE, false);
    }

    public static void setTutorialDone(Context context, boolean done) {
        putBoolean(context, KEY_TUTORIAL_DONE, done);
    }

    public static boolean isVibrationEnabled(Context context) {
        return getBoolean(context, KEY_VIBRATION, true);
    }

    public static void setVibrationEnabled(Context context, boolean enabled) {
        putBoolean(context, KEY_VIBRATION, enabled);
    }

    public static boolean isVoiceGuideEnabled(Context context) {
        return getBoolean(context, KEY_VOICE_GUIDE, true);
    }

    public static void setVoiceGuideEnabled(Context context, boolean enabled) {
        putBoolean(context, KEY_VOICE_GUIDE, enabled);
    }

    public static boolean isTalkBackCompatibilityEnabled(Context context) {
        // On by default: with TalkBack running, VocaSee speaks through TalkBack instead of
        // starting a second voice over it. Turning this off opts into ducked app TTS.
        return getBoolean(context, KEY_TALKBACK_COMPATIBILITY, true);
    }

    public static void setTalkBackCompatibilityEnabled(Context context, boolean enabled) {
        putBoolean(context, KEY_TALKBACK_COMPATIBILITY, enabled);
    }

    public static boolean isSoundEffectsEnabled(Context context) {
        return getBoolean(context, KEY_SOUND_EFFECTS, false);
    }

    public static void setSoundEffectsEnabled(Context context, boolean enabled) {
        putBoolean(context, KEY_SOUND_EFFECTS, enabled);
    }

    public static int getVolumePercent(Context context) {
        return getInt(context, KEY_VOLUME, 75);
    }

    public static void setVolumePercent(Context context, int percent) {
        putInt(context, KEY_VOLUME, clamp(percent, 0, 100));
    }

    public static int getSpeechSpeedPercent(Context context) {
        return getInt(context, KEY_SPEECH_SPEED, 50);
    }

    public static void setSpeechSpeedPercent(Context context, int percent) {
        putInt(context, KEY_SPEECH_SPEED, clamp(percent, 0, 100));
    }

    public static float getSpeechRate(Context context) {
        // The default slider position (50) is the engine's natural 1.0x pace; the old
        // 0.75 offset made every first-time user hear 1.25x speech.
        return 0.5f + (getSpeechSpeedPercent(context) / 100f);
    }

    public static int getSensitivityPercent(Context context) {
        return getInt(context, KEY_SENSITIVITY, 50);
    }

    public static void setSensitivityPercent(Context context, int percent) {
        putInt(context, KEY_SENSITIVITY, clamp(percent, 0, 100));
    }

    public static float getConfidenceThreshold(Context context) {
        // Higher sensitivity intentionally lowers the required YOLO class score.
        // At the default 50%, require 0.35; at 100%, require 0.20. This is closer
        // to Ultralytics' normal 0.25 prediction threshold while retaining a useful
        // conservative setting for demos in cluttered rooms.
        return 0.20f + ((100 - getSensitivityPercent(context)) / 100f) * 0.30f;
    }

    public static boolean isAutoFlashlightEnabled(Context context) {
        return getBoolean(context, KEY_AUTO_FLASHLIGHT, true);
    }

    public static void setAutoFlashlightEnabled(Context context, boolean enabled) {
        putBoolean(context, KEY_AUTO_FLASHLIGHT, enabled);
    }

    public static boolean isConfidenceOverlayEnabled(Context context) {
        return getBoolean(context, KEY_CONFIDENCE_OVERLAY, true);
    }

    public static void setConfidenceOverlayEnabled(Context context, boolean enabled) {
        putBoolean(context, KEY_CONFIDENCE_OVERLAY, enabled);
    }

    public static boolean isColorDescriptionEnabled(Context context) {
        return getBoolean(context, KEY_COLOR_DESCRIPTION, true);
    }

    public static void setColorDescriptionEnabled(Context context, boolean enabled) {
        putBoolean(context, KEY_COLOR_DESCRIPTION, enabled);
    }

    public static List<String> getRecentSearches(Context context) {
        return parseRecentSearches(getString(context, KEY_RECENT_SEARCHES, ""));
    }

    static List<String> parseRecentSearches(String stored) {
        if (stored == null || stored.trim().isEmpty()) return new ArrayList<>();
        List<String> recent = new ArrayList<>();
        for (String item : stored.split("\\|")) {
            if (ObjectCatalog.isSupported(item)) recent.add(item);
        }
        return recent;
    }

    public static void addRecentSearch(Context context, String objectName) {
        // HISTORY SUPPORT: Store a search and update its frequency count when a caller records it.
        if (!isSaveHistoryEnabled(context)) return;
        if (objectName == null || objectName.trim().isEmpty()) return;
        String normalized = objectName.trim().toLowerCase();
        update(context, mutable -> {
            List<String> recent = parseRecentSearches(getString(mutable, KEY_RECENT_SEARCHES));
            recent.removeIf(item -> item.equalsIgnoreCase(normalized));
            recent.add(0, normalized);
            while (recent.size() > MAX_RECENT_SEARCHES) {
                recent.remove(recent.size() - 1);
            }
            mutable.set(PreferencesKeys.stringKey(KEY_RECENT_SEARCHES), String.join("|", recent));
            mutable.set(PreferencesKeys.stringKey(KEY_SEARCH_COUNTS),
                    incrementSearchCount(getString(mutable, KEY_SEARCH_COUNTS), normalized));
        });
    }

    public static void clearRecentSearches(Context context) {
        removeStrings(context, KEY_RECENT_SEARCHES, KEY_SEARCH_COUNTS);
    }

    public static boolean isRecentReminderEnabled(Context context) {
        return getBoolean(context, KEY_RECENT_REMINDER, true);
    }

    public static void setRecentReminderEnabled(Context context, boolean enabled) {
        putBoolean(context, KEY_RECENT_REMINDER, enabled);
    }

    public static boolean isSaveHistoryEnabled(Context context) {
        return getBoolean(context, KEY_SAVE_HISTORY, true);
    }

    public static void setSaveHistoryEnabled(Context context, boolean enabled) {
        putBoolean(context, KEY_SAVE_HISTORY, enabled);
    }

    public static List<String> getFrequentSearches(Context context) {
        return topSearches(getString(context, KEY_SEARCH_COUNTS, ""));
    }

    /** The three most-searched supported objects in a "name:count|name:count" string. */
    static List<String> topSearches(String stored) {
        List<String> items = new ArrayList<>();
        if (stored == null || stored.trim().isEmpty()) return items;
        String[] pairs = stored.split("\\|");
        Arrays.sort(pairs, (a, b) -> Integer.compare(parseCount(b), parseCount(a)));
        for (String pair : pairs) {
            int index = pair.lastIndexOf(':');
            if (index > 0 && ObjectCatalog.isSupported(pair.substring(0, index))) {
                items.add(pair.substring(0, index));
            }
            if (items.size() == 3) break;
        }
        return items;
    }

    static String incrementSearchCount(String stored, String objectName) {
        List<String> pairs = new ArrayList<>();
        boolean updated = false;
        if (stored != null && !stored.trim().isEmpty()) {
            pairs.addAll(Arrays.asList(stored.split("\\|")));
        }
        for (int i = 0; i < pairs.size(); i++) {
            String pair = pairs.get(i);
            int index = pair.lastIndexOf(':');
            if (index > 0 && pair.substring(0, index).equalsIgnoreCase(objectName)) {
                pairs.set(i, objectName + ":" + (parseCount(pair) + 1));
                updated = true;
                break;
            }
        }
        if (!updated) pairs.add(objectName + ":1");
        return String.join("|", pairs);
    }

    private static int parseCount(String pair) {
        int index = pair.lastIndexOf(':');
        if (index < 0 || index == pair.length() - 1) return 0;
        try {
            return Integer.parseInt(pair.substring(index + 1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static int getThemeMode(Context context) {
        return getInt(context, KEY_THEME_MODE, 1); // Default to Dark
    }

    public static void setThemeMode(Context context, int mode) {
        putInt(context, KEY_THEME_MODE, mode);
    }

    public static int getTextSizePercent(Context context) {
        return getInt(context, KEY_TEXT_SIZE, 50); // Default 50%
    }

    public static void setTextSizePercent(Context context, int percent) {
        putInt(context, KEY_TEXT_SIZE, clamp(percent, 0, 100));
    }

    public static float getTextScale(Context context) {
        return 0.85f + (getTextSizePercent(context) / 100f) * 0.45f;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
