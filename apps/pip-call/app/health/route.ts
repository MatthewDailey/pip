import { NextResponse } from "next/server";
import { config, liveVoiceReady } from "@/src/config";
import { storeKind } from "@/src/store";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

export async function GET() {
  const cfg = config();
  return NextResponse.json({
    ok: true,
    service: "pip-call",
    twilio: cfg.twilioFake ? "fake (RecordingTwilioOps)" : "live",
    store: storeKind(),
    pip4: cfg.pip4Url,
    approach: cfg.pip4Approach,
    // what takeover does: live (Pip talks with Mom), or warn (fixed message; also when live is half-configured)
    voice: liveVoiceReady(cfg) ? "live" : cfg.pipVoice === "live" ? "warn (live is missing OPENAI_* settings)" : "warn",
  });
}
