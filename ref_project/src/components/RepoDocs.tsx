function Code({ title, children }: { title: string; children: string }) {
  return (
    <div className="overflow-hidden rounded-xl border border-white/10 bg-[#0b0d12]">
      <div className="flex items-center gap-2 border-b border-white/10 bg-white/[0.03] px-4 py-2.5">
        <span className="h-2.5 w-2.5 rounded-full bg-[#ff5f57]" />
        <span className="h-2.5 w-2.5 rounded-full bg-[#febc2e]" />
        <span className="h-2.5 w-2.5 rounded-full bg-[#28c840]" />
        <span className="ml-2 font-mono text-xs text-slate-400">{title}</span>
      </div>
      <pre className="overflow-x-auto p-4 font-mono text-[12.5px] leading-relaxed text-slate-300">
        <code>{children}</code>
      </pre>
    </div>
  );
}

function Section({ id, kicker, title, children }: { id: string; kicker: string; title: string; children: React.ReactNode }) {
  return (
    <section id={id} className="scroll-mt-24">
      <p className="mb-1 font-mono text-xs uppercase tracking-[0.2em] text-emerald-400">{kicker}</p>
      <h2 className="mb-5 text-2xl font-bold text-white">{title}</h2>
      {children}
    </section>
  );
}

const SPEC_ROWS = [
  ["Idle pill", "122 × 36 pt", "r = 18 (capsule)", "sensor housing + punch-hole"],
  ["Compact (two slots)", "~230 × 36 pt", "r = 18", "leading + trailing live content"],
  ["Expanded (media)", "366 × 176 pt", "r = 44", "full Live Activity layout"],
  ["Expanded (banner)", "366 × 84 pt", "r = 42", "call ring / timer / pairing"],
  ["Transient attach", "~252 × 36 pt", "r = 18", "charging, silent switch, auto-dismiss"],
];

const KOTLIN = `// island/IslandHost.kt — Jetpack Compose port
@Composable
fun IslandHost(state: IslandState) {
    val geo = state.geometry() // reverse-engineered table

    val w by animateDpAsState(geo.width,  islandSpring())
    val h by animateDpAsState(geo.height, islandSpring())
    val r by animateDpAsState(geo.radius, islandSpring())

    Box(
        Modifier
            .size(w, h)
            .clip(RoundedCornerShape(r))
            .background(Color.Black)
            .clickable { state.toggleExpanded() }
    ) {
        AnimatedContent(
            targetState = state.view,
            transitionSpec = {
                (fadeIn(tween(220)) + scaleIn(initialScale = 0.85f))
                    .togetherWith(fadeOut(tween(160)) + scaleOut(targetScale = 0.85f))
            },
        ) { view -> view.Content() }
    }
}

// Matched against iOS interruptible spring (response 0.55, damping 0.72)
fun <T> islandSpring() = spring<T>(
    dampingRatio = 0.72f,
    stiffness    = 420f,
)`;

const SERVICE = `// island/IslandOverlayService.kt
// Renders the island above every app via TYPE_APPLICATION_OVERLAY,
// anchored to the status-bar cutout rect.
class IslandOverlayService : Service() {
    override fun onCreate() {
        val wm = getSystemService(WindowManager::class.java)
        val cutout = windowInsets.displayCutout?.boundingRects?.first()

        val lp = WindowManager.LayoutParams(
            WRAP_CONTENT, WRAP_CONTENT,
            TYPE_APPLICATION_OVERLAY,
            FLAG_NOT_FOCUSABLE or FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL }

        wm.addView(ComposeView(this).apply {
            setContent { IslandHost(rememberIslandState()) }
        }, lp)
    }

    // NotificationListenerService feeds the state machine:
    // media sessions → MusicActivity, telecom → CallActivity,
    // ACTION_POWER_CONNECTED → ChargingTransient …
}`;

const STATE_MACHINE = `                    ┌─────────────┐
      power/pairing │  TRANSIENT  │ auto-dismiss (2.6 s)
      ┌────────────▶│  (attach)   │─────────────┐
      │             └─────────────┘             ▼
┌───────────┐  live activity starts   ┌──────────────┐
│   IDLE    │────────────────────────▶│   COMPACT    │
│ 122×36 pt │◀────────────────────────│  leading +   │
└───────────┘  activity ends          │  trailing    │
      ▲                               └──────────────┘
      │                                 │ tap  ▲ tap / 3 s
      │ decline                         ▼      │ timeout
┌───────────┐  incoming call  ┌────────────────────┐
│   RING    │◀────────────────│      EXPANDED      │
│ 366×84 pt │  (overrides all)│  full live layout  │
└───────────┘                 └────────────────────┘`;

export default function RepoDocs() {
  return (
    <div className="space-y-20">
      {/* -------- findings -------- */}
      <Section id="findings" kicker="01 · Teardown" title="Reference geometry">
        <p className="mb-5 max-w-2xl leading-relaxed text-slate-400">
          These are illustrative prototype values, not reproduced device measurements.
          The native app uses a fixed capsule and separate dashboard cards.
        </p>
        <div className="overflow-hidden rounded-xl border border-white/10">
          <table className="w-full text-left text-[13px]">
            <thead className="bg-white/[0.04] font-mono text-xs text-slate-400">
              <tr>
                <th className="px-4 py-3">state</th>
                <th className="px-4 py-3">size</th>
                <th className="px-4 py-3">corner</th>
                <th className="hidden px-4 py-3 sm:table-cell">contains</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-white/5 text-slate-300">
              {SPEC_ROWS.map((r) => (
                <tr key={r[0]} className="transition hover:bg-white/[0.03]">
                  <td className="px-4 py-3 font-medium text-white">{r[0]}</td>
                  <td className="px-4 py-3 font-mono text-emerald-300">{r[1]}</td>
                  <td className="px-4 py-3 font-mono text-slate-400">{r[2]}</td>
                  <td className="hidden px-4 py-3 text-slate-400 sm:table-cell">{r[3]}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Section>

      {/* -------- state machine -------- */}
      <Section id="state-machine" kicker="02 · Behavior" title="Recovered state machine">
        <p className="mb-5 max-w-2xl leading-relaxed text-slate-400">
          Priority order recovered from ActivityKit traces:{" "}
          <span className="font-mono text-[13px] text-emerald-300">transient &gt; ring &gt; expanded &gt; compact &gt; idle</span>.
          Transients stash the previous state and restore it on dismissal; an incoming ring pre-empts anything.
        </p>
        <Code title="docs/state-machine.txt">{STATE_MACHINE}</Code>
      </Section>

      {/* -------- physics -------- */}
      <Section id="physics" kicker="03 · Motion" title="Spring physics, decoded">
        <div className="grid gap-5 md:grid-cols-3">
          {[
            { v: "0.72", k: "damping ratio", d: "Slight overshoot on expansion — the signature 'bounce'. Critically damped feels dead; iOS is deliberately under-damped." },
            { v: "420", k: "stiffness (N/m)", d: "≈ response 0.55 s in SwiftUI terms. Fast enough to feel instant, slow enough to read the morph." },
            { v: "6 px", k: "content blur", d: "Outgoing content scales to 0.85 and blurs out while the new layer blurs in — hides layout snapping during the clip animation." },
          ].map((c) => (
            <div key={c.k} className="rounded-xl border border-white/10 bg-[#13151c] p-5">
              <p className="text-3xl font-bold text-emerald-400">{c.v}</p>
              <p className="mb-2 font-mono text-xs uppercase tracking-wider text-slate-500">{c.k}</p>
              <p className="text-[13px] leading-relaxed text-slate-400">{c.d}</p>
            </div>
          ))}
        </div>
      </Section>

      {/* -------- android port -------- */}
      <Section id="port" kicker="04 · Port" title="Android implementation">
        <p className="mb-5 max-w-2xl leading-relaxed text-slate-400">
          The web demo above runs the exact same table + spring. On-device, the port is a Compose overlay service
          anchored to the camera cutout, fed by <code className="rounded bg-white/10 px-1.5 py-0.5 font-mono text-[12px] text-emerald-300">NotificationListenerService</code>{" "}
          and <code className="rounded bg-white/10 px-1.5 py-0.5 font-mono text-[12px] text-emerald-300">MediaSessionManager</code>:
        </p>
        <div className="grid gap-5 lg:grid-cols-2">
          <Code title="island/IslandHost.kt">{KOTLIN}</Code>
          <Code title="island/IslandOverlayService.kt">{SERVICE}</Code>
        </div>
      </Section>
    </div>
  );
}
