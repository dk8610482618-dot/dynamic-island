export type IslandView =
  | "idle"
  | "music-compact"
  | "music-expanded"
  | "timer-compact"
  | "timer-expanded"
  | "ring"
  | "call-compact"
  | "charging"
  | "airpods";

export type Activity = "none" | "music" | "timer" | "call";
export type Transient = "charging" | "airpods" | null;
