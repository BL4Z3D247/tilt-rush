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

    /**
     * Converts a calibrated screen-relative pitch delta into throttle.
     * forwardDirection is learned from the player's actual forward tilt and
     * is therefore either +1 or -1 depending on the device/orientation.
     */
    static float throttleFromScreenTilt(float currentScreenY, float calibratedScreenY,
                                        float fullScaleDelta, float forwardDirection) {
        float safeScale = Math.max(0.001f, Math.abs(fullScaleDelta));
        float safeDirection = forwardDirection < 0f ? -1f : 1f;
        return clamp(((currentScreenY - calibratedScreenY) / safeScale) * safeDirection,
                -1f, 1f);
    }

    static float learnedForwardDirection(float largestForwardDelta, float fallbackDirection,
                                         float minimumUsefulDelta) {
        if (Math.abs(largestForwardDelta) < Math.abs(minimumUsefulDelta)) {
            return fallbackDirection < 0f ? -1f : 1f;
        }
        return largestForwardDelta < 0f ? -1f : 1f;
    }

    static float remapScreenX(float rawX, float rawY, int rotationQuarterTurns) {
        switch (rotationQuarterTurns) {
            case 1: return rawY;
            case 2: return -rawX;
            case 3: return -rawY;
            case 0:
            default: return rawX;
        }
    }

    static float remapScreenY(float rawX, float rawY, int rotationQuarterTurns) {
        switch (rotationQuarterTurns) {
            case 1: return -rawX;
            case 2: return -rawY;
            case 3: return rawX;
            case 0:
            default: return rawY;
        }
    }

    static String formatTime(long millis) {
        long safe = Math.max(0L, millis);
        long minutes = safe / 60_000L;
        long seconds = (safe / 1_000L) % 60L;
        long milliseconds = safe % 1_000L;
        return String.format(Locale.US, "%d:%02d.%03d", minutes, seconds, milliseconds);
    }
}
