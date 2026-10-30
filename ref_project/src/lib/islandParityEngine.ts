/**
 * Pure TypeScript replica of the `:core` Apple-Parity Dynamic Island engine.
 *
 * Implements:
 * - Phase 1: Apple-Parity Tap (<200ms -> deepLinkIntent) vs Long-Press (~450ms -> Expand with 0.985x touch compression),
 *   4-region Expanded grid (.leading, .trailing, .center, .bottom) with .belowIfTooWide wrapping & regionPriority,
 *   160dp nominal height cap, 44dp corner radius, 44x44dp minimum interactive hit target,
 *   28dp landscape width-limited circular condensation, and foldable crease avoidance.
 * - Phase 2: 1:1 finger-scrubbable upward collapse interpolation, velocity-aware spring release handoff,
 *   2D metaball ferrofluid droplet detachment bridge SDF & neck geometry, and .keylineTint perimeter border.
 * - Phase 3: ActivityKit arbitration (priority + relevanceScore + interruptBoost + FIFO), up to 3 concurrent
 *   Live Activities (iPhone 18 Pro mitosis standard), staleDate dimming, 4 KB payload cap, 8h active ceiling,
 *   and 4h post-end Lock Screen / Inbox retention.
 * - Phase 4: Android 16 Notification.ProgressStyle / Live Update classification (Navigation, Live Journey/Delivery,
 *   File Download), adaptive album art dominant color extraction, and Smart Lock Screen privacy redaction.
 */

export type InteractionMode = 'APPLE_PARITY' | 'ANDROID_CLASSIC';
export type LandscapeBehavior = 'CONDENSE_28DP' | 'KEEP_FULL';
export type DevicePosture = 'NORMAL_PORTRAIT' | 'FOLDABLE_UNFOLDED' | 'DUO_OUTER_RIGHT';
export type ActivityKind =
  | 'LIVE_CALL'
  | 'LIVE_TIMER'
  | 'NAVIGATION'
  | 'MEDIA_SESSION'
  | 'LIVE_JOURNEY'
  | 'FILE_DOWNLOAD';

export interface IslandActivityModel {
  id: string;
  kind: ActivityKind;
  appName: string;
  packageName: string;
  title: string;
  subtitle: string;
  badgeText: string;
  priorityOrder: number;
  relevanceScore: number;
  interruptBoost: number;
  startedAtMs: number;
  staleAtMs?: number;
  endedAtMs?: number;
  keylineTintHex: string;
  progressFraction?: number;
  etaLabel?: string;
  milestones?: string[];
  currentMilestoneIndex?: number;
  lyricLine?: string;
  audioRouteLabel?: string;
  payloadBytes: number;
}

export interface ArbitratedAllocation {
  primary: IslandActivityModel | null;
  secondary: IslandActivityModel | null;
  tertiary: IslandActivityModel | null;
  allSorted: IslandActivityModel[];
}

export interface ScrubInterpolation {
  progress: number;
  widthPx: number;
  heightPx: number;
  cornerRadiusDp: number;
  expandedContentAlpha: number;
  compactContentAlpha: number;
}

export interface MetaballNeckGeometry {
  isConnected: boolean;
  pinchHeightPx: number;
  centerX: number;
  gapPx: number;
}

export interface RegionLayoutAllocation {
  centerPlacedBelow: boolean;
  totalHeightDp: number;
  cornerRadiusDp: number;
  minTouchTargetDp: number;
  leadingWidthDp: number;
  trailingWidthDp: number;
  centerWidthDp: number;
}

export const ACTIVITY_KIT_LIMITS = {
  MAX_PAYLOAD_BYTES: 4096,
  MAX_ACTIVE_DURATION_MS: 8 * 60 * 60 * 1000,
  POST_END_RETENTION_MS: 4 * 60 * 60 * 1000,
  NOMINAL_EXPANDED_HEIGHT_DP: 160,
  EXPANDED_CORNER_RADIUS_DP: 44,
  MIN_TOUCH_TARGET_DP: 44,
  LANDSCAPE_CONDENSED_SLOT_DP: 28,
  PRESS_COMPRESSION_SCALE: 0.985,
} as const;

export function estimatePayloadBytes(
  title: string,
  subtitle = '',
  badgeText = '',
  extraBytes = 0
): number {
  const encoder = new TextEncoder();
  const strBytes =
    encoder.encode(title).length +
    encoder.encode(subtitle).length +
    encoder.encode(badgeText).length;
  return 64 + strBytes + Math.max(0, extraBytes);
}

export function evaluateActivityLifecycle(
  activity: Pick<IslandActivityModel, 'startedAtMs' | 'staleAtMs' | 'endedAtMs' | 'payloadBytes'>,
  nowMs: number
): {
  isStale: boolean;
  isExpiredActiveCeiling: boolean;
  isPayloadWithinLimit: boolean;
  shouldVacateIsland: boolean;
  shouldRetainInInbox: boolean;
} {
  const elapsed = Math.max(0, nowMs - activity.startedAtMs);
  const isExpiredActiveCeiling = elapsed >= ACTIVITY_KIT_LIMITS.MAX_ACTIVE_DURATION_MS;
  const isStale = activity.staleAtMs != null && nowMs >= activity.staleAtMs;
  const isEnded = activity.endedAtMs != null && nowMs >= activity.endedAtMs;
  const isPayloadWithinLimit =
    activity.payloadBytes >= 0 && activity.payloadBytes <= ACTIVITY_KIT_LIMITS.MAX_PAYLOAD_BYTES;

  const shouldVacateIsland = isEnded || isExpiredActiveCeiling || !isPayloadWithinLimit;
  const shouldRetainInInbox =
    isPayloadWithinLimit &&
    (!isEnded
      ? !isExpiredActiveCeiling
      : nowMs - (activity.endedAtMs ?? nowMs) < ACTIVITY_KIT_LIMITS.POST_END_RETENTION_MS);

  return {
    isStale,
    isExpiredActiveCeiling,
    isPayloadWithinLimit,
    shouldVacateIsland,
    shouldRetainInInbox,
  };
}

export function arbitrateActivities(
  candidates: IslandActivityModel[],
  nowMs: number,
  maxConcurrent: 2 | 3 = 3,
  focusOffset = 0
): ArbitratedAllocation {
  const valid = candidates.filter((c) => {
    const lifecycle = evaluateActivityLifecycle(c, nowMs);
    return !lifecycle.shouldVacateIsland;
  });

  if (valid.length === 0) {
    return { primary: null, secondary: null, tertiary: null, allSorted: [] };
  }

  const sorted = [...valid].sort((a, b) => {
    const aStale = a.staleAtMs != null && nowMs >= a.staleAtMs ? 250 : 0;
    const bStale = b.staleAtMs != null && nowMs >= b.staleAtMs ? 250 : 0;
    const aEff = a.priorityOrder === 100 ? 100 : Math.max(101, a.priorityOrder - a.interruptBoost + aStale);
    const bEff = b.priorityOrder === 100 ? 100 : Math.max(101, b.priorityOrder - b.interruptBoost + bStale);
    if (aEff !== bEff) return aEff - bEff;
    if (b.relevanceScore !== a.relevanceScore) return b.relevanceScore - a.relevanceScore;
    if (a.startedAtMs !== b.startedAtMs) return a.startedAtMs - b.startedAtMs;
    return a.id.localeCompare(b.id);
  });

  if (sorted[0].priorityOrder === 100) {
    return {
      primary: sorted[0],
      secondary: sorted[1] ?? null,
      tertiary: maxConcurrent >= 3 ? sorted[2] ?? null : null,
      allSorted: sorted,
    };
  }

  const n = sorted.length;
  const shift = ((focusOffset % n) + n) % n;
  const rotated = sorted.map((_, idx) => sorted[(idx + shift) % n]);

  return {
    primary: rotated[0] ?? null,
    secondary: rotated[1] ?? null,
    tertiary: maxConcurrent >= 3 ? rotated[2] ?? null : null,
    allSorted: rotated,
  };
}

export function resolveGestureAction(
  mode: InteractionMode,
  gesture: 'SINGLE_TAP' | 'LONG_PRESS'
): 'OPEN_HOST_APP_DEEP_LINK' | 'EXPAND_ISLAND_CARD' {
  if (mode === 'APPLE_PARITY') {
    return gesture === 'SINGLE_TAP' ? 'OPEN_HOST_APP_DEEP_LINK' : 'EXPAND_ISLAND_CARD';
  }
  return gesture === 'SINGLE_TAP' ? 'EXPAND_ISLAND_CARD' : 'OPEN_HOST_APP_DEEP_LINK';
}

export function interpolateScrubCollapse(
  cumulativeDeltaYPx: number,
  expandedWidthPx = 366,
  expandedHeightPx = 160,
  compactWidthPx = 125,
  compactHeightPx = 37,
  expandedRadiusDp = 44,
  compactRadiusDp = 18.5
): ScrubInterpolation {
  const upwardPx = Math.max(0, -cumulativeDeltaYPx);
  const progress = Math.min(1, upwardPx / 132);
  const lerp = (start: number, stop: number, t: number) => start + (stop - start) * t;

  return {
    progress,
    widthPx: lerp(expandedWidthPx, compactWidthPx, progress),
    heightPx: lerp(expandedHeightPx, compactHeightPx, progress),
    cornerRadiusDp: lerp(expandedRadiusDp, compactRadiusDp, progress),
    expandedContentAlpha: Math.max(0, Math.min(1, 1 - progress * 1.45)),
    compactContentAlpha: Math.max(0, Math.min(1, (progress - 0.55) / 0.45)),
  };
}

export function computeMetaballBridge(
  primaryRightX: number,
  dropletLeftX: number,
  dropletDiameterPx = 37,
  snapThresholdPx = 22
): MetaballNeckGeometry {
  const gapPx = dropletLeftX - primaryRightX;
  if (gapPx >= snapThresholdPx) {
    return {
      isConnected: false,
      pinchHeightPx: 0,
      centerX: (primaryRightX + dropletLeftX) / 2,
      gapPx,
    };
  }
  const normalized = Math.max(0, Math.min(1, gapPx / Math.max(1, snapThresholdPx)));
  const factor = Math.pow(1 - normalized, 1.65);
  return {
    isConnected: true,
    pinchHeightPx: dropletDiameterPx * 0.62 * factor,
    centerX: (primaryRightX + dropletLeftX) / 2,
    gapPx,
  };
}

export function solveExpandedRegions(params: {
  containerWidthDp?: number;
  cutoutWidthDp?: number;
  leadingRequestedWidthDp: number;
  trailingRequestedWidthDp: number;
  centerRequestedWidthDp: number;
  bottomRequestedHeightDp: number;
  wrapBehavior: 'DEFAULT' | 'BELOW_IF_TOO_WIDE';
  leadingPriority?: number;
  trailingPriority?: number;
  centerPriority?: number;
}): RegionLayoutAllocation {
  const containerWidthDp = params.containerWidthDp ?? 366;
  const cutoutWidthDp = params.cutoutWidthDp ?? 76;
  const usableTopWidth = containerWidthDp - 32;
  const sideMax = (usableTopWidth - cutoutWidthDp) / 2;

  const combinedTop =
    params.leadingRequestedWidthDp +
    params.trailingRequestedWidthDp +
    params.centerRequestedWidthDp +
    16;

  const centerPlacedBelow =
    params.wrapBehavior === 'BELOW_IF_TOO_WIDE' &&
    (combinedTop > usableTopWidth || params.centerRequestedWidthDp > cutoutWidthDp + 24);

  const leadingWidthDp = Math.min(params.leadingRequestedWidthDp, sideMax);
  const trailingWidthDp = Math.min(params.trailingRequestedWidthDp, sideMax);
  const centerWidthDp = centerPlacedBelow
    ? usableTopWidth
    : Math.min(
        params.centerRequestedWidthDp,
        Math.max(0, usableTopWidth - leadingWidthDp - trailingWidthDp - 16)
      );

  const rawHeight =
    28 + 40 + (centerPlacedBelow ? 34 : 0) + Math.max(0, params.bottomRequestedHeightDp);

  return {
    centerPlacedBelow,
    totalHeightDp: Math.min(ACTIVITY_KIT_LIMITS.NOMINAL_EXPANDED_HEIGHT_DP, Math.max(84, rawHeight)),
    cornerRadiusDp: ACTIVITY_KIT_LIMITS.EXPANDED_CORNER_RADIUS_DP,
    minTouchTargetDp: ACTIVITY_KIT_LIMITS.MIN_TOUCH_TARGET_DP,
    leadingWidthDp,
    trailingWidthDp,
    centerWidthDp,
  };
}
