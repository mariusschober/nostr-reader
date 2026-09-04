// Content script: selection -> provider adapter -> generic extraction.
// Never touches private keys; posts validated messages to the service worker.
import { adapterFor, pickResponse } from "../providers/adapters.js";
import { extractGeneric, meaningfulSelection, selectionDocument } from "../extraction/pipeline.js";

const MOUNT_ATTR = "data-reader-mounted";

function validOutbound(o: unknown): boolean {
  if (typeof o !== "object" || o === null) return false;
  const m = (o as Record<string, unknown>).markdown;
  return typeof m === "string" && m.length > 0 && m.length < 21 * 1024 * 1024;
}

async function captureForToolbar(): Promise<void> {
  const sel = window.getSelection()?.toString() ?? "";
  if (meaningfulSelection(sel)) {
    const doc = selectionDocument(sel, location.href, document.title);
    if (validOutbound(doc)) void chrome.runtime.sendMessage({ kind: "reader-capture", doc });
    return;
  }
  const adapter = adapterFor(location.href);
  if (adapter) {
    const picked = pickResponse(adapter.findAssistantResponses(document));
    if (picked) {
      const title = adapter.extractConversationTitle(document) ?? document.title;
      const doc = adapter.extractResponse(picked, title || "AI response", location.href);
      (doc as unknown as Record<string, unknown>).sourceType = adapter.id;
      if (validOutbound(doc)) void chrome.runtime.sendMessage({ kind: "reader-capture", doc });
      return;
    }
  }
  const doc = await extractGeneric(document, location.href);
  if (doc.confidence === "low") {
    void chrome.runtime.sendMessage({ kind: "reader-low-confidence", doc });
    return;
  }
  if (validOutbound(doc)) void chrome.runtime.sendMessage({ kind: "reader-capture", doc });
}

function mountInlineButtons(): void {
  let adapter: ReturnType<typeof adapterFor>;
  try { adapter = adapterFor(location.href); } catch { return; }
  if (!adapter) return;
  for (const resp of adapter.findAssistantResponses(document)) {
    if (resp.hasAttribute(MOUNT_ATTR)) continue;
    const mount = adapter.findMountTarget(resp);
    if (!mount || mount.querySelector(":scope > .reader-send")) continue;
    const btn = document.createElement("button");
    btn.className = "reader-send";
    btn.type = "button";
    btn.title = "Send to Reader";
    btn.setAttribute("aria-label", "Send to Reader");
    btn.textContent = "\u25CB";
    btn.style.cssText = "font-size:18px;color:currentColor;background:none;border:0;cursor:pointer;padding:2px 6px;";
    btn.addEventListener("click", async (ev) => {
      ev.stopPropagation();
      btn.textContent = "\u2026";
      try {
        const title = adapter.extractConversationTitle(document) ?? document.title;
        const doc = adapter.extractResponse(resp, title || "AI response", location.href);
        (doc as unknown as Record<string, unknown>).sourceType = adapter.id;
        await chrome.runtime.sendMessage({ kind: "reader-capture", doc });
        btn.textContent = "\u2713";
        btn.style.color = "green";
        setTimeout(() => { btn.textContent = "\u25CB"; btn.style.color = ""; }, 1800);
      } catch {
        btn.textContent = "!";
        btn.style.color = "red";
      }
    });
    mount.appendChild(btn);
    resp.setAttribute(MOUNT_ATTR, "1");
  }
}

chrome.runtime.onMessage.addListener((msg, _sender, sendResponse) => {
  if (msg?.kind === "reader-capture-now") {
    void captureForToolbar().then(() => sendResponse({ ok: true }));
    return true;
  }
  return false;
});

// Lightweight discovery only; extraction runs on explicit capture.
let scheduled = false;
new MutationObserver(() => {
  if (scheduled) return;
  scheduled = true;
  setTimeout(() => { scheduled = false; try { mountInlineButtons(); } catch { /* ignore */ } }, 800);
}).observe(document.documentElement, { childList: true, subtree: true });
try { mountInlineButtons(); } catch { /* ignore */ }
