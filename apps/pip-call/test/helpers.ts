import { resetConfig } from "@/src/config";
import { MemoryStore, setStore } from "@/src/store";
import { RecordingTwilioOps, setTwilioOps } from "@/src/twilioOps";

export function freshWorld() {
  resetConfig();
  const store = new MemoryStore();
  setStore(store);
  setTwilioOps(new RecordingTwilioOps());
  return store;
}

let seq = 0;
export function transcriptionParams(o: {
  callSid: string;
  track: "inbound_track" | "outbound_track";
  text: string;
  final: boolean;
  seq?: number;
  at?: number;
}): Record<string, string> {
  return {
    AccountSid: "ACtest",
    CallSid: o.callSid,
    TranscriptionSid: "GTtest",
    TranscriptionEvent: "transcription-content",
    LanguageCode: "en-US",
    Track: o.track,
    TranscriptionData: JSON.stringify({ transcript: o.text, confidence: 0.9 }),
    Final: String(o.final),
    SequenceId: String(o.seq ?? ++seq),
    Timestamp: new Date(o.at ?? Date.now()).toISOString(),
  };
}
