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
    public void timeFormattingIsRaceFriendly() {
        assertEquals("1:02.345", GameMath.formatTime(62_345L));
    }
}
