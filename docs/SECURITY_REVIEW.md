# Security and privacy review

Implemented safeguards:
- No accessibility service, broad background-launch override, Internet permission,
  arbitrary file-URI artwork access, telemetry or external backend.
- Overlay uses bounded public windows. Device-wide fullscreen touch interception and
  reflected input-region setup were removed. A system-app-op watcher ends the service
  when display permission is revoked. The persistent notification exposes Stop.
- Keyguard hides sensitive presentation, media artwork and notification content and
  blocks actions. Listener disconnection clears the inbox and media state.
- Actions use their original PendingIntent once. Canceled/refused actions do not
  produce local success. Timer cancel is typed, requires an explicit known action and
  waits for Android removal; generic Stop/Delete/first-action fallbacks are prohibited.
- Notification bodies are bounded, cache counts are bounded, artwork is downsampled
  along its largest dimension, obsolete artwork requests are generation/session checked.
- Settings import uses the document picker, caps input at 64 KiB and validates all
  fields before mutating preferences. Service state, grants and personal notification
  content are excluded. Export is an explicit local user action.
- Release shrinking is enabled. Framework-entry keep rules remain; obsolete accessibility
  and hidden-inset suppressions were removed. Publisher signing is optional and reads
  environment variables; no release key is checked into source.

Residual limits:
- Overlay/listener permissions are inherently sensitive and OEM scanning is independent
  of these source safeguards. No malware-scan or Play Protect clearance is claimed.
- Realme's reported warning cannot be reproduced without that firmware and scanner.
- An untrusted application overlay cannot cover protected system surfaces.
- Media notification fallback and coarse telephony status depend on external applications;
  advertised actions are checked but platform refusal remains possible.
- Static common-credential scanning is not a complete secret, dependency or penetration audit.

Review with the actual distribution channel before release: specialUse foreground
service declaration/justification, target-API deadlines, privacy disclosure and data
safety form, overlay/notification permission consent and publisher signing identity.
