// Pure TwiML builders. Every call that reaches pip-call ends up as one leg in conference
// pip-<CallSid> (labelled "caller" or "mom") with Real-Time Transcription started on that leg;
// the other party is added to the same conference through the Participants API.
import twilio from "twilio";

export interface TranscriptionOpts {
  statusCallbackUrl: string;
  engine?: string;
  model?: string;
}

export interface JoinOpts {
  conference: string;
  label: "caller" | "mom";
  transcription: TranscriptionOpts;
  /** what this leg hears until the other party joins (a ringback tone) */
  waitUrl: string;
  /** conference status callback: join / leave / end events go to the timeline */
  statusCallback: string;
}

/** Start transcription on this leg, then join the conference. The other party starts it when they join. */
export function joinConferenceTwiml(o: JoinOpts): string {
  const r = new twilio.twiml.VoiceResponse();
  r.start().transcription({
    statusCallbackUrl: o.transcription.statusCallbackUrl,
    track: "both_tracks",
    partialResults: true,
    languageCode: "en-US",
    ...(o.transcription.engine ? { transcriptionEngine: o.transcription.engine } : {}),
    ...(o.transcription.model ? { speechModel: o.transcription.model } : {}),
  });
  r.dial().conference(
    {
      participantLabel: o.label,
      startConferenceOnEnter: false,
      endConferenceOnExit: true,
      beep: "false",
      waitUrl: o.waitUrl,
      waitMethod: "GET",
      statusCallback: o.statusCallback,
      statusCallbackEvent: ["start", "end", "join", "leave", "hold", "mute"],
    },
    o.conference,
  );
  return r.toString();
}

export function sayAndHangUpTwiml(text: string): string {
  const r = new twilio.twiml.VoiceResponse();
  r.say(text);
  r.hangup();
  return r.toString();
}

/** Plays a ringback tone in a loop: what the waiting leg hears until the other party picks up. */
export function ringbackTwiml(toneUrl: string): string {
  const r = new twilio.twiml.VoiceResponse();
  r.play({ loop: 0 }, toneUrl);
  return r.toString();
}

export const twimlResponse = (xml: string) => new Response(xml, { headers: { "content-type": "text/xml" } });
