// Service worker: key ownership, outbox, pairing, quorum send, E2E ACK listen.
// Private keys never leave this scope. State is durable (storage.local +
// IndexedDB) because MV3 workers are suspended at will.
import { SimplePool } from "nostr-tools/pool";
import { getPublicKey } from "nostr-tools/pure";
import {
  READER_PROTOCOL, canonicalize, documentId, wordCount, syncSince, checkLimits,
} from "../protocol/core.js";
import { deterministicGzip } from "../protocol/codec.js";
import {
  publishPerRelay,
  queryRelayWithAuth,
  randomHex,
  randomSeckey,
  sealAndWrap,
  unwrapAndVerifyEnvelope,
  WRAP_KIND,
} from "../nostr/transport.js";
import {
  createPairAck,
  createPairingRequest,
  PAIRING_PROTOCOL,
  type PairingRequestV2,
  validatePairComplete,
  validatePairResponse,
} from "../protocol/pairing.js";
import { manifestIdentity, validateEndpointAck } from "../protocol/messages.js";
import {
  configuredRelays,
  DEFAULT_RELAYS,
  normalizeCustomRelays,
  RELAY_WRITE_QUORUM,
  sameRelayOrder,
} from "../protocol/relays.js";
import {
  cancelActivePairingSessions,
  isActivePairingState,
  PAIRED_CHANNEL_STORAGE_KEYS,
  stripPairingSecret,
  type PairingLifecycleState,
} from "../protocol/pairing-session.js";
import {
  deliveryCeilingReason,
  summarizeDeliveryStates,
  type DeliveryStatus,
} from "../protocol/delivery-state.js";
import { KeyedSerialExecutor, SerialExecutor } from "../protocol/serial-executor.js";

interface OutboxItem {
  transferId: string;
  documentId: string;
  title: string;
  manifest: Record<string, unknown>;
  chunks: string[]; // base64 gzip slices
  relays: string[];
  createdAt: number;
  expiresAt: number;
  status: DeliveryStatus;
  attemptCount: number;
  nextAttemptAt: number;
  lastError?: string;
}

interface PairingSession {
  request: PairingRequestV2;
  pairingSeckey?: string;
  state: PairingLifecycleState;
  androidChannelPubkey?: string;
  responseRumorId?: string;
  lastAttemptAt?: number;
  lastError?: string;
  completedAt?: number;
}

const PAIRING_SESSIONS_KEY = "pairingSessionsV2";
const PAIRING_ALARM = "reader-pairing-recovery";
const RETRY_ALARM = "reader-retry";
const ACK_ALARM = "reader-ack-check";
const CUSTOM_RELAYS_KEY = "customRelays";
const DELIVERY_RECEIPTS_KEY = "recentDeliveryReceipts";
const MAX_PAIRING_SESSIONS = 2;
const transferOperations = new KeyedSerialExecutor<string>();
const receiptOperations = new SerialExecutor();
const pairingOperations = new SerialExecutor();
let pairingRecovery: Promise<void> | null = null;
let ackPolling: Promise<void> | null = null;
let transportEpoch = 0;

function hexToSeckey(hex: string): Uint8Array {
  const b = new Uint8Array(32);
  for (let i = 0; i < 32; i++) b[i] = parseInt(hex.slice(i * 2, i * 2 + 2), 16);
  return b;
}
function seckeyToHex(s: Uint8Array): string {
  return [...s].map((x) => x.toString(16).padStart(2, "0")).join("");
}
function b64(bytes: Uint8Array): string {
  let s = "";
  for (const x of bytes) s += String.fromCharCode(x);
  return btoa(s);
}

async function loadCustomRelays(): Promise<string[]> {
  const stored = (await chrome.storage.local.get([CUSTOM_RELAYS_KEY]))[CUSTOM_RELAYS_KEY];
  return runSafely(() => normalizeCustomRelays(stored ?? []), []);
}

function runSafely<T>(operation: () => T, fallback: T): T {
  try { return operation(); } catch { return fallback; }
}

async function configuredRelaySet(): Promise<string[]> {
  return configuredRelays(await loadCustomRelays());
}

async function getDeviceKey(): Promise<{ seckey: Uint8Array; pubkey: string }> {
  await lockDownKeyStorage();
  const st = await chrome.storage.local.get(["deviceSeckey"]);
  if (st.deviceSeckey) {
    const seckey = hexToSeckey(st.deviceSeckey as string);
    return { seckey, pubkey: getPublicKey(seckey) };
  }
  const seckey = randomSeckey();
  await chrome.storage.local.set({ deviceSeckey: seckeyToHex(seckey) });
  return { seckey, pubkey: getPublicKey(seckey) };
}

/** Keep private key material unavailable to content-script execution contexts. */
async function lockDownKeyStorage(): Promise<void> {
  await chrome.storage.local.setAccessLevel({ accessLevel: "TRUSTED_CONTEXTS" });
}

// Reassert on every worker evaluation, not only installation/startup events.
// Chrome persists this access level, while this closes upgrade/reload windows.
void lockDownKeyStorage().catch(() => undefined);

// ---- Outbox (IndexedDB, durable across worker restarts) ----
function idb(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const r = indexedDB.open("reader-outbox", 1);
    r.onupgradeneeded = () => r.result.createObjectStore("items", { keyPath: "transferId" });
    r.onsuccess = () => resolve(r.result);
    r.onerror = () => reject(r.error);
  });
}
async function outboxPut(item: OutboxItem): Promise<void> {
  const db = await idb();
  await new Promise<void>((res, rej) => {
    const tx = db.transaction("items", "readwrite");
    tx.objectStore("items").put(item as never);
    tx.oncomplete = () => res();
    tx.onerror = () => rej(tx.error);
  });
  db.close();
}
async function outboxAll(): Promise<OutboxItem[]> {
  const db = await idb();
  const items = await new Promise<OutboxItem[]>((res, rej) => {
    const q = db.transaction("items").objectStore("items").getAll();
    q.onsuccess = () => res(q.result as OutboxItem[]);
    q.onerror = () => rej(q.error);
  });
  db.close();
  return items;
}
async function outboxGet(transferId: string): Promise<OutboxItem | undefined> {
  const db = await idb();
  const item = await new Promise<OutboxItem | undefined>((res, rej) => {
    const q = db.transaction("items").objectStore("items").get(transferId);
    q.onsuccess = () => res(q.result as OutboxItem | undefined);
    q.onerror = () => rej(q.error);
  });
  db.close();
  return item;
}
async function outboxDelete(transferId: string): Promise<void> {
  const db = await idb();
  await new Promise<void>((res, rej) => {
    const tx = db.transaction("items", "readwrite");
    tx.objectStore("items").delete(transferId);
    tx.oncomplete = () => res();
    tx.onerror = () => rej(tx.error);
  });
  db.close();
}

interface DeliveryReceipt {
  transferId: string;
  deliveredAt: number;
}

async function loadDeliveryReceipts(nowSecs = Math.floor(Date.now() / 1000)): Promise<DeliveryReceipt[]> {
  const raw = (await chrome.storage.local.get([DELIVERY_RECEIPTS_KEY]))[DELIVERY_RECEIPTS_KEY];
  if (!Array.isArray(raw)) return [];
  const cutoff = nowSecs - 7 * 86400;
  return raw.filter((item): item is DeliveryReceipt => (
    !!item && typeof item === "object" &&
    typeof (item as DeliveryReceipt).transferId === "string" &&
    Number.isSafeInteger((item as DeliveryReceipt).deliveredAt) &&
    (item as DeliveryReceipt).deliveredAt >= cutoff
  )).slice(-20);
}

async function recordDelivered(transferId: string, deliveredAt: number): Promise<void> {
  await receiptOperations.run(async () => {
    const receipts = (await loadDeliveryReceipts(deliveredAt)).filter((item) => item.transferId !== transferId);
    receipts.push({ transferId, deliveredAt });
    await chrome.storage.local.set({ [DELIVERY_RECEIPTS_KEY]: receipts.slice(-20) });
  });
}

// ---- Send pipeline ----
export async function queueCapture(raw: { title: string; markdown: string; sourceUrl?: string; sourceType?: string }): Promise<{ transferId: string; queued: boolean }> {
  const { seckey, pubkey } = await getDeviceKey();
  const canonical = canonicalize(raw.markdown);
  const canonicalBytes = new TextEncoder().encode(canonical);
  const docId = await documentId(canonical);
  const gz = deterministicGzip(canonicalBytes);
  const st = await chrome.storage.local.get(["channelPubkey", "relays"]);
  const channelPubkey = st.channelPubkey as string | undefined;
  const relays = (st.relays as string[] | undefined) ?? [...DEFAULT_RELAYS];
  const now = Math.floor(Date.now() / 1000);
  const expiresAt = now + 7 * 86400;
  // Adaptive chunk target: size real frames later; start 24 KiB of gzip.
  const SLICE = 24 * 1024;
  const chunks: string[] = [];
  for (let i = 0; i < gz.length; i += SLICE) chunks.push(b64(gz.slice(i, i + SLICE)));
  checkLimits({ compressedBytes: gz.length, expandedBytes: canonicalBytes.length, titleLen: raw.title.length, urlLen: (raw.sourceUrl ?? "").length, chunkCount: chunks.length });
  const transferId = randomHex(16);
  const compressedSha256 = await shaHex(gz);
  const allowedSourceTypes = new Set(["web", "chatgpt", "claude", "gemini", "perplexity", "selection"]);
  const sourceType = allowedSourceTypes.has(raw.sourceType ?? "") ? raw.sourceType! : "web";
  const manifest = {
    protocol: READER_PROTOCOL, type: "manifest", transferId, documentId: docId,
    manifestId: "", recipientChannelPubkey: channelPubkey ?? "",
    title: raw.title.trim().slice(0, 500) || "Untitled", sourceType,
    sourceUrl: (raw.sourceUrl ?? "").slice(0, 2000), capturedAt: now,
    mime: "text/markdown", compression: "gzip", wordCount: wordCount(canonical),
    uncompressedBytes: canonicalBytes.length,
    compressedBytes: gz.length,
    compressedSha256, documentSha256: docId,
    chunkCount: chunks.length, senderDevicePubkey: pubkey, expiresAt,
  };
  if (channelPubkey) {
    manifest.manifestId = await manifestIdentity({
      transferId,
      documentId: docId,
      compressedSha256,
      compressedBytes: gz.length,
      chunkCount: chunks.length,
      senderDevicePubkey: pubkey,
      recipientChannelPubkey: channelPubkey,
      expiresAt,
    });
  }
  const item: OutboxItem = {
    transferId,
    documentId: docId,
    title: raw.title,
    manifest,
    chunks,
    relays,
    createdAt: now,
    expiresAt,
    status: "queued",
    attemptCount: 0,
    nextAttemptAt: now,
  };
  await outboxPut(item);
  if (!channelPubkey) return { transferId, queued: true }; // unpaired: stays queued
  void publishTransfer(item, seckey, channelPubkey).catch(() => { /* alarm retries */ });
  return { transferId, queued: false };
}

async function shaHex(bytes: Uint8Array): Promise<string> {
  const d = await crypto.subtle.digest("SHA-256", bytes as BufferSource);
  return [...new Uint8Array(d)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

function retryAt(attemptCount: number, nowSecs: number): number {
  const base = Math.min(3600, 15 * (2 ** Math.min(8, Math.max(0, attemptCount - 1))));
  const random = new Uint32Array(1);
  crypto.getRandomValues(random);
  return nowSecs + base + ((random[0] ?? 0) % Math.max(1, Math.floor(base / 4)));
}

function scheduleAckCheck(): void {
  // The Android foreground sync may finish after the publish call's immediate
  // ACK query. A one-shot alarm survives MV3 worker suspension and closes that
  // race without keeping the worker alive or retransmitting the article.
  const when = Date.now() + 30_000;
  void chrome.alarms.get(ACK_ALARM).then((existing) => {
    // Repeated or concurrent publishes must not postpone an already-earlier
    // device-ACK check by replacing its one-shot alarm with a later time.
    if (!existing || existing.scheduledTime > when) {
      chrome.alarms.create(ACK_ALARM, { when });
    }
  }).catch(() => {
    // Failure to inspect an existing alarm must not suppress the only durable
    // post-publish ACK catch-up.
    chrome.alarms.create(ACK_ALARM, { when });
  });
}

async function bindOutboxItem(item: OutboxItem, channelPubkey: string): Promise<void> {
  const manifest = item.manifest;
  const existingRecipient = String(manifest["recipientChannelPubkey"] ?? "");
  if (existingRecipient && existingRecipient !== channelPubkey) throw new Error("queued transfer is bound to a different channel");
  const st = await chrome.storage.local.get(["relays"]);
  const configuredRelays = st.relays as string[] | undefined;
  if (!String(manifest["manifestId"] ?? "")) {
    if (!configuredRelays?.length) throw new Error("paired relay set is unavailable");
    item.relays = [...configuredRelays];
    manifest["recipientChannelPubkey"] = channelPubkey;
    manifest["manifestId"] = await manifestIdentity({
      transferId: item.transferId,
      documentId: item.documentId,
      compressedSha256: String(manifest["compressedSha256"]),
      compressedBytes: Number(manifest["compressedBytes"]),
      chunkCount: Number(manifest["chunkCount"]),
      senderDevicePubkey: String(manifest["senderDevicePubkey"]),
      recipientChannelPubkey: channelPubkey,
      expiresAt: Number(manifest["expiresAt"]),
    });
    await outboxPut(item);
  }
}

async function publishTransferInternal(
  item: OutboxItem,
  seckey: Uint8Array,
  channelPubkey: string,
  epoch: number,
): Promise<void> {
  const now = Math.floor(Date.now() / 1000);
  item.expiresAt = item.expiresAt || Number(item.manifest["expiresAt"] ?? item.createdAt + 7 * 86400);
  item.attemptCount = item.attemptCount || 0;
  item.nextAttemptAt = item.nextAttemptAt || now;
  if (item.status === "failed" || now < item.nextAttemptAt) return;
  const ceilingReason = deliveryCeilingReason(item, now);
  if (ceilingReason) {
    item.status = "failed";
    item.lastError = ceilingReason;
    // Retain captured plaintext until an authenticated device ACK or an
    // explicit user discard. A retry ceiling must never masquerade as delivery.
    await outboxPut(item);
    return;
  }
  await bindOutboxItem(item, channelPubkey);
  const manifestId = String(item.manifest["manifestId"]);
  const compressedSha256 = String(item.manifest["compressedSha256"]);
  const payloads: unknown[] = [item.manifest];
  item.chunks.forEach((dataBase64, index) => payloads.push({
    protocol: READER_PROTOCOL, type: "chunk", transferId: item.transferId,
    manifestId, documentId: item.documentId, compressedSha256,
    index, count: item.chunks.length, dataBase64, expiresAt: item.expiresAt,
  }));
  const required = Math.min(RELAY_WRITE_QUORUM, item.relays.length);
  let complete = 0;
  let anyAccepted = false;
  try {
    for (const payload of payloads) {
      if (epoch !== transportEpoch) throw new Error("delivery interrupted by channel change");
      const accepted = await publishFreshPayload(item.relays, seckey, channelPubkey, payload, 7 * 86400);
      if (accepted.length) anyAccepted = true;
      if (accepted.length >= required) complete += 1;
    }
    if (epoch !== transportEpoch) throw new Error("delivery interrupted by channel change");
    // Never stop at a manifest or early chunk merely because one relay is
    // unavailable. Every payload has now been attempted, so a surviving relay
    // can still deliver a complete transfer and the authenticated device ACK
    // can settle it.
    if (complete < payloads.length) {
      throw new Error(`relay quorum failed (${complete}/${payloads.length} payloads)`);
    }
    item.status = "awaiting_device";
    item.attemptCount += 1;
    item.nextAttemptAt = retryAt(item.attemptCount, now);
    item.lastError = undefined;
    await outboxPut(item);
  } catch (error) {
    item.status = anyAccepted || complete > 0 ? "relay_accepted" : "queued";
    item.attemptCount += 1;
    item.nextAttemptAt = retryAt(item.attemptCount, now);
    item.lastError = safePairingError(error);
    await outboxPut(item);
    // A device can receive from the one relay that accepted the payload even
    // when the configured write quorum was missed. Its authenticated ACK is
    // authoritative and must be checked immediately in that partial-success
    // path as well.
    scheduleAckCheck();
    void pollForAcks().catch(() => undefined);
    throw error;
  }
  scheduleAckCheck();
  void pollForAcks().catch(() => undefined);
}

async function publishTransfer(
  item: OutboxItem,
  seckey: Uint8Array,
  channelPubkey: string,
  force = false,
): Promise<void> {
  return transferOperations.run(item.transferId, async () => {
    // Always reload inside the per-transfer critical section. An ACK or user
    // discard may have deleted a stale caller's object while it was waiting.
    const durable = await outboxGet(item.transferId);
    if (!durable) return;
    const storedChannel = (await chrome.storage.local.get(["channelPubkey"])).channelPubkey;
    if (storedChannel !== channelPubkey) return;
    if (force) durable.nextAttemptAt = 0;
    await publishTransferInternal(durable, seckey, channelPubkey, transportEpoch);
  });
}

// ---- E2E ACK catch-up (rolling window: NIP-59 timestamps are randomized) ----
async function pollForAcksInternal(): Promise<void> {
  const st = await chrome.storage.local.get(["deviceSeckey", "channelPubkey", "relays"]);
  if (!st.deviceSeckey || !st.channelPubkey) return;
  const seckey = hexToSeckey(st.deviceSeckey as string);
  const devicePubkey = getPublicKey(seckey);
  const channelPubkey = st.channelPubkey as string;
  const relays = st.relays as string[];
  if (!Array.isArray(relays) || !relays.length) return;
  const now = Math.floor(Date.now() / 1000);
  const events = await queryPairingEvents(relays, devicePubkey, syncSince(now), seckey);
  for (const event of events) {
    try {
      const envelope = await unwrapAndVerifyEnvelope({
        wrap: event,
        recipientSeckey: seckey,
        expectedSenderPubkey: channelPubkey,
        expectedProtocols: [READER_PROTOCOL],
        nowSecs: now,
      });
      if (envelope.payload["type"] !== "ack") continue;
      const transferId = String(envelope.payload["transferId"] ?? "");
      await transferOperations.run(transferId, async () => {
        const item = await outboxGet(transferId);
        if (!item) return;
        const ack = validateEndpointAck(
          envelope.payload,
          {
            transferId: item.transferId,
            documentId: item.documentId,
            manifestId: String(item.manifest["manifestId"]),
            channelPubkey,
            devicePubkey,
          },
          envelope.senderPubkey,
          now,
        );
        if (ack.status === "stored" || ack.status === "duplicate") {
          // Persist the delivered receipt before deleting the only durable
          // outbox copy. If receipt storage fails, retain the item so the same
          // authenticated ACK can safely settle it on a later poll.
          await recordDelivered(item.transferId, now);
          await outboxDelete(item.transferId); // Only this strict E2E ACK clears captured content.
        } else {
          item.status = "failed";
          item.lastError = ack.reasonCode ?? "device-rejected";
          await outboxPut(item);
        }
      });
    } catch {
      // Hostile, expired, misbound, or unrelated events never mutate the outbox.
    }
  }
}

async function pollForAcks(): Promise<void> {
  if (!ackPolling) {
    ackPolling = pollForAcksInternal().finally(() => { ackPolling = null; });
  }
  return ackPolling;
}

// ---- Pairing ----
function safePairingError(error: unknown): string {
  const raw = error instanceof Error ? error.message : String(error);
  return raw
    .replace(/[\u0000-\u001f\u007f]+/g, " ")
    .replace(/[0-9a-f]{32,}/gi, "[redacted]")
    .replace(/[A-Za-z0-9_+/=-]{48,}/g, "[redacted]")
    .trim()
    .slice(0, 180) || "pairing step failed";
}

async function loadPairingSessions(): Promise<PairingSession[]> {
  const stored = (await chrome.storage.local.get([PAIRING_SESSIONS_KEY]))[PAIRING_SESSIONS_KEY];
  if (!Array.isArray(stored)) return [];
  return stored.filter((item): item is PairingSession => (
    !!item && typeof item === "object" &&
    !!(item as PairingSession).request &&
    typeof (item as PairingSession).request.sessionId === "string"
  ));
}

async function savePairingSessions(sessions: PairingSession[]): Promise<void> {
  await chrome.storage.local.set({ [PAIRING_SESSIONS_KEY]: sessions });
}

async function readyPairingRelays(
  relays: string[],
  recipientPubkey: string,
  recipientSeckey: Uint8Array,
  nowSecs: number,
): Promise<string[]> {
  const pool = new SimplePool();
  const ready = await Promise.all(relays.map(async (url) => {
    try {
      await queryRelayWithAuth(
        pool,
        url,
        { kinds: [WRAP_KIND], "#p": [recipientPubkey], since: nowSecs - 172800, limit: 1 },
        recipientSeckey,
        4500,
      );
      return url;
    } catch {
      return null;
    }
  }));
  try { pool.close(relays); } catch { /* ignore */ }
  return ready.filter((url): url is string => url !== null);
}

async function queryPairingEvents(
  relays: string[],
  recipientPubkey: string,
  since: number,
  recipientSeckey: Uint8Array,
): Promise<Array<Parameters<typeof unwrapAndVerifyEnvelope>[0]["wrap"]>> {
  const pool = new SimplePool();
  const batches = await Promise.all(relays.map(async (url) => {
    try {
      return await queryRelayWithAuth(
        pool,
        url,
        { kinds: [WRAP_KIND], "#p": [recipientPubkey], since, limit: 64 },
        recipientSeckey,
        5000,
      );
    } catch {
      return [];
    }
  }));
  try { pool.close(relays); } catch { /* ignore */ }
  const unique = new Map<string, Parameters<typeof unwrapAndVerifyEnvelope>[0]["wrap"]>();
  for (const event of batches.flat()) unique.set(event.id, event as never);
  return [...unique.values()].sort((a, b) => a.created_at - b.created_at);
}

/** A separate outer key and signed wrap are generated for every relay. */
async function publishFreshPayload(
  relays: string[],
  senderSeckey: Uint8Array,
  recipientPubkey: string,
  payload: unknown,
  expireSecs = 600,
): Promise<string[]> {
  const pool = new SimplePool();
  const accepted = await Promise.all(relays.map(async (url) => {
    const { wrap } = await sealAndWrap({
      senderSeckey,
      recipientPubkey,
      payload,
      wrapKind: WRAP_KIND,
      expireSecs,
    });
    const [result] = await publishPerRelay(pool, [url], wrap as never, senderSeckey);
    return result?.ok ? url : null;
  }));
  try { pool.close(relays); } catch { /* ignore */ }
  return accepted.filter((url): url is string => url !== null);
}

async function processPairingSession(session: PairingSession): Promise<PairingSession> {
  const now = Math.floor(Date.now() / 1000);
  let next: PairingSession = { ...session, lastAttemptAt: now, lastError: undefined };
  if (!isActivePairingState(next.state)) return next;
  if (now >= next.request.expiresAt + 600) {
    return { ...stripPairingSecret(next), state: "expired", lastError: "Pairing expired. Create a new code." };
  }

  if (next.state === "waiting_response") {
    if (!next.pairingSeckey) {
      return { ...next, state: "expired", lastError: "Pairing secret is unavailable. Create a new code." };
    }
    const pairingSeckey = hexToSeckey(next.pairingSeckey);
    const events = await queryPairingEvents(
      next.request.relays.filter((relay) => (DEFAULT_RELAYS as readonly string[]).includes(relay)),
      next.request.pairingPubkey,
      next.request.createdAt - 172800,
      pairingSeckey,
    );
    for (const event of events) {
      try {
        const envelope = await unwrapAndVerifyEnvelope({
          wrap: event,
          recipientSeckey: pairingSeckey,
          expectedProtocols: [PAIRING_PROTOCOL],
          nowSecs: now,
        });
        const response = validatePairResponse(envelope.payload, next.request, envelope.senderPubkey, now);
        next = {
          ...next,
          state: "response_validated",
          androidChannelPubkey: response.androidChannelPubkey,
          responseRumorId: envelope.rumorId,
        };
        break;
      } catch {
        // Hostile, stale, or unrelated events are deliberately ignored.
      }
    }
  }

  if (next.state === "response_validated" && next.androidChannelPubkey) {
    const { seckey } = await getDeviceKey();
    const ack = createPairAck(next.request, next.androidChannelPubkey, next.request.relays, now);
    const accepted = await publishFreshPayload(next.request.relays, seckey, next.androidChannelPubkey, ack);
    if (!accepted.length) {
      return { ...next, lastError: "No pairing relay accepted Chrome's authenticated acknowledgement yet." };
    }
    next = { ...next, state: "waiting_completion", lastError: undefined };
  }

  if (next.state === "waiting_completion" && next.androidChannelPubkey) {
    const { seckey, pubkey } = await getDeviceKey();
    const events = await queryPairingEvents(
      next.request.relays.filter((relay) => (DEFAULT_RELAYS as readonly string[]).includes(relay)),
      pubkey,
      next.request.createdAt - 172800,
      seckey,
    );
    for (const event of events) {
      try {
        const envelope = await unwrapAndVerifyEnvelope({
          wrap: event,
          recipientSeckey: seckey,
          expectedSenderPubkey: next.androidChannelPubkey,
          expectedProtocols: [PAIRING_PROTOCOL],
          nowSecs: now,
        });
        validatePairComplete(envelope.payload, next.request, next.androidChannelPubkey, envelope.senderPubkey, now);
        return {
          ...stripPairingSecret(next),
          state: "complete",
          completedAt: now,
          lastError: undefined,
        };
      } catch {
        // Only an authenticated completion over the exact transcript is accepted.
      }
    }
  }
  return next;
}

async function recoverPairingSessionsInternal(): Promise<void> {
  await lockDownKeyStorage();
  let sessions = await loadPairingSessions();
  const now = Math.floor(Date.now() / 1000);
  // Retain terminal status briefly for the UI, never its bootstrap secret.
  sessions = sessions.filter((session) => (
    isActivePairingState(session.state) ||
    (session.completedAt ?? session.request.expiresAt) > now - 600
  ));

  let completedIndex = -1;
  for (let index = 0; index < sessions.length; index += 1) {
    if (!isActivePairingState(sessions[index]!.state)) continue;
    try {
      sessions[index] = await processPairingSession(sessions[index]!);
    } catch (error) {
      sessions[index] = { ...sessions[index]!, lastAttemptAt: now, lastError: safePairingError(error) };
    }
    if (sessions[index]!.state === "complete") {
      completedIndex = index;
      break;
    }
  }

  if (completedIndex >= 0) {
    const winner = sessions[completedIndex]!;
    sessions = sessions.map((session, index) => (
      index === completedIndex || !isActivePairingState(session.state)
        ? session
        : { ...stripPairingSecret(session), state: "superseded" as const, lastError: "Another pairing session completed first." }
    ));
    // Stop any old-channel publisher between payloads before installing the
    // newly authenticated channel transcript.
    transportEpoch += 1;
    await chrome.storage.local.set({
      [PAIRING_SESSIONS_KEY]: sessions,
      channelPubkey: winner.androidChannelPubkey,
      relays: winner.request.relays,
      protocolVersion: 2,
    });
    const items = await outboxAll().catch(() => []);
    const { seckey } = await getDeviceKey();
    for (const item of items) void publishTransfer(item, seckey, winner.androidChannelPubkey!).catch(() => undefined);
  } else {
    await savePairingSessions(sessions);
  }
  await chrome.storage.local.remove(["pairingSeckey", "pairingNonce", "pairingResponse"]);
}

async function recoverPairingSessions(): Promise<void> {
  if (!pairingRecovery) {
    pairingRecovery = pairingOperations.run(recoverPairingSessionsInternal)
      .finally(() => { pairingRecovery = null; });
  }
  return pairingRecovery;
}

async function startPairingInternal(): Promise<{ qr: PairingRequestV2; sessionId: string }> {
  let sessions = await loadPairingSessions();
  const active = sessions.filter((session) => isActivePairingState(session.state));
  if (active.length >= MAX_PAIRING_SESSIONS) throw new Error("Two pairing codes are already active. Cancel or let one expire.");

  const { pubkey } = await getDeviceKey();
  const pairingSeckey = randomSeckey();
  const pairingPubkey = getPublicKey(pairingSeckey);
  const now = Math.floor(Date.now() / 1000);
  const candidates = await configuredRelaySet();
  // Do not contact a user-supplied hostname before Android has resolved and
  // rejected private/local destinations. The fixed public defaults bootstrap
  // the handshake; Android then validates the complete signed set.
  const readyRelays = await readyPairingRelays([...DEFAULT_RELAYS], pairingPubkey, pairingSeckey, now);
  if (readyRelays.length < RELAY_WRITE_QUORUM) {
    throw new Error(`Only ${readyRelays.length} of 6 default relays are reachable; at least ${RELAY_WRITE_QUORUM} are required.`);
  }
  const request = await createPairingRequest({
    sessionId: randomHex(16),
    pairingPubkey,
    chromeDevicePubkey: pubkey,
    nonce: randomHex(32),
    // Keep the full verified default set in the signed transcript. A relay
    // that is transiently unavailable during QR creation can recover later;
    // permanently shrinking the channel here would defeat the redundancy.
    relays: candidates,
    nowSecs: now,
  });
  sessions = [...sessions, {
    request,
    pairingSeckey: seckeyToHex(pairingSeckey),
    state: "waiting_response",
  }];
  await savePairingSessions(sessions);
  return { qr: request, sessionId: request.sessionId };
}

async function startPairing(): Promise<{ qr: PairingRequestV2; sessionId: string }> {
  await recoverPairingSessions();
  return pairingOperations.run(startPairingInternal);
}

async function pairingStatus(sessionId: string): Promise<Record<string, unknown>> {
  await recoverPairingSessions();
  return pairingOperations.run(async () => {
    const session = (await loadPairingSessions()).find((item) => item.request.sessionId === sessionId);
    if (!session) return { state: "missing", message: "Pairing session is no longer available." };
    const message = session.state === "waiting_response"
      ? "Waiting for Android's encrypted response…"
      : session.state === "response_validated"
        ? "Android authenticated. Sending Chrome acknowledgement…"
        : session.state === "waiting_completion"
          ? "Chrome acknowledged Android. Waiting for final confirmation…"
          : session.state === "complete"
            ? "Connected. Both devices authenticated the same pairing session."
            : session.lastError ?? "Pairing ended.";
    return {
      state: session.state,
      message,
      expiresAt: session.request.expiresAt,
      error: session.lastError,
    };
  });
}

async function cancelPairingInternal(sessionId: string): Promise<boolean> {
  const sessions = await loadPairingSessions();
  let found = false;
  const updated = sessions.map((session) => {
    if (session.request.sessionId !== sessionId || !isActivePairingState(session.state)) return session;
    found = true;
    return { ...stripPairingSecret(session), state: "cancelled" as const, lastError: "Pairing cancelled." };
  });
  await savePairingSessions(updated);
  return found;
}

async function cancelPairing(sessionId: string): Promise<boolean> {
  return pairingOperations.run(() => cancelPairingInternal(sessionId));
}

// ---- Wiring ----
chrome.runtime.onMessage.addListener((msg, _sender, sendResponse) => {
  (async () => {
    if (msg?.kind === "reader-capture") {
      const r = await queueCapture(msg.doc);
      sendResponse({ ok: true, ...r });
    } else if (msg?.kind === "reader-start-pairing") {
      sendResponse({ ok: true, ...(await startPairing()) });
    } else if (msg?.kind === "reader-pairing-status") {
      sendResponse({ ok: true, ...(await pairingStatus(String(msg.sessionId ?? ""))) });
    } else if (msg?.kind === "reader-cancel-pairing") {
      sendResponse({ ok: true, cancelled: await cancelPairing(String(msg.sessionId ?? "")) });
    } else if (msg?.kind === "reader-status") {
      const st = await chrome.storage.local.get(["channelPubkey", "relays"]);
      const items = await outboxAll().catch(() => []);
      const customRelays = await loadCustomRelays();
      const receipts = await loadDeliveryReceipts();
      const nextRelays = configuredRelays(customRelays);
      const pairedRelays = Array.isArray(st.relays) ? st.relays as string[] : [];
      const delivery = summarizeDeliveryStates(items, receipts.length);
      sendResponse({
        ok: true,
        paired: !!st.channelPubkey,
        relays: pairedRelays,
        pending: delivery.queued + delivery.relayAccepted + delivery.awaitingDevice,
        delivery,
        defaultRelays: [...DEFAULT_RELAYS],
        customRelays,
        configuredRelays: nextRelays,
        relayChangePending: !!st.channelPubkey && !sameRelayOrder(pairedRelays, nextRelays),
      });
    } else if (msg?.kind === "reader-save-custom-relays") {
      const customRelays = normalizeCustomRelays(msg.relays);
      await chrome.storage.local.set({ [CUSTOM_RELAYS_KEY]: customRelays });
      const nextRelays = configuredRelays(customRelays);
      const st = await chrome.storage.local.get(["channelPubkey", "relays"]);
      const pairedRelays = Array.isArray(st.relays) ? st.relays as string[] : [];
      sendResponse({
        ok: true,
        customRelays,
        configuredRelays: nextRelays,
        relayChangePending: !!st.channelPubkey && !sameRelayOrder(pairedRelays, nextRelays),
      });
    } else if (msg?.kind === "reader-disconnect") {
      transportEpoch += 1;
      await pairingOperations.run(async () => {
        const sessions = cancelActivePairingSessions(
          await loadPairingSessions(),
          "Pairing cancelled by disconnect.",
        );
        await savePairingSessions(sessions);
        await chrome.storage.local.remove([...PAIRED_CHANNEL_STORAGE_KEYS]);
      });
      sendResponse({ ok: true });
    } else if (msg?.kind === "reader-discard-failed") {
      const failed = (await outboxAll()).filter((item) => item.status === "failed");
      const discarded = (await Promise.all(failed.map((item) => transferOperations.run(item.transferId, async () => {
        const durable = await outboxGet(item.transferId);
        if (durable?.status !== "failed") return false;
        await outboxDelete(item.transferId);
        return true;
      })))).filter(Boolean).length;
      sendResponse({ ok: true, discarded });
    } else if (msg?.kind === "reader-check-acks") {
      // Read-only status refresh: query for authenticated device ACKs without
      // retransmitting article payloads.
      await pollForAcks();
      sendResponse({ ok: true });
    } else if (msg?.kind === "reader-retry") {
      // Consume already-arrived device ACKs before retransmitting payloads.
      await pollForAcks().catch(() => undefined);
      const items = await outboxAll();
      const { seckey } = await getDeviceKey();
      const ch = (await chrome.storage.local.get(["channelPubkey"])).channelPubkey as string | undefined;
      const retryable = items.filter((item) => item.status !== "failed");
      if (ch) await Promise.all(retryable.map((item) => publishTransfer(item, seckey, ch, true).catch(() => undefined)));
      sendResponse({ ok: true, retried: retryable.length });
    }
  })().catch((e) => sendResponse({ ok: false, error: String(e).slice(0, 300) }));
  return true;
});

chrome.action.onClicked.addListener(async (tab) => {
  const st = await chrome.storage.local.get(["channelPubkey"]);
  if (!st.channelPubkey) {
    await chrome.tabs.create({ url: chrome.runtime.getURL("src/ui/pairing.html") });
    return;
  }
  if (tab.id != null) {
    try {
      await chrome.tabs.sendMessage(tab.id, { kind: "reader-capture-now" });
      return;
    } catch { /* fall through to scripting */ }
  }
  if (tab.id != null) {
    try {
      await chrome.scripting.executeScript({ target: { tabId: tab.id }, files: ["content.js"] });
      await chrome.tabs.sendMessage(tab.id, { kind: "reader-capture-now" });
    } catch { /* offline or restricted page */ }
  }
});

chrome.alarms.create(RETRY_ALARM, { periodInMinutes: 15 });
chrome.alarms.create(PAIRING_ALARM, { periodInMinutes: 1 });
chrome.alarms.onAlarm.addListener(async (a) => {
  if (a.name === PAIRING_ALARM) {
    void recoverPairingSessions().catch(() => undefined);
    return;
  }
  if (a.name === ACK_ALARM) {
    await pollForAcks().catch(() => undefined);
    return;
  }
  if (a.name !== RETRY_ALARM) return;
  // ACK-first ordering prevents a late publisher from resurrecting an outbox
  // item that was concurrently deleted after authenticated device storage.
  await pollForAcks().catch(() => undefined);
  const items = await outboxAll().catch(() => []);
  if (!items.length) return;
  const st = await chrome.storage.local.get(["channelPubkey"]);
  if (!st.channelPubkey) return;
  const { seckey } = await getDeviceKey();
  const now = Math.floor(Date.now() / 1000);
  await Promise.all(
    items
      .filter((candidate) => candidate.status !== "failed" && (candidate.nextAttemptAt ?? 0) <= now)
      .map((item) => publishTransfer(item, seckey, st.channelPubkey as string).catch(() => undefined)),
  );
});

chrome.runtime.onInstalled.addListener(() => {
  void lockDownKeyStorage()
    .then(getDeviceKey)
    .then(() => recoverPairingSessions())
    .catch(() => undefined);
  try { chrome.contextMenus.create({ id: "reader-send-selection", title: "Send selection to Reader", contexts: ["selection"] }); } catch { /* exists */ }
});
chrome.runtime.onStartup.addListener(() => {
  void recoverPairingSessions().catch(() => undefined);
  void pollForAcks().catch(() => undefined);
  // The phone may still be finishing a background sync when Chrome starts.
  // A durable alarm provides one more read-only catch-up after the worker can
  // already have gone idle; it never republishes the captured article.
  scheduleAckCheck();
});
chrome.contextMenus.onClicked.addListener(async (info) => {
  if (info.menuItemId !== "reader-send-selection" || !info.selectionText) return;
  if (info.selectionText.trim().length < 10) return;
  await queueCapture({ title: "Selection", markdown: info.selectionText.trim() + "\n", sourceType: "selection" }).catch(() => undefined);
});
