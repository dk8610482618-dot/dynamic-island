import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import {
  ACTIVITY_KIT_LIMITS,
  arbitrateActivities,
  computeMetaballBridge,
  estimatePayloadBytes,
  evaluateActivityLifecycle,
  interpolateScrubCollapse,
  resolveGestureAction,
  solveExpandedRegions,
  type IslandActivityModel,
} from './islandParityEngine.ts';

describe('Apple-Parity Dynamic Island Core Logic', () => {
  it('routes single tap to host app deep link and long press to expand in APPLE_PARITY mode', () => {
    assert.equal(resolveGestureAction('APPLE_PARITY', 'SINGLE_TAP'), 'OPEN_HOST_APP_DEEP_LINK');
    assert.equal(resolveGestureAction('APPLE_PARITY', 'LONG_PRESS'), 'EXPAND_ISLAND_CARD');
    assert.equal(resolveGestureAction('ANDROID_CLASSIC', 'SINGLE_TAP'), 'EXPAND_ISLAND_CARD');
  });

  it('enforces ActivityKit 4KB payload cap, staleDate dimming, and 8h active ceiling', () => {
    const now = 1_700_000_000_000;
    const oversizedBytes = estimatePayloadBytes('Order Status', 'X'.repeat(4200));
    assert.equal(oversizedBytes > ACTIVITY_KIT_LIMITS.MAX_PAYLOAD_BYTES, true);

    const staleEval = evaluateActivityLifecycle(
      {
        startedAtMs: now - 60_000,
        staleAtMs: now - 1_000,
        payloadBytes: 512,
      },
      now
    );
    assert.equal(staleEval.isStale, true);
    assert.equal(staleEval.shouldVacateIsland, false);

    const ceilingEval = evaluateActivityLifecycle(
      {
        startedAtMs: now - ACTIVITY_KIT_LIMITS.MAX_ACTIVE_DURATION_MS - 1,
        payloadBytes: 512,
      },
      now
    );
    assert.equal(ceilingEval.isExpiredActiveCeiling, true);
    assert.equal(ceilingEval.shouldVacateIsland, true);
  });

  it('arbitrates up to 3 concurrent activities (primary, secondary, tertiary) and cycles focus', () => {
    const now = 1_700_000_000_000;
    const activities: IslandActivityModel[] = [
      {
        id: 'media-1',
        kind: 'MEDIA_SESSION',
        appName: 'Apple Music',
        packageName: 'com.apple.android.music',
        title: 'Starboy',
        subtitle: 'The Weeknd',
        badgeText: 'WAVE',
        priorityOrder: 400,
        relevanceScore: 0.75,
        interruptBoost: 0,
        startedAtMs: now - 20_000,
        keylineTintHex: '#FF2D55',
        payloadBytes: 256,
      },
      {
        id: 'timer-1',
        kind: 'LIVE_TIMER',
        appName: 'Clock',
        packageName: 'com.google.android.deskclock',
        title: 'Espresso Extraction',
        subtitle: 'Countdown',
        badgeText: '00:28',
        priorityOrder: 200,
        relevanceScore: 0.9,
        interruptBoost: 0,
        startedAtMs: now - 10_000,
        keylineTintHex: '#FF9F0A',
        payloadBytes: 180,
      },
      {
        id: 'journey-1',
        kind: 'LIVE_JOURNEY',
        appName: 'Uber Eats',
        packageName: 'com.ubercab.eats',
        title: 'Courier Approaching',
        subtitle: 'Arriving in 4 min',
        badgeText: '4m',
        priorityOrder: 450,
        relevanceScore: 0.92,
        interruptBoost: 0,
        startedAtMs: now - 5_000,
        keylineTintHex: '#30D158',
        payloadBytes: 320,
      },
    ];

    const slot0 = arbitrateActivities(activities, now, 3, 0);
    assert.equal(slot0.primary?.id, 'timer-1');
    assert.equal(slot0.secondary?.id, 'media-1');
    assert.equal(slot0.tertiary?.id, 'journey-1');

    const slot1 = arbitrateActivities(activities, now, 3, 1);
    assert.equal(slot1.primary?.id, 'media-1');
    assert.equal(slot1.secondary?.id, 'journey-1');
    assert.equal(slot1.tertiary?.id, 'timer-1');
  });

  it('interpolates 1:1 upward scrub collapse and metaball ferrofluid bridge geometry', () => {
    const mid = interpolateScrubCollapse(-66);
    assert.equal(mid.progress, 0.5);
    assert.equal(mid.widthPx < 366 && mid.widthPx > 125, true);

    const connected = computeMetaballBridge(200, 210, 37, 22);
    assert.equal(connected.isConnected, true);
    assert.equal(connected.pinchHeightPx > 0, true);

    const detached = computeMetaballBridge(200, 230, 37, 22);
    assert.equal(detached.isConnected, false);
    assert.equal(detached.pinchHeightPx, 0);
  });

  it('wraps center region below when .belowIfTooWide is active and caps height at 160dp', () => {
    const layout = solveExpandedRegions({
      leadingRequestedWidthDp: 130,
      trailingRequestedWidthDp: 130,
      centerRequestedWidthDp: 140,
      bottomRequestedHeightDp: 120,
      wrapBehavior: 'BELOW_IF_TOO_WIDE',
    });
    assert.equal(layout.centerPlacedBelow, true);
    assert.equal(layout.totalHeightDp, 160);
    assert.equal(layout.cornerRadiusDp, 44);
    assert.equal(layout.minTouchTargetDp, 44);
  });
});
