# v1.4 — in-place controls and overlay lifecycle repairs

## Interaction

The v1.3 live service only rendered FixedIslandCapsule and routed a tap to the host
activity. Its expanded components were not connected to the overlay. v1.4 sends
user commands to the existing activity arbiter and renders an expanded panel in
the same bounded window. The earlier fixed-only requirement now applies to the
compact state; expansion is explicit user input.

- The entire compact capsule opens controls, including its music indicator.
- Live artwork, artist/title, play/pause, next/previous and seeking use the player
  session. Unsupported commands stay disabled. Pausing keeps the card open.
- Chevron, header swipe-up and outside touch collapse the panel. Outside touch
  observation uses FLAG_WATCH_OUTSIDE_TOUCH on the bounded window; there is no
  fullscreen input interception surface.
- Insets constrain expanded controls away from the camera and system bars. Side
  cutouts retain the compact anchor while expanded text stays upright.
- Size/corner transitions respect reduced motion and disabled system animation.
- An explicitly selected secondary activity now opens even when a higher-ranked
  activity was already present. Newly higher-priority events still preempt it.
- Notification alerts open their inbox. Incoming notifications do not interrupt
  an already open media panel. Calls retain higher priority.
- A cancelled seek gesture no longer commits a seek or leaves scrubbing stuck.
- Media duration refreshes on metadata changes. Same-player notification action
  fallbacks are reflected in control availability, without crossing app ownership.

## Persistence and privacy

- API 30+ overlays use an explicit display/window context for window metrics and
  Compose resources. Screen and configuration changes refresh visibility/bounds.
- Screen-off and keyguard close controls and clear expanded presentation. Hidden
  secondary windows are removed immediately. Media actions check keyguard again.
- Updating an enabled app restores its service independently of the reboot option.
- Returning to the app can restore the enabled overlay without notification access;
  the listener is needed for content, not for a plain capsule.
- ColorOS users have a direct app-info button for background/battery settings.
- Permission revocation and Stop remove the windows; refused starts are not retried
  in a background loop. No accessibility elevation or hidden window APIs are used.

## Limits

A normal Android overlay cannot guarantee precedence over system icons, the
notification shade, secure lock screens or apps using overlay suppression. ColorOS
can still restrict background activity. Those restrictions cannot be fixed by
changing z-order. No scanner-clearance, 120 Hz performance or physical Realme
verification claim is made. See TEST_REPORT.md for executed tests.
