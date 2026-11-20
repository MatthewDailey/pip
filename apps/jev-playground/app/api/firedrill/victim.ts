// Scripted elderly-victim replies for the optional Firedrill / smoke harness.
// Keyword-picked only — no model. This file is imported solely by ./route.ts.
// While Pip is on, replies stall and ask confused questions and never include
// payment, credential, gift-card, or remote-access details.

export const PIP_TAKEOVER_REPLY =
  "[Pip] This call looks like a scam. I'm ending it and texting her son.";

export const FAKE_CARD_LINE =
  "Alright, I think I found it. The number on the card is 4111 1111 1111 1111. Is that what you need?";

/** Turns of payment/credential pressure before the pip-off "bad timeline" discloses the fake card. */
export const PIP_OFF_DISCLOSE_AFTER = 4;

const PRESSURE_RE =
  /\b(gift\s*cards?|google\s*play|apple\s*card|steam\s*card|bitcoin|crypto|wire(?:\s*transfer)?|zelle|venmo|cash\s*app|western\s*union|moneygram|routing\s*number|account\s*number|card\s*numbers?|social\s*security|\bssn\b|one-?time\s*code|verification\s*code|anydesk|teamviewer|remote\s*access|log\s*in|password|pin\b|bitcoin\s*atm|safe\s*account|move\s+(?:your\s+)?money|settlement|bail|warrant|arrest|sheriff|refund|overpaid|medicare\s*card|bank\s*account|read\s+(?:them|it|the\s+numbers)|send(?:ing)?\s+(?:me\s+)?(?:the|a)\s+money)\b/i;

const BENIGN_RE =
  /\b(prescription|refill|pharmacy|pickup|pick\s*up|store\s*hours|home\s*delivery|birthday|party|dinner|thanksgiving|visit(?:ing)?|how\s+have\s+you\s+been|declined\s+a?\s*suspicious|just\s+wanted\s+to\s+(?:let\s+you\s+know|make\s+sure)|don'?t\s+(?:need|want)\s+(?:any\s+)?(?:passwords?|codes?|numbers)|official\s+app|come\s+into\s+the\s+branch|no\s+need\s+to\s+pay(?:\s+anything)?\s+over\s+the\s+phone)\b/i;

const THEMES = {
  government:
    /\b(irs|internal\s*revenue|tax|warrant|sheriff|officer|federal|badge|criminal\s*investigation|back\s*taxes)\b/i,
  family:
    /\b(grandma|grandpa|grandson|granddaughter|mom|dad|bail|jail|arrested|accident|out\s+of\s+state|don'?t\s+tell\s+(?:my\s+)?parents)\b/i,
  tech: /\b(microsoft|apple\s*support|technician|computer|virus|hackers?|anydesk|teamviewer|refund|overpaid)\b/i,
  bank: /\b(fraud\s*department|your\s+bank|suspicious\s+transactions?|safe\s*account|wire|zelle|verify\s+identity|one-?time)\b/i,
  medicare: /\b(medicare|medicaid|beneficiary|coverage|reactivat)\b/i,
} as const;

const STALLS: Record<keyof typeof THEMES | "generic", string[]> = {
  government: [
    "Oh my, the IRS? I'm a little confused. Could you say that again more slowly?",
    "A warrant? I pay my taxes, dear. I need to sit down. What did you say your name was?",
    "I'm sorry, this is a lot to take in. Can you tell me what exactly you want me to do? I don't follow.",
    "My son usually helps me with anything official. Could I call you back after I talk to him?",
  ],
  family: [
    "Sweetheart, is that you? The connection is bad. Who is this now?",
    "Oh honey, I'm worried, but I can barely hear you. Can you say that again?",
    "I want to help, I do, but I need to find a pen and write this down. What happened, exactly?",
    "Should I call your mother? I'm not sure I understand. Don't hang up, just… explain it once more.",
  ],
  tech: [
    "A computer problem? I'm not very good with those. What did you say the issue was?",
    "I'm sorry, I don't follow. Are you sure you have the right person? I didn't call anyone.",
    "You want me to install something? I get nervous about that. Can you explain it again, slowly?",
    "My son set this computer up for me. I should probably wait and ask him. What was your name?",
  ],
  bank: [
    "Oh dear, the bank? I didn't notice anything. Could you repeat what you said was wrong?",
    "I'm confused. I wasn't expecting a call. Which bank did you say you're with, again?",
    "I don't want to get this wrong. What exactly do you need from me? Please say it slowly.",
    "Usually I just walk into the branch. Can I do that instead? I'm not sure I understand.",
  ],
  medicare: [
    "Medicare is calling? I thought they only mailed letters. I'm confused. What is this about?",
    "My card? I have it here somewhere. Hold on, I need my glasses. What did you say is happening?",
    "I don't follow, dear. Are you saying I have to do something today? Can you repeat that?",
    "I should probably ask my son about this. What was your name and office again?",
  ],
  generic: [
    "I'm sorry, I don't follow. Could you say that again more slowly?",
    "Oh my, that's a lot at once. Let me catch my breath. What did you say you needed?",
    "I want to be helpful, but I'm confused. Can you explain that in a simpler way?",
    "Hold on, I have to sit down. Who did you say you were, dear?",
  ],
};

const BENIGN_REPLIES = [
  "Oh, thank you for calling. That's good to know. I appreciate you letting me know.",
  "Yes, that sounds fine. I'll take care of it this afternoon. Was there anything else?",
  "Thank you, dear. I don't have any questions right now. I'll see you then.",
];

export function isPressure(text: string): boolean {
  return PRESSURE_RE.test(text);
}

export function looksBenign(text: string): boolean {
  return BENIGN_RE.test(text) && !isPressure(text);
}

export interface VictimState {
  pipOff: boolean;
  takeover: boolean;
  callerTurns: number;
  pressureTurns: number;
  lastCaller: string;
  allCallerText: string;
}

function themeOf(text: string): keyof typeof THEMES | "generic" {
  const entries = Object.entries(THEMES) as [keyof typeof THEMES, RegExp][];
  for (const [name, re] of entries) if (re.test(text)) return name;
  return "generic";
}

function pick(lines: string[], n: number): string {
  return lines[Math.max(0, n) % lines.length]!;
}

export function chooseVictimReply(state: VictimState): string {
  if (state.takeover) return PIP_TAKEOVER_REPLY;
  const corpus = `${state.allCallerText}\n${state.lastCaller}`;
  const benign = looksBenign(corpus) && state.pressureTurns === 0;
  if (state.pipOff && !benign && state.pressureTurns >= PIP_OFF_DISCLOSE_AFTER) {
    return FAKE_CARD_LINE;
  }
  if (benign) return pick(BENIGN_REPLIES, state.callerTurns - 1);
  const theme = themeOf(corpus);
  return pick(STALLS[theme], state.callerTurns - 1);
}
