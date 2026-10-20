package com.vocasee.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

public class ObjectCatalogTest {

    @Test
    public void classOrderMatchesTrainedYoloModel() {
        assertEquals(Arrays.asList(
                "backpack", "book", "cup", "glasses", "headset",
                "key", "phone", "remote", "wallet", "water bottle",
                "body spray", "card", "charger", "comb", "flashlight",
                "glasses case", "medicine", "nail clipper", "shoe", "watch",
                "body scrub", "cane", "lighter", "towel"), ObjectCatalog.PREDEFINED_OBJECTS);
    }

    @Test
    public void everyCanonicalNameResolvesToItsOwnIndex() {
        for (int i = 0; i < ObjectCatalog.PREDEFINED_OBJECTS.size(); i++) {
            String name = ObjectCatalog.PREDEFINED_OBJECTS.get(i);
            assertEquals(name, ObjectCatalog.resolve(name));
            assertEquals(i, ObjectCatalog.classIndexOf(ObjectCatalog.resolve(name)));
        }
    }

    @Test
    public void longerPhraseWinsOverContainedShorterOne() {
        assertEquals(15, index("glasses case"));
        assertEquals(15, index("find my sunglasses case please"));
        assertEquals(15, index("eyeglass case"));
        assertEquals(3, index("glasses"));
        assertEquals(3, index("sunglasses"));
        assertEquals(12, index("phone charger"));
        assertEquals(6, index("my phone"));
        assertEquals(9, index("water bottle"));
        assertEquals(9, index("bottle"));
        assertEquals(17, index("nail cutter"));
        assertEquals(7, index("the tv remote"));
    }

    @Test
    public void wholeWordsOnlyNoSubstringCollisions() {
        assertEquals(2, index("drinking glass"));
        assertEquals(2, index("glass"));
        assertEquals(3, index("glasses")); // "glass" must not match inside "glasses"
        assertEquals(4, index("headphones")); // not "phone" inside "headphones"
        assertNull(ObjectCatalog.resolve("keyboard"));
        assertNull(ObjectCatalog.resolve("cupboard"));
        assertNull(ObjectCatalog.resolve("hurricane"));
        assertNull(ObjectCatalog.resolve("cardboard"));
        assertNull(ObjectCatalog.resolve("bookshelf"));
    }

    @Test
    public void requestedSynonymsResolve() {
        assertEquals("cup", ObjectCatalog.resolve("mug"));
        assertEquals("cup", ObjectCatalog.resolve("tumbler"));
        assertEquals("glasses", ObjectCatalog.resolve("specs"));
        assertEquals("water bottle", ObjectCatalog.resolve("flask"));
        assertEquals("water bottle", ObjectCatalog.resolve("water jug"));
        assertEquals("body spray", ObjectCatalog.resolve("deodorant spray"));
        assertEquals("body spray", ObjectCatalog.resolve("mist"));
        assertEquals("body scrub", ObjectCatalog.resolve("exfoliator"));
        assertEquals("nail clipper", ObjectCatalog.resolve("clippers"));
        assertEquals("headset", ObjectCatalog.resolve("earbuds"));
        assertEquals("phone", ObjectCatalog.resolve("cellphone"));
        assertEquals("remote", ObjectCatalog.resolve("remote control"));
        assertEquals("charger", ObjectCatalog.resolve("adapter"));
        assertEquals("cane", ObjectCatalog.resolve("walking stick"));
        assertEquals("medicine", ObjectCatalog.resolve("gamot"));
    }

    @Test
    public void normalizesCaseAndPunctuation() {
        assertEquals("glasses case", ObjectCatalog.resolve("  Glasses-Case! "));
        assertEquals("water bottle", ObjectCatalog.resolve("WATER   BOTTLE"));
        assertNull(ObjectCatalog.resolve(""));
        assertNull(ObjectCatalog.resolve(null));
        assertNull(ObjectCatalog.resolve("umbrella"));
    }

    @Test
    public void everyClassHasATypicalSize() {
        for (String name : ObjectCatalog.PREDEFINED_OBJECTS) {
            float meters = ObjectCatalog.typicalSizeMeters(name);
            assertTrue(name, meters > 0.02f && meters < 2f);
        }
        assertEquals(-1f, ObjectCatalog.typicalSizeMeters("umbrella"), 0f);
        assertEquals(-1f, ObjectCatalog.typicalSizeMeters(null), 0f);
    }

    private static int index(String query) {
        return ObjectCatalog.classIndexOf(ObjectCatalog.resolve(query));
    }
}
