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

describe("structured response capture", () => {
  it("preserves emphasis, fenced code, nested lists, and table cells without button text", () => {
    const dom = new JSDOM(`<div data-message-author-role="assistant"><p><strong>Bold</strong> and <em>quiet</em></p><pre><code>line one\nline two</code></pre><ol><li>First<ul><li>Nested</li></ul></li></ol><table><tr><th>Name</th><th>Value</th></tr><tr><td>A</td><td>1 | 2</td></tr></table><button data-reader-ui="capture">Reader</button></div>`);
    const adapter = adapterFor("https://chatgpt.com/c/1")!;
    const captured = adapter.extractResponse(adapter.findAssistantResponses(dom.window.document)[0], "T", "https://chatgpt.com/c/1");
    expect(captured.markdown).toContain("**Bold**");
    expect(captured.markdown).toMatch(/(?:\*quiet\*|_quiet_)/);
    expect(captured.markdown).toContain("```\nline one\nline two\n```");
    expect(captured.markdown).toContain("Nested");
    expect(captured.markdown).toContain("| Name | Value |");
    expect(captured.markdown).toContain("1 \\| 2");
    expect(captured.markdown).not.toContain("Reader");
  });
  it("rejects provider-lookalike hosts and malformed URLs", () => {
    expect(adapterFor("https://chatgpt.com.attacker.example/")).toBeNull();
    expect(adapterFor("https://notclaude.ai/")).toBeNull();
    expect(adapterFor("not a URL")).toBeNull();
  });
});
