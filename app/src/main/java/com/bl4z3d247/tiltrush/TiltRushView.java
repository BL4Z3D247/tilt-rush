package com.bl4z3d247.tiltrush;

import static com.bl4z3d247.tiltrush.GameMath.clamp;
import static com.bl4z3d247.tiltrush.GameMath.formatTime;
import static com.bl4z3d247.tiltrush.GameMath.lerp;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import android.view.Display;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class TiltRushView extends View implements SensorEventListener {
    private static final float TAU = (float) (Math.PI * 2.0);
    private static final float ROAD_WIDTH = 210f;
    private static final int TOTAL_LAPS = 3;
    private static final float GRAVITY = 900f;
    private static final String TAG = "TiltRush";

    private enum State { INTRO, LEARN_FORWARD, COUNTDOWN, RACING, PAUSED, FINISHED, ERROR }

    private static final class Point {
        float x;
        float y;

        Point(float x, float y) {
            this.x = x;
            this.y = y;
        }
    }

    private static final class Cone {
        final float x;
        final float y;
        final float radius;

        Cone(float x, float y, float radius) {
            this.x = x;
            this.y = y;
            this.radius = radius;
        }
    }

    private static final class Ramp {
        final float x;
        final float y;
        final float angle;
        final float halfLength;
        final float halfWidth;

        Ramp(float x, float y, float angle, float halfLength, float halfWidth) {
            this.x = x;
            this.y = y;
            this.angle = angle;
            this.halfLength = halfLength;
            this.halfWidth = halfWidth;
        }
    }

    private static final class Wall {
        final float x1;
        final float y1;
        final float x2;
        final float y2;
        final float thickness;

        Wall(float x1, float y1, float x2, float y2, float thickness) {
            this.x1 = x1;
            this.y1 = y1;
            this.x2 = x2;
            this.y2 = y2;
            this.thickness = thickness;
        }
    }

    private static final class BoostPad {
        final float x;
        final float y;
        final float angle;
        final float halfLength;
        final float halfWidth;
        long nextAvailableMillis;

        BoostPad(float x, float y, float angle, float halfLength, float halfWidth) {
            this.x = x;
            this.y = y;
            this.angle = angle;
            this.halfLength = halfLength;
            this.halfWidth = halfWidth;
        }
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final List<Point> track = new ArrayList<>();
    private final List<Cone> cones = new ArrayList<>();
    private final List<Ramp> ramps = new ArrayList<>();
    private final List<Wall> walls = new ArrayList<>();
    private final List<BoostPad> boostPads = new ArrayList<>();
    private final int[] checkpointIndices = {0, 35, 70, 105, 140, 175, 210, 245};

    private final SensorManager sensorManager;
    private final Sensor gravitySensor;
    private final Sensor accelerometer;
    private final Vibrator vibrator;
    private final SharedPreferences preferences;

    private State state = State.INTRO;
    private long lastFrameNanos;
    private long countdownStartMillis;
    private long raceStartMillis;
    private long pauseStartedMillis;
    private long pausedDurationMillis;
    private long finalElapsedMillis;

    private float carX;
    private float carY;
    private float carAngle;
    private float carSpeed;
    private float carHeight;
    private float verticalSpeed;
    private float boostRemaining;
    private float rampCooldown;
    private float wallCooldown;
    private final float carRadius = 18f;

    private float cameraX;
    private float cameraY;
    private float cameraZoom = 1f;

    private int lap = 1;
    private int nextCheckpoint = 1;
    private float collisionCooldown;

    private float rawScreenX;
    private float rawScreenY;
    private float filteredScreenX;
    private float filteredScreenY;
    private float calibrationX;
    private float calibrationY;
    private float steer;
    private float throttle;
    private float throttleDirection;
    private float forwardLearnLargestDelta;
    private long forwardLearnStartMillis;
    private boolean calibrated;
    private boolean usingAccelerometerFallback;

    private String banner = "";
    private long bannerUntilMillis;
    private String runtimeError = "";

    private final RectF startButton = new RectF();
    private final RectF pauseButton = new RectF();
    private final RectF retryButton = new RectF();

    public TiltRushView(Context context) {
        super(context);
        setFocusable(true);
        setKeepScreenOn(true);
        setBackgroundColor(Color.rgb(5, 7, 12));

        sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        gravitySensor = sensorManager == null ? null : sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY);
        accelerometer = sensorManager == null ? null : sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        preferences = context.getSharedPreferences("tilt_rush", Context.MODE_PRIVATE);
        throttleDirection = preferences.getFloat("throttle_direction", -1f);

        buildTrack();
        resetCar();
    }

    public void onHostResume() {
        Sensor chosen = gravitySensor != null ? gravitySensor : accelerometer;
        usingAccelerometerFallback = gravitySensor == null;
        if (chosen != null && sensorManager != null) {
            sensorManager.registerListener(this, chosen, SensorManager.SENSOR_DELAY_GAME);
        }
        lastFrameNanos = 0L;
        postInvalidateOnAnimation();
    }

    public void onHostPause() {
        if (sensorManager != null) sensorManager.unregisterListener(this);
        if (state == State.RACING) pauseRace();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (getWidth() <= 0 || getHeight() <= 0) {
            postInvalidateOnAnimation();
            return;
        }

        try {
            long nowNanos = System.nanoTime();
            float dt = lastFrameNanos == 0L ? 0f
                    : Math.min(0.033f, (nowNanos - lastFrameNanos) / 1_000_000_000f);
            lastFrameNanos = nowNanos;
            long nowMillis = System.currentTimeMillis();

            if (state != State.ERROR) {
                update(dt, nowMillis);
                drawWorld(canvas);
                drawHud(canvas, nowMillis);
            } else {
                drawRuntimeError(canvas);
            }
        } catch (Throwable error) {
            Log.e(TAG, "Game frame failed", error);
            runtimeError = Log.getStackTraceString(error);
            if (runtimeError.length() > 2800) runtimeError = runtimeError.substring(0, 2800);
            state = State.ERROR;
            drawRuntimeError(canvas);
        }

        postInvalidateOnAnimation();
    }

    private void update(float dt, long nowMillis) {
        if (state == State.LEARN_FORWARD) {
            long learnElapsed = nowMillis - forwardLearnStartMillis;
            if (learnElapsed >= 550L) {
                float delta = filteredScreenY - calibrationY;
                if (Math.abs(delta) > Math.abs(forwardLearnLargestDelta)) {
                    forwardLearnLargestDelta = delta;
                }
            }
            if (learnElapsed >= 2_350L) {
                throttleDirection = GameMath.learnedForwardDirection(
                        forwardLearnLargestDelta, throttleDirection, 0.35f);
                preferences.edit().putFloat("throttle_direction", throttleDirection).apply();
                countdownStartMillis = nowMillis;
                state = State.COUNTDOWN;
                showBanner("CENTER THE PHONE", 900L);
                vibrate(30L);
            }
            return;
        }

        if (state == State.COUNTDOWN) {
            if (nowMillis - countdownStartMillis >= 3_000L) {
                state = State.RACING;
                raceStartMillis = nowMillis;
                showBanner("GO!", 850L);
            }
            return;
        }

        if (state != State.RACING) return;

        float targetSteer = clamp((filteredScreenX - calibrationX) / 3.8f, -1f, 1f);
        float targetThrottle = GameMath.throttleFromScreenTilt(
                filteredScreenY, calibrationY, 3.6f, throttleDirection);
        steer = lerp(steer, targetSteer, 1f - (float) Math.pow(0.002, dt));
        throttle = lerp(throttle, targetThrottle, 1f - (float) Math.pow(0.003, dt));

        boostRemaining = Math.max(0f, boostRemaining - dt);
        rampCooldown = Math.max(0f, rampCooldown - dt);
        wallCooldown = Math.max(0f, wallCooldown - dt);
        if (carHeight > 0f || verticalSpeed > 0f) {
            verticalSpeed -= GRAVITY * dt;
            carHeight += verticalSpeed * dt;
            if (carHeight <= 0f) {
                carHeight = 0f;
                verticalSpeed = 0f;
                vibrate(22L);
            }
        }

        Nearest nearest = nearestTrackPoint(carX, carY);
        boolean onRoad = nearest.distance < ROAD_WIDTH * 0.5f;

        float acceleration = throttle >= 0f ? 275f * throttle : 440f * throttle;
        carSpeed += acceleration * dt;
        if (boostRemaining > 0f && carSpeed > 0f) carSpeed += 235f * dt;
        carSpeed -= carSpeed * (onRoad ? 0.48f : 1.7f) * dt;
        float topSpeed = boostRemaining > 0f ? 735f : 520f;
        carSpeed = clamp(carSpeed, -75f, onRoad ? topSpeed : 220f);

        float speedFactor = clamp(Math.abs(carSpeed) / 180f, 0.2f, 1.6f);
        carAngle += steer * 2.25f * speedFactor * dt * (carSpeed >= 0f ? 1f : -1f);
        carX += Math.cos(carAngle) * carSpeed * dt;
        carY += Math.sin(carAngle) * carSpeed * dt;

        if (nearest.distance > ROAD_WIDTH * 0.82f) {
            float dx = nearest.x - carX;
            float dy = nearest.y - carY;
            float length = length(dx, dy);
            carX += dx / length * 82f * dt;
            carY += dy / length * 82f * dt;
        }

        if (rampCooldown <= 0f && carHeight < 4f && carSpeed > 145f) {
            for (Ramp ramp : ramps) {
                if (insideOrientedRectangle(carX, carY, ramp.x, ramp.y, ramp.angle,
                        ramp.halfLength, ramp.halfWidth)) {
                    carHeight = 2f;
                    verticalSpeed = clamp(Math.abs(carSpeed) * 0.72f, 255f, 450f);
                    carSpeed = Math.min(carSpeed + 55f, 650f);
                    rampCooldown = 1.15f;
                    showBanner("AIR!", 700L);
                    vibrate(30L);
                    break;
                }
            }
        }

        if (carHeight < 12f) {
            for (BoostPad pad : boostPads) {
                if (nowMillis >= pad.nextAvailableMillis
                        && insideOrientedRectangle(carX, carY, pad.x, pad.y, pad.angle,
                        pad.halfLength, pad.halfWidth)) {
                    if (carSpeed >= 0f) carSpeed = Math.max(carSpeed, 570f);
                    boostRemaining = 1.65f;
                    pad.nextAvailableMillis = nowMillis + 2_600L;
                    showBanner("BOOST!", 750L);
                    vibrate(45L);
                    break;
                }
            }
        }

        if (carHeight < 26f) resolveWallCollisions();

        if (collisionCooldown > 0f) collisionCooldown -= dt;
        if (carHeight < 22f) {
            for (Cone cone : cones) {
                float dx = carX - cone.x;
                float dy = carY - cone.y;
                float distance = length(dx, dy);
                if (distance < carRadius + cone.radius && collisionCooldown <= 0f) {
                    carSpeed *= -0.25f;
                    carX += dx / distance * 18f;
                    carY += dy / distance * 18f;
                    collisionCooldown = 0.5f;
                    showBanner("HIT!", 450L);
                    vibrate(35L);
                }
            }
        }

        Point checkpoint = pointAt(checkpointIndices[nextCheckpoint]);
        if (distance(carX, carY, checkpoint.x, checkpoint.y) < 115f) {
            nextCheckpoint++;
            showBanner("GATE", 350L);
            if (nextCheckpoint >= checkpointIndices.length) nextCheckpoint = 0;
        }

        if (nextCheckpoint == 0) {
            Point start = pointAt(0);
            if (distance(carX, carY, start.x, start.y) < 100f) {
                if (lap >= TOTAL_LAPS) {
                    finishRace(nowMillis);
                } else {
                    lap++;
                    nextCheckpoint = 1;
                    showBanner("LAP " + lap, 900L);
                    vibrate(55L);
                }
            }
        }

        cameraX = lerp(cameraX, carX + (float) Math.cos(carAngle) * 110f,
                1f - (float) Math.pow(0.02, dt));
        cameraY = lerp(cameraY, carY + (float) Math.sin(carAngle) * 110f,
                1f - (float) Math.pow(0.02, dt));
        cameraZoom = lerp(cameraZoom,
                clamp(1.16f - Math.abs(carSpeed) / 1_200f, 0.78f, 1.08f),
                1f - (float) Math.pow(0.08, dt));
    }

    private void drawWorld(Canvas canvas) {
        int width = getWidth();
        int height = getHeight();

        float gradientRadius = Math.max(1f, Math.max(width, height) * 0.8f);
        paint.setShader(new RadialGradient(width * 0.5f, height * 0.58f,
                gradientRadius,
                new int[]{Color.rgb(11, 31, 29), Color.rgb(5, 7, 12)},
                new float[]{0f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, width, height, paint);
        paint.setShader(null);

        drawGrid(canvas);
        drawTrack(canvas);
        for (int index = 0; index < checkpointIndices.length; index++) {
            drawCheckpoint(canvas, index, index == nextCheckpoint);
        }
        drawBoostPads(canvas);
        drawRamps(canvas);
        drawWalls(canvas);
        drawCones(canvas);
        drawCar(canvas);
        drawDirectionArrow(canvas);
    }

    private void drawGrid(Canvas canvas) {
        float spacing = 70f * cameraZoom;
        paint.setColor(Color.argb(24, 99, 230, 183));
        paint.setStrokeWidth(1f);
        float xOffset = positiveModulo(-cameraX * cameraZoom, spacing);
        float yOffset = positiveModulo(-cameraY * cameraZoom, spacing);
        for (float x = xOffset; x < getWidth(); x += spacing) canvas.drawLine(x, 0, x, getHeight(), paint);
        for (float y = yOffset; y < getHeight(); y += spacing) canvas.drawLine(0, y, getWidth(), y, paint);
    }

    private void drawTrack(Canvas canvas) {
        drawTrackLine(canvas, ROAD_WIDTH + 28f, Color.rgb(18, 21, 31));
        drawTrackLine(canvas, ROAD_WIDTH, Color.rgb(47, 54, 71));
        drawTrackLine(canvas, ROAD_WIDTH - 20f, Color.rgb(34, 39, 54));

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(4f * cameraZoom);
        paint.setColor(Color.argb(95, 122, 245, 255));
        paint.setPathEffect(new android.graphics.DashPathEffect(
                new float[]{22f * cameraZoom, 26f * cameraZoom}, 0f));
        buildScreenTrackPath();
        canvas.drawPath(path, paint);
        paint.setPathEffect(null);
        paint.setStyle(Paint.Style.FILL);

        for (int i = 0; i < track.size(); i += 5) {
            Point point = pointAt(i);
            Point normal = normalAt(i);
            for (int side : new int[]{-1, 1}) {
                Point screen = worldToScreen(point.x + normal.x * (ROAD_WIDTH / 2f - 5f) * side,
                        point.y + normal.y * (ROAD_WIDTH / 2f - 5f) * side);
                paint.setColor(i % 10 == 0 ? Color.rgb(255, 85, 119) : Color.rgb(98, 238, 255));
                canvas.drawCircle(screen.x, screen.y, 2.6f * cameraZoom, paint);
            }
        }
    }

    private void drawTrackLine(Canvas canvas, float width, int color) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(width * cameraZoom);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(color);
        buildScreenTrackPath();
        canvas.drawPath(path, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private void buildScreenTrackPath() {
        path.reset();
        for (int i = 0; i < track.size(); i++) {
            Point screen = worldToScreen(track.get(i).x, track.get(i).y);
            if (i == 0) path.moveTo(screen.x, screen.y);
            else path.lineTo(screen.x, screen.y);
        }
        Point start = worldToScreen(track.get(0).x, track.get(0).y);
        path.lineTo(start.x, start.y);
    }

    private void drawCheckpoint(Canvas canvas, int checkpoint, boolean active) {
        int trackIndex = checkpointIndices[checkpoint];
        Point point = pointAt(trackIndex);
        Point normal = normalAt(trackIndex);
        Point a = worldToScreen(point.x + normal.x * ROAD_WIDTH * 0.48f,
                point.y + normal.y * ROAD_WIDTH * 0.48f);
        Point b = worldToScreen(point.x - normal.x * ROAD_WIDTH * 0.48f,
                point.y - normal.y * ROAD_WIDTH * 0.48f);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth((active ? 8f : 4f) * cameraZoom);
        paint.setColor(active ? Color.rgb(157, 255, 122) : Color.argb(50, 255, 255, 255));
        paint.setPathEffect(new android.graphics.DashPathEffect(
                active ? new float[]{10f, 7f} : new float[]{4f, 10f}, 0f));
        canvas.drawLine(a.x, a.y, b.x, b.y, paint);
        paint.setPathEffect(null);
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawBoostPads(Canvas canvas) {
        for (BoostPad pad : boostPads) {
            boolean ready = System.currentTimeMillis() >= pad.nextAvailableMillis;
            int outer = ready ? Color.argb(170, 35, 245, 255) : Color.argb(80, 80, 105, 120);
            int inner = ready ? Color.rgb(70, 255, 229) : Color.rgb(70, 85, 95);
            drawOrientedRectangle(canvas, pad.x, pad.y, pad.angle,
                    pad.halfLength, pad.halfWidth, outer);
            drawOrientedRectangle(canvas, pad.x, pad.y, pad.angle,
                    pad.halfLength * 0.72f, pad.halfWidth * 0.72f, inner);

            float cosine = (float) Math.cos(pad.angle);
            float sine = (float) Math.sin(pad.angle);
            for (int i = -1; i <= 1; i++) {
                float offset = i * pad.halfLength * 0.42f;
                float centerX = pad.x + cosine * offset;
                float centerY = pad.y + sine * offset;
                Point tip = worldToScreen(centerX + cosine * 24f, centerY + sine * 24f);
                Point left = worldToScreen(centerX - cosine * 14f - sine * 16f,
                        centerY - sine * 14f + cosine * 16f);
                Point right = worldToScreen(centerX - cosine * 14f + sine * 16f,
                        centerY - sine * 14f - cosine * 16f);
                path.reset();
                path.moveTo(tip.x, tip.y);
                path.lineTo(left.x, left.y);
                path.lineTo(right.x, right.y);
                path.close();
                paint.setColor(ready ? Color.WHITE : Color.argb(80, 255, 255, 255));
                canvas.drawPath(path, paint);
            }
        }
    }

    private void drawRamps(Canvas canvas) {
        for (Ramp ramp : ramps) {
            drawOrientedRectangle(canvas, ramp.x, ramp.y, ramp.angle,
                    ramp.halfLength + 7f, ramp.halfWidth + 7f, Color.rgb(51, 24, 12));
            drawOrientedRectangle(canvas, ramp.x, ramp.y, ramp.angle,
                    ramp.halfLength, ramp.halfWidth, Color.rgb(224, 112, 42));

            float cosine = (float) Math.cos(ramp.angle);
            float sine = (float) Math.sin(ramp.angle);
            for (int stripe = -2; stripe <= 2; stripe++) {
                float along = stripe * ramp.halfLength * 0.38f;
                float cx = ramp.x + cosine * along;
                float cy = ramp.y + sine * along;
                Point a = worldToScreen(cx - sine * ramp.halfWidth, cy + cosine * ramp.halfWidth);
                Point b = worldToScreen(cx + sine * ramp.halfWidth, cy - cosine * ramp.halfWidth);
                paint.setStrokeWidth(7f * cameraZoom);
                paint.setColor(Color.argb(180, 255, 229, 170));
                canvas.drawLine(a.x, a.y, b.x, b.y, paint);
            }
        }
    }

    private void drawWalls(Canvas canvas) {
        paint.setStrokeCap(Paint.Cap.ROUND);
        for (Wall wall : walls) {
            Point a = worldToScreen(wall.x1, wall.y1);
            Point b = worldToScreen(wall.x2, wall.y2);
            paint.setStrokeWidth((wall.thickness * 2f + 8f) * cameraZoom);
            paint.setColor(Color.rgb(18, 20, 27));
            canvas.drawLine(a.x, a.y, b.x, b.y, paint);
            paint.setStrokeWidth(wall.thickness * 2f * cameraZoom);
            paint.setColor(Color.rgb(178, 185, 200));
            canvas.drawLine(a.x, a.y, b.x, b.y, paint);
            paint.setStrokeWidth(3f * cameraZoom);
            paint.setColor(Color.rgb(245, 250, 255));
            canvas.drawLine(a.x, a.y, b.x, b.y, paint);
        }
    }

    private void drawOrientedRectangle(Canvas canvas, float centerX, float centerY,
                                       float angle, float halfLength, float halfWidth, int color) {
        float cosine = (float) Math.cos(angle);
        float sine = (float) Math.sin(angle);
        float[][] local = {
                {halfLength, halfWidth}, {halfLength, -halfWidth},
                {-halfLength, -halfWidth}, {-halfLength, halfWidth}
        };
        path.reset();
        for (int i = 0; i < local.length; i++) {
            float worldX = centerX + cosine * local[i][0] - sine * local[i][1];
            float worldY = centerY + sine * local[i][0] + cosine * local[i][1];
            Point screen = worldToScreen(worldX, worldY);
            if (i == 0) path.moveTo(screen.x, screen.y);
            else path.lineTo(screen.x, screen.y);
        }
        path.close();
        paint.setColor(color);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawPath(path, paint);
    }

    private void drawCones(Canvas canvas) {
        for (Cone cone : cones) {
            Point screen = worldToScreen(cone.x, cone.y);
            if (screen.x < -40 || screen.x > getWidth() + 40
                    || screen.y < -40 || screen.y > getHeight() + 40) continue;

            canvas.save();
            canvas.translate(screen.x, screen.y);
            canvas.scale(cameraZoom, cameraZoom);
            path.reset();
            path.moveTo(0, -15);
            path.lineTo(12, 13);
            path.lineTo(-12, 13);
            path.close();
            paint.setColor(Color.rgb(255, 123, 53));
            canvas.drawPath(path, paint);
            paint.setColor(Color.rgb(255, 230, 191));
            canvas.drawRect(-8, 1, 8, 5, paint);
            canvas.restore();
        }
    }

    private void drawCar(Canvas canvas) {
        Point screen = worldToScreen(carX, carY);
        float lift = carHeight * 0.34f;
        float scale = 1f + Math.min(0.18f, carHeight / 520f);

        paint.setColor(Color.argb((int) clamp(95f - carHeight * 0.25f, 25f, 95f), 0, 0, 0));
        canvas.drawOval(screen.x - 20f * scale + lift * 0.18f,
                screen.y - 8f * scale + lift * 0.38f,
                screen.x + 20f * scale + lift * 0.18f,
                screen.y + 25f * scale + lift * 0.38f, paint);

        canvas.save();
        canvas.translate(screen.x, screen.y - lift);
        canvas.scale(scale, scale);

        paint.setShader(new LinearGradient(0, -30, 0, 30,
                new int[]{Color.rgb(232, 253, 255), Color.rgb(103, 233, 255), Color.rgb(19, 116, 255)},
                null, Shader.TileMode.CLAMP));
        path.reset();
        path.moveTo(0, -30);
        path.quadTo(15, -20, 18, 3);
        path.lineTo(13, 24);
        path.quadTo(0, 32, -13, 24);
        path.lineTo(-18, 3);
        path.quadTo(-15, -20, 0, -30);
        path.close();
        canvas.drawPath(path, paint);
        paint.setShader(null);

        paint.setColor(Color.rgb(7, 18, 29));
        path.reset();
        path.moveTo(0, -16);
        path.lineTo(10, -2);
        path.lineTo(7, 11);
        path.lineTo(-7, 11);
        path.lineTo(-10, -2);
        path.close();
        canvas.drawPath(path, paint);

        paint.setColor(Color.rgb(255, 79, 121));
        canvas.drawRect(-13, 19, -5, 23, paint);
        canvas.drawRect(5, 19, 13, 23, paint);

        if (carSpeed > 80f) {
            paint.setColor(Color.rgb(141, 255, 248));
            float flame = 45f + (float) Math.random() * 12f;
            path.reset();
            path.moveTo(-8, 27);
            path.lineTo(-2, 27);
            path.lineTo(-5, flame);
            path.close();
            canvas.drawPath(path, paint);
            path.reset();
            path.moveTo(2, 27);
            path.lineTo(8, 27);
            path.lineTo(5, flame);
            path.close();
            canvas.drawPath(path, paint);
        }
        canvas.restore();
    }

    private void drawDirectionArrow(Canvas canvas) {
        if (state != State.RACING) return;
        Point checkpoint = pointAt(checkpointIndices[nextCheckpoint]);
        Point screen = worldToScreen(checkpoint.x, checkpoint.y);
        float margin = 70f;
        if (screen.x > margin && screen.x < getWidth() - margin
                && screen.y > margin && screen.y < getHeight() - margin) return;

        float centerX = getWidth() * 0.5f;
        float centerY = getHeight() * 0.52f;
        float angle = (float) Math.atan2(screen.y - centerY, screen.x - centerX);
        float radius = Math.min(getWidth(), getHeight()) * 0.36f;
        float arrowX = clamp(centerX + (float) Math.cos(angle) * radius, margin, getWidth() - margin);
        float arrowY = clamp(centerY + (float) Math.sin(angle) * radius, margin, getHeight() - margin);

        canvas.save();
        canvas.translate(arrowX, arrowY);
        canvas.rotate((float) Math.toDegrees(angle) + 90f);
        paint.setColor(Color.rgb(157, 255, 122));
        path.reset();
        path.moveTo(0, -14);
        path.lineTo(11, 10);
        path.lineTo(0, 5);
        path.lineTo(-11, 10);
        path.close();
        canvas.drawPath(path, paint);
        canvas.restore();
    }

    private void drawHud(Canvas canvas, long nowMillis) {
        if (state == State.INTRO) {
            drawIntro(canvas);
            return;
        }
        if (state == State.FINISHED) {
            drawFinish(canvas);
            return;
        }
        if (state == State.LEARN_FORWARD) {
            drawForwardCalibration(canvas, nowMillis);
            return;
        }

        drawPill(canvas, 18, 16, 155, 82, "LAP", lap + " / " + TOTAL_LAPS, false);
        long elapsed = state == State.COUNTDOWN ? 0L
                : nowMillis - raceStartMillis - pausedDurationMillis;
        drawPill(canvas, getWidth() / 2f - 95, 16, 190, 82, "TIME", formatTime(elapsed), false);
        drawPill(canvas, getWidth() - 173, 16, 155, 82, "SPEED",
                Integer.toString(Math.max(0, Math.round(Math.abs(carSpeed) * 0.17f))), true);

        String power = carHeight > 1f ? "AIR" : boostRemaining > 0f ? "BOOST" : "READY";
        int powerColor = carHeight > 1f ? Color.rgb(255, 196, 92)
                : boostRemaining > 0f ? Color.rgb(70, 255, 229) : Color.rgb(170, 178, 201);
        drawText(canvas, power, 22f, getHeight() - 23f, 17f, powerColor, true);

        pauseButton.set(getWidth() - 75, 112, getWidth() - 18, 169);
        paint.setColor(Color.argb(210, 8, 12, 24));
        canvas.drawRoundRect(pauseButton, 14, 14, paint);
        drawCenteredText(canvas, state == State.PAUSED ? "▶" : "Ⅱ",
                pauseButton.centerX(), pauseButton.centerY() + 9, 27, Color.WHITE, true);

        if (state == State.COUNTDOWN) {
            long remaining = 3_000L - (nowMillis - countdownStartMillis);
            int count = Math.max(1, (int) Math.ceil(remaining / 1_000f));
            drawCenteredText(canvas, Integer.toString(count), getWidth() / 2f,
                    getHeight() * 0.34f, 82, Color.WHITE, true);
        }

        if (state == State.PAUSED) {
            paint.setColor(Color.argb(150, 0, 0, 0));
            canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
            drawCenteredText(canvas, "PAUSED", getWidth() / 2f, getHeight() / 2f,
                    58, Color.WHITE, true);
        }

        if (nowMillis < bannerUntilMillis && !banner.isEmpty()) {
            drawCenteredText(canvas, banner, getWidth() / 2f, getHeight() * 0.29f,
                    52, Color.WHITE, true);
        }

        float meterWidth = Math.min(260f, getWidth() * 0.34f);
        float meterLeft = getWidth() / 2f - meterWidth / 2f;
        float meterTop = getHeight() - 35f;
        paint.setColor(Color.argb(55, 255, 255, 255));
        canvas.drawRoundRect(meterLeft, meterTop, meterLeft + meterWidth, meterTop + 9, 6, 6, paint);
        paint.setColor(Color.rgb(100, 240, 255));
        float dotX = meterLeft + meterWidth * (0.5f + steer * 0.46f);
        canvas.drawCircle(dotX, meterTop + 4.5f, 8f, paint);
    }

    private void drawIntro(Canvas canvas) {
        paint.setColor(Color.argb(150, 2, 4, 10));
        canvas.drawRect(0, 0, getWidth(), getHeight(), paint);

        float cardWidth = Math.min(660f, getWidth() - 80f);
        float cardHeight = Math.min(430f, getHeight() - 50f);
        RectF card = new RectF(getWidth() / 2f - cardWidth / 2f,
                getHeight() / 2f - cardHeight / 2f,
                getWidth() / 2f + cardWidth / 2f,
                getHeight() / 2f + cardHeight / 2f);
        paint.setColor(Color.argb(238, 8, 11, 21));
        canvas.drawRoundRect(card, 28, 28, paint);

        float left = card.left + 36f;
        drawText(canvas, "NATIVE TILT RACER", left, card.top + 45f, 15,
                Color.rgb(100, 240, 255), true);
        drawText(canvas, "TILT RUSH", left, card.top + 112f, 58, Color.WHITE, true);
        drawText(canvas, "Tilt left and right to steer.", left, card.top + 165f, 22,
                Color.rgb(212, 220, 238), false);
        drawText(canvas, "Tilt forward to accelerate; pull back to brake.",
                left, card.top + 199f, 18, Color.rgb(170, 178, 201), false);
        drawText(canvas, "The game learns your phone's forward direction at startup.",
                left, card.top + 231f, 18, Color.rgb(170, 178, 201), false);

        startButton.set(card.left + 36f, card.bottom - 92f, card.right - 36f, card.bottom - 30f);
        paint.setShader(new LinearGradient(startButton.left, startButton.top,
                startButton.right, startButton.bottom,
                Color.rgb(124, 248, 255), Color.rgb(85, 183, 255), Shader.TileMode.CLAMP));
        canvas.drawRoundRect(startButton, 18, 18, paint);
        paint.setShader(null);
        drawCenteredText(canvas, "START + CALIBRATE", startButton.centerX(),
                startButton.centerY() + 8f, 21, Color.rgb(3, 16, 20), true);
        drawText(canvas, "v0.2.2", card.right - 82f, card.top + 43f, 14,
                Color.rgb(130, 140, 165), true);

        if (gravitySensor == null && accelerometer == null) {
            drawCenteredText(canvas, "No motion sensor detected on this device.",
                    card.centerX(), card.bottom - 112f, 16, Color.rgb(255, 95, 122), true);
        }
    }

    private void drawFinish(Canvas canvas) {
        paint.setColor(Color.argb(175, 2, 4, 10));
        canvas.drawRect(0, 0, getWidth(), getHeight(), paint);

        float width = Math.min(560f, getWidth() - 80f);
        float height = Math.min(350f, getHeight() - 50f);
        RectF card = new RectF(getWidth() / 2f - width / 2f,
                getHeight() / 2f - height / 2f,
                getWidth() / 2f + width / 2f,
                getHeight() / 2f + height / 2f);
        paint.setColor(Color.argb(240, 8, 11, 21));
        canvas.drawRoundRect(card, 28, 28, paint);

        long best = preferences.getLong("best_time", finalElapsedMillis);
        boolean newBest = finalElapsedMillis <= best;
        drawCenteredText(canvas, newBest ? "NEW BEST" : "FINISH", card.centerX(),
                card.top + 80f, 52, Color.WHITE, true);
        drawCenteredText(canvas, "Time  " + formatTime(finalElapsedMillis), card.centerX(),
                card.top + 132f, 22, Color.rgb(212, 220, 238), true);
        drawCenteredText(canvas, "Best  " + formatTime(best), card.centerX(),
                card.top + 168f, 20, Color.rgb(170, 178, 201), false);

        retryButton.set(card.left + 36f, card.bottom - 88f, card.right - 36f, card.bottom - 28f);
        paint.setColor(Color.rgb(100, 240, 255));
        canvas.drawRoundRect(retryButton, 18, 18, paint);
        drawCenteredText(canvas, "RACE AGAIN", retryButton.centerX(),
                retryButton.centerY() + 8, 21, Color.rgb(3, 16, 20), true);
    }


    private void drawForwardCalibration(Canvas canvas, long nowMillis) {
        paint.setColor(Color.argb(170, 2, 4, 10));
        canvas.drawRect(0, 0, getWidth(), getHeight(), paint);

        float width = Math.min(680f, getWidth() - 80f);
        float height = Math.min(300f, getHeight() - 50f);
        RectF card = new RectF(getWidth() / 2f - width / 2f,
                getHeight() / 2f - height / 2f,
                getWidth() / 2f + width / 2f,
                getHeight() / 2f + height / 2f);
        paint.setColor(Color.argb(242, 8, 11, 21));
        canvas.drawRoundRect(card, 28, 28, paint);

        long elapsed = nowMillis - forwardLearnStartMillis;
        String heading = elapsed < 550L ? "HOLD YOUR NORMAL POSITION" : "TILT FORWARD NOW";
        String instruction = elapsed < 550L
                ? "Keep the phone steady while Tilt Rush captures neutral."
                : "Push the top edge of the phone away from you and hold it.";
        drawCenteredText(canvas, heading, card.centerX(), card.top + 82f,
                elapsed < 550L ? 32f : 42f, Color.WHITE, true);
        drawCenteredText(canvas, instruction,
                card.centerX(), card.top + 128f, 18f, Color.rgb(190, 199, 220), false);
        drawCenteredText(canvas, "Tilt Rush is learning which sensor direction means forward.",
                card.centerX(), card.top + 159f, 16f, Color.rgb(150, 161, 188), false);

        float progress = clamp((nowMillis - forwardLearnStartMillis) / 2_350f, 0f, 1f);
        RectF bar = new RectF(card.left + 52f, card.bottom - 70f,
                card.right - 52f, card.bottom - 48f);
        paint.setColor(Color.argb(55, 255, 255, 255));
        canvas.drawRoundRect(bar, 11f, 11f, paint);
        paint.setColor(Color.rgb(100, 240, 255));
        canvas.drawRoundRect(new RectF(bar.left, bar.top,
                bar.left + bar.width() * progress, bar.bottom), 11f, 11f, paint);
    }

    private void drawPill(Canvas canvas, float x, float y, float width, float height,
                          String label, String value, boolean rightAligned) {
        RectF pill = new RectF(x, y, x + width, y + height);
        paint.setColor(Color.argb(205, 8, 12, 24));
        canvas.drawRoundRect(pill, 17, 17, paint);
        float textX = rightAligned ? pill.right - 14f : pill.left + 14f;
        Paint.Align align = rightAligned ? Paint.Align.RIGHT : Paint.Align.LEFT;
        drawTextAligned(canvas, label, textX, pill.top + 25f, 12,
                Color.rgb(170, 178, 201), true, align);
        drawTextAligned(canvas, value, textX, pill.top + 59f, 25,
                Color.WHITE, true, align);
    }

    private void drawText(Canvas canvas, String text, float x, float y, float size,
                          int color, boolean bold) {
        drawTextAligned(canvas, text, x, y, size, color, bold, Paint.Align.LEFT);
    }

    private void drawCenteredText(Canvas canvas, String text, float x, float y, float size,
                                  int color, boolean bold) {
        drawTextAligned(canvas, text, x, y, size, color, bold, Paint.Align.CENTER);
    }

    private void drawTextAligned(Canvas canvas, String text, float x, float y, float size,
                                 int color, boolean bold, Paint.Align align) {
        textPaint.setTextSize(size);
        textPaint.setColor(color);
        textPaint.setTextAlign(align);
        textPaint.setTypeface(bold ? android.graphics.Typeface.DEFAULT_BOLD
                : android.graphics.Typeface.DEFAULT);
        canvas.drawText(text, x, y, textPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() != MotionEvent.ACTION_UP) return true;
        float x = event.getX();
        float y = event.getY();

        if (state == State.INTRO && startButton.contains(x, y)) {
            calibrateAndStart();
            return true;
        }
        if (state == State.FINISHED && retryButton.contains(x, y)) {
            calibrateAndStart();
            return true;
        }
        if ((state == State.RACING || state == State.PAUSED) && pauseButton.contains(x, y)) {
            if (state == State.RACING) pauseRace();
            else resumeRace();
            return true;
        }
        return true;
    }

    private void calibrateAndStart() {
        calibrationX = filteredScreenX;
        calibrationY = filteredScreenY;
        calibrated = true;
        resetCar();
        lap = 1;
        nextCheckpoint = 1;
        pausedDurationMillis = 0L;
        forwardLearnLargestDelta = 0f;
        forwardLearnStartMillis = System.currentTimeMillis();
        state = State.LEARN_FORWARD;
        vibrate(35L);
    }

    private void pauseRace() {
        state = State.PAUSED;
        pauseStartedMillis = System.currentTimeMillis();
    }

    private void resumeRace() {
        pausedDurationMillis += System.currentTimeMillis() - pauseStartedMillis;
        state = State.RACING;
        lastFrameNanos = 0L;
    }

    private void finishRace(long nowMillis) {
        state = State.FINISHED;
        carSpeed = 0f;
        finalElapsedMillis = nowMillis - raceStartMillis - pausedDurationMillis;
        long previous = preferences.getLong("best_time", 0L);
        if (previous == 0L || finalElapsedMillis < previous) {
            preferences.edit().putLong("best_time", finalElapsedMillis).apply();
        }
        vibratePattern();
    }

    private void resetCar() {
        Point start = pointAt(0);
        Point tangent = tangentAt(0);
        carX = start.x;
        carY = start.y;
        carAngle = (float) Math.atan2(tangent.y, tangent.x);
        carSpeed = 0f;
        cameraX = carX;
        cameraY = carY;
        cameraZoom = 1f;
        steer = 0f;
        throttle = 0f;
        carHeight = 0f;
        verticalSpeed = 0f;
        boostRemaining = 0f;
        rampCooldown = 0f;
        wallCooldown = 0f;
        for (BoostPad pad : boostPads) pad.nextAvailableMillis = 0L;
    }

    private void showBanner(String text, long durationMillis) {
        banner = text;
        bannerUntilMillis = System.currentTimeMillis() + durationMillis;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        float x = event.values[0];
        float y = event.values[1];

        if (usingAccelerometerFallback) {
            rawScreenX = rawScreenX * 0.82f + x * 0.18f;
            rawScreenY = rawScreenY * 0.82f + y * 0.18f;
        } else {
            rawScreenX = x;
            rawScreenY = y;
        }

        int rotation = Surface.ROTATION_0;
        Display display = getDisplay();
        if (display != null) rotation = display.getRotation();

        float screenX = GameMath.remapScreenX(rawScreenX, rawScreenY, rotation);
        float screenY = GameMath.remapScreenY(rawScreenX, rawScreenY, rotation);

        filteredScreenX = filteredScreenX * 0.78f + screenX * 0.22f;
        filteredScreenY = filteredScreenY * 0.78f + screenY * 0.22f;

        if (!calibrated && state == State.INTRO) {
            calibrationX = filteredScreenX;
            calibrationY = filteredScreenY;
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // No action needed. The controls are calibrated relative to the current pose.
    }

    private void buildTrack() {
        Point[] control = {
                new Point(0, 0), new Point(700, -120), new Point(1180, 380),
                new Point(980, 1040), new Point(260, 1240), new Point(-420, 980),
                new Point(-760, 380), new Point(-520, -280), new Point(40, -620),
                new Point(700, -520)
        };

        for (int i = 0; i < control.length; i++) {
            Point p0 = control[(i - 1 + control.length) % control.length];
            Point p1 = control[i];
            Point p2 = control[(i + 1) % control.length];
            Point p3 = control[(i + 2) % control.length];
            for (int sample = 0; sample < 28; sample++) {
                track.add(catmull(p0, p1, p2, p3, sample / 28f));
            }
        }

        for (int i = 18; i < track.size(); i += 31) {
            Point point = pointAt(i);
            Point normal = normalAt(i);
            cones.add(new Cone(point.x + normal.x * 70f,
                    point.y + normal.y * 70f, 16f));
        }

        addRamp(55, 70f, 68f);
        addRamp(188, 72f, 70f);

        addBoostPad(22, 64f, 58f);
        addBoostPad(120, 64f, 58f);
        addBoostPad(226, 64f, 58f);

        addWallRun(76, 108, 1);
        addWallRun(148, 181, -1);
        addWallRun(218, 252, 1);
        addWallRun(218, 252, -1);
    }

    private void addRamp(int trackIndex, float halfLength, float halfWidth) {
        Point point = pointAt(trackIndex);
        Point tangent = tangentAt(trackIndex);
        ramps.add(new Ramp(point.x, point.y,
                (float) Math.atan2(tangent.y, tangent.x), halfLength, halfWidth));
    }

    private void addBoostPad(int trackIndex, float halfLength, float halfWidth) {
        Point point = pointAt(trackIndex);
        Point tangent = tangentAt(trackIndex);
        boostPads.add(new BoostPad(point.x, point.y,
                (float) Math.atan2(tangent.y, tangent.x), halfLength, halfWidth));
    }

    private void addWallRun(int startIndex, int endIndex, int side) {
        float offset = ROAD_WIDTH * 0.47f;
        for (int index = startIndex; index < endIndex; index += 5) {
            Point first = pointAt(index);
            Point second = pointAt(Math.min(index + 5, endIndex));
            Point firstNormal = normalAt(index);
            Point secondNormal = normalAt(Math.min(index + 5, endIndex));
            walls.add(new Wall(
                    first.x + firstNormal.x * offset * side,
                    first.y + firstNormal.y * offset * side,
                    second.x + secondNormal.x * offset * side,
                    second.y + secondNormal.y * offset * side,
                    10f));
        }
    }

    private void resolveWallCollisions() {
        for (Wall wall : walls) {
            float segmentX = wall.x2 - wall.x1;
            float segmentY = wall.y2 - wall.y1;
            float segmentLengthSquared = segmentX * segmentX + segmentY * segmentY;
            float projection = segmentLengthSquared <= 0.0001f ? 0f
                    : ((carX - wall.x1) * segmentX + (carY - wall.y1) * segmentY)
                    / segmentLengthSquared;
            projection = clamp(projection, 0f, 1f);
            float closestX = wall.x1 + segmentX * projection;
            float closestY = wall.y1 + segmentY * projection;
            float dx = carX - closestX;
            float dy = carY - closestY;
            float distance = length(dx, dy);
            float minimumDistance = carRadius + wall.thickness;
            if (distance < minimumDistance) {
                float normalX = dx / distance;
                float normalY = dy / distance;
                float penetration = minimumDistance - distance + 1f;
                carX += normalX * penetration;
                carY += normalY * penetration;

                if (wallCooldown <= 0f) {
                    float velocityX = (float) Math.cos(carAngle) * carSpeed;
                    float velocityY = (float) Math.sin(carAngle) * carSpeed;
                    float intoWall = velocityX * normalX + velocityY * normalY;
                    if (intoWall < 0f) {
                        velocityX -= 1.65f * intoWall * normalX;
                        velocityY -= 1.65f * intoWall * normalY;
                        carAngle = (float) Math.atan2(velocityY, velocityX);
                        carSpeed = Math.min(length(velocityX, velocityY) * 0.58f, 390f);
                    } else {
                        carSpeed *= 0.62f;
                    }
                    wallCooldown = 0.32f;
                    boostRemaining = 0f;
                    showBanner("WALL!", 420L);
                    vibrate(45L);
                }
            }
        }
    }

    private static boolean insideOrientedRectangle(float pointX, float pointY,
                                                    float centerX, float centerY,
                                                    float angle, float halfLength,
                                                    float halfWidth) {
        float dx = pointX - centerX;
        float dy = pointY - centerY;
        float cosine = (float) Math.cos(angle);
        float sine = (float) Math.sin(angle);
        float along = dx * cosine + dy * sine;
        float across = -dx * sine + dy * cosine;
        return Math.abs(along) <= halfLength && Math.abs(across) <= halfWidth;
    }

    private static Point catmull(Point p0, Point p1, Point p2, Point p3, float t) {
        float t2 = t * t;
        float t3 = t2 * t;
        float x = 0.5f * ((2f * p1.x) + (-p0.x + p2.x) * t
                + (2f * p0.x - 5f * p1.x + 4f * p2.x - p3.x) * t2
                + (-p0.x + 3f * p1.x - 3f * p2.x + p3.x) * t3);
        float y = 0.5f * ((2f * p1.y) + (-p0.y + p2.y) * t
                + (2f * p0.y - 5f * p1.y + 4f * p2.y - p3.y) * t2
                + (-p0.y + 3f * p1.y - 3f * p2.y + p3.y) * t3);
        return new Point(x, y);
    }

    private Point pointAt(int index) {
        int size = track.size();
        int wrapped = ((index % size) + size) % size;
        return track.get(wrapped);
    }

    private Point tangentAt(int index) {
        Point before = pointAt(index - 1);
        Point after = pointAt(index + 1);
        float dx = after.x - before.x;
        float dy = after.y - before.y;
        float length = length(dx, dy);
        return new Point(dx / length, dy / length);
    }

    private Point normalAt(int index) {
        Point tangent = tangentAt(index);
        return new Point(-tangent.y, tangent.x);
    }

    private Point worldToScreen(float worldX, float worldY) {
        float rotation = -carAngle + (float) Math.PI / 2f;
        float cosine = (float) Math.cos(rotation);
        float sine = (float) Math.sin(rotation);
        float dx = (worldX - cameraX) * cameraZoom;
        float dy = (worldY - cameraY) * cameraZoom;
        return new Point(getWidth() / 2f + dx * cosine - dy * sine,
                getHeight() * 0.62f + dx * sine + dy * cosine);
    }

    private Nearest nearestTrackPoint(float x, float y) {
        Nearest best = new Nearest(Float.MAX_VALUE, 0f, 0f);
        for (Point point : track) {
            float distanceSquared = square(point.x - x) + square(point.y - y);
            if (distanceSquared < best.distanceSquared) {
                best.distanceSquared = distanceSquared;
                best.x = point.x;
                best.y = point.y;
            }
        }
        best.distance = (float) Math.sqrt(best.distanceSquared);
        return best;
    }

    private static final class Nearest {
        float distanceSquared;
        float distance;
        float x;
        float y;

        Nearest(float distanceSquared, float x, float y) {
            this.distanceSquared = distanceSquared;
            this.x = x;
            this.y = y;
        }
    }

    private static float distance(float x1, float y1, float x2, float y2) {
        return length(x2 - x1, y2 - y1);
    }

    private static float length(float x, float y) {
        return Math.max(0.0001f, (float) Math.sqrt(x * x + y * y));
    }

    private static float square(float value) {
        return value * value;
    }

    private static float positiveModulo(float value, float modulus) {
        return ((value % modulus) + modulus) % modulus;
    }

    private void drawRuntimeError(Canvas canvas) {
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.rgb(8, 11, 21));
        canvas.drawRect(0, 0, getWidth(), getHeight(), paint);

        drawText(canvas, "TILT RUSH ERROR", 28f, 50f, 24f,
                Color.rgb(255, 95, 122), true);
        drawText(canvas, "The app stayed open so the real crash can be photographed.",
                28f, 82f, 16f, Color.WHITE, false);

        String safe = runtimeError == null || runtimeError.isEmpty()
                ? "Unknown rendering error" : runtimeError;
        String[] lines = safe.split("\n");
        float y = 116f;
        int shown = 0;
        for (String line : lines) {
            if (shown >= 18 || y > getHeight() - 20f) break;
            String clipped = line.length() > 110 ? line.substring(0, 110) : line;
            drawText(canvas, clipped, 28f, y, 13f, Color.rgb(212, 220, 238), false);
            y += 20f;
            shown++;
        }
    }

    private void vibrate(long milliseconds) {
        if (vibrator == null || !vibrator.hasVibrator()) return;
        if (Build.VERSION.SDK_INT >= 26) {
            vibrator.vibrate(VibrationEffect.createOneShot(milliseconds,
                    VibrationEffect.DEFAULT_AMPLITUDE));
        } else {
            vibrator.vibrate(milliseconds);
        }
    }

    private void vibratePattern() {
        if (vibrator == null || !vibrator.hasVibrator()) return;
        long[] pattern = {0, 80, 60, 160};
        if (Build.VERSION.SDK_INT >= 26) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1));
        } else {
            vibrator.vibrate(pattern, -1);
        }
    }
}
