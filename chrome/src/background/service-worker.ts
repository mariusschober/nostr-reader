// Service worker: key ownership, outbox, pairing, quorum send, E2E ACK listen.
// Private keys never leave this scope. State is durable (storage.local +
// IndexedDB) because MV3 workers are suspended at will.
import { publishFragments, type FragmentOutcome } from "./resumable-publisher.js";
import { scanHistory, type ScanState } from "../nostr/history-scan.js";
import { DurableAlarms } from "./durable-alarms.js";
import { CAPTURE_FEEDBACK_TEXT, type CaptureFeedbackRoute, type CaptureFeedbackState } from "../protocol/capture-feedback.js";
import { SimplePool } from "nostr-tools/pool";
import { getPublicKey } from "nostr-tools/pure";
import {
  READER_PROTOCOL, escapePlainText, canonicalize, documentId, wordCount, syncSince, checkLimits,
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
  relaySetDigest,
  type PairingRequestV2,
  validatePairComplete,
  validatePairResponse,
} from "../protocol/pairing.js";
import { manifestIdentity, validateEndpointAck } from "../protocol/messages.js";
import {
  configuredRelays,
  DEFAULT_RELAYS,
  normalizeCustomRelays,
  pairingReadRelays,
  RELAY_WRITE_QUORUM,
  sameRelayOrder,
  validatePairedRelaySet,
} from "../protocol/relays.js";
import {
  cancelActivePairingSessions,
  isActivePairingState,
  pairingAckAttemptTransition,
  pairingAckRetryDue,
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
import {
  decodeStoredSecret,
  inspectDeviceBinding,
  isValidXOnlyPubkey,
} from "../protocol/device-binding.js";

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
  fragmentProgress?: Record<string, Record<string, FragmentOutcome>>;
  feedbackRoute?: CaptureFeedbackRoute;
}

interface PairingSession {
  request: PairingRequestV2;
  pairingSeckey?: string;
  state: PairingLifecycleState;
  androidChannelPubkey?: string;
  responseRumorId?: string;
  lastAttemptAt?: number;
  lastAckAttemptAt?: number;
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
const captureOperations = new SerialExecutor();
const publicationOwner = new SerialExecutor();
const activePublicationStops = new Map<string, () => void>();
const transferOperations = new KeyedSerialExecutor<string>();
const receiptOperations = new SerialExecutor();
const pairingOperations = new SerialExecutor();
let pairingRecovery: Promise<void> | null = null;
let ackPolling: Promise<void> | null = null;
let transportEpoch = 0;
const durableAlarms = new DurableAlarms(chrome.alarms);
let retryRecovery: Promise<void> | null = null;

async function restoreWorkAlarms(): Promise<void> {
  await durableAlarms.reconcile(RETRY_ALARM, async () => {
    const items = (await outboxAll()).filter(item => item.status !== "failed");
    if (!items.length || !await loadPairedChannelState()) return undefined;
    return Math.min(...items.map(item => (item.nextAttemptAt ?? item.createdAt) * 1000));
  });
  await durableAlarms.reconcile(PAIRING_ALARM, async () => {
    const sessions = (await loadPairingSessions()).filter(session => isActivePairingState(session.state));
    if (!sessions.length) return undefined;
    return Math.min(...sessions.map(session => ((session.lastAttemptAt ?? session.request.createdAt) + 30) * 1000));
  });
}

function hexToSeckey(value: unknown): Uint8Array {
  return decodeStoredSecret(value).seckey;
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

interface PairedChannelState {
  channelPubkey: string;
  devicePubkey: string;
  relays: string[];
  relaySetDigest: string;
}

async function pairedChannelState(st: Record<string, unknown>): Promise<PairedChannelState | undefined> {
  const channelPubkey = st["channelPubkey"];
  if (
    st["protocolVersion"] !== 2 ||
    !isValidXOnlyPubkey(channelPubkey)
  ) return undefined;
  try {
    const identity = inspectDeviceBinding(st["deviceSeckey"], st["channelDevicePubkey"], true);
    if (identity.state !== "bound" && identity.state !== "legacy") return undefined;
    const relays = validatePairedRelaySet(st["relays"]);
    const expectedDigest = await relaySetDigest(relays);
    const storedDigest = st["channelRelaySetDigest"];
    const migration: Record<string, unknown> = {};
    if (identity.state === "legacy") migration["channelDevicePubkey"] = identity.pubkey;
    if (storedDigest === undefined) {
      // Compatibility migration for channels completed by an earlier v2
      // build, which authenticated this exact list but did not retain its
      // digest beside the channel. New completions always write both.
      migration["channelRelaySetDigest"] = expectedDigest;
    } else if (storedDigest !== expectedDigest) {
      return undefined;
    }
    if (Object.keys(migration).length) await chrome.storage.local.set(migration);
    return { channelPubkey, devicePubkey: identity.pubkey!, relays, relaySetDigest: expectedDigest };
  } catch {
    return undefined;
  }
}

async function loadPairedChannelState(): Promise<PairedChannelState | undefined> {
  const st = await chrome.storage.local.get(["deviceSeckey", ...PAIRED_CHANNEL_STORAGE_KEYS]);
  return pairedChannelState(st as Record<string, unknown>);
}

async function getDeviceKey(): Promise<{ seckey: Uint8Array; pubkey: string }> {
  await lockDownKeyStorage();
  const st = await chrome.storage.local.get(["deviceSeckey", ...PAIRED_CHANNEL_STORAGE_KEYS]);
  const hasChannelBinding = PAIRED_CHANNEL_STORAGE_KEYS.some((key) => st[key] !== undefined);
  const identity = inspectDeviceBinding(st.deviceSeckey, st.channelDevicePubkey, hasChannelBinding);
  if (identity.seckey && identity.pubkey) {
    if (identity.state === "invalid" || identity.state === "mismatch") {
      // Preserve the still-valid local device key, but never associate it with
      // a channel authenticated by a different identity.
      await chrome.storage.local.remove([...PAIRED_CHANNEL_STORAGE_KEYS]);
    }
    return { seckey: identity.seckey, pubkey: identity.pubkey };
  }
  // A lost or malformed private key makes the old channel unrecoverable. Strip
  // only that binding before creating a replacement identity; preferences and
  // durable outbox content remain for an explicit re-pair.
  if (hasChannelBinding) await chrome.storage.local.remove([...PAIRED_CHANNEL_STORAGE_KEYS]);
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
    const r = indexedDB.open("reader-outbox", 2);
    r.onupgradeneeded = () => {
      if (!r.result.objectStoreNames.contains("items")) r.result.createObjectStore("items", { keyPath: "transferId" });
      if (!r.result.objectStoreNames.contains("captures")) r.result.createObjectStore("captures", { keyPath: "captureId" });
    };
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
  )).slice(-1000);
}

async function recordDelivered(transferId: string, deliveredAt: number): Promise<void> {
  await receiptOperations.run(async () => {
    const receipts = (await loadDeliveryReceipts(deliveredAt)).filter((item) => item.transferId !== transferId);
    receipts.push({ transferId, deliveredAt });
    await chrome.storage.local.set({ [DELIVERY_RECEIPTS_KEY]: receipts.slice(-1000) });
  });
}

// ---- Send pipeline ----
export async function queueCapture(raw: { title: string; markdown: string; sourceUrl?: string; sourceType?: string }, captureId = randomHex(16), feedbackRoute?: CaptureFeedbackRoute): Promise<{ transferId: string; queued: boolean }> {
  if (!raw || typeof raw.title !== "string" || typeof raw.markdown !== "string" || !raw.markdown.trim() ||
      raw.markdown.length > 20 * 1024 * 1024 || raw.title.length > 500 ||
      (raw.sourceUrl !== undefined && (typeof raw.sourceUrl !== "string" || raw.sourceUrl.length > 2000)) ||
      !/^[a-f0-9-]{32,36}$/.test(captureId)) throw new Error("Invalid capture or content too large.");
  return captureOperations.run(async () => {
    const db = await idb();
    try {
      const existing = await new Promise<any>((resolve, reject) => {
        const request = db.transaction("captures").objectStore("captures").get(captureId);
        request.onsuccess = () => resolve(request.result); request.onerror = () => reject(request.error);
      });
      if (existing) return { transferId: existing.transferId, queued: !await loadPairedChannelState() };
      return await queueCaptureInternal(raw, captureId, feedbackRoute);
    } finally { db.close(); }
  });
}

async function queueCaptureInternal(raw: { title: string; markdown: string; sourceUrl?: string; sourceType?: string }, captureId: string, feedbackRoute?: CaptureFeedbackRoute): Promise<{ transferId: string; queued: boolean }> {
  const { seckey, pubkey } = await getDeviceKey();
  const canonical = canonicalize(raw.markdown);
  const canonicalBytes = new TextEncoder().encode(canonical);
  const docId = await documentId(canonical);
  const gz = deterministicGzip(canonicalBytes);
  const paired = await loadPairedChannelState();
  const channelPubkey = paired?.channelPubkey;
  // If durable channel metadata is incomplete, retain this capture as an
  // unbound queued item. Never guess a relay set for a supposedly paired
  // channel; a later authenticated re-pair can bind it safely.
  const relays = paired?.relays ?? [...DEFAULT_RELAYS];
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
    feedbackRoute,
  };
  const captureDb = await idb();
  try {
    await new Promise<void>((resolve, reject) => {
      const tx = captureDb.transaction(["items", "captures"], "readwrite");
      tx.oncomplete = () => resolve(); tx.onerror = () => reject(tx.error); tx.onabort = () => reject(tx.error);
      try {
        tx.objectStore("items").put(item);
        tx.objectStore("captures").put({ captureId, transferId, ...raw, createdAt: now });
      } catch (error) {
        // Synchronous request setup failures do not automatically roll back
        // earlier queued writes. Never leave a send without its retained capture.
        tx.abort();
        reject(error);
      }
    });
  } finally { captureDb.close(); }
  void restoreWorkAlarms().catch(() => undefined);
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

async function bindOutboxItem(
  item: OutboxItem,
  channelPubkey: string,
  senderDevicePubkey: string,
): Promise<void> {
  const manifest = item.manifest;
  const existingRecipient = String(manifest["recipientChannelPubkey"] ?? "");
  if (existingRecipient && existingRecipient !== channelPubkey) throw new Error("queued transfer is bound to a different channel");
  const paired = await loadPairedChannelState();
  if (!paired || paired.channelPubkey !== channelPubkey) throw new Error("paired channel state is unavailable");
  if (paired.devicePubkey !== senderDevicePubkey) throw new Error("paired device identity changed");
  const manifestId = String(manifest["manifestId"] ?? "");
  if (!manifestId) {
    item.relays = [...paired.relays];
    manifest["recipientChannelPubkey"] = channelPubkey;
    // An unbound capture may predate a local identity repair. It has never
    // been transmitted or authenticated, so bind it to the current key before
    // computing its immutable manifest identity.
    manifest["senderDevicePubkey"] = senderDevicePubkey;
    manifest["manifestId"] = await manifestIdentity({
      transferId: item.transferId,
      documentId: item.documentId,
      compressedSha256: String(manifest["compressedSha256"]),
      compressedBytes: Number(manifest["compressedBytes"]),
      chunkCount: Number(manifest["chunkCount"]),
      senderDevicePubkey,
      recipientChannelPubkey: channelPubkey,
      expiresAt: Number(manifest["expiresAt"]),
    });
    await outboxPut(item);
  } else if (existingRecipient !== channelPubkey) {
    throw new Error("bound transfer recipient is unavailable");
  } else if (manifest["senderDevicePubkey"] !== senderDevicePubkey) {
    throw new Error("bound transfer sender identity is unavailable");
  }
  item.relays = validatePairedRelaySet(item.relays);
  if (!sameRelayOrder(item.relays, paired.relays)) throw new Error("bound transfer relay set changed");
}

async function publishTransfer(
  item: OutboxItem, seckey: Uint8Array, channelPubkey: string, force = false,
): Promise<void> {
  return publicationOwner.run(async () => {
    const epoch = transportEpoch;
    const snapshot = await transferOperations.run(item.transferId, async () => {
      const durable = await outboxGet(item.transferId);
      if (!durable) return;
      // Recover the receipt-before-cleanup crash without republishing.
      if ((await loadDeliveryReceipts()).some(receipt => receipt.transferId === item.transferId)) {
        void notifyCapture(durable.feedbackRoute, "delivered", durable.transferId);
        await outboxDelete(item.transferId);
        return;
      }
      const now = Math.floor(Date.now() / 1000);
      if (durable.status === "failed" || (!force && durable.nextAttemptAt > now)) return;
      const ceiling = deliveryCeilingReason(durable, now);
      try {
        if (ceiling) throw new Error(ceiling);
        await bindOutboxItem(durable, channelPubkey, getPublicKey(seckey));
      } catch (error) {
        durable.status = "failed";
        durable.lastError = safePairingError(error);
        await outboxPut(durable);
        void notifyCapture(durable.feedbackRoute, "error", durable.transferId);
        return;
      }
      durable.attemptCount++;
      durable.nextAttemptAt = retryAt(durable.attemptCount, now);
      await outboxPut(durable); // attempt reservation survives interruption
      return durable;
    });
    if (!snapshot) return;
    scheduleAckCheck();
    const generation = snapshot.attemptCount;
    const current = async () => {
      const latest = await outboxGet(snapshot.transferId);
      return epoch === transportEpoch && !!latest && latest.status !== "failed" && latest.attemptCount === generation;
    };
    const pool = new SimplePool();
    activePublicationStops.set(snapshot.transferId, () => pool.close(snapshot.relays));
    try {
      await publishFragments({
        snapshot: { relays: snapshot.relays, payloadCount: snapshot.chunks.length + 1, progress: snapshot.fragmentProgress ?? {} },
        current,
        send: async (index, relay) => {
          const payload = index === 0 ? snapshot.manifest : {
            protocol: READER_PROTOCOL, type: "chunk", transferId: snapshot.transferId,
            manifestId: snapshot.manifest["manifestId"], documentId: snapshot.documentId,
            compressedSha256: snapshot.manifest["compressedSha256"], index: index - 1,
            count: snapshot.chunks.length, dataBase64: snapshot.chunks[index - 1], expiresAt: snapshot.expiresAt,
          };
          const { wrap } = await sealAndWrap({ senderSeckey: seckey, recipientPubkey: channelPubkey, payload, expireSecs: Math.max(1, snapshot.expiresAt - Math.floor(Date.now() / 1000)) });
          if (new TextEncoder().encode(JSON.stringify(["EVENT", wrap])).length > 512 * 1024) throw new Error("encrypted frame too large");
          const [outcome] = await publishPerRelay(pool, [relay], wrap as never, seckey);
          return { state: outcome?.state ?? "SOCKET_ERROR", at: Date.now() };
        },
        checkpoint: (index, relay, outcome) => transferOperations.run(snapshot.transferId, async () => {
          if (!await current()) return false;
          const durable = (await outboxGet(snapshot.transferId))!;
          ((durable.fragmentProgress ??= {})[String(index)] ??= {})[relay] = outcome;
          if (outcome.state === "OK_TRUE") durable.status = "relay_accepted";
          await outboxPut(durable);
          return true;
        }),
      });
      await transferOperations.run(snapshot.transferId, async () => {
        if (!await current()) return;
        const durable = (await outboxGet(snapshot.transferId))!;
        const complete = Array.from({ length: snapshot.chunks.length + 1 }, (_, i) =>
          snapshot.relays.filter(relay => durable.fragmentProgress?.[String(i)]?.[relay]?.state === "OK_TRUE").length >= RELAY_WRITE_QUORUM).every(Boolean);
        durable.status = complete ? "awaiting_device" : durable.status;
        durable.lastError = complete ? undefined : "Relay quorum incomplete; captured content retained.";
        await outboxPut(durable);
      });
    } finally {
      activePublicationStops.delete(snapshot.transferId);
      pool.close(snapshot.relays);
      scheduleAckCheck();
      void pollForAcks().catch(() => undefined);
      await restoreWorkAlarms();
    }
  });
}

// ---- E2E ACK catch-up (rolling window: NIP-59 timestamps are randomized) ----
async function pollForAcksInternal(): Promise<void> {
  const st = await chrome.storage.local.get(["deviceSeckey", ...PAIRED_CHANNEL_STORAGE_KEYS]);
  const paired = await pairedChannelState(st as Record<string, unknown>);
  if (!st.deviceSeckey || !paired) return;
  const seckey = hexToSeckey(st.deviceSeckey);
  const devicePubkey = getPublicKey(seckey);
  const { channelPubkey, relays } = paired;
  const now = Math.floor(Date.now() / 1000);
  const consume = async (events: Awaited<ReturnType<typeof queryPairingEvents>>) => {
  for (const event of events) {
    let storageOperation = false;
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
      storageOperation = true;
      await transferOperations.run(transferId, async () => {
        const item = await outboxGet(transferId);
        if (!item) return;
        const ack = runSafely(() => validateEndpointAck(
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
        ), null);
        if (!ack) return;
        if (ack.status === "stored" || ack.status === "duplicate") {
          // Persist the delivered receipt before deleting the only durable
          // outbox copy. If receipt storage fails, retain the item so the same
          // authenticated ACK can safely settle it on a later poll.
          await recordDelivered(item.transferId, now);
          // A receipt, not relay acceptance, authorizes the visible confirmation.
          // Route survives worker restarts; document targeting prevents updates
          // from leaking into a different page that later occupies the tab.
          void notifyCapture(item.feedbackRoute, "delivered", item.transferId);
          activePublicationStops.get(item.transferId)?.();
          await outboxDelete(item.transferId); // Only this strict E2E ACK clears captured content.
        } else {
          item.status = "failed";
          item.lastError = ack.reasonCode ?? "device-rejected";
          await outboxPut(item);
          void notifyCapture(item.feedbackRoute, "error", item.transferId);
        }
      });
    } catch (error) {
      if (storageOperation) throw error;
      // Hostile, expired, misbound, or unrelated events never mutate the outbox.
    }
  }
  };
  await queryPairingEvents(relays, devicePubkey, syncSince(now), seckey, consume);
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
  await restoreWorkAlarms();
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
  consume?: (events: Awaited<ReturnType<typeof queryRelayWithAuth>>) => Promise<void>,
): Promise<Array<Parameters<typeof unwrapAndVerifyEnvelope>[0]["wrap"]>> {
  const pool = new SimplePool();
  const batches = await Promise.allSettled(relays.map(async (url) => {
    const key = `readerScan:${recipientPubkey}:${url}`;
    try {
      const stored = (await chrome.storage.local.get(key))[key] as ScanState | undefined;
      const result = await scanHistory({
        since, until: Math.floor(Date.now() / 1000) + 600, state: stored,
        query: window => queryRelayWithAuth(pool, url, { kinds: [WRAP_KIND], "#p": [recipientPubkey], ...window }, recipientSeckey, 2500),
        checkpoint: async state => { await chrome.storage.local.set({ [key]: state }); },
        consume,
      });
      await chrome.storage.local.set({ [`${key}:coverage`]: result.complete ? "covered" : "incomplete" });
      return result.events;

    } catch (error) {
      await chrome.storage.local.set({ [`${key}:coverage`]: "failed" });
      throw error;
    }
  }));
  try { pool.close(relays); } catch { /* ignore */ }
  const successful = batches.filter((batch): batch is PromiseFulfilledResult<Awaited<ReturnType<typeof queryRelayWithAuth>>> => batch.status === "fulfilled");
  await chrome.storage.local.set({ readerLastReceive: {
    checkedAt: Date.now(), successfulRelays: successful.length, failedRelays: batches.length - successful.length,
  } });
  if (!successful.length && relays.length) throw new Error("Couldn’t check relays; saved content will retry.");
  const unique = new Map<string, Parameters<typeof unwrapAndVerifyEnvelope>[0]["wrap"]>();
  for (const event of successful.flatMap(batch => batch.value)) unique.set(event.id, event as never);
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
  let next: PairingSession = { ...session, lastAttemptAt: now };
  if (!isActivePairingState(next.state)) return next;
  if (now >= next.request.expiresAt + 600) {
    return { ...stripPairingSecret(next), state: "expired", lastError: "Pairing expired. Create a new code." };
  }
  let requestRelays: string[];
  try {
    requestRelays = validatePairedRelaySet(next.request.relays);
    if (await relaySetDigest(requestRelays) !== next.request.relaySetDigest) {
      throw new Error("pairing relay digest mismatch");
    }
  } catch {
    return {
      ...stripPairingSecret(next),
      state: "cancelled",
      lastError: "Stored pairing state is invalid. Create a new code.",
    };
  }
  const deviceKey = await getDeviceKey();
  if (deviceKey.pubkey !== next.request.chromeDevicePubkey) {
    return {
      ...stripPairingSecret(next),
      state: "cancelled",
      lastError: "Chrome's device key changed. Create a new pairing code.",
    };
  }

  // Once Android's signed channel response is authenticated, this bootstrap
  // key no longer participates in the transcript. Old durable sessions may
  // still contain it; remove it before the next write.
  if (next.state !== "waiting_response" && next.pairingSeckey) {
    next = stripPairingSecret(next);
  }

  if (next.state === "waiting_response") {
    if (!next.pairingSeckey) {
      return { ...next, state: "expired", lastError: "Pairing secret is unavailable. Create a new code." };
    }
    let pairingSeckey: Uint8Array;
    try {
      pairingSeckey = hexToSeckey(next.pairingSeckey);
    } catch {
      return {
        ...stripPairingSecret(next),
        state: "cancelled",
        lastError: "Stored pairing key is invalid. Create a new code.",
      };
    }
    const events = await queryPairingEvents(
      pairingReadRelays("bootstrap_response", requestRelays),
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
        // Persist the authenticated transition without the bootstrap secret
        // before any ACK network attempt. A crash can then only delay the ACK;
        // it cannot extend retention of the one-time decryption key.
        return {
          ...stripPairingSecret(next),
          state: "response_validated",
          androidChannelPubkey: response.androidChannelPubkey,
          responseRumorId: envelope.rumorId,
          lastError: undefined,
        };
      } catch {
        // Hostile, stale, or unrelated events are deliberately ignored.
      }
    }
  }

  if (
    next.state === "response_validated" && next.androidChannelPubkey &&
    pairingAckRetryDue(next.lastAckAttemptAt, now)
  ) {
    const ack = createPairAck(next.request, next.androidChannelPubkey, requestRelays, now);
    const accepted = await publishFreshPayload(requestRelays, deviceKey.seckey, next.androidChannelPubkey, ack);
    // Missing relay OK is not proof that the ACK was not stored. Enter the
    // completion-reading state after every completed publish attempt so an
    // authenticated Android completion can win over absent transport evidence.
    next = {
      ...stripPairingSecret(next),
      ...pairingAckAttemptTransition(accepted.length, now),
    };
  }

  if (next.state === "waiting_completion" && next.androidChannelPubkey) {
    const events = await queryPairingEvents(
      pairingReadRelays("authenticated_completion", requestRelays),
      deviceKey.pubkey,
      next.request.createdAt - 172800,
      deviceKey.seckey,
    );
    for (const event of events) {
      try {
        const envelope = await unwrapAndVerifyEnvelope({
          wrap: event,
          recipientSeckey: deviceKey.seckey,
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
    if (pairingAckRetryDue(next.lastAckAttemptAt, now)) {
      const ack = createPairAck(next.request, next.androidChannelPubkey, requestRelays, now);
      const accepted = await publishFreshPayload(requestRelays, deviceKey.seckey, next.androidChannelPubkey, ack);
      next = {
        ...next,
        lastAckAttemptAt: now,
        lastError: accepted.length
          ? undefined
          : "No pairing relay accepted Chrome's authenticated acknowledgement yet.",
      };
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
      channelDevicePubkey: winner.request.chromeDevicePubkey,
      relays: winner.request.relays,
      channelRelaySetDigest: winner.request.relaySetDigest,
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
    const trustedPage = _sender.id === chrome.runtime.id &&
      ["src/ui/options.html", "src/ui/pairing.html", "src/ui/popup.html"].some(path => _sender.url === chrome.runtime.getURL(path));
    const captureRequest = msg?.kind === "reader-capture" || msg?.kind === "reader-delivery-receipt";
    if (!trustedPage && (!captureRequest || _sender.id !== chrome.runtime.id || !_sender.tab)) throw new Error("Untrusted extension request");
    if (msg?.kind === "reader-capture") {
      const captureId = msg.captureId ?? randomHex(16);
      const route = _sender.tab?.id === undefined ? undefined : {
        captureId, tabId: _sender.tab.id, frameId: _sender.frameId ?? 0, documentId: _sender.documentId,
      };
      const r = await queueCapture(msg.doc, captureId, route);
      sendResponse({ ok: true, ...r });
    } else if (msg?.kind === "reader-delivery-receipt") {
      if (typeof msg.transferId !== "string" || !/^[0-9a-f]{32}$/.test(msg.transferId)) throw new Error("Invalid transfer identity");
      // Opaque transfer capability reveals only its local receipt bit, never
      // retained text, keys, titles or other items. No network IO in this path.
      sendResponse({ ok: true, delivered: (await loadDeliveryReceipts()).some(receipt => receipt.transferId === msg.transferId) });
    } else if (msg?.kind === "reader-start-pairing") {
      sendResponse({ ok: true, ...(await startPairing()) });
    } else if (msg?.kind === "reader-pairing-status") {
      sendResponse({ ok: true, ...(await pairingStatus(String(msg.sessionId ?? ""))) });
    } else if (msg?.kind === "reader-cancel-pairing") {
      sendResponse({ ok: true, cancelled: await cancelPairing(String(msg.sessionId ?? "")) });
    } else if (msg?.kind === "reader-status") {
      const paired = await loadPairedChannelState();
      const items = await outboxAll().catch(() => []);
      const customRelays = await loadCustomRelays();
      const receipts = await loadDeliveryReceipts();
      const nextRelays = configuredRelays(customRelays);
      const pairedRelays = paired?.relays ?? [];
      const delivery = summarizeDeliveryStates(items, receipts.length);
      sendResponse({
        ok: true,
        paired: !!paired,
        relays: pairedRelays,
        pending: delivery.queued + delivery.relayAccepted + delivery.awaitingDevice,
        delivery,
        defaultRelays: [...DEFAULT_RELAYS],
        customRelays,
        configuredRelays: nextRelays,
        relayChangePending: !!paired && !sameRelayOrder(pairedRelays, nextRelays),
      });
    } else if (msg?.kind === "reader-save-custom-relays") {
      const customRelays = normalizeCustomRelays(msg.relays);
      await chrome.storage.local.set({ [CUSTOM_RELAYS_KEY]: customRelays });
      const nextRelays = configuredRelays(customRelays);
      const paired = await loadPairedChannelState();
      const pairedRelays = paired?.relays ?? [];
      sendResponse({
        ok: true,
        customRelays,
        configuredRelays: nextRelays,
        relayChangePending: !!paired && !sameRelayOrder(pairedRelays, nextRelays),
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
      const paired = await loadPairedChannelState();
      const retryable = items.filter((item) => item.status !== "failed");
      if (paired) {
        await Promise.all(retryable.map((item) => (
          publishTransfer(item, seckey, paired.channelPubkey, true).catch(() => undefined)
        )));
      }
      sendResponse({ ok: true, retried: retryable.length });
    }
  })().catch((e) => sendResponse({ ok: false, error: String(e).slice(0, 300) }));
  return true;
});

async function captureTab(tab: chrome.tabs.Tab): Promise<void> {
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
    } catch {
      await chrome.action.setBadgeText({ text: "!", tabId: tab.id });
      await chrome.action.setTitle({ title: "Couldn’t save: this page restricts capture. Open a normal web page or select text.", tabId: tab.id });
    }
  }
}
chrome.action.onClicked.addListener(captureTab);
chrome.commands.onCommand.addListener(async command => {
  if (command === "capture") {
    const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
    if (tab) await captureTab(tab);
  }
});

async function resumeDueTransfers(): Promise<void> {
  if (retryRecovery) return retryRecovery;
  retryRecovery = (async () => {
    await pollForAcks().catch(() => undefined);
    const paired = await loadPairedChannelState();
    if (!paired) return;
    const { seckey } = await getDeviceKey();
    // Bounded owner; network operations never fan out over the whole library.
    for (const item of await outboxAll()) {
      if (item.status !== "failed" && (item.nextAttemptAt ?? 0) <= Math.floor(Date.now() / 1000)) {
        await publishTransfer(item, seckey, paired.channelPubkey).catch(() => undefined);
      }
    }
  })().finally(async () => { retryRecovery = null; await restoreWorkAlarms(); });
  return retryRecovery;
}

chrome.alarms.onAlarm.addListener(async (a) => {
  try {
    if (a.name === PAIRING_ALARM) await recoverPairingSessions();
    if (a.name === ACK_ALARM) {
      await pollForAcks();
      if ((await outboxAll()).some(item => item.status !== "failed")) scheduleAckCheck();
    }
    if (a.name === RETRY_ALARM) await resumeDueTransfers();
  } finally {
    await restoreWorkAlarms();
  }
});
// Evaluation/reload restores missing alarms from persisted work, without moving
// existing deadlines. Startup also executes due work even with all UI closed.
void restoreWorkAlarms().catch(() => undefined);

chrome.runtime.onInstalled.addListener(() => {
  void lockDownKeyStorage()
    .then(getDeviceKey)
    .then(() => recoverPairingSessions())
    .catch(() => undefined);
  try { chrome.contextMenus.create({ id: "reader-options", title: "Reader settings and recent sends", contexts: ["action"] }); } catch {}
  try { chrome.contextMenus.create({ id: "reader-send-selection", title: "Send selection to Reader", contexts: ["selection"] }); } catch { /* exists */ }
});
chrome.runtime.onStartup.addListener(() => {
  void resumeDueTransfers().catch(() => undefined);
  void recoverPairingSessions().catch(() => undefined);
  void pollForAcks().catch(() => undefined);
  // The phone may still be finishing a background sync when Chrome starts.
  // A durable alarm provides one more read-only catch-up after the worker can
  // already have gone idle; it never republishes the captured article.
  scheduleAckCheck();
});
async function captureBadge(tabId: number | undefined, text: string, badge: string): Promise<void> {
  if (tabId === undefined) return;
  await chrome.action.setBadgeText({ tabId, text: badge });
  await chrome.action.setTitle({ tabId, title: text });
}

function feedbackTarget(route: CaptureFeedbackRoute): chrome.tabs.MessageSendOptions {
  return route.documentId ? { documentId: route.documentId } : { frameId: route.frameId };
}

async function notifyCapture(route: CaptureFeedbackRoute | undefined, state: CaptureFeedbackState, transferId?: string, detail?: string): Promise<boolean> {
  if (!route) return false;
  try {
    await chrome.tabs.sendMessage(route.tabId, { kind: "reader-capture-feedback", captureId: route.captureId, state, transferId, detail }, feedbackTarget(route));
    return true;
  } catch { return false; } // Closed/navigated pages must not affect delivery.
}

async function prepareSelectionFeedback(tabId: number | undefined, frameId: number, captureId: string): Promise<CaptureFeedbackRoute | undefined> {
  if (tabId === undefined) return undefined;
  try {
    // A context-menu invocation grants activeTab but does not load a content
    // script on generic articles. Install its idempotent listener before use.
    const [injected] = await chrome.scripting.executeScript({ target: { tabId, frameIds: [frameId] }, files: ["content.js"] });
    const route = { tabId, frameId, captureId, documentId: injected?.documentId };
    await chrome.tabs.sendMessage(tabId, { kind: "reader-feedback-begin", captureId }, feedbackTarget(route));
    return route;
  } catch {
    // Cross-origin frames may not be injectable with activeTab. The capture is
    // still the exact selected text; use the visible main page for its notice.
    return frameId === 0 ? undefined : prepareSelectionFeedback(tabId, 0, captureId);
  }
}

chrome.contextMenus.onClicked.addListener(async (info, tab) => {
  if (info.menuItemId === "reader-options") { await chrome.runtime.openOptionsPage(); return; }
  if (info.menuItemId !== "reader-send-selection" || !info.selectionText) return;
  if (!info.selectionText.trim()) return;
  const captureId = randomHex(16);
  await captureBadge(tab?.id, CAPTURE_FEEDBACK_TEXT.saving, "…").catch(() => undefined);
  const route = await prepareSelectionFeedback(tab?.id, info.frameId ?? 0, captureId);
  try {
    const result = await queueCapture({ title: "Selection", markdown: escapePlainText(info.selectionText.trim()) + "\n", sourceUrl: info.frameUrl ?? info.pageUrl, sourceType: "selection" }, captureId, route);
    const state = result.queued ? "unpaired" : "waiting";
    const shown = await notifyCapture(route, state, result.transferId);
    await captureBadge(tab?.id, CAPTURE_FEEDBACK_TEXT[state], shown ? "" : "✓").catch(() => undefined);
  } catch {
    const detail = "Couldn’t save — storage unavailable or content too large. Try again.";
    await notifyCapture(route, "error", undefined, detail);
    await captureBadge(tab?.id, detail, "!").catch(() => undefined);
  }
});
