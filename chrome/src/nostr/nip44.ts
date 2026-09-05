import { chacha20 } from "@noble/ciphers/chacha";
import { equalBytes } from "@noble/ciphers/utils";
import { secp256k1 } from "@noble/curves/secp256k1";
import { expand as hkdfExpand, extract as hkdfExtract } from "@noble/hashes/hkdf";
import { hmac } from "@noble/hashes/hmac";
import { sha256 } from "@noble/hashes/sha256";
import { concatBytes } from "@noble/hashes/utils";
import { base64 } from "@scure/base";

/** Current NIP-44 v2, including the six-byte extended length prefix. */
export const NIP44_MAX_PLAINTEXT = 1024 * 1024;
const MIN_PLAINTEXT = 1;
const EXTENDED_PREFIX_THRESHOLD = 65536;
const VERSION = 2;
const encoder = new TextEncoder();
const decoder = new TextDecoder("utf-8", { fatal: true });

export function calcPaddedLen(length: number): number {
  if (!Number.isSafeInteger(length) || length < MIN_PLAINTEXT || length > NIP44_MAX_PLAINTEXT) {
    throw new Error("plaintext size is outside the local NIP-44 limit");
  }
  if (length <= 32) return 32;
  const nextPower = 2 ** (Math.floor(Math.log2(length - 1)) + 1);
  const chunk = nextPower <= 256 ? 32 : nextPower / 8;
  return chunk * (Math.floor((length - 1) / chunk) + 1);
}

export function getConversationKey(privateKey: Uint8Array, publicKey: string): Uint8Array {
  const sharedX = secp256k1.getSharedSecret(privateKey, `02${publicKey}`).subarray(1, 33);
  return hkdfExtract(sha256, sharedX, "nip44-v2");
}

export function messageKeys(conversationKey: Uint8Array, nonce: Uint8Array): {
  chachaKey: Uint8Array;
  chachaNonce: Uint8Array;
  hmacKey: Uint8Array;
} {
  if (conversationKey.length !== 32 || nonce.length !== 32) throw new Error("invalid NIP-44 key material");
  const keys = hkdfExpand(sha256, conversationKey, nonce, 76);
  return {
    chachaKey: keys.subarray(0, 32),
    chachaNonce: keys.subarray(32, 44),
    hmacKey: keys.subarray(44, 76),
  };
}

function pad(plaintext: string): Uint8Array {
  const bytes = encoder.encode(plaintext);
  const length = bytes.length;
  const paddedLength = calcPaddedLen(length);
  const prefixLength = length >= EXTENDED_PREFIX_THRESHOLD ? 6 : 2;
  const out = new Uint8Array(prefixLength + paddedLength);
  const view = new DataView(out.buffer);
  if (prefixLength === 6) {
    view.setUint16(0, 0, false);
    view.setUint32(2, length, false);
  } else {
    view.setUint16(0, length, false);
  }
  out.set(bytes, prefixLength);
  return out;
}

function unpad(padded: Uint8Array): string {
  if (padded.length < 2) throw new Error("invalid padding");
  const view = new DataView(padded.buffer, padded.byteOffset, padded.byteLength);
  const shortLength = view.getUint16(0, false);
  let prefixLength = 2;
  let length = shortLength;
  if (shortLength === 0) {
    if (padded.length < 6) throw new Error("invalid padding");
    length = view.getUint32(2, false);
    prefixLength = 6;
    if (length < EXTENDED_PREFIX_THRESHOLD) throw new Error("invalid extended padding");
  }
  if (length < MIN_PLAINTEXT || length > NIP44_MAX_PLAINTEXT) throw new Error("plaintext size is outside the local NIP-44 limit");
  if (padded.length !== prefixLength + calcPaddedLen(length)) throw new Error("invalid padding length");
  const end = prefixLength + length;
  if (end > padded.length) throw new Error("invalid padding length");
  for (let index = end; index < padded.length; index += 1) {
    if (padded[index] !== 0) throw new Error("nonzero padding");
  }
  return decoder.decode(padded.subarray(prefixLength, end));
}

function payloadLengthLimit(): number {
  const raw = 1 + 32 + 6 + calcPaddedLen(NIP44_MAX_PLAINTEXT) + 32;
  return 4 * Math.ceil(raw / 3);
}

function decodePayload(payload: string): { nonce: Uint8Array; ciphertext: Uint8Array; mac: Uint8Array } {
  if (typeof payload !== "string" || payload.length === 0 || payload[0] === "#") throw new Error("unknown encryption version");
  if (payload.length < 132 || payload.length > payloadLengthLimit() || payload.length % 4 !== 0) {
    throw new Error("invalid payload size");
  }
  if (!/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(payload)) {
    throw new Error("invalid base64");
  }
  let data: Uint8Array;
  try {
    data = base64.decode(payload);
  } catch {
    throw new Error("invalid base64");
  }
  const maxRaw = 1 + 32 + 6 + calcPaddedLen(NIP44_MAX_PLAINTEXT) + 32;
  if (data.length < 99 || data.length > maxRaw) throw new Error("invalid decoded payload size");
  if (data[0] !== VERSION) throw new Error(`unknown encryption version ${data[0] ?? "missing"}`);
  return {
    nonce: data.subarray(1, 33),
    ciphertext: data.subarray(33, data.length - 32),
    mac: data.subarray(data.length - 32),
  };
}

export function encrypt(plaintext: string, conversationKey: Uint8Array, nonce?: Uint8Array): string {
  const actualNonce = nonce ?? crypto.getRandomValues(new Uint8Array(32));
  const keys = messageKeys(conversationKey, actualNonce);
  const ciphertext = chacha20(keys.chachaKey, keys.chachaNonce, pad(plaintext));
  const mac = hmac(sha256, keys.hmacKey, concatBytes(actualNonce, ciphertext));
  return base64.encode(concatBytes(new Uint8Array([VERSION]), actualNonce, ciphertext, mac));
}

export function decrypt(payload: string, conversationKey: Uint8Array): string {
  const decoded = decodePayload(payload);
  const keys = messageKeys(conversationKey, decoded.nonce);
  const calculatedMac = hmac(sha256, keys.hmacKey, concatBytes(decoded.nonce, decoded.ciphertext));
  if (!equalBytes(calculatedMac, decoded.mac)) throw new Error("invalid MAC");
  return unpad(chacha20(keys.chachaKey, keys.chachaNonce, decoded.ciphertext));
}
