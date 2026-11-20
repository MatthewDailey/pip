import { describe, expect, it } from "vitest";
import { parseScript, roboCallerTwiml } from "@/src/robo";

describe("robo-caller", () => {
  it("parses a mock-calls script, skipping the voice header", () => {
    expect(parseScript("voice: Eddy\nHello there.\n\nSecond line.\n")).toEqual(["Hello there.", "Second line."]);
  });
  it("says one line, then redirects so the next request marks when it ended", () => {
    const xml = roboCallerTwiml(["A.", "B."], 1, (i) => `https://x/r?i=${i}`);
    expect(xml).toContain("<Say voice=\"Polly.Matthew\">B.</Say>");
    expect(xml).toContain("https://x/r?i=2</Redirect>");
    expect(roboCallerTwiml(["A."], 1, () => "")).toContain("<Hangup/>");
  });
});
