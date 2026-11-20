// POST /api/screens: score one phone screen for fraud and say whether Pip should intervene.
// JSON body: { image_base64? | image_url?, image_format?, a11y_text?, app_package?, url?, window_title?, trigger?, recent_events?, mode? }
// or multipart/form-data with an `image` file plus the same fields (recent_events as a JSON string).
// mode: "image+text" (default) | "image" | "text". Returns the screen_assessment contract.
import { NextResponse } from "next/server";
import { assessScreen, type ScreenInput, type ScreenMode } from "@/lib/screens/jevScreen";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

const MODES: ScreenMode[] = ["image+text", "image", "text"];

async function readInput(req: Request): Promise<(ScreenInput & { mode?: string; image_url?: string }) | null> {
  const ct = req.headers.get("content-type") ?? "";
  if (ct.includes("multipart/form-data")) {
    const form = await req.formData();
    const file = form.get("image");
    const str = (k: string) => (typeof form.get(k) === "string" ? (form.get(k) as string) : undefined);
    let recent: ScreenInput["recent_events"];
    try {
      recent = str("recent_events") ? JSON.parse(str("recent_events")!) : undefined;
    } catch {
      recent = [str("recent_events")!];
    }
    return {
      image_base64: file && typeof file !== "string" ? Buffer.from(await file.arrayBuffer()).toString("base64") : undefined,
      image_format: file && typeof file !== "string" && /jpe?g/.test(file.type) ? "jpg" : undefined,
      a11y_text: str("a11y_text"),
      app_package: str("app_package"),
      url: str("url"),
      window_title: str("window_title"),
      trigger: str("trigger"),
      recent_events: recent,
      mode: str("mode"),
      image_url: str("image_url"),
    };
  }
  return (await req.json().catch(() => null)) as (ScreenInput & { mode?: string; image_url?: string }) | null;
}

export async function POST(req: Request) {
  const body = await readInput(req);
  if (!body) return NextResponse.json({ error: "JSON or multipart body required" }, { status: 400 });
  if (!body.image_base64 && body.image_url) {
    const r = await fetch(body.image_url).catch(() => null);
    if (!r?.ok) return NextResponse.json({ error: `could not fetch image_url (${r?.status ?? "network error"})` }, { status: 400 });
    body.image_base64 = Buffer.from(await r.arrayBuffer()).toString("base64");
    if ((r.headers.get("content-type") ?? "").includes("jpeg")) body.image_format = "jpg";
  }
  if (!body.image_base64 && !body.a11y_text) {
    return NextResponse.json({ error: "send image_base64, image_url, an image file, or a11y_text" }, { status: 400 });
  }
  const mode = MODES.includes(body.mode as ScreenMode) ? (body.mode as ScreenMode) : "image+text";
  try {
    return NextResponse.json(await assessScreen(body, mode));
  } catch (e) {
    return NextResponse.json({ error: String((e as Error).message ?? e) }, { status: 502 });
  }
}
