# Changelog

## 1.1 (2026-10-31)
- **Voice commands** on the phone page: a microphone button (needs the secure address) and a typed command box
  that works everywhere. English sentences are parsed on the phone into key presses and app launches.
- **Hand gestures** with Google MediaPipe hand tracking (bundled, offline): swipes, open palm, fist, peace sign,
  thumbs up/down. The classifier ignores a relaxed, resting hand (checked against real photos).
- **TV camera first:** TV Hub looks for a camera on the box and, if one works, runs the gestures on the TV itself;
  otherwise it uses the phone's camera.
- **One QR code** for everything. The page offers a banner to move to the secure version only when you want the
  microphone or camera.
- **HTTPS on port 8091** with a self-signed certificate generated on the box (Chromecast uses 8443).
- Phone **touchpad** (swipe/tap), a stricter finger-straightness test, 15 unit tests, `tools/fetch_mediapipe.py`.
- Fix: TV Hub screens could render at twice the size on a fresh launch.

## 1.0
- Glass launcher theme and tiles, boot logo, TV Hub (clock, weather, dashboard, photo upload), phone remote with
  typing, Settings tile.
