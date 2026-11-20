// GET /api/calls → live call sessions, most recently active first (the /live stage view uses it to
// find the current call without typing an id).
import { NextResponse } from "next/server";
import { listSessions, sessionStoreKind } from "@/lib/sessions";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

export async function GET() {
  return NextResponse.json({ store: sessionStoreKind(), calls: await listSessions() });
}
