# Launch-crash hardening — v0.1.1

- Defers immersive-mode setup until the window is attached.
- Makes immersive mode non-fatal.
- Handles unavailable sensor services safely.
- Prevents zero-size first-frame gradient construction.
- Catches frame/render exceptions and displays the stack trace inside the app instead of immediately closing.
- Displays startup and sensor initialization exceptions in-app for easy screenshots.
