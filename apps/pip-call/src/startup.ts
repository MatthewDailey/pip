// Imported once at server start (instrumentation.ts, Node.js runtime only).
import { ConfigError, loadConfig } from "./config";

try {
  loadConfig();
} catch (e) {
  if (e instanceof ConfigError) {
    console.error(e.message);
    if (!process.env.VERCEL) process.exit(1);
  }
  throw e;
}
