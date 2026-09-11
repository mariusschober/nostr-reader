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
turndown.addRule("reader-code", {
  filter: "pre",
  replacement: (_content, node) => {
    const text = node.textContent ?? "";
    const longest = Math.max(0, ...(text.match(/`+/g) ?? []).map(value => value.length));
    const fence = "`".repeat(Math.max(3, longest + 1));
    return `\n\n${fence}\n${text.replace(/\n$/, "")}\n${fence}\n\n`;
  },
});
turndown.addRule("reader-table", {
  filter: "table",
  replacement: (_content, node) => {
    const rows = [...(node as HTMLTableElement).rows].map(row => [...row.cells].map(cell =>
      turndown.turndown(cell.innerHTML).trim().replace(/\|/g, "\\|").replace(/\r?\n/g, "<br>")));
    if (!rows.length) return "";
    const width = Math.max(...rows.map(row => row.length));
    const render = (row: string[]) => `| ${Array.from({ length: width }, (_, index) => row[index] ?? "").join(" | ")} |`;
    return `\n\n${render(rows[0])}\n${render(Array(width).fill("---"))}\n${rows.slice(1).map(render).join("\n")}\n\n`;
  },
});

export function meaningfulSelection(text: string): boolean {
  const t = text.trim();
  return t.length > 0;
}

// Inline tags the page can lay out as block-level boxes with CSS.
const INLINE_TAG_SELECTOR = "span,a,b,strong,i,em,small,sub,sup,label,u,s,code,time";
const BLOCK_DISPLAYS = new Set([
  "block", "flex", "grid", "list-item", "flow-root",
  "table", "table-row", "table-cell", "table-caption",
]);
const WORD = /[\p{L}\p{N}]/u;

/**
 * Class tokens that the live page renders as block-level boxes even though the
 * tag is inline (a <span style="display:block"> eyebrow, flex legend items).
 * Read from real computed style on the live document; applied to the sanitized
 * clone, which cannot resolve styles once detached.
 */
export function collectVisualBlockClasses(doc: Document): Set<string> {
  const classes = new Set<string>();
  const view = doc.defaultView;
  if (!view?.getComputedStyle) return classes;
  for (const el of doc.querySelectorAll(INLINE_TAG_SELECTOR)) {
    try {
      if (BLOCK_DISPLAYS.has(view.getComputedStyle(el).display)) el.classList.forEach(c => classes.add(c));
    } catch { /* detached or unsupported node */ }
  }
  return classes;
}

/**
 * A CSS-blind Markdown converter fuses text from two visually separate boxes
 * (e.g. `Knowledge workers` + `All other workers`) into one word when the
 * source has no whitespace between the tags. Insert a single joining space at
 * those boundaries only; genuine inline formatting is left untouched.
 */
export function separateVisuallyBlockInline(root: ParentNode, isBlock: (el: Element) => boolean): number {
  let inserted = 0;
  for (const el of [...root.querySelectorAll(INLINE_TAG_SELECTOR)]) {
    if (!isBlock(el)) continue;
    // Never touch fenced code: its whitespace is semantically meaningful.
    if (el.closest("pre")) continue;
    if (joinBoundary(el, "before")) inserted++;
    if (joinBoundary(el, "after")) inserted++;
  }
  return inserted;
}

function edgeChar(node: Node | null, edge: "start" | "end"): string | null {
  if (!node) return null;
  const text = node.nodeType === 3 ? node.nodeValue ?? "" : node.textContent ?? "";
  return edge === "start" ? text.slice(0, 1) : text.slice(-1);
}

function joinBoundary(el: Element, side: "before" | "after"): boolean {
  const neighbour = side === "before" ? el.previousSibling : el.nextSibling;
  if (!neighbour) return false;
  if (neighbour.nodeType === 3 && (side === "before" ? /\s$/ : /^\s/).test(neighbour.nodeValue ?? "")) return false;
  const outside = side === "before" ? edgeChar(neighbour, "end") : edgeChar(neighbour, "start");
  const inside = side === "before" ? edgeChar(el, "start") : edgeChar(el, "end");
  if (!outside || !inside || !WORD.test(outside) || !WORD.test(inside)) return false;
  const parent = el.parentNode;
  const doc = el.ownerDocument;
  if (!parent || !doc) return false;
  parent.insertBefore(doc.createTextNode(" "), side === "before" ? el : neighbour);
  return true;
}

function sanitizeHtml(html: string): string {
  const clean = DOMPurify.sanitize(html, {
    ALLOWED_TAGS: ["p", "h1", "h2", "h3", "h4", "br", "strong", "em", "b", "i", "a", "ul", "ol", "li", "blockquote", "pre", "code", "table", "thead", "tbody", "tr", "th", "td", "img", "hr"],
    ALLOWED_ATTR: ["href", "src", "alt", "title", "start"],
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

export function mdFromHtml(html: string): string {
  return turndown.turndown(sanitizeHtml(html)).replace(/[ \t]+$/gm, "").replace(/\n{3,}/g, "\n\n").trim() + "\n";
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
  const sourceText = doc.body?.innerText ?? doc.body?.textContent ?? "";
  // Read the real computed display from the live page. Clones are detached and
  // cannot resolve styles, so carry the visually-block class tokens across.
  const visualBlockClasses = collectVisualBlockClasses(doc);
  const isVisualBlock = (el: Element): boolean => {
    for (const token of el.classList) if (visualBlockClasses.has(token)) return true;
    return false;
  };
  // 1. Defuddle on a cleaned clone (primary). Defuddle lifts the article
  // H1 into its title field, so keep it for the title fallback below.
  let defuddleMd = "";
  let defuddleTitle = "";
  try {
    const clone = doc.cloneNode(true) as Document;
    stripNoise(clone);
    separateVisuallyBlockInline(clone, isVisualBlock);
    const parsed = new Defuddle(clone, { markdown: false, url: url }).parse();
    defuddleMd = mdFromHtml(String(parsed.content ?? ""));
    defuddleTitle = String(parsed.title ?? "").trim();
  } catch { defuddleMd = ""; }
  // 2. Readability on another clone (fallback), sanitized -> markdown.
  let readabilityMd = "";
  try {
    const clone2 = doc.cloneNode(true) as Document;
    stripNoise(clone2);
    separateVisuallyBlockInline(clone2, isVisualBlock);
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
  return { title, markdown: withTitle(best), sourceUrl: url, confidence: Math.max(sD, sR) >= 1 ? "high" : "low", reason: Math.max(sD, sR) >= 1 ? undefined : "Article extraction is uncertain" };
}

export function selectionDocument(sel: string, url: string, title: string): CapturedDocument {
  // Literal user selection: escape so it is never parsed as markdown source.
  return { title: title || "Selection", markdown: escapePlainText(sel.trim()) + "\n", sourceUrl: url, confidence: "high" };
}
