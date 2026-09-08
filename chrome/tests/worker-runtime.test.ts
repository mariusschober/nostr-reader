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
    permissions: { contains: vi.fn(async () => false), onAdded: event("permission-added"), onRemoved: event("permission-removed") },
    scripting: { getRegisteredContentScripts: vi.fn(async () => []), registerContentScripts: vi.fn(async () => {}), updateContentScripts: vi.fn(async () => {}), unregisterContentScripts: vi.fn(async () => {}), executeScript: vi.fn(async () => [{ frameId: 0, documentId: "document-1" }]) },
    commands: { onCommand: event("command") },
    contextMenus: { onClicked: event("menu"), create: vi.fn() },
  });
  return import("../src/background/service-worker.js");
}
async function records(name: string): Promise<any[]> {
  return new Promise((resolve, reject) => {
    const open = indexedDB.open("reader-outbox", 3);
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
    expect(await records("captureIds")).toMatchObject([{ captureId, transferId: reply.transferId }]);
    expect(await records("items")).toMatchObject([{ transferId: reply.transferId, attemptCount: 0 }]);
    expect(alarms.has("reader-pairing-recovery")).toBe(false);
  });
  it("migrates legacy plaintext without loss; ACK cleanup and discard leave the separate library accessible", async () => {
    await new Promise<void>((resolve, reject) => {
      const request = indexedDB.open("reader-outbox", 2);
      request.onupgradeneeded = () => {
        request.result.createObjectStore("items", { keyPath: "transferId" });
        request.result.createObjectStore("captures", { keyPath: "captureId" });
      };
      request.onsuccess = () => {
        const db = request.result;
        const tx = db.transaction("captures", "readwrite");
        tx.objectStore("captures").put({ captureId, transferId: "c".repeat(32), ...doc, createdAt: 1 });
        tx.oncomplete = () => { db.close(); resolve(); };
        tx.onerror = () => reject(tx.error);
      };
    });
    const worker = await boot();
    // Legacy row migrates to a retained tombstone; re-queue returns the same
    // transfer truthfully settled (no transport payload to send).
    const settled = await worker.queueCapture(doc, captureId);
    expect(settled.transferId).toBe("c".repeat(32));
    expect(settled.settled).toBe("retained");
    expect(settled.queued).toBe(false);
    expect(await records("captures")).toMatchObject([{ markdown: doc.markdown }]);
    expect(await records("captureIds")).toEqual([{ captureId, transferId: "c".repeat(32), createdAt: 1, status: "retained" }]);
    const trusted = { ...sender, url: `chrome-extension://${sender.id}/src/ui/options.html` };
    expect(await message({ kind: "reader-retained-action", action: "export", captureId }, trusted)).toMatchObject({ ok: true, markdown: doc.markdown });
    const fresh = await worker.queueCapture(doc, "b".repeat(32));
    expect(await message({ kind: "reader-transfer-action", action: "discard", transferId: fresh.transferId }, trusted)).toMatchObject({ ok: true });
    expect(await records("items")).toHaveLength(0);
    expect(await records("captures")).toHaveLength(1);
    // Discard tombstones the fresh ID as discarded instead of deleting it.
    expect(await records("captureIds")).toMatchObject(expect.arrayContaining([
      expect.objectContaining({ captureId: "b".repeat(32), status: "discarded" }),
    ]));
    const rediscarded = await worker.queueCapture(doc, "b".repeat(32));
    expect(rediscarded.transferId).toBe(fresh.transferId);
    expect(rediscarded.settled).toBe("discarded");
    expect(await records("items")).toHaveLength(0);
    expect(await message({ kind: "reader-retained-action", action: "delete", captureId }, trusted)).toMatchObject({ ok: true });
    expect(await records("captures")).toHaveLength(0);
    expect((await worker.queueCapture(doc, captureId)).transferId).toBe("c".repeat(32));
  });
  it("migration skips malformed legacy rows without aborting the upgrade", async () => {
    await new Promise<void>((resolve, reject) => {
      const request = indexedDB.open("reader-outbox", 2);
      request.onupgradeneeded = () => {
        request.result.createObjectStore("items", { keyPath: "transferId" });
        request.result.createObjectStore("captures", { keyPath: "captureId" });
      };
      request.onsuccess = () => {
        const db = request.result;
        const tx = db.transaction("captures", "readwrite");
        tx.objectStore("captures").put({ captureId: "not-a-valid-id", transferId: "also-bad", ...doc, createdAt: 1 });
        tx.objectStore("captures").put({ captureId, transferId: "c".repeat(32), ...doc, createdAt: 2 });
        tx.oncomplete = () => { db.close(); resolve(); };
        tx.onerror = () => reject(tx.error);
      };
    });
    await boot();
    // One valid tombstone migrates; the malformed library row stays for
    // Settings export/delete but does not brick the outbox.
    expect(await records("captureIds")).toEqual([{ captureId, transferId: "c".repeat(32), createdAt: 2, status: "retained" }]);
    expect(await records("captures")).toHaveLength(2);
  });
  it("a lost response followed by worker replacement and message retry creates one transfer", async () => {
    let worker = await boot();
    await worker.queueCapture(doc, captureId); // caller loses the result
    const before = await records("items");
    worker = await boot(); // JS owners disappear, browser persistence stays
    const recovered = await worker.queueCapture(doc, captureId);
    expect(recovered.transferId).toBe(before[0].transferId);
    expect(await records("items")).toHaveLength(1);
    expect(await records("captureIds")).toHaveLength(1);
  });
  it("concurrent repeated messages share one durable result; later deliberate capture stays possible", async () => {
    const worker = await boot();
    const results = await Promise.all(Array.from({ length: 10 }, () => worker.queueCapture(doc, captureId)));
    expect(new Set(results.map(item => item.transferId)).size).toBe(1);
    await worker.queueCapture(doc, "b".repeat(32));
    expect(await records("items")).toHaveLength(2);
    expect(await records("captureIds")).toHaveLength(2);
  });
  it("quota failure during capture commit cannot return saved or strand a send intent", async () => {
    await boot();
    const failQuota = () => { throw new DOMException("Storage full", "QuotaExceededError"); };
    const putSpy = vi.spyOn(IDBObjectStore.prototype, "put").mockImplementation(function(this: IDBObjectStore, ...args: Parameters<typeof IDBObjectStore.prototype.put>) {
      if (this.name === "captureIds" || this.name === "items") failQuota();
      return (IDBObjectStore.prototype.put as unknown as (...a: unknown[]) => never).apply(this, args as unknown as []);
    });
    const addSpy = vi.spyOn(IDBObjectStore.prototype, "add").mockImplementation(function(this: IDBObjectStore, ...args: Parameters<typeof IDBObjectStore.prototype.add>) {
      if (this.name === "captureIds" || this.name === "items") failQuota();
      return (IDBObjectStore.prototype.add as unknown as (...a: unknown[]) => never).apply(this, args as unknown as []);
    });
    try {
      expect(await message({ kind: "reader-capture", doc, captureId })).toMatchObject({ ok: false });
      expect(await records("items")).toHaveLength(0);
      expect(await records("captureIds")).toHaveLength(0);
    } finally { putSpy.mockRestore(); addSpy.mockRestore(); }
  });
  it("quota on the transport leg alone also rolls back the dedupe record", async () => {
    await boot();
    const originalAdd = IDBObjectStore.prototype.add;
    const spy = vi.spyOn(IDBObjectStore.prototype, "add").mockImplementation(function(this: IDBObjectStore, ...args: Parameters<typeof originalAdd>) {
      if (this.name === "items") throw new DOMException("Storage full", "QuotaExceededError");
      return originalAdd.apply(this, args);
    });
    try {
      expect(await message({ kind: "reader-capture", doc, captureId })).toMatchObject({ ok: false });
      expect(await records("items")).toHaveLength(0);
      expect(await records("captureIds")).toHaveLength(0);
    } finally { spy.mockRestore(); }
  });
  it("settled capture IDs report terminal status instead of fake queueing", async () => {
    const worker = await boot();
    const first = await worker.queueCapture(doc, captureId);
    expect(first.queued).toBe(true);
    const trusted = { ...sender, url: `chrome-extension://${sender.id}/src/ui/options.html` };
    expect(await message({ kind: "reader-transfer-action", action: "discard", transferId: first.transferId }, trusted)).toMatchObject({ ok: true });
    const second = await worker.queueCapture(doc, captureId);
    expect(second.transferId).toBe(first.transferId);
    expect(second.queued).toBe(false);
    expect(second.settled).toBe("discarded");
    expect(await records("items")).toHaveLength(0);
  });
  it("capture IDs require strict hex or UUID; hashes bind canonical text", async () => {
    const worker = await boot();
    await expect(worker.queueCapture(doc, "-------------------------------x")).rejects.toThrow();
    await expect(worker.queueCapture(doc, "ZZ".repeat(16))).rejects.toThrow();
    const raw = { title: doc.title, markdown: "Trailing spaces   \n\n\n", sourceType: "selection" };
    const first = await worker.queueCapture(raw, "d".repeat(32));
    const items = await records("items");
    expect(items).toHaveLength(1);
    expect(items[0].transferId).toBe(first.transferId);
    // Canonicalization (trailing-space strip + single LF) determines the ID,
    // not the raw markdown bytes.
    expect(items[0].documentId).not.toBe("untrimmed");
    expect(items[0].documentId).toMatch(/^[0-9a-f]{64}$/);
  });
  it("web content cannot invoke privileged status or identity operations", async () => {
    await boot();
    for (const kind of ["reader-status", "reader-start-pairing", "reader-disconnect", "reader-retry", "reader-retained-action", "reader-transfer-action", "reader-discard-failed", "reader-check-acks", "reader-save-custom-relays"]) {
      const payload = kind === "reader-retained-action"
        ? { kind, action: "export", captureId }
        : kind === "reader-transfer-action"
          ? { kind, action: "discard", transferId: "a".repeat(32) }
          : { kind };
      expect(await message(payload)).toMatchObject({ ok: false, error: expect.stringContaining("Untrusted") });
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
    expect(await records("captureIds")).toHaveLength(1);
    expect(await records("captures")).toHaveLength(0);
  });
  it("blocked page injection does not discard selected text or prevent badge feedback", async () => {
    await boot();
    vi.mocked(chrome.scripting.executeScript).mockRejectedValue(new Error("Restricted page"));
    await listeners.menu({ menuItemId: "reader-send-selection", selectionText: "Constellation", pageUrl: "https://example.org/article" }, { id: 7 });
    expect(await records("captureIds")).toHaveLength(1);
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
    expect(await records("captureIds")).toHaveLength(1);
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

  it("a receipt-before-cleanup crash stops late publication and cleans up on a new worker without the relay ACK", async () => {
    const worker = await boot();
    await worker.queueCapture(doc, captureId);
    await vi.waitFor(() => expect(published).toBeGreaterThan(0));
    const [item] = await records("items");
    incoming = [await signedAck(item)];
    const original = IDBObjectStore.prototype.delete;
    const failure = vi.spyOn(IDBObjectStore.prototype, "delete").mockImplementation(function(this: IDBObjectStore, key) {
      if (this.name === "items") throw new DOMException("Synthetic cleanup failure", "UnknownError");
      return original.call(this, key);
    });
    expect(await message({ kind: "reader-check-acks" }, trusted)).toMatchObject({ ok: false });
    expect(storage.recentDeliveryReceipts).toMatchObject([{ transferId: item.transferId }]);
    const sendsAtReceipt = published;
    release();
    await new Promise(resolve => setTimeout(resolve, 25));
    expect(published).toBe(sendsAtReceipt);
    failure.mockRestore(); incoming = []; // accepted ACKs have disappeared
    await boot();
    await vi.waitFor(async () => expect(await records("items")).toHaveLength(0));
    expect(await records("captureIds")).toHaveLength(1);
  });

  it("restores receipt polling after reload and after an all-relay read failure", async () => {
    const worker = await boot();
    await worker.queueCapture(doc, captureId);
    await vi.waitFor(() => expect(published).toBeGreaterThan(0));
    alarms.clear();
    await boot();
    await vi.waitFor(() => expect(alarms.has("reader-ack-check")).toBe(true));
    const earlierWake = alarms.get("reader-ack-check").scheduledTime;
    for (let i = 0; i < 3; i++) { await boot(); await message({ kind: "reader-status" }, trusted); }
    expect(alarms.get("reader-ack-check").scheduledTime).toBeLessThanOrEqual(earlierWake);
    networkFailure = true;
    alarms.delete("reader-ack-check");
    await expect(listeners.alarm({ name: "reader-ack-check" })).rejects.toThrow();
    expect(alarms.has("reader-ack-check")).toBe(true);
    release();
  });

  it("unavailable ACK queries report failure while publishing still makes progress", async () => {
    networkFailure = true;
    const worker = await boot();
    expect(await message({ kind: "reader-check-acks" }, trusted)).toMatchObject({ ok: false });
    expect(storage.readerLastReceive).toMatchObject({ successfulRelays: 0, failedRelays: storage.relays.length });
    await worker.queueCapture(doc, captureId);
    release();
    await vi.waitFor(async () => expect((await records("items"))[0].status).toBe("awaiting_device"));
    expect(await records("captureIds")).toHaveLength(1);
  });
});
