# Base import provenance

This repository was empty (a single `README.md`) when the Apple Dynamic Island
parity work requested in `research papers/` was scheduled. The two research
documents were written against the existing Android implementation published at
`Darshan8610/Dynamic-Island`, so that project was imported as the base and the
remaining research features were implemented on top of it.

| | |
| --- | --- |
| Source repository | `https://github.com/Darshan8610/Dynamic-Island` |
| Source commit | `a22dccb` — *feat: implement Apple Dynamic Island parity (Phases 1-4), add 25 used skills & 2 research papers, and build v1.6 APK* |
| Source commit date | 2026-10-30 17:35:56 UTC |
| Imported | 2026-10-30, as a single squashed commit (upstream's own history was not grafted) |
| Import size | ~2.0 MB working tree, ~190 files |

## What was imported

`app/`, `benchmark/`, `core/`, `src/`, `docs/`, `gradle/`, `tools/`, `.agents/`,
`.github/`, `ref_project/`, `research papers/`, the top-level Markdown design
documents (`PLAN.md`, `TODO.md`, `DEVICE_NOTES.md`, `VALIDATION.md`,
`UPGRADE_NOTES.md`, `SKILL.md`, and the reverse-engineering specification), and
the Gradle build files including `gradle/wrapper/gradle-wrapper.jar`.

## What was deliberately excluded

These are not part of the build and would have added ~79 MB and ~7,000 files:

| Excluded | Size | Reason |
| --- | --- | --- |
| `used skills/` | 62 MB, 6,949 files | Vendored third-party agent/skill packs. Reference material only; nothing in `settings.gradle.kts` builds them. |
| `deliverables/**/*.apk`, `*.aab`, `*.zip` | 17 MB | Compiled release binaries. `.gitignore` already excludes `*.apk`, `*.aab` and `*.jar`; this import follows that existing convention. The text reports (`TEST_REPORT.md`, `FEATURES.md`, `COMPATIBILITY_MATRIX.md`, `SHA256SUMS.txt`) were kept. |
| `extracted_ref/` | 28 KB | Decompiled reference dumps of a third-party app. |

`deliverables/v1.6/SHA256SUMS.txt` is retained but the artifacts it hashes are
not present in this repository. Rebuild them locally with the command in
`README.md` if the checksums are needed.

## Verification status of the imported base

Upstream's own `docs/TEST_REPORT.md` records 120 core and 117 engine unit tests
passing on JDK 21 / AGP 8.10.1 / SDK 36, and explicitly states that **no
physical device or emulator validation was performed**. That limitation is
inherited and still current: this sandbox has no JDK, Android SDK or Gradle and
no access to Google Maven, so the Android modules could not be compiled or run
here. The gaps and the honest capability limits are tracked in
`docs/FEATURE_TRACEABILITY.md`.
