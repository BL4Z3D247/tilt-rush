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

    static float dot3(float ax, float ay, float az, float bx, float by, float bz) {
        return ax * bx + ay * by + az * bz;
    }

    static float magnitude3(float x, float y, float z) {
        return (float) Math.sqrt(x * x + y * y + z * z);
    }

    /**
     * Converts the change from the neutral gravity vector into throttle by
     * projecting it onto the exact 3D forward gesture learned from the player.
     */
    static float throttleFromVector(float deltaX, float deltaY, float deltaZ,
                                    float forwardAxisX, float forwardAxisY,
                                    float forwardAxisZ, float fullScaleDelta) {
        float axisMagnitude = magnitude3(forwardAxisX, forwardAxisY, forwardAxisZ);
        if (axisMagnitude < 0.0001f) return 0f;

        float normalizedX = forwardAxisX / axisMagnitude;
        float normalizedY = forwardAxisY / axisMagnitude;
        float normalizedZ = forwardAxisZ / axisMagnitude;
        float projection = dot3(deltaX, deltaY, deltaZ,
                normalizedX, normalizedY, normalizedZ);
        float safeScale = Math.max(0.001f, Math.abs(fullScaleDelta));
        return clamp(projection / safeScale, -1f, 1f);
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

    /**
     * Rotates a world vector into the camera frame. The car's forward heading
     * must map toward the top of the display because the ship sprite points up.
     */
    static float worldVectorToScreenX(float worldDx, float worldDy, float headingRadians) {
        float rotation = -headingRadians - (float) Math.PI / 2f;
        float cosine = (float) Math.cos(rotation);
        float sine = (float) Math.sin(rotation);
        return worldDx * cosine - worldDy * sine;
    }

    static float worldVectorToScreenY(float worldDx, float worldDy, float headingRadians) {
        float rotation = -headingRadians - (float) Math.PI / 2f;
        float cosine = (float) Math.cos(rotation);
        float sine = (float) Math.sin(rotation);
        return worldDx * sine + worldDy * cosine;
    }

    static String formatTime(long millis) {
        long safe = Math.max(0L, millis);
        long minutes = safe / 60_000L;
        long seconds = (safe / 1_000L) % 60L;
        long milliseconds = safe % 1_000L;
        return String.format(Locale.US, "%d:%02d.%03d", minutes, seconds, milliseconds);
    }
}
