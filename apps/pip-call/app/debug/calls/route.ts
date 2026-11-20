import { NextResponse } from "next/server";
import { checkDebugToken } from "@/src/debugAuth";
import { listCalls } from "@/src/timeline";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

export async function GET(req: Request) {
  const denied = checkDebugToken(req);
  if (denied) return denied;
  return NextResponse.json({ calls: await listCalls() });
}
