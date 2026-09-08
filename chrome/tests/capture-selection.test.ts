/** @vitest-environment jsdom */
import { afterEach, expect, it, vi } from "vitest";

const state = vi.hoisted(() => ({ liveSelection: "", captured: undefined as any }));
vi.mock("../src/content/capture-feedback.js", () => ({
  CaptureFeedback: class {
    begin() { state.liveSelection += "Reader feedback"; }
    update() {}
    isPending() { return false; }
  },
}));
vi.mock("../src/providers/adapters.js", () => ({ adapterFor: () => null, pickResponse: () => null }));

afterEach(() => {
  delete (globalThis as any).readerCaptureInstalled;
  vi.restoreAllMocks(); vi.unstubAllGlobals(); vi.resetModules();
});

async function capture(selectionSnapshot?: string) {
  let listener: any;
  vi.stubGlobal("chrome", { runtime: {
    onMessage: { addListener(callback: any) { listener = callback; } },
    async sendMessage(message: any) {
      if (message.kind === "reader-capture") state.captured = message.doc;
      return { ok: true, queued: true, transferId: "test-transfer" };
    },
  } });
  vi.stubGlobal("MutationObserver", class { observe() {} });
  vi.spyOn(window, "getSelection").mockImplementation(() => ({ toString: () => state.liveSelection }) as Selection);
  await import("../src/content/capture.js");
  await new Promise<void>(resolve => listener({ kind: "reader-capture-now", selectionSnapshot }, {}, resolve));
  return state.captured;
}

it("keeps the pre-install selection when mounted buttons changed the live range", async () => {
  state.liveSelection = "A café 🌱Reader";
  const doc = await capture("A café 🌱");
  expect(doc.sourceType).toBe("selection");
  expect(doc.markdown).toBe("A café 🌱\n");
});

it("reads an existing content-script selection before feedback mutates the page", async () => {
  state.liveSelection = "x";
  const doc = await capture();
  expect(doc.sourceType).toBe("selection");
  expect(doc.markdown).toBe("x\n");
});
