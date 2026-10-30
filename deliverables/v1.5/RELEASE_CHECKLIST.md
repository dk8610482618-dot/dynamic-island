# Release gates

- [ ] Check actual current Play target-API and foreground-service requirements for the intended distribution date.
- [ ] Review overlay/listener permission disclosures and data-safety declarations.
- [x] Run core/engine tests, host/engine lint, R8 APK/AAB compilation and credential-pattern scan.
- [ ] Run connectedDebugAndroidTest on API 28/31/36 and the target Realme phone.
- [ ] Verify outside taps, capsule controls, secondary gap, notification shade and system navigation.
- [ ] Verify screen lock/unlock/off, rotations, folding, large fonts and permission revocation.
- [ ] Test at least two real media players, rapid track changes, timers and multiple SIMs.
- [ ] Review deterministic screenshots and device benchmark traces; generate a device profile.
- [ ] Configure ISLAND_KEYSTORE_PATH, ISLAND_KEYSTORE_PASSWORD, ISLAND_KEY_ALIAS and ISLAND_KEY_PASSWORD securely for publisher signing.
- [x] Build :app:assembleRelease and :app:bundleRelease; verify development APK signature and SHA-256 hashes.
- [ ] Test an upgrade using the same publisher key. A development-signed build cannot update another identity.

The user authorized committing this repair and its development-signed APK to the
repository main branch. That authorization does not include Play publication or
replacing a publisher signing key. Device checks below remain separate release gates.
