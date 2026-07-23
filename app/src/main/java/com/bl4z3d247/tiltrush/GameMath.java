package com.bl4z3d247.tiltrush;

import java.util.Locale;

final class GameMath {
    private GameMath() {}

    static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    static float lerp(float from, float to, float amount) {
        return from + (to - from) * amount;
    }

    static String formatTime(long millis) {
        long safe = Math.max(0L, millis);
        long minutes = safe / 60_000L;
        long seconds = (safe / 1_000L) % 60L;
        long milliseconds = safe % 1_000L;
        return String.format(Locale.US, "%d:%02d.%03d", minutes, seconds, milliseconds);
    }
}
