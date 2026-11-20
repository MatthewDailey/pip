import path from "node:path";
import { defineConfig } from "vitest/config";

export default defineConfig({
  resolve: { alias: { "@": path.resolve(__dirname) } },
  test: { include: ["test/**/*.test.ts"], env: { PIP_TWILIO: "fake", TWILIO_AUTH_TOKEN: "test-token", PIP_QUIET: "1" } },
});
