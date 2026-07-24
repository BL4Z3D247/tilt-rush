# Tilt Rush 0.3.0

This release fixes the actual forward-motion problem rather than flipping another sensor sign.

- Corrected the camera transform: the car's world-forward vector now renders toward the top of the screen, matching the direction the ship sprite points. The previous transform displayed positive forward motion behind the nose.
- Replaced one-axis sign learning with full 3-axis gravity-vector calibration.
- Neutral and forward positions are averaged over many sensor samples instead of relying on one frame.
- Calibration now retries instead of silently accepting an unusably small forward gesture.
- Preserved the existing left/right steering mapping that was already confirmed correct.
- Added a live `FORWARD`, `COAST`, or `BRAKE / REVERSE` indicator to verify the interpreted control direction.
- Added unit tests proving that forward world motion renders above the ship and that mixed Y/Z forward gestures produce positive throttle.
