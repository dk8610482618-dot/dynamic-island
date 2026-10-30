/**
 * TypeScript mirror of the nine `:core` policies added for the research gaps
 * G1–G9 in `docs/PARITY_GAP_WORKLIST.md`.
 *
 * This is not a re-implementation with its own ideas: each function mirrors the
 * Kotlin object of the same name in `core/src/main/kotlin/com/dynamicisland/core/`
 * so the browser prototype exercises the *same* decisions the Android build
 * makes. Where the two could drift, the constants below are the ones asserted by
 * both test suites.
 *
 * Kotlin sources mirrored here:
 *   presentation/TransientAlertPolicy.kt   -> G1, G2
 *   privacy/AppSuppressionPolicy.kt        -> G3
 *   system/MiniStatusBarPolicy.kt          -> G4
 *   material/LiquidGlassPolicy.kt          -> G5
 *   system/ServiceWatchdogPolicy.kt        -> G6
 *   media/WaveformSourcePolicy.kt          -> G7
 *   call/CallSessionFeatures.kt            -> G8
 *   media/AudioRoutePolicy.kt              -> G9
 */

// ---------------------------------------------------------------------------
// G1 / G2 — transient alerting updates
// ---------------------------------------------------------------------------

export const MIN_AUTO_EXPAND_MS = 1_800;
export const MAX_AUTO_EXPAND_MS = 3_000;
export const DEFAULT_AUTO_EXPAND_MS = 2_000;
export const DEFAULT_SUPPRESSION_COOLDOWN_MS = 60_000;
export const MAX_TRACKED_SUPPRESSIONS = 32;

export type AlertImportance = 'PASSIVE' | 'DEFAULT' | 'TIME_SENSITIVE' | 'CRITICAL';

export type SuppressReason =
  | 'NOT_ALERTING'
  | 'REPEATED_PAYLOAD'
  | 'USER_SUPPRESSED'
  | 'LOCKED'
  | 'QUIET_HOURS'
  | 'USER_ALREADY_EXPANDED';

export interface AlertConfiguration {
  alertId: string;
  importance?: AlertImportance;
  autoExpandMs?: number;
  isRepeatOfPreviousPayload?: boolean;
  suppressionKey?: string;
}

export interface AutoExpandDecision {
  shouldAutoExpand: boolean;
  expandDurationMs: number;
  reason: SuppressReason | null;
  useCrossfadeInsteadOfSpring: boolean;
}

export interface AutoExpandContext {
  suppressionLedger?: Record<string, number>;
  isLocked?: boolean;
  quietHoursActive?: boolean;
  userIsHoldingExpanded?: boolean;
  reduceMotion?: boolean;
}

export function clampAutoExpand(requestedMs: number): number {
  return Math.min(Math.max(requestedMs, MIN_AUTO_EXPAND_MS), MAX_AUTO_EXPAND_MS);
}

export function resolveAutoExpand(
  configuration: AlertConfiguration,
  nowMillis: number,
  context: AutoExpandContext = {},
): AutoExpandDecision {
  const importance = configuration.importance ?? 'DEFAULT';
  const crossfade = context.reduceMotion ?? false;
  const deny = (reason: SuppressReason): AutoExpandDecision => ({
    shouldAutoExpand: false,
    expandDurationMs: 0,
    reason,
    useCrossfadeInsteadOfSpring: crossfade,
  });

  if (importance === 'PASSIVE') return deny('NOT_ALERTING');
  if (configuration.isRepeatOfPreviousPayload) return deny('REPEATED_PAYLOAD');
  if (isSuppressed(context.suppressionLedger ?? {}, configuration.suppressionKey ?? configuration.alertId, nowMillis)) {
    return deny('USER_SUPPRESSED');
  }
  if (context.isLocked) return deny('LOCKED');
  if (context.quietHoursActive && importance !== 'CRITICAL') return deny('QUIET_HOURS');
  if (context.userIsHoldingExpanded) return deny('USER_ALREADY_EXPANDED');

  const alerting = importance === 'TIME_SENSITIVE' || importance === 'CRITICAL';
  if (!alerting) return deny('NOT_ALERTING');

  return {
    shouldAutoExpand: true,
    expandDurationMs: clampAutoExpand(configuration.autoExpandMs ?? DEFAULT_AUTO_EXPAND_MS),
    reason: null,
    useCrossfadeInsteadOfSpring: crossfade,
  };
}

export function isSuppressed(
  ledger: Record<string, number>,
  suppressionKey: string,
  nowMillis: number,
): boolean {
  const expiry = ledger[suppressionKey];
  return expiry !== undefined && expiry > nowMillis;
}

export function suppressInstance(
  ledger: Record<string, number>,
  suppressionKey: string,
  nowMillis: number,
  cooldownMs: number = DEFAULT_SUPPRESSION_COOLDOWN_MS,
): Record<string, number> {
  const live: Record<string, number> = {};
  for (const [key, expiry] of Object.entries(ledger)) {
    if (expiry > nowMillis) live[key] = expiry;
  }
  live[suppressionKey] = nowMillis + Math.max(cooldownMs, 0);

  const entries = Object.entries(live);
  if (entries.length <= MAX_TRACKED_SUPPRESSIONS) return live;

  // Keep the freshest keys; the ledger lives in a service that runs for days.
  entries.sort((a, b) => b[1] - a[1]);
  return Object.fromEntries(entries.slice(0, MAX_TRACKED_SUPPRESSIONS));
}

export type PresentationAfterDismissal = 'RESTORE_UNDERLYING' | 'IDLE';

export interface SwipeDismissal {
  alertIdToRemove: string;
  /**
   * The heart of G1: dismissing an alert never ends the activity underneath it.
   */
  activityIdsToRetain: string[];
  nextPresentation: PresentationAfterDismissal;
  suppressionLedger: Record<string, number>;
}

export function resolveSwipeDismissal(
  ledger: Record<string, number>,
  alert: AlertConfiguration,
  underlyingActivityIds: string[],
  alertProducingActivityId: string | null,
  nowMillis: number,
  cooldownMs: number = DEFAULT_SUPPRESSION_COOLDOWN_MS,
): SwipeDismissal {
  const retained = [...underlyingActivityIds];
  if (alertProducingActivityId !== null && !retained.includes(alertProducingActivityId)) {
    retained.push(alertProducingActivityId);
  }
  return {
    alertIdToRemove: alert.alertId,
    activityIdsToRetain: retained,
    nextPresentation: retained.length === 0 ? 'IDLE' : 'RESTORE_UNDERLYING',
    suppressionLedger: suppressInstance(
      ledger,
      alert.suppressionKey ?? alert.alertId,
      nowMillis,
      cooldownMs,
    ),
  };
}

// ---------------------------------------------------------------------------
// G3 — contextual app suppression / full-screen auto-hide
// ---------------------------------------------------------------------------

export type PackageRule = 'ALLOW' | 'HIDE_NON_CRITICAL' | 'HIDE_ALWAYS';

export type SuppressionReason =
  | 'USER_RULE_HIDE_ALWAYS'
  | 'USER_RULE_HIDE_NON_CRITICAL'
  | 'GLOBAL_FULLSCREEN_IMMERSIVE'
  | 'GLOBAL_FULLSCREEN_VIDEO';

export interface ForegroundAppState {
  packageName: string;
  isImmersiveFullscreen?: boolean;
  isPlayingVideo?: boolean;
  isScreenOn?: boolean;
}

export interface SuppressionDecision {
  hideNothing: boolean;
  hideNonCritical: boolean;
  reason: SuppressionReason | null;
}

export const VISIBLE: SuppressionDecision = { hideNothing: false, hideNonCritical: false, reason: null };

export function resolvesSuppressesOverlay(d: SuppressionDecision): boolean {
  return d.hideNothing || d.hideNonCritical;
}

export function resolveAppSuppression(
  foreground: ForegroundAppState | null,
  packageRules: Record<string, PackageRule>,
  autoHideInFullscreen: boolean,
  pendingAlertIsCritical = false,
  islandEnabled = true,
): SuppressionDecision {
  if (!islandEnabled || foreground === null || foreground.isScreenOn === false) return VISIBLE;

  const rule = packageRules[foreground.packageName];

  // An explicit per-package rule always wins over the global heuristic.
  if (rule === 'HIDE_ALWAYS') {
    return { hideNothing: true, hideNonCritical: false, reason: 'USER_RULE_HIDE_ALWAYS' };
  }
  if (rule === 'HIDE_NON_CRITICAL') {
    return pendingAlertIsCritical
      ? VISIBLE
      : { hideNothing: false, hideNonCritical: true, reason: 'USER_RULE_HIDE_NON_CRITICAL' };
  }

  if (!autoHideInFullscreen) return VISIBLE;

  if (foreground.isImmersiveFullscreen) {
    return pendingAlertIsCritical
      ? VISIBLE
      : { hideNothing: false, hideNonCritical: true, reason: 'GLOBAL_FULLSCREEN_IMMERSIVE' };
  }
  if (foreground.isPlayingVideo) {
    return pendingAlertIsCritical
      ? VISIBLE
      : { hideNothing: false, hideNonCritical: true, reason: 'GLOBAL_FULLSCREEN_VIDEO' };
  }
  return VISIBLE;
}

export function mayShowCriticalAlert(d: SuppressionDecision): boolean {
  return !d.hideNothing;
}

// ---------------------------------------------------------------------------
// G4 — immersive mini status bar
// ---------------------------------------------------------------------------

export const MINUTE_MS = 60_000;

export type SignalLevel = 'NONE' | 'POOR' | 'MODERATE' | 'GOOD' | 'EXCELLENT';

export const SIGNAL_BARS: Record<SignalLevel, number> = {
  NONE: 0,
  POOR: 1,
  MODERATE: 2,
  GOOD: 3,
  EXCELLENT: 4,
};

function floorMod(value: number, modulus: number): number {
  const result = value % modulus;
  return result < 0 ? result + modulus : result;
}

/**
 * Milliseconds until the displayed minute changes. The Android renderer used to
 * capture the clock once with `remember { SimpleDateFormat(...) }` and show a
 * frozen time forever; scheduling on this value fixes it without polling.
 */
export function millisUntilNextClockTick(nowMillis: number, timezoneOffsetMinutes = 0): number {
  if (nowMillis <= 0) return MINUTE_MS;
  const shifted = nowMillis + timezoneOffsetMinutes * MINUTE_MS;
  const remaining = MINUTE_MS - floorMod(shifted, MINUTE_MS);
  return remaining <= 0 ? 1 : remaining;
}

export function formatClock(nowMillis: number, timezoneOffsetMinutes = 0, is24Hour = true): string {
  const shifted = nowMillis + timezoneOffsetMinutes * MINUTE_MS;
  const minutesOfDay = floorMod(Math.floor(shifted / MINUTE_MS), 24 * 60);
  const hour24 = Math.floor(minutesOfDay / 60);
  const minute = minutesOfDay % 60;
  const pad = (n: number) => String(n).padStart(2, '0');
  if (is24Hour) return `${pad(hour24)}:${pad(minute)}`;
  const hour12 = hour24 % 12 === 0 ? 12 : hour24 % 12;
  return `${hour12}:${pad(minute)} ${hour24 < 12 ? 'AM' : 'PM'}`;
}

export function wifiLevel(rssiDbm: number): SignalLevel {
  if (rssiDbm >= -55) return 'EXCELLENT';
  if (rssiDbm >= -66) return 'GOOD';
  if (rssiDbm >= -88) return 'MODERATE';
  if (rssiDbm >= -100) return 'POOR';
  return 'NONE';
}

export function cellularLevel(rsrpDbm: number): SignalLevel {
  if (rsrpDbm >= -88) return 'EXCELLENT';
  if (rsrpDbm >= -98) return 'GOOD';
  if (rsrpDbm >= -108) return 'MODERATE';
  if (rsrpDbm >= -118) return 'POOR';
  return 'NONE';
}

export function batteryPresentation(
  percent: number,
  isCharging: boolean,
  isPowerSaveMode: boolean,
  showPercentText: boolean,
): { percent: number; text: string | null; tint: string; fillFraction: number } {
  const clamped = Math.min(Math.max(percent, 0), 100);
  let tint = '#FFFFFF';
  if (isCharging) tint = '#30D158';
  else if (isPowerSaveMode) tint = '#FFD60A';
  else if (clamped <= 10) tint = '#FF453A';
  else if (clamped <= 20) tint = '#FF9F0A';
  return {
    percent: clamped,
    text: showPercentText && !isCharging ? String(clamped) : null,
    tint,
    fillFraction: clamped / 100,
  };
}

// ---------------------------------------------------------------------------
// G5 — Liquid Glass, contrast, 8-point grid
// ---------------------------------------------------------------------------

export const GRID_DP = 8;
export const MIN_CONTRAST_BODY = 4.5;
export const MIN_CONTRAST_LARGE = 3.0;
export const LARGE_TEXT_SP = 18;

export type GlassSurface = 'CARD' | 'CONTROL' | 'POPOVER';
export type SurfaceTreatment = 'GLASS' | 'SOLID_TINT' | 'NONE';

export interface ResolvedSurface {
  surface: GlassSurface;
  treatment: SurfaceTreatment;
  blurRadiusDp: number;
  tintAlpha: number;
  demotedToAvoidStacking: boolean;
}

const BLUR_BY_SURFACE: Record<GlassSurface, number> = { CARD: 24, CONTROL: 12, POPOVER: 32 };

const TINT_ALPHA: Record<SurfaceTreatment, Record<GlassSurface, number>> = {
  NONE: { CARD: 0, CONTROL: 0, POPOVER: 0 },
  GLASS: { CARD: 0.32, CONTROL: 0.18, POPOVER: 0.28 },
  // A demoted control keeps a lighter tint than its card so it still reads as
  // raised even though its blur is gone.
  SOLID_TINT: { CARD: 0.92, CONTROL: 0.26, POPOVER: 0.95 },
};

/** At most one layer in any parent→child chain may blur. */
export function resolveSurfaces(
  chain: GlassSurface[],
  appearanceIsLiquidGlass: boolean,
  opts: { reduceTransparency?: boolean; lowPowerMode?: boolean; parentIsGlass?: boolean } = {},
): ResolvedSurface[] {
  const glassAllowed =
    appearanceIsLiquidGlass && !(opts.reduceTransparency ?? false) && !(opts.lowPowerMode ?? false);

  let ancestorBlurred = opts.parentIsGlass ?? false;
  return chain.map((surface) => {
    const wantsGlass = glassAllowed;
    const treatment: SurfaceTreatment = !wantsGlass
      ? 'NONE'
      : ancestorBlurred
        ? 'SOLID_TINT'
        : 'GLASS';
    if (treatment === 'GLASS') ancestorBlurred = true;
    return {
      surface,
      treatment,
      blurRadiusDp: treatment === 'GLASS' && !opts.lowPowerMode ? BLUR_BY_SURFACE[surface] : 0,
      tintAlpha: TINT_ALPHA[treatment][surface],
      demotedToAvoidStacking: wantsGlass && treatment === 'SOLID_TINT',
    };
  });
}

export function snapToGrid(valueDp: number, gridDp: number = GRID_DP): number {
  if (gridDp <= 0) return valueDp;
  return Math.round(valueDp / gridDp) * gridDp;
}

function channel(v: number): number {
  return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
}

/** WCAG relative luminance from an `[r,g,b]` triple in 0..255. */
export function relativeLuminance(rgb: [number, number, number]): number {
  const [r, g, b] = rgb.map((c) => channel(c / 255));
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

export function contrastRatio(
  fg: [number, number, number],
  bg: [number, number, number],
): number {
  const l1 = relativeLuminance(fg);
  const l2 = relativeLuminance(bg);
  const lighter = Math.max(l1, l2);
  const darker = Math.min(l1, l2);
  return (lighter + 0.05) / (darker + 0.05);
}

export function meetsContrast(
  fg: [number, number, number],
  bg: [number, number, number],
  textSizeSp: number,
  isBoldOrHeavier = false,
): boolean {
  const isLarge = textSizeSp >= LARGE_TEXT_SP || (isBoldOrHeavier && textSizeSp >= 14);
  return contrastRatio(fg, bg) >= (isLarge ? MIN_CONTRAST_LARGE : MIN_CONTRAST_BODY);
}

export function scaleForDynamicType(baseSp: number, fontScale: number, maxSp = 34): number {
  return Math.min(baseSp * Math.min(Math.max(fontScale, 0.85), 3.0), maxSp);
}

export function expandedHeightDp(naturalHeightDp: number, fontScale: number, hardCapDp = 160): number {
  return Math.min(naturalHeightDp * Math.max(fontScale, 1), hardCapDp);
}

// ---------------------------------------------------------------------------
// G6 — foreground-service watchdog
// ---------------------------------------------------------------------------

export const DEFAULT_CHECK_INTERVAL_MS = 60_000;
export const MAX_BACKOFF_MS = 60 * 60_000;
export const BASE_RETRY_MS = 15_000;
export const WATCHDOG_FAILURE_THRESHOLD = 2;
export const MAX_EXEMPTION_PROMPTS = 2;

export type WatchdogVerdict =
  | 'HEALTHY'
  | 'REATTACH_OVERLAY'
  | 'LISTENER_DISCONNECTED'
  | 'RESTART_SERVICE'
  | 'BLOCKED'
  | 'DISABLED';

export interface HealthSignal {
  isServiceRunning: boolean;
  isOverlayAttached: boolean;
  isListenerConnected: boolean;
  millisSinceLastHeartbeat: number;
  hasBatteryExemption: boolean;
  exemptionDeclineCount?: number;
  isUserEnabled?: boolean;
  isDeviceIdle?: boolean;
}

export interface WatchdogDecision {
  verdict: WatchdogVerdict;
  delayMs: number;
  consecutiveFailures: number;
  requiresUserAction: boolean;
}

export function backoffFor(consecutiveFailures: number): number {
  if (consecutiveFailures <= 0) return 0;
  const exponential = BASE_RETRY_MS * Math.pow(2, Math.min(consecutiveFailures - 1, 20));
  return Math.min(exponential, MAX_BACKOFF_MS);
}

export function isWedged(millisSinceLastHeartbeat: number, checkIntervalMs = DEFAULT_CHECK_INTERVAL_MS): boolean {
  return millisSinceLastHeartbeat > checkIntervalMs * WATCHDOG_FAILURE_THRESHOLD;
}

export function evaluateWatchdog(
  signal: HealthSignal,
  consecutiveFailures = 0,
  checkIntervalMs = DEFAULT_CHECK_INTERVAL_MS,
): WatchdogDecision {
  if (signal.isUserEnabled === false) return { verdict: 'DISABLED', delayMs: 0, consecutiveFailures: 0, requiresUserAction: false };
  if (signal.isDeviceIdle) {
    return { verdict: 'BLOCKED', delayMs: checkIntervalMs, consecutiveFailures, requiresUserAction: false };
  }

  const serviceDead = !signal.isServiceRunning || isWedged(signal.millisSinceLastHeartbeat, checkIntervalMs);
  if (serviceDead) {
    const failures = consecutiveFailures + 1;
    const blockedByPolicy =
      !signal.hasBatteryExemption && (signal.exemptionDeclineCount ?? 0) >= MAX_EXEMPTION_PROMPTS;
    return blockedByPolicy
      ? { verdict: 'BLOCKED', delayMs: 0, consecutiveFailures: failures, requiresUserAction: true }
      : { verdict: 'RESTART_SERVICE', delayMs: backoffFor(failures), consecutiveFailures: failures, requiresUserAction: false };
  }

  if (!signal.isOverlayAttached) {
    return { verdict: 'REATTACH_OVERLAY', delayMs: checkIntervalMs, consecutiveFailures: 0, requiresUserAction: false };
  }
  if (!signal.isListenerConnected) {
    return { verdict: 'LISTENER_DISCONNECTED', delayMs: checkIntervalMs, consecutiveFailures: 0, requiresUserAction: true };
  }
  return { verdict: 'HEALTHY', delayMs: checkIntervalMs, consecutiveFailures: 0, requiresUserAction: false };
}

// ---------------------------------------------------------------------------
// G7 — waveform source honesty
// ---------------------------------------------------------------------------

export type WaveformSource = 'REAL_FFT' | 'SYNTHETIC' | 'FLAT' | 'UNAVAILABLE';

export type WaveformUnavailableReason =
  | 'NO_RECORD_AUDIO_PERMISSION'
  | 'VISUALIZER_UNSUPPORTED_BY_DEVICE'
  | 'SESSION_ZERO_RESTRICTED'
  | 'API_LEVEL_TOO_LOW'
  | 'NOT_PLAYING'
  | 'NOT_VISIBLE'
  | 'REDUCED_MOTION';

export interface WaveformCapability {
  source: WaveformSource;
  reason: WaveformUnavailableReason | null;
  /** False means the UI must not call this a visualiser or a spectrum. */
  mayBeDescribedAsRealSpectrum: boolean;
}

export const MIN_API_FOR_VISUALIZER = 19;

export function resolveWaveformSource(input: {
  isVisible: boolean;
  isPlaying: boolean;
  waveformEnabledInSettings: boolean;
  hasRecordAudioPermission: boolean;
  deviceSupportsVisualizer: boolean;
  visualizerSessionAttached: boolean;
  apiLevel: number;
  reduceMotion?: boolean;
}): WaveformCapability {
  const cap = (source: WaveformSource, reason: WaveformUnavailableReason | null = null): WaveformCapability => ({
    source,
    reason,
    mayBeDescribedAsRealSpectrum: source === 'REAL_FFT',
  });

  if (!input.isVisible) return cap('FLAT', 'NOT_VISIBLE');
  if (!input.waveformEnabledInSettings) return cap('FLAT', null);
  if (!input.isPlaying) return cap('FLAT', 'NOT_PLAYING');
  if (input.reduceMotion) return cap('FLAT', 'REDUCED_MOTION');

  const realAvailable =
    input.apiLevel >= MIN_API_FOR_VISUALIZER &&
    input.hasRecordAudioPermission &&
    input.deviceSupportsVisualizer &&
    input.visualizerSessionAttached;
  if (realAvailable) return cap('REAL_FFT', null);

  let reason: WaveformUnavailableReason;
  if (input.apiLevel < MIN_API_FOR_VISUALIZER) reason = 'API_LEVEL_TOO_LOW';
  else if (!input.hasRecordAudioPermission) reason = 'NO_RECORD_AUDIO_PERMISSION';
  else if (!input.deviceSupportsVisualizer) reason = 'VISUALIZER_UNSUPPORTED_BY_DEVICE';
  else reason = 'SESSION_ZERO_RESTRICTED';
  return cap('SYNTHETIC', reason);
}

/**
 * Deterministic indicative motion. Derived from playback *position*, so pausing
 * freezes the bars instead of rippling — which is what stops it reading as a
 * live analyser of someone else's audio.
 */
export function syntheticAmplitudes(
  barCount: number,
  phaseRadians: number,
  energy = 0.7,
  isPlaying = true,
): number[] {
  if (barCount <= 0) return [];
  if (!isPlaying) return new Array(barCount).fill(0);
  const amplitude = Math.min(Math.max(energy, 0), 1);
  return Array.from({ length: barCount }, (_, index) => {
    const normalised = (index + 0.5) / barCount;
    const primary = Math.sin(phaseRadians + normalised * 3.1);
    const secondary = Math.sin(phaseRadians * 0.63 + normalised * 7.7);
    const envelope = 0.55 + 0.45 * Math.sin(normalised * Math.PI);
    const value = (0.5 + 0.32 * primary + 0.18 * secondary) * envelope * amplitude;
    return Math.min(Math.max(value, 0.06), 1);
  });
}

export function syntheticPhase(positionMs: number, playbackSpeed = 1): number {
  const speed = playbackSpeed > 0 ? playbackSpeed : 1;
  return ((positionMs * speed) / 900) * 2 * Math.PI;
}

/** Logarithmic binning: linear binning leaves the bars looking dead. */
export function binMagnitudes(magnitudes: number[], barCount: number, minBin = 1): number[] {
  if (barCount <= 0 || magnitudes.length < 2) return new Array(Math.max(barCount, 0)).fill(0);
  const from = Math.min(Math.max(minBin, 1), magnitudes.length - 1);
  const to = Math.min(Math.max(from + 1, magnitudes.length), magnitudes.length);

  if (to - from < barCount) {
    const head = [];
    for (let i = from; i < to; i++) head.push(magnitudes[i]);
    while (head.length < barCount) head.push(to > from ? magnitudes[to - 1] : 0);
    return head;
  }

  const ratio = Math.pow((to - 1) / from, 1 / barCount);
  const bars: number[] = [];
  let start = from;
  for (let b = 0; b < barCount; b++) {
    const end = Math.min(start * ratio, to);
    const lo = Math.min(Math.max(Math.floor(start), from), to - 1);
    const hi = Math.min(Math.max(Math.floor(end), lo + 1), to);
    let sum = 0;
    let count = 0;
    for (let i = lo; i < hi; i++) {
      sum += magnitudes[i];
      count++;
    }
    bars.push(count > 0 ? Math.min(Math.max(sum / count, 0), 1) : 0);
    start = end;
  }
  return bars;
}

export function flatten(current: number[], progress: number): number[] {
  const p = Math.min(Math.max(progress, 0), 1);
  return current.map((v) => v * (1 - p));
}

// ---------------------------------------------------------------------------
// G8 — call session features (SharePlay / Live Captions / Live Translation)
// ---------------------------------------------------------------------------

export type CallFeature =
  | 'MICROPHONE_MUTE'
  | 'SPEAKER_ROUTING'
  | 'SCREEN_SHARE'
  | 'REMOTE_CONTROL_REQUEST'
  | 'LIVE_CAPTIONS'
  | 'LIVE_TRANSLATION'
  | 'SHAREPLAY_SESSION'
  | 'HOLD';

export type FeatureOrigin = 'DEVICE_STATE' | 'SOURCE_ADVERTISED' | 'UNAVAILABLE';

export const CALL_EXTRA_KEYS = {
  LIVE_CAPTIONS_ACTIVE: 'island.feature.live_captions',
  LIVE_TRANSLATION_ACTIVE: 'island.feature.live_translation',
  SHAREPLAY_SESSION_ACTIVE: 'island.feature.shared_session',
  SCREEN_SHARE_ACTIVE: 'island.feature.screen_share',
  REMOTE_CONTROL_REQUESTED: 'island.feature.remote_control',
} as const;

export const MIN_API_FOR_LIVE_CAPTION = 30;

export interface CallFeatureState {
  feature: CallFeature;
  origin: FeatureOrigin;
  isActive: boolean;
  isActionable: boolean;
  unavailableReason: string | null;
  sourceLabel: string | null;
  /** The honesty rule: only device- or source-verified state may render as lit. */
  mayRenderAsActive: boolean;
  mayRenderAtAll: boolean;
}

export interface CallObservation {
  isCallActive: boolean;
  isMuted?: boolean;
  isSpeakerOn?: boolean;
  isOnHold?: boolean;
  isControllable?: boolean;
  apiLevel?: number;
  systemCaptioningEnabled?: boolean;
  advertisedExtraKeys?: string[];
}

const ALL_CALL_FEATURES: CallFeature[] = [
  'MICROPHONE_MUTE',
  'SPEAKER_ROUTING',
  'SCREEN_SHARE',
  'REMOTE_CONTROL_REQUEST',
  'LIVE_CAPTIONS',
  'LIVE_TRANSLATION',
  'SHAREPLAY_SESSION',
  'HOLD',
];

function featureState(
  feature: CallFeature,
  origin: FeatureOrigin,
  opts: { isActive?: boolean; isActionable?: boolean; unavailableReason?: string | null; sourceLabel?: string | null } = {},
): CallFeatureState {
  const isActive = opts.isActive ?? false;
  return {
    feature,
    origin,
    isActive,
    isActionable: opts.isActionable ?? false,
    unavailableReason: opts.unavailableReason ?? null,
    sourceLabel: opts.sourceLabel ?? null,
    mayRenderAsActive: isActive && origin !== 'UNAVAILABLE',
    mayRenderAtAll: origin !== 'UNAVAILABLE' || (opts.unavailableReason ?? null) === null,
  };
}

export function resolveCallFeatures(observation: CallObservation, sessionLabel: string | null = null): CallFeatureState[] {
  if (!observation.isCallActive) {
    return ALL_CALL_FEATURES.map((f) =>
      featureState(f, 'UNAVAILABLE', { unavailableReason: 'NO_ACTIVE_CALL' }),
    );
  }

  const advertised = new Set(observation.advertisedExtraKeys ?? []);
  const controllable = observation.isControllable ?? false;
  const apiLevel = observation.apiLevel ?? 34;

  const advertisedOr = (key: string, feature: CallFeature, reason: string): CallFeatureState =>
    advertised.has(key)
      ? featureState(feature, 'SOURCE_ADVERTISED', { isActive: true, sourceLabel: sessionLabel })
      : featureState(feature, 'UNAVAILABLE', { unavailableReason: reason });

  let captions: CallFeatureState;
  if (advertised.has(CALL_EXTRA_KEYS.LIVE_CAPTIONS_ACTIVE)) {
    captions = featureState('LIVE_CAPTIONS', 'SOURCE_ADVERTISED', { isActive: true });
  } else if (apiLevel < MIN_API_FOR_LIVE_CAPTION) {
    captions = featureState('LIVE_CAPTIONS', 'UNAVAILABLE', { unavailableReason: 'API_LEVEL_TOO_LOW' });
  } else if (observation.systemCaptioningEnabled) {
    // A system caption *preference* is real, but it is not a running session.
    captions = featureState('LIVE_CAPTIONS', 'DEVICE_STATE', { sourceLabel: 'System captions on' });
  } else {
    captions = featureState('LIVE_CAPTIONS', 'UNAVAILABLE', { unavailableReason: 'NOT_ADVERTISED_BY_SOURCE' });
  }

  return [
    featureState('MICROPHONE_MUTE', 'DEVICE_STATE', { isActive: observation.isMuted ?? false, isActionable: controllable }),
    featureState('SPEAKER_ROUTING', 'DEVICE_STATE', { isActive: observation.isSpeakerOn ?? false, isActionable: controllable }),
    featureState('HOLD', 'DEVICE_STATE', { isActive: observation.isOnHold ?? false, isActionable: controllable }),
    advertisedOr(CALL_EXTRA_KEYS.SCREEN_SHARE_ACTIVE, 'SCREEN_SHARE', 'NOT_ADVERTISED_BY_SOURCE'),
    advertisedOr(CALL_EXTRA_KEYS.REMOTE_CONTROL_REQUESTED, 'REMOTE_CONTROL_REQUEST', 'NOT_ADVERTISED_BY_SOURCE'),
    captions,
    advertisedOr(CALL_EXTRA_KEYS.LIVE_TRANSLATION_ACTIVE, 'LIVE_TRANSLATION', 'NO_PUBLIC_API'),
    advertisedOr(CALL_EXTRA_KEYS.SHAREPLAY_SESSION_ACTIVE, 'SHAREPLAY_SESSION', 'NOT_ADVERTISED_BY_SOURCE'),
  ];
}

export function visibleCallFeatures(states: CallFeatureState[]): CallFeatureState[] {
  return states.filter((s) => s.mayRenderAtAll);
}

export function activeCallFeatures(states: CallFeatureState[]): CallFeatureState[] {
  return states.filter((s) => s.mayRenderAsActive);
}

// ---------------------------------------------------------------------------
// G9 — audio output routing
// ---------------------------------------------------------------------------

export type AudioRouteKind =
  | 'BUILTIN_SPEAKER'
  | 'BUILTIN_EARPIECE'
  | 'WIRED_HEADSET'
  | 'WIRED_HEADPHONES'
  | 'USB_AUDIO'
  | 'BLUETOOTH_A2DP'
  | 'BLUETOOTH_LE_AUDIO'
  | 'BLUETOOTH_SCO'
  | 'HEARING_AID'
  | 'CAST'
  | 'UNKNOWN';

export type RouteContext = 'CALL' | 'MEDIA';
export type FallbackAction = 'NONE' | 'SOURCE_APP_SHEET' | 'SYSTEM_SETTINGS';

export const MIN_API_FOR_COMMUNICATION_DEVICE = 31;

export interface RoutingCapability {
  context: RouteContext;
  canChangeRoute: boolean;
  apiLevel: number;
  fallback: FallbackAction;
  limitation: string | null;
}

export interface RouteOption {
  kind: AudioRouteKind;
  label: string;
  isAvailable: boolean;
  isSelected: boolean;
  /** False means "informational row", never a dead-looking toggle. */
  isSelectable: boolean;
}

export function resolveRoutingCapability(
  context: RouteContext,
  apiLevel: number,
  isControllableCall = false,
  sourceAppExposesRoutingSheet = false,
): RoutingCapability {
  if (context === 'CALL') {
    if (apiLevel >= MIN_API_FOR_COMMUNICATION_DEVICE && isControllableCall) {
      return { context, canChangeRoute: true, apiLevel, fallback: 'NONE', limitation: null };
    }
    if (isControllableCall) {
      return {
        context,
        canChangeRoute: false,
        apiLevel,
        fallback: 'SYSTEM_SETTINGS',
        limitation: 'Changing call audio needs Android 12 (API 31) or newer.',
      };
    }
    return {
      context,
      canChangeRoute: false,
      apiLevel,
      fallback: 'SYSTEM_SETTINGS',
      limitation: 'Only the app handling the call can change its audio route.',
    };
  }
  // Android gives no third-party path to reroute another app's media output.
  return {
    context,
    canChangeRoute: false,
    apiLevel,
    fallback: sourceAppExposesRoutingSheet ? 'SOURCE_APP_SHEET' : 'SYSTEM_SETTINGS',
    limitation:
      "Android does not let one app change another app's media output. This opens the player's own output picker instead.",
  };
}

const ROUTE_ORDER: AudioRouteKind[] = [
  'BUILTIN_SPEAKER',
  'BUILTIN_EARPIECE',
  'WIRED_HEADPHONES',
  'WIRED_HEADSET',
  'USB_AUDIO',
  'BLUETOOTH_A2DP',
  'BLUETOOTH_LE_AUDIO',
  'BLUETOOTH_SCO',
  'HEARING_AID',
  'CAST',
  'UNKNOWN',
];

const ROUTE_LABELS: Record<AudioRouteKind, string> = {
  BUILTIN_SPEAKER: 'Speaker',
  BUILTIN_EARPIECE: 'Earpiece',
  WIRED_HEADSET: 'Wired headset',
  WIRED_HEADPHONES: 'Headphones',
  USB_AUDIO: 'USB audio',
  BLUETOOTH_A2DP: 'Bluetooth',
  BLUETOOTH_LE_AUDIO: 'Bluetooth LE Audio',
  BLUETOOTH_SCO: 'Bluetooth (call)',
  HEARING_AID: 'Hearing aid',
  CAST: 'Cast',
  UNKNOWN: 'Other output',
};

export function buildRouteOptions(
  availableKinds: AudioRouteKind[],
  selectedKind: AudioRouteKind | null,
  capability: RoutingCapability,
): RouteOption[] {
  const available = new Set(availableKinds);
  return ROUTE_ORDER.filter((kind) => available.has(kind)).map((kind) => ({
    kind,
    label: ROUTE_LABELS[kind],
    isAvailable: true,
    isSelected: kind === selectedKind,
    isSelectable: capability.canChangeRoute && kind !== selectedKind,
  }));
}

export function routeAccessibilityLabel(option: RouteOption): string {
  if (option.isSelected) return `${option.label}, current output`;
  if (option.isSelectable) return `Switch output to ${option.label}`;
  return `${option.label}, available. Open the player's output picker to change.`;
}
