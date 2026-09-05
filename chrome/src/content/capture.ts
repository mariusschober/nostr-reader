// Content scripts own capture gestures and feedback, never transport keys.
import { adapterFor, pickResponse } from "../providers/adapters.js";
import { extractGeneric, meaningfulSelection, selectionDocument } from "../extraction/pipeline.js";

let statusHost: HTMLElement | undefined;
function feedback(text: string): void {
  if (!statusHost?.isConnected) {
    statusHost = document.createElement("div");
    statusHost.dataset.readerUi = "status";
    const shadow = statusHost.attachShadow({ mode: "closed" });
    const status = document.createElement("div");
    status.setAttribute("role", "status");
    status.setAttribute("aria-live", "polite");
    status.style.cssText = "position:fixed;bottom:24px;right:24px;z-index:2147483647;max-width:320px;padding:12px 16px;background:#100f0f;color:#fffcf0;border:1px solid #6f6e69;border-radius:10px;font:14px/1.5 system-ui;box-shadow:0 4px 18px #0003;pointer-events:none";
    shadow.append(status);
    Object.defineProperty(statusHost, "readerStatus", { value: status });
    (document.body ?? document.documentElement).append(statusHost);
  }
  (statusHost as HTMLElement & { readerStatus: HTMLElement }).readerStatus.textContent = text;
}

async function sendCapture(doc: unknown): Promise<void> {
  const captureId = crypto.randomUUID();
  let response: any;
  // A lost response retries the same capture identity; the worker's atomic
  // capture/outbox transaction prevents duplicate transfer creation.
  try { response = await chrome.runtime.sendMessage({ kind: "reader-capture", captureId, doc }); }
  catch { response = await chrome.runtime.sendMessage({ kind: "reader-capture", captureId, doc }); }
  if (!response?.ok) throw new Error(response?.error || "Storage unavailable. Try again.");
  feedback(response.queued ? "Saved — connect your phone" : "Saved — waiting for your phone");
}

async function captureForToolbar(): Promise<void> {
  feedback("Saving…");
  try {
    const sel = window.getSelection()?.toString() ?? "";
    if (meaningfulSelection(sel)) {
      await sendCapture({ ...selectionDocument(sel, location.href, document.title), sourceType: "selection" });
      return;
    }
    const adapter = adapterFor(location.href);
    if (adapter) {
      const picked = pickResponse(adapter.findAssistantResponses(document));
      if (picked) {
        await sendCapture({ ...adapter.extractResponse(picked, adapter.extractConversationTitle(document) ?? document.title, location.href), sourceType: adapter.id });
        return;
      }
    }
    const doc = await extractGeneric(document, location.href);
    if (doc.confidence === "low") {
      feedback("Couldn’t identify an article. Select the text you want, then click Reader.");
      return;
    }
    await sendCapture(doc);
  } catch (error) { feedback(`Couldn’t save — ${error instanceof Error ? error.message : "try again"}`); }
}

function mountInlineButtons(): void {
  const adapter = adapterFor(location.href);
  if (!adapter) return;
  for (const response of adapter.findAssistantResponses(document)) {
    if (response.querySelector(".reader-send")) continue;
    const mount = adapter.findMountTarget(response);
    if (!mount) continue;
    const button = document.createElement("button");
    button.className = "reader-send";
    button.dataset.readerUi = "capture";
    button.type = "button";
    button.textContent = "Reader";
    button.title = "Save this response to Reader";
    button.setAttribute("aria-label", button.title);
    button.style.cssText = "font:12px system-ui;color:inherit;background:transparent;border:1px solid currentColor;border-radius:6px;cursor:pointer;min-height:32px;padding:4px 8px";
    button.addEventListener("click", async event => {
      if (!event.isTrusted) return;
      event.preventDefault(); event.stopPropagation();
      feedback("Saving…"); button.disabled = true;
      try {
        await sendCapture({ ...adapter.extractResponse(response, adapter.extractConversationTitle(document) ?? document.title, location.href), sourceType: adapter.id });
      } catch (error) { feedback(`Couldn’t save — ${error instanceof Error ? error.message : "try again"}`); }
      finally { button.disabled = false; }
    });
    mount.append(button);
  }
}

const owner = globalThis as typeof globalThis & { readerCaptureInstalled?: boolean };
if (!owner.readerCaptureInstalled) {
  owner.readerCaptureInstalled = true;
  chrome.runtime.onMessage.addListener((msg, _sender, sendResponse) => {
    if (msg?.kind === "reader-capture-feedback" && typeof msg.text === "string") {
      feedback(msg.text.slice(0, 400));
      sendResponse({ ok: true });
      return false;
    }
    if (msg?.kind !== "reader-capture-now") return false;
    void captureForToolbar().then(() => sendResponse({ ok: true }));
    return true;
  });
  let scheduled = false;
  new MutationObserver(changes => {
    if (scheduled || changes.every(change => (change.target as Element).closest?.("[data-reader-ui]"))) return;
    scheduled = true;
    setTimeout(() => { scheduled = false; try { mountInlineButtons(); } catch {} }, 300);
  }).observe(document.documentElement, { childList: true, subtree: true });
  try { mountInlineButtons(); } catch {}
}
