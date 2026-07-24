package com.bl4z3d247.tiltrush;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class GameMathTest {
    @Test
    public void clampRestrictsBothEnds() {
        assertEquals(-1f, GameMath.clamp(-2f, -1f, 1f), 0.001f);
        assertEquals(1f, GameMath.clamp(2f, -1f, 1f), 0.001f);
    }

    @Test
    public void landscapeNinetyMapsDeviceAxesToScreenAxes() {
        assertEquals(7f, GameMath.remapScreenX(3f, 7f, 1), 0.001f);
        assertEquals(-3f, GameMath.remapScreenY(3f, 7f, 1), 0.001f);
    }

    @Test
    public void reverseLandscapeMapsDeviceAxesToScreenAxes() {
        assertEquals(-7f, GameMath.remapScreenX(3f, 7f, 3), 0.001f);
        assertEquals(3f, GameMath.remapScreenY(3f, 7f, 3), 0.001f);
    }

    @Test
    public void positiveSensorDeltaCanBeLearnedAsForward() {
        assertEquals(1f, GameMath.throttleFromScreenTilt(7.6f, 4f, 3.6f, 1f), 0.001f);
    }

    @Test
    public void negativeSensorDeltaCanBeLearnedAsForward() {
        assertEquals(1f, GameMath.throttleFromScreenTilt(0.4f, 4f, 3.6f, -1f), 0.001f);
    }

    @Test
    public void oppositeTiltBecomesBrakeAfterLearning() {
        assertEquals(-1f, GameMath.throttleFromScreenTilt(7.6f, 4f, 3.6f, -1f), 0.001f);
    }

    @Test
    public void neutralPoseProducesNoThrottle() {
        assertEquals(0f, GameMath.throttleFromScreenTilt(4f, 4f, 3.6f, -1f), 0.001f);
    }

    @Test
    public void forwardLearningUsesTheObservedSign() {
        assertEquals(-1f, GameMath.learnedForwardDirection(-2.1f, 1f, 0.35f), 0.001f);
        assertEquals(1f, GameMath.learnedForwardDirection(2.1f, -1f, 0.35f), 0.001f);
    }

    @Test
    public void weakForwardLearningKeepsFallback() {
        assertEquals(-1f, GameMath.learnedForwardDirection(0.1f, -1f, 0.35f), 0.001f);
    }

    @Test
    public void timeFormattingIsRaceFriendly() {
        assertEquals("1:02.345", GameMath.formatTime(62_345L));
    }
}
