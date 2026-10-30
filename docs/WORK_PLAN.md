> v1.4 update: tap now expands controls in the overlay. Earlier references to
> host-only expansion are superseded by [v1.4 changes](V1_4_CHANGES.md).

# v1.2 repair plan

Scope: the user's reported overlay, OEM risk warning, opaque capsule, camera alignment,
rotation, fixed footprint, media controls/artwork, dashboard and ColorOS settings.
The attached master prompt is reference material, not evidence of implemented features.

1. Import the supplied v1.1 source and establish build/test baseline.
2. Replace the fullscreen/reflected input-region overlay with a bounded public-API
   application overlay. Remove accessibility elevation, global screenshot actions,
   and broad background-activity-launch overrides. Keep explicit Start/Stop and errors.
3. Keep a single black capsule footprint for every activity. Follow the actual cutout
   on rotation; offer a system-bar clearance option for OEM touch conflicts. Show
   only generic status while locked, and never launch actions through the keyguard.
4. Repair media ownership, stale artwork and control capabilities. Expose live media
   controls and the notification inbox in the host dashboard, without a growing overlay.
5. Replace misleading accessibility/always-on-top claims and decorative UI with clear
   Material controls and ColorOS setup guidance. Use only public settings intents.
6. Run core/engine tests, Android lint, and APK build. Record device-dependent checks
   as unrun; provide source, APK and checksums when the build succeeds.

Platform limits: application overlays stay below status bar/IME and may be hidden by
secure surfaces, keyguard or OEM policy. Public APIs cannot guarantee removal of an OEM
risk warning, visibility above all system UI, or a 120 Hz refresh rate.

## Added scope: 2.0 feature brief

The latest upload is byte-for-byte identical to the earlier master brief. The user now
explicitly requests its features. Continue after the repair baseline with:
- deterministic local preview lab (never inject fixtures into live observers);
- per-app notification selection, quiet periods and optional text redaction;
- supported media transport controls, live call presentation and source-app actions;
- non-sensitive configuration backup, diagnostics and a capability/test matrix.
Keep the earlier explicit fixed-size capsule requirement. Put rich content in the host.
Split/merge geometry, risky platform privileges, OEM scans and measured performance
remain separate acceptance items and must not be claimed from a successful compilation.

## Completion pass (v1.3)

- [x] Persistent priority, gesture, appearance, motion and haptic controls.
- [x] Optional independent secondary bubble, geometry/collision/RTL tests; fixed primary.
- [x] Typed timer cancellation, no arbitrary destructive action, no false local dismissal.
- [x] Grouped inbox with platform importance ordering and ongoing-notification protection.
- [x] Permission watcher and access-loss redaction/reconciliation; multi-SIM call identities.
- [x] UI/screenshot/permission test harness restricted to debug variants.
- [x] Macrobenchmark and BaselineProfile generator; profile installer and release shrinking.
- [x] Central library version catalog; optional publisher signing from environment variables.
- [x] Build/test gates executed; 220 unit tests passed. Runtime attempted and blocked by emulator system-process failure. See TEST_REPORT.md.
