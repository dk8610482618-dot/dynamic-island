---
name: island-release-quality
description: Prepare APK/AAB artifacts or modify dependencies, CI and signing.
---

Read docs/RELEASE_CHECKLIST.md. Library versions live in gradle/libs.versions.toml; plugin versions in settings.gradle.kts. Keep private signing material outside source and never echo secret values.

```sh
python3 tools/check-secrets.py
./gradlew :core:test :testDebugUnitTest :lintDebug :app:lintDebug :app:assembleRelease :app:bundleRelease
```

Verify apksigner, zip alignment, permissions, SHA-256 and absence of debug fixtures. Publisher signing uses ISLAND_KEYSTORE_PATH, ISLAND_KEYSTORE_PASSWORD, ISLAND_KEY_ALIAS, ISLAND_KEY_PASSWORD. Failure blocks publication; preserve reports and do not publish automatically.
