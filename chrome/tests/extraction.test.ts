/** @vitest-environment jsdom */
import { describe, it, expect } from "vitest";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { JSDOM } from "jsdom";
import { extractGeneric, meaningfulSelection } from "../src/extraction/pipeline.js";
import { adapterFor } from "../src/providers/adapters.js";

function load(rel: string): string {
  return readFileSync(join(process.cwd(), "..", "shared", "fixtures", rel), "utf8");
}

describe("generic extraction", () => {
  it("keeps article, drops nav/ads/footer/comments", async () => {
    const dom = new JSDOM(load("generic/article-noise.html"), { url: "https://example.com/a" });
    const doc = await extractGeneric(dom.window.document, "https://example.com/a");
    expect(doc.confidence).toBe("high");
    expect(doc.markdown).toMatch(/Quiet Cost of Speed/);
    expect(doc.markdown).toMatch(/First real paragraph/);
    expect(doc.markdown).not.toMatch(/Buy now/);
    expect(doc.markdown).not.toMatch(/We use cookies/);
    expect(doc.markdown).not.toMatch(/Copyright/);
    expect(doc.markdown).not.toMatch(/User123/);
  });
});

describe("provider adapters", () => {
  it("chatgpt: finds response and preserves structure", () => {
    const dom = new JSDOM(load("chatgpt/response.html"), { url: "https://chatgpt.com/c/1" });
    const a = adapterFor("https://chatgpt.com/c/1")!;
    expect(a.id).toBe("chatgpt");
    const resps = a.findAssistantResponses(dom.window.document);
    expect(resps.length).toBeGreaterThan(0);
    const doc = a.extractResponse(resps[0], "T", "https://chatgpt.com/c/1");
    expect(doc.markdown).toMatch(/batch the writes/);
    expect(doc.markdown).toMatch(/the docs/);
  });
  it("claude/gemini/perplexity match their hosts", () => {
    expect(adapterFor("https://claude.ai/chat/1")?.id).toBe("claude");
    expect(adapterFor("https://gemini.google.com/app/1")?.id).toBe("gemini");
    expect(adapterFor("https://www.perplexity.ai/search/1")?.id).toBe("perplexity");
  });
});

describe("selection", () => {
  it("honors every nonblank deliberate selection", () => {
    expect(meaningfulSelection("hi")).toBe(true);
    expect(meaningfulSelection(" \n\t")).toBe(false);
    expect(meaningfulSelection("word ".repeat(12))).toBe(true);
    expect(meaningfulSelection("x".repeat(80))).toBe(true);
  });
});
