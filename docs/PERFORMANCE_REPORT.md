# Performance validation

The app has no measured 120 Hz, battery-life or memory-regression claim. Emulator
numbers, if recorded, are engineering checks and not representative of a physical phone.

Implemented controls: no permanent capsule animation clock, bounded windows, icons
crossfade only on changes, reduced/system-disabled animation support, waveform animation
is disabled in reduced-motion mode, bounded/downsampled local artwork, structured service
scope teardown, and optional refresh-rate hint rather than forced display-mode switching.

The :benchmark module contains StartupTimingMetric + FrameTimingMetric cold-start tests
and a BaselineProfileRule generator. Run on an unlocked physical API 33+ device:

```
./gradlew :app:assembleBenchmark :benchmark:connectedBenchmarkAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.dynamicisland.benchmark.StartupBenchmark
./tools/generate-baseline-profile.sh
```

Retain benchmark/build/outputs including JSON, traces and generated profiles. Record
model, firmware, API, refresh mode, temperature, battery state and build checksum.
The benchmark producer disables minification to preserve source method names for
human-readable profiles. The release build rewrites installed source profiles through R8.
Startup numbers from this producer are not numbers for the shrunk release. Do not invent a
baseline-prof.txt from source names and call it a measured profile.

Profile cold start, attach/remove, media changes, call preemption, notifications,
secondary-window attach, rotation and prolonged idle. Compare the same workload/build
settings before and after a change. Realme scan/lockscreen tests are a separate matrix.

Visual baseline workflow: run :app:connectedDebugAndroidTest; pull the screenshot folder
from the test device, review the actual rendered states and retain those PNGs as a
baseline for that API/density/font configuration. Then compare subsequent captures:
`python3 tools/compare-screenshots.py reviewed-goldens new-captures` (Pillow required).
The comparator fails for missing/unreviewed images and geometry/pixel differences;
it never manufactures passing goldens or overwrites the baseline.

Artifact-only measurement: development-signed v1.2 release APK 17,299,321 bytes; shrunk v1.3 release APK 1,993,926 bytes. This measures download size, not FPS, memory or battery consumption.
