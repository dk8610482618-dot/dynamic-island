# v1.3 feature completion

The remaining implementation and build tooling from the earlier feature report are
now present. The primary capsule remains fixed and opaque by default, as explicitly
requested; automatic growth into an expanded overlay is intentionally not enabled.

Implemented:
- Persistent activity-priority presets (calls always first), configurable tap/hold/
  horizontal swipes, haptic strength, monochrome/accent/high-contrast appearance,
  motion presets and reduced/system-disabled animation support.
- Optional separate secondary activity bubble with independent semantics/gestures,
  collision/RTL/rotation placement and an uncovered input gap. Primary size is unchanged.
- Live media/call dashboards, source opening, capability-gated controls and adaptive
  large-font card height. Local request/session-checked and downsampled artwork.
- Grouped notifications using Android group keys/importance, collapsible groups,
  individual/clear-eligible dismissal, ongoing protection and authoritative removals.
- Strict typed timer cancellation and visible pending/failure status. Generic first,
  destructive, Stop or Cancel labels are not guessed; unsupported apps open their timer UI.
- Quiet hours, package exclusion, text redaction and versioned bounded settings backup.
- Overlay app-op revocation teardown and listener-disconnection data clearing;
  locked state redaction/action blocking; separate multi-SIM call identities.
- Local simulation lab, battery/device/capability diagnostics and explicit OEM limits.
- Central dependency catalog, credential-pattern scan, R8/resource shrinking,
  optional secure publisher signing and APK/AAB builds.
- Debug-only UI/screenshot/rotation/permission tests, golden comparison tool,
  Macrobenchmark/frame metrics and baseline-profile generator/installer integration.
- KVM-backed CI device job and five focused repository skills with valid source paths.
- Browser reference marked simulation; unverifiable stars, forks, capture and license claims removed.

Verified here: unit suites, engine/host lint, instrumentation/benchmark compilation,
R8 release APK/AAB compilation, static credential patterns and artifact verification.
Exact counts and final results are in TEST_REPORT.md.

External verification still blocked, rather than represented as implemented measurements:
- This host has no KVM. The software API 36 emulator eventually booted, but its system
  process died during APK installation (DeadSystemException in system apps and missing
  package service). No app UI/instrumentation test completed on that emulator.
- Therefore reviewed on-device PNG goldens, measured startup/frame/battery/memory
  comparisons and a device-generated baseline-prof.txt were not produced here.
  Their generators/tests compile and the CI job is configured; no benchmark results are invented.
- Realme/ColorOS Android 17 risk warnings, cutout touches and keyguard behavior require
  the actual phone. Android still gives protected system surfaces precedence.
- Publisher signing identity and Play review/approval cannot be manufactured. The
  supplied APK is development-signed, non-debuggable; the AAB is unsigned.

No unfinished optional product controls from the previous future-work list are being
presented as device-validated. Automatic primary shape expansion is excluded by the
user's fixed-footprint requirement, not an undisclosed fallback.
