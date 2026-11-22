// Content scripts own capture gestures and feedback, never transport keys.
import { adapterFor, pickResponse, isEditableContext, isSafeMountTarget } from "../providers/adapters.js";
import { extractGeneric, meaningfulSelection, selectionDocument } from "../extraction/pipeline.js";

import { CaptureFeedback } from "./capture-feedback.js";
import { isCaptureFeedbackState } from "../protocol/capture-feedback.js";

const feedback = new CaptureFeedback();
let watchTimer: ReturnType<typeof setTimeout> | undefined;
let watchedCaptureId: string | undefined;

// Remount cooldown: frameworks (React/ProseMirror) may strip our button during
// rerender. Remounting synchronously on every strip + observer tick is what
// turned one bad mount into endless "Reader" text. Cooldown breaks the loop.
const mounted = new WeakMap<HTMLElement, {
  at: number;
  remounts: number;
  retry?: ReturnType<typeof setTimeout>;
}>();
const REMOUNT_COOLDOWN_MS = 5000;
const MAX_REMOUNTS = 2;
const ownedControls = new WeakSet<HTMLButtonElement>();

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

function previewUncertain(doc: Awaited<ReturnType<typeof extractGeneric>>): void {
  document.querySelector('[data-reader-ui="preview"]')?.remove();
  const host = document.createElement("div"); host.dataset.readerUi = "preview";
  const root = host.attachShadow({ mode: "closed" });
  const panel = document.createElement("section");
  panel.setAttribute("role", "dialog"); panel.setAttribute("aria-label", "Review extracted article");
  panel.style.cssText = "position:fixed;right:20px;bottom:20px;z-index:2147483647;max-width:440px;max-height:80vh;overflow:auto;padding:20px;background:Canvas;color:CanvasText;border:1px solid GrayText;border-radius:12px;font:16px system-ui;color-scheme:light dark";
  const heading = document.createElement("h2"); heading.textContent = "Check this capture";
  const description = document.createElement("p"); description.textContent = "Reader is uncertain about this article. Save the extracted text below, or close this preview and select exactly what you want.";
  const preview = document.createElement("pre"); preview.style.cssText = "white-space:pre-wrap;max-height:35vh;overflow:auto;font:14px system-ui";
  preview.textContent = doc.markdown.slice(0, 2400) + (doc.markdown.length > 2400 ? "\n… Preview shortened; Save keeps the full extraction." : "");
  const save = document.createElement("button"); save.textContent = "Save extracted article"; save.disabled = !doc.markdown.trim();
  save.addEventListener("click", event => {
    if (!event.isTrusted) return;
    host.remove();
    // Explicit confirmation is a new gesture. The extraction warning ended
    // the earlier notice, so its identity cannot show saving/error/receipt.
    const captureId = crypto.randomUUID();
    beginCapture(captureId);
    void sendCapture(doc, captureId).catch(error => feedback.update(captureId, "error", String(error)));
  });
  const close = document.createElement("button"); close.textContent = "Use selection instead";
  close.addEventListener("click", event => { if (event.isTrusted) host.remove(); });
  panel.append(heading, description, preview, save, close); root.append(panel); document.documentElement.append(host); save.focus();
}

async function captureForToolbar(selectionSnapshot?: string): Promise<void> {
  // Read before feedback inserts its UI into a live select-all range.
  const sel = selectionSnapshot ?? window.getSelection()?.toString() ?? "";
  const captureId = crypto.randomUUID();
  beginCapture(captureId);
  try {
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
      feedback.update(captureId, "error", "Check the capture preview, or select the text you want.");
      previewUncertain(doc);
      return;
    }
    await sendCapture(doc, captureId);
  } catch (error) { feedback.update(captureId, "error", `Couldn’t save — ${error instanceof Error ? error.message : "try again"}`); }
}

function mountInlineButtons(root: ParentNode = document): void {
  const adapter = adapterFor(location.href);
  if (!adapter) return;
  for (const response of adapter.findAssistantResponses(root)) {
    // Edit / Canvas mode: the assistant node itself becomes editable. Never
    // mount there; remove any stale button so ProseMirror cannot convert it
    // to persisted "Reader" text.
    if (!response.isConnected) continue;
    if (!isSafeMountTarget(response)) {
      clearTimeout(mounted.get(response)?.retry);
      mounted.delete(response);
      try {
        response.querySelectorAll('.reader-send[data-reader-ui="capture"]').forEach((node) => node.remove());
      } catch { /* ignore */ }
      continue;
    }
    response.dataset.readerResponse = adapter.id;
    const controls = [...response.querySelectorAll<HTMLButtonElement>('.reader-send[data-reader-ui="capture"]')];
    // A response replacement may retain a cloned control. Keep at most one.
    controls.slice(1).forEach(node => node.remove());
    const existing = controls[0];
    const streaming = !!response.closest('[aria-busy="true"], [data-is-streaming="true"]');
    if (existing) {
      const parent = existing.parentElement;
      const poisoned =
        !parent ||
        !ownedControls.has(existing) ||
        !isSafeMountTarget(parent) ||
        isEditableContext(existing);
      if (poisoned) {
        try { existing.remove(); } catch { /* ignore */ }
        clearTimeout(mounted.get(response)?.retry);
        mounted.delete(response);
      } else {
        existing.disabled = streaming;
        continue;
      }
    } else {
      // Button was stripped by a framework rerender. Do not remount in a
      // tight observer loop; wait for cooldown.
      const state = mounted.get(response);
      if (state) {
        if (state.remounts >= MAX_REMOUNTS) continue;
        const remaining = state.at + REMOUNT_COOLDOWN_MS - Date.now();
        if (remaining > 0) {
          // A settled strip gets its own bounded retry, even without another
          // provider mutation. Repeated stripping cannot schedule a hot loop.
          state.retry ??= setTimeout(() => {
            state.retry = undefined;
            if (response.isConnected) mountInlineButtons(response);
          }, remaining);
          continue;
        }
      }
    }
    const mount = adapter.findMountTarget(response);
    if (!mount || !isSafeMountTarget(mount)) continue;
    if (!mount.isConnected) continue;
    if (mount !== response && !response.contains(mount)) continue;
    const button = document.createElement("button");
    ownedControls.add(button);
    button.className = "reader-send";
    button.dataset.readerUi = "capture";
    button.type = "button";
    button.disabled = streaming;
    button.textContent = "Reader";
    button.title = "Save this response to Reader";
    button.setAttribute("aria-label", button.title);
    button.setAttribute("contenteditable", "false");
    try {
      (button as HTMLElement).contentEditable = "false";
      (button as unknown as { draggable: boolean }).draggable = false;
    } catch { /* ignore */ }
    button.style.cssText = "font:12px/1 system-ui,sans-serif;color:inherit;background:transparent;border:1px solid currentColor;border-radius:999px;cursor:pointer;min-height:32px;padding:6px 12px;display:inline-flex;align-items:center;flex-shrink:0;margin-left:8px;position:relative;z-index:1;user-select:none;white-space:nowrap";
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
    try {
      mount.append(button);
      const previous = mounted.get(response);
      clearTimeout(previous?.retry);
      mounted.set(response, { at: Date.now(), remounts: previous ? previous.remounts + 1 : 0 });
    } catch { /* next response */ }
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
    void captureForToolbar(typeof msg.selectionSnapshot === "string" ? msg.selectionSnapshot : undefined)
      .then(() => sendResponse({ ok: true }));
    return true;
  });
  let scheduled = false;
  const dirty = new Set<ParentNode>();
  new MutationObserver(changes => {
    for (const change of changes) {
      // Ignore our own button insertion; its parent is the action row, not
      // a data-reader-ui node, so filter addedNodes explicitly.
      try {
        if (change.type === "childList" && change.addedNodes.length && !change.removedNodes.length) {
          let onlyOurs = true;
          for (const node of change.addedNodes) {
            if (node instanceof Element) {
              if (!node.matches("[data-reader-ui]")) {
                onlyOurs = false;
                break;
              }
            } else {
              onlyOurs = false;
              break;
            }
          }
          if (onlyOurs) continue;
        }
      } catch { /* fall through to generic handling */ }
      const target = change.target instanceof Element ? change.target : change.target.parentElement;
      if (!target || target.closest("[data-reader-ui]")) continue;
      if (change.type === "attributes" && change.attributeName === "data-reader-response") continue;
      dirty.add(target.closest("[data-reader-response]") ?? target);
    }
    if (scheduled || !dirty.size) return;
    scheduled = true;
    setTimeout(() => {
      scheduled = false;
      const roots = [...dirty]; dirty.clear();
      for (const root of roots) try { mountInlineButtons(root); } catch {}
    }, 300);
  }).observe(document.documentElement, { childList: true, subtree: true, attributes: true });
  try { mountInlineButtons(); } catch {}
}
