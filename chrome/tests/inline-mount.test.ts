/** @vitest-environment jsdom */
import { beforeEach, describe, expect, it } from "vitest";
import {
  adapterFor,
  chatgptAdapter,
  geminiAdapter,
  isEditableContext,
  isSafeMountTarget,
} from "../src/providers/adapters.js";

beforeEach(() => { document.body.replaceChildren(); });
function fixture(html: string): Document {
  // Production guards use the ambient Element realm, just as a content script does.
  document.body.innerHTML = html;
  return document;
}
function chatgptResponse(doc: Document): HTMLElement {
  const response = chatgptAdapter.findAssistantResponses(doc)[0];
  expect(response).toBeTruthy();
  return response;
}

describe("editable guard", () => {
  it("treats editors as unsafe", () => {
    const doc = fixture(`<div id="a" contenteditable="true">x</div><div class="ProseMirror">y</div><div role="textbox">z</div><input value="q" />`);
    for (const selector of ["#a", ".ProseMirror", '[role="textbox"]', "input"]) {
      expect(isEditableContext(doc.querySelector(selector))).toBe(true);
      expect(isSafeMountTarget(doc.querySelector(selector))).toBe(false);
    }
  });

  it.each(["true", "", "plaintext-only"])("refuses editable chatgpt responses (%s)", value => {
    const doc = fixture(`<div data-message-author-role="assistant" contenteditable="${value}"><div class="markdown"><p>Hello</p></div><div><button>Copy</button><button>Like</button></div></div>`);
    const adapter = adapterFor("https://chatgpt.com/c/1")!;
    expect(adapter.id).toBe("chatgpt");
    expect(chatgptAdapter.findMountTarget(chatgptResponse(doc))).toBeNull();
  });

  it.each([
    '<div contenteditable="true">', '<div role="textbox">', '<div class="ProseMirror">',
    '<div data-testid="composer">', '<a href="/link">', '<button>', '<div role="button">',
    '<pre>', '<code>', '<table><tbody><tr><td>',
  ])("rejects unsafe ancestors: %s", ancestor => {
    fixture(`${ancestor}<div data-message-author-role="assistant"><p>Response</p><div class="actions"><button>Copy</button><button>Like</button></div></div>`);
    const response = chatgptResponse(document);
    expect(isSafeMountTarget(response)).toBe(false);
    expect(chatgptAdapter.findMountTarget(response)).toBeNull();
  });
});

describe("action-row targeting", () => {
  it("prefers chatgpt action row over code copy button", () => {
    const doc = fixture(`<div data-message-author-role="assistant"><div class="markdown"><pre><code>x</code><div><button>Copy code</button></div></pre><p>Answer</p></div><div class="actions"><button aria-label="Copy">Copy</button><button aria-label="Like">Like</button></div></div>`);
    const response = chatgptResponse(doc);
    const mount = chatgptAdapter.findMountTarget(response);
    expect(mount).not.toBeNull();
    expect(mount!.className).toContain("actions");
    expect(mount!.querySelectorAll("button").length).toBeGreaterThanOrEqual(2);
  });

  it("mounts gemini in action row, not inside like button", () => {
    const doc = fixture(`<div class="response-container"><div class="assistant-message"><p>Result</p><div class="row"><button aria-label="Like">Like</button><button aria-label="Dislike">Dislike</button></div></div></div>`);
    const adapter = adapterFor("https://gemini.google.com/app/1")!;
    expect(adapter.id).toBe("gemini");
    const response = adapter.findAssistantResponses(doc)[0];
    expect(response).toBeTruthy();
    const mount = geminiAdapter.findMountTarget(response);
    expect(mount).not.toBeNull();
    expect(mount!.tagName.toLowerCase()).not.toBe("button");
    expect(mount!.querySelectorAll("button").length).toBeGreaterThanOrEqual(2);
  });

  it("uses the known read-only response root when only code has actions", () => {
    const response = chatgptResponse(fixture(`<div data-message-author-role="assistant"><div class="markdown"><pre><code>x</code><button>Copy code</button></pre></div></div>`));
    const mount = chatgptAdapter.findMountTarget(response);
    expect(mount).toBe(response);
    expect(mount!.closest("pre,code,table,a,button")).toBeNull();
  });
});
