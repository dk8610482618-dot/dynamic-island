#!/usr/bin/env bash
set -euo pipefail
# Connect an unlocked API 33+ device. This does not publish or sign a release.
./gradlew :app:assembleBenchmark :benchmark:connectedBenchmarkAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.dynamicisland.benchmark.BaselineProfileGenerator
printf '%s\n' 'Copy the generated human-readable baseline-prof.txt from benchmark/build/outputs to app/src/main/baseline-prof.txt, then rebuild release.'
