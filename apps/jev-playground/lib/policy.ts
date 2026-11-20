// Escalation policy: OUR code decides, the detectors only measure.
// Smooths each signal with an asymmetric EMA (quick to worry, slow to calm down) so a single
// spike ("I bought Emma a gift card") doesn't hijack a call, but a sustained pattern escalates fast.

export type Level = "calm" | "watch" | "alert" | "takeover";

export interface PolicyConfig {
  /** EMA weight when the new value is higher than the current (rising) */
  alphaUp: number;
  /** EMA weight when the new value is lower (falling) */
  alphaDown: number;
  /** smoothed risk at which Pip quietly watches (UI only) */
  watchThreshold: number;
  /** smoothed risk at which Pip texts the trusted contact */
  alertThreshold: number;
  /** smoothed risk at which Pip takes over the call */
  takeoverThreshold: number;
  /** take over if smoothed payment AND urgency are both above this */
  comboThreshold: number;
  /** require at least this many scored updates before a takeover (avoid first-chunk misfires) */
  minUpdatesForTakeover: number;
  /** once taken over, stay taken over */
  stickyTakeover: boolean;
}

export const DEFAULT_POLICY: PolicyConfig = {
  alphaUp: 0.6,
  alphaDown: 0.3,
  watchThreshold: 0.3,
  alertThreshold: 0.5,
  takeoverThreshold: 0.8,
  comboThreshold: 0.65,
  minUpdatesForTakeover: 2,
  stickyTakeover: true,
};

export interface Signals {
  risk: number;
  payment: number;
  urgency: number;
  /** set by detectors that accumulate over the whole call themselves (e.g. TJ's rules): no smoothing */
  directLevel?: Level;
}

export interface PolicyState {
  updates: number;
  smoothed: Signals;
  level: Level;
  /** highest level reached during the call */
  maxLevel: Level;
  reason: string;
  /** index of the update at which each level was first reached */
  firstReached: Partial<Record<Level, number>>;
}

const ORDER: Level[] = ["calm", "watch", "alert", "takeover"];
export const levelRank = (l: Level) => ORDER.indexOf(l);

export function initialPolicyState(): PolicyState {
  return {
    updates: 0,
    smoothed: { risk: 0, payment: 0, urgency: 0 },
    level: "calm",
    maxLevel: "calm",
    reason: "",
    firstReached: { calm: 0 },
  };
}

const ema = (prev: number, x: number, c: PolicyConfig) => prev + (x > prev ? c.alphaUp : c.alphaDown) * (x - prev);

export function stepPolicy(state: PolicyState, s: Signals, cfg: PolicyConfig = DEFAULT_POLICY): PolicyState {
  if (s.directLevel) {
    const level = levelRank(s.directLevel) > levelRank(state.level) ? s.directLevel : state.level; // score only grows
    const updates = state.updates + 1;
    const maxLevel = levelRank(level) > levelRank(state.maxLevel) ? level : state.maxLevel;
    const firstReached = { ...state.firstReached };
    for (const l of ORDER) if (levelRank(l) <= levelRank(level) && firstReached[l] === undefined) firstReached[l] = updates;
    return { updates, smoothed: { risk: s.risk, payment: s.payment, urgency: s.urgency }, level, maxLevel, reason: `rule score → ${level}`, firstReached };
  }
  const smoothed: Signals = {
    risk: ema(state.smoothed.risk, s.risk, cfg),
    payment: ema(state.smoothed.payment, s.payment, cfg),
    urgency: ema(state.smoothed.urgency, s.urgency, cfg),
  };
  const updates = state.updates + 1;
  let level: Level = "calm";
  let reason = "normal";
  if (smoothed.risk >= cfg.watchThreshold) [level, reason] = ["watch", `risk ${smoothed.risk.toFixed(2)} ≥ watch ${cfg.watchThreshold}`];
  if (smoothed.risk >= cfg.alertThreshold) [level, reason] = ["alert", `risk ${smoothed.risk.toFixed(2)} ≥ alert ${cfg.alertThreshold}`];
  if (updates >= cfg.minUpdatesForTakeover) {
    if (smoothed.risk >= cfg.takeoverThreshold) [level, reason] = ["takeover", `risk ${smoothed.risk.toFixed(2)} ≥ takeover ${cfg.takeoverThreshold}`];
    else if (smoothed.payment >= cfg.comboThreshold && smoothed.urgency >= cfg.comboThreshold && smoothed.risk >= cfg.alertThreshold)
      [level, reason] = ["takeover", `payment ${smoothed.payment.toFixed(2)} + urgency ${smoothed.urgency.toFixed(2)} both ≥ ${cfg.comboThreshold}`];
  }
  if (cfg.stickyTakeover && state.level === "takeover") [level, reason] = ["takeover", state.reason];
  const maxLevel = levelRank(level) > levelRank(state.maxLevel) ? level : state.maxLevel;
  const firstReached = { ...state.firstReached };
  for (const l of ORDER) if (levelRank(l) <= levelRank(level) && firstReached[l] === undefined) firstReached[l] = updates;
  return { updates, smoothed, level, maxLevel, reason, firstReached };
}
