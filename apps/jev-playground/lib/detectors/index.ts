import type { WindowOptions } from "../transcript";
import type { ApproachId, Detector, DetectorInput, DetectorResult } from "../types";
import { jevConfigured, JEV_MODEL, makeJevDetector } from "./jev";
import { makeKeywordDetector } from "./keyword";
import { llmConfigured, llmProviders, makeLlmDetector } from "./llm";
import { makeTjDetector } from "./tjRules";

export function getDetectors(opts: WindowOptions = {}): Record<ApproachId, Detector> {
  return { jev: makeJevDetector(opts), keyword: makeKeywordDetector(opts), llm: makeLlmDetector(opts), tj: makeTjDetector() };
}

export async function runApproaches(
  input: DetectorInput,
  approaches: ApproachId[] = ["jev", "keyword", "tj", "llm"],
  opts: WindowOptions = {},
): Promise<DetectorResult[]> {
  const d = getDetectors(opts);
  return Promise.all(approaches.map((a) => d[a].analyze(input)));
}

export function status() {
  return {
    jev: { configured: jevConfigured(), model: JEV_MODEL(), mock: !jevConfigured() },
    llm: { configured: llmConfigured(), providers: llmProviders() },
  };
}
