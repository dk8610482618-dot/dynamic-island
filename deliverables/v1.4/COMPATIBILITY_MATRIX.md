# Compatibility and capability matrix

The code supports API 28–36 and can run on later versions subject to their platform
rules. compile/target API is 36. No physical OEM device has been validated here.

| Capability | API / grant | Behavior and fallback |
|---|---|---|
| Capsule | API 28+, display-over-apps | Bounded application window; stops on app-op revocation; protected system UI retains priority. |
| Expanded controls | Tap or configured gesture | Bounded in-place media/call/inbox panel; closes on outside touch, header swipe or chevron. |
| Secondary bubble | API 28+, same grant, explicit opt-in | Independent window; omitted if no collision-free space; does not enlarge primary. |
| Rotation/folding | Live WindowMetrics on API 30+, display metrics fallback | Follows cutout edge; clamps to viewport; device fold-state behavior still needs instrumentation. |
| Lock screen | Explicit user option + OEM policy | Generic status only; no private titles/artwork or gestures; hidden with screen off. |
| Notifications | User-enabled listener access | Live inbox, Android group keys and importance; listener removal confirms dismissal. Disconnection clears retained content. |
| Media | Listener access + player-advertised capabilities | No unsupported seek/transport actions; local bounded artwork; source-app fallback. |
| Calls | READ_PHONE_STATE, available subscriptions | Per-subscription coarse state; dialer-role-only actions never offered without control eligibility. |
| Bluetooth | BLUETOOTH_CONNECT on API 31+ | Optional name/connection signals. Vendor battery broadcasts are best-effort, not a guaranteed API. |
| Battery | Sticky Android battery broadcast | Percentage/charging. Fast-charge current is a heuristic, not charger identification. |
| Ringer/DND | Public system state | Status observation; no request to change DND policy. |
| Microphone | Platform-limited AudioManager observations | Does not claim global monitoring or replace Android privacy indicators. |
| 120 Hz | Optional window refresh hint | Actual display mode depends on panel, OEM, battery and system policy. |
| Accessibility | No service registered | No screen reading, screenshot actions, global actions or accessibility elevation. |
| Network | No INTERNET permission | Artwork and settings stay local. |

Required physical-device matrix: Realme/ColorOS Android 17 reported by the user,
Pixel API 28/31/36, Samsung One UI, Xiaomi/HyperOS, both landscape rotations,
gesture/three-button navigation, fold/unfold, large font/display size, secure screen
and overlay-permission revocation. These are verification targets, not passed claims.
