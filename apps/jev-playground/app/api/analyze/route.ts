// POST /api/analyze  { lines: TranscriptLine[], context?, approaches?: ("jev"|"keyword"|"llm")[], windowSeconds? }
// Stateless: scores the transcript so far with each approach (in parallel). The playground calls this.
import { NextResponse } from "next/server";
import { runApproaches } from "@/lib/detectors";
import type { ApproachId, DetectorInput } from "@/lib/types";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

export async function POST(req: Request) {
  const body = (await req.json().catch(() => null)) as (DetectorInput & { approaches?: ApproachId[]; windowSeconds?: number }) | null;
  if (!body || !Array.isArray(body.lines)) return NextResponse.json({ error: "body.lines (array) required" }, { status: 400 });
  const results = await runApproaches(
    { lines: body.lines, context: body.context },
    body.approaches?.length ? body.approaches : ["jev", "keyword", "tj", "llm"],
    { windowSeconds: body.windowSeconds },
  );
  return NextResponse.json({ results });
}
