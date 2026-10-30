> Historical source-archive reference. Claims and counts below are not current validation. See docs/TEST_REPORT.md, docs/FEATURES.md and docs/COMPATIBILITY_MATRIX.md for this build.

# Dynamic Island — Outstanding Work

Working list of user-reported issues and requests. Ordered so that straight bug
fixes land first and platform-constrained items are explicit rather than
silently deferred.

Legend: `[ ]` todo · `[~]` in progress · `[x]` done · `[!]` blocked by platform

---

## Done this session

- [x] **Whole-screen touch failure resolved.** The overlay window was capturing
      all touches across the device screen. Root causes identified and resolved:
      1. Removed `FLAG_LAYOUT_NO_LIMITS` and `FLAG_WATCH_OUTSIDE_TOUCH` from `OverlayWindowPolicy.kt`.
      2. Fixed reflection in `DynamicIslandService.kt` on API 28–32:
         `InternalInsetsInfo` has method `setTouchableInsets(int)` rather than a public field.
      3. Fixed listener registration timing: `registerReflectionListener` was being invoked before `wm.addView`,
         attaching to an unattached floating `ViewTreeObserver` that was discarded upon window attachment.
         Added `View.OnAttachStateChangeListener` so the listener is registered onto the real `ViewRootImpl.mViewTreeObserver`
         when attached, and unregistered cleanly when detached.
      4. Fixed dynamic `Proxy` `InvocationHandler` unboxing crash: calling `removeOnComputeInternalInsetsListener`
         triggered `ArrayList.remove(proxy)` -> `proxy.equals(other)`. Because the proxy handler returned `null`, Java
         attempted to unbox `null` to primitive `boolean` and crashed with `NullPointerException`. Added explicit
         handling for `equals`, `hashCode`, and `toString`.
      5. Fail-open touch region initialization: applied `Region()` (empty pass-through) immediately on surface creation
         so no touch events are swallowed before Compose completes its first measurement pass.
      6. Added continuous `addOnLayoutChangeListener` on `ComposeView` to re-apply the region on every layout change,
         and set `onStartCommand` to `START_NOT_STICKY`. (The `InternalInsetsProxyTest`
         that followed has since been removed: it built its own proxy inside the test and
         asserted on that, so it exercised no production code.)
- [x] **Pill raised into cutout band ("Raise into the cutout band").**
      Previously, `DynamicIslandView` vertically centered the pill inside a 286.4 dp host
      (`top = (size.height - h) / 2f`), drawing the pill ~125.2 dp below the top inset
      (~493.5 px instead of ~164 px). Top-aligned the pill in the host (`top = 0f` in `drawWithContent`,
      `contentAlignment = Alignment.TopCenter`), and updated `publishBounds` and secondary bubble
      alignment (`topPx = origin.y`, `translationY`). The resting pill now sits directly in the
      status-bar cutout band over the camera aperture.
- [x] **Down-clamp travel envelope bug resolved (TODO #9).**
      TODO #9 was caused by the same 125.2 dp discrepancy between the placement model (which
      assumed top-aligned geometry) and the previous vertically centered renderer. Top-aligning
      the pill in the host brought the renderer into 100% agreement with `IslandPlacement`,
      so the down-travel envelope `(bottomLimit - automaticTopDp - islandHeightDp)` now cleanly
      stops the pill right above the navigation margin without pushing it off the bottom edge.
- [x] **Apple G2 continuous squircle on expanded card.**
      Replaced G1 `addRoundRect` in `DynamicIslandView.buildPillPath` with Apple G2 curvature
      continuous superellipse splines via `SquircleGeometry` (in `:core`). Eliminates
      curvature discontinuities ($\kappa = 0 \to 1/R$) and corner optical pinching on OLED displays.
- [x] **Non-interactive gap pass-through.**
      Updated `TouchRegionGeometry.expand(...)` so when the pill and secondary bubble are
      horizontally separated, facing edges do not expand into the 8 dp gap between them,
      preserving zero-latency touch pass-through in the gap.
- [x] **Tap/long-press swapped.** Tap now opens *and* closes the island; hold
      launches the app. Previously a hold expanded and a tap launched.
      `IslandPriorityArbiter.handleUserCommand`, `DynamicIslandView` gesture block.
- [x] **Swipe no longer moves the island.** A media track skip is a command, not
      a drag — the pill now stays put and only the track changes. Classification
      is by dominant axis, shared between the drag handler and the release
      handler so the two cannot disagree.
- [x] **Two gesture-contract tests updated** to assert the new semantics, plus a
      new test pinning that a hold does *not* expand.
- [x] **Broken X/Y position controls — root cause found and fixed.**
      `MainActivity` and `DynamicIslandService` each constructed their own
      `IslandSettings`, so each had its own `MutableStateFlow`. The slider wrote
      to the activity's flow; the service watched a different one and never saw
      the change. `IslandSettings` is now a process-wide singleton via
      `IslandSettings.get()`, with a `SharedPreferences` change listener as a
      second line of defence so two instances can never silently diverge again.
      **Verified on device:** `mode=MANUAL offset=(9,108)dp` appears in the
      service log and the island moves; reset restores `mode=AUTO offset=(0,0)`.
- [x] **Size adjuster added.** A single `Size` slider (70%–105%) applied as a
      uniform multiplier to width, height **and** corner radius — all three
      together, because the island is a capsule whose radius is half its height
      and scaling the box alone would turn it into a rounded rectangle. The
      constant-size render host now derives from `IslandSettings.MAX_SCALE` so a
      scaled-up card cannot be clipped. Persisted alongside the offset.
- [x] **Render-pipeline rewrite (the jitter fix) — measured on device.** The
      three independent `Animatable` coroutines (width/height/radius, plus the
      bubble) that could advance on different frames were replaced by a
      single-clock `DynamicIslandMotionEngine` in `core`, so every dimension is
      sampled at the same instant. Measured on the `island_test` AVD with host
      GPU, same `dumpsys gfxinfo` protocol as the pre-rewrite baseline:
      pre-fix expand = 102 frames, 18.63% janky, p50 32 ms, p90 69 ms,
      p99 1150 ms, 11 missed vsync;
      post-fix expand = 112 frames, 8.04% janky, p50 25 ms, p90 36 ms,
      p99 53 ms, 1 missed vsync.
      Emulator floor (Settings scrolling, island stopped): 6.54% janky,
      p50 24 ms, p90 32 ms — the island now runs ~1.5 points above the
      emulator's own floor, so what is left is the emulator, not the app.
- [x] **Closed island's touch region was emptied — the bug that made "tap a
      quiet island to open the inbox" impossible on device.** Found by
      end-to-end testing: taps dismissed peeks and expanded activity cards,
      but a tap on the quiet pill did nothing, every time.
      `DynamicIslandService.updateTouchableSurfaceRegion` published an empty
      input region whenever the state was `Closed` — stale behavior from before
      the gesture rework (the view had already been fixed to keep the region on
      the pill; the service branch was never updated). The OS therefore routed
      every tap on the closed pill straight through to the app beneath.
      The region is now purely geometric: the pill always owns its own pixels,
      and pass-through is what the region does *outside* it.
      Verified after the fix (screenshots in `screenshots/`): tap on the quiet
      pill opened the inbox (`oc_23_inbox.png` — header, count, rows); swipe on
      the open inbox cleared it (`oc_24_after_swipe.png`); the follow-up tap on
      the then-empty inbox correctly did nothing (`oc_25_after_tap_again.png`);
      and a scroll started outside the pill still drives the app beneath
      (`oc_26_scrolled.png`).
- [x] **Vertical travel extended: the slider now reaches -250 dp** (it stopped
      at -51 dp on a typical status bar, -63 dp on the emulator).
      Root cause was a model/renderer mismatch: the travel envelope treated the
      placement top as the island's visible top, but the renderer paints every
      presentation centred inside a constant-size host — on the `island_test`
      AVD the pill's visible top sits ~73 dp below the placement top. The old
      up-budget (`upDp = automaticTopDp`) therefore stopped the pill about one
      status bar short of the screen edge, and a second hard clamp
      (`top = coerceIn(0f, …)` in `applyOffset`) silently ate anything above
      y = 0 even when the envelope would have allowed it.
      Changes: `TravelEnvelope.UP_TRAVEL_DP = 250f` (the required ceiling);
      `applyOffset`'s vertical floor is now `automatic.topDp - envelope.upDp`
      instead of 0; the service now passes the AUTOMATIC top as padding and
      the applied offset as a draw-time `y` translation, because a negative
      padding is not a legal modifier; the clamped-position message in
      `MainActivity` was reworded to match the new envelope. Core tests
      updated (`an extreme upward offset clamps at the upward travel budget`,
      envelope assertion) — `gradlew test` green.
      **Verified on device** (screenshots `oc_35`–`oc_38`):
      track-start tap → `mode=MANUAL offset=(0,-250)dp`, no `[CLAMPED]`;
      `placement_offset_y_dp = -135.6` persisted in prefs; at `-136 dp` the
      pill is flush with the very top edge (`oc_37_pill_top_launcher.png`,
      pixel-probed 0–100 px black at screen centre); at `-250 dp` it travels
      fully off-screen; Reset restores `mode=AUTO offset=(0,0)`; the down
      direction is unregressed (`offset=(0,194)dp`, no clamp).
      Note: past roughly -136 dp the pill slides off the top edge by design
      (the requirement is the range, and it stays recoverable — the slider and
      Reset do not depend on the island being visible). Also: on apps that
      paint an opaque status-bar band (including this app's own settings
      page) the pill at the very top sits hidden behind it, per item 3's
      z-order limit; over the launcher/wallpaper it shows fully.

- [x] **Lockscreen pill (TODO #4) — implemented, verified on device.**
      The keyguard gate already existed in `IslandPriorityArbiter`
      (`isKeyguardLocked`, fed by `SystemStateObserver` from `ACTION_SCREEN_OFF` /
      `ACTION_USER_PRESENT` / `isKeyguardLocked`); the TODO entry saying "not started"
      was simply stale. `recomputeIslandState()` short-circuits while locked: media
      (compact, or expanded if it was expanded) or `Closed`, nothing else, and
      `triggerAlertPeek()` drops every transient alert.
      Verified on the `island_test` AVD this session (screenshots in
      `screenshots/verify_session/`): with the keyguard up and nothing playing the
      island is the bare black capsule over the cutout
      (`v2_lockscreen_idle.png`); with a real `MediaSession` playing it is the compact
      media pill — music glyph leading, equalizer trailing (`v3_lockscreen_media.png`),
      while a pending notification sits in the shade and produces no peek.
      Not re-verified visually: the peek-suppression half is only covered by
      `lockscreen forces Closed state unless music is playing` in
      `IslandPriorityArbiterTest`, because a notification posted *while* locked was not
      captured in a screenshot. The rule is asserted, the frame was not.
- [x] **Progress-bar trail effect (TODO #6) — already present.** `IslandScrubber`
      draws the filled track with a horizontal `Brush.horizontalGradient`
      (0.35 → 0.70 → 1.0 alpha) plus a radial glow halo behind the knob
      (`MediaExpandedContent.kt`). The TODO entry predated the rewrite. What is *not*
      there is an explicit after-glow that persists for a moment after release; the
      current trail is drawn live with the knob. Say the word if that is what "trail"
      meant.
- [x] **Inbox noise: our own overlay notice.** The system posts an
      "is displaying over other apps" notice on
      `com.android.server.wm.AlertWindowNotification` for every overlay app, this one
      included, and it arrived at the listener as ordinary content. Dropped at the
      door by `IngestionFilters.isOverlayAlertNotice`.
      Honest scope: the rule is unit-tested (`IngestionFiltersTest`, 11 cases) but the
      notice did **not** reproduce in this session's logcat — Android 15 with the
      permission granted through `appops` did not post it — so this is a guard against
      a reported leak, not an end-to-end verified fix.
- [x] **Media sessions with no displayable title.** `MediaSessionTracker` no longer
      substitutes `"Unknown Title"`; a metadata bundle with no usable title is treated
      as "no item" and skipped (`IngestionFilters.displayableTitle`, which also falls
      through a *blank* title key to `DISPLAY_TITLE`, the case a `?:` chain silently
      lost). `MediaExpandedContent` no longer prints `"Unknown Artist"` either — the
      subtitle row closes up instead. On this device the phantom session
      (`com.google.android.googlequicksearchbox`) is already stopped by the older
      idle-state guard (`state=0`), so the placeholder path was reachable only for a
      *playing* session with untitled metadata; that is now closed.

---

## 2. Width / height adjuster — DONE, see above

Shipped as a uniform scale rather than separate width/height controls. A capsule
cannot be independently stretched without changing its shape, so two sliders
would have produced a rounded rectangle instead of a bigger pill. If separate
axes are genuinely wanted, that is a different shape model and needs a
conversation first.

---

## 3. Status-bar icons draw over the island

**Reported:** "the system icons like the vibration symbol, network tower
indicator pushes this dynamic island back and those icons are on top"

**Status:** [ ] todo.

Cause identified: the overlay is `TYPE_APPLICATION_OVERLAY`, which composites
*below* the status bar by design, and `resolveStatusBarInsetDp()` then
deliberately offsets the island below the clock.

Drawing above system icons needs a window type that is signature-gated and not
grantable to a normal app. Best achievable is to move the island up into the
status-bar band while accepting the icons still draw over it — which may still
be what the user wants, since iOS icons also sit near the pill.

Needs a decision, not just code.

---

## 4. Lockscreen pill

**Reported:** "the dynamic island must be visible in the lockscreen too, while
in lockscreen it must be just a black pill and do not display any other things,
only exception is music and its controls"

**Status:** [x] done — see "Done this session". Keyguard detection, the
Closed-or-media-only state restriction, and suppression of calls, timers,
charging and notification peeks are all in place and verified over the keyguard
on the AVD.

Note for future work: the pill is visible over the keyguard because the overlay
is typed `TYPE_APPLICATION_OVERLAY`; on ROMs that hide overlays behind the
keyguard this will need a different window type, which is the same
signature-gated constraint as TODO #3.

---

## 5. Notification capture into the island — IMPLEMENTED, verified on device

**Reported:** "i need the dynamic island to pull the live notifications ... it
should fall on the dynamic island and when i expand and swipe the island, the
notifications should be gone"

**Status:** [x] implemented; verified on the emulator with real system
notifications (`cmd notification post`).

Shipped:
- **Store** — `IslandPriorityArbiter` keeps a capped (20), newest-first
  `notificationInbox`; re-posting a key replaces its entry; a notification
  removed from the shade leaves the inbox.
- **Arrival** — every non-ongoing notification lands in the inbox and fires a
  3.5 s bell `AlertPeek` ("falls on" the island); tapping the peek dismisses it.
- **Open + clear** — a tap on a quiet island opens the inbox; a swipe on the
  open inbox clears it; the header row carries an explicit **Clear**.
- **Fix found on device** — low-priority ongoing notifications (no category, no
  progress, no chronometer — "Serial console enabled", USB debugging, sync
  notices) are no longer promoted to island activities. They used to pin a card
  with no dismiss affordance, and because opening the inbox requires a quiet
  island, the inbox was unreachable while any such system notification existed.
  `ActivityKind.TRANSIENT_NOTIFICATION` now never reaches the island; the entry
  still waits in the inbox. Pinned by a new test.

Remaining polish (not blocking):
- [x] **Notifications about this app itself** — filtered; see "Done this session"
  for the honest scope note (rule tested, leak not reproduced on this build).
- [x] **Media session with no playable item** — no placeholder title or artist is
  produced any more; see "Done this session".
- [ ] `IslandUserCommand.DismissActivity` is handled by the arbiter and emitted by
  the repository, but no UI path sends it. With the low-priority filter there is
  no longer a stuck card it must cover; wire it if a promoted activity ever
  needs a manual dismiss. Left open deliberately: adding a dismiss affordance
  nothing needs would be UI for a state that cannot occur.

---

## 6. Progress bar trail effect

**Reported:** "the song playing bar should have a trail effect on it"

**Status:** [x] done — `IslandScrubber` already draws a gradient trail plus a
radial glow behind the knob. See "Done this session" for what exists and what
"trail" could still mean.

---

## 7. Triple-tap to screenshot

**Reported:** "tripple tapping the dynamic island should take screenshot"

**Status:** [x] done — `AccessibilityService.takeScreenshot` (typed overload on API 33+,
`GLOBAL_ACTION_TAKE_SCREENSHOT` below), with `canTakeScreenshot="true"` in the service
config and the loaded service flags no longer being zeroed on connect. The gesture was
already wired; the handler was a stub that returned `false`.

---

## 8. [!] Mic / camera privacy dots — PARTLY IMPOSSIBLE

**Reported:** "when mic is in use on the phone then a tiny orange dot should be
visible ... when camera is in use, a blue dot"

**Status:** [!] **Cannot be built as specified.** Left here deliberately so it
is not mistaken for done.

Two separate problems:

**Colours.** iOS uses **orange for microphone** and **green for camera**. The
user asked for blue for camera; Apple's own support page says green covers
"either the camera only or the camera and microphone". Blue is a different iOS
indicator (active call / location bubble behind the clock).

**Detection — hard blocker.** Android records mic/camera access in app-ops owned
by the system server, and only SystemUI may read another app's records:

1. Access is recorded in the system server; only SystemUI is permitted to read
   records belonging to other apps.
2. `AppOpsManager.startWatchingActive` requires `WATCH_APPOPS`, and its docs
   state that without it you watch *"only for your UID"*.
3. `WATCH_APPOPS` is signature-level — platform-signed or preinstalled system
   apps only. It cannot be granted by the user, by ADB, or on the Play Store.
4. So a normal app can only detect *its own* mic/camera use; a true system-wide
   indicator needs root or a system-signed build.

Best achievable: show the dot for our own use plus notification-inferred
recording. Genuinely partial.

---

## 9. Down-slider end pushes the pill off the bottom edge — DONE

**Status:** [x] Done.

Root cause resolved: the down budget in `IslandPlacement` treats `topDp` as the
island's actual top. Previously, the renderer vertically centered the pill inside
the constant-size host (`top = (size.height - h) / 2f`), which placed the pill
125.2 dp below `topDp`, pushing it past the screen bottom on full downward travel.
Top-aligning the pill in `DynamicIslandView` (`top = 0f`, `Alignment.TopCenter`)
eliminated the model/renderer discrepancy. The down travel envelope now stops the
pill cleanly before the navigation bar.
