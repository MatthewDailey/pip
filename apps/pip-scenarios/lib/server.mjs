// Serves web/ to the emulator's Chrome through `adb reverse`. Bound to 127.0.0.1 only; pages are static and their forms
// post nowhere.
import { createServer } from "node:http";
import { readFile } from "node:fs/promises";
import path from "node:path";

const PORT = Number(process.env.SCENARIOS_PORT) || 8765;
let server;

export async function ensureServer(dir) {
  if (server) return PORT;
  server = createServer(async (req, res) => {
    const name = path.normalize(decodeURIComponent(new URL(req.url, "http://x").pathname)).replace(/^([/\\])+/, "");
    const file = path.join(dir, name || "index.html");
    if (!file.startsWith(dir)) return res.writeHead(403).end();
    try {
      const body = await readFile(file);
      res.writeHead(200, { "content-type": file.endsWith(".html") ? "text/html; charset=utf-8" : "application/octet-stream", "cache-control": "no-store" });
      res.end(body);
    } catch {
      res.writeHead(404).end("not found");
    }
  });
  await new Promise((resolve, reject) => server.once("error", reject).listen(PORT, "127.0.0.1", resolve));
  return PORT;
}

export function stopServer() {
  server?.close();
  server = undefined;
}
