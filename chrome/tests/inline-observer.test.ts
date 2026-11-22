/** @vitest-environment jsdom */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { chatgptAdapter } from "../src/providers/adapters.js";

const chatgpt = (id: string, text = "Reader is an ordinary word.") =>
  `<div id="${id}" data-message-author-role="assistant"><div class="markdown"><p>${text}</p><pre><code>const name = "Reader";</code></pre></div><div class="actions"><button aria-label="Copy">Copy</button><button aria-label="Like">Like</button></div></div>`;
const gemini = (id: string) => `<div id="${id}" class="assistant-message"><p>Gemini response</p><div class="actions"><button aria-label="Like">Like</button><button aria-label="Dislike">Dislike</button></div></div>`;
const controls = (root: ParentNode = document) => [...root.querySelectorAll<HTMLButtonElement>('.reader-send[data-reader-ui="capture"]')];
const sourceText = (root: HTMLElement) => {
  const copy = root.cloneNode(true) as HTMLElement;
  copy.querySelectorAll("[data-reader-ui]").forEach(node => node.remove());
  return copy.textContent;
};
let observers: MutationObserver[];
let listener: (message: any, sender: any, response: (value: unknown) => void) => boolean;
let captured: any[];
let preview: ShadowRoot | undefined;

beforeEach(() => {
  vi.useFakeTimers();
  vi.resetModules();
  delete (globalThis as any).readerCaptureInstalled;
  document.body.replaceChildren();
  window.getSelection()?.removeAllRanges();
  captured = []; observers = []; preview = undefined;
  // Track/disconnect actual DOM observers; their callbacks and delivery are real.
  const Observer = globalThis.MutationObserver;
  vi.stubGlobal("MutationObserver", class extends Observer {
    constructor(callback: MutationCallback) { super(callback); observers.push(this); }
  });
  vi.stubGlobal("chrome", { runtime: {
    onMessage: { addListener(callback: typeof listener) { listener = callback; } },
    async sendMessage(message: any) {
      if (message.kind === "reader-capture") captured.push(message.doc);
      return { ok: true, queued: true, transferId: "fixture-transfer", delivered: false };
    },
  } });
  const attach = Element.prototype.attachShadow;
  vi.spyOn(Element.prototype, "attachShadow").mockImplementation(function(this: HTMLElement, options) {
    const shadow = attach.call(this, options);
    if (this.dataset.readerUi === "preview") preview = shadow;
    return shadow;
  });
});
afterEach(() => {
  observers.forEach(observer => observer.disconnect());
  vi.clearAllTimers(); vi.restoreAllMocks(); vi.unstubAllGlobals(); vi.useRealTimers();
  delete (globalThis as any).readerCaptureInstalled;
  window.getSelection()?.removeAllRanges();
});
async function start(html: string, url = "https://chatgpt.com/c/fixture") {
  document.body.innerHTML = html;
  vi.stubGlobal("location", new URL(url));
  await import("../src/content/capture.js");
  await Promise.resolve();
}
async function settle(milliseconds = 300) {
  await Promise.resolve();
  await vi.advanceTimersByTimeAsync(milliseconds);
  await Promise.resolve();
}
async function toolbar() {
  await new Promise(resolve => listener({ kind: "reader-capture-now" }, {}, resolve));
}

describe("production inline observer", () => {
  it.each([
    ["response", "contenteditable", "true"],
    ["ancestor", "contenteditable", "plaintext-only"],
    ["ancestor", "role", "textbox"],
    ["ancestor", "class", "ProseMirror"],
    ["ancestor", "data-testid", "composer"],
  ])("removes controls on %s %s transition and restores one read-only control", async (where, attribute, value) => {
    await start(`<section id="outer">${chatgpt("answer")}</section>`);
    const response = document.getElementById("answer")!;
    const original = sourceText(response);
    expect(controls(response)).toHaveLength(1);
    const target = where === "response" ? response : document.getElementById("outer")!;
    target.setAttribute(attribute, value);
    await settle();
    expect(controls(response)).toHaveLength(0);
    expect(response.textContent).toBe(original);
    target.removeAttribute(attribute);
    await settle();
    expect(controls(response)).toHaveLength(1);
    expect(sourceText(response)).toBe(original);
    await settle();
    expect(controls(response)).toHaveLength(1);
  });

  it("remounts a settled strip without another mutation and bounds repeated stripping", async () => {
    await start(chatgpt("answer"));
    const response = document.getElementById("answer")!;
    const original = sourceText(response);
    controls(response)[0].remove();
    await settle();
    expect(controls(response)).toHaveLength(0);
    await settle(4700);
    expect(controls(response)).toHaveLength(1);
    controls(response)[0].remove();
    await settle(5000);
    expect(controls(response)).toHaveLength(1);
    controls(response)[0].remove();
    await settle(20000);
    expect(controls(response)).toHaveLength(0);
    expect(vi.getTimerCount()).toBe(0);
    expect(sourceText(response)).toBe(original);
  });

  it.each([
    ["https://chatgpt.com/c/fixture", chatgpt("answer")],
    ["https://gemini.google.com/app/fixture", gemini("answer")],
  ])("updates streaming state and replaces cloned controls safely on %s", async (url, html) => {
    await start(`<section id="outer" aria-busy="true">${html}</section>`, url);
    const outer = document.getElementById("outer")!;
    expect(controls()).toHaveLength(1);
    expect(controls()[0].disabled).toBe(true);
    outer.setAttribute("aria-busy", "false");
    await settle();
    expect(controls()[0].disabled).toBe(false);
    const response = document.getElementById("answer")!;
    const replacement = response.cloneNode(true) as HTMLElement;
    const clonedControl = controls(replacement)[0];
    response.replaceWith(replacement);
    await settle();
    expect(controls()).toHaveLength(1);
    expect(controls(replacement)[0]).not.toBe(clonedControl);
    expect(response.isConnected).toBe(false);
    expect(sourceText(replacement)).toBe(sourceText(response));
  });

  it("does not drop provider mutations occurring during its own insertion", async () => {
    const append = Element.prototype.append;
    let changed = false;
    vi.spyOn(Element.prototype, "append").mockImplementation(function(this: Element, ...nodes) {
      append.apply(this, nodes);
      if (!changed && nodes.some(node => node instanceof Element && node.matches('[data-reader-ui="capture"]'))) {
        changed = true;
        // Provider subtree contains an earlier Reader control and a new response.
        const wrapper = document.createElement("section");
        wrapper.append(document.getElementById("first")!);
        wrapper.insertAdjacentHTML("beforeend", chatgpt("second", "Later provider text"));
        document.body.append(wrapper);
      }
    });
    await start(chatgpt("first"));
    await settle();
    expect(changed).toBe(true);
    expect(controls(document.getElementById("first")!)).toHaveLength(1);
    expect(controls(document.getElementById("second")!)).toHaveLength(1);
    expect(sourceText(document.getElementById("second")!)).toContain("Later provider text");
    expect(controls()).toHaveLength(2);
  });

  it.each([
    '<div contenteditable="true">', '<div role="textbox">', '<div class="tiptap">',
    '<div data-lexical-editor="true">', '<div data-testid="composer-input">',
    '<a href="/link">', '<button>', '<div role="button">', '<pre>', '<code>',
    '<table><tbody><tr><td>',
  ])("withholds injection under unsafe ancestor %s", async ancestor => {
    await start(`${ancestor}${chatgpt("answer")}`);
    const response = document.getElementById("answer")!;
    expect(response).not.toBeNull();
    expect(controls()).toHaveLength(0);
    expect(response.textContent).toContain("Reader is an ordinary word.");
  });

  it("captures actual Markdown without Reader UI and preserves literal Reader content", async () => {
    await start(chatgpt("answer"));
    const response = document.getElementById("answer")!;
    const expected = chatgptAdapter.extractResponse(response, document.title, "https://chatgpt.com/c/fixture");
    expect(controls(response)).toHaveLength(1);
    await toolbar();
    expect(captured).toHaveLength(1);
    expect(captured[0].markdown).toBe(expected.markdown);
    expect(captured[0].markdown).toContain("Reader is an ordinary word.");
    expect(captured[0].markdown).toContain('```\nconst name = "Reader";\n```');
    expect(captured[0].markdown).not.toContain("Copy");
    expect(captured[0].sourceType).toBe("chatgpt");
    const range = document.createRange(); range.selectNodeContents(response.querySelector("p")!);
    window.getSelection()!.addRange(range);
    await toolbar();
    expect(captured).toHaveLength(2);
    expect(captured[1].sourceType).toBe("selection");
    expect(captured[1].markdown).toBe("Reader is an ordinary word.\n");
  });

  it("keeps uncertain generic extraction behind the explicit preview", async () => {
    await start('<article><h1>Short note</h1><p>A short Reader paragraph.</p></article>', "https://example.org/short");
    await toolbar();
    expect(captured).toHaveLength(0);
    expect(document.querySelector('[data-reader-ui="preview"]')).not.toBeNull();
    expect(preview).toBeDefined();
    expect(preview!.textContent).toContain("Check this capture");
    expect(preview!.querySelectorAll("button")).toHaveLength(2);
    expect(preview!.textContent).toContain("A short Reader paragraph.");
    preview!.querySelector("button")!.click();
    await settle(0);
    expect(captured).toHaveLength(0); // programmatic click is not confirmation
  });
});
