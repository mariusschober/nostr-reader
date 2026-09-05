/** @vitest-environment jsdom */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { CaptureFeedback } from "../src/content/capture-feedback.js";

let shadow: ShadowRoot;
const text = () => document.querySelector('[data-reader-ui="status"]') ? shadow.textContent : null;

beforeEach(() => {
  vi.useFakeTimers();
  document.body.replaceChildren();
  const attach = Element.prototype.attachShadow;
  vi.spyOn(Element.prototype, "attachShadow").mockImplementation(function(this: Element, options) {
    shadow = attach.call(this, options);
    return shadow;
  });
});
afterEach(() => { vi.restoreAllMocks(); vi.useRealTimers(); vi.unstubAllGlobals(); });

describe("capture notice lifecycle", () => {
  it("shows saving, durable save, then phone receipt and dismisses the confirmation", () => {
    const view = new CaptureFeedback();
    view.begin("first");
    expect(text()).toBe("Saving…");
    view.update("first", "waiting");
    expect(text()).toBe("Saved — waiting for your phone");
    view.update("first", "delivered");
    expect(text()).toBe("On your phone");
    expect(shadow.querySelector('[role="status"]')?.getAttribute("aria-live")).toBe("polite");
    vi.advanceTimersByTime(4999);
    expect(text()).toBe("On your phone");
    vi.advanceTimersByTime(1);
    expect(text()).toBeNull();
  });
  it("dismisses a pending notice without inventing delivery, then shows a late real receipt", () => {
    const view = new CaptureFeedback();
    view.begin("first"); view.update("first", "waiting");
    vi.advanceTimersByTime(8000);
    expect(text()).toBeNull();
    expect(view.isPending("first")).toBe(true);
    view.update("first", "delivered");
    expect(text()).toBe("On your phone");
    vi.advanceTimersByTime(5000);
    expect(text()).toBeNull();
  });
  it("a fast receipt cannot be overwritten by the delayed saved response", () => {
    const view = new CaptureFeedback();
    view.begin("first"); view.update("first", "delivered");
    vi.advanceTimersByTime(3000);
    view.update("first", "waiting");
    expect(text()).toBe("On your phone");
    vi.advanceTimersByTime(2000);
    expect(text()).toBeNull();
    view.update("first", "delivered");
    expect(text()).toBeNull();
  });
  it("late events and timers from an older capture cannot replace the newer notice", () => {
    const view = new CaptureFeedback();
    view.begin("first"); view.update("first", "waiting");
    vi.advanceTimersByTime(4000);
    view.begin("second"); view.update("second", "waiting");
    view.update("first", "delivered");
    vi.advanceTimersByTime(4001);
    expect(text()).toBe("Saved — waiting for your phone");
    view.update("second", "delivered");
    expect(text()).toBe("On your phone");
  });
  it("ignores an old capture arriving in a different document", () => {
    new CaptureFeedback().update("previous-document", "delivered");
    expect(text()).toBeNull();
  });
  it("repeated saved checks do not extend the notice forever", () => {
    const view = new CaptureFeedback();
    view.begin("first"); view.update("first", "unpaired");
    for (let i = 0; i < 4; i++) { vi.advanceTimersByTime(2000); view.update("first", "unpaired"); }
    expect(text()).toBeNull();
  });
  it("renders errors as literal text and dismisses them", () => {
    const view = new CaptureFeedback();
    view.begin("first"); view.update("first", "error", 'Couldn’t save <img src=x onerror="bad()">');
    expect(shadow.querySelector("img")).toBeNull();
    expect(text()).toContain("Couldn’t save");
    vi.advanceTimersByTime(8000);
    expect(text()).toBeNull();
  });
});

describe("real content-script feedback wiring", () => {
  it("toolbar selection tracks its receipt and menu feedback uses the same live notice", async () => {
    vi.resetModules();
    delete (globalThis as any).readerCaptureInstalled;
    let onMessage: Function;
    let delivered = false;
    const sent: any[] = [];
    vi.stubGlobal("MutationObserver", class { observe() {} disconnect() {} });
    vi.stubGlobal("chrome", { runtime: {
      onMessage: { addListener: (callback: Function) => { onMessage = callback; } },
      sendMessage: vi.fn(async (message: any) => {
        sent.push(message);
        return message.kind === "reader-capture" ? { ok: true, queued: false, transferId: "a".repeat(32) } : { ok: true, delivered };
      }),
    } });
    document.body.innerHTML = '<p id="selected">Constellation</p>';
    const range = document.createRange(); range.selectNodeContents(document.getElementById("selected")!);
    window.getSelection()!.removeAllRanges(); window.getSelection()!.addRange(range);
    await import("../src/content/capture.js");
    const response = new Promise(resolve => onMessage!({ kind: "reader-capture-now" }, {}, resolve));
    expect(text()).toBe("Saving…");
    await response;
    expect(text()).toBe("Saved — waiting for your phone");
    expect(sent[0].doc.markdown).toBe("Constellation\n");
    delivered = true;
    await vi.advanceTimersByTimeAsync(2000);
    expect(text()).toBe("On your phone");
    await vi.advanceTimersByTimeAsync(5000);
    expect(text()).toBeNull();
    onMessage!({ kind: "reader-feedback-begin", captureId: "menu" }, {}, () => {});
    expect(text()).toBe("Saving…");
    onMessage!({ kind: "reader-capture-feedback", captureId: "menu", state: "waiting", transferId: "b".repeat(32) }, {}, () => {});
    onMessage!({ kind: "reader-capture-feedback", captureId: "menu", state: "delivered" }, {}, () => {});
    expect(text()).toBe("On your phone");
    await vi.advanceTimersByTimeAsync(5000);
    expect(text()).toBeNull();
  });
});
