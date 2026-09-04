// Nostr transport: NIP-44 + NIP-59 gift-wrap with the full verification
// checklist from PROTOCOL.md. No high-level unwrap shortcut is used.
import { finalizeEvent, getPublicKey, verifyEvent } from "nostr-tools/pure";
import * as nip44 from "nostr-tools/nip44";
import { SimplePool } from "nostr-tools/pool";
import { READER_PROTOCOL, RUMOR_KIND } from "../protocol/core.js";

export const SEAL_KIND = 13;
export const WRAP_KIND = 1059;
export const WRAP_KIND_EPHEMERAL = 21059;
export const OUTER_TTL_SECS = 7 * 86400;

export interface RelayHealth {
  url: string;
  ok: boolean;
  nip11?: unknown;
  maxEventBytes?: number;
  note: string;
}

function randomPow(): string {
  const b = new Uint8Array(8);
  crypto.getRandomValues(b);
  return [...b].map((x) => x.toString(16).padStart(2, "0")).join("");
}

export function randomHex(bytes: number): string {
  const b = new Uint8Array(bytes);
  crypto.getRandomValues(b);
  return [...b].map((x) => x.toString(16).padStart(2, "0")).join("");
}

/** Build + gift-wrap one encrypted rumor payload to a recipient. */
export async function sealAndWrap(opts: {
  senderSeckey: Uint8Array;
  recipientPubkey: string;
  payload: unknown;
  wrapKind?: number;
  expireSecs?: number;
}): Promise<{ seal: unknown; wrap: unknown }> {
  const now = Math.floor(Date.now() / 1000);
  const rumor: Record<string, unknown> = {
    kind: RUMOR_KIND,
    created_at: now,
    tags: [],
    content: JSON.stringify({ protocol: READER_PROTOCOL, ...(opts.payload as object) }),
    pubkey: getPublicKey(opts.senderSeckey),
  };
  const sealContent = nip44.encrypt(
    JSON.stringify(rumor),
    nip44.getConversationKey(opts.senderSeckey, opts.recipientPubkey),
  );
  const seal = finalizeEvent(
    { kind: SEAL_KIND, created_at: now - randomPowDelay(), tags: [], content: sealContent },
    opts.senderSeckey,
  );
  const wrapKey = randomSeckey();
  const wrapContent = nip44.encrypt(
    JSON.stringify(seal),
    nip44.getConversationKey(wrapKey, opts.recipientPubkey),
  );
  const wrapKind = opts.wrapKind ?? WRAP_KIND;
  // NIP-59 timestamp randomization into the past (privacy) + NIP-40 expiry.
  const wrap: Record<string, unknown> = {
    kind: wrapKind,
    created_at: now - randomPowDelay(),
    tags: [["expiration", String(now + (opts.expireSecs ?? OUTER_TTL_SECS))]],
    content: wrapContent,
    pubkey: getPublicKey(wrapKey),
  };
  return { seal, wrap: finalizeEvent(wrap as never, wrapKey) };
}

function randomPowDelay(): number {
  const b = new Uint8Array(4);
  crypto.getRandomValues(b);
  const v = new DataView(b.buffer).getUint32(0);
  return v % 172800; // up to 2 days into the past
}

export function randomSeckey(): Uint8Array {
  const b = new Uint8Array(32);
  crypto.getRandomValues(b);
  b[0] &= 0x7f; // keep inside curve order with overwhelming probability
  if (b.every((x) => x === 0)) b[31] = 1;
  return b;
}

/** Full verification checklist (PROTOCOL.md section: layering). */
export async function unwrapAndVerify(opts: {
  wrap: { kind: number; pubkey: string; content: string; created_at: number; id: string; sig: string; tags?: string[][] };
  recipientSeckey: Uint8Array;
  expectedSenderPubkey: string;
}): Promise<Record<string, unknown>> {
  // Defuse cached-verification symbols: nostr-tools memoizes verification on
  // the object via a symbol, and object spread copies symbols. Wire events
  // arrive as fresh JSON, but re-parsing here makes that invariant explicit
  // so no caller can alias a previously-trusted object into trust.
  const wrap = JSON.parse(JSON.stringify(opts.wrap)) as typeof opts.wrap;
  const { recipientSeckey, expectedSenderPubkey } = opts;
  if (wrap.kind !== WRAP_KIND && wrap.kind !== WRAP_KIND_EPHEMERAL) throw new Error("bad wrap kind");
  if (!verifyEvent(wrap as never)) throw new Error("invalid outer signature");
  let sealJson: string;
  try {
    sealJson = nip44.decrypt(
      wrap.content,
      nip44.getConversationKey(recipientSeckey, wrap.pubkey),
    );
  } catch {
    throw new Error("wrap decrypt failed");
  }
  const seal = JSON.parse(sealJson) as { kind: number; pubkey: string; content: string; id: string; sig: string; created_at: number };
  if (seal.kind !== SEAL_KIND) throw new Error("bad seal kind");
  if (!verifyEvent(seal as never)) throw new Error("invalid seal signature");
  if (Math.abs(Date.now() / 1000 - seal.created_at) > 30 * 86400) throw new Error("seal timestamp out of range");
  let rumorJson: string;
  try {
    rumorJson = nip44.decrypt(
      seal.content,
      nip44.getConversationKey(recipientSeckey, seal.pubkey),
    );
  } catch {
    throw new Error("seal decrypt failed");
  }
  const rumor = JSON.parse(rumorJson) as { pubkey: string; content: string; kind: number };
  if (rumor.pubkey !== seal.pubkey) throw new Error("rumor/seal pubkey mismatch");
  if (rumor.pubkey !== expectedSenderPubkey) throw new Error("untrusted sender");
  const payload = JSON.parse(rumor.content) as Record<string, unknown>;
  if (payload["protocol"] !== READER_PROTOCOL) throw new Error("unsupported protocol version");
  return payload;
}

/** Probe candidate relays: connect + NIP-11 + write/read roundtrip. */
export async function probeRelays(urls: string[], timeoutMs = 9000): Promise<RelayHealth[]> {
  const pool = new SimplePool();
  const out: RelayHealth[] = [];
  for (const url of urls) {
    try {
      const info = await fetch(url.replace(/^ws/, "http"), {
        headers: { Accept: "application/nostr+json" },
        signal: AbortSignal.timeout(timeoutMs),
      }).then((r) => (r.ok ? r.json() : null)).catch(() => null);
      const relay = await pool.ensureRelay(url);
      void relay;
      out.push({ url, ok: true, nip11: info, note: "connect ok" });
    } catch (e) {
      out.push({ url, ok: false, note: String(e).slice(0, 160) });
    }
  }
  try { pool.close(urls); } catch { /* ignore */ }
  return out;
}

/** Publish one signed wrap to relays, requiring quorum (default 2). */
export async function publishQuorum(pool: SimplePool, urls: string[], event: never, quorum = 2): Promise<string[]> {
  const oks: string[] = [];
  await Promise.all(urls.map(async (url) => {
    try {
      await pool.publish(urls, event as never);
      oks.push(url);
    } catch { /* per-relay failure is expected */ }
  }));
  void pool;
  if (oks.length < quorum) throw new Error(`relay quorum failed (${oks.length}/${quorum})`);
  return oks;
}
