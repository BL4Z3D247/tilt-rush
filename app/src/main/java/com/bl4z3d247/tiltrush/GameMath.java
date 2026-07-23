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
     * Converts screen-relative pitch into throttle. After calibration, tilting
     * the top edge of the phone forward increases screen Y on the supported
     * landscape mappings, so a positive delta must mean forward acceleration.
     */
    static float throttleFromScreenTilt(float currentScreenY, float calibratedScreenY,
                                        float fullScaleDelta) {
        float safeScale = Math.max(0.001f, Math.abs(fullScaleDelta));
        return clamp((currentScreenY - calibratedScreenY) / safeScale, -1f, 1f);
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
