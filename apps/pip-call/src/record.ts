// PIP_RECORD=1 saves every raw incoming Twilio webhook so a payload that broke a real call can
// become a unit-test fixture. Locally: test/fixtures/twilio/<callSid>/<n>-<route>.json.
// On Vercel (read-only disk): a Redis list that `pnpm pull-fixtures` downloads.
import fs from "node:fs";
import path from "node:path";
import { config } from "./config";
import { DEFAULT_TTL_MS, getStore, storeKind } from "./store";

export interface RecordedWebhook {
  route: string;
  url: string;
  at: number;
  headers: Record<string, string>;
  params: Record<string, string>;
}

export const FIXTURE_LIST_KEY = "pip:fixtures";

export async function recordWebhook(callSid: string, w: RecordedWebhook) {
  if (!config().record) return;
  if (process.env.VERCEL || storeKind() === "redis") {
    await getStore().rpush(FIXTURE_LIST_KEY, JSON.stringify({ callSid, ...w }), 7 * DEFAULT_TTL_MS);
    return;
  }
  const dir = path.join(process.cwd(), "test/fixtures/twilio", callSid || "unknown");
  fs.mkdirSync(dir, { recursive: true });
  const n = fs.readdirSync(dir).length;
  fs.writeFileSync(path.join(dir, `${String(n).padStart(4, "0")}-${w.route.replace(/\W+/g, "_")}.json`), JSON.stringify(w, null, 2));
}
