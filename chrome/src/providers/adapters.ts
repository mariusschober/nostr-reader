// Provider adapters: semantic-role-first selectors with structural fallbacks.
// Must never break generic toolbar capture if the DOM redesigns.
import { mdFromHtml, type CapturedDocument } from "../extraction/pipeline.js";

export interface ProviderAdapter {
  id: "chatgpt" | "claude" | "gemini" | "perplexity" | "notebook" | "grok" | "substack" | "x";
  matches(url: URL): boolean;
  findAssistantResponses(root: ParentNode): HTMLElement[];
  findMountTarget(response: HTMLElement): HTMLElement | null;
  extractResponse(response: HTMLElement, title: string, url: string): CapturedDocument;
  extractConversationTitle(doc: Document): string | null;
}

function q<T extends Element>(root: ParentNode, sels: string[]): T[] {
  for (const s of sels) {
    try {
      const own = (root as Element).matches?.(s) ? [root as Element] : [];
      const els = [...own, ...(root as Document).querySelectorAll(s)].filter(el => !el.closest("[hidden], [aria-hidden='true']")) as T[];
      if (els.length) return els;
    } catch { /* try next selector */ }
  }
  return [];
}

function subtreeMarkdown(el: HTMLElement, title: string, url: string): CapturedDocument {
  const clone = el.cloneNode(true) as HTMLElement;
  clone.querySelectorAll("button, nav, script, style, [data-reader-ui], [hidden], [aria-hidden='true']").forEach(node => node.remove());
  const markdown = mdFromHtml(clone.innerHTML);
  return { title, markdown, sourceUrl: url, confidence: markdown.trim() ? "high" : "low" };
}

function host(url: URL, expected: string): boolean {
  return url.protocol === "https:" && (url.hostname === expected || url.hostname === `www.${expected}`);
}

// Never mount capture controls inside editable composition surfaces. ChatGPT,
// Gemini, and Claude all normalize unknown elements inside ProseMirror /
// TipTap / Lexical editors into text, which turned a single injected button
// into endless persisted "Reader" text.
export const EDITABLE_CLOSEST_SELECTOR =
  'input,textarea,select,[contenteditable]:not([contenteditable="false" i]),[role="textbox" i],.ProseMirror,.tiptap,[data-lexical-editor],[data-testid*="composer" i],#prompt-textarea';

export function isEditableContext(el: Element | null): boolean {
  if (!el) return false;
  try {
    const html = el as HTMLElement;
    if (html.isContentEditable) return true;
    if (el.closest(EDITABLE_CLOSEST_SELECTOR)) return true;
  } catch { /* ignore */ }
  return false;
}

function isContentMount(el: Element): boolean {
  try {
    return !!el.closest("pre,code,table");
  } catch {
    return false;
  }
}

export function isSafeMountTarget(el: Element | null): boolean {
  if (!el || !(el instanceof Element)) return false;
  if (isEditableContext(el)) return false;
  // A harmless-looking div inside a link/button/code/table is still unsafe.
  if (el.closest('a,button,pre,code,table,[role="button" i]')) return false;
  const tag = (el.tagName || "").toLowerCase();
  if (tag === "input" || tag === "textarea" || tag === "select" || tag === "button" || tag === "a" || tag === "pre" || tag === "code") return false;
  try {
    if ((el as HTMLElement).isContentEditable) return false;
  } catch { /* ignore */ }
  return true;
}

// Prefer a real action row (copy / like / share grouping) at the end of the
// response. The final fallback is a known, read-only provider response root.
function findActionRow(response: HTMLElement, hints: string[]): HTMLElement | null {
  for (const sel of hints) {
    try {
      const hit = response.querySelector(sel);
      if (!hit) continue;
      let cur: Element | null = hit;
      for (let i = 0; i < 3 && cur && cur !== response; i++) {
        if (isSafeMountTarget(cur) && !isContentMount(cur) && cur.querySelectorAll("button").length >= 1) {
          if (cur.querySelectorAll("button").length >= 2) return cur as HTMLElement;
        }
        cur = cur.parentElement;
      }
      const parent = (hit as Element).parentElement;
      if (parent && isSafeMountTarget(parent) && !isContentMount(parent)) return parent as HTMLElement;
    } catch { /* try next hint */ }
  }
  try {
    const groups = [...response.querySelectorAll("div,span,footer,section")].filter((g) => {
      if (!isSafeMountTarget(g)) return false;
      if (isContentMount(g)) return false;
      if (g.closest("[data-reader-ui]")) return false;
      const direct = g.querySelectorAll(":scope > button");
      const all = g.querySelectorAll("button");
      if (direct.length >= 2) return true;
      if (all.length >= 2 && all.length <= 8 && (g.textContent || "").length < 400) return true;
      return false;
    }) as HTMLElement[];
    if (groups.length) return groups[groups.length - 1];
  } catch { /* no generic row */ }
  return null;
}

function safeResponseRoot(response: HTMLElement): HTMLElement | null {
  if (isEditableContext(response)) return null;
  if (!isSafeMountTarget(response)) return null;
  return response;
}

function firstButtonParentIfSafe(response: HTMLElement): HTMLElement | null {
  try {
    const first = response.querySelector("button");
    if (!first) return null;
    if (first.closest("pre,code,table")) return null;
    if (isEditableContext(first)) return null;
    const parent = first.parentElement;
    if (parent && isSafeMountTarget(parent) && !isContentMount(parent) && parent.querySelectorAll("button").length >= 2) {
      return parent as HTMLElement;
    }
  } catch { /* fall through */ }
  return null;
}

export const chatgptAdapter: ProviderAdapter = {
  id: "chatgpt",
  matches: (u) => host(u, "chatgpt.com") || host(u, "chat.openai.com"),
  findAssistantResponses: (r) => q<HTMLElement>(r, [
    '[data-message-author-role="assistant"]',
    '[data-testid^="conversation-turn-"] [data-message-author-role="assistant"]',
    '[data-testid^="conversation-turn-"]:has([data-message-author-role="assistant"]) .markdown',
  ]),
  findMountTarget: (el) => {
    if (isEditableContext(el)) return null;
    const row = findActionRow(el, [
      'button[aria-label*="Copy" i]',
      'button[aria-label*="Like" i]',
      'button[aria-label*="Dislike" i]',
      'button[data-testid*="copy" i]',
      '[data-testid*="action" i]',
    ]);
    if (row) return row;
    return firstButtonParentIfSafe(el) ?? safeResponseRoot(el);
  },
  extractResponse: (el, t, u) => subtreeMarkdown(el, t, u),
  extractConversationTitle: (d) => d.title?.replace(/ - ChatGPT$/, "").trim() || null,
};

export const claudeAdapter: ProviderAdapter = {
  id: "claude",
  matches: (u) => host(u, "claude.ai"),
  findAssistantResponses: (r) => q<HTMLElement>(r, [
    '[data-role="assistant"]', ".ClaudeResponse", '[data-testid="assistant-message"]',
  ]),
  findMountTarget: (el) => {
    if (isEditableContext(el)) return null;
    const row = findActionRow(el, [
      'button[aria-label*="Copy" i]',
      'button[aria-label*="Like" i]',
      'button[aria-label*="Dislike" i]',
    ]);
    if (row) return row;
    return firstButtonParentIfSafe(el) ?? safeResponseRoot(el);
  },
  extractResponse: (el, t, u) => subtreeMarkdown(el, t, u),
  extractConversationTitle: (d) => d.title?.replace(/ - Claude$/, "").trim() || null,
};

export const geminiAdapter: ProviderAdapter = {
  id: "gemini",
  matches: (u) => host(u, "gemini.google.com"),
  findAssistantResponses: (r) => q<HTMLElement>(r, [
    ".assistant-message", ".response-container", "[data-assistant-response]",
  ]),
  findMountTarget: (el) => {
    if (isEditableContext(el)) return null;
    const row = findActionRow(el, [
      'button[aria-label*="Like" i]',
      'button[aria-label*="Dislike" i]',
      'button[aria-label*="Copy" i]',
      'button[aria-label*="Share" i]',
      ".response-container footer",
      ".assistant-message footer",
    ]);
    if (row) return row;
    return firstButtonParentIfSafe(el) ?? safeResponseRoot(el);
  },
  extractResponse: (el, t, u) => subtreeMarkdown(el, t, u),
  extractConversationTitle: (d) => d.title?.replace(/ - Gemini$/, "").trim() || null,
};

export const perplexityAdapter: ProviderAdapter = {
  id: "perplexity",
  matches: (u) => host(u, "perplexity.ai"),
  findAssistantResponses: (r) => q<HTMLElement>(r, [
    ".answer .prose", ".answer", "[data-answer]",
  ]),
  findMountTarget: (el) => {
    if (isEditableContext(el)) return null;
    const row = findActionRow(el, [
      'button[aria-label*="Copy" i]',
      'button[aria-label*="Like" i]',
    ]);
    if (row) return row;
    return firstButtonParentIfSafe(el) ?? safeResponseRoot(el);
  },
  extractResponse: (el, t, u) => subtreeMarkdown(el, t, u),
  extractConversationTitle: (d) => d.title?.replace(/ - Perplexity$/, "").trim() || null,
};

/** Conservative candidate scopes. Live provider acceptance is recorded separately. */
function scopedAdapter(id: ProviderAdapter["id"], hosts: string[], routes: RegExp, selectors: string[]): ProviderAdapter {
  return {
    id,
    matches: url => hosts.some(name => host(url, name)) && routes.test(url.pathname),
    findAssistantResponses: root => {
      const found = q<HTMLElement>(root, selectors);
      if (id !== "x") return found;
      const path = (root as Document).location?.pathname ?? (root as Element).ownerDocument?.location?.pathname;
      return found.filter(el => el.matches('[data-testid="articleContent"]') || [...(el.closest('article')?.querySelectorAll('a[href]') ?? [])]
        .some(link => { try { return new URL((link as HTMLAnchorElement).href).pathname === path; } catch { return false; } }));
    },
    findMountTarget: (response) => safeResponseRoot(response),
    extractResponse: (response, title, url) => subtreeMarkdown(response, title, url),
    extractConversationTitle: doc => doc.title.trim() || null,
  };
}
export const notebookAdapter = scopedAdapter("notebook", ["notebook.google.com"], /^\/(?:notebook|chat|notes)(?:\/|$)/,
  ['[data-message-author-role="assistant"]', '[data-role="assistant"]', '[data-testid="text-note"]']);
export const grokAdapter = scopedAdapter("grok", ["grok.com"], /^\/(?:chat(?:\/|$)|$)/,
  ['[data-message-author-role="assistant"]', '[data-role="assistant"]', '[data-testid="assistant-message"]']);
export const substackAdapter = scopedAdapter("substack", ["substack.com"], /^\/home\/post/,
  ['[role="dialog"] .available-content', '[role="dialog"] .body.markup', 'article .available-content']);
export const xAdapter = scopedAdapter("x", ["x.com"], /^\/[^/]+\/(?:status|article)\//,
  ['article[data-testid="tweet"] [data-testid="tweetText"]', '[data-testid="articleContent"]']);

export const adapters: ProviderAdapter[] = [chatgptAdapter, claudeAdapter, geminiAdapter, perplexityAdapter, notebookAdapter, grokAdapter, substackAdapter, xAdapter];

export function adapterFor(url: string): ProviderAdapter | null {
  let u: URL;
  try { u = new URL(url); } catch { return null; }
  return adapters.find((a) => { try { return a.matches(u); } catch { return false; } }) ?? null;
}

/** Fallback chain: nearest-to-viewport-center -> latest -> null. */
export function pickResponse(responses: HTMLElement[]): HTMLElement | null {
  if (!responses.length) return null;
  const cy = window.innerHeight / 2;
  let best: HTMLElement | null = null;
  let bestD = Infinity;
  for (const el of responses) {
    const r = el.getBoundingClientRect();
    if (r.bottom < 0 || r.top > window.innerHeight) continue;
    const d = Math.abs((r.top + r.bottom) / 2 - cy);
    if (d < bestD) { bestD = d; best = el; }
  }
  return best ?? responses[responses.length - 1] ?? null;
}
