package com.vocasee.app;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

public final class ObjectCatalog {
    // DO NOT reorder: list position IS the YOLO class index (0-23) of the supplied
    // 24-class YOLO26n export. TFLiteDetector reads its LABELS array straight from this.
    public static final List<String> PREDEFINED_OBJECTS = Arrays.asList(
            "backpack", "book", "cup", "glasses", "headset",
            "key", "phone", "remote", "wallet", "water bottle",
            "body spray", "card", "charger", "comb", "flashlight",
            "glasses case", "medicine", "nail clipper", "shoe", "watch",
            "body scrub", "cane", "lighter", "towel"
    );

    /** Synonyms per class. Every canonical name also matches itself. */
    private static final Map<String, List<String>> ALIASES = new LinkedHashMap<>();
    static {
        alias("backpack", "backpacks", "back pack", "school bag", "rucksack", "knapsack", "bag");
        alias("book", "books", "paperback", "hardcover");
        alias("cup", "cups", "glass", "drinking glass", "mug", "mugs", "tumbler", "coffee cup");
        alias("glasses", "eyeglasses", "eye glasses", "spectacles", "sunglasses", "specs");
        alias("headset", "headsets", "headphones", "headphone", "earphones", "earphone",
                "earbuds", "earbud");
        alias("key", "keys", "house key", "car key", "keychain", "key chain");
        alias("phone", "phones", "smartphone", "cellphone", "cell phone", "mobile phone",
                "mobile");
        alias("remote", "remotes", "remote control", "tv remote");
        alias("wallet", "wallets", "purse", "coin purse");
        alias("water bottle", "water bottles", "bottle", "bottles", "flask", "water jug",
                "tumbler bottle");
        alias("body spray", "deodorant spray", "deodorant", "mist", "body mist",
                "perfume spray", "perfume", "cologne");
        alias("card", "cards", "id card", "credit card", "atm card", "debit card");
        alias("charger", "chargers", "charging cable", "charger cable", "usb charger", "adapter",
                "phone charger", "charging brick");
        alias("comb", "combs", "hair comb");
        alias("flashlight", "flashlights", "flash light", "torch");
        alias("glasses case", "eyeglass case", "eyeglasses case", "spectacle case",
                "spectacles case", "sunglass case", "sunglasses case", "glass case", "specs case");
        alias("medicine", "medicines", "medication", "meds", "pill", "pills", "tablet", "tablets",
                "capsule", "capsules", "gamot");
        alias("nail clipper", "nail clippers", "nail cutter", "clippers", "clipper");
        alias("shoe", "shoes", "sneaker", "sneakers");
        alias("watch", "watches", "wristwatch", "wrist watch", "smartwatch");
        alias("body scrub", "scrub", "scrub jar", "exfoliator", "body exfoliant");
        alias("cane", "walking stick", "walking cane", "white cane");
        alias("lighter", "lighters", "cigarette lighter");
        alias("towel", "towels", "hand towel", "bath towel");
    }

    /**
     * Typical largest physical dimension of each class, in meters. A box's apparent size
     * only means "close" relative to the object's real size: a backpack fills as much of
     * the view at one meter as a key does at ten centimeters.
     */
    private static final Map<String, Float> TYPICAL_SIZE_METERS = new LinkedHashMap<>();
    static {
        size("backpack", 0.45f);     size("book", 0.24f);          size("cup", 0.10f);
        size("glasses", 0.14f);      size("headset", 0.18f);       size("key", 0.07f);
        size("phone", 0.15f);        size("remote", 0.18f);        size("wallet", 0.11f);
        size("water bottle", 0.24f); size("body spray", 0.15f);    size("card", 0.086f);
        size("charger", 0.10f);      size("comb", 0.18f);          size("flashlight", 0.15f);
        size("glasses case", 0.16f); size("medicine", 0.10f);      size("nail clipper", 0.07f);
        size("shoe", 0.28f);         size("watch", 0.12f);         size("body scrub", 0.10f);
        size("cane", 0.90f);         size("lighter", 0.08f);       size("towel", 0.50f);
    }

    private static void size(String canonical, float meters) {
        if (!PREDEFINED_OBJECTS.contains(canonical)) {
            throw new IllegalStateException("Size for unknown class: " + canonical);
        }
        TYPICAL_SIZE_METERS.put(canonical, meters);
    }

    /** Typical largest dimension in meters, or -1 when the class is unknown. */
    public static float typicalSizeMeters(String canonical) {
        Float meters = canonical == null ? null : TYPICAL_SIZE_METERS.get(canonical);
        return meters == null ? -1f : meters;
    }

    private static final class PhraseMatcher {
        final String phrase;
        final String canonical;
        final Pattern pattern;

        PhraseMatcher(String phrase, String canonical) {
            this.phrase = phrase;
            this.canonical = canonical;
            // Whole-word match so "glass" cannot hit inside "glasses" and "key" cannot hit
            // inside "keyboard"; any run of whitespace may separate the words.
            this.pattern = Pattern.compile("\\b"
                    + String.join("\\s+", quoteWords(phrase)) + "\\b");
        }
    }

    // Longest phrase first, so "glasses case" (15) wins over "glasses" (3) and
    // "phone charger" (12) wins over "phone" (6).
    private static final List<PhraseMatcher> MATCHERS = buildMatchers();

    private static void alias(String canonical, String... synonyms) {
        if (!PREDEFINED_OBJECTS.contains(canonical)) {
            throw new IllegalStateException("Alias for unknown class: " + canonical);
        }
        ALIASES.put(canonical, Collections.unmodifiableList(Arrays.asList(synonyms)));
    }

    private static List<PhraseMatcher> buildMatchers() {
        Map<String, String> phraseToCanonical = new LinkedHashMap<>();
        for (String canonical : PREDEFINED_OBJECTS) {
            register(phraseToCanonical, canonical, canonical);
            List<String> synonyms = ALIASES.get(canonical);
            if (synonyms == null) continue;
            for (String synonym : synonyms) register(phraseToCanonical, synonym, canonical);
        }
        List<PhraseMatcher> matchers = new ArrayList<>();
        for (Map.Entry<String, String> entry : phraseToCanonical.entrySet()) {
            matchers.add(new PhraseMatcher(entry.getKey(), entry.getValue()));
        }
        matchers.sort((a, b) -> a.phrase.length() != b.phrase.length()
                ? Integer.compare(b.phrase.length(), a.phrase.length())
                : a.phrase.compareTo(b.phrase));
        return Collections.unmodifiableList(matchers);
    }

    private static void register(Map<String, String> phraseToCanonical, String phrase, String canonical) {
        String key = normalize(phrase);
        String existing = phraseToCanonical.put(key, canonical);
        if (existing != null && !existing.equals(canonical)) {
            throw new IllegalStateException("Phrase \"" + key + "\" maps to both "
                    + existing + " and " + canonical);
        }
    }

    private static List<String> quoteWords(String phrase) {
        List<String> quoted = new ArrayList<>();
        for (String word : phrase.split(" ")) quoted.add(Pattern.quote(word));
        return quoted;
    }

    /** Lower-cases and turns punctuation such as hyphens into spaces ("body-spray"). */
    static String normalize(String text) {
        if (text == null) return "";
        return text.toLowerCase(Locale.US)
                .replaceAll("[^a-z0-9']+", " ")
                .trim();
    }

    /**
     * Resolves a tap label, typed query, or speech transcript to its canonical class name,
     * or null when no supported object is mentioned.
     */
    public static String resolve(String query) {
        String normalized = normalize(query);
        if (normalized.isEmpty()) return null;
        for (PhraseMatcher matcher : MATCHERS) {
            if (matcher.pattern.matcher(normalized).find()) return matcher.canonical;
        }
        return null;
    }

    /** YOLO class index (0-23) for a canonical name, or -1. */
    public static int classIndexOf(String canonical) {
        return canonical == null ? -1 : PREDEFINED_OBJECTS.indexOf(canonical);
    }

    public static boolean isSupported(String objectName) {
        if (objectName == null) return false;
        for (String supportedObject : PREDEFINED_OBJECTS) {
            if (supportedObject.equalsIgnoreCase(objectName.trim())) return true;
        }
        return false;
    }

    private ObjectCatalog() {}
}
