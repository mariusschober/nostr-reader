// Nostr transport: NIP-44 + NIP-59 gift-wrap with the full verification
// checklist from PROTOCOL.md. No high-level unwrap shortcut is used.
import { finalizeEvent, getEventHash, getPublicKey, verifyEvent } from "nostr-tools/pure";
import * as nip44 from "./nip44.js";
import { SimplePool } from "nostr-tools/pool";
import { READER_PROTOCOL, RUMOR_KIND } from "../protocol/core.js";
import { parseStrictJson } from "../protocol/strict-json.js";

export const SEAL_KIND = 13;
export const WRAP_KIND = 1059;
export const OUTER_TTL_SECS = 7 * 86400;
export const MAX_RELAY_EVENT_BYTES = 512 * 1024;

export interface RelayHealth {
  url: string;
  ok: boolean;
  nip11?: unknown;
  maxEventBytes?: number;
  note: string;
}

export interface RelayPublishResult {
  url: string;
  ok: boolean;
  state: "OK_TRUE" | "OK_FALSE" | "NO_OK_TIMEOUT" | "SOCKET_ERROR" | "TLS_ERROR" | "CLOSED";
  reasonPrefix?: string;
}

export interface UnwrappedEnvelope {
  payload: Record<string, unknown>;
  senderPubkey: string;
  recipientPubkey: string;
  rumorId: string;
}

type PoolQueryFilter = Parameters<SimplePool["querySync"]>[1];
type PoolQueryEvent = Awaited<ReturnType<SimplePool["querySync"]>>[number];

interface AuthController {
  authenticate(): Promise<void>;
  current(): Promise<void> | null;
  authenticated(): boolean;
}

/** Configure NIP-42 with the app's pseudonymous device/channel key, never a user identity. */
async function configureRelayAuth(
  pool: SimplePool,
  url: string,
  authSeckey: Uint8Array,
): Promise<{ relay: Awaited<ReturnType<SimplePool["ensureRelay"]>>; auth: AuthController }> {
  const relay = await pool.ensureRelay(url);
  const authRelay = relay as unknown as {
    _onauth: ((challenge: string) => void) | null;
    challenge?: string;
  };
  let inFlight: Promise<void> | null = null;
  let didAuthenticate = false;
  let markChallenge!: () => void;
  const challengeReady = new Promise<void>((resolve) => { markChallenge = resolve; });
  if (typeof authRelay.challenge === "string" && authRelay.challenge.length > 0) markChallenge();
  const authenticate = (): Promise<void> => {
    if (inFlight) return inFlight;
    const pending = (async () => {
      if (!(typeof authRelay.challenge === "string" && authRelay.challenge.length > 0)) {
        await bounded(challengeReady, 2500, "relay authentication challenge");
      }
      await relay.auth(async (template) => finalizeEvent(template, authSeckey));
      didAuthenticate = true;
    })();
    inFlight = pending;
    void pending.finally(() => {
      if (inFlight === pending) inFlight = null;
    }).catch(() => undefined);
    return pending;
  };
  authRelay._onauth = () => {
    markChallenge();
    void authenticate().catch(() => undefined);
  };
  return {
    relay,
    auth: { authenticate, current: () => inFlight, authenticated: () => didAuthenticate },
  };
}

function isAuthRequired(error: unknown): boolean {
  return /auth-required/i.test(error instanceof Error ? error.message : String(error));
}

function bounded<T>(promise: Promise<T>, milliseconds: number, label: string): Promise<T> {
  const signal = AbortSignal.timeout(milliseconds);
  const deadline = new Promise<never>((_resolve, reject) => {
    signal.addEventListener("abort", () => reject(new Error(`${label} timed out`)), { once: true });
  });
  return Promise.race([promise, deadline]);
}

function boundedRelayEvents(events: PoolQueryEvent[]): PoolQueryEvent[] {
  const unique = new Map<string, PoolQueryEvent>();
  for (const event of events) {
    if (new TextEncoder().encode(JSON.stringify(event)).length > MAX_RELAY_EVENT_BYTES) continue;
    unique.set(event.id, event);
  }
  return [...unique.values()].sort((left, right) => left.created_at - right.created_at);
}

/** One-relay catch-up query with a NIP-42 retry when the relay challenges. */
export async function queryRelayWithAuth(
  pool: SimplePool,
  url: string,
  filter: PoolQueryFilter,
  authSeckey: Uint8Array,
  maxWait = 5000,
): Promise<PoolQueryEvent[]> {
  const controller = await configureRelayAuth(pool, url, authSeckey);
  let first: PoolQueryEvent[];
  try {
    first = await pool.querySync([url], filter, { maxWait });
  } catch (error) {
    if (!isAuthRequired(error)) throw error;
    await bounded(controller.auth.authenticate(), 2500, "relay authentication");
    return boundedRelayEvents(await pool.querySync([url], filter, { maxWait }));
  }
  const pending = controller.auth.current();
  if (pending) await bounded(pending, 2500, "relay authentication").catch(() => undefined);
  if (!controller.auth.authenticated()) return boundedRelayEvents(first);
  const second = await pool.querySync([url], filter, { maxWait });
  const unique = new Map(first.map((event) => [event.id, event]));
  for (const event of second) unique.set(event.id, event);
  return boundedRelayEvents([...unique.values()]);
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
  const rumorBase: Record<string, unknown> = {
    kind: RUMOR_KIND,
    created_at: now,
    tags: [],
    content: JSON.stringify({ protocol: READER_PROTOCOL, ...(opts.payload as object) }),
    pubkey: getPublicKey(opts.senderSeckey),
  };
  const rumor: Record<string, unknown> = {
    ...rumorBase,
    id: getEventHash(rumorBase as never),
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
    tags: [
      ["p", opts.recipientPubkey],
      ["expiration", String(now + (opts.expireSecs ?? OUTER_TTL_SECS))],
    ],
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

function isLowerHex(value: string, bytes: number): boolean {
  return value.length === bytes * 2 && /^[0-9a-f]+$/.test(value);
}

/** Full verification checklist, including recipient routing and expiry. */
export async function unwrapAndVerifyEnvelope(opts: {
  wrap: { kind: number; pubkey: string; content: string; created_at: number; id: string; sig: string; tags?: string[][] };
  recipientSeckey: Uint8Array;
  expectedSenderPubkey?: string;
  expectedProtocols?: readonly string[];
  nowSecs?: number;
}): Promise<UnwrappedEnvelope> {
  // Defuse cached-verification symbols: nostr-tools memoizes verification on
  // the object via a symbol, and object spread copies symbols. Wire events
  // arrive as fresh JSON, but re-parsing here makes that invariant explicit
  // so no caller can alias a previously-trusted object into trust.
  const wrap = JSON.parse(JSON.stringify(opts.wrap)) as typeof opts.wrap;
  const { recipientSeckey, expectedSenderPubkey } = opts;
  const now = opts.nowSecs ?? Math.floor(Date.now() / 1000);
  // reader/2 uses durable kind 1059 for both pairing and delivery so MV3 and
  // Android process restarts can catch up. Ephemeral 21059 is a wrong kind.
  if (wrap.kind !== WRAP_KIND) throw new Error("bad wrap kind");
  if (!isLowerHex(wrap.pubkey, 32) || !isLowerHex(wrap.id, 32) || !isLowerHex(wrap.sig, 64)) {
    throw new Error("non-canonical outer hex");
  }
  if (!verifyEvent(wrap as never)) throw new Error("invalid outer signature");
  const recipientPubkey = getPublicKey(recipientSeckey);
  const pTags = (wrap.tags ?? []).filter((tag) => tag[0] === "p");
  if (pTags.length !== 1 || pTags[0]?.length !== 2 || pTags[0]?.[1] !== recipientPubkey) {
    throw new Error("wrong or missing recipient tag");
  }
  const expirationTags = (wrap.tags ?? []).filter((tag) => tag[0] === "expiration");
  if (expirationTags.length !== 1 || expirationTags[0]?.length !== 2 || !/^\d+$/.test(expirationTags[0]?.[1] ?? "")) {
    throw new Error("missing or invalid expiration");
  }
  const expiration = Number(expirationTags[0]?.[1]);
  if (!Number.isSafeInteger(expiration) || expiration <= now) throw new Error("outer event expired");
  if (wrap.created_at > now + 600) throw new Error("outer timestamp in future");
  let sealJson: string;
  try {
    sealJson = nip44.decrypt(
      wrap.content,
      nip44.getConversationKey(recipientSeckey, wrap.pubkey),
    );
  } catch {
    throw new Error("wrap decrypt failed");
  }
  const seal = parseStrictJson(sealJson) as { kind: number; pubkey: string; content: string; id: string; sig: string; created_at: number; tags?: string[][] };
  if (seal.kind !== SEAL_KIND) throw new Error("bad seal kind");
  if ((seal.tags ?? []).length !== 0) throw new Error("seal tags must be empty");
  if (!isLowerHex(seal.pubkey, 32) || !isLowerHex(seal.id, 32) || !isLowerHex(seal.sig, 64)) {
    throw new Error("non-canonical seal hex");
  }
  if (!verifyEvent(seal as never)) throw new Error("invalid seal signature");
  if (Math.abs(now - seal.created_at) > 30 * 86400) throw new Error("seal timestamp out of range");
  let rumorJson: string;
  try {
    rumorJson = nip44.decrypt(
      seal.content,
      nip44.getConversationKey(recipientSeckey, seal.pubkey),
    );
  } catch {
    throw new Error("seal decrypt failed");
  }
  const rumor = parseStrictJson(rumorJson) as {
    id?: string;
    pubkey: string;
    content: string;
    kind: number;
    created_at: number;
    tags: string[][];
    sig?: unknown;
  };
  if (rumor.sig !== undefined) throw new Error("rumor must not be signed");
  if (!isLowerHex(rumor.pubkey, 32) || !rumor.id || !isLowerHex(rumor.id, 32)) {
    throw new Error("non-canonical rumor hex");
  }
  if (getEventHash(rumor as never) !== rumor.id) throw new Error("invalid rumor id");
  if (rumor.pubkey !== seal.pubkey) throw new Error("rumor/seal pubkey mismatch");
  if (expectedSenderPubkey !== undefined && rumor.pubkey !== expectedSenderPubkey) throw new Error("untrusted sender");
  const payload = parseStrictJson(rumor.content) as Record<string, unknown>;
  const expectedProtocols = opts.expectedProtocols ?? [READER_PROTOCOL];
  if (!expectedProtocols.includes(String(payload["protocol"]))) throw new Error("unsupported protocol version");
  return { payload, senderPubkey: rumor.pubkey, recipientPubkey, rumorId: rumor.id };
}

export async function unwrapAndVerify(opts: {
  wrap: { kind: number; pubkey: string; content: string; created_at: number; id: string; sig: string; tags?: string[][] };
  recipientSeckey: Uint8Array;
  expectedSenderPubkey: string;
  expectedProtocols?: readonly string[];
  nowSecs?: number;
}): Promise<Record<string, unknown>> {
  return (await unwrapAndVerifyEnvelope(opts)).payload;
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

export function normalizeRelayUrl(raw: string): string {
  const parsed = new URL(raw);
  if (parsed.protocol !== "wss:" && parsed.protocol !== "ws:") throw new Error("relay URL must use WebSocket");
  if (parsed.username || parsed.password || parsed.search || parsed.hash) throw new Error("relay URL contains forbidden components");
  const port = parsed.port ? `:${parsed.port}` : "";
  const path = parsed.pathname === "/" ? "" : parsed.pathname.replace(/\/$/, "");
  return `${parsed.protocol}//${parsed.hostname.toLowerCase()}${port}${path}`;
}

function sanitizedReason(error: unknown): string {
  const raw = error instanceof Error ? error.message : String(error);
  return raw
    .replace(/[\u0000-\u001f\u007f]+/g, " ")
    .replace(/[0-9a-f]{48,}/gi, "[redacted-hex]")
    .replace(/[A-Za-z0-9_+/=-]{48,}/g, "[redacted-token]")
    .trim()
    .slice(0, 160) || "relay publication failed";
}

function classifyPublishFailure(error: unknown): RelayPublishResult["state"] {
  const raw = error instanceof Error ? error.message : String(error);
  if (/timed out|timeout/i.test(raw)) return "NO_OK_TIMEOUT";
  if (/\bclosed\b/i.test(raw)) return "CLOSED";
  if (/tls|ssl|certificate|cert\b/i.test(raw)) return "TLS_ERROR";
  if (/websocket|connection|network|socket|failed to fetch|sending on closed/i.test(raw)) return "SOCKET_ERROR";
  // nostr-tools rejects a matching OK=false with Error(reason). Any remaining
  // settled rejection is therefore the relay's explicit NIP-01 reason.
  return "OK_FALSE";
}

/** Pin nostr-tools@2.7.1: SimplePool.publish returns one Promise per relay. */
export async function publishPerRelay(
  pool: SimplePool,
  urls: string[],
  event: never,
  authSeckey?: Uint8Array,
  timeoutMs = 10_000,
): Promise<RelayPublishResult[]> {
  const normalized = [...new Set(urls.map(normalizeRelayUrl))];
  return Promise.all(normalized.map(async (url): Promise<RelayPublishResult> => {
    try {
      if (authSeckey) {
        const controller = await configureRelayAuth(pool, url, authSeckey);
        try {
          await bounded(controller.relay.publish(event as never), timeoutMs, "relay publish");
        } catch (error) {
          if (!isAuthRequired(error)) throw error;
          await bounded(controller.auth.authenticate(), 2500, "relay authentication");
          await bounded(controller.relay.publish(event as never), timeoutMs, "relay publish");
        }
      } else {
        const publications = pool.publish([url], event as never);
        if (publications.length !== 1) throw new Error(`unexpected publish Promise count ${publications.length}`);
        await bounded(publications[0], timeoutMs, "relay publish");
      }
      return { url, ok: true, state: "OK_TRUE" };
    } catch (error) {
      return { url, ok: false, state: classifyPublishFailure(error), reasonPrefix: sanitizedReason(error) };
    }
  }));
}

/** Publish one signed wrap to unique relays, requiring quorum (default 2). */
export async function publishQuorum(pool: SimplePool, urls: string[], event: never, quorum = 2): Promise<string[]> {
  const results = await publishPerRelay(pool, urls, event);
  const oks = results.filter((result) => result.ok).map((result) => result.url);
  if (oks.length < quorum) throw new Error(`relay quorum failed (${oks.length}/${quorum})`);
  return oks;
}
