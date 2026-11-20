// Next.js loads .env.local automatically. Plain Node scripts (npm run eval) call this.
import fs from "node:fs";
import path from "node:path";

export function loadEnvLocal(dir = process.cwd()) {
  for (const name of [".env.local", ".env"]) {
    const p = path.join(dir, name);
    if (fs.existsSync(p)) {
      try {
        process.loadEnvFile(p); // Node >= 20.12; does not override already-set vars
      } catch {
        /* ignore malformed file */
      }
    }
  }
}
