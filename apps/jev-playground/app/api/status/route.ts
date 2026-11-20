import { NextResponse } from "next/server";
import { status } from "@/lib/detectors";

export const dynamic = "force-dynamic";

export async function GET() {
  return NextResponse.json(status());
}
