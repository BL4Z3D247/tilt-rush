# Tilt Rush

A native Android tilt-controlled arcade racing game.

## Controls

**v0.3.0 fixes the actual forward-motion bug.**

1. Hold the phone in landscape in your normal playing position.
2. Tap **START + CALIBRATE**.
3. Keep the phone steady during **HOLD YOUR NORMAL POSITION**.
4. During **TILT FORWARD NOW**, push the top edge of the phone away from you and hold it there.
5. Return to your normal position during the countdown.

Tilt left or right to steer. Tilt forward to accelerate. Pull back to brake and then reverse.

The bottom-right HUD explicitly displays `FORWARD`, `COAST`, or `BRAKE / REVERSE`, so the interpreted input is visible while testing.

## Why v0.2.2 was still wrong

There were two separate issues:

- Forward calibration only inspected one screen axis and guessed its sign. Some phone poses distribute a forward tilt across multiple gravity-vector axes.
- More importantly, the camera transform mapped the car's positive world-forward direction toward the bottom of the screen while the ship graphic pointed toward the top. Positive speed therefore looked like backward travel.

v0.3.0 learns the complete 3D gravity-vector change and renders the car's forward vector in the same direction as its visible nose.

## Build

```bash
./gradlew test assembleDebug
```

The debug APK is produced at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

The included GitHub Actions workflow runs the tests and uploads the APK as `tilt-rush-debug-apk` after each push to `main`.

## Features

- Native Android gravity-sensor controls with accelerometer fallback
- Full 3-axis neutral/forward calibration
- Landscape immersive mode
- Tilt steering, acceleration, braking, and reverse
- Three-lap checkpoint course
- Launch ramps and airborne physics
- Concrete wall sections with collision response
- Reusable speed-boost pads
- Off-road slowdown
- Cone collisions and vibration feedback
- Race timer and persistent best time
- Pause and replay controls
- Automated tests and APK build workflow
