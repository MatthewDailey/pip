// POST /twiml/voice: every call that reaches Twilio for us lands here, from one of two places.
//
//   App outgoing  From=client:<identity>  Mom placed the call in Elder Guard (Voice SDK). Her leg joins
//                                         pip-<CallSid> as "mom"; the number she dialled (To) is added
//                                         to the same conference as "caller".
//   Pip line      To=<the Pip line>        Someone called the number Mom gives out. Their leg joins as
//                                         "caller"; Mom is added as "mom": her app (MOM_CLIENT_IDENTITY)
//                                         when set, else her phone (MOM_NUMBER).
//
// The leg that hit this webhook runs the transcription, so we remember which party it is: the line
// assembler maps inbound/outbound tracks to speakers from that. The app's call params become the
// context Pip4 scores with.
import { conferenceName, mergeContext, setTranscribedLeg, type CallContext, type Leg } from "./callState";
import { config, webhookUrl, type Config } from "./config";
import { logEvent } from "./timeline";
import { joinConferenceTwiml, sayAndHangUpTwiml } from "./twiml";
import { twilioOps, type CreateParticipant } from "./twilioOps";

export type VoiceRoute =
  | { kind: "app_outgoing"; identity: string; to: string }
  | { kind: "pip_line"; from: string }
  | { kind: "reject"; reason: string };

const digits = (n?: string) => (n ?? "").replace(/[^\d+]/g, "");

/** Pure: which kind of call this is. */
export function classifyVoice(p: Record<string, string>, cfg: Pick<Config, "pipLineNumber">): VoiceRoute {
  const from = p.From ?? p.Caller ?? "";
  if (from.startsWith("client:")) {
    const to = digits(p.To);
    if (!/^\+?\d{3,15}$/.test(to)) return { kind: "reject", reason: `app call has no number to dial (To=${p.To ?? ""})` };
    return { kind: "app_outgoing", identity: from.slice("client:".length), to: to.startsWith("+") ? to : `+${to}` };
  }
  const called = digits(p.Called || p.To);
  if (cfg.pipLineNumber && called === digits(cfg.pipLineNumber)) return { kind: "pip_line", from };
  return { kind: "reject", reason: `call to ${p.Called || p.To || "?"} is neither from the app nor to the Pip line` };
}

const truthy = (v?: string) => (v === undefined ? undefined : ["true", "1", "yes"].includes(v.toLowerCase()));

/** Pure: Pip4's call context from the webhook (the app's params arrive as extra form fields). */
export function contextFor(route: VoiceRoute, p: Record<string, string>): CallContext {
  if (route.kind === "app_outgoing") {
    const inContacts = truthy(p.in_contacts);
    return {
      direction: p.direction === "inbound" ? "inbound" : "outbound",
      caller_in_contacts: inContacts ?? false,
      caller_id: p.contact_name || route.to,
      caller_number: route.to,
      contact_name: p.contact_name || undefined,
      pip_user: p.pip_user || route.identity,
      local_time: p.local_time || undefined,
      user_profile: p.user_profile || undefined,
      source: "app",
    };
  }
  if (route.kind === "pip_line") {
    return {
      direction: "inbound",
      caller_in_contacts: false,
      caller_id: p.CallerName || route.from || "Unknown",
      caller_number: route.from,
      source: "pip_line",
    };
  }
  return {};
}

/** Pure: who to add as the other participant, or undefined when there is nobody to ring. */
export function otherParty(route: VoiceRoute, cfg: Config, callSid: string): CreateParticipant | undefined {
  const common = {
    startConferenceOnEnter: true,
    endConferenceOnExit: true,
    beep: "false",
    timeout: 30,
    statusCallback: webhookUrl(`/twilio/participant?call=${encodeURIComponent(callSid)}`, cfg),
    statusCallbackEvent: ["initiated", "ringing", "answered", "completed"],
    // this REST participant usually creates the conference, and Twilio only takes the conference's
    // status callback from whoever creates it
    conferenceStatusCallback: webhookUrl("/twilio/conference", cfg),
    conferenceStatusCallbackEvent: ["start", "end", "join", "leave", "hold", "mute"],
  };
  if (route.kind === "app_outgoing") {
    if (!cfg.pipLineNumber) return undefined;
    return { ...common, label: "caller", from: cfg.pipLineNumber, to: route.to };
  }
  if (route.kind === "pip_line") {
    if (cfg.momClientIdentity) return { ...common, label: "mom", from: route.from || cfg.pipLineNumber!, to: `client:${cfg.momClientIdentity}` };
    // a real phone only shows numbers we own as caller ID, so Mom sees the Pip line
    if (cfg.momNumber) return { ...common, label: "mom", from: cfg.pipLineNumber!, to: cfg.momNumber };
  }
  return undefined;
}

export async function handleVoice(p: Record<string, string>, cfg: Config = config()): Promise<string> {
  const callSid = p.CallSid || "unknown";
  const route = classifyVoice(p, cfg);
  await logEvent(callSid, "webhook", { route: "voice", call: route.kind, from: p.From, to: p.To });
  if (route.kind === "reject") {
    await logEvent(callSid, "note", { voiceRejected: route.reason });
    return sayAndHangUpTwiml("Sorry, this number can't take that call.");
  }
  const leg: Leg = route.kind === "app_outgoing" ? "mom" : "caller";
  const conference = conferenceName(callSid);
  const context = contextFor(route, p);
  await Promise.all([setTranscribedLeg(callSid, leg), mergeContext(callSid, context), logEvent(callSid, "note", { context })]);

  const other = otherParty(route, cfg, callSid);
  if (!other) {
    await logEvent(callSid, "note", { voiceRejected: "no MOM_CLIENT_IDENTITY or MOM_NUMBER to ring" });
    return sayAndHangUpTwiml("Sorry, we can't reach the person you're calling right now. Please try again later.");
  }
  try {
    await twilioOps().createParticipant(callSid, conference, other);
  } catch {
    // already on the timeline as twilio_error
    return sayAndHangUpTwiml("Sorry, the call couldn't be connected. Please try again.");
  }
  return joinConferenceTwiml({
    conference,
    label: leg,
    transcription: {
      statusCallbackUrl: webhookUrl("/twilio/transcription", cfg),
      engine: process.env.PIP_STT_ENGINE ?? "deepgram",
      model: process.env.PIP_STT_MODEL ?? "nova-3",
    },
    waitUrl: webhookUrl("/twiml/ringback", cfg),
    statusCallback: webhookUrl("/twilio/conference", cfg),
  });
}
