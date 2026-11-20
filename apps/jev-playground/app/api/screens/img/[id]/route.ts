// GET /api/screens/img/:id  serves a dataset screenshot (data/screens/img/<id>.png) for the Screens page.
import fs from "node:fs";
import path from "node:path";

export const runtime = "nodejs";

type Ctx = { params: Promise<{ id: string }> };

export async function GET(_req: Request, { params }: Ctx) {
  const { id } = await params;
  if (!/^s\d{2,3}$/.test(id)) return new Response("bad id", { status: 400 });
  const p = path.join(process.cwd(), "data/screens/img", `${id}.png`);
  if (!fs.existsSync(p)) return new Response("not found", { status: 404 });
  return new Response(fs.readFileSync(p), { headers: { "content-type": "image/png", "cache-control": "public, max-age=3600" } });
}
