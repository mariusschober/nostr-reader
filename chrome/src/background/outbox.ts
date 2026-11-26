import type { FragmentOutcome } from "./resumable-publisher.js";
import type { CaptureFeedbackRoute } from "../protocol/capture-feedback.js";
import type { DeliveryStatus } from "../protocol/delivery-state.js";

export interface OutboxItem {
  transferId: string;
  documentId: string;
  title: string;
  manifest: Record<string, unknown>;
  relays: string[];
  createdAt: number;
  expiresAt: number;
  status: DeliveryStatus;
  attemptCount: number;
  nextAttemptAt: number;
  lastError?: string;
  fragmentProgress?: Record<string, Record<string, FragmentOutcome>>;
  feedbackRoute?: CaptureFeedbackRoute;
}

type OutboxSummary = Omit<OutboxItem, "fragmentProgress">;

// Strict capture identity: 32 lowercase hex (randomHex) or canonical UUID.
// The older permissive pattern allowed arbitrary hyphen placement; new writes
// require one of these two exact shapes. Reads remain tolerant of legacy rows
// via migration-time validation that skips (never aborts on) malformed rows.
export const STRICT_CAPTURE_ID = /^(?:[0-9a-f]{32}|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})$/;
const STRICT_TRANSFER_ID = /^[0-9a-f]{32}$/;

// captureIds rows are deduplication tombstones, not transport payload:
// { captureId, transferId, createdAt, status: 'pending'|'delivered'|'discarded'|'retained' }.
// Repeating a capture ID returns its original terminal result even after the
// transport payload is gone. A deliberate new capture must use a new ID.
// Tombstones are bounded separately (see pruneCaptureIds); pending rows are
// never pruned while their transport item still exists.
export type CaptureIdStatus = "pending" | "delivered" | "discarded" | "retained";

// ---- Outbox (IndexedDB, durable across worker restarts) ----
function idb(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const r = indexedDB.open("reader-outbox", 4);
    r.onupgradeneeded = (event) => {
      const oldVersion = (event as IDBVersionChangeEvent).oldVersion;
      if (!r.result.objectStoreNames.contains("items")) r.result.createObjectStore("items", { keyPath: "transferId" });
      if (!r.result.objectStoreNames.contains("captures")) r.result.createObjectStore("captures", { keyPath: "captureId" });
      if (!r.result.objectStoreNames.contains("captureIds")) r.result.createObjectStore("captureIds", { keyPath: "captureId" });
      const tx = r.transaction!;
      const ids = tx.objectStore("captureIds");
      ids.createIndex("transferId", "transferId");
      ids.createIndex("createdAt", "createdAt");
      const payloads = r.result.createObjectStore("payloads");
      // Upgrade atomically. A failed payload copy rolls back metadata and version.
      if (oldVersion > 0) {
        const request = tx.objectStore("items").openCursor();
        request.onsuccess = () => {
          const cursor = request.result;
          if (!cursor) return;
          const { chunks, ...item } = cursor.value;
          if (Array.isArray(chunks)) {
            payloads.add(chunks, cursor.primaryKey);
            cursor.update(item);
          }
          cursor.continue();
        };
      }
      // Migrate only once, from v2 rows that predate lightweight dedupe.
      // Preserve every legacy plaintext row as an explicitly accessible recovery
      // library. Migration is one versionchange transaction: quota failure
      // aborts and leaves version 2 intact for a later retry. Malformed rows
      // are skipped (never abort the whole upgrade on one bad row).
      if (oldVersion >= 2 && oldVersion < 3) {
        try {
          const tx = r.transaction!;
          const capturesStore = tx.objectStore("captures");
          const idsStore = tx.objectStore("captureIds");
          const cursorReq = capturesStore.openCursor();
          cursorReq.onsuccess = () => {
            try {
              const cur = cursorReq.result as IDBCursorWithValue | null;
              if (!cur) return;
              const v = cur.value as { captureId?: unknown; transferId?: unknown; createdAt?: unknown };
              if (typeof v?.captureId === "string" && typeof v?.transferId === "string" &&
                STRICT_CAPTURE_ID.test(v.captureId) && STRICT_TRANSFER_ID.test(v.transferId) &&
                Number.isSafeInteger(v.createdAt)) {
                try { idsStore.put({ captureId: v.captureId, transferId: v.transferId, createdAt: v.createdAt, status: "retained" }); } catch { /* per-row put setup failure: skip row, keep transaction alive */ }
              }
              // Skip invalid rows silently: the legacy library row stays for
              // explicit Settings export/delete; only the dedupe index skips it.
              try { cur.continue(); } catch { /* transaction will complete */ }
            } catch {
              try { (cursorReq.result as IDBCursorWithValue | null)?.continue(); } catch { /* complete */ }
            }
          };
          cursorReq.onerror = () => { try { tx.abort(); } catch { /* leaves v2 intact */ } };
        } catch {
          try { r.transaction!.abort(); } catch { /* leaves v2 intact */ }
        }
      }
    };
    r.onsuccess = () => resolve(r.result);
    r.onerror = () => reject(r.error);
  });
}
export async function outboxPut(item: OutboxItem): Promise<void> {
  const db = await idb();
  try {
    await new Promise<void>((resolve, reject) => {
      const tx = db.transaction("items", "readwrite");
      tx.oncomplete = () => resolve();
      tx.onerror = tx.onabort = () => reject(tx.error);
      const store = tx.objectStore("items");
      const pending = store.getKey(item.transferId);
      pending.onsuccess = () => {
        if (pending.result !== undefined) store.put(item);
      };
    });
  } finally { db.close(); }
}
export async function outboxAll(): Promise<OutboxSummary[]> {
  const db = await idb();
  try {
    return await new Promise<OutboxSummary[]>((resolve, reject) => {
      const summaries: OutboxSummary[] = [];
      const request = db.transaction("items").objectStore("items").openCursor();
      request.onerror = () => reject(request.error);
      request.onsuccess = () => {
        const cursor = request.result;
        if (!cursor) { resolve(summaries); return; }
        const { fragmentProgress: _progress, ...summary } = cursor.value as OutboxItem;
        summaries.push(summary);
        cursor.continue();
      };
    });
  } finally { db.close(); }
}
export async function outboxGet(transferId: string): Promise<OutboxItem | undefined> {
  const db = await idb();
  try {
    return await new Promise<OutboxItem | undefined>((resolve, reject) => {
      const request = db.transaction("items").objectStore("items").get(transferId);
      request.onsuccess = () => resolve(request.result as OutboxItem | undefined);
      request.onerror = () => reject(request.error);
    });
  } finally { db.close(); }
}
export async function outboxChunks(transferId: string): Promise<string[]> {
  const db = await idb();
  try {
    const chunks = await new Promise<unknown>((resolve, reject) => {
      const req = db.transaction("payloads").objectStore("payloads").get(transferId);
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => reject(req.error);
    });
    if (!Array.isArray(chunks) || !chunks.length || chunks.some(chunk => typeof chunk !== "string")) {
      throw new Error("Stored article content is unavailable");
    }
    return chunks;
  } finally { db.close(); }
}

export async function settleTransfer(transferId: string, status: "discarded" | "delivered"): Promise<void> {
  const db = await idb();
  try {
    await new Promise<void>((resolve, reject) => {
      const tx = db.transaction(["items", "payloads", "captureIds"], "readwrite");
      tx.oncomplete = () => resolve();
      tx.onabort = tx.onerror = () => reject(tx.error);
      const items = tx.objectStore("items");
      const pending = items.getKey(transferId);
      pending.onsuccess = () => {
        if (pending.result === undefined) return;
        try {
          items.delete(transferId);
          tx.objectStore("payloads").delete(transferId);
          const cursor = tx.objectStore("captureIds").index("transferId").openCursor(transferId);
          cursor.onsuccess = () => {
            const row = cursor.result;
            if (!row) return;
            row.update({ ...row.value, status });
            row.continue();
          };
        } catch (error) { tx.abort(); reject(error); }
      };
    });
  } finally { db.close(); }
}

/** Count first. Visit only as many old settled IDs as are needed to reach the cap. */
export async function pruneCaptureIds(): Promise<void> {
  const db = await idb();
  try {
    await new Promise<void>((resolve, reject) => {
      const tx = db.transaction(["captureIds", "items"], "readwrite");
      tx.oncomplete = () => resolve();
      tx.onabort = tx.onerror = () => reject(tx.error);
      const store = tx.objectStore("captureIds");
      const count = store.count();
      count.onsuccess = () => {
        let excess = count.result - 2000;
        if (excess <= 0) return;
        const cursor = store.index("createdAt").openCursor();
        cursor.onsuccess = () => {
          const row = cursor.result;
          if (!row || excess <= 0) return;
          if (row.value.status === "pending") { row.continue(); return; }
          const item = tx.objectStore("items").getKey(row.value.transferId);
          item.onsuccess = () => {
            if (item.result === undefined) { row.delete(); excess--; }
            if (excess > 0) row.continue();
          };
        };
      };
    });
  } finally { db.close(); }
}

/** Legacy library owns its plaintext independently of transport and ACK cleanup. */
export async function retainedCaptures(action: "list" | "export" | "delete", captureId?: string): Promise<any> {
  const db = await idb();
  try {
    return await new Promise((resolve, reject) => {
      const tx = db.transaction("captures", action === "delete" ? "readwrite" : "readonly");
      const store = tx.objectStore("captures");
      let result: unknown;
      if (action === "list") {
        const rows: unknown[] = [];
        result = rows;
        const cursor = store.openCursor();
        cursor.onsuccess = () => {
          const row = cursor.result;
          if (!row) return;
          rows.push({ captureId: row.value.captureId, title: row.value.title, createdAt: row.value.createdAt });
          row.continue();
        };
      } else {
        const request = action === "delete" ? store.delete(captureId!) : store.get(captureId!);
        request.onsuccess = () => { result = request.result; };
      }
      tx.oncomplete = () => resolve(result);
      tx.onerror = tx.onabort = () => reject(tx.error);
    });
  } finally { db.close(); }
}

export async function captureRecord(captureId: string) {
  const db = await idb();
  try {
    type CaptureId = { transferId: string; status?: CaptureIdStatus };
    return await new Promise<{ id: CaptureId; active: boolean } | undefined>((resolve, reject) => {
      const tx = db.transaction(["items", "captureIds"]);
      let match: { id: CaptureId; active: boolean } | undefined;
      const key = tx.objectStore("captureIds").get(captureId);
      key.onsuccess = () => {
        const id: CaptureId | undefined = key.result;
        if (!id) return;
        const pending = tx.objectStore("items").getKey(id.transferId);
        pending.onsuccess = () => { match = { id, active: pending.result !== undefined }; };
      };
      tx.oncomplete = () => resolve(match);
      tx.onerror = tx.onabort = () => reject(tx.error);
    });
  } finally { db.close(); }
}

export async function insertCapture(item: OutboxItem, chunks: string[], captureId: string): Promise<void> {
  const db = await idb();
  const { transferId, createdAt } = item;
  try {
    await new Promise<void>((resolve, reject) => {
      const tx = db.transaction(["items", "captureIds", "payloads"], "readwrite");
      tx.oncomplete = () => resolve(); tx.onerror = () => reject(tx.error); tx.onabort = () => reject(tx.error);
      try {
        // add (not put) for the dedupe index: an overlapping worker restart
        // that raced us must fail here instead of silently overwriting the
        // winner's mapping and orphaning its transport item.
        tx.objectStore("items").add(item);
        tx.objectStore("payloads").add(chunks, transferId);
        tx.objectStore("captureIds").add({ captureId, transferId, createdAt, status: "pending" });
      } catch (error) {
        // Synchronous request setup failures do not automatically roll back
        // earlier queued writes. Never leave a send without its deduplication record.
        try { tx.abort(); } catch { /* ignore */ }
        reject(error);
      }
    });
  } finally { db.close(); }
}
