package com.vocasee.app;

import java.util.Locale;
import java.util.regex.Pattern;

/** Plain-text parsing of recognized speech: filler removal and yes/no detection. */
final class VoiceCommands {

    // Whole phrases only: a plain replace("find a", "") turned "find apple" into "pple".
    private static final Pattern FILLER = Pattern.compile(
            "\\b(?:please"
                    + "|(?:find|look for|search for)(?: (?:my|the|an|a))?"
                    + "|(?:where is|where's) (?:my|the))\\b");

    // Whole-word matching: a bare contains("ok") accepted "book" as a yes, and
    // contains("no") treated "know" or "nothing" as a no.
    private static final Pattern AFFIRMATIVE = Pattern.compile(
            "\\b(yes|yeah|yep|sure|okay|ok|i am|got it|holding)\\b");
    private static final Pattern NEGATIVE = Pattern.compile(
            "\\b(no|not|nope|don't|dont|do not)\\b");

    private VoiceCommands() {}

    static String normalize(String spoken) {
        String lower = spoken == null ? "" : spoken.toLowerCase(Locale.US).trim();
        return FILLER.matcher(lower).replaceAll(" ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    static boolean isAffirmative(String lower) {
        return lower != null && AFFIRMATIVE.matcher(lower).find();
    }

    static boolean isNegative(String lower) {
        return lower != null && NEGATIVE.matcher(lower).find();
    }

    static boolean containsWord(String text, String word) {
        return text != null && Pattern.compile(
                "\\b" + Pattern.quote(word) + "\\b").matcher(text).find();
    }
}
