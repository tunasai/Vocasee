package com.vocasee.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

public class SearchHistoryTest {

    @Test
    public void recentSearchesSkipUnsupportedAndEmptyEntries() {
        assertEquals(Arrays.asList("cup", "key"),
                AppPreferences.parseRecentSearches("cup|spaceship|key"));
        assertTrue(AppPreferences.parseRecentSearches("").isEmpty());
        assertTrue(AppPreferences.parseRecentSearches(null).isEmpty());
    }

    @Test
    public void searchCountsIncrementOrAppend() {
        assertEquals("cup:1", AppPreferences.incrementSearchCount("", "cup"));
        assertEquals("cup:3|key:1", AppPreferences.incrementSearchCount("cup:2|key:1", "cup"));
        assertEquals("cup:2|key:1", AppPreferences.incrementSearchCount("cup:2", "key"));
        // A multi-word name keeps its space; the count is after the last colon.
        assertEquals("water bottle:2",
                AppPreferences.incrementSearchCount("water bottle:1", "water bottle"));
    }

    @Test
    public void topSearchesAreTheThreeMostFrequentSupportedObjects() {
        assertEquals(Arrays.asList("key", "cup", "book"),
                AppPreferences.topSearches("cup:3|book:1|key:9|spaceship:20|phone:0"));
        assertTrue(AppPreferences.topSearches("").isEmpty());
    }

    @Test
    public void corruptCountsAreTreatedAsZero() {
        assertEquals("cup:1", AppPreferences.incrementSearchCount("cup:abc", "cup"));
    }
}
