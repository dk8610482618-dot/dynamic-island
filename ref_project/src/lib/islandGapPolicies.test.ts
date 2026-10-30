import { describe, it } from 'node:test';
import assert from 'node:assert/strict';

import {
  DEFAULT_AUTO_EXPAND_MS,
  DEFAULT_SUPPRESSION_COOLDOWN_MS,
  MAX_AUTO_EXPAND_MS,
  MAX_TRACKED_SUPPRESSIONS,
  MIN_API_FOR_COMMUNICATION_DEVICE,
  MIN_API_FOR_LIVE_CAPTION,
  MIN_AUTO_EXPAND_MS,
  MIN_CONTRAST_BODY,
  MIN_CONTRAST_LARGE,
  activeCallFeatures,
  backoffFor,
  batteryPresentation,
  binMagnitudes,
  buildRouteOptions,
  cellularLevel,
  clampAutoExpand,
  contrastRatio,
  evaluateWatchdog,
  expandedHeightDp,
  flatten,
  formatClock,
  isSuppressed,
  isWedged,
  mayShowCriticalAlert,
  meetsContrast,
  millisUntilNextClockTick,
  relativeLuminance,
  resolveAppSuppression,
  resolveAutoExpand,
  resolveCallFeatures,
  resolveRoutingCapability,
  resolveSurfaces,
  resolveSwipeDismissal,
  resolveWaveformSource,
  routeAccessibilityLabel,
  scaleForDynamicType,
  snapToGrid,
  suppressInstance,
  syntheticAmplitudes,
  syntheticPhase,
  visibleCallFeatures,
  wifiLevel,
  CALL_EXTRA_KEYS,
  type HealthSignal,
} from './islandGapPolicies.ts';

const NOW = 1_700_000_000_000;
const BLACK: [number, number, number] = [0, 0, 0];
const WHITE: [number, number, number] = [255, 255, 255];
const MID_GREY: [number, number, number] = [128, 128, 128];

describe('G1/G2 — transient alerting updates', () => {
  it('auto-expands a time sensitive update for the documented window', () => {
    const decision = resolveAutoExpand({ alertId: 'a', importance: 'TIME_SENSITIVE' }, NOW);
    assert.equal(decision.shouldAutoExpand, true);
    assert.equal(decision.expandDurationMs, DEFAULT_AUTO_EXPAND_MS);
    assert.equal(decision.reason, null);
  });

  it('clamps the requested window into the Apple range', () => {
    assert.equal(clampAutoExpand(100), MIN_AUTO_EXPAND_MS);
    assert.equal(clampAutoExpand(60_000), MAX_AUTO_EXPAND_MS);
    assert.equal(
      resolveAutoExpand({ alertId: 'a', importance: 'CRITICAL', autoExpandMs: 250 }, NOW).expandDurationMs,
      MIN_AUTO_EXPAND_MS,
    );
    assert.equal(
      resolveAutoExpand({ alertId: 'a', importance: 'CRITICAL', autoExpandMs: 9_000 }, NOW).expandDurationMs,
      MAX_AUTO_EXPAND_MS,
    );
  });

  it('lets a critical alert through quiet hours but not a time sensitive one', () => {
    const ctx = { quietHoursActive: true };
    assert.equal(resolveAutoExpand({ alertId: 'a', importance: 'TIME_SENSITIVE' }, NOW, ctx).reason, 'QUIET_HOURS');
    assert.equal(resolveAutoExpand({ alertId: 'a', importance: 'CRITICAL' }, NOW, ctx).shouldAutoExpand, true);
  });

  it('never interrupts for passive or default importance', () => {
    assert.equal(resolveAutoExpand({ alertId: 'a', importance: 'PASSIVE' }, NOW).reason, 'NOT_ALERTING');
    assert.equal(resolveAutoExpand({ alertId: 'a', importance: 'DEFAULT' }, NOW).reason, 'NOT_ALERTING');
  });

  it('treats an unchanged payload as a repeat and does not re-expand', () => {
    const decision = resolveAutoExpand(
      { alertId: 'a', importance: 'TIME_SENSITIVE', isRepeatOfPreviousPayload: true },
      NOW,
    );
    assert.equal(decision.shouldAutoExpand, false);
    assert.equal(decision.reason, 'REPEATED_PAYLOAD');
  });

  it('does not expand on a locked device', () => {
    assert.equal(resolveAutoExpand({ alertId: 'a', importance: 'CRITICAL' }, NOW, { isLocked: true }).reason, 'LOCKED');
  });

  it('never yanks the island out from under an active long press', () => {
    const decision = resolveAutoExpand(
      { alertId: 'a', importance: 'CRITICAL' },
      NOW,
      { userIsHoldingExpanded: true },
    );
    assert.equal(decision.shouldAutoExpand, false);
    assert.equal(decision.reason, 'USER_ALREADY_EXPANDED');
  });

  it('cross-fades under reduce motion but still expands', () => {
    const decision = resolveAutoExpand({ alertId: 'a', importance: 'TIME_SENSITIVE' }, NOW, { reduceMotion: true });
    assert.equal(decision.shouldAutoExpand, true);
    assert.equal(decision.useCrossfadeInsteadOfSpring, true);
  });

  it('swiping an alert away keeps the activity that produced it', () => {
    const outcome = resolveSwipeDismissal(
      {},
      { alertId: 'nav-turn-7', suppressionKey: 'navigation:turn' },
      ['journey-42'],
      'journey-42',
      NOW,
    );
    assert.equal(outcome.alertIdToRemove, 'nav-turn-7');
    assert.deepEqual(outcome.activityIdsToRetain, ['journey-42']);
    assert.equal(outcome.nextPresentation, 'RESTORE_UNDERLYING');
    assert.equal(isSuppressed(outcome.suppressionLedger, 'navigation:turn', NOW), true);
  });

  it('returns to idle when nothing ongoing was underneath', () => {
    const outcome = resolveSwipeDismissal({}, { alertId: 'charging' }, [], null, NOW);
    assert.equal(outcome.nextPresentation, 'IDLE');
    assert.deepEqual(outcome.activityIdsToRetain, []);
  });

  it('blocks repeats only for the cooldown, and only for that key', () => {
    const ledger = suppressInstance({}, 'navigation:turn', NOW);
    assert.equal(
      resolveAutoExpand({ alertId: 'x', importance: 'TIME_SENSITIVE', suppressionKey: 'navigation:turn' }, NOW + 10_000, {
        suppressionLedger: ledger,
      }).reason,
      'USER_SUPPRESSED',
    );
    assert.equal(
      resolveAutoExpand(
        { alertId: 'x', importance: 'TIME_SENSITIVE', suppressionKey: 'navigation:turn' },
        NOW + DEFAULT_SUPPRESSION_COOLDOWN_MS + 1,
        { suppressionLedger: ledger },
      ).shouldAutoExpand,
      true,
    );
    // A different key is untouched.
    assert.equal(
      resolveAutoExpand({ alertId: 'y', importance: 'TIME_SENSITIVE', suppressionKey: 'timer:complete' }, NOW, {
        suppressionLedger: ledger,
      }).shouldAutoExpand,
      true,
    );
  });

  it('bounds the suppression ledger so a long-lived service cannot leak', () => {
    let ledger: Record<string, number> = {};
    for (let i = 0; i < 200; i++) ledger = suppressInstance(ledger, `key-${i}`, NOW + i);
    assert.equal(Object.keys(ledger).length, MAX_TRACKED_SUPPRESSIONS);
    assert.equal('key-199' in ledger, true);
    assert.equal('key-0' in ledger, false);
  });

  it('prunes expired entries on the next write', () => {
    const ledger = suppressInstance({ old: NOW - 1 }, 'new', NOW);
    assert.equal('old' in ledger, false);
    assert.equal('new' in ledger, true);
  });
});

describe('G3 — contextual app suppression', () => {
  const game = { packageName: 'com.example.game', isImmersiveFullscreen: true };
  const player = { packageName: 'com.example.player', isPlayingVideo: true };
  const chat = { packageName: 'com.example.chat' };

  it('suppresses the overlay inside a full screen app once the dead setting is wired', () => {
    const d = resolveAppSuppression(game, {}, true);
    assert.equal(d.reason, 'GLOBAL_FULLSCREEN_IMMERSIVE');
    assert.equal(d.hideNonCritical, true);
  });

  it('has a separate signal for video playback', () => {
    assert.equal(resolveAppSuppression(player, {}, true).reason, 'GLOBAL_FULLSCREEN_VIDEO');
  });

  it('leaves ordinary foreground apps alone', () => {
    const d = resolveAppSuppression(chat, {}, true);
    assert.deepEqual(d, { hideNothing: false, hideNonCritical: false, reason: null });
  });

  it('does nothing when the switch is off', () => {
    assert.equal(resolveAppSuppression(game, {}, false).reason, null);
  });

  it('lets a critical alert through full screen auto-hide but not through HIDE_ALWAYS', () => {
    assert.equal(resolveAppSuppression(game, {}, true, true).reason, null);

    const always = resolveAppSuppression(game, { 'com.example.game': 'HIDE_ALWAYS' }, false, true);
    assert.equal(always.hideNothing, true);
    assert.equal(mayShowCriticalAlert(always), false);
  });

  it('HIDE_NON_CRITICAL keeps calls but drops routine content', () => {
    const rules = { 'com.example.game': 'HIDE_NON_CRITICAL' as const };
    const routine = resolveAppSuppression(game, rules, false);
    assert.equal(routine.hideNonCritical, true);
    assert.equal(routine.hideNothing, false);
    assert.equal(mayShowCriticalAlert(routine), true);
    assert.equal(resolveAppSuppression(game, rules, false, true).reason, null);
  });

  it('never suppresses when the island is off or the screen is dark', () => {
    assert.equal(resolveAppSuppression(game, {}, true, false, false).reason, null);
    assert.equal(resolveAppSuppression({ ...game, isScreenOn: false }, {}, true).reason, null);
    assert.equal(resolveAppSuppression(null, {}, true).reason, null);
  });
});

describe('G4 — mini status bar', () => {
  it('never returns a zero or negative clock delay (no polling loop)', () => {
    for (let offset = 0; offset <= 120_000; offset += 7) {
      const delay = millisUntilNextClockTick(offset);
      assert.ok(delay >= 1 && delay <= 60_000, `delay ${delay} at ${offset}`);
    }
  });

  it('lands exactly on the minute boundary', () => {
    assert.equal(millisUntilNextClockTick(30_000), 30_000);
    assert.equal(millisUntilNextClockTick(59_999), 1);
    assert.equal(millisUntilNextClockTick(60_000), 60_000);
    assert.equal(millisUntilNextClockTick(0), 60_000);
  });

  it('advances the displayed minute after waiting the returned delay', () => {
    const start = 1_700_000_030_123;
    const before = formatClock(start);
    const after = formatClock(start + millisUntilNextClockTick(start));
    assert.notEqual(before, after);
  });

  it('formats half hour and forty five minute timezones', () => {
    assert.equal(formatClock(0, 0), '00:00');
    assert.equal(formatClock(0, 330), '05:30'); // IST
    assert.equal(formatClock(0, 345), '05:45'); // Nepal
    assert.equal(formatClock(0, -300), '19:00'); // US Eastern
  });

  it('uses twelve for midnight and noon in 12 hour format', () => {
    assert.equal(formatClock(0, 0, false), '12:00 AM');
    assert.equal(formatClock(12 * 60 * 60_000, 0, false), '12:00 PM');
    assert.equal(formatClock((13 * 60 + 5) * 60_000, 0, false), '1:05 PM');
  });

  it('maps wifi and cellular signal to level thresholds', () => {
    assert.equal(wifiLevel(-40), 'EXCELLENT');
    assert.equal(wifiLevel(-60), 'GOOD');
    assert.equal(wifiLevel(-75), 'MODERATE');
    assert.equal(wifiLevel(-95), 'POOR');
    assert.equal(wifiLevel(-120), 'NONE');
    assert.equal(cellularLevel(-80), 'EXCELLENT');
    assert.equal(cellularLevel(-95), 'GOOD');
    assert.equal(cellularLevel(-140), 'NONE');
  });

  it('tints the battery by charge state and level and hides text while charging', () => {
    assert.equal(batteryPresentation(80, true, false, true).tint, '#30D158');
    assert.equal(batteryPresentation(80, true, false, true).text, null);
    assert.equal(batteryPresentation(80, false, true, true).tint, '#FFD60A');
    assert.equal(batteryPresentation(8, false, false, true).tint, '#FF453A');
    assert.equal(batteryPresentation(18, false, false, true).tint, '#FF9F0A');
    assert.equal(batteryPresentation(60, false, false, true).tint, '#FFFFFF');
    assert.equal(batteryPresentation(60, false, false, true).text, '60');
  });

  it('clamps out of range battery percentages', () => {
    assert.equal(batteryPresentation(150, false, false, true).percent, 100);
    assert.equal(batteryPresentation(-20, false, false, true).percent, 0);
    assert.equal(batteryPresentation(150, false, false, true).fillFraction, 1);
  });
});

describe('G5 — liquid glass, contrast and the grid', () => {
  it('demotes a control inside a glass card so glass never stacks', () => {
    const resolved = resolveSurfaces(['CARD', 'CONTROL'], true);
    assert.equal(resolved[0].treatment, 'GLASS');
    assert.equal(resolved[1].treatment, 'SOLID_TINT');
    assert.equal(resolved[1].demotedToAvoidStacking, true);
    assert.equal(resolved[1].blurRadiusDp, 0);
    assert.equal(
      resolved.filter((s) => s.treatment === 'GLASS').length,
      1,
    );
  });

  it('demotes the card too when an ancestor already blurs', () => {
    const resolved = resolveSurfaces(['CARD', 'CONTROL'], true, { parentIsGlass: true });
    assert.equal(
      resolved.filter((s) => s.treatment === 'GLASS').length,
      0,
    );
    assert.ok(resolved.every((s) => s.demotedToAvoidStacking));
  });

  it('drops glass under reduce transparency, low power, or a different preset', () => {
    for (const opts of [
      { reduceTransparency: true },
      { lowPowerMode: true },
    ]) {
      assert.equal(resolveSurfaces(['CARD'], true, opts)[0].treatment, 'NONE');
    }
    assert.equal(resolveSurfaces(['CARD', 'CONTROL'], false)[0].treatment, 'NONE');
  });

  it('keeps a demoted control lighter than its card so it still reads as raised', () => {
    const resolved = resolveSurfaces(['CARD', 'CONTROL'], true);
    assert.ok(resolved[0].tintAlpha > resolved[1].tintAlpha);
  });

  it('computes WCAG contrast from 1 to 21', () => {
    assert.ok(Math.abs(contrastRatio(WHITE, BLACK) - 21) < 0.01);
    assert.ok(Math.abs(contrastRatio(BLACK, WHITE) - 21) < 0.01);
    assert.ok(Math.abs(contrastRatio(BLACK, BLACK) - 1) < 0.01);
  });

  it('linearises mid grey instead of treating it as 0.5', () => {
    assert.ok(Math.abs(relativeLuminance(BLACK)) < 1e-9);
    assert.ok(Math.abs(relativeLuminance(WHITE) - 1) < 1e-9);
    assert.ok(Math.abs(relativeLuminance(MID_GREY) - 0.2159) < 0.001);
  });

  it('applies the large text exception at 3 to 1', () => {
    const ratio = contrastRatio(MID_GREY, WHITE);
    assert.ok(ratio < MIN_CONTRAST_BODY && ratio >= MIN_CONTRAST_LARGE);
    assert.equal(meetsContrast(MID_GREY, WHITE, 12), false);
    assert.equal(meetsContrast(MID_GREY, WHITE, 20), true);
    assert.equal(meetsContrast(MID_GREY, WHITE, 14, true), true);
    assert.equal(meetsContrast(MID_GREY, WHITE, 13, true), false);
  });

  it('keeps zero reachable when snapping to the grid', () => {
    assert.equal(snapToGrid(0), 0);
    assert.equal(snapToGrid(3.9), 0);
    assert.equal(snapToGrid(4.1), 8);
    assert.equal(snapToGrid(13), 16);
    assert.equal(snapToGrid(13, 0), 13);
  });

  it('bounds dynamic type and the 160dp expanded cap', () => {
    assert.equal(scaleForDynamicType(14, 2), 28);
    assert.equal(scaleForDynamicType(14, 3), 34);
    assert.equal(scaleForDynamicType(14, 50), 34);
    assert.equal(expandedHeightDp(120, 1.3), 156);
    assert.equal(expandedHeightDp(120, 2), 160);
    assert.equal(expandedHeightDp(120, 0.8), 120);
  });
});

describe('G6 — foreground service watchdog', () => {
  const healthy: HealthSignal = {
    isServiceRunning: true,
    isOverlayAttached: true,
    isListenerConnected: true,
    millisSinceLastHeartbeat: 1_000,
    hasBatteryExemption: true,
  };

  it('reports healthy with a coarse interval and no action', () => {
    const d = evaluateWatchdog(healthy);
    assert.equal(d.verdict, 'HEALTHY');
    assert.equal(d.consecutiveFailures, 0);
    assert.equal(d.delayMs, 60_000);
    assert.equal(d.requiresUserAction, false);
  });

  it('backs off from 15s and caps at an hour', () => {
    assert.equal(backoffFor(0), 0);
    assert.equal(backoffFor(1), 15_000);
    assert.equal(backoffFor(2), 30_000);
    assert.equal(backoffFor(3), 60_000);
    assert.equal(backoffFor(40), 60 * 60_000);
    assert.equal(backoffFor(-5), 0);
  });

  it('restarts a dead service with growing backoff', () => {
    const first = evaluateWatchdog({ ...healthy, isServiceRunning: false });
    assert.equal(first.verdict, 'RESTART_SERVICE');
    assert.equal(first.delayMs, 15_000);
    const second = evaluateWatchdog({ ...healthy, isServiceRunning: false }, first.consecutiveFailures);
    assert.equal(second.delayMs, 30_000);
  });

  it('detects a wedged service from missed heartbeats, not one slow frame', () => {
    assert.equal(isWedged(60_000), false);
    assert.equal(isWedged(120_000), false);
    assert.equal(isWedged(120_001), true);
    assert.equal(evaluateWatchdog({ ...healthy, millisSinceLastHeartbeat: 300_000 }).verdict, 'RESTART_SERVICE');
  });

  it('resets the failure counter on recovery', () => {
    assert.equal(evaluateWatchdog(healthy, 4).consecutiveFailures, 0);
  });

  it('reattaches a detached overlay instead of restarting everything', () => {
    assert.equal(evaluateWatchdog({ ...healthy, isOverlayAttached: false }).verdict, 'REATTACH_OVERLAY');
  });

  it('asks the user about revoked notification access rather than retrying', () => {
    const d = evaluateWatchdog({ ...healthy, isListenerConnected: false });
    assert.equal(d.verdict, 'LISTENER_DISCONNECTED');
    assert.equal(d.requiresUserAction, true);
  });

  it('never restarts a user-disabled island and defers during doze', () => {
    assert.equal(evaluateWatchdog({ ...healthy, isUserEnabled: false, isServiceRunning: false }).verdict, 'DISABLED');
    assert.equal(evaluateWatchdog({ ...healthy, isDeviceIdle: true, isServiceRunning: false }).verdict, 'BLOCKED');
  });

  it('stops retrying once the exemption has been refused twice', () => {
    const d = evaluateWatchdog({
      ...healthy,
      isServiceRunning: false,
      hasBatteryExemption: false,
      exemptionDeclineCount: 2,
    });
    assert.equal(d.verdict, 'BLOCKED');
    assert.equal(d.requiresUserAction, true);
  });
});

describe('G7 — waveform source honesty', () => {
  const full = {
    isVisible: true,
    isPlaying: true,
    waveformEnabledInSettings: true,
    hasRecordAudioPermission: true,
    deviceSupportsVisualizer: true,
    visualizerSessionAttached: true,
    apiLevel: 34,
  };

  it('uses real FFT only when every precondition holds', () => {
    const c = resolveWaveformSource(full);
    assert.equal(c.source, 'REAL_FFT');
    assert.equal(c.mayBeDescribedAsRealSpectrum, true);
  });

  it('falls back to synthetic and refuses the real-spectrum label', () => {
    const c = resolveWaveformSource({ ...full, hasRecordAudioPermission: false });
    assert.equal(c.source, 'SYNTHETIC');
    assert.equal(c.reason, 'NO_RECORD_AUDIO_PERMISSION');
    assert.equal(c.mayBeDescribedAsRealSpectrum, false);
  });

  it('reports the most fundamental blocker first', () => {
    const c = resolveWaveformSource({
      ...full,
      apiLevel: 14,
      hasRecordAudioPermission: false,
      deviceSupportsVisualizer: false,
    });
    assert.equal(c.reason, 'API_LEVEL_TOO_LOW');
    assert.equal(resolveWaveformSource({ ...full, deviceSupportsVisualizer: false }).reason, 'VISUALIZER_UNSUPPORTED_BY_DEVICE');
    assert.equal(resolveWaveformSource({ ...full, visualizerSessionAttached: false }).reason, 'SESSION_ZERO_RESTRICTED');
  });

  it('does no DSP work when hidden, paused, or under reduce motion', () => {
    assert.equal(resolveWaveformSource({ ...full, isVisible: false }).reason, 'NOT_VISIBLE');
    assert.equal(resolveWaveformSource({ ...full, isPlaying: false }).reason, 'NOT_PLAYING');
    assert.equal(resolveWaveformSource({ ...full, reduceMotion: true }).reason, 'REDUCED_MOTION');
    assert.equal(resolveWaveformSource({ ...full, waveformEnabledInSettings: false }).source, 'FLAT');
  });

  it('bins logarithmically into exactly the requested bar count', () => {
    const magnitudes = Array.from({ length: 64 }, (_, i) => (i % 7) / 10);
    for (const bars of [1, 5, 16, 40]) {
      const binned = binMagnitudes(magnitudes, bars);
      assert.equal(binned.length, bars);
      assert.ok(binned.every((v) => v >= 0 && v <= 1));
    }
  });

  it('degrades safely on degenerate input instead of throwing', () => {
    assert.equal(binMagnitudes([0.1, 0.2, 0.3], 5).length, 5);
    assert.deepEqual(binMagnitudes([0.9], 5), [0, 0, 0, 0, 0]);
    assert.deepEqual(binMagnitudes([], 5), [0, 0, 0, 0, 0]);
    assert.equal(binMagnitudes([0.1, 0.2], 0).length, 0);
  });

  it('keeps synthetic bars deterministic and frozen when paused', () => {
    const a = syntheticAmplitudes(5, 1.234);
    const b = syntheticAmplitudes(5, 1.234);
    assert.deepEqual(a, b);
    assert.ok(a.every((v) => v >= 0 && v <= 1));
    assert.deepEqual(syntheticAmplitudes(5, 2, 0.7, false), [0, 0, 0, 0, 0]);
    assert.equal(syntheticPhase(0), 0);
    assert.ok(syntheticPhase(900) > 0);
    assert.ok(syntheticPhase(900, 2) > syntheticPhase(900, 1));
    assert.equal(syntheticAmplitudes(0, 1).length, 0);
  });

  it('flattens to exactly zero at full progress and clamps overshoot', () => {
    const bars = [0.4, 0.8, 1];
    assert.deepEqual(flatten(bars, 0), bars);
    assert.deepEqual(flatten(bars, 1), [0, 0, 0]);
    assert.deepEqual(flatten(bars, 4), [0, 0, 0]);
    assert.ok(Math.abs(flatten(bars, 0.5)[0] - 0.2) < 1e-9);
  });
});

describe('G8 — call session features', () => {
  const activeCall = { isCallActive: true, isMuted: true, isControllable: true, apiLevel: 34 };
  const find = (states: ReturnType<typeof resolveCallFeatures>, f: string) => states.find((s) => s.feature === f)!;

  it('reads mute, speaker and hold from real device state', () => {
    const states = resolveCallFeatures(activeCall);
    const mute = find(states, 'MICROPHONE_MUTE');
    assert.equal(mute.origin, 'DEVICE_STATE');
    assert.equal(mute.isActive, true);
    assert.equal(mute.mayRenderAsActive, true);
    assert.equal(mute.isActionable, true);
  });

  it('is not actionable without the dialer role', () => {
    const states = resolveCallFeatures({ ...activeCall, isControllable: false });
    assert.equal(find(states, 'MICROPHONE_MUTE').isActionable, false);
    assert.equal(find(states, 'HOLD').isActionable, false);
  });

  it('makes everything unavailable with no active call', () => {
    const states = resolveCallFeatures({ ...activeCall, isCallActive: false });
    assert.equal(states.length, 8);
    assert.ok(states.every((s) => s.origin === 'UNAVAILABLE' && s.unavailableReason === 'NO_ACTIVE_CALL'));
    assert.equal(activeCallFeatures(states).length, 0);
  });

  it('never invents SharePlay or Live Translation', () => {
    const states = resolveCallFeatures(activeCall);
    const shared = find(states, 'SHAREPLAY_SESSION');
    assert.equal(shared.origin, 'UNAVAILABLE');
    assert.equal(shared.mayRenderAsActive, false);
    assert.equal(find(states, 'LIVE_TRANSLATION').unavailableReason, 'NO_PUBLIC_API');

    const advertised = resolveCallFeatures({
      ...activeCall,
      advertisedExtraKeys: [CALL_EXTRA_KEYS.SHAREPLAY_SESSION_ACTIVE, CALL_EXTRA_KEYS.LIVE_TRANSLATION_ACTIVE],
    }, 'Watching with 2 people');
    assert.equal(find(advertised, 'SHAREPLAY_SESSION').origin, 'SOURCE_ADVERTISED');
    assert.equal(find(advertised, 'SHAREPLAY_SESSION').mayRenderAsActive, true);
    assert.equal(find(advertised, 'SHAREPLAY_SESSION').sourceLabel, 'Watching with 2 people');
    assert.equal(find(advertised, 'LIVE_TRANSLATION').mayRenderAsActive, true);
  });

  it('separates a running caption session from a system caption preference', () => {
    const advertised = find(
      resolveCallFeatures({ ...activeCall, advertisedExtraKeys: [CALL_EXTRA_KEYS.LIVE_CAPTIONS_ACTIVE] }),
      'LIVE_CAPTIONS',
    );
    assert.equal(advertised.origin, 'SOURCE_ADVERTISED');
    assert.equal(advertised.isActive, true);

    const preference = find(resolveCallFeatures({ ...activeCall, systemCaptioningEnabled: true }), 'LIVE_CAPTIONS');
    assert.equal(preference.origin, 'DEVICE_STATE');
    assert.equal(preference.isActive, false, 'a preference is not a running session');
    assert.equal(preference.sourceLabel, 'System captions on');

    assert.equal(
      find(resolveCallFeatures({ ...activeCall, apiLevel: 29, systemCaptioningEnabled: true }), 'LIVE_CAPTIONS')
        .unavailableReason,
      'API_LEVEL_TOO_LOW',
    );
    assert.ok(MIN_API_FOR_LIVE_CAPTION === 30);
  });

  it('filters unverified rows out of the visible set', () => {
    const states = resolveCallFeatures(activeCall);
    const visible = visibleCallFeatures(states);
    assert.equal(visible.length, 3);
    assert.ok(visible.every((s) => s.origin !== 'UNAVAILABLE'));
    assert.equal(
      visibleCallFeatures(
        resolveCallFeatures({ ...activeCall, advertisedExtraKeys: [CALL_EXTRA_KEYS.SCREEN_SHARE_ACTIVE] }),
      ).length,
      4,
    );
  });
});

describe('G9 — audio output routing', () => {
  it('routes call audio on API 31+ when the call is controllable', () => {
    const c = resolveRoutingCapability('CALL', MIN_API_FOR_COMMUNICATION_DEVICE, true);
    assert.equal(c.canChangeRoute, true);
    assert.equal(c.fallback, 'NONE');
  });

  it('falls back to system settings below API 31 or without control', () => {
    assert.equal(resolveRoutingCapability('CALL', 30, true).canChangeRoute, false);
    assert.equal(resolveRoutingCapability('CALL', 30, true).fallback, 'SYSTEM_SETTINGS');
    assert.equal(resolveRoutingCapability('CALL', 34, false).canChangeRoute, false);
  });

  it('never claims to reroute another app’s media', () => {
    const withSheet = resolveRoutingCapability('MEDIA', 34, false, true);
    assert.equal(withSheet.canChangeRoute, false);
    assert.equal(withSheet.fallback, 'SOURCE_APP_SHEET');
    assert.ok(withSheet.limitation!.includes('does not let one app change another app'));
    assert.equal(resolveRoutingCapability('MEDIA', 34).fallback, 'SYSTEM_SETTINGS');
  });

  it('orders rows like the system sheet and never offers a dead toggle', () => {
    const callCap = resolveRoutingCapability('CALL', 34, true);
    const options = buildRouteOptions(
      ['CAST', 'BLUETOOTH_A2DP', 'BUILTIN_SPEAKER', 'WIRED_HEADPHONES'],
      'BLUETOOTH_A2DP',
      callCap,
    );
    assert.deepEqual(
      options.map((o) => o.kind),
      ['BUILTIN_SPEAKER', 'WIRED_HEADPHONES', 'BLUETOOTH_A2DP', 'CAST'],
    );
    const selected = options.find((o) => o.isSelected)!;
    assert.equal(selected.isSelectable, false, 're-selecting the current output is not an action');
    assert.ok(options.filter((o) => !o.isSelected).every((o) => o.isSelectable));

    const mediaOptions = buildRouteOptions(
      ['BUILTIN_SPEAKER', 'BLUETOOTH_A2DP'],
      'BUILTIN_SPEAKER',
      resolveRoutingCapability('MEDIA', 34, false, true),
    );
    assert.equal(mediaOptions.length, 2);
    assert.ok(mediaOptions.every((o) => !o.isSelectable));
  });

  it('states the limitation in the accessibility label', () => {
    const routable = { kind: 'BLUETOOTH_A2DP' as const, label: 'Bluetooth', isAvailable: true, isSelected: false, isSelectable: true };
    assert.equal(routeAccessibilityLabel(routable), 'Switch output to Bluetooth');
    assert.equal(routeAccessibilityLabel({ ...routable, isSelected: true, isSelectable: false }), 'Bluetooth, current output');
    assert.ok(routeAccessibilityLabel({ ...routable, isSelectable: false }).includes('available'));
  });
});
