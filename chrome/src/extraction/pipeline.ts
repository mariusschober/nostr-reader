// Extraction: selection -> provider adapter -> Defuddle -> Readability.
// Never an LLM, never a server; operates on a cloned DOM only.
import DOMPurify from "dompurify";
import Defuddle from "defuddle";
import { Readability } from "@mozilla/readability";
import TurndownService from "turndown";
import { escapePlainText } from "../protocol/core.js";

export interface CapturedDocument {
  title: string;
  markdown: string;
  sourceUrl: string;
  confidence: "high" | "low";
  reason?: string;
}

const turndown = new TurndownService({ headingStyle: "atx", codeBlockStyle: "fenced" });
turndown.addRule("cite", {
  filter: (n) => n.nodeName === "A" && (n as HTMLElement).textContent?.trim().match(/^\[\d+\]$/) != null,
  replacement: (content, node) => ` ${(node as HTMLAnchorElement).href} `,
});

export function meaningfulSelection(text: string): boolean {
  const t = text.trim();
  return t.length >= 80 || t.split(/\s+/).filter(Boolean).length >= 12;
}

function sanitizeHtml(html: string): string {
  const clean = DOMPurify.sanitize(html, {
    ALLOWED_TAGS: ["p", "h1", "h2", "h3", "h4", "br", "strong", "em", "b", "i", "a", "ul", "ol", "li", "blockquote", "pre", "code", "table", "thead", "tbody", "tr", "th", "td", "img", "hr"],
    ALLOWED_ATTR: ["href", "src", "alt", "title"],
  }) as unknown as string;
  return String(clean);
}

// Conservative pre-strip on a clone: cookie/consent, ads, nav/footer,
// newsletter asides, comment sections. Never touches <main>/<article>.
function stripNoise(root: ParentNode): void {
  const sels = [
    "script", "style", "noscript", "template", "[hidden]",
    '[class*="cookie" i]', '[id*="cookie" i]',
    '[class*="consent" i]', '[id*="consent" i]',
    '[class*="gdpr" i]',
    ".ads", '[class*="advert" i]', '[id*="advert" i]',
    "header nav", "nav",
    "footer",
    "aside",
    '[class*="newsletter" i]', '[class*="signup" i]',
    '[class*="comment" i]', '[id*="comment" i]',
    '[class*="related-article" i]', '[class*="recommend" i]',
  ];
  for (const sel of sels) {
    try {
      for (const el of [...(root as Document).querySelectorAll(sel)]) {
        const t = el as HTMLElement;
        // Never strip inside the main article element itself.
        if (t.closest("main article, article")) {
          if (!/cookie|consent|advert|newsletter|comment/i.test(t.className?.toString?.() ?? "")) continue;
        }
        t.remove();
      }
    } catch { /* next selector */ }
  }
}

function mdFromHtml(html: string): string {
  return turndown.turndown(html).replace(/[ \t]+$/gm, "").replace(/\n{3,}/g, "\n\n").trim() + "\n";
}

function score(md: string, sourceText: string): number {
  const words = md.split(/\s+/).filter(Boolean).length;
  const ratio = sourceText.length > 0 ? md.length / sourceText.length : 0;
  let s = 0;
  if (words > 120) s += 2; else if (words > 40) s += 1; else s -= 2;
  if (ratio > 0.08 && ratio < 0.9) s += 1; else s -= 1;
  if (/menu|cookie|subscribe|newsletter/i.test(md.slice(0, 400))) s -= 1;
  return s;
}

export async function extractGeneric(doc: Document, url: string): Promise<CapturedDocument> {
  const sourceText = doc.body?.innerText ?? "";
  // 1. Defuddle on a cleaned clone (primary). Defuddle lifts the article
  // H1 into its title field, so keep it for the title fallback below.
  let defuddleMd = "";
  let defuddleTitle = "";
  try {
    const clone = doc.cloneNode(true) as Document;
    stripNoise(clone);
    const parsed = new Defuddle(clone, { markdown: true, url: url }).parse();
    defuddleMd = String(parsed.content ?? "");
    defuddleTitle = String(parsed.title ?? "").trim();
  } catch { defuddleMd = ""; }
  // 2. Readability on another clone (fallback), sanitized -> markdown.
  let readabilityMd = "";
  try {
    const clone2 = doc.cloneNode(true) as Document;
    stripNoise(clone2);
    const art = new Readability(clone2 as never).parse();
    if (art?.content) readabilityMd = mdFromHtml(sanitizeHtml(art.content));
  } catch { readabilityMd = ""; }
  const sD = score(defuddleMd, sourceText);
  const sR = score(readabilityMd, sourceText);
  const best = sD >= sR ? defuddleMd : readabilityMd;
  const og = doc.querySelector("meta[property='og:title']")?.getAttribute("content")?.trim();
  const h1 = doc.querySelector("main h1, article h1")?.textContent?.trim();
  const title = (og || (defuddleTitle && defuddleTitle !== doc.title ? defuddleTitle : "") || h1 || doc.title || url).trim().slice(0, 500) || url;
  const withTitle = (md: string): string => {
    if (!md || md.includes(title)) return md;
    return `# ${title}\n\n${md}`;
  };
  if (!best || best.split(/\s+/).length < 30) {
    return { title, markdown: withTitle(best || ""), sourceUrl: url, confidence: "low", reason: "could not identify article confidently" };
  }
  return { title, markdown: withTitle(best), sourceUrl: url, confidence: "high" };
}

export function selectionDocument(sel: string, url: string, title: string): CapturedDocument {
  // Literal user selection: escape so it is never parsed as markdown source.
  return { title: title || "Selection", markdown: escapePlainText(sel.trim()) + "\n", sourceUrl: url, confidence: "high" };
}
