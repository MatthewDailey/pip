// Rung 2 test parties, served by pip-call itself so a robo-call needs no people:
//   robo-caller  reads a script (tools/mock-calls/lines/<name>.txt) one <Say> at a time. After each line
//                Twilio <Redirect>s back here, so the arrival of request i+1 marks the moment line i
//                finished playing: the robocall script compares that with when the line's final
//                transcript arrived (speech end → transcript latency).
//   robo-mom     answers Mom's side with a few short replies.
// The script's lines are registered per run with POST /debug/robocall (x-pip-debug-token =
// TWILIO_AUTH_TOKEN), so the deployment doesn't need the repo's text files.
import twilio from "twilio";
import { DEFAULT_TTL_MS, getStore } from "./store";

export const CALLER_VOICE = "Polly.Matthew";
export const MOM_VOICE = "Polly.Joanna";
const runKey = (run: string) => `pip:robo:${run}`;

export async function saveRun(run: string, lines: string[]) {
  await getStore().set(runKey(run), JSON.stringify(lines), DEFAULT_TTL_MS);
}
export async function loadRun(run: string): Promise<string[] | undefined> {
  const raw = await getStore().get(runKey(run));
  return raw ? (JSON.parse(raw) as string[]) : undefined;
}

/** Pure: TwiML for step i of the robo-caller. nextUrl(i+1) is where Twilio goes once line i has been said. */
export function roboCallerTwiml(lines: string[], i: number, nextUrl: (i: number) => string): string {
  const r = new twilio.twiml.VoiceResponse();
  if (i >= lines.length) {
    r.pause({ length: 4 });
    r.hangup();
    return r.toString();
  }
  // first line: give Mom's phone time to ring and answer; later lines: a beat for her reply
  r.pause({ length: i === 0 ? 7 : 3 });
  r.say({ voice: CALLER_VOICE }, lines[i]);
  r.redirect({ method: "POST" }, nextUrl(i + 1));
  return r.toString();
}

export const ROBO_MOM_REPLIES = ["Hello?", "Oh. Yes, that's me.", "Oh my goodness. What do I need to do?", "Okay. Which store?", "Alright, I have them here."];

export function roboMomTwiml(): string {
  const r = new twilio.twiml.VoiceResponse();
  r.pause({ length: 1 });
  for (const line of ROBO_MOM_REPLIES) {
    r.say({ voice: MOM_VOICE }, line);
    r.pause({ length: 9 });
  }
  r.pause({ length: 60 });
  return r.toString();
}

/** Parse tools/mock-calls/lines/<name>.txt: an optional "voice: ..." header, then one line per utterance. */
export function parseScript(text: string): string[] {
  return text
    .split("\n")
    .map((l) => l.trim())
    .filter((l) => l && !/^voice\s*:/i.test(l) && !l.startsWith("#"));
}
