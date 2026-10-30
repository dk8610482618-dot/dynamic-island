import type { IslandActivityModel } from '../lib/islandParityEngine';

export interface SkillRepoEntry {
  index: number;
  folderName: string;
  repoSlug: string;
  url: string;
  category: 'Reverse Engineering' | 'Kotlin & Android' | 'Compose & UI' | 'Testing & Perf' | 'Agent & Security';
  appliedInProject: string;
}

export interface KotlinModuleEntry {
  path: string;
  module: ':core (Pure JVM)' | 'engine (Android)' | ':app (Host UI)' | 'Unit Tests';
  phase: 'Phase 1' | 'Phase 2' | 'Phase 3' | 'Phase 4' | 'Verification';
  summary: string;
  keySymbols: string[];
}

export interface ResearchPaperEntry {
  id: string;
  fileName: string;
  title: string;
  subtitle: string;
  sections: { heading: string; content: string }[];
}

export const INITIAL_ACTIVITIES: IslandActivityModel[] = [
  {
    id: 'activity_media_session',
    kind: 'MEDIA_SESSION',
    appName: 'Apple Music',
    packageName: 'com.apple.android.music',
    title: 'Starboy',
    subtitle: 'The Weeknd · Daft Punk',
    badgeText: 'WAVE',
    priorityOrder: 400,
    relevanceScore: 0.84,
    interruptBoost: 0,
    startedAtMs: 1_700_000_000_000 - 120_000,
    keylineTintHex: '#FF2D55',
    progressFraction: 0.38,
    lyricLine: 'Look what you’ve done — I’m a motherfuckin’ Starboy',
    audioRouteLabel: 'AirPods Pro · Spatial Audio',
    payloadBytes: 412,
  },
  {
    id: 'activity_live_journey',
    kind: 'LIVE_JOURNEY',
    appName: 'DoorDash',
    packageName: 'com.doordash.driverapp',
    title: 'Dasher Approaching',
    subtitle: 'Blue Bottle Coffee · 2 items',
    badgeText: '4m',
    priorityOrder: 450,
    relevanceScore: 0.92,
    interruptBoost: 150,
    startedAtMs: 1_700_000_000_000 - 60_000,
    keylineTintHex: '#30D158',
    progressFraction: 0.78,
    etaLabel: 'ETA 6:42 PM (4 min)',
    milestones: ['Confirmed', 'Preparing', 'On the way', 'Arriving'],
    currentMilestoneIndex: 2,
    payloadBytes: 528,
  },
  {
    id: 'activity_navigation',
    kind: 'NAVIGATION',
    appName: 'Google Maps',
    packageName: 'com.google.android.apps.maps',
    title: 'Turn right onto Market St',
    subtitle: '350 ft · Then keep left onto Octavia Blvd',
    badgeText: '350 ft',
    priorityOrder: 300,
    relevanceScore: 0.96,
    interruptBoost: 200,
    startedAtMs: 1_700_000_000_000 - 90_000,
    keylineTintHex: '#0A84FF',
    progressFraction: 0.64,
    etaLabel: 'ETA 6:48 PM · 1.4 mi',
    milestones: ['Start', 'Cruising', 'Approaching', 'Destination'],
    currentMilestoneIndex: 2,
    payloadBytes: 490,
  },
  {
    id: 'activity_timer',
    kind: 'LIVE_TIMER',
    appName: 'Clock',
    packageName: 'com.google.android.deskclock',
    title: 'Pour-Over Bloom',
    subtitle: 'Countdown Timer',
    badgeText: '01:42',
    priorityOrder: 200,
    relevanceScore: 0.88,
    interruptBoost: 0,
    startedAtMs: 1_700_000_000_000 - 45_000,
    keylineTintHex: '#FF9F0A',
    progressFraction: 0.71,
    etaLabel: '01:42 remaining',
    payloadBytes: 240,
  },
  {
    id: 'activity_download',
    kind: 'FILE_DOWNLOAD',
    appName: 'System Downloads',
    packageName: 'com.android.providers.downloads',
    title: 'DynamicIsland-release-unsigned.apk',
    subtitle: '42.8 MB of 64.0 MB · 18.4 MB/s',
    badgeText: '67%',
    priorityOrder: 480,
    relevanceScore: 0.67,
    interruptBoost: 0,
    startedAtMs: 1_700_000_000_000 - 30_000,
    keylineTintHex: '#64D2FF',
    progressFraction: 0.67,
    etaLabel: '2s left',
    payloadBytes: 310,
  },
];

export const KOTLIN_MODULES: KotlinModuleEntry[] = [
  {
    path: 'core/src/main/kotlin/com/dynamicisland/core/geometry/ExpandedRegionLayoutSolver.kt',
    module: ':core (Pure JVM)',
    phase: 'Phase 1',
    summary:
      '4-region Expanded grid solver (.leading, .trailing, .center, .bottom) with .belowIfTooWide wrapping, regionPriority conflict resolution, 160dp nominal height cap, 44dp corner radius, 44dp hit target enforcement, 28dp landscape width-limited condensation, and foldable crease avoidance.',
    keySymbols: [
      'ExpandedRegionLayoutSolver.solve',
      'ExpandedRegionLayoutSolver.resolveWidthLimitedCompact',
      'ExpandedRegionLayoutSolver.resolvePostureWindow',
      'CenterWrapBehavior.BELOW_IF_TOO_WIDE',
    ],
  },
  {
    path: 'core/src/main/kotlin/com/dynamicisland/core/motion/ScrubCollapseController.kt',
    module: ':core (Pure JVM)',
    phase: 'Phase 2',
    summary:
      '1:1 finger-scrubbable upward collapse interpolation (width, height, 44dp->18.5dp radius, content alpha crossfade), 0.985f long-press touch compression scale, and velocity-aware spring release handoff.',
    keySymbols: [
      'ScrubCollapseController.interpolate',
      'ScrubCollapseController.shouldCommitCollapse',
      'ScrubCollapseController.pressCompressionScale',
    ],
  },
  {
    path: 'core/src/main/kotlin/com/dynamicisland/core/motion/MetaballGeometry.kt',
    module: ':core (Pure JVM)',
    phase: 'Phase 2',
    summary:
      '2D polynomial/exponential smooth-minimum (smin) signed distance field (SDF) and ferrofluid bridge neck geometry for secondary/tertiary droplet detachment and coalescence.',
    keySymbols: [
      'MetaballGeometry.smoothMin',
      'MetaballGeometry.expSmoothMin',
      'MetaballGeometry.sdRoundedBox',
      'MetaballGeometry.computeBridgeNeck',
    ],
  },
  {
    path: 'core/src/main/kotlin/com/dynamicisland/core/activity/ActivityLifecyclePolicy.kt',
    module: ':core (Pure JVM)',
    phase: 'Phase 3',
    summary:
      'ActivityKit lifecycle governance: 4 KB (4,096 bytes) serialized payload cap, staleDate evaluation (isStale), 8-hour active session ceiling, and 4-hour post-end Lock Screen / Inbox retention.',
    keySymbols: [
      'ActivityLifecyclePolicy.MAX_PAYLOAD_BYTES',
      'ActivityLifecyclePolicy.MAX_ACTIVE_DURATION_MS',
      'ActivityLifecyclePolicy.POST_END_RETENTION_MS',
      'ActivityLifecyclePolicy.evaluate',
    ],
  },
  {
    path: 'core/src/main/kotlin/com/dynamicisland/core/arbitration/RelevanceArbitrator.kt',
    module: ':core (Pure JVM)',
    phase: 'Phase 3',
    summary:
      'Deterministic multi-activity arbitrator sorting by priority + relevanceScore + interruptBoost + FIFO start time, supporting 3-activity mitosis (primary, secondary, tertiary) and swipe focus cycling.',
    keySymbols: [
      'RelevanceArbitrator.arbitrate',
      'RelevanceArbitrator.Allocation',
      'SecondaryBubblePlacement.resolveTertiary',
    ],
  },
  {
    path: 'src/main/kotlin/com/dynamicisland/engine/ingestion/notifications/LiveUpdateProgressParser.kt',
    module: 'engine (Android)',
    phase: 'Phase 4',
    summary:
      'Android 16 (API 36) Notification.ProgressStyle and ongoing Live Update classifier for Turn-by-Turn Navigation, Rideshare/Delivery Journeys (with 4-stage milestones & ETA), and File Downloads.',
    keySymbols: [
      'LiveUpdateProgressParser.isAndroid16ProgressStyle',
      'LiveUpdateProgressParser.classifyAndParse',
      'ParsedLiveUpdate',
    ],
  },
  {
    path: 'src/main/kotlin/com/dynamicisland/engine/ui/FixedIslandCapsule.kt',
    module: 'engine (Android)',
    phase: 'Phase 2',
    summary:
      'Compose Compact & Minimal Dynamic Island capsule with 0.985x touch compression, .keylineTint 1dp border, 2D metaball ferrofluid bridge canvas, 28dp landscape condensation, staleDate dimming, and Idle Mini-Status Bar.',
    keySymbols: ['FixedIslandCapsule', 'MetaballGeometry.computeBridgeNeck', 'LockStatePrivacyPolicy.redact'],
  },
  {
    path: 'src/main/kotlin/com/dynamicisland/engine/ui/IslandExpandedPanel.kt',
    module: 'engine (Android)',
    phase: 'Phase 4',
    summary:
      'Expanded 4-region island card with 1:1 upward drag-to-collapse gesture, .keylineTint perimeter border, and specialized templates for Navigation, Live Journeys, File Downloads, Media, Timers, and Calls.',
    keySymbols: [
      'IslandExpandedPanel',
      'NavigationExpandedContent',
      'JourneyExpandedContent',
      'DownloadExpandedContent',
    ],
  },
  {
    path: 'core/src/test/kotlin/com/dynamicisland/core/parity/AppleParityCoreTest.kt',
    module: 'Unit Tests',
    phase: 'Verification',
    summary:
      'Pure JVM unit test suite verifying gesture routing, 4-region layout solver, .belowIfTooWide wrapping, 1:1 scrub collapse, 2D metaball SDF, ActivityKit 4KB/8h/4h/staleDate policies, and 3-activity mitosis.',
    keySymbols: ['AppleParityCoreTest', 'AppleParityEngineTest'],
  },
];

export const USED_SKILLS_CATALOG: SkillRepoEntry[] = [
  {
    index: 1,
    folderName: '01-SimoneAvogadro-android-reverse-engineering-skill',
    repoSlug: 'SimoneAvogadro/android-reverse-engineering-skill',
    url: 'https://github.com/SimoneAvogadro/android-reverse-engineering-skill',
    category: 'Reverse Engineering',
    appliedInProject: 'SystemUI cutout & overlay window flag analysis for WindowManager.LayoutParams.',
  },
  {
    index: 2,
    folderName: '02-credittone-android-reverse-engineering-skill',
    repoSlug: 'credittone/android-reverse-engineering-skill',
    url: 'https://github.com/credittone/android-reverse-engineering-skill',
    category: 'Reverse Engineering',
    appliedInProject: 'Notification.ProgressStyle & hidden Android 16 extras inspection.',
  },
  {
    index: 3,
    folderName: '03-zhaoxuya520-reverse-skill',
    repoSlug: 'zhaoxuya520/reverse-skill',
    url: 'https://github.com/zhaoxuya520/reverse-skill',
    category: 'Reverse Engineering',
    appliedInProject: 'Jindo / SpringBoard state machine parity verification and spring constant mapping.',
  },
  {
    index: 4,
    folderName: '04-trailofbits-skills',
    repoSlug: 'trailofbits/skills',
    url: 'https://github.com/trailofbits/skills',
    category: 'Agent & Security',
    appliedInProject: 'PendingIntent mutability hardening, secret scanning, and presenter-level lock screen redaction.',
  },
  {
    index: 5,
    folderName: '05-android-skills',
    repoSlug: 'android/skills',
    url: 'https://github.com/android/skills',
    category: 'Kotlin & Android',
    appliedInProject: 'Official Android SDK 36 lifecycle, NotificationListenerService, and foreground service architecture.',
  },
  {
    index: 6,
    folderName: '06-Kotlin-kotlin-agent-skills',
    repoSlug: 'Kotlin/kotlin-agent-skills',
    url: 'https://github.com/Kotlin/kotlin-agent-skills',
    category: 'Kotlin & Android',
    appliedInProject: 'Idiomatic Kotlin 2.2 sealed interfaces, value semantics, and pure-JVM :core module decoupling.',
  },
  {
    index: 7,
    folderName: '07-chrisbanes-skills',
    repoSlug: 'chrisbanes/skills',
    url: 'https://github.com/chrisbanes/skills',
    category: 'Compose & UI',
    appliedInProject: 'WindowInsets cutout geometry, edge-to-edge safe inset math, and Compose modifier ordering.',
  },
  {
    index: 8,
    folderName: '08-maxrave-dev-kotlin-footguns',
    repoSlug: 'maxrave-dev/kotlin-footguns',
    url: 'https://github.com/maxrave-dev/kotlin-footguns',
    category: 'Kotlin & Android',
    appliedInProject: 'Coroutine cancellation safety, Mutex lock discipline in IslandPriorityArbiter, and zero-allocation loops.',
  },
  {
    index: 9,
    folderName: '09-new-silvermoon-awesome-android-agent-skills',
    repoSlug: 'new-silvermoon/awesome-android-agent-skills',
    url: 'https://github.com/new-silvermoon/awesome-android-agent-skills',
    category: 'Kotlin & Android',
    appliedInProject: 'Multi-module Gradle dependency hygiene and Android accessibility overlay patterns.',
  },
  {
    index: 10,
    folderName: '10-suchobits-android-skills',
    repoSlug: 'suchobits/android-skills',
    url: 'https://github.com/suchobits/android-skills',
    category: 'Kotlin & Android',
    appliedInProject: 'MediaSessionManager active controller tracking and Telecom call state synchronization.',
  },
  {
    index: 11,
    folderName: '11-dpconde-claude-android-skill',
    repoSlug: 'dpconde/claude-android-skill',
    url: 'https://github.com/dpconde/claude-android-skill',
    category: 'Kotlin & Android',
    appliedInProject: 'Clean Repository-Arbiter-Bus reactive state flow pipelines.',
  },
  {
    index: 12,
    folderName: '12-Drjacky-claude-android-ninja',
    repoSlug: 'Drjacky/claude-android-ninja',
    url: 'https://github.com/Drjacky/claude-android-ninja',
    category: 'Kotlin & Android',
    appliedInProject: 'Strict unidirectional data flow (UDF) for SystemIngestionEvent and IslandUserCommand.',
  },
  {
    index: 13,
    folderName: '13-lennonpetrick-android-engineering-skills',
    repoSlug: 'lennonpetrick/android-engineering-skills',
    url: 'https://github.com/lennonpetrick/android-engineering-skills',
    category: 'Kotlin & Android',
    appliedInProject: 'Production R8/ProGuard rules, defensive PendingIntent execution, and API 28–36 compatibility.',
  },
  {
    index: 14,
    folderName: '14-almasumdev-awesome-kotlin-android-agent-skills',
    repoSlug: 'almasumdev/awesome-kotlin-android-agent-skills',
    url: 'https://github.com/almasumdev/awesome-kotlin-android-agent-skills',
    category: 'Kotlin & Android',
    appliedInProject: 'SharedPreferences persistence and reactive StateFlow settings synchronization.',
  },
  {
    index: 15,
    folderName: '15-haidrrrry-compose-kotlin-agent-skills',
    repoSlug: 'haidrrrry/compose-kotlin-agent-skills',
    url: 'https://github.com/haidrrrry/compose-kotlin-agent-skills',
    category: 'Compose & UI',
    appliedInProject: 'Jetpack Compose custom Canvas drawing for metaball bridges and circular progress rings.',
  },
  {
    index: 16,
    folderName: '16-aldefy-compose-skill',
    repoSlug: 'aldefy/compose-skill',
    url: 'https://github.com/aldefy/compose-skill',
    category: 'Compose & UI',
    appliedInProject: 'rememberUpdatedState gesture lambdas and graphicsLayer hardware-accelerated transforms.',
  },
  {
    index: 17,
    folderName: '17-ehmo-platform-design-skills',
    repoSlug: 'ehmo/platform-design-skills',
    url: 'https://github.com/ehmo/platform-design-skills',
    category: 'Compose & UI',
    appliedInProject: 'Apple HIG 4-region Dynamic Island grid geometry, 44dp corner radii, and 44x44dp touch targets.',
  },
  {
    index: 18,
    folderName: '18-skydashnet-material-design-3-ui-skill',
    repoSlug: 'skydashnet/material-design-3-ui-skill',
    url: 'https://github.com/skydashnet/material-design-3-ui-skill',
    category: 'Compose & UI',
    appliedInProject: 'Host app settings panel Material 3 controls, filter chips, and accessible contrast.',
  },
  {
    index: 19,
    folderName: '19-skydoves-android-testing-skills',
    repoSlug: 'skydoves/android-testing-skills',
    url: 'https://github.com/skydoves/android-testing-skills',
    category: 'Testing & Perf',
    appliedInProject: 'Deterministic UnconfinedTestDispatcher coroutine unit testing in AppleParityEngineTest.',
  },
  {
    index: 20,
    folderName: '20-hamen-compose_skill',
    repoSlug: 'hamen/compose_skill',
    url: 'https://github.com/hamen/compose_skill',
    category: 'Compose & UI',
    appliedInProject: 'Composable slot APIs for NavigationExpandedContent, JourneyExpandedContent, and DownloadExpandedContent.',
  },
  {
    index: 21,
    folderName: '21-skydoves-compose-performance-skills',
    repoSlug: 'skydoves/compose-performance-skills',
    url: 'https://github.com/skydoves/compose-performance-skills',
    category: 'Testing & Perf',
    appliedInProject: 'Lambda-based state reads in graphicsLayer and zero-allocation spring stepping at 120Hz.',
  },
  {
    index: 22,
    folderName: '22-Gracker-Perfetto-Skills',
    repoSlug: 'Gracker/Perfetto-Skills',
    url: 'https://github.com/Gracker/Perfetto-Skills',
    category: 'Testing & Perf',
    appliedInProject: 'Frame-time budget adherence (<8.33ms at 120Hz) and bitmap downscaling bounds (384px max).',
  },
  {
    index: 23,
    folderName: '23-droidrun-mobile-harness',
    repoSlug: 'droidrun/mobile-harness',
    url: 'https://github.com/droidrun/mobile-harness',
    category: 'Testing & Perf',
    appliedInProject: 'Automated APK verification harness and cross-posture window bounds validation.',
  },
  {
    index: 24,
    folderName: '24-obra-superpowers',
    repoSlug: 'obra/superpowers',
    url: 'https://github.com/obra/superpowers',
    category: 'Agent & Security',
    appliedInProject: 'Test-driven phase execution, systematic root-cause verification, and zero-regression discipline.',
  },
  {
    index: 25,
    folderName: '25-getsentry-skills',
    repoSlug: 'getsentry/skills',
    url: 'https://github.com/getsentry/skills',
    category: 'Agent & Security',
    appliedInProject: 'Defensive runCatching boundaries around WindowManager overlay attachment and PendingIntent dispatch.',
  },
];

export const RESEARCH_PAPERS: ResearchPaperEntry[] = [
  {
    id: 'paper-01',
    fileName: 'research papers/01-Deep-Research-Report-Apple-Dynamic-Island-Behaviour.md',
    title: 'Paper 01: Apple Dynamic Island Behaviour, Interaction Model & HIG Specification',
    subtitle: 'Comprehensive reverse-engineering study of iOS Jindo / ActivityKit geometry, gestures, physics, and lifecycle rules.',
    sections: [
      {
        heading: '1. Three Presentation States & Spatial Geometry',
        content:
          'Apple’s Dynamic Island operates across three canonical presentation states: Compact (single active background session flanking the sensor cutout with compactLeading and compactTrailing slots), Minimal (when 2–3 concurrent activities are active, splitting into a primary pill and a detached circular/capsule droplet via mitosis), and Expanded (long-press or transient alert reveal up to 160pt nominal height with a 44pt corner radius and a 4-region grid: .leading, .trailing, .center, .bottom).',
      },
      {
        heading: '2. Gesture Disambiguation: Single Tap vs. Long Press vs. Scrub Collapse',
        content:
          'A single tap (<200ms) on the Compact or Minimal island never expands the card in Apple’s native paradigm; instead, it deep-links directly into the host application (via widgetURL or PendingIntent). Pressing and holding (~350–450ms) applies an immediate 0.985x touch compression scale with light impact haptics before springing open into the Expanded card. Dragging upward on the Expanded card scrubs the collapse 1:1 with finger displacement and hands off live gesture velocity to a critically damped spring.',
      },
      {
        heading: '3. 2D Metaball Ferrofluid Physics & .keylineTint',
        content:
          'During Minimal state mitosis and coalescence, the detached secondary droplet does not simply fade in; it stretches a black ferrofluid bridge across the inter-bubble gap governed by a 2D smooth-minimum (smin) signed distance field until surface tension snaps at the threshold distance. Subtle 1pt .keylineTint borders derived from active album art or brand accent colors frame the obsidian capsule against dark wallpapers.',
      },
      {
        heading: '4. ActivityKit Lifecycle Governance & Resource Limits',
        content:
          'Every Live Activity is governed by four strict system guardrails: a 4 KB (4,096-byte) serialized payload cap to guarantee 120Hz ProMotion rendering without IPC stalls; a staleDate timestamp that dims stale telemetry when updates are delayed; an 8-hour active Dynamic Island ceiling; and a 4-hour post-end retention window in the Lock Screen / Notification Inbox.',
      },
    ],
  },
  {
    id: 'paper-02',
    fileName: 'research papers/02-Architectural-Blueprint-and-Feature-Roadmap-Apple-Dynamic-Island-Parity.md',
    title: 'Paper 02: Architectural Blueprint & 4-Phase Feature Roadmap for Android Parity',
    subtitle: 'Engineering specification mapping iOS ActivityKit & Jindo mechanics onto Android 9–16 (API 28–36) and Jetpack Compose.',
    sections: [
      {
        heading: 'Phase 1: Core Interaction & Spatial Geometry',
        content:
          'Implements InteractionMode.APPLE_PARITY vs ANDROID_CLASSIC in GesturePolicy and IslandPriorityArbiter; builds ExpandedRegionLayoutSolver for the 4-region grid (.leading, .trailing, .center, .bottom) with .belowIfTooWide wrapping and regionPriority; adds 28dp circular slot condensation in landscape (isDynamicIslandLimitedInWidth); and enforces foldable ReservedRegion crease avoidance.',
      },
      {
        heading: 'Phase 2: Fluid Physics, Gesture Scrubbing & Visual Polish',
        content:
          'Implements ScrubCollapseController for 1:1 upward drag-to-collapse interpolation and velocity-aware release handoff; adds MetaballGeometry smooth-minimum SDF and Canvas bridge rendering; extracts dominant vibrant album art colors in AlbumArtResolver for .keylineTint and AudioWaveform bars; and adds Liquid Glass surface highlights with Reduced Motion compliance.',
      },
      {
        heading: 'Phase 3: Multi-Activity Arbitration & Lifecycle Governance',
        content:
          'Implements RelevanceArbitrator and ActivityLifecyclePolicy in pure JVM :core; upgrades IslandState.Minimal and SecondaryBubblePlacement to support up to 3 concurrent activities (primary, secondary, tertiary); enforces the 4 KB payload cap, staleDate dimming, 8-hour active ceiling, and 4-hour post-end retention.',
      },
      {
        heading: 'Phase 4: Rich Ecosystem Integrations & Smart Context',
        content:
          'Ingests Android 16 (API 36) Notification.ProgressStyle and structured ongoing notifications via LiveUpdateProgressParser; adds dedicated Compose views for Turn-by-Turn Navigation (NavigationExpandedContent), Rideshare/Delivery Journeys with 4-stage milestone queues (JourneyExpandedContent), and File Downloads (DownloadExpandedContent); plus LockStatePrivacyPolicy smart lock-screen redaction and Idle Mini-Status Bar mode.',
      },
    ],
  },
];
