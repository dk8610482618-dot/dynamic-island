# v1.3 completion pass

The earlier fixed-size/no-accessibility requirements remain the default. The remaining
product controls now persist: gesture mappings, activity priority, feedback strength,
appearance, motion/reduced motion and an optional secondary activity window. Calls
retain the highest priority. The secondary window never grows the primary; its own
bounds leave the gap uncovered and it is omitted if no legal placement exists.

The host adds grouped Android notifications with ranking order, timer cancellation
eligibility/status, and capability explanations. Cancellation never chooses an arbitrary
first/destructive action and never treats local disappearance as platform success.
Permission revocation is observed through AppOps; notification connection loss clears
retained sensitive state. Multi-SIM call fallback uses separate subscription identities.

Release builds now shrink code/resources and can read a publisher signing identity
from secure environment bindings. Library versions are consolidated. :benchmark provides
startup/frame measurements and device-derived profile generation. Debug-only Compose/UI
checks cover deterministic state screenshots, actual outside-window target taps,
play-button routing, permission revocation, rotation and font scale. KVM CI is configured.

These tooling additions compile independently of the shipped app. A missing generated
profile or unavailable device is reported as blocked validation, never replaced by
fabricated timings or hand-authored profile data. The app does not promise system-level
window priority, malware-scanner clearance or a fixed physical refresh rate.
