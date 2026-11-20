#!/usr/bin/env node
// Stitch library IDs into scenario and world files.
//
//   1. Save personas and evaluations (`firedrill library save --kind ... --json`),
//      put the printed id / revision / digest into ids.json (see ids.example.json).
//   2. node bind.mjs            # writes scenarios/*.json from the templates
//   3. Save each scenarios/*.json (not the .template.json files)
//      and add those ids to ids.json under "scenarios"
//   4. node bind.mjs            # writes world.json
//
// This script never calls the Firedrill CLI and spends no credits.
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));

function lookup(ids, dotted) {
  const parts = dotted.split(".");
  let cur = ids;
  for (const p of parts) {
    if (cur == null || typeof cur !== "object" || !(p in cur)) return undefined;
    cur = cur[p];
  }
  return cur;
}

function fill(template, ids) {
  return template.replace(/\{\{([a-z0-9_.-]+)\}\}/gi, (m, key) => {
    const v = lookup(ids, key);
    if (v == null || v === "") throw new Error(`ids.json missing a value for {{${key}}}`);
    return String(v);
  });
}

const idsPath = path.join(here, "ids.json");
if (!fs.existsSync(idsPath)) {
  console.error("No ids.json yet. Copy ids.example.json to ids.json and fill in IDs from `firedrill library save`.");
  process.exit(1);
}
const ids = JSON.parse(fs.readFileSync(idsPath, "utf8"));

const scenarioDir = path.join(here, "scenarios");
const templates = fs.readdirSync(scenarioDir).filter((f) => f.endsWith(".template.json"));
let scenariosReady = 0;
let scenariosWrote = 0;
for (const file of templates) {
  const slug = file.replace(/\.template\.json$/, "");
  const raw = fs.readFileSync(path.join(scenarioDir, file), "utf8");
  const personaId = lookup(ids, `personas.${slug}.id`);
  const evalOk =
    lookup(ids, "evaluations.scam-takeover.id") || lookup(ids, "evaluations.benign-no-takeover.id");
  if (!personaId || !evalOk) {
    console.log(`skip ${file}: personas / evaluations not filled in ids.json yet`);
    continue;
  }
  const filled = fill(raw, ids);
  const obj = JSON.parse(filled);
  // revision must be a number in the Firedrill reference object
  for (const list of [obj.personas, obj.evaluations]) {
    for (const ref of list) ref.revision = Number(ref.revision);
  }
  fs.writeFileSync(path.join(scenarioDir, `${slug}.json`), `${JSON.stringify(obj, null, 2)}\n`);
  scenariosWrote++;
  const sid = lookup(ids, `scenarios.${slug}.id`);
  if (sid) scenariosReady++;
  console.log(`wrote scenarios/${slug}.json`);
}

const worldRaw = fs.readFileSync(path.join(here, "world.template.json"), "utf8");
if (scenariosReady === templates.length) {
  const world = JSON.parse(fill(worldRaw, ids));
  for (const ref of world.resources) ref.revision = Number(ref.revision);
  fs.writeFileSync(path.join(here, "world.json"), `${JSON.stringify(world, null, 2)}\n`);
  console.log("wrote world.json");
} else {
  console.log(
    `world.json not written yet (${scenariosReady}/${templates.length} scenario IDs in ids.json). Save the scenario files, then rerun.`,
  );
}
