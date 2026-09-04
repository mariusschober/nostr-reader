// Provider adapters: semantic-role-first selectors with structural fallbacks.
// Must never break generic toolbar capture if the DOM redesigns.
import type { CapturedDocument } from "../extraction/pipeline.js";

export interface ProviderAdapter {
  id: "chatgpt" | "claude" | "gemini" | "perplexity";
  matches(url: URL): boolean;
  findAssistantResponses(root: ParentNode): HTMLElement[];
  findMountTarget(response: HTMLElement): HTMLElement | null;
  extractResponse(response: HTMLElement, title: string, url: string): CapturedDocument;
  extractConversationTitle(doc: Document): string | null;
}

function q<T extends Element>(root: ParentNode, sels: string[]): T[] {
  for (const s of sels) {
    try {
      const els = [...(root as Document).querySelectorAll(s)] as T[];
      if (els.length) return els;
    } catch { /* try next selector */ }
  }
  return [];
}

function subtreeMarkdown(el: HTMLElement, title: string, url: string): CapturedDocument {
  // Deterministic DOM -> markdown for an assistant subtree (no Readability).
  const parts: string[] = [];
  const walk = (n: Node): void => {
    if (n.nodeType === 3) { parts.push(n.textContent ?? ""); return; }
    if (n.nodeType !== 1) return;
    const e = n as HTMLElement;
    const tag = e.tagName;
    if (/^(SCRIPT|STYLE|BUTTON|NAV)$/.test(tag)) return;
    if (/^H([1-4])$/.test(tag)) parts.push(`\n${"#".repeat(Number(tag[1]))} ${(e.textContent ?? "").trim()}\n`);
    else if (tag === "P" || tag === "DIV") { [...e.childNodes].forEach(walk); parts.push("\n\n"); }
    else if (tag === "LI") { parts.push(`\n- `); [...e.childNodes].forEach(walk); }
    else if (tag === "PRE" || tag === "CODE") parts.push(`\`${(e.textContent ?? "")}\``);
    else if (tag === "A") parts.push(`[${(e.textContent ?? "").trim()}](${(e as HTMLAnchorElement).href})`);
    else if (tag === "BLOCKQUOTE") parts.push(`\n> ${(e.textContent ?? "").trim()}\n`);
    else [...e.childNodes].forEach(walk);
  };
  walk(el);
  const md = parts.join("").replace(/[ \t]+$/gm, "").replace(/\n{3,}/g, "\n\n").trim() + "\n";
  return { title, markdown: md, sourceUrl: url, confidence: "high" };
}

export const chatgptAdapter: ProviderAdapter = {
  id: "chatgpt",
  matches: (u) => u.hostname.includes("chatgpt.com") || u.hostname.includes("chat.openai.com"),
  findAssistantResponses: (r) => q<HTMLElement>(r, [
    '[data-message-author-role="assistant"]',
    '[data-testid^="conversation-turn-"] [data-message-author-role="assistant"]',
    ".markdown",
  ]),
  findMountTarget: (el) => el.querySelector("button")?.parentElement ?? el,
  extractResponse: (el, t, u) => subtreeMarkdown(el, t, u),
  extractConversationTitle: (d) => d.title?.replace(/ - ChatGPT$/, "").trim() || null,
};

export const claudeAdapter: ProviderAdapter = {
  id: "claude",
  matches: (u) => u.hostname.includes("claude.ai"),
  findAssistantResponses: (r) => q<HTMLElement>(r, [
    '[data-role="assistant"]', ".ClaudeResponse", '[data-testid="assistant-message"]',
  ]),
  findMountTarget: (el) => el.querySelector("button")?.parentElement ?? el,
  extractResponse: (el, t, u) => subtreeMarkdown(el, t, u),
  extractConversationTitle: (d) => d.title?.replace(/ - Claude$/, "").trim() || null,
};

export const geminiAdapter: ProviderAdapter = {
  id: "gemini",
  matches: (u) => u.hostname.includes("gemini.google.com"),
  findAssistantResponses: (r) => q<HTMLElement>(r, [
    ".assistant-message", ".response-container", "[data-assistant-response]",
  ]),
  findMountTarget: (el) => el.querySelector("button")?.parentElement ?? el,
  extractResponse: (el, t, u) => subtreeMarkdown(el, t, u),
  extractConversationTitle: (d) => d.title?.replace(/ - Gemini$/, "").trim() || null,
};

export const perplexityAdapter: ProviderAdapter = {
  id: "perplexity",
  matches: (u) => u.hostname.includes("perplexity.ai"),
  findAssistantResponses: (r) => q<HTMLElement>(r, [
    ".answer .prose", ".answer", "[data-answer]",
  ]),
  findMountTarget: (el) => el.querySelector("button")?.parentElement ?? el,
  extractResponse: (el, t, u) => subtreeMarkdown(el, t, u),
  extractConversationTitle: (d) => d.title?.replace(/ - Perplexity$/, "").trim() || null,
};

export const adapters: ProviderAdapter[] = [chatgptAdapter, claudeAdapter, geminiAdapter, perplexityAdapter];

export function adapterFor(url: string): ProviderAdapter | null {
  const u = new URL(url);
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
