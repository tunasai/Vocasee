package com.vocasee.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SpeechWatchdogTest {

    @Test
    public void allowsTwiceTheExpectedSpeakingTimePlusMargin() {
        // 100 characters at 65 ms each at normal rate: 6.5 s expected, so 4 s + 13 s.
        assertEquals(17_000L, VoiceFeedbackManager.speechWatchdogTimeoutMs(100, 1.0f));
    }

    @Test
    public void slowerSpeechGetsMoreTime() {
        assertTrue(VoiceFeedbackManager.speechWatchdogTimeoutMs(100, 0.5f)
                > VoiceFeedbackManager.speechWatchdogTimeoutMs(100, 1.0f));
    }

    @Test
    public void timeoutIsCappedAndHasAFloor() {
        assertEquals(60_000L, VoiceFeedbackManager.speechWatchdogTimeoutMs(10_000, 0.5f));
        assertEquals(4_000L, VoiceFeedbackManager.speechWatchdogTimeoutMs(0, 1.0f));
        // A zero rate must not divide by zero; it is clamped to the slowest rate.
        assertEquals(60_000L, VoiceFeedbackManager.speechWatchdogTimeoutMs(500, 0f));
    }
}
