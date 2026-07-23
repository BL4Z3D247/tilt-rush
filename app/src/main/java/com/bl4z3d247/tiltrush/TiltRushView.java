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

    private enum State { INTRO, COUNTDOWN, RACING, PAUSED, FINISHED }

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

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final List<Point> track = new ArrayList<>();
    private final List<Cone> cones = new ArrayList<>();
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
    private boolean calibrated;
    private boolean usingAccelerometerFallback;

    private String banner = "";
    private long bannerUntilMillis;

    private final RectF startButton = new RectF();
    private final RectF pauseButton = new RectF();
    private final RectF retryButton = new RectF();

    public TiltRushView(Context context) {
        super(context);
        setFocusable(true);
        setKeepScreenOn(true);
        setBackgroundColor(Color.rgb(5, 7, 12));

        sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY);
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        preferences = context.getSharedPreferences("tilt_rush", Context.MODE_PRIVATE);

        buildTrack();
        resetCar();
    }

    public void onHostResume() {
        Sensor chosen = gravitySensor != null ? gravitySensor : accelerometer;
        usingAccelerometerFallback = gravitySensor == null;
        if (chosen != null) {
            sensorManager.registerListener(this, chosen, SensorManager.SENSOR_DELAY_GAME);
        }
        lastFrameNanos = 0L;
        postInvalidateOnAnimation();
    }

    public void onHostPause() {
        sensorManager.unregisterListener(this);
        if (state == State.RACING) pauseRace();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        long nowNanos = System.nanoTime();
        float dt = lastFrameNanos == 0L ? 0f : Math.min(0.033f, (nowNanos - lastFrameNanos) / 1_000_000_000f);
        lastFrameNanos = nowNanos;
        long nowMillis = System.currentTimeMillis();

        update(dt, nowMillis);
        drawWorld(canvas);
        drawHud(canvas, nowMillis);

        postInvalidateOnAnimation();
    }

    private void update(float dt, long nowMillis) {
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
        float targetThrottle = clamp((calibrationY - filteredScreenY) / 3.6f, -1f, 1f);
        steer = lerp(steer, targetSteer, 1f - (float) Math.pow(0.002, dt));
        throttle = lerp(throttle, targetThrottle, 1f - (float) Math.pow(0.003, dt));

        Nearest nearest = nearestTrackPoint(carX, carY);
        boolean onRoad = nearest.distance < ROAD_WIDTH * 0.5f;

        float acceleration = throttle >= 0f ? 275f * throttle : 440f * throttle;
        carSpeed += acceleration * dt;
        carSpeed -= carSpeed * (onRoad ? 0.48f : 1.7f) * dt;
        carSpeed = clamp(carSpeed, -75f, onRoad ? 520f : 220f);

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

        if (collisionCooldown > 0f) collisionCooldown -= dt;
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

        paint.setShader(new RadialGradient(width * 0.5f, height * 0.58f,
                Math.max(width, height) * 0.8f,
                new int[]{Color.rgb(11, 31, 29), Color.rgb(5, 7, 12)},
                new float[]{0f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, width, height, paint);
        paint.setShader(null);

        drawGrid(canvas);
        drawTrack(canvas);
        for (int index = 0; index < checkpointIndices.length; index++) {
            drawCheckpoint(canvas, index, index == nextCheckpoint);
        }
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
        canvas.save();
        canvas.translate(screen.x, screen.y);

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

        drawPill(canvas, 18, 16, 155, 82, "LAP", lap + " / " + TOTAL_LAPS, false);
        long elapsed = state == State.COUNTDOWN ? 0L
                : nowMillis - raceStartMillis - pausedDurationMillis;
        drawPill(canvas, getWidth() / 2f - 95, 16, 190, 82, "TIME", formatTime(elapsed), false);
        drawPill(canvas, getWidth() - 173, 16, 155, 82, "SPEED",
                Integer.toString(Math.max(0, Math.round(Math.abs(carSpeed) * 0.17f))), true);

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
        drawText(canvas, "Tilt the top edge forward to accelerate; pull it back to brake.",
                left, card.top + 199f, 18, Color.rgb(170, 178, 201), false);
        drawText(canvas, "Hold the phone in your normal playing position, then calibrate.",
                left, card.top + 231f, 18, Color.rgb(170, 178, 201), false);

        startButton.set(card.left + 36f, card.bottom - 92f, card.right - 36f, card.bottom - 30f);
        paint.setShader(new LinearGradient(startButton.left, startButton.top,
                startButton.right, startButton.bottom,
                Color.rgb(124, 248, 255), Color.rgb(85, 183, 255), Shader.TileMode.CLAMP));
        canvas.drawRoundRect(startButton, 18, 18, paint);
        paint.setShader(null);
        drawCenteredText(canvas, "START + CALIBRATE", startButton.centerX(),
                startButton.centerY() + 8f, 21, Color.rgb(3, 16, 20), true);

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
        countdownStartMillis = System.currentTimeMillis();
        state = State.COUNTDOWN;
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

        float screenX;
        float screenY;
        switch (rotation) {
            case Surface.ROTATION_90:
                screenX = -rawScreenY;
                screenY = rawScreenX;
                break;
            case Surface.ROTATION_180:
                screenX = -rawScreenX;
                screenY = -rawScreenY;
                break;
            case Surface.ROTATION_270:
                screenX = rawScreenY;
                screenY = -rawScreenX;
                break;
            case Surface.ROTATION_0:
            default:
                screenX = rawScreenX;
                screenY = rawScreenY;
                break;
        }

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
