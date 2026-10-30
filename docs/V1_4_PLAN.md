# v1.4 repair plan

The current request supersedes the earlier fixed-size-only interaction: keep the
compact capsule stable during music and notifications, and expand on user input.

1. Connect compact gestures to the existing activity/inbox state machine.
2. Render live media/call/inbox controls in a bounded overlay; add close, header
   swipe and outside-tap dismissal. Keep compact and expanded bounds on-screen.
3. Correct window context/lifecycle, update restoration and touch routing. Respect
   Android's protected system UI and apps that intentionally hide overlays.
4. Test open/pause/next/previous/close using a real Android MediaSession and overlay,
   plus geometry/state regression tests, lint and release builds.
5. Sign with the existing development identity, package source/APK/AAB/reports,
   verify signatures and checksums, and commit/push to main.

Acceptance requires working controls in the overlay above another app, stable
compact bounds after closing, and no background host launch for an ordinary tap.
Physical ColorOS/realme behavior and performance cannot be established by JVM or
emulator tests; record those limitations explicitly.
