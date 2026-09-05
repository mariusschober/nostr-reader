import { sha256HexBytes } from "./core.js";

export const PAIRING_PROTOCOL = "reader-pair/2";
export const PAIRING_QR_TTL_SECS = 300;
export const PAIRING_MESSAGE_TTL_SECS = 600;
export const PAIRING_CAPABILITIES = [
  "durable-pair-response",
  "pair-ack-v2",
  "pair-complete-v2",
  "endpoint-ack-v2",
  "gzip",
] as const;

const HEX_16 = /^[0-9a-f]{32}$/;
const HEX_32 = /^[0-9a-f]{64}$/;
const VERSION = /^[0-9A-Za-z][0-9A-Za-z.+-]{0,31}$/;

export interface PairingRequestV2 {
  protocol: typeof PAIRING_PROTOCOL;
  sessionId: string;
  pairingPubkey: string;
  chromeDevicePubkey: string;
  nonce: string;
  relays: string[];
  relaySetDigest: string;
  createdAt: number;
  expiresAt: number;
  capabilities: string[];
}

export interface PairResponseV2 {
  protocol: typeof PAIRING_PROTOCOL;
  type: "pair-response";
  sessionId: string;
  nonce: string;
  recipientPairingPubkey: string;
  chromeDevicePubkey: string;
  androidChannelPubkey: string;
  relaySetDigest: string;
  appVersion: string;
  capabilities: string[];
  createdAt: number;
  expiresAt: number;
}

export interface PairAckV2 {
  protocol: typeof PAIRING_PROTOCOL;
  type: "pair-ack";
  sessionId: string;
  chromeDevicePubkey: string;
  androidChannelPubkey: string;
  relaySetDigest: string;
  acceptedRelays: string[];
  status: "accepted";
  createdAt: number;
  expiresAt: number;
}

export interface PairCompleteV2 {
  protocol: typeof PAIRING_PROTOCOL;
  type: "pair-complete";
  sessionId: string;
  chromeDevicePubkey: string;
  androidChannelPubkey: string;
  relaySetDigest: string;
  status: "connected";
  createdAt: number;
  expiresAt: number;
}

function record(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error("payload must be an object");
  return value as Record<string, unknown>;
}

function exactKeys(value: Record<string, unknown>, expected: readonly string[]): void {
  const actual = Object.keys(value).sort();
  const wanted = [...expected].sort();
  if (actual.length !== wanted.length || actual.some((key, index) => key !== wanted[index])) {
    throw new Error("payload fields do not match protocol");
  }
}

function stringField(value: Record<string, unknown>, key: string): string {
  const field = value[key];
  if (typeof field !== "string") throw new Error(`invalid ${key}`);
  return field;
}

function intField(value: Record<string, unknown>, key: string): number {
  const field = value[key];
  if (!Number.isSafeInteger(field)) throw new Error(`invalid ${key}`);
  return field as number;
}

function stringArray(value: Record<string, unknown>, key: string, max = 8): string[] {
  const field = value[key];
  if (!Array.isArray(field) || field.length < 1 || field.length > max || !field.every((item) => typeof item === "string")) {
    throw new Error(`invalid ${key}`);
  }
  if (new Set(field).size !== field.length) throw new Error(`duplicate ${key}`);
  return field as string[];
}

function assertHex(value: string, pattern: RegExp, label: string): void {
  if (!pattern.test(value)) throw new Error(`invalid ${label}`);
}

export function normalizePairingRelay(raw: string): string {
  if (raw.length < 1 || raw.length > 512 || /[\u0000-\u001f\u007f]/.test(raw)) throw new Error("invalid relay URL length");
  const parsed = new URL(raw);
  if (parsed.protocol !== "wss:") throw new Error("relay URL must use wss");
  if (parsed.username || parsed.password || parsed.search || parsed.hash) throw new Error("relay URL has forbidden components");
  const host = parsed.hostname.toLowerCase();
  if (!host || host.length > 253 || host.endsWith(".") || host === "localhost" || host.endsWith(".local")) {
    throw new Error("relay host is prohibited");
  }
  if (host.includes(":") || /^\d{1,3}(?:\.\d{1,3}){3}$/.test(host)) throw new Error("numeric relay hosts are prohibited");
  if (parsed.pathname.length > 128 || parsed.pathname.includes("%") || parsed.pathname.split("/").some((part) => part === "." || part === "..")) {
    throw new Error("relay path is ambiguous");
  }
  const port = parsed.port && parsed.port !== "443" ? `:${parsed.port}` : "";
  const path = parsed.pathname === "/" ? "" : parsed.pathname.replace(/\/$/, "");
  return `wss://${host}${port}${path}`;
}

export async function relaySetDigest(relays: readonly string[]): Promise<string> {
  return sha256HexBytes(new TextEncoder().encode(JSON.stringify(relays)));
}

export async function createPairingRequest(input: {
  sessionId: string;
  pairingPubkey: string;
  chromeDevicePubkey: string;
  nonce: string;
  relays: string[];
  nowSecs: number;
}): Promise<PairingRequestV2> {
  assertHex(input.sessionId, HEX_16, "sessionId");
  assertHex(input.pairingPubkey, HEX_32, "pairingPubkey");
  assertHex(input.chromeDevicePubkey, HEX_32, "chromeDevicePubkey");
  assertHex(input.nonce, HEX_32, "nonce");
  const relays = [...new Set(input.relays.map(normalizePairingRelay))];
  if (relays.length < 1 || relays.length > 8) throw new Error("pairing requires 1 to 8 unique relays");
  return {
    protocol: PAIRING_PROTOCOL,
    sessionId: input.sessionId,
    pairingPubkey: input.pairingPubkey,
    chromeDevicePubkey: input.chromeDevicePubkey,
    nonce: input.nonce,
    relays,
    relaySetDigest: await relaySetDigest(relays),
    createdAt: input.nowSecs,
    expiresAt: input.nowSecs + PAIRING_QR_TTL_SECS,
    capabilities: [...PAIRING_CAPABILITIES],
  };
}

export function validatePairResponse(
  value: unknown,
  request: PairingRequestV2,
  verifiedInnerSenderPubkey: string,
  nowSecs: number,
): PairResponseV2 {
  const payload = record(value);
  exactKeys(payload, [
    "protocol", "type", "sessionId", "nonce", "recipientPairingPubkey",
    "chromeDevicePubkey", "androidChannelPubkey", "relaySetDigest",
    "appVersion", "capabilities", "createdAt", "expiresAt",
  ]);
  if (payload["protocol"] !== PAIRING_PROTOCOL || payload["type"] !== "pair-response") throw new Error("wrong pairing message type");
  const response = payload as unknown as PairResponseV2;
  assertHex(stringField(payload, "sessionId"), HEX_16, "sessionId");
  assertHex(stringField(payload, "nonce"), HEX_32, "nonce");
  assertHex(stringField(payload, "recipientPairingPubkey"), HEX_32, "recipientPairingPubkey");
  assertHex(stringField(payload, "chromeDevicePubkey"), HEX_32, "chromeDevicePubkey");
  assertHex(stringField(payload, "androidChannelPubkey"), HEX_32, "androidChannelPubkey");
  assertHex(stringField(payload, "relaySetDigest"), HEX_32, "relaySetDigest");
  if (!VERSION.test(stringField(payload, "appVersion"))) throw new Error("invalid appVersion");
  const capabilities = stringArray(payload, "capabilities");
  if (PAIRING_CAPABILITIES.some((capability) => !capabilities.includes(capability))) {
    throw new Error("required pairing capabilities are missing");
  }
  const createdAt = intField(payload, "createdAt");
  const expiresAt = intField(payload, "expiresAt");
  if (createdAt < request.createdAt - 60 || createdAt > nowSecs + 60 || createdAt > request.expiresAt) throw new Error("response timestamp out of range");
  if (expiresAt <= nowSecs || expiresAt > createdAt + PAIRING_MESSAGE_TTL_SECS) throw new Error("response expired or overlong");
  if (response.sessionId !== request.sessionId || response.nonce !== request.nonce) throw new Error("pairing transcript mismatch");
  if (response.recipientPairingPubkey !== request.pairingPubkey || response.chromeDevicePubkey !== request.chromeDevicePubkey) {
    throw new Error("pairing recipient mismatch");
  }
  if (response.relaySetDigest !== request.relaySetDigest) throw new Error("relay set mismatch");
  if (verifiedInnerSenderPubkey !== response.androidChannelPubkey) throw new Error("pairing sender does not own channel key");
  return response;
}

export function createPairAck(
  request: PairingRequestV2,
  androidChannelPubkey: string,
  acceptedRelays: string[],
  nowSecs: number,
): PairAckV2 {
  assertHex(androidChannelPubkey, HEX_32, "androidChannelPubkey");
  const normalizedAccepted = [...new Set(acceptedRelays.map(normalizePairingRelay))];
  if (!normalizedAccepted.length || normalizedAccepted.some((relay) => !request.relays.includes(relay))) {
    throw new Error("ack relay set is not a subset of the request");
  }
  return {
    protocol: PAIRING_PROTOCOL,
    type: "pair-ack",
    sessionId: request.sessionId,
    chromeDevicePubkey: request.chromeDevicePubkey,
    androidChannelPubkey,
    relaySetDigest: request.relaySetDigest,
    acceptedRelays: normalizedAccepted,
    status: "accepted",
    createdAt: nowSecs,
    expiresAt: nowSecs + PAIRING_MESSAGE_TTL_SECS,
  };
}

export function validatePairComplete(
  value: unknown,
  request: PairingRequestV2,
  expectedAndroidChannelPubkey: string,
  verifiedInnerSenderPubkey: string,
  nowSecs: number,
): PairCompleteV2 {
  const payload = record(value);
  exactKeys(payload, [
    "protocol", "type", "sessionId", "chromeDevicePubkey", "androidChannelPubkey",
    "relaySetDigest", "status", "createdAt", "expiresAt",
  ]);
  if (payload["protocol"] !== PAIRING_PROTOCOL || payload["type"] !== "pair-complete" || payload["status"] !== "connected") {
    throw new Error("wrong pairing completion type");
  }
  const completion = payload as unknown as PairCompleteV2;
  const createdAt = intField(payload, "createdAt");
  const expiresAt = intField(payload, "expiresAt");
  if (
    createdAt < request.createdAt - 60 || createdAt > nowSecs + 60 ||
    expiresAt <= nowSecs || expiresAt > createdAt + PAIRING_MESSAGE_TTL_SECS
  ) {
    throw new Error("completion expired or timestamp invalid");
  }
  if (
    completion.sessionId !== request.sessionId ||
    completion.chromeDevicePubkey !== request.chromeDevicePubkey ||
    completion.androidChannelPubkey !== expectedAndroidChannelPubkey ||
    completion.relaySetDigest !== request.relaySetDigest
  ) throw new Error("pairing completion transcript mismatch");
  if (verifiedInnerSenderPubkey !== expectedAndroidChannelPubkey) throw new Error("untrusted pairing completion sender");
  return completion;
}
