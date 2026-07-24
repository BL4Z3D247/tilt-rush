# Tilt Rush

A native Android tilt-controlled arcade racing game.

## Controls

**v0.2.2:** forward tilt accelerates; pulling back brakes/reverses.

- Hold the phone in landscape in your normal playing position.
- Tap **START + CALIBRATE**.
- Tilt left or right to steer.
- Tilt the top edge forward to accelerate.
- Pull the top edge back to brake or reverse.
- Pass every glowing gate and complete three laps.

The app reads Android's gravity sensor directly and falls back to the accelerometer when needed. No browser motion permission is involved.

## Open and run in Android Studio

1. Clone or extract this repository.
2. Open the repository folder in Android Studio.
3. Allow Gradle sync to finish.
4. Connect an Android phone with USB debugging enabled.
5. Press **Run**.

## Build from Termux or a computer

```bash
./gradlew test assembleDebug
```

On the first run, `gradlew` downloads the official Gradle wrapper JAR automatically. Internet access is therefore required for the initial build and Gradle dependency sync.

The APK will be created at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Create the GitHub repository

The intended repository name is:

```text
BL4Z3D247/tilt-rush
```

After creating an empty public or private repository on GitHub, run:

```bash
git init
git add .
git commit -m "Initial native tilt racing prototype"
git branch -M main
git remote add origin git@github.com:BL4Z3D247/tilt-rush.git
git push -u origin main
```

GitHub Actions will then run the tests and produce a downloadable debug APK under the workflow run's **Artifacts** section.

## Current prototype features

- Native Android sensor input
- Landscape immersive mode
- Tilt steering, acceleration, braking, and reverse
- Three-lap checkpoint course
- Correct screen-relative tilt controls in either landscape orientation
- Launch ramps and airborne physics
- Concrete wall sections with collision response
- Reusable speed-boost pads and temporary higher top speed
- Off-road slowdown
- Cone collisions and vibration feedback
- Race timer and persistent best time
- Pause and replay controls
- Automated APK build workflow

## v0.2.0 course objects

Orange striped platforms are ramps. Cyan arrow pads trigger a speed boost. Concrete barriers cannot be driven through, but ramps can launch the car over low cones.


## v0.2.2 control calibration

The game no longer assumes a universal Android pitch sign. After tapping Start,
it asks the player to tilt the top edge of the phone forward for about 1.5 seconds,
learns that device/orientation-specific direction, saves it, and then starts the race.
