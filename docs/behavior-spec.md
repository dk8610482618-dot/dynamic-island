# Dynamic Island — behaviour spec

What this recreation claims to model, what it copies by eye, and what it does not claim at all.

The rule this document follows: **every number is tagged**. `observed` means the behaviour is
visible in normal use of an iPhone and can be checked by anyone with a device in hand. `derived`
means it follows from an observed rule. `invented` means it was chosen to look right here, and a
fidelity pass should replace it.

---

## 1. Shapes

| Shape | Behaviour | Status |
| --- | --- | --- |
| Collapsed | A black capsule centred at the top of the display, containing the camera and TrueDepth sensor. No content. | observed |
| Compact | One activity, in a pill of the same height as the cutout. Width grows from the centre. | observed |
| Expanded | A large rounded card (roughly the width of the screen minus margins) that pushes down from the top edge. | observed |
| Split | Two activities share the pill; the camera stays between them. | observed |

| Measurement | Value used | Status |
| --- | --- | --- |
| Cutout height | 37dp | derived — matches the compact pill height, which users can see is identical |
| Cutout width (idle) | 126dp | invented — visually matched |
| Expanded width | 371dp | invented |
| Expanded corner radius | 44dp | invented |
| Pill corner radius | height / 2 (18.5dp) | observed — compact is always a full pill |

## 2. Arbitration

- One activity may be **expanded** at a time. `observed`.
- Two activities may run simultaneously; with two, the island shows **split** rather than picking
  one. `observed` (a call and a song coexist).
- Activities are ranked. Rank, not recency, decides the leading slot. `derived`.
- Priority table (100 call, 80 navigation, 60 music, 55 timer, 20 charging, 10 unlock) is
  `invented` — the ordering is plausible from what outranks what on device, the exact numbers are
  ours.
- `SPLIT_MIN_PRIORITY = 50` is `invented`. It encodes the observed rule that low-salience activities
  (charging) do not split the pill with a peer.

## 3. Transitions

| Transition | Behaviour | Status |
| --- | --- | --- |
| Width / height | Spring with a visible overshoot, ~0.4–0.5s to settle | observed qualitatively |
| Corner radius | Morphs separately, damper than the size | observed qualitatively |
| Content | Fades and scales in, arriving slightly after the container starts moving | observed qualitatively |
| Data updates | Do **not** re-animate; a countdown ticks in place | observed |

Concrete numbers (damping 0.74 / stiffness 420 for size, 0.9 / 520 for radius, 190ms crossfade) are
`invented`. They were tuned to match the *feel*; no frame timings were extracted.

## 4. Content, per activity

| Activity | Compact | Expanded |
| --- | --- | --- |
| Music | artwork, title, live level bars | artwork, title/artist, scrubber, elapsed/remaining, transport |
| Call | avatar, name, duration, decline/accept | eyebrow label, name, type, two large actions with captions |
| Timer | progress ring, remaining | large ring, remaining + total, pause/stop |
| Charging | bolt, percentage, charge mode | large percentage, battery graphic, mode |
| Navigation | manoeuvre tile, distance, ETA | manoeuvre, road, divider, ETA + arrival, End |
| Face ID | check badge, "Unlocked" | larger badge, label |

Layouts and copy are `invented` in their specifics; the *sets* of elements are `observed`.

## 5. Gestures

- Tap toggles: collapsed/compact → expanded, expanded → compact. `observed`.
- Drag down collapses; drag up expands. `observed` qualitatively.
- Swipe horizontally to dismiss a live activity is **not implemented**. `observed` on device, out of
  scope here.

## 6. Not modelled

- Real Live Activity bridging (`Notification.ProgressStyle`, Android 16 live updates).
- Audio session, telephony, location — every activity is driven by the demo loop, not a real sensor.
- iOS-specific materials: the island's exact black, its inner shadow, blur bleeding of wallpaper
  under the expanded card.
- Haptics.
- Accessibility semantics beyond the island's role/`aria-label` equivalent in the preview.

## 7. How to verify this properly

Everything tagged `invented` is a guess that a device could settle. The intended workflow, and the
reason this repo started from a pointer to [morluto/rea](https://github.com/morluto/rea):

1. Capture the island's transitions on a real device (screen recording at 120fps or 60fps).
2. Measure the settled frames — pixel widths and corner radii at rest are stable and unambiguous.
3. Fit the springs: overshoot ratio gives damping, time-to-settle gives stiffness.
4. Diff against `IslandSize` and `IslandMotion`; replace the invented numbers with measured ones.

**None of that analysis has been run.** No screen recordings were captured, no binaries were
inspected, and rea was not invoked for this build. The numbers above are a designer's estimate, and
this section exists so nobody mistakes them for measurements.

## 8. Scope discipline

Worth stating plainly: this is a UI recreation, not a crack of a proprietary feature. It copies
observable *behaviour* — geometry, choreography, information hierarchy — and it deliberately
reimplements everything from scratch in Compose rather than reproducing Apple's assets, fonts,
icons or code. The glyphs here are hand-drawn Canvas paths, the album art is a gradient, and the
telephony and audio are simulated.
