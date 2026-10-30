import { useEffect, useRef, useState } from "react";
import DynamicIsland from "./island/DynamicIsland";
import type { Activity, IslandView, Transient } from "./island/types";

/* ------------------------------------------------------------------ */
/*  Demo state machine — mirrors the decompiled ActivityKit priority:  */
/*  transient > incoming ring > live activity (expanded|compact) > idle */
/* ------------------------------------------------------------------ */
function useIslandDemo() {
  const [activity, setActivity] = useState<Activity>("none");
  const [expanded, setExpanded] = useState(false);
  const [incoming, setIncoming] = useState(false);
  const [transient, setTransient] = useState<Transient>(null);
  const [musicPlaying, setMusicPlaying] = useState(true);
  const [musicProgress, setMusicProgress] = useState(0.22);
  const [timerLeft, setTimerLeft] = useState(15 * 60);
  const [callElapsed, setCallElapsed] = useState(0);
  const [battery, setBattery] = useState(68);
  const transientTimeout = useRef<ReturnType<typeof setTimeout> | null>(null);

  /* 1 Hz tick for live content */
  useEffect(() => {
    const id = setInterval(() => {
      setMusicProgress((v) => (musicPlaying && activity === "music" ? (v + 1 / 212) % 1 : v));
      setTimerLeft((v) => (activity === "timer" ? Math.max(0, v - 1) : v));
      setCallElapsed((v) => (activity === "call" && !incoming ? v + 1 : v));
    }, 1000);
    return () => clearInterval(id);
  }, [activity, incoming, musicPlaying]);

  const showTransient = (t: Exclude<Transient, null>, ms: number) => {
    if (transientTimeout.current) clearTimeout(transientTimeout.current);
    setTransient(t);
    transientTimeout.current = setTimeout(() => setTransient(null), ms);
  };

  const view: IslandView = transient
    ? transient
    : incoming
      ? "ring"
      : activity === "none"
        ? "idle"
        : expanded
          ? (`${activity}-expanded` as IslandView)
          : (`${activity}-compact` as IslandView);

  /* call has no expanded layout in this demo — clamp */
  const resolvedView: IslandView = view === ("call-expanded" as IslandView) ? "call-compact" : view;

  return {
    view: resolvedView,
    musicPlaying,
    musicProgress,
    timerLeft,
    callElapsed,
    battery,
    actions: {
      tapIsland: () => {
        if (transient || incoming) return;
        if (activity !== "none") setExpanded((e) => !e);
      },
      startMusic: () => {
        setIncoming(false);
        setActivity("music");
        setMusicPlaying(true);
        setExpanded(true);
      },
      toggleMusic: () => setMusicPlaying((v) => !v),
      startTimer: () => {
        setIncoming(false);
        setActivity("timer");
        setTimerLeft(15 * 60);
        setExpanded(false);
      },
      cancelTimer: () => {
        setActivity("none");
        setExpanded(false);
      },
      incomingCall: () => {
        setTransient(null);
        setIncoming(true);
        setActivity("call");
        setCallElapsed(0);
      },
      acceptCall: () => {
        setIncoming(false);
        setExpanded(false);
      },
      declineCall: () => {
        setIncoming(false);
        setActivity("none");
        setExpanded(false);
      },
      charge: () => {
        setBattery((b) => Math.min(100, b + 1));
        showTransient("charging", 2600);
      },
      airpods: () => showTransient("airpods", 3000),
      reset: () => {
        setActivity("none");
        setIncoming(false);
        setTransient(null);
        setExpanded(false);
      },
    },
  };
}

/* ------------------------------------------------------------------ */
/*  Android phone frame                                                */
/* ------------------------------------------------------------------ */
const APPS = [
  { icon: "📞", label: "Phone" },
  { icon: "💬", label: "Messages" },
  { icon: "🌐", label: "Chrome" },
  { icon: "📷", label: "Camera" },
  { icon: "🎵", label: "Music" },
  { icon: "🗺️", label: "Maps" },
  { icon: "⏱️", label: "Clock" },
  { icon: "⚙️", label: "Settings" },
];

export default function PhoneDemo() {
  const demo = useIslandDemo();
  const now = new Date();
  const clock = now.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });

  return (
    <div className="flex flex-col items-center gap-8 lg:flex-row lg:items-start lg:gap-12">
      {/* ---------- phone ---------- */}
      <div className="relative h-[760px] w-[372px] shrink-0 rounded-[52px] bg-[#1c1d22] p-[10px] shadow-[0_40px_90px_-20px_rgba(0,0,0,0.7),inset_0_0_0_2px_rgba(255,255,255,0.06)]">
        {/* side buttons */}
        <div className="absolute -right-[3px] top-40 h-20 w-[3px] rounded-r bg-[#2c2d33]" />
        <div className="absolute -right-[3px] top-64 h-12 w-[3px] rounded-r bg-[#2c2d33]" />

        <div className="relative h-full w-full overflow-hidden rounded-[44px] bg-black">
          <img src="images/wallpaper.jpg" alt="" className="absolute inset-0 h-full w-full object-cover" />
          <div className="absolute inset-0 bg-gradient-to-b from-black/30 via-transparent to-black/50" />

          {/* status bar */}
          <div className="absolute inset-x-0 top-0 z-20 flex items-center justify-between px-7 pt-4 text-[12px] font-medium text-white/90">
            <span className="tabular-nums">{clock}</span>
            <div className="flex items-center gap-1.5">
              <svg viewBox="0 0 16 12" className="h-3 w-4 fill-white/90"><path d="M1 9h2v3H1zm4-3h2v6H5zm4-3h2v9H9zm4-3h2v12h-2z" /></svg>
              <svg viewBox="0 0 24 24" className="h-3.5 w-3.5 fill-white/90"><path d="M12 21 1.8 8.5A16 16 0 0 1 12 5a16 16 0 0 1 10.2 3.5z" /></svg>
              <span className="tabular-nums">{demo.battery}%</span>
            </div>
          </div>

          {/* THE ISLAND */}
          <DynamicIsland
            view={demo.view}
            musicPlaying={demo.musicPlaying}
            musicProgress={demo.musicProgress}
            timerLeft={demo.timerLeft}
            callElapsed={demo.callElapsed}
            batteryLevel={demo.battery}
            onTap={demo.actions.tapIsland}
            onToggleMusic={demo.actions.toggleMusic}
            onCancelTimer={demo.actions.cancelTimer}
            onAcceptCall={demo.actions.acceptCall}
            onDeclineCall={demo.actions.declineCall}
          />

          {/* lock-screen style clock */}
          <div className="absolute inset-x-0 top-[26%] text-center text-white">
            <p className="text-[15px] font-medium text-white/70">
              {now.toLocaleDateString([], { weekday: "long", month: "short", day: "numeric" })}
            </p>
            <p className="text-[76px] font-semibold leading-none tracking-tight tabular-nums drop-shadow-lg">
              {clock}
            </p>
          </div>

          {/* app dock */}
          <div className="absolute inset-x-5 bottom-10 rounded-[30px] bg-white/10 p-4 backdrop-blur-xl">
            <div className="grid grid-cols-4 gap-y-4">
              {APPS.map((a) => (
                <div key={a.label} className="flex flex-col items-center gap-1">
                  <div className="flex h-12 w-12 items-center justify-center rounded-2xl bg-white/15 text-[22px] backdrop-blur transition active:scale-90">
                    {a.icon}
                  </div>
                  <span className="text-[10px] text-white/70">{a.label}</span>
                </div>
              ))}
            </div>
          </div>

          {/* gesture bar */}
          <div className="absolute inset-x-0 bottom-2 flex justify-center">
            <div className="h-[4px] w-28 rounded-full bg-white/70" />
          </div>
        </div>
      </div>

      {/* ---------- control panel ---------- */}
      <div className="w-full max-w-sm">
        <div className="rounded-2xl border border-white/10 bg-[#13151c] p-5">
          <div className="mb-1 flex items-center gap-2">
            <span className="h-2 w-2 rounded-full bg-emerald-400" />
            <h3 className="font-mono text-sm font-semibold text-white">event_injector.ts</h3>
          </div>
          <p className="mb-5 font-mono text-xs leading-relaxed text-slate-500">
            // Fire system events into the replicated island.
            <br />
            // Tap the island to toggle compact ⇄ expanded.
          </p>

          <div className="grid grid-cols-2 gap-2.5">
            {[
              { label: "▶ Play music", fn: demo.actions.startMusic, cls: "hover:border-orange-400/50 hover:text-orange-300" },
              { label: "⏱ Start timer", fn: demo.actions.startTimer, cls: "hover:border-amber-400/50 hover:text-amber-300" },
              { label: "📞 Incoming call", fn: demo.actions.incomingCall, cls: "hover:border-emerald-400/50 hover:text-emerald-300" },
              { label: "⚡ Plug charger", fn: demo.actions.charge, cls: "hover:border-green-400/50 hover:text-green-300" },
              { label: "🎧 Connect buds", fn: demo.actions.airpods, cls: "hover:border-sky-400/50 hover:text-sky-300" },
              { label: "✕ Reset island", fn: demo.actions.reset, cls: "hover:border-rose-400/50 hover:text-rose-300" },
            ].map((b) => (
              <button
                key={b.label}
                onClick={b.fn}
                className={`rounded-xl border border-white/10 bg-white/[0.04] px-3 py-3 text-left font-mono text-[13px] text-slate-300 transition active:scale-95 ${b.cls}`}
              >
                {b.label}
              </button>
            ))}
          </div>

          <div className="mt-5 rounded-xl bg-black/40 p-3.5 font-mono text-[11px] leading-relaxed text-slate-400">
            <span className="text-slate-600">state →</span>{" "}
            <span className="text-emerald-400">"{demo.view}"</span>
            <br />
            <span className="text-slate-600">spring →</span> stiffness 420 · damping 32 · mass 0.9
          </div>
        </div>

        <div className="mt-4 rounded-2xl border border-white/10 bg-[#13151c] p-5">
          <h4 className="mb-2 text-sm font-semibold text-white">Try the gestures</h4>
          <ul className="space-y-1.5 text-[13px] leading-relaxed text-slate-400">
            <li>• Tap island while music plays → expanded player</li>
            <li>• Accept the call → shrinks to compact call pill</li>
            <li>• Transients (charger / buds) auto-dismiss & restore</li>
            <li>• Ring state overrides any live activity</li>
          </ul>
        </div>
      </div>
    </div>
  );
}
