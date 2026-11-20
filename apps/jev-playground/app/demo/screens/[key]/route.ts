import { NextResponse } from "next/server";
import { demoPageHtml } from "@/lib/screens/demoPage";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

export async function GET(_req: Request, ctx: { params: Promise<{ key: string }> }) {
  const { key } = await ctx.params;
  const page = demoPageHtml(key);
  if (!page) return NextResponse.json({ error: `unknown demo screen "${key}"` }, { status: 404 });
  return new NextResponse(page.html, {
    status: 200,
    headers: { "Content-Type": "text/html; charset=utf-8", "Cache-Control": "no-store" },
  });
}
