import { AnimatePresence, motion } from "framer-motion";
import type { IslandView } from "./types";

/* ------------------------------------------------------------------ */
/*  Reverse-engineered geometry (pt values measured from iPhone 15 Pro,
    scaled 1:1 into CSS px for this 390px-wide viewport mock)          */
/* ------------------------------------------------------------------ */
const GEOMETRY: Record<string, { w: number; h: number; r: number }> = {
  idle: { w: 122, h: 36, r: 18 },
  "music-compact": { w: 236, h: 36, r: 18 },
  "timer-compact": { w: 228, h: 36, r: 18 },
  "call-compact": { w: 224, h: 36, r: 18 },
  "music-expanded": { w: 366, h: 176, r: 44 },
  "timer-expanded": { w: 366, h: 84, r: 42 },
  ring: { w: 366, h: 84, r: 42 },
  charging: { w: 252, h: 36, r: 18 },
  airpods: { w: 366, h: 84, r: 42 },
};

/* iOS-equivalent interruptible spring: response 0.55 / damping 0.72  */
const SPRING = { type: "spring" as const, stiffness: 420, damping: 32, mass: 0.9 };
const CONTENT = {
  initial: { opacity: 0, scale: 0.85, filter: "blur(6px)" },
  animate: { opacity: 1, scale: 1, filter: "blur(0px)" },
  exit: { opacity: 0, scale: 0.85, filter: "blur(6px)" },
  transition: { duration: 0.22 },
};

const fmt = (s: number) =>
  `${Math.floor(s / 60)}:${String(Math.floor(s % 60)).padStart(2, "0")}`;

/* ------------------------------------------------------------------ */
/*  Small shared bits                                                  */
/* ------------------------------------------------------------------ */
function Waveform({ color = "#fa5c42", playing = true }: { color?: string; playing?: boolean }) {
  return (
    <div className="flex h-4 items-end gap-[2px]">
      {[0, 1, 2, 3, 4].map((i) => (
        <span
          key={i}
          className="w-[3px] rounded-full"
          style={{
            backgroundColor: color,
            height: playing ? undefined : 4,
            animation: playing ? `wave 0.9s ease-in-out ${i * 0.12}s infinite` : "none",
          }}
        />
      ))}
    </div>
  );
}

function PhoneIcon({ className = "" }: { className?: string }) {
  return (
    <svg viewBox="0 0 24 24" fill="currentColor" className={className}>
      <path d="M6.6 10.8a15.1 15.1 0 0 0 6.6 6.6l2.2-2.2a1 1 0 0 1 1-.25 11.4 11.4 0 0 0 3.6.58 1 1 0 0 1 1 1V20a1 1 0 0 1-1 1A17 17 0 0 1 3 4a1 1 0 0 1 1-1h3.5a1 1 0 0 1 1 1 11.4 11.4 0 0 0 .57 3.6 1 1 0 0 1-.25 1z" />
    </svg>
  );
}

/* ------------------------------------------------------------------ */
/*  Content layers for every state                                     */
/* ------------------------------------------------------------------ */
function MusicCompact() {
  return (
    <div className="flex h-9 w-full items-center justify-between px-2">
      <img src="images/album.jpg" alt="" className="h-6 w-6 rounded-[7px] object-cover" />
      <Waveform />
    </div>
  );
}

function MusicExpanded({ progress, playing, onToggle }: { progress: number; playing: boolean; onToggle: () => void }) {
  return (
    <div className="flex h-full w-full flex-col justify-between px-5 py-4">
      <div className="flex items-center gap-3.5">
        <motion.img
          src="images/album.jpg"
          alt="album art"
          className="h-14 w-14 rounded-2xl object-cover shadow-lg"
          animate={{ scale: playing ? 1 : 0.92 }}
          transition={SPRING}
        />
        <div className="min-w-0 flex-1">
          <p className="truncate text-[15px] font-semibold text-white">Midnight Drive</p>
          <p className="truncate text-[13px] text-white/50">Neon Arcadia</p>
        </div>
        <Waveform playing={playing} />
      </div>

      <div className="flex items-center gap-2 text-[11px] font-medium tabular-nums text-white/40">
        <span>{fmt(progress * 212)}</span>
        <div className="h-[5px] flex-1 overflow-hidden rounded-full bg-white/15">
          <div className="h-full rounded-full bg-white/80 transition-[width] duration-500" style={{ width: `${progress * 100}%` }} />
        </div>
        <span>-{fmt(212 - progress * 212)}</span>
      </div>

      <div className="flex items-center justify-center gap-10 text-white">
        <button aria-label="previous" className="transition active:scale-90" onClick={(e) => e.stopPropagation()}>
          <svg viewBox="0 0 24 24" fill="currentColor" className="h-6 w-6"><path d="M6 6h2v12H6zm3.5 6L18 6v12z" /></svg>
        </button>
        <button
          aria-label="play pause"
          className="transition active:scale-90"
          onClick={(e) => { e.stopPropagation(); onToggle(); }}
        >
          {playing ? (
            <svg viewBox="0 0 24 24" fill="currentColor" className="h-8 w-8"><path d="M6 5h4v14H6zm8 0h4v14h-4z" /></svg>
          ) : (
            <svg viewBox="0 0 24 24" fill="currentColor" className="h-8 w-8"><path d="M8 5v14l11-7z" /></svg>
          )}
        </button>
        <button aria-label="next" className="transition active:scale-90" onClick={(e) => e.stopPropagation()}>
          <svg viewBox="0 0 24 24" fill="currentColor" className="h-6 w-6"><path d="M16 6h2v12h-2zM6 18V6l8.5 6z" /></svg>
        </button>
      </div>
    </div>
  );
}

function TimerCompact({ left }: { left: number }) {
  return (
    <div className="flex h-9 w-full items-center justify-between px-2.5">
      <svg viewBox="0 0 24 24" fill="none" stroke="#fe9f0a" strokeWidth={2} strokeLinecap="round" className="h-[18px] w-[18px]">
        <circle cx="12" cy="13" r="8" />
        <path d="M12 9v4l2.5 2.5M9 2h6" />
      </svg>
      <span className="text-[15px] font-semibold tabular-nums text-[#fe9f0a]">{fmt(left)}</span>
    </div>
  );
}

function TimerExpanded({ left, onCancel }: { left: number; onCancel: () => void }) {
  return (
    <div className="flex h-full w-full items-center gap-4 px-5">
      <button
        aria-label="cancel timer"
        onClick={(e) => { e.stopPropagation(); onCancel(); }}
        className="flex h-12 w-12 items-center justify-center rounded-full bg-white/15 text-white transition active:scale-90"
      >
        <svg viewBox="0 0 24 24" fill="currentColor" className="h-5 w-5"><path d="M6 6l12 12M18 6L6 18" stroke="currentColor" strokeWidth={2.4} strokeLinecap="round" /></svg>
      </button>
      <div className="flex-1">
        <p className="text-[12px] font-medium text-[#fe9f0a]/80">Timer</p>
        <p className="text-[13px] text-white/40">Pomodoro focus</p>
      </div>
      <span className="text-[34px] font-light tabular-nums tracking-tight text-[#fe9f0a]">{fmt(left)}</span>
    </div>
  );
}

function Ring({ onAccept, onDecline }: { onAccept: () => void; onDecline: () => void }) {
  return (
    <div className="flex h-full w-full items-center gap-3 px-4">
      <div className="flex h-12 w-12 items-center justify-center rounded-full bg-gradient-to-br from-indigo-400 to-violet-600 text-[17px] font-semibold text-white">
        AK
      </div>
      <div className="min-w-0 flex-1">
        <p className="text-[12px] text-white/50">mobile</p>
        <p className="truncate text-[16px] font-semibold text-white">Ada Kernel</p>
      </div>
      <button
        aria-label="decline"
        onClick={(e) => { e.stopPropagation(); onDecline(); }}
        className="flex h-12 w-12 items-center justify-center rounded-full bg-[#ff453a] text-white transition active:scale-90"
      >
        <PhoneIcon className="h-5 w-5 rotate-[135deg]" />
      </button>
      <button
        aria-label="accept"
        onClick={(e) => { e.stopPropagation(); onAccept(); }}
        className="flex h-12 w-12 animate-pulse items-center justify-center rounded-full bg-[#30d158] text-white transition active:scale-90"
      >
        <PhoneIcon className="h-5 w-5" />
      </button>
    </div>
  );
}

function CallCompact({ elapsed }: { elapsed: number }) {
  return (
    <div className="flex h-9 w-full items-center justify-between px-2.5">
      <PhoneIcon className="h-[16px] w-[16px] text-[#30d158]" />
      <span className="text-[14px] font-semibold tabular-nums text-[#30d158]">{fmt(elapsed)}</span>
    </div>
  );
}

function Charging({ level }: { level: number }) {
  return (
    <div className="flex h-9 w-full items-center justify-between px-3">
      <span className="text-[13px] font-medium text-white/80">Charging</span>
      <div className="flex items-center gap-1.5">
        <span className="text-[13px] font-semibold text-[#30d158]">{level}%</span>
        <svg viewBox="0 0 24 24" fill="#30d158" className="h-4 w-4">
          <path d="M13 2 4 14h6l-1 8 9-12h-6z" />
        </svg>
      </div>
    </div>
  );
}

function AirPods() {
  return (
    <div className="flex h-full w-full items-center gap-4 px-5">
      <div className="flex h-12 w-12 items-center justify-center rounded-full bg-white/10 text-2xl">🎧</div>
      <div className="flex-1">
        <p className="text-[15px] font-semibold text-white">Pixel Buds Pro</p>
        <p className="text-[13px] text-white/50">Connected</p>
      </div>
      <div className="flex items-center gap-1.5">
        <span className="text-[13px] font-semibold text-[#30d158]">87%</span>
        <div className="relative h-[11px] w-[22px] rounded-[3px] border border-white/40">
          <div className="absolute inset-[2px] rounded-[1px] bg-[#30d158]" style={{ width: "80%" }} />
          <div className="absolute -right-[3px] top-[3px] h-[5px] w-[2px] rounded-r bg-white/40" />
        </div>
      </div>
    </div>
  );
}

/* ------------------------------------------------------------------ */
/*  The island itself                                                  */
/* ------------------------------------------------------------------ */
export interface DynamicIslandProps {
  view: IslandView;
  musicProgress: number;
  musicPlaying: boolean;
  timerLeft: number;
  callElapsed: number;
  batteryLevel: number;
  onTap: () => void;
  onToggleMusic: () => void;
  onCancelTimer: () => void;
  onAcceptCall: () => void;
  onDeclineCall: () => void;
}

export default function DynamicIsland(p: DynamicIslandProps) {
  const g = GEOMETRY[p.view] ?? GEOMETRY.idle;

  return (
    <div className="pointer-events-none absolute inset-x-0 top-[10px] z-30 flex justify-center">
      <motion.div
        layout
        onClick={p.onTap}
        animate={{ width: g.w, height: g.h, borderRadius: g.r }}
        transition={SPRING}
        whileTap={{ scale: 0.97 }}
        className="pointer-events-auto relative cursor-pointer overflow-hidden bg-black shadow-[0_6px_24px_rgba(0,0,0,0.55)]"
        style={{ willChange: "width,height,border-radius" }}
      >
        {/* camera punch-hole always visible in idle, swallowed otherwise */}
        <AnimatePresence mode="popLayout" initial={false}>
          <motion.div key={p.view} {...CONTENT} className="flex h-full w-full items-center justify-center">
            {p.view === "idle" && (
              <div className="flex h-9 w-full items-center justify-end pr-[14px]">
                <div className="h-[14px] w-[14px] rounded-full bg-[#0b0b10] ring-1 ring-white/10" />
              </div>
            )}
            {p.view === "music-compact" && <MusicCompact />}
            {p.view === "music-expanded" && (
              <MusicExpanded progress={p.musicProgress} playing={p.musicPlaying} onToggle={p.onToggleMusic} />
            )}
            {p.view === "timer-compact" && <TimerCompact left={p.timerLeft} />}
            {p.view === "timer-expanded" && <TimerExpanded left={p.timerLeft} onCancel={p.onCancelTimer} />}
            {p.view === "ring" && <Ring onAccept={p.onAcceptCall} onDecline={p.onDeclineCall} />}
            {p.view === "call-compact" && <CallCompact elapsed={p.callElapsed} />}
            {p.view === "charging" && <Charging level={p.batteryLevel} />}
            {p.view === "airpods" && <AirPods />}
          </motion.div>
        </AnimatePresence>
      </motion.div>
    </div>
  );
}
