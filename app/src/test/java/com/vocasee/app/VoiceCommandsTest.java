package com.vocasee.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class VoiceCommandsTest {

    @Test
    public void fillerPhrasesAreRemoved() {
        assertEquals("cup", VoiceCommands.normalize("Find a cup please"));
        assertEquals("remote", VoiceCommands.normalize("look for the remote"));
        assertEquals("umbrella", VoiceCommands.normalize("Search for an umbrella"));
        assertEquals("keys", VoiceCommands.normalize("Where's my keys"));
        assertEquals("wallet", VoiceCommands.normalize("where is the wallet"));
    }

    @Test
    public void fillerIsRemovedOnlyAsWholeWords() {
        // A substring replace of "find a" used to turn this into "pple".
        assertEquals("apple", VoiceCommands.normalize("find apple"));
        assertEquals("findings", VoiceCommands.normalize("findings"));
        assertEquals("backpack", VoiceCommands.normalize("find backpack"));
    }

    @Test
    public void normalizeHandlesEmptyInput() {
        assertEquals("", VoiceCommands.normalize(null));
        assertEquals("", VoiceCommands.normalize("   "));
        assertEquals("", VoiceCommands.normalize("find"));
    }

    @Test
    public void normalizedRequestsResolveToCatalogObjects() {
        assertEquals("glasses case",
                ObjectCatalog.resolve(VoiceCommands.normalize("find the glasses case")));
        assertEquals("water bottle",
                ObjectCatalog.resolve(VoiceCommands.normalize("please find my water bottle")));
    }

    @Test
    public void yesAndNoMatchWholeWordsOnly() {
        assertTrue(VoiceCommands.isAffirmative("yes"));
        assertTrue(VoiceCommands.isAffirmative("okay i got it"));
        assertFalse(VoiceCommands.isAffirmative("book"));
        assertTrue(VoiceCommands.isNegative("no"));
        assertTrue(VoiceCommands.isNegative("do not"));
        assertFalse(VoiceCommands.isNegative("i know"));
        assertFalse(VoiceCommands.isNegative("nothing"));
        assertFalse(VoiceCommands.isAffirmative(null));
        assertFalse(VoiceCommands.isNegative(null));
    }

    @Test
    public void containsWordIgnoresPartialWords() {
        assertTrue(VoiceCommands.containsWord("open settings", "settings"));
        assertFalse(VoiceCommands.containsWord("settingsmenu", "settings"));
        assertFalse(VoiceCommands.containsWord(null, "settings"));
    }
}
