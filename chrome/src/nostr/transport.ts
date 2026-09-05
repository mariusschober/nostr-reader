// Nostr transport: NIP-44 + NIP-59 gift-wrap with the full verification
// checklist from PROTOCOL.md. No high-level unwrap shortcut is used.
import { generateSecretKey, finalizeEvent, getEventHash, getPublicKey, verifyEvent } from "nostr-tools/pure";
import * as nip44 from "./nip44.js";
import { SimplePool } from "nostr-tools/pool";
import { READER_PROTOCOL, RUMOR_KIND } from "../protocol/core.js";
import { parseStrictJson } from "../protocol/strict-json.js";

export const SEAL_KIND = 13;
export const WRAP_KIND = 1059;
export const OUTER_TTL_SECS = 7 * 86400;
export const MAX_RELAY_EVENT_BYTES = 512 * 1024;

export interface RelayPublishResult {
  url: string;
  ok: boolean;
  state: "OK_TRUE" | "OK_FALSE" | "NO_OK_TIMEOUT" | "SOCKET_ERROR" | "TLS_ERROR" | "CLOSED" | "AUTH_ERROR" | "PROTOCOL_ERROR";
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
  challenges(): readonly string[];
  failure(): ClientProtocolError | undefined;
}

class ClientProtocolError extends Error {}
class RelayAuthError extends Error {}

/** Independently constrain the exact NIP-42 template before any key signs it. */
export function validateAuthTemplate(
  template: Record<string, unknown>,
  relayUrl: string,
  expectedChallenge: string,
  nowSecs = Math.floor(Date.now() / 1000),
): void {
  const keys = Object.keys(template).sort();
  if (keys.join(",") !== "content,created_at,kind,tags") {
    throw new ClientProtocolError("relay authentication template fields are invalid");
  }
  if (template["kind"] !== 22242 || template["content"] !== "") {
    throw new ClientProtocolError("relay authentication template kind or content is invalid");
  }
  const createdAt = template["created_at"];
  if (!Number.isSafeInteger(createdAt) || Math.abs(nowSecs - Number(createdAt)) > 600) {
    throw new ClientProtocolError("relay authentication timestamp is out of range");
  }
  const challengeBytes = new TextEncoder().encode(expectedChallenge).length;
  if (challengeBytes < 1 || challengeBytes > 512) {
    throw new ClientProtocolError("relay authentication challenge is invalid");
  }
  const tags = template["tags"];
  if (
    !Array.isArray(tags) || tags.length !== 2 ||
    !tags.every((tag) => Array.isArray(tag) && tag.length === 2 && tag.every((part) => typeof part === "string"))
  ) {
    throw new ClientProtocolError("relay authentication tags are invalid");
  }
  const relayTags = tags.filter((tag) => tag[0] === "relay");
  const challengeTags = tags.filter((tag) => tag[0] === "challenge");
  let relayBindingMatches = false;
  try {
    relayBindingMatches = normalizeRelayUrl(String(relayTags[0]?.[1])) === normalizeRelayUrl(relayUrl);
  } catch {
    throw new ClientProtocolError("relay authentication binding mismatch");
  }
  if (
    relayTags.length !== 1 || challengeTags.length !== 1 ||
    !relayBindingMatches ||
    challengeTags[0]?.[1] !== expectedChallenge
  ) {
    throw new ClientProtocolError("relay authentication binding mismatch");
  }
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
  let latest: Promise<void> | null = null;
  let didAuthenticate = false;
  let selectedChallenge = typeof authRelay.challenge === "string" ? authRelay.challenge : undefined;
  const sensitiveChallenges = selectedChallenge === undefined ? [] : [selectedChallenge];
  let protocolFailure: ClientProtocolError | undefined;
  let markChallenge!: () => void;
  const challengeReady = new Promise<void>((resolve) => { markChallenge = resolve; });
  if (selectedChallenge !== undefined) markChallenge();
  relay.onnotice = (message) => {
    console.debug(`NOTICE from ${normalizeRelayUrl(url)}: ${sanitizedReason(message, sensitiveChallenges)}`);
  };
  const authenticate = (): Promise<void> => {
    if (protocolFailure) return Promise.reject(protocolFailure);
    // One Reader operation signs at most one challenge on one connection.
    // A duplicate/late AUTH frame reuses the original result instead of
    // becoming an attacker-controlled signing loop.
    if (latest) return latest;
    const pending = (async () => {
      if (selectedChallenge === undefined && typeof authRelay.challenge === "string") {
        selectedChallenge = authRelay.challenge;
        sensitiveChallenges.push(selectedChallenge);
        markChallenge();
      }
      if (selectedChallenge === undefined) {
        await bounded(challengeReady, 2500, "relay authentication challenge");
      }
      const challenge = selectedChallenge;
      if (typeof challenge !== "string") throw new ClientProtocolError("relay authentication challenge is unavailable");
      try {
        await relay.auth(async (template) => {
          validateAuthTemplate(template as unknown as Record<string, unknown>, url, challenge);
          return finalizeEvent(template, authSeckey);
        });
      } catch (error) {
        if (error instanceof ClientProtocolError) throw error;
        throw new RelayAuthError(sanitizedReason(error, [challenge]));
      }
      if (protocolFailure) throw protocolFailure;
      didAuthenticate = true;
    })();
    inFlight = pending;
    latest = pending;
    void pending.finally(() => {
      if (inFlight === pending) inFlight = null;
    }).catch(() => undefined);
    return pending;
  };
  authRelay._onauth = (challenge) => {
    if (selectedChallenge === undefined) {
      selectedChallenge = challenge;
      sensitiveChallenges.push(challenge);
      markChallenge();
    } else if (challenge !== selectedChallenge) {
      if (!sensitiveChallenges.includes(challenge)) sensitiveChallenges.push(challenge);
      protocolFailure ??= new ClientProtocolError("relay authentication challenge changed during one operation");
      try { relay.close(); } catch { /* test doubles need not implement close */ }
      return;
    }
    void authenticate().catch(() => undefined);
  };
  return {
    relay,
    auth: {
      authenticate,
      current: () => inFlight ?? latest,
      authenticated: () => didAuthenticate,
      challenges: () => [...sensitiveChallenges],
      failure: () => protocolFailure,
    },
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

/** Pinned nostr-tools 2.7.1 socket adapter: bound frames BEFORE its queue,
 * JSON.parse, signature verification and query accumulation. Fail closed if the
 * pinned hook disappears. One query owner per relay connection is required.
 */
async function boundedQuery(
  relay: Awaited<ReturnType<SimplePool["ensureRelay"]>>,
  filter: PoolQueryFilter,
  maxWait: number,
): Promise<PoolQueryEvent[]> {
  const socket = (relay as unknown as { ws?: WebSocket }).ws;
  if (!socket || typeof socket.onmessage !== "function") throw new ClientProtocolError("relay receive adapter unavailable");
  const original = socket.onmessage;
  const id = `reader-${randomHex(8)}`;
  let bytes = 0;
  let frames = 0;
  let receivedEose = false;
  let subscription: ReturnType<typeof relay.subscribe> | undefined;
  let timer: ReturnType<typeof setTimeout> | undefined;
  try {
    return await new Promise<PoolQueryEvent[]>((resolve, reject) => {
      const events: PoolQueryEvent[] = [];
      socket.onmessage = event => {
        try {
          if (typeof event.data !== "string") throw new ClientProtocolError("binary relay frame");
          const size = new TextEncoder().encode(event.data).length;
          bytes += size;
          if (size > MAX_RELAY_EVENT_BYTES || bytes > 8 * 1024 * 1024 || ++frames > 4100) {
            throw new ClientProtocolError("relay receive budget exceeded");
          }
          const frame = parseStrictJson(event.data);
          if (!Array.isArray(frame)) throw new ClientProtocolError("invalid relay frame");
          if (frame[0] === "EOSE" && frame[1] === id) receivedEose = true;
          if (frame[0] === "EVENT") {
            const tags = frame[2]?.tags;
            if (!Array.isArray(tags) || tags.length > 16 || tags.some((tag: unknown) => !Array.isArray(tag) || tag.length > 4)) {
              throw new ClientProtocolError("relay tag budget exceeded");
            }
          }
          original.call(socket, event);
        } catch (error) {
          reject(error);
        }
      };
      timer = setTimeout(() => reject(new Error("relay query timeout")), maxWait + 100);
      subscription = relay.prepareSubscription([filter], {
        id, eoseTimeout: maxWait,
        onevent: event => { events.push(event); },
        oneose: () => receivedEose ? resolve(events) : reject(new Error("relay query timeout before EOSE")),
        onclose: reason => reject(new Error(reason || "relay subscription closed")),
      });
      subscription.fire();
    });
  } finally {
    if (timer) clearTimeout(timer);
    socket.onmessage = original;
    subscription?.close();
  }
}

/** One-relay bounded catch-up with one NIP-42 authentication retry. */
export async function queryRelayWithAuth(
  pool: SimplePool,
  url: string,
  filter: PoolQueryFilter,
  authSeckey: Uint8Array,
  maxWait = 5000,
): Promise<PoolQueryEvent[]> {
  const controller = await configureRelayAuth(pool, url, authSeckey);
  try {
    let events: PoolQueryEvent[];
    try {
      events = await boundedQuery(controller.relay, filter, maxWait);
    } catch (error) {
      if (!isAuthRequired(error)) throw error;
      await bounded(controller.auth.authenticate(), 2500, "relay authentication");
      events = await boundedQuery(controller.relay, filter, maxWait);
    }
    const pending = controller.auth.current();
    if (pending) await bounded(pending, 2500, "relay authentication");
    const failure = controller.auth.failure();
    if (failure) throw failure;
    return boundedRelayEvents(events);
  } catch (error) {
    throw new Error(sanitizedReason(controller.auth.failure() ?? error, controller.auth.challenges()));
  }
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
  return generateSecretKey();
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
  if (expectedSenderPubkey && seal.pubkey !== expectedSenderPubkey) throw new Error("untrusted sender (seal)");
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
  if (rumor.kind !== RUMOR_KIND || !Number.isSafeInteger(rumor.created_at) ||
      rumor.created_at < 0 || rumor.created_at > now + 600 ||
      !Array.isArray(rumor.tags) || rumor.tags.length !== 0 || typeof rumor.content !== "string") {
    throw new Error("invalid Reader rumor profile");
  }
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

export function normalizeRelayUrl(raw: string): string {
  const parsed = new URL(raw);
  if (parsed.protocol !== "wss:" && parsed.protocol !== "ws:") throw new Error("relay URL must use WebSocket");
  if (parsed.username || parsed.password || parsed.search || parsed.hash) throw new Error("relay URL contains forbidden components");
  const port = parsed.port ? `:${parsed.port}` : "";
  const path = parsed.pathname === "/" ? "" : parsed.pathname.replace(/\/$/, "");
  return `${parsed.protocol}//${parsed.hostname.toLowerCase()}${port}${path}`;
}

function sanitizedReason(error: unknown, sensitiveValues: readonly string[] = []): string {
  const raw = error instanceof Error ? error.message : String(error);
  const withoutChallenge = [...new Set(sensitiveValues)]
    .filter((sensitive) => sensitive.length > 0)
    .sort((left, right) => right.length - left.length)
    .reduce((redacted, sensitive) => redacted.split(sensitive).join("[redacted-challenge]"), raw);
  return withoutChallenge
    .replace(/[\u0000-\u001f\u007f]+/g, " ")
    .replace(/[0-9a-f]{48,}/gi, "[redacted-hex]")
    .replace(/[A-Za-z0-9_+/=-]{48,}/g, "[redacted-token]")
    .trim()
    .slice(0, 160) || "relay publication failed";
}

function classifyPublishFailure(error: unknown): RelayPublishResult["state"] {
  if (error instanceof ClientProtocolError) return "PROTOCOL_ERROR";
  if (error instanceof RelayAuthError) return "AUTH_ERROR";
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
    let auth: AuthController | undefined;
    try {
      if (authSeckey) {
        const controller = await configureRelayAuth(pool, url, authSeckey);
        auth = controller.auth;
        try {
          await bounded(controller.relay.publish(event as never), timeoutMs, "relay publish");
          const failure = controller.auth.failure();
          if (failure) throw failure;
        } catch (error) {
          if (!isAuthRequired(error)) throw error;
          await bounded(controller.auth.authenticate(), 2500, "relay authentication");
          await bounded(controller.relay.publish(event as never), timeoutMs, "relay publish");
          const failure = controller.auth.failure();
          if (failure) throw failure;
        }
      } else {
        const publications = pool.publish([url], event as never);
        if (publications.length !== 1) {
          throw new ClientProtocolError(`unexpected publish Promise count ${publications.length}`);
        }
        await bounded(publications[0], timeoutMs, "relay publish");
      }
      return { url, ok: true, state: "OK_TRUE" };
    } catch (error) {
      const surfaced = auth?.failure() ?? error;
      return {
        url,
        ok: false,
        state: classifyPublishFailure(surfaced),
        reasonPrefix: sanitizedReason(surfaced, auth?.challenges()),
      };
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
