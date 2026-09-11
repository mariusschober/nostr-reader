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

  // §3D: a CSS-blind converter fuses text from two visually separate boxes when
  // the source has no whitespace between the tags (chart legends, block eyebrows).
  it("keeps a word boundary between visually-block inline boxes", async () => {
    const html = `<!doctype html><html><head><style>
        .eyebrow { display: block; }
        .legend { display: flex; }
      </style></head><body><main><article>
      <h1>Report on merged labels</h1>
      <h2><span class="eyebrow">Finding 4: Labor vs. capital share</span>The pie will grow, but a larger share might go to capital</h2>
      <p>Body paragraph one has enough words that the extractor treats this document as a real article rather than a fragment of navigation chrome or an empty shell. It keeps talking so the word count and the text ratio both clear the confidence thresholds used by the generic extractor scoring function that decides between the primary and fallback candidates for the page.</p>
      <p><span class="legend">Knowledge workers</span><span class="legend">All other workers</span></p>
      </article></main></body></html>`;
    const dom = new JSDOM(html, { url: "https://example.com/report" });
    const doc = await extractGeneric(dom.window.document, "https://example.com/report");
    expect(doc.markdown).not.toMatch(/workersAll/);
    expect(doc.markdown).toMatch(/Knowledge workers\s+All other workers/);
    expect(doc.markdown).not.toMatch(/shareThe/);
    expect(doc.markdown).toMatch(/capital share\s+The pie/);
  });

  it("keeps shared classes inline in prose while blocking them in legends", async () => {
    const html = `<!doctype html><html><head><style>.legend .shared{display:block} p .shared{display:inline}</style></head><body><main><article>
      <h1>Shared class contexts</h1>
      <div class="legend"><span class="shared">Legend</span></div>
      <p>micro<span class="shared">scope</span>s and enough body text here to clear the extractor confidence thresholds comfortably for this focused boundary test of shared class handling.</p>
      <p>Second body paragraph adds enough words that the extractor treats this document as a real article rather than a fragment of navigation chrome or an empty shell for scoring.</p>
      </article></main></body></html>`;
    const dom = new JSDOM(html, { url: "https://example.com/shared" });
    const doc = await extractGeneric(dom.window.document, "https://example.com/shared");
    expect(doc.markdown).toMatch(/microscopes/);
    expect(doc.markdown).not.toMatch(/micro scope/);
  });

  it("separates classless block spans without touching live DOM or leaking markers", async () => {
    const html = `<!doctype html><html><body><main><article>
      <h1>Classless blocks</h1>
      <p><span style="display:block">Knowledge workers</span><span style="display:block">All other workers</span></p>
      <p>Body paragraph adds enough words that the extractor treats this document as a real article rather than a fragment for scoring and confidence thresholds.</p>
      </article></main></body></html>`;
    const dom = new JSDOM(html, { url: "https://example.com/classless" });
    const live = dom.window.document;
    const before = live.body.textContent;
    const doc = await extractGeneric(live, "https://example.com/classless");
    expect(doc.markdown).toMatch(/Knowledge workers\s+All other workers/);
    expect(live.body.textContent).toBe(before);
    expect(doc.markdown).not.toMatch(/data-reader-visual-block/);
  });

  it("leaves genuine inline formatting and punctuation untouched", async () => {
    const html = `<!doctype html><html><head><style>.plain { display: inline; }</style></head><body><main><article>
      <h1>Inline boundaries</h1>
      <p>This has <strong>bold</strong> and <em>quiet</em> words inline, plus a <span class="plain">plain</span> word and 12<sup>th</sup> and (parens) intact across enough text to clear the extractor confidence thresholds comfortably and reliably for the generic path here today without any visual block spaces being inserted anywhere in the output.</p>
      </article></main></body></html>`;
    const dom = new JSDOM(html, { url: "https://example.com/inline" });
    const doc = await extractGeneric(dom.window.document, "https://example.com/inline");
    expect(doc.markdown).toMatch(/This has \*\*bold\*\* and (?:\*quiet\*|_quiet_) words inline/);
    expect(doc.markdown).toContain("12th");
    expect(doc.markdown).toContain("(parens)");
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
