# Tilt Rush 0.2.2

- Replaced the hard-coded throttle polarity with a real forward-tilt learning step.
- The startup flow now captures neutral, asks the player to tilt forward, and learns whether that phone reports forward as a positive or negative screen-axis delta.
- Saved the learned direction for fallback when the movement sample is too small.
- Added tests for both possible sensor polarities.
