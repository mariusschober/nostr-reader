// Content scripts own capture gestures and feedback, never transport keys.
import { adapterFor, pickResponse } from "../providers/adapters.js";
import { extractGeneric, meaningfulSelection, selectionDocument } from "../extraction/pipeline.js";

import { CaptureFeedback } from "./capture-feedback.js";
import { isCaptureFeedbackState } from "../protocol/capture-feedback.js";

const feedback = new CaptureFeedback();
let watchTimer: ReturnType<typeof setTimeout> | undefined;
let watchedCaptureId: string | undefined;

function beginCapture(captureId: string): void {
  clearTimeout(watchTimer);
  watchedCaptureId = undefined;
  feedback.begin(captureId);
}

function watchReceipt(captureId: string, transferId: string): void {
  if (watchedCaptureId === captureId || !feedback.isPending(captureId)) return;
  clearTimeout(watchTimer);
  watchedCaptureId = captureId;
  let attempts = 0;
  const check = async () => {
    if (!feedback.isPending(captureId) || watchedCaptureId !== captureId) return;
    try {
      // Reads durable local state only; does not query relays or retransmit.
      // Recovers a missed push or receipt-before-notification worker crash.
      const result = await chrome.runtime.sendMessage({ kind: "reader-delivery-receipt", transferId });
      if (result?.ok && result.delivered === true) feedback.update(captureId, "delivered");
    } catch { /* Keep the truthful saved state; delivery still has durable alarms. */ }
    if (++attempts < 30 && feedback.isPending(captureId) && watchedCaptureId === captureId) {
      watchTimer = setTimeout(check, 2000);
    }
  };
  void check();
}

async function sendCapture(doc: unknown, captureId: string): Promise<void> {
  let response: any;
  // A lost response retries the same capture identity; the worker's atomic
  // capture/outbox transaction prevents duplicate transfer creation.
  try { response = await chrome.runtime.sendMessage({ kind: "reader-capture", captureId, doc }); }
  catch { response = await chrome.runtime.sendMessage({ kind: "reader-capture", captureId, doc }); }
  if (!response?.ok) throw new Error(response?.error || "Storage unavailable. Try again.");
  feedback.update(captureId, response.queued ? "unpaired" : "waiting");
  watchReceipt(captureId, response.transferId);
}

async function captureForToolbar(): Promise<void> {
  const captureId = crypto.randomUUID();
  beginCapture(captureId);
  try {
    const sel = window.getSelection()?.toString() ?? "";
    if (meaningfulSelection(sel)) {
      await sendCapture({ ...selectionDocument(sel, location.href, document.title), sourceType: "selection" }, captureId);
      return;
    }
    const adapter = adapterFor(location.href);
    if (adapter) {
      const picked = pickResponse(adapter.findAssistantResponses(document));
      if (picked) {
        await sendCapture({ ...adapter.extractResponse(picked, adapter.extractConversationTitle(document) ?? document.title, location.href), sourceType: adapter.id }, captureId);
        return;
      }
    }
    const doc = await extractGeneric(document, location.href);
    if (doc.confidence === "low") {
      feedback.update(captureId, "error", "Couldn’t identify an article. Select the text you want, then click Reader.");
      return;
    }
    await sendCapture(doc, captureId);
  } catch (error) { feedback.update(captureId, "error", `Couldn’t save — ${error instanceof Error ? error.message : "try again"}`); }
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
      const captureId = crypto.randomUUID();
      beginCapture(captureId); button.disabled = true;
      try {
        await sendCapture({ ...adapter.extractResponse(response, adapter.extractConversationTitle(document) ?? document.title, location.href), sourceType: adapter.id }, captureId);
      } catch (error) { feedback.update(captureId, "error", `Couldn’t save — ${error instanceof Error ? error.message : "try again"}`); }
      finally { button.disabled = false; }
    });
    mount.append(button);
  }
}

const owner = globalThis as typeof globalThis & { readerCaptureInstalled?: boolean };
if (!owner.readerCaptureInstalled) {
  owner.readerCaptureInstalled = true;
  chrome.runtime.onMessage.addListener((msg, _sender, sendResponse) => {
    if (msg?.kind === "reader-feedback-begin" && typeof msg.captureId === "string") {
      beginCapture(msg.captureId);
      sendResponse({ ok: true });
      return false;
    }
    if (msg?.kind === "reader-capture-feedback" && typeof msg.captureId === "string" && isCaptureFeedbackState(msg.state)) {
      feedback.update(msg.captureId, msg.state, msg.detail);
      if ((msg.state === "waiting" || msg.state === "unpaired") && typeof msg.transferId === "string") watchReceipt(msg.captureId, msg.transferId);
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
