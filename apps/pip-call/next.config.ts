import path from "node:path";
import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // pnpm hoists packages into the monorepo root's node_modules/.pnpm, so Turbopack needs the repo root.
  turbopack: { root: path.join(__dirname, "../..") },
};

export default nextConfig;
