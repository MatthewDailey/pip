// Demo tripwire: deterministic, heavy-handed rules that run on EVERY chunk (partial or final), before and
// independent of Jev. Toggle with DEMO_TRIPWIRE=0 (default on).
//  1. gift_card_readout → takeover: the user has mentioned gift cards anywhere in the call AND the current
//     chunk looks like she is reading out a number (≥4 numeral digits, a run of ≥3 spoken digit words, or
//     "the number/code/PIN is ...").
//  2. gift_card_unknown_caller → alert: the user mentions gift cards on an inbound call from a number that is
//     not in her contacts.
import { levelRank, type Level, type PolicyState } from "./policy";
import type { CallContext } from "./types";

export interface TripwireHit {
  rule: "gift_card_readout" | "gift_card_unknown_caller";
  level: Level;
  reason: string;
  /** the text that triggered it */
  evidence: string;
}

export const tripwireEnabled = () => !/^(0|false|off|no)$/i.test(process.env.DEMO_TRIPWIRE ?? "on");

// "gift card(s)", "giftcard", STT slips ("gif card", "gift cards", "gift car"), and brand cards ("Target card", "Apple card").
const GIFT =
  /\b(gi(?:ft|f|fts|ff)\s*-?\s*car(?:d|ds|ts|s)?|giftcards?|(?:target|apple|itunes|google\s*play|steam|walmart|amazon|ebay|best\s*buy|razer)\s+(?:gift\s+)?cards?)\b/i;

const DIGIT_WORD = new Set(["zero", "oh", "o", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "double", "triple", "niner"]);
const READ_PHRASE = /\b(the (?:number|numbers|code|codes|pin|claim code) (?:is|are)|pin (?:is|number)|on the back (?:it says|is))\b/i;

export function mentionsGiftCard(text: string) {
  return GIFT.test(text);
}

/** Does this chunk look like reading out a number? */
export function readingNumber(text: string): string | null {
  // money amounts, clock times and lone years are not card numbers
  const cleaned = text
    .replace(/\$\s?[\d,]+(\.\d+)?/g, " ")
    .replace(/\b[\d,]+(\.\d+)?\s*(dollars|bucks|cents|percent|%)/gi, " ")
    .replace(/\b\d{1,2}:\d{2}\b/g, " ")
    .replace(/\b\d{1,2}\s*(am|pm|a\.m\.|p\.m\.|o'?clock)/gi, " ")
    .replace(/(^|[^\d\s,.-]\s*)\b(?:19|20)\d{2}\b(?!\s*[\d,.-])/g, "$1 ");
  const numerals = cleaned.match(/\d[\d\s,.\-]*\d|\d/g)?.join("").replace(/\D/g, "") ?? "";
  if (numerals.length >= 4) return "digits";
  let run = 0;
  for (const w of text.toLowerCase().replace(/[^a-z\s]/g, " ").split(/\s+/).filter(Boolean)) {
    run = DIGIT_WORD.has(w) ? run + 1 : 0;
    if (run >= 3) return "spoken digits";
  }
  if (READ_PHRASE.test(text)) return "read-out phrase";
  return null;
}

/**
 * @param userTexts every user line so far (final), in order
 * @param current the chunk just received (partial or final); already included in userTexts if final
 */
export function checkTripwire(userTexts: string[], current: string, ctx: CallContext = {}): TripwireHit | null {
  if (!tripwireEnabled()) return null;
  const all = current && !userTexts.includes(current) ? [...userTexts, current] : userTexts;
  const giftLine = all.find(mentionsGiftCard);
  if (!giftLine) return null;
  const reading = current ? readingNumber(current) : null;
  if (reading) {
    return {
      rule: "gift_card_readout",
      level: "takeover",
      reason: `Reading gift card numbers to a caller (${reading} after "${giftLine.slice(0, 60)}")`,
      evidence: current.slice(0, 120),
    };
  }
  if (ctx.caller_in_contacts !== true && ctx.direction !== "outbound") {
    return {
      rule: "gift_card_unknown_caller",
      level: "alert",
      reason: "Gift cards mentioned on a call from a number not in contacts",
      evidence: giftLine.slice(0, 120),
    };
  }
  return null;
}

const ORDER: Level[] = ["calm", "watch", "alert", "takeover"];

/** Raise the policy level to at least `level` (never lowers; takeover stays sticky through stepPolicy). */
export function raisePolicy(ps: PolicyState, level: Level, reason: string): PolicyState {
  if (levelRank(level) <= levelRank(ps.level)) return ps;
  const firstReached = { ...ps.firstReached };
  for (const l of ORDER) if (levelRank(l) <= levelRank(level) && firstReached[l] === undefined) firstReached[l] = ps.updates;
  return {
    ...ps,
    level,
    maxLevel: levelRank(level) > levelRank(ps.maxLevel) ? level : ps.maxLevel,
    reason,
    firstReached,
  };
}
