#!/usr/bin/env node
// Fill machine-specific hashes and paths for the Firedrill descriptor and
// local plan. Writes descriptor.json and plan.local.json (gitignored).
// Nothing is uploaded. Run from anywhere:
//   node prepare.mjs
//   node prepare.mjs --descriptor-sha256 <digest from `firedrill register`>
import { createHash } from "node:crypto";
import { execFileSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const appDir = path.resolve(here, "..");
const repoRoot = path.resolve(appDir, "../..");
const routeFile = path.join(appDir, "app/api/firedrill/route.ts");

function arg(name) {
  const i = process.argv.indexOf(`--${name}`);
  if (i >= 0 && process.argv[i + 1]) return process.argv[i + 1];
  const eq = process.argv.find((a) => a.startsWith(`--${name}=`));
  return eq ? eq.slice(name.length + 3) : "";
}

function which(cmd) {
  try {
    return execFileSync("which", [cmd], { encoding: "utf8" }).trim();
  } catch {
    return cmd;
  }
}

function rootIdentity(folder) {
  const p = fs.realpathSync(folder);
  const s = fs.statSync(p, { bigint: true });
  return createHash("sha256")
    .update("firedrill.root-identity.v1\0")
    .update(p)
    .update("\0")
    .update(`${s.dev}:${s.ino}`)
    .digest("hex");
}

function sha256File(file) {
  return createHash("sha256").update(fs.readFileSync(file)).digest("hex");
}

function readJson(file) {
  return JSON.parse(fs.readFileSync(file, "utf8"));
}

function writeJson(file, obj) {
  fs.writeFileSync(file, `${JSON.stringify(obj, null, 2)}\n`);
  console.log(`wrote ${path.relative(here, file) || file}`);
}

const identity = rootIdentity(repoRoot);
const adapter = sha256File(routeFile);
const pnpm = which("pnpm");
const digest = arg("descriptor-sha256") || process.env.FIREDRILL_DESCRIPTOR_SHA256 || "";

const descriptor = readJson(path.join(here, "descriptor.template.json"));
descriptor.source_roots[0].identity_sha256 = identity;
descriptor.driver.adapter_sha256 = adapter;
writeJson(path.join(here, "descriptor.json"), descriptor);

function fillPlan(templateName, outName) {
  const plan = readJson(path.join(here, templateName));
  plan.descriptor_sha256 = digest || plan.descriptor_sha256;
  plan.roots[0].path = repoRoot;
  plan.roots[0].identity_sha256 = identity;
  plan.services[0].command.executable = pnpm;
  writeJson(path.join(here, outName), plan);
}

fillPlan("plan.template.json", "plan.local.json");
fillPlan("plan.pip-off.template.json", "plan.pip-off.local.json");

console.log("root identity:", identity);
console.log("adapter sha256 (route.ts):", adapter);
console.log("pnpm:", pnpm);
if (!digest) {
  console.log("descriptor_sha256 is still a placeholder. After `firedrill register`, rerun:");
  console.log("  node prepare.mjs --descriptor-sha256 <digest>");
}
