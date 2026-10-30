import React, { useMemo, useState } from 'react';
import {
  Activity,
  ArrowUpRight,
  BookOpen,
  Check,
  ChevronRight,
  Clock,
  Code2,
  Compass,
  Download,
  ExternalLink,
  Layers,
  Lock,
  Music,
  Navigation,
  Package,
  Play,
  Pause,
  RotateCcw,
  Search,
  ShieldCheck,
  Smartphone,
  Sliders,
  Truck,
  Unlock,
} from 'lucide-react';
import {
  ACTIVITY_KIT_LIMITS,
  arbitrateActivities,
  computeMetaballBridge,
  estimatePayloadBytes,
  evaluateActivityLifecycle,
  interpolateScrubCollapse,
  resolveGestureAction,
  solveExpandedRegions,
  type ActivityKind,
  type InteractionMode,
  type IslandActivityModel,
} from './lib/islandParityEngine';
import {
  INITIAL_ACTIVITIES,
  KOTLIN_MODULES,
  RESEARCH_PAPERS,
  USED_SKILLS_CATALOG,
} from './data/repoCatalog';

type WorkbenchTab = 'simulator' | 'architecture' | 'papers' | 'skills';

export default function App() {
  const [activeTab, setActiveTab] = useState<WorkbenchTab>('simulator');

  // Simulator state
  const [interactionMode, setInteractionMode] = useState<InteractionMode>('APPLE_PARITY');
  const [activeIds, setActiveIds] = useState<string[]>([
    'activity_media_session',
    'activity_live_journey',
    'activity_navigation',
  ]);
  const [maxConcurrent, setMaxConcurrent] = useState<2 | 3>(3);
  const [focusOffset, setFocusOffset] = useState<number>(0);
  const [isExpanded, setIsExpanded] = useState<boolean>(false);
  const [isPressed, setIsPressed] = useState<boolean>(false);
  const [enableKeylineTint, setEnableKeylineTint] = useState<boolean>(true);
  const [enableMetaballBridge, setEnableMetaballBridge] = useState<boolean>(true);
  const [isLandscapeLimited, setIsLandscapeLimited] = useState<boolean>(false);
  const [isKeyguardLocked, setIsKeyguardLocked] = useState<boolean>(false);
  const [simulateStale, setSimulateStale] = useState<boolean>(false);
  const [scrubDeltaYPx, setScrubDeltaYPx] = useState<number>(0);
  const [dropletGapPx, setDropletGapPx] = useState<number>(10);
  const [lastHostAppAction, setLastHostAppAction] = useState<string>(
    'Ready · Tap or Long-Press the Dynamic Island capsule to test gesture disambiguation'
  );
  const [payloadTestText, setPayloadTestText] = useState<string>(
    'Dasher is approaching with your order · Arriving in 4 min'
  );

  // Filters for Architecture & Skills tabs
  const [selectedPhase, setSelectedPhase] = useState<string>('ALL');
  const [selectedPaperId, setSelectedPaperId] = useState<string>(RESEARCH_PAPERS[0].id);
  const [skillCategory, setSkillCategory] = useState<string>('ALL');
  const [skillQuery, setSkillQuery] = useState<string>('');

  const simulatedNowMs = 1_700_000_000_000;

  const activeCandidates = useMemo(() => {
    return INITIAL_ACTIVITIES.filter((a) => activeIds.includes(a.id)).map((a) => ({
      ...a,
      staleAtMs: simulateStale ? simulatedNowMs - 5_000 : simulatedNowMs + 600_000,
    }));
  }, [activeIds, simulateStale]);

  const allocation = useMemo(() => {
    return arbitrateActivities(activeCandidates, simulatedNowMs, maxConcurrent, focusOffset);
  }, [activeCandidates, maxConcurrent, focusOffset]);

  const primary = allocation.primary;
  const secondary = allocation.secondary;
  const tertiary = allocation.tertiary;

  const scrubState = useMemo(() => {
    return interpolateScrubCollapse(scrubDeltaYPx);
  }, [scrubDeltaYPx]);

  const metaballBridge = useMemo(() => {
    return computeMetaballBridge(200, 200 + dropletGapPx, 37, 22);
  }, [dropletGapPx]);

  const regionAllocation = useMemo(() => {
    return solveExpandedRegions({
      leadingRequestedWidthDp: 110,
      trailingRequestedWidthDp: 96,
      centerRequestedWidthDp: primary?.kind === 'NAVIGATION' ? 148 : 112,
      bottomRequestedHeightDp: 56,
      wrapBehavior: 'BELOW_IF_TOO_WIDE',
    });
  }, [primary]);

  const payloadBytesCount = useMemo(() => {
    return estimatePayloadBytes('Live Activity Payload', payloadTestText, '4m');
  }, [payloadTestText]);

  const toggleActivity = (id: string) => {
    setActiveIds((prev) =>
      prev.includes(id) ? prev.filter((item) => item !== id) : [...prev, id]
    );
  };

  const handlePrimarySingleTap = () => {
    if (!primary) {
      setLastHostAppAction('Idle capsule tapped · Opening Notification Inbox');
      return;
    }
    const resolved = resolveGestureAction(interactionMode, 'SINGLE_TAP');
    if (resolved === 'OPEN_HOST_APP_DEEP_LINK') {
      setIsExpanded(false);
      setLastHostAppAction(
        `Single Tap (<200ms) in APPLE_PARITY mode -> Launched ${primary.appName} (${primary.packageName}) via deepLinkIntent`
      );
    } else {
      setIsExpanded((prev) => !prev);
      setScrubDeltaYPx(0);
      setLastHostAppAction(
        `Single Tap in ANDROID_CLASSIC mode -> Toggled Expanded 4-region card for ${primary.appName}`
      );
    }
  };

  const handlePrimaryLongPress = () => {
    if (!primary) return;
    const resolved = resolveGestureAction(interactionMode, 'LONG_PRESS');
    if (resolved === 'EXPAND_ISLAND_CARD') {
      setIsExpanded(true);
      setScrubDeltaYPx(0);
      setLastHostAppAction(
        `Long Press (~450ms, 0.985x compression) in APPLE_PARITY mode -> Expanded 4-region card for ${primary.appName}`
      );
    } else {
      setLastHostAppAction(
        `Long Press in ANDROID_CLASSIC mode -> Launched ${primary.appName} (${primary.packageName})`
      );
    }
  };

  const handleCycleFocus = () => {
    setFocusOffset((prev) => prev + 1);
    setLastHostAppAction(
      'Horizontal swipe on Minimal capsule -> Emitted IslandUserCommand.CycleActivityFocus(1)'
    );
  };

  const renderActivityGlyph = (kind?: ActivityKind, color = '#FFFFFF') => {
    switch (kind) {
      case 'MEDIA_SESSION':
        return <Music className="w-4 h-4" style={{ color }} />;
      case 'LIVE_JOURNEY':
        return <Truck className="w-4 h-4" style={{ color }} />;
      case 'NAVIGATION':
        return <Navigation className="w-4 h-4" style={{ color }} />;
      case 'LIVE_TIMER':
        return <Clock className="w-4 h-4" style={{ color }} />;
      case 'FILE_DOWNLOAD':
        return <Download className="w-4 h-4" style={{ color }} />;
      default:
        return <Activity className="w-4 h-4" style={{ color }} />;
    }
  };

  const filteredModules = useMemo(() => {
    if (selectedPhase === 'ALL') return KOTLIN_MODULES;
    return KOTLIN_MODULES.filter((m) => m.phase === selectedPhase);
  }, [selectedPhase]);

  const filteredSkills = useMemo(() => {
    return USED_SKILLS_CATALOG.filter((skill) => {
      const matchesCat = skillCategory === 'ALL' || skill.category === skillCategory;
      const q = skillQuery.trim().toLowerCase();
      const matchesQuery =
        !q ||
        skill.repoSlug.toLowerCase().includes(q) ||
        skill.folderName.toLowerCase().includes(q) ||
        skill.appliedInProject.toLowerCase().includes(q);
      return matchesCat && matchesQuery;
    });
  }, [skillCategory, skillQuery]);

  const activePaper = useMemo(() => {
    return RESEARCH_PAPERS.find((p) => p.id === selectedPaperId) ?? RESEARCH_PAPERS[0];
  }, [selectedPaperId]);

  const keylineBorderColor =
    enableKeylineTint && primary ? `${primary.keylineTintHex}88` : 'rgba(255,255,255,0.16)';

  return (
    <div className="min-h-screen bg-[#090A0D] text-[#F5F7FA] font-sans selection:bg-[#0A84FF]/30">
      {/* Top Header Bar */}
      <header className="border-b border-white/10 bg-[#090A0D]/90 backdrop-blur-md sticky top-0 z-30">
        <div className="max-w-[1360px] mx-auto px-6 h-16 flex items-center justify-between gap-6">
          <div className="flex items-center gap-4">
            <div className="w-9 h-9 rounded-lg bg-white/[0.06] border border-white/15 flex items-center justify-center">
              <Smartphone className="w-4 h-4 text-[#0A84FF]" />
            </div>
            <div>
              <h1 className="text-base font-semibold tracking-tight text-white">
                Dynamic Island — Apple Parity Engine &amp; Architecture Workbench
              </h1>
              <p className="text-xs text-zinc-400">
                Darshan8610/Dynamic-Island · Kotlin 2.2 · Android SDK 36 · 210 Unit Tests Passing · 25 Agent Skills
              </p>
            </div>
          </div>

          {/* Primary Navigation (Clean Segmented Functional Controls) */}
          <nav className="flex items-center gap-1 p-1 bg-white/[0.04] border border-white/10 rounded-lg" aria-label="Main Navigation">
            {(
              [
                { id: 'simulator', label: 'Interactive Simulator', icon: Sliders },
                { id: 'architecture', label: 'Kotlin Modules (Phases 1–4)', icon: Code2 },
                { id: 'papers', label: 'Research Papers (2)', icon: BookOpen },
                { id: 'skills', label: 'Used Skills (25)', icon: Layers },
              ] as const
            ).map((tab) => {
              const Icon = tab.icon;
              const isSelected = activeTab === tab.id;
              return (
                <button
                  key={tab.id}
                  type="button"
                  onClick={() => setActiveTab(tab.id)}
                  className={`min-h-[38px] px-3.5 py-1.5 rounded-md text-xs font-medium flex items-center gap-2 whitespace-nowrap shrink-0 transition-colors ${
                    isSelected
                      ? 'bg-[#0A84FF] text-white'
                      : 'text-zinc-300 hover:text-white hover:bg-white/[0.05]'
                  }`}
                >
                  <Icon className="w-3.5 h-3.5" />
                  <span>{tab.label}</span>
                </button>
              );
            })}
          </nav>
        </div>
      </header>

      {/* Main Content Container */}
      <main className="max-w-[1360px] mx-auto px-6 py-8">
        {activeTab === 'simulator' && (
          <div className="grid grid-cols-1 lg:grid-cols-12 gap-8 items-start">
            {/* Left Column: Device Bezel & Live Dynamic Island Viewport (7 cols) */}
            <section className="lg:col-span-7 bg-[#101218] border border-white/10 rounded-2xl p-6">
              <div className="flex flex-wrap items-center justify-between gap-4 pb-5 border-b border-white/10">
                <div>
                  <h2 className="text-lg font-semibold text-white">
                    Live Hardware Cutout &amp; Jindo Physics Viewport
                  </h2>
                  <p className="text-xs text-zinc-400 mt-0.5">
                    Mode: {interactionMode} · Concurrent Cap: {maxConcurrent} Activities ·{' '}
                    {isLandscapeLimited ? 'Landscape Width-Limited (28dp)' : 'Portrait Standard'}
                  </p>
                </div>

                <div className="flex items-center gap-2">
                  <button
                    type="button"
                    onClick={handlePrimarySingleTap}
                    className="min-h-[40px] px-3.5 py-2 rounded-lg bg-white/[0.07] hover:bg-white/[0.12] border border-white/10 text-xs font-medium text-white transition-colors"
                  >
                    Simulate Tap (&lt;200ms)
                  </button>
                  <button
                    type="button"
                    onMouseDown={() => setIsPressed(true)}
                    onMouseUp={() => {
                      setIsPressed(false);
                      handlePrimaryLongPress();
                    }}
                    onClick={handlePrimaryLongPress}
                    className="min-h-[40px] px-3.5 py-2 rounded-lg bg-[#0A84FF] hover:bg-[#0070E0] text-xs font-medium text-white transition-colors"
                  >
                    Simulate Long-Press (~450ms)
                  </button>
                </div>
              </div>

              {/* Simulated Phone Upper Viewport */}
              <div className="mt-6 relative rounded-2xl bg-gradient-to-b from-[#171B26] via-[#12151E] to-[#0B0D13] border border-white/10 p-6 min-h-[390px] flex flex-col items-center justify-between overflow-hidden">
                {/* Status Bar Context */}
                <div className="w-full flex items-center justify-between text-xs text-zinc-400 px-2">
                  <span className="font-mono font-medium text-zinc-200">09:41</span>
                  <span>
                    {isKeyguardLocked ? 'Keyguard Locked (Redacted)' : 'Unlocked · 120Hz ProMotion'}
                  </span>
                  <span className="font-mono">5G · 98%</span>
                </div>

                {/* Dynamic Island Interactive Stage */}
                <div className="my-auto py-6 flex flex-col items-center justify-center w-full">
                  {!isExpanded ? (
                    /* COMPACT / MINIMAL CAPSULE + METABALL DROPLETS */
                    <div className="flex items-center justify-center relative">
                      {/* Primary Capsule */}
                      <div
                        role="button"
                        tabIndex={0}
                        aria-label="Primary Dynamic Island Capsule"
                        onClick={handlePrimarySingleTap}
                        onContextMenu={(e) => {
                          e.preventDefault();
                          handlePrimaryLongPress();
                        }}
                        style={{
                          transform: `scale(${isPressed ? ACTIVITY_KIT_LIMITS.PRESS_COMPRESSION_SCALE : 1})`,
                          borderColor: keylineBorderColor,
                          opacity: simulateStale ? 0.58 : 1,
                          width: isLandscapeLimited ? 156 : primary ? 228 : 126,
                          height: 38,
                        }}
                        className="bg-black rounded-full border transition-all duration-200 px-3.5 flex items-center justify-between cursor-pointer select-none shadow-[0_12px_30px_rgba(0,0,0,0.65)]"
                      >
                        {/* Leading Slot (compactLeading) */}
                        <div className="flex items-center gap-2 min-w-0">
                          <div
                            className="w-6 h-6 rounded-full flex items-center justify-center shrink-0"
                            style={{
                              backgroundColor: primary
                                ? `${primary.keylineTintHex}24`
                                : 'rgba(255,255,255,0.08)',
                            }}
                          >
                            {renderActivityGlyph(primary?.kind, primary?.keylineTintHex ?? '#FFFFFF')}
                          </div>
                          {!isLandscapeLimited && primary && (
                            <span className="text-xs font-medium text-white truncate max-w-[72px]">
                              {isKeyguardLocked ? primary.appName : primary.title}
                            </span>
                          )}
                          {!primary && (
                            <span className="text-[11px] font-mono text-zinc-400">09:41 · 98%</span>
                          )}
                        </div>

                        {/* True-Black Sensor Cutout Keep-Out Zone */}
                        <div
                          className="w-9 h-5 rounded-full bg-[#050505] border border-white/[0.04] shrink-0 mx-1.5 flex items-center justify-center"
                          title="Hardware Camera & Proximity Cutout Keep-Out Region"
                        >
                          <div className="w-2 h-2 rounded-full bg-[#0F131D]" />
                        </div>

                        {/* Trailing Slot (compactTrailing) */}
                        <div className="flex items-center gap-1.5 shrink-0">
                          {primary && (
                            <span
                              className="text-xs font-mono font-semibold"
                              style={{ color: primary.keylineTintHex }}
                            >
                              {isKeyguardLocked ? 'Locked' : primary.badgeText}
                            </span>
                          )}
                        </div>
                      </div>

                      {/* 2D Metaball Ferrofluid Bridge & Detached Secondary Droplet */}
                      {secondary && (
                        <div className="flex items-center">
                          {/* Ferrofluid Neck Bridge */}
                          {enableMetaballBridge && metaballBridge.isConnected ? (
                            <div
                              style={{
                                width: Math.max(2, dropletGapPx),
                                height: Math.max(4, metaballBridge.pinchHeightPx),
                              }}
                              className="bg-black transition-all duration-150"
                              title={`2D Metaball smin Bridge (pinchHeight: ${metaballBridge.pinchHeightPx.toFixed(1)}px)`}
                            />
                          ) : (
                            <div style={{ width: Math.max(4, dropletGapPx) }} />
                          )}

                          {/* Secondary Droplet */}
                          <button
                            type="button"
                            onClick={handleCycleFocus}
                            style={{
                              width: 38,
                              height: 38,
                              borderColor: enableKeylineTint
                                ? `${secondary.keylineTintHex}88`
                                : 'rgba(255,255,255,0.16)',
                            }}
                            className="rounded-full bg-black border flex items-center justify-center shadow-lg hover:scale-105 transition-transform"
                            title={`Secondary Activity (${secondary.appName}) — Click to cycle focus`}
                          >
                            {renderActivityGlyph(secondary.kind, secondary.keylineTintHex)}
                          </button>
                        </div>
                      )}

                      {/* Tertiary Droplet (iPhone 18 Pro 3-Activity Mitosis) */}
                      {tertiary && maxConcurrent >= 3 && (
                        <div className="flex items-center ml-2">
                          <button
                            type="button"
                            onClick={handleCycleFocus}
                            style={{
                              width: 34,
                              height: 34,
                              borderColor: enableKeylineTint
                                ? `${tertiary.keylineTintHex}88`
                                : 'rgba(255,255,255,0.16)',
                            }}
                            className="rounded-full bg-black border flex items-center justify-center shadow-lg hover:scale-105 transition-transform"
                            title={`Tertiary Activity (${tertiary.appName}) — Click to cycle focus`}
                          >
                            {renderActivityGlyph(tertiary.kind, tertiary.keylineTintHex)}
                          </button>
                        </div>
                      )}
                    </div>
                  ) : (
                    /* EXPANDED 4-REGION CARD (.leading, .trailing, .center, .bottom) */
                    <div
                      style={{
                        width: Math.max(366, scrubState.widthPx + 34),
                        minHeight: scrubState.heightPx,
                        borderRadius: `${scrubState.cornerRadiusDp}px`,
                        borderColor: keylineBorderColor,
                        opacity: simulateStale ? 0.65 : 1,
                      }}
                      className="bg-black border p-5 text-white shadow-[0_20px_50px_rgba(0,0,0,0.85)] transition-all duration-150 flex flex-col justify-between max-w-full"
                    >
                      {primary ? (
                        <div style={{ opacity: scrubState.expandedContentAlpha }}>
                          {/* Top Row: .leading and .trailing flanking sensor keep-out */}
                          <div className="flex items-center justify-between gap-3">
                            {/* .leading region */}
                            <div className="flex items-center gap-3 min-w-0">
                              <div
                                className="w-10 h-10 rounded-xl flex items-center justify-center shrink-0"
                                style={{ backgroundColor: `${primary.keylineTintHex}24` }}
                              >
                                {renderActivityGlyph(primary.kind, primary.keylineTintHex)}
                              </div>
                              <div className="min-w-0">
                                <div className="text-xs text-zinc-400 truncate">
                                  {primary.appName} · .leading
                                </div>
                                <div className="text-sm font-semibold text-white truncate">
                                  {isKeyguardLocked
                                    ? 'Private Activity Content Redacted'
                                    : primary.title}
                                </div>
                              </div>
                            </div>

                            {/* .trailing region */}
                            <div className="text-right shrink-0">
                              <div className="text-xs text-zinc-400">.trailing</div>
                              <div
                                className="text-sm font-mono font-bold whitespace-nowrap"
                                style={{ color: primary.keylineTintHex }}
                              >
                                {isKeyguardLocked ? 'Locked' : primary.etaLabel ?? primary.badgeText}
                              </div>
                            </div>
                          </div>

                          {/* .center region (wrapped below if .belowIfTooWide triggered) */}
                          <div className="mt-3 pt-2.5 border-t border-white/10 flex items-center justify-between gap-3 text-xs text-zinc-300">
                            <span className="truncate">
                              {isKeyguardLocked
                                ? 'Unlock device to view full details'
                                : primary.lyricLine ?? primary.subtitle}
                            </span>
                            <span className="text-zinc-500 font-mono whitespace-nowrap shrink-0">
                              .center ({regionAllocation.centerPlacedBelow ? 'wrapped' : 'inline'})
                            </span>
                          </div>

                          {/* .bottom region: Progress / Milestones / 44dp Min Touch Target Controls */}
                          <div className="mt-3 pt-2">
                            {primary.milestones && primary.milestones.length > 0 ? (
                              <div className="grid grid-cols-4 gap-2 mb-3">
                                {primary.milestones.map((step, idx) => {
                                  const reached = idx <= (primary.currentMilestoneIndex ?? 0);
                                  return (
                                    <div key={step} className="text-left">
                                      <div
                                        className="h-1.5 rounded-full mb-1"
                                        style={{
                                          backgroundColor: reached
                                            ? primary.keylineTintHex
                                            : 'rgba(255,255,255,0.14)',
                                        }}
                                      />
                                      <span
                                        className={`text-[11px] block truncate ${
                                          reached ? 'text-white font-medium' : 'text-zinc-500'
                                        }`}
                                      >
                                        {step}
                                      </span>
                                    </div>
                                  );
                                })}
                              </div>
                            ) : (
                              <div className="w-full h-1.5 bg-white/15 rounded-full overflow-hidden mb-3">
                                <div
                                  className="h-full rounded-full"
                                  style={{
                                    width: `${Math.round((primary.progressFraction ?? 0.5) * 100)}%`,
                                    backgroundColor: primary.keylineTintHex,
                                  }}
                                />
                              </div>
                            )}

                            {/* 44dp Minimum Hit-Target Interactive Controls */}
                            <div className="flex items-center justify-between gap-2">
                              <button
                                type="button"
                                onClick={() =>
                                  setLastHostAppAction(
                                    `Opened ${primary.appName} via deepLinkIntent (${primary.packageName})`
                                  )
                                }
                                className="min-h-[44px] px-4 rounded-xl bg-white/10 hover:bg-white/15 text-xs font-medium text-white flex items-center gap-1.5 whitespace-nowrap shrink-0 transition-colors"
                              >
                                <span>Open {primary.appName}</span>
                                <ArrowUpRight className="w-3.5 h-3.5" />
                              </button>

                              <div className="flex items-center gap-2">
                                <button
                                  type="button"
                                  onClick={handleCycleFocus}
                                  className="min-h-[44px] px-3.5 rounded-xl bg-white/[0.07] hover:bg-white/[0.12] text-xs text-zinc-200 whitespace-nowrap shrink-0 transition-colors"
                                >
                                  Cycle Focus
                                </button>
                                <button
                                  type="button"
                                  onClick={() => {
                                    setIsExpanded(false);
                                    setScrubDeltaYPx(0);
                                    setLastHostAppAction('Collapsed Expanded card back to Compact pill');
                                  }}
                                  className="min-h-[44px] px-4 rounded-xl bg-[#0A84FF] hover:bg-[#0070E0] text-xs font-medium text-white whitespace-nowrap shrink-0 transition-colors"
                                >
                                  Collapse
                                </button>
                              </div>
                            </div>
                          </div>
                        </div>
                      ) : (
                        <div className="text-center py-6 text-xs text-zinc-400">
                          No active Live Activities selected. Toggle an activity on the right panel.
                        </div>
                      )}
                    </div>
                  )}
                </div>

                {/* Telemetry Readout Footer inside Viewport */}
                <div className="w-full pt-4 border-t border-white/10 flex flex-wrap items-center justify-between gap-2 text-xs text-zinc-300">
                  <span className="truncate max-w-full">{lastHostAppAction}</span>
                  <span className="text-zinc-400 font-mono whitespace-nowrap">
                    4-Region Cap: {regionAllocation.totalHeightDp}dp · Radius:{' '}
                    {isExpanded ? scrubState.cornerRadiusDp.toFixed(1) : '18.5'}dp
                  </span>
                </div>
              </div>

              {/* 1:1 Upward Scrub Collapse & Metaball Physics Sliders */}
              <div className="mt-6 grid grid-cols-1 md:grid-cols-2 gap-4">
                <div className="p-4 rounded-xl bg-white/[0.03] border border-white/10">
                  <div className="flex items-center justify-between gap-2 text-xs mb-2">
                    <label htmlFor="scrub-slider" className="font-medium text-white truncate">
                      1:1 Upward Scrub Collapse
                    </label>
                    <span className="font-mono text-zinc-400 whitespace-nowrap shrink-0">
                      ΔY: {scrubDeltaYPx}px ({Math.round(scrubState.progress * 100)}%)
                    </span>
                  </div>
                  <input
                    id="scrub-slider"
                    type="range"
                    min={-132}
                    max={0}
                    step={2}
                    value={scrubDeltaYPx}
                    onChange={(e) => {
                      const val = Number(e.target.value);
                      setIsExpanded(true);
                      setScrubDeltaYPx(val);
                      if (val <= -124) {
                        setIsExpanded(false);
                        setScrubDeltaYPx(0);
                        setLastHostAppAction(
                          '1:1 Upward Scrub crossed collapse threshold (progress >= 94%) -> Committed Compact state'
                        );
                      }
                    }}
                    className="w-full accent-[#0A84FF]"
                  />
                  <p className="text-[11px] text-zinc-400 mt-1.5">
                    Width: {Math.round(scrubState.widthPx)}px · Height:{' '}
                    {Math.round(scrubState.heightPx)}px · Radius:{' '}
                    {scrubState.cornerRadiusDp.toFixed(1)}dp
                  </p>
                </div>

                <div className="p-4 rounded-xl bg-white/[0.03] border border-white/10">
                  <div className="flex items-center justify-between gap-2 text-xs mb-2">
                    <label htmlFor="metaball-slider" className="font-medium text-white truncate">
                      2D Metaball Droplet Gap (smin)
                    </label>
                    <span className="font-mono text-zinc-400 whitespace-nowrap shrink-0">
                      {dropletGapPx}px · {metaballBridge.isConnected ? 'Connected' : 'Detached'}
                    </span>
                  </div>
                  <input
                    id="metaball-slider"
                    type="range"
                    min={2}
                    max={30}
                    step={1}
                    value={dropletGapPx}
                    onChange={(e) => setDropletGapPx(Number(e.target.value))}
                    className="w-full accent-[#30D158]"
                  />
                  <p className="text-[11px] text-zinc-400 mt-1.5">
                    Neck Pinch Height: {metaballBridge.pinchHeightPx.toFixed(1)}px · Snap Threshold:
                    22px
                  </p>
                </div>
              </div>
            </section>

            {/* Right Column: ActivityKit Arbitrator, Parity Toggles & 4KB Guardrail (5 cols) */}
            <aside className="lg:col-span-5 space-y-6">
              {/* Interaction Mode & Parity Policy Card */}
              <div className="bg-[#101218] border border-white/10 rounded-2xl p-6">
                <h3 className="text-base font-semibold text-white">
                  Apple Parity &amp; Spatial Geometry Controls
                </h3>
                <p className="text-xs text-zinc-400 mt-1">
                  Configure InteractionMode, 3-activity mitosis, .keylineTint, and Lock Screen privacy.
                </p>

                {/* Segmented Interaction Mode Selector */}
                <div className="mt-4 grid grid-cols-2 gap-2 p-1 bg-white/[0.04] border border-white/10 rounded-lg">
                  <button
                    type="button"
                    onClick={() => setInteractionMode('APPLE_PARITY')}
                    className={`min-h-[38px] px-3 py-1.5 rounded-md text-xs font-medium transition-colors ${
                      interactionMode === 'APPLE_PARITY'
                        ? 'bg-[#0A84FF] text-white'
                        : 'text-zinc-300 hover:text-white'
                    }`}
                  >
                    APPLE_PARITY (Tap=App, Hold=Expand)
                  </button>
                  <button
                    type="button"
                    onClick={() => setInteractionMode('ANDROID_CLASSIC')}
                    className={`min-h-[38px] px-3 py-1.5 rounded-md text-xs font-medium transition-colors ${
                      interactionMode === 'ANDROID_CLASSIC'
                        ? 'bg-[#0A84FF] text-white'
                        : 'text-zinc-300 hover:text-white'
                    }`}
                  >
                    ANDROID_CLASSIC (Tap=Expand)
                  </button>
                </div>

                {/* Parity Feature Toggles */}
                <div className="mt-4 space-y-2.5">
                  {[
                    {
                      label: '.keylineTint 1dp Adaptive Perimeter Border',
                      checked: enableKeylineTint,
                      onChange: () => setEnableKeylineTint((v) => !v),
                    },
                    {
                      label: '2D Metaball Ferrofluid Droplet Bridge',
                      checked: enableMetaballBridge,
                      onChange: () => setEnableMetaballBridge((v) => !v),
                    },
                    {
                      label: '3-Activity Mitosis (Primary + Secondary + Tertiary)',
                      checked: maxConcurrent === 3,
                      onChange: () => setMaxConcurrent((v) => (v === 3 ? 2 : 3)),
                    },
                    {
                      label: 'Landscape Width-Limited Mode (28dp Circular Slots)',
                      checked: isLandscapeLimited,
                      onChange: () => setIsLandscapeLimited((v) => !v),
                    },
                    {
                      label: 'Smart Lock Screen Redaction (LockStatePrivacyPolicy)',
                      checked: isKeyguardLocked,
                      onChange: () => setIsKeyguardLocked((v) => !v),
                    },
                    {
                      label: 'Simulate ActivityKit staleDate Expiration (isStale)',
                      checked: simulateStale,
                      onChange: () => setSimulateStale((v) => !v),
                    },
                  ].map((item) => (
                    <label
                      key={item.label}
                      className="flex items-center justify-between gap-3 p-2.5 rounded-lg bg-white/[0.03] hover:bg-white/[0.05] border border-white/5 cursor-pointer text-xs"
                    >
                      <span className="text-zinc-200">{item.label}</span>
                      <input
                        type="checkbox"
                        checked={item.checked}
                        onChange={item.onChange}
                        className="w-4 h-4 accent-[#0A84FF] rounded"
                      />
                    </label>
                  ))}
                </div>
              </div>

              {/* Concurrent Live Activities Pool */}
              <div className="bg-[#101218] border border-white/10 rounded-2xl p-6">
                <div className="flex items-center justify-between">
                  <div>
                    <h3 className="text-base font-semibold text-white">
                      Live Activities Arbitration Pool
                    </h3>
                    <p className="text-xs text-zinc-400 mt-0.5">
                      Sorted by PriorityRank + relevanceScore + interruptBoost + FIFO
                    </p>
                  </div>
                  <button
                    type="button"
                    onClick={handleCycleFocus}
                    className="min-h-[36px] px-3 py-1.5 rounded-lg bg-white/[0.07] hover:bg-white/[0.12] text-xs font-medium text-white flex items-center gap-1.5 transition-colors"
                  >
                    <RotateCcw className="w-3.5 h-3.5" />
                    <span>Rotate Focus</span>
                  </button>
                </div>

                <div className="mt-4 space-y-2.5">
                  {INITIAL_ACTIVITIES.map((act) => {
                    const isActive = activeIds.includes(act.id);
                    const slotRole =
                      primary?.id === act.id
                        ? 'PRIMARY'
                        : secondary?.id === act.id
                        ? 'SECONDARY'
                        : tertiary?.id === act.id
                        ? 'TERTIARY'
                        : isActive
                        ? 'QUEUED'
                        : 'OFF';
                    return (
                      <div
                        key={act.id}
                        className={`p-3 rounded-xl border transition-colors flex items-center justify-between gap-3 ${
                          isActive
                            ? 'bg-white/[0.05] border-white/15'
                            : 'bg-white/[0.01] border-white/5 opacity-60'
                        }`}
                      >
                        <div className="flex items-center gap-3 min-w-0">
                          <div
                            className="w-8 h-8 rounded-lg flex items-center justify-center shrink-0"
                            style={{ backgroundColor: `${act.keylineTintHex}24` }}
                          >
                            {renderActivityGlyph(act.kind, act.keylineTintHex)}
                          </div>
                          <div className="min-w-0">
                            <div className="text-xs font-semibold text-white truncate">
                              {act.appName} — {act.title}
                            </div>
                            <div className="text-[11px] text-zinc-400 truncate">
                              Rank {act.priorityOrder} · Relevance {act.relevanceScore.toFixed(2)} ·
                              Slot: {slotRole}
                            </div>
                          </div>
                        </div>

                        <button
                          type="button"
                          onClick={() => toggleActivity(act.id)}
                          className={`min-h-[36px] px-3 py-1 rounded-lg text-xs font-medium transition-colors shrink-0 ${
                            isActive
                              ? 'bg-[#30D158]/20 text-[#30D158] border border-[#30D158]/40'
                              : 'bg-white/10 text-zinc-300 hover:text-white'
                          }`}
                        >
                          {isActive ? 'Active' : 'Enable'}
                        </button>
                      </div>
                    );
                  })}
                </div>
              </div>

              {/* ActivityKit 4KB Payload Cap Verifier */}
              <div className="bg-[#101218] border border-white/10 rounded-2xl p-6">
                <h3 className="text-base font-semibold text-white">
                  ActivityKit 4 KB (4,096-Byte) Payload Guardrail
                </h3>
                <p className="text-xs text-zinc-400 mt-1">
                  Payloads exceeding 4,096 UTF-8 bytes are rejected by ActivityLifecyclePolicy to prevent
                  120Hz IPC frame drops.
                </p>
                <div className="mt-3 flex items-center justify-between text-xs font-mono">
                  <span>
                    Estimated Size: {payloadBytesCount} / {ACTIVITY_KIT_LIMITS.MAX_PAYLOAD_BYTES} bytes
                  </span>
                  <span
                    className={
                      payloadBytesCount <= ACTIVITY_KIT_LIMITS.MAX_PAYLOAD_BYTES
                        ? 'text-[#30D158]'
                        : 'text-[#FF453A]'
                    }
                  >
                    {payloadBytesCount <= ACTIVITY_KIT_LIMITS.MAX_PAYLOAD_BYTES
                      ? 'ACCEPTED (Within 4KB Cap)'
                      : 'REJECTED (Exceeds 4KB Cap)'}
                  </span>
                </div>
                <div className="mt-3 flex items-center gap-2">
                  <button
                    type="button"
                    onClick={() =>
                      setPayloadTestText('Dasher is approaching with your order · Arriving in 4 min')
                    }
                    className="min-h-[36px] px-3 py-1.5 rounded-lg bg-white/[0.07] hover:bg-white/[0.12] text-xs text-white transition-colors"
                  >
                    Test Valid Payload (128 B)
                  </button>
                  <button
                    type="button"
                    onClick={() => setPayloadTestText('X'.repeat(4300))}
                    className="min-h-[36px] px-3 py-1.5 rounded-lg bg-[#FF453A]/20 hover:bg-[#FF453A]/30 border border-[#FF453A]/40 text-xs text-[#FF453A] transition-colors"
                  >
                    Test Oversized Payload (&gt;4 KB)
                  </button>
                </div>
              </div>
            </aside>
          </div>
        )}

        {activeTab === 'architecture' && (
          <section className="space-y-6">
            <div className="flex flex-wrap items-center justify-between gap-4 border-b border-white/10 pb-5">
              <div>
                <h2 className="text-xl font-semibold text-white">
                  Implemented Kotlin Architecture (:core &amp; Android Engine)
                </h2>
                <p className="text-xs text-zinc-400 mt-1">
                  All 4 Roadmap Phases implemented and verified with 210 passing unit tests (:core:test + :testDebugUnitTest).
                </p>
              </div>

              <div className="flex items-center gap-1 p-1 bg-white/[0.04] border border-white/10 rounded-lg">
                {['ALL', 'Phase 1', 'Phase 2', 'Phase 3', 'Phase 4', 'Verification'].map((phase) => (
                  <button
                    key={phase}
                    type="button"
                    onClick={() => setSelectedPhase(phase)}
                    className={`min-h-[36px] px-3 py-1 rounded-md text-xs font-medium transition-colors ${
                      selectedPhase === phase
                        ? 'bg-[#0A84FF] text-white'
                        : 'text-zinc-300 hover:text-white'
                    }`}
                  >
                    {phase}
                  </button>
                ))}
              </div>
            </div>

            <div className="grid grid-cols-1 md:grid-cols-2 gap-5">
              {filteredModules.map((mod) => (
                <article
                  key={mod.path}
                  className="bg-[#101218] border border-white/10 rounded-2xl p-6 flex flex-col justify-between"
                >
                  <div>
                    <div className="text-xs text-zinc-400 font-mono">
                      {mod.phase} · {mod.module}
                    </div>
                    <h3 className="text-sm font-mono font-semibold text-[#0A84FF] mt-1 break-all">
                      {mod.path}
                    </h3>
                    <p className="text-xs text-zinc-300 mt-3 leading-relaxed">{mod.summary}</p>
                  </div>

                  <div className="mt-4 pt-3 border-t border-white/10 text-xs text-zinc-400 font-mono">
                    Exports: {mod.keySymbols.join(' · ')}
                  </div>
                </article>
              ))}
            </div>
          </section>
        )}

        {activeTab === 'papers' && (
          <section className="grid grid-cols-1 lg:grid-cols-12 gap-8 items-start">
            <aside className="lg:col-span-4 bg-[#101218] border border-white/10 rounded-2xl p-5 space-y-3">
              <h2 className="text-base font-semibold text-white">
                Repository Folder: <code className="font-mono text-[#0A84FF]">research papers/</code>
              </h2>
              <p className="text-xs text-zinc-400">
                Both research papers provided for this project are stored in the root{' '}
                <code className="font-mono text-zinc-200">research papers/</code> directory of{' '}
                <code className="font-mono text-zinc-200">Darshan8610/Dynamic-Island</code>.
              </p>

              <div className="space-y-2 pt-2">
                {RESEARCH_PAPERS.map((paper) => (
                  <button
                    key={paper.id}
                    type="button"
                    onClick={() => setSelectedPaperId(paper.id)}
                    className={`w-full text-left p-3.5 rounded-xl border transition-colors ${
                      selectedPaperId === paper.id
                        ? 'bg-[#0A84FF]/15 border-[#0A84FF] text-white'
                        : 'bg-white/[0.02] border-white/10 text-zinc-300 hover:bg-white/[0.05]'
                    }`}
                  >
                    <div className="text-xs font-mono text-zinc-400">{paper.fileName}</div>
                    <div className="text-sm font-semibold mt-1">{paper.title}</div>
                  </button>
                ))}
              </div>
            </aside>

            <article className="lg:col-span-8 bg-[#101218] border border-white/10 rounded-2xl p-6 space-y-6">
              <div className="border-b border-white/10 pb-4">
                <div className="text-xs font-mono text-[#0A84FF]">{activePaper.fileName}</div>
                <h3 className="text-xl font-semibold text-white mt-1">{activePaper.title}</h3>
                <p className="text-xs text-zinc-400 mt-1">{activePaper.subtitle}</p>
              </div>

              <div className="space-y-5">
                {activePaper.sections.map((sec) => (
                  <div key={sec.heading} className="p-4 rounded-xl bg-white/[0.02] border border-white/5">
                    <h4 className="text-sm font-semibold text-white">{sec.heading}</h4>
                    <p className="text-xs text-zinc-300 mt-2 leading-relaxed">{sec.content}</p>
                  </div>
                ))}
              </div>
            </article>
          </section>
        )}

        {activeTab === 'skills' && (
          <section className="space-y-6">
            <div className="flex flex-wrap items-center justify-between gap-4 border-b border-white/10 pb-5">
              <div>
                <h2 className="text-xl font-semibold text-white">
                  Repository Folder: <code className="font-mono text-[#0A84FF]">used skills/</code> (25 Skill Repositories)
                </h2>
                <p className="text-xs text-zinc-400 mt-1">
                  All 25 requested Android, Kotlin, Compose, Reverse Engineering, Perfetto, and Testing skill repositories are saved in{' '}
                  <code className="font-mono text-zinc-200">used skills/01-...</code> through{' '}
                  <code className="font-mono text-zinc-200">25-...</code>.
                </p>
              </div>

              <div className="flex flex-wrap items-center gap-3">
                <div className="relative">
                  <Search className="w-3.5 h-3.5 text-zinc-400 absolute left-3 top-1/2 -translate-y-1/2" />
                  <input
                    type="search"
                    value={skillQuery}
                    onChange={(e) => setSkillQuery(e.target.value)}
                    placeholder="Search 25 skills..."
                    aria-label="Search 25 skills"
                    className="h-9 pl-8 pr-3 rounded-lg bg-white/[0.04] border border-white/10 text-xs text-white placeholder:text-zinc-500 focus:outline-none focus:border-[#0A84FF]"
                  />
                </div>

                <div className="flex items-center gap-1 p-1 bg-white/[0.04] border border-white/10 rounded-lg">
                  {[
                    'ALL',
                    'Reverse Engineering',
                    'Kotlin & Android',
                    'Compose & UI',
                    'Testing & Perf',
                    'Agent & Security',
                  ].map((cat) => (
                    <button
                      key={cat}
                      type="button"
                      onClick={() => setSkillCategory(cat)}
                      className={`min-h-[32px] px-2.5 py-1 rounded-md text-xs font-medium transition-colors ${
                        skillCategory === cat
                          ? 'bg-[#0A84FF] text-white'
                          : 'text-zinc-300 hover:text-white'
                      }`}
                    >
                      {cat}
                    </button>
                  ))}
                </div>
              </div>
            </div>

            <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
              {filteredSkills.map((skill) => (
                <article
                  key={skill.folderName}
                  className="bg-[#101218] border border-white/10 rounded-xl p-4 flex flex-col justify-between"
                >
                  <div>
                    <div className="text-xs text-zinc-400 font-mono">
                      #{String(skill.index).padStart(2, '0')} · {skill.category}
                    </div>
                    <h3 className="text-sm font-semibold text-white mt-1">{skill.repoSlug}</h3>
                    <div className="text-[11px] font-mono text-[#0A84FF] mt-1 break-all">
                      used skills/{skill.folderName}
                    </div>
                    <p className="text-xs text-zinc-300 mt-2.5 leading-relaxed">
                      {skill.appliedInProject}
                    </p>
                  </div>

                  <div className="mt-4 pt-3 border-t border-white/10 flex items-center justify-between text-xs text-zinc-400">
                    <span>Saved in Repo</span>
                    <a
                      href={skill.url}
                      target="_blank"
                      rel="noreferrer"
                      className="text-zinc-200 hover:text-white inline-flex items-center gap-1"
                    >
                      <span>GitHub Source</span>
                      <ExternalLink className="w-3 h-3" />
                    </a>
                  </div>
                </article>
              ))}
            </div>
          </section>
        )}
      </main>
    </div>
  );
}
