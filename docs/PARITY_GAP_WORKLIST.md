# Parity gap worklist

Features from `research papers/` that were **genuinely missing or only nominally
present** in the imported base (`Darshan8610/Dynamic-Island@a22dccb`), verified
by grep audit plus reading each call site.

Legend: `[ ]` todo · `[~]` in progress · `[x]` done

## Confirmed gaps

- [ ] **G1. Transient alert swipe-away.** Dismissing an alert must suppress *that
      instance*, not the underlying ongoing activity (deep-research-report,
      gesture table). No code path exists; `IslandState.AlertPeek` only expires on
      a timer.
- [ ] **G2. `AlertConfiguration` auto-expand.** An important state update must
      transiently expand the island for ~2 s and then settle back to the prior
      Compact/Minimal presentation (both docs). `TransientAlert` exists but
      nothing derives an auto-expand decision from update importance.
- [ ] **G3. `hideInFullscreenApps` is a dead setting.** Declared in
      `BehaviorOptions`, persisted to prefs, never read by any renderer or
      service. Doc 2 requires auto-hide inside full-screen games/video plus an
      app-by-app "Hide Notch in Apps" matrix. `blockedPackages` only filters
      *notifications*, not overlay visibility.
- [ ] **G4. Mini status bar is incomplete and has a live bug.**
      `FixedIslandCapsule` renders the clock with `remember { SimpleDateFormat... }`
      so it is captured once and **never ticks**; the battery glyph is a constant
      green icon with no level; there are no cellular/Wi-Fi signal bars, no
      quick-control shortcuts and no screenshot/lock triggers.
- [ ] **G5. Liquid Glass is an alpha swap, not a material.** `LIQUID_GLASS` only
      sets capsule fill to `Color.White.copy(alpha = 0.38f)`. No blur, and no
      rule preventing glass-on-glass stacking between the card and its inner
      controls, which doc 2 explicitly calls out as visual noise and wasted GPU.
- [ ] **G6. No foreground-service watchdog.** Doc 2 requires a watchdog with
      battery-optimisation exemption onboarding and `BOOT_COMPLETED` recovery.
      The boot receiver and the exemption screen exist; nothing detects a wedged
      or killed service and restarts it.
- [ ] **G7. Waveform is not audio-driven.** `AudioWaveformEqualizer` is fed a
      hard-coded fallback `listOf(0.45f, 0.85f, 0.6f, 0.9f)`. Doc 2 requires a
      real-time multi-bar equaliser that flattens on pause. Needs capability
      gating plus an honest synthetic fallback, matching the camera-dot stance.
- [ ] **G8. Call expanded lacks SharePlay / Live Captions / Live Translation.**
      Zero hits in the codebase. Doc 2 lists them as expanded quick-action state.
- [ ] **G9. Media output routing is display-only.** `audioRouteLabel` is rendered
      but there is no selector action to change output (doc 2: AirPlay/Bluetooth
      routing selector).

## Verified already present in the base — do not rebuild

Apple-parity tap/long-press (`GesturePolicy.resolvePrimaryTap/Hold`), 4-region
expanded grid + `belowIfTooWide` (`ExpandedRegionLayoutSolver`), 1:1 scrub
collapse (`ScrubCollapseController`), metaball ferrofluid (`MetaballGeometry`),
relevance arbitration incl. 3-activity mitosis (`RelevanceArbitrator`),
lifecycle guardrails 8 h / 4 h / 4 KB / staleDate (`ActivityLifecyclePolicy`),
lock-state redaction (`LockStatePrivacyPolicy`), `ProgressStyle` Live Updates
(`LiveUpdateProgressParser`), navigation/journey/download presenters, keyline
tint from album art, landscape 28 dp condensation, squircle continuous corners,
reduced-motion, RTL, boot receiver, battery-exemption onboarding.

## Platform limits to state honestly, not code around

- Global camera / combined privacy state is not observable by an ordinary app
  (upstream TODO #8). Mic is real via `AudioRecordingCallback`; camera is not.
- The island cannot composite above the status bar's own icons with
  `TYPE_APPLICATION_OVERLAY` (upstream TODO #3).
- Full answer/decline control needs `ROLE_DIALER`; not assumed here.
