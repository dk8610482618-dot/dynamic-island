# Compose skills audit

The Kotlin in this repo cannot be compiled in the sandbox it was written in (no JDK, no Android
SDK, no Maven/Google repo access). The next best thing is a large, opinionated rule set — so the
implementation was audited against the Compose/Kotlin agent skills that apply to it.

This document records: which skills were used, what they flagged, what changed, and what was
deliberately **not** changed.

---

## 1. The list, sorted by what it is good for

Star counts are a popularity signal only, read at the time of the audit. What follows is based on
reading each repo's tree and its relevant `SKILL.md` / reference files, not on running the skills.

### Used for this audit

| Repo | Stars | What it gave here |
| --- | --- | --- |
| [chrisbanes/skills](https://github.com/chrisbanes/skills) | ~1.1k | The single highest-value find. `compose-animations/references/animated-content.md` documented the content-identity bug in my crossfade; `compose-performance/references/deferred-reads.md` documented the frame-rate-state-in-composition problem; `compose-state-and-effects/references/side-effects.md` confirmed the `LaunchedEffect` keying. |
| [aldefy/compose-skill](https://github.com/aldefy/compose-skill) | ~600 | `references/deprecated-patterns.md` (no `accompanist`, no `Modifier.composed`, `enableEdgeToEdge` already in use) and `references/animation.md` as an API sanity check. |
| [skydoves/compose-performance-skills](https://github.com/skydoves/compose-performance-skills) | ~520 | `modifiers/ordering-modifier-chains/SKILL.md` — the size → shape → click → padding mnemonic, used to re-read every chain; also the reminder that hoisting a `Modifier` chain via `remember` for "perf" is not a real win. |
| [hamen/compose_skill](https://github.com/hamen/compose_skill) | ~380 | `references/modifiers.md` — the modifier-parameter contract (one param, named `modifier`, applied to the outermost layout, internal modifiers after it). |
| [maxrave-dev/kotlin-footguns](https://github.com/maxrave-dev/kotlin-footguns) | ~1.8k | `CATALOG.md` — scanned for anything about gesture overlays, self-hiding children and weight clusters. Two candidates checked, both rejected as not applicable (see §4). |

### Adjacent — worth keeping for later phases

| Repo | Why it is not in this pass | When it becomes relevant |
| --- | --- | --- |
| [android/skills](https://github.com/android/skills) | Official Google pack; mostly AGP 9, CameraX, LiteRT, appfunctions — none of which this repo touches yet. | The AGP 9 upgrade will bite this build; its `agp-9-upgrade` skill is the reference. |
| [Kotlin/kotlin-agent-skills](https://github.com/Kotlin/kotlin-agent-skills) | Tooling migrations (AGP 9, CocoaPods→SPM, Gradle→toolchain plugin). | Same upgrade, plus if this becomes KMP. |
| [skydoves/android-testing-skills](https://github.com/skydoves/android-testing-skills) | This repo has JVM reducer tests but no Compose UI tests. | Adding `createComposeRule` tests for tap/drag/expansion. |
| [Gracker/Perfetto-Skills](https://github.com/Gracker/Perfetto-Skills) | Needs a device and a trace. | Verifying that the island's spring actually runs at 120fps and where it janks. |
| [droidrun/mobile-harness](https://github.com/droidrun/mobile-harness) | Needs a device. | Capturing real island transitions for measurement (see `behavior-spec.md` §7). |
| [skydashnet/material-design-3-ui-skill](https://github.com/skydashnet/material-design-3-ui-skill), [ehmo/platform-design-skills](https://github.com/ehmo/platform-design-skills) | The island deliberately does *not* follow M3 theming — it is a fidelity replica of an iOS component. Tokens would fight the spec. | If a Material-flavoured variant is ever wanted. |

### Out of scope for this repo

- **Reverse-engineering packs**: [SimoneAvogadro](https://github.com/SimoneAvogadro/android-reverse-engineering-skill) (~8.6k),
  [credittone](https://github.com/credittone/android-reverse-engineering-skill),
  [zhaoxuya520/reverse-skill](https://github.com/zhaoxuya520/reverse-skill) (~44k),
  [trailofbits/skills](https://github.com/trailofbits/skills) (~7.9k). These are for taking an APK or
  a binary apart. This repo reimplements observable behaviour from scratch; nothing here decompiles
  anything, and the scope note in `behavior-spec.md` §8 explains why that boundary is deliberate.
- **General workflow/meta packs**: [obra/superpowers](https://github.com/obra/superpowers) (~309k),
  [getsentry/skills](https://github.com/getsentry/skills). Broad methodology, not Compose-specific.
- **Overlapping Android "best practices" collections**:
  [new-silvermoon](https://github.com/new-silvermoon/awesome-android-agent-skills),
  [suchobits](https://github.com/suchobits/android-skills),
  [dpconde](https://github.com/dpconde/claude-android-skill),
  [Drjacky](https://github.com/Drjacky/claude-android-ninja),
  [lennonpetrick](https://github.com/lennonpetrick/android-engineering-skills),
  [almasumdev](https://github.com/almasumdev/awesome-kotlin-android-agent-skills),
  [haidrrrry](https://github.com/haidrrrry/compose-kotlin-agent-skills). Architecture and MVI
  guidance for app-scale projects; this is a single-component demo with one screen.

---

## 2. Findings that changed the code

### F1 — Crossfade rendered from captured outer state *(correctness)*

**Rule** (chrisbanes/skills, `compose-animations`): *"`AnimatedContent` keeps outgoing and incoming
content composed together. Render from its content-lambda target rather than captured outer state,
so each branch keeps the identity assigned to it."*

My container did exactly the wrong thing:

```kotlin
// Before — both branches read the newest presentation.
Crossfade(targetState = presentation.contentKey, ...) { _ ->
    when (presentation) { /* ... */ }
}
```

The outgoing branch was re-composed with the *new* presentation, so during a transition both
branches showed the incoming content, and the fade read as a flicker rather than a crossfade.

```kotlin
// After — each branch renders its own target; the key only decides *when* to transition.
AnimatedContent(
    targetState = presentation,
    contentKey = { it.contentKey },
    transitionSpec = { fadeIn(...) togetherWith fadeOut(...) },
) { state ->
    when (state) { /* renders `state`, never `presentation` */ }
}
```

`contentKey` also preserves the intent that was previously carried by the string key: ticking data
keeps the same shape, so a countdown updates in place instead of re-animating the card.

### F2 — Frame-rate state read in composition *(performance)*

**Rule** (chrisbanes/skills, `deferred-reads`): *"State invalidates the phase that reads it. Read
frame-rate state in layout or draw; never write observable state in a phase that invalidates the
current or an earlier phase."*

`animateDpAsState` was consumed as `Modifier.width(width)`, so every animation frame recomposed the
island container. Now the `State<Dp>` is handed to a layout-phase modifier:

```kotlin
private fun Modifier.islandSize(width: State<Dp>, height: State<Dp>) = layout { measurable, constraints ->
    val widthPx = width.value.roundToPx().coerceIn(0, constraints.maxWidth)
    val heightPx = height.value.roundToPx().coerceIn(0, constraints.maxHeight)
    val placeable = measurable.measure(Constraints.fixed(widthPx, heightPx))
    layout(widthPx, heightPx) { placeable.place(0, 0) }
}
```

An in-flight spring now invalidates layout only. `Constraints.fixed` keeps the children measured at
the true width mid-morph, instead of a stale one from the previous frame.

Honest caveat: the corner radius is still read in composition, because it feeds `clip`/`border`,
which take a `Shape` parameter. Deferring it would mean drawing the fill and hairline inside
`drawWithContent { }` — worth doing only if a trace shows it matters.

### F3 — Paused waveform kept a clock running *(performance)*

The infinite transition in `WaveformBars` ticked whether or not audio played, redrawing every frame
to paint the same static bars. The transition is now created only while playing:

```kotlin
val phase = if (isPlaying) rememberWaveformPhase() else 0f
```

### F4 — `IslandHost` used `Modifier` without importing it *(compile error)*

Found by a symbol/import sweep across all sources: `androidx.compose.ui.Modifier` was missing. This
is the kind of thing the compiler catches instantly and a review catches slowly — it is the main
reason this audit was worth doing.

### F5 — Unstable state carriers *(stability)*

`IslandState` holds a `List<IslandActivity>`, which Compose treats as unstable, so nothing taking it
could skip. Both `IslandState` and `IslandPresentation` are now `@Immutable` — a promise that is
true here, since every mutation returns a new instance.

---

## 3. Rules checked and already satisfied

- Modifier chain order: size → shape/background → pointer input; `clickable` sits above the padding
  it should include (`GlyphButton`).
- Modifier-parameter contract: one `modifier` per public composable, defaulted, applied to the
  outermost layout, internal modifiers chained after it.
- No `Modifier.composed`, no Accompanist, no `runBlocking`/`GlobalScope`.
- `LaunchedEffect` keyed by the semantic input that owns the work (`transient?.id`), not `Unit`.
- `enableEdgeToEdge()` rather than the deprecated system-Ui controller.
- Pure reducer with JVM tests, so the interaction model is testable off-device.

## 4. Rejected on purpose

| Suggestion | Why not |
| --- | --- |
| Enforce a 48dp minimum touch target on the call actions and island | The compact puck is 26dp because that is the size of the thing being replicated. `Modifier.minimumInteractiveComponentSize()` would grow the *hit* area without growing the visual — that is the right fix if this were a shipping app, but it would silently change the geometry the preview is asserting. Left as a documented trade-off. |
| `weight(1f, fill = false)` to centre a cluster (`kotlin-footguns`) | The places I use `weight` genuinely want to fill the remaining slot (title ellipsis, split halves), so `fill = false` would break the layout. |
| Self-hiding child / slot rules (`kotlin-footguns`) | Applies to components that hide themselves; the island's content is swapped by `AnimatedContent`, never hidden in place. |
| Hoist `Modifier` chains into `remember` "for perf" | skydoves' own guidance says Compose already interns structurally-equal chains; no measurement, no change. |
| Adopt M3 design tokens (`skydashnet`, `ehmo`) | The island intentionally bypasses Material theming — tokens would pull the replica away from the target. |

## 5. Still unverified

This audit is a reading of rules against source. It is not a build. Open risks, in order:

1. **Nothing has been compiled.** Import-level mistakes like F4 are now mostly swept, but type
   inference, overload resolution and `@Composable` context errors can still exist.
2. **`ExperimentalAnimationApi` opt-in is a hedge.** If `AnimatedContent(contentKey = …)` and
   `togetherWith` are non-experimental in Compose 1.7, the opt-in becomes an unnecessary-annotation
   warning, not an error.
3. **Layout-phase size modifier** is correct by inspection but has never been measured; confirm with
   layout inspector that children still measure at the mid-animation width.
4. **No UI tests.** The reducer is tested; the gestures, the press scale and the transient lifetime
   are not.
