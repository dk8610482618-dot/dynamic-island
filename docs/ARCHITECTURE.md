# Runtime ownership

:core remains Android-free: physical placement, secondary-window collision geometry,
quiet-hour arithmetic, gesture eligibility, spring physics and motion math.

The engine owns one event bus/repository, media/telephony/system/listener adapters,
serialized priority arbitration and lifecycle-bound Compose overlay windows. The main
capsule is bounded and fixed in compact mode; explicit user input opens a bounded
expanded card in the same window. ExpandedAnchor keeps controls inside safe display
insets, and the service animates only this user-requested size change. Outside
touch, close/header swipe, lock and screen-off dismiss expanded presentation.
An optional second bounded window holds a
secondary activity; the space between windows is never covered by an input surface.

:app owns permission onboarding, dashboard cards, configuration, simulations, local
backup and diagnostics. Simulations never connect to the live bus. Debug-only test
activities and their fake state are not registered in a release build.

:benchmark is a separate instrumentation process targeting the benchmark app variant.
It records cold-start/frame metrics and produces device-derived baseline profiles.
It is not included in the shipped APK. The CI device job runs on a KVM-backed runner.

Android listener state remains authoritative for notification removal. Commands execute
outside the arbiter mutex and never infer successful cancellation from UI disappearance.
App-op revocation removes windows; listener disconnection removes retained sensitive data.
