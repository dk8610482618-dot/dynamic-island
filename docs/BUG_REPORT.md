# Repair ledger

"Implemented" means source changes plus the specified automated checks. Device/OEM
claims remain unverified until the runtime checks pass on the named device.

| Finding | Root cause / correction | Evidence and limits |
|---|---|---|
| Fullscreen input risk | Replaced fullscreen reflected touch regions with bounded touchable windows. | Policy/unit tests; real input test compiled, device execution pending. |
| Translucent black | Window alpha was 0.79. Bounded windows now use alpha 1 and black fill. | Window policy regression; screenshot center-pixel assertion compiled. |
| Accessibility/OEM warning | Removed accessibility service, global actions, broad background-launch overrides and listener component toggles. | Packaged manifest and source checks; OEM scanner outcome unverified. |
| Missing secondary activity | Optional second bounded window with independent interaction, collision/RTL geometry and a gap. | Placement and gesture tests; split/merge means adding/removing this window while primary stays fixed. |
| Wrong timer cancellation | Explicit typed action eligibility, one PendingIntent attempt and platform reconciliation. | CompletionRegressionTest; unsupported/localized labels disable cancellation with source-app fallback. |
| Media gestures hijack calls | GesturePolicy requires visible media and an unlocked screen. | State/gesture regression tests. |
| Geometry drift | WindowMetrics/cutout share coordinates; rotated edges and viewport clamping. | Portrait, both sides, RTL/collision and tiny viewport tests; fold/unfold needs hardware. |
| Locked private content | Arbiter emits redacted closed state while locked, clears media presentation and blocks actions. | Lock/unlock regression; OEM lockscreen visibility not guaranteed. |
| Stale artwork | Generation and exact session checks, canceled obsolete work, local URI-only artwork. | Source review; rapid real-player changes still need device verification. |
| Artwork bounds/network | No network permission/fetch, scheme restrictions, bounded cache and largest-dimension downsampling. | Source/manifest checks; no remote service used. |
| False notification dismissal | Preserve entries until listener removal; protect ongoing rows. | Arbiter reconciliation tests. |
| Permission loss | App-op watcher removes windows; listener disconnect clears inbox/media retained content. | Access-loss regression; real app-op test compiled. |
| Inconsistent build versions | One library version catalog, plugin versions in settings, pinned verified Gradle wrapper. | Gradle compile/test/lint gates. |
| Release setup | R8/resource shrinking, APK/AAB build, environment-based publisher signing, hashes. | Build outputs; publisher key and store approval remain external. |
| Unverified docs/demo claims | Reference marked simulation; fake stars/forks/capture/licensing assertions removed. | Source inspection; no performance measurements invented. |
