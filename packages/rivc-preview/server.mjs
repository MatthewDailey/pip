// Static server for the previewer, with a server-sent-events stream of build.json.
// Usage: node server.mjs [--port 8787] [--root DIR] [--backdrop PNG]
// No dependencies: node:http plus fs.watchFile polling (works on every filesystem).
import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const arg = (name, fallback) => {
  const i = process.argv.indexOf(`--${name}`);
  return i > 0 ? process.argv[i + 1] : fallback;
};
const port = Number(arg("port", process.env.PORT || 8787));
const root = path.resolve(arg("root", here));
const backdrop = path.resolve(
  arg("backdrop", process.env.BACKDROP || path.join(here, "../../.proof/pip1.png")),
);
const buildFile = path.join(root, "build.json");

const types = {
  ".html": "text/html; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".mjs": "text/javascript; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".json": "application/json; charset=utf-8",
  ".wasm": "application/wasm",
  ".png": "image/png",
  ".svg": "image/svg+xml",
  ".riv": "application/octet-stream",
};

const clients = new Set();
const readBuild = () => {
  try {
    return fs.readFileSync(buildFile, "utf8").trim() || "{}";
  } catch {
    return "{}";
  }
};
fs.watchFile(buildFile, { interval: 200 }, () => {
  const data = readBuild().replace(/\n/g, " ");
  for (const res of clients) res.write(`event: build\ndata: ${data}\n\n`);
});

const server = http.createServer((req, res) => {
  const url = new URL(req.url, "http://localhost");
  if (url.pathname === "/events") {
    res.writeHead(200, {
      "content-type": "text/event-stream",
      "cache-control": "no-store",
      connection: "keep-alive",
    });
    res.write(`event: build\ndata: ${readBuild().replace(/\n/g, " ")}\n\n`);
    clients.add(res);
    const ping = setInterval(() => res.write(": ping\n\n"), 15000);
    req.on("close", () => {
      clearInterval(ping);
      clients.delete(res);
    });
    return;
  }
  let file;
  if (url.pathname === "/backdrop.png") {
    file = backdrop;
  } else {
    const rel = decodeURIComponent(url.pathname === "/" ? "/index.html" : url.pathname);
    file = path.join(root, path.normalize(rel));
    if (!file.startsWith(root + path.sep)) {
      res.writeHead(403).end();
      return;
    }
  }
  fs.readFile(file, (err, body) => {
    if (err) {
      res.writeHead(404, { "content-type": "text/plain" }).end("not found");
      return;
    }
    res.writeHead(200, {
      "content-type": types[path.extname(file)] || "application/octet-stream",
      "cache-control": "no-store",
    });
    res.end(body);
  });
});

server.listen(port, "127.0.0.1", () => {
  console.log(`rivc-preview: http://localhost:${port}/  (root ${root})`);
});
