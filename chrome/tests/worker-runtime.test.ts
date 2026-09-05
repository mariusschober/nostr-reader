import { beforeEach, describe, expect, it, vi } from "vitest";
import { IDBFactory, IDBObjectStore } from "fake-indexeddb";

// Execute the production worker with browser-owned persistence preserved between
// worker instances. No transport is mocked: unpaired captures must never send.
let storage: Record<string, any>;
let alarms: Map<string, any>;
let listeners: Record<string, Function>;
const sender = { id: "reader-test", url: "https://example.org/fixture", tab: { id: 1 } };
const doc = { title: "Synthetic capture", markdown: "Constellation\n", sourceType: "selection" };
const captureId = "a".repeat(32);

async function boot() {
  vi.resetModules();
  listeners = {};
  const event = (name: string) => ({ addListener: (callback: Function) => { listeners[name] = callback; } });
  vi.stubGlobal("chrome", {
    runtime: { id: sender.id, getURL: (path: string) => `chrome-extension://${sender.id}/${path}`,
      onMessage: event("message"), onInstalled: event("install"), onStartup: event("startup") },
    storage: { local: {
      get: async (keys: string | string[]) => Object.fromEntries((Array.isArray(keys) ? keys : [keys]).map(key => [key, structuredClone(storage[key])])),
      set: async (values: object) => { Object.assign(storage, structuredClone(values)); },
      remove: async (keys: string[]) => { keys.forEach(key => delete storage[key]); },
      setAccessLevel: vi.fn(async () => {}),
    } },
    alarms: { get: async (name: string) => alarms.get(name), create: async (name: string, value: any) => { alarms.set(name, { name, scheduledTime: value.when }); },
      clear: async (name: string) => alarms.delete(name), onAlarm: event("alarm") },
    action: { onClicked: event("action"), setBadgeText: vi.fn(async () => {}), setTitle: vi.fn(async () => {}) },
    tabs: { sendMessage: vi.fn(async () => ({ ok: true })) },
    scripting: { executeScript: vi.fn(async () => [{ frameId: 0, documentId: "document-1" }]) },
    commands: { onCommand: event("command") },
    contextMenus: { onClicked: event("menu"), create: vi.fn() },
  });
  return import("../src/background/service-worker.js");
}
async function records(name: string): Promise<any[]> {
  return new Promise((resolve, reject) => {
    const open = indexedDB.open("reader-outbox", 2);
    open.onsuccess = () => {
      const db = open.result;
      const request = db.transaction(name).objectStore(name).getAll();
      request.onsuccess = () => { resolve(request.result); db.close(); };
      request.onerror = () => reject(request.error);
    };
    open.onerror = () => reject(open.error);
  });
}
async function message(value: object, from = sender): Promise<any> {
  return new Promise(resolve => listeners.message(value, from, resolve));
}

beforeEach(() => {
  vi.restoreAllMocks();
  vi.stubGlobal("indexedDB", new IDBFactory());
  storage = {}; alarms = new Map();
});

describe("production worker persistence and request boundaries", () => {
  it("commits unpaired capture and outbox before returning saved; schedules no pairing poll", async () => {
    await boot();
    const reply = await message({ kind: "reader-capture", doc, captureId });
    expect(reply).toMatchObject({ ok: true, queued: true });
    expect(await records("captures")).toMatchObject([{ captureId, transferId: reply.transferId, markdown: doc.markdown }]);
    expect(await records("items")).toMatchObject([{ transferId: reply.transferId, attemptCount: 0 }]);
    expect(alarms.has("reader-pairing-recovery")).toBe(false);
  });
  it("a lost response followed by worker replacement and message retry creates one transfer", async () => {
    let worker = await boot();
    await worker.queueCapture(doc, captureId); // caller loses the result
    const before = await records("items");
    worker = await boot(); // JS owners disappear, browser persistence stays
    const recovered = await worker.queueCapture(doc, captureId);
    expect(recovered.transferId).toBe(before[0].transferId);
    expect(await records("items")).toHaveLength(1);
    expect(await records("captures")).toHaveLength(1);
  });
  it("concurrent repeated messages share one durable result; later deliberate capture stays possible", async () => {
    const worker = await boot();
    const results = await Promise.all(Array.from({ length: 10 }, () => worker.queueCapture(doc, captureId)));
    expect(new Set(results.map(item => item.transferId)).size).toBe(1);
    await worker.queueCapture(doc, "b".repeat(32));
    expect(await records("items")).toHaveLength(2);
    expect(await records("captures")).toHaveLength(2);
  });
  it("quota failure during capture commit cannot return saved or strand a send intent", async () => {
    await boot();
    const original = IDBObjectStore.prototype.put;
    vi.spyOn(IDBObjectStore.prototype, "put").mockImplementation(function(this: IDBObjectStore, ...args: Parameters<typeof original>) {
      if (this.name === "captures") { throw new DOMException("Storage full", "QuotaExceededError"); }
      return original.apply(this, args);
    });
    expect(await message({ kind: "reader-capture", doc, captureId })).toMatchObject({ ok: false });
    expect(await records("items")).toHaveLength(0);
    expect(await records("captures")).toHaveLength(0);
  });
  it("web content cannot invoke privileged status or identity operations", async () => {
    await boot();
    for (const kind of ["reader-status", "reader-start-pairing", "reader-disconnect", "reader-retry"]) {
      expect(await message({ kind })).toMatchObject({ ok: false, error: expect.stringContaining("Untrusted") });
    }
    expect(storage).toEqual({});
  });
  it("registered options page is trusted even when Chrome supplies its tab", async () => {
    await boot();
    const reply = await message({ kind: "reader-status" }, { ...sender, url: `chrome-extension://${sender.id}/src/ui/options.html` });
    expect(reply).toMatchObject({ ok: true, paired: false, pending: 0 });
  });
  it("malformed capture cannot create durable content or an identity", async () => {
    await boot();
    for (const invalid of [{ ...doc, markdown: " " }, { ...doc, title: {} }, { ...doc, sourceUrl: 7 }]) {
      expect(await message({ kind: "reader-capture", doc: invalid, captureId })).toMatchObject({ ok: false });
    }
    expect(await records("items")).toHaveLength(0);
    expect(storage.deviceSeckey).toBeUndefined();
  });
});

describe("context-menu capture feedback", () => {
  it("installs feedback on a generic page before saving, without losing the selected scope", async () => {
    await boot();
    await listeners.menu({ menuItemId: "reader-send-selection", selectionText: "Constellation", pageUrl: "https://example.org/article", frameId: 0 }, { id: 7 });
    expect(chrome.scripting.executeScript).toHaveBeenCalledWith({ target: { tabId: 7, frameIds: [0] }, files: ["content.js"] });
    const messages = vi.mocked(chrome.tabs.sendMessage).mock.calls;
    expect(messages[0]).toEqual([7, expect.objectContaining({ kind: "reader-feedback-begin" }), { documentId: "document-1" }]);
    expect(messages[1]).toEqual([7, expect.objectContaining({ kind: "reader-capture-feedback", state: "unpaired" }), { documentId: "document-1" }]);
    expect(await records("captures")).toMatchObject([{ markdown: "Constellation\n" }]);
  });
  it("blocked page injection does not discard selected text or prevent badge feedback", async () => {
    await boot();
    vi.mocked(chrome.scripting.executeScript).mockRejectedValue(new Error("Restricted page"));
    await listeners.menu({ menuItemId: "reader-send-selection", selectionText: "Constellation", pageUrl: "https://example.org/article" }, { id: 7 });
    expect(await records("captures")).toHaveLength(1);
    expect(chrome.action.setTitle).toHaveBeenCalledWith({ tabId: 7, title: "Saved — connect your phone" });
  });
  it("receipt status never exposes capture text and never guesses delivered for an unknown ID", async () => {
    await boot();
    expect(await message({ kind: "reader-delivery-receipt", transferId: "f".repeat(32) })).toEqual({ ok: true, delivered: false });
    expect(await message({ kind: "reader-delivery-receipt", transferId: "not-an-id" })).toMatchObject({ ok: false });
  });
});

// Transport boundary can delay/lose a relay's response while production key,
// wrapping, ACK authentication, generation guards, scan and database paths run.
describe("production publication and ACK interleavings", () => {
  let published: number;
  let release: () => void;
  let incoming: any[];
  let networkFailure: boolean;
  let phoneKey: Uint8Array;
  let realTransport: typeof import("../src/nostr/transport.js");
  let devicePubkey: string;
  let phonePubkey: string;
  const trusted = { ...sender, url: `chrome-extension://${sender.id}/src/ui/options.html` };

  beforeEach(async () => {
    incoming = []; published = 0; networkFailure = false;
    const gate = new Promise<void>(resolve => { release = resolve; });
    realTransport = await vi.importActual("../src/nostr/transport.js");
    vi.doMock("../src/nostr/transport.js", () => ({
      ...realTransport,
      queryRelayWithAuth: async () => { if (networkFailure) throw new Error("Synthetic disconnected relay"); return incoming; },
      publishPerRelay: async (_pool: any, relays: string[]) => {
        published++;
        await gate;
        return relays.map(url => ({ url, state: "OK_TRUE" }));
      },
    }));
    const { getPublicKey } = await import("nostr-tools/pure");
    const { DEFAULT_RELAYS } = await import("../src/protocol/relays.js");
    const { relaySetDigest } = await import("../src/protocol/pairing.js");
    const deviceKey = realTransport.randomSeckey(); phoneKey = realTransport.randomSeckey();
    devicePubkey = getPublicKey(deviceKey); phonePubkey = getPublicKey(phoneKey);
    Object.assign(storage, {
      deviceSeckey: Buffer.from(deviceKey).toString("hex"), channelDevicePubkey: devicePubkey,
      protocolVersion: 2, channelPubkey: phonePubkey, relays: DEFAULT_RELAYS,
      channelRelaySetDigest: await relaySetDigest(DEFAULT_RELAYS),
    });
  });

  async function signedAck(item: any, overrides: object = {}) {
    const now = Math.floor(Date.now() / 1000);
    const { wrap } = await realTransport.sealAndWrap({ senderSeckey: phoneKey, recipientPubkey: devicePubkey, expireSecs: 3600,
      payload: { protocol: "reader/2", type: "ack", transferId: item.transferId, documentId: item.documentId,
        manifestId: item.manifest.manifestId, contentHash: item.documentId, senderChannelPubkey: phonePubkey,
        recipientDevicePubkey: devicePubkey, status: "stored", receivedAt: now, expiresAt: now + 3600, ...overrides },
    });
    return wrap;
  }

  it("authenticated phone receipt settles while a redundant relay is slow; late publication cannot resurrect it", async () => {
    const worker = await boot();
    await message({ kind: "reader-capture", doc, captureId, feedbackRoute: { tabId: 999 } }, { ...sender, frameId: 2, documentId: "document-1" } as any);
    await vi.waitFor(() => expect(published).toBeGreaterThan(0));
    const [item] = await records("items");
    expect(item.feedbackRoute).toEqual({ captureId, tabId: 1, frameId: 2, documentId: "document-1" });
    await boot(); // Route survives a new worker; do not trust a page-supplied tab.
    incoming = [await signedAck(item)];
    expect(await message({ kind: "reader-check-acks" }, trusted)).toMatchObject({ ok: true });
    expect(await records("items")).toHaveLength(0);
    expect(storage.recentDeliveryReceipts).toMatchObject([{ transferId: item.transferId }]);
    expect(chrome.tabs.sendMessage).toHaveBeenCalledWith(1, expect.objectContaining({ kind: "reader-capture-feedback", captureId, state: "delivered" }), { documentId: "document-1" });
    expect(await message({ kind: "reader-delivery-receipt", transferId: item.transferId })).toEqual({ ok: true, delivered: true });
    release();
    await new Promise(resolve => setTimeout(resolve, 30));
    expect(await records("items")).toHaveLength(0);
    expect(await records("captures")).toHaveLength(1);
  });

  it("wrong manifest ACK never clears a capture despite valid phone signature", async () => {
    const worker = await boot();
    await worker.queueCapture(doc, captureId);
    await vi.waitFor(() => expect(published).toBeGreaterThan(0));
    const [item] = await records("items");
    incoming = [await signedAck(item, { manifestId: "f".repeat(64) })];
    await message({ kind: "reader-check-acks" }, trusted);
    expect(await records("items")).toHaveLength(1);
    expect(storage.recentDeliveryReceipts).toBeUndefined();
    release();
    await vi.waitFor(async () => expect((await records("items"))[0].status).toBe("awaiting_device"));
  });

  it("unavailable ACK queries report failure while publishing still makes progress", async () => {
    networkFailure = true;
    const worker = await boot();
    expect(await message({ kind: "reader-check-acks" }, trusted)).toMatchObject({ ok: false });
    expect(storage.readerLastReceive).toMatchObject({ successfulRelays: 0, failedRelays: storage.relays.length });
    await worker.queueCapture(doc, captureId);
    release();
    await vi.waitFor(async () => expect((await records("items"))[0].status).toBe("awaiting_device"));
    expect(await records("captures")).toHaveLength(1);
  });
});
