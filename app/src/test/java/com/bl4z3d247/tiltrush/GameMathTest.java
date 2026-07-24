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
    public void learnedThreeAxisForwardGestureProducesPositiveThrottle() {
        // The learned forward gesture spans Y and Z, which the old one-axis
        // implementation could not interpret reliably.
        float magnitude = GameMath.magnitude3(0f, -2f, 3f);
        assertEquals(1f, GameMath.throttleFromVector(
                0f, -2f, 3f,
                0f, -2f / magnitude, 3f / magnitude,
                magnitude), 0.001f);
    }

    @Test
    public void oppositeThreeAxisGestureProducesReverseThrottle() {
        float magnitude = GameMath.magnitude3(1f, -2f, 2f);
        assertEquals(-1f, GameMath.throttleFromVector(
                -1f, 2f, -2f,
                1f / magnitude, -2f / magnitude, 2f / magnitude,
                magnitude), 0.001f);
    }

    @Test
    public void perpendicularTiltDoesNotAccelerate() {
        assertEquals(0f, GameMath.throttleFromVector(
                3f, 0f, 0f,
                0f, 1f, 0f,
                3f), 0.001f);
    }

    @Test
    public void carForwardHeadingRendersTowardTopOfScreen() {
        float heading = 0f;
        assertEquals(0f, GameMath.worldVectorToScreenX(1f, 0f, heading), 0.001f);
        assertEquals(-1f, GameMath.worldVectorToScreenY(1f, 0f, heading), 0.001f);
    }

    @Test
    public void carRightSideRendersTowardRightOfScreen() {
        float heading = 0f;
        assertEquals(1f, GameMath.worldVectorToScreenX(0f, 1f, heading), 0.001f);
        assertEquals(0f, GameMath.worldVectorToScreenY(0f, 1f, heading), 0.001f);
    }

    @Test
    public void worldBehindCarRendersBelowShip() {
        float heading = 0f;
        assertEquals(1f, GameMath.worldVectorToScreenY(-1f, 0f, heading), 0.001f);
    }

    @Test
    public void timeFormattingIsRaceFriendly() {
        assertEquals("1:02.345", GameMath.formatTime(62_345L));
    }
}
