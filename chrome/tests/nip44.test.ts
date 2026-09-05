import { describe, expect, it } from "vitest";
import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { getPublicKey } from "nostr-tools/pure";
import { sha256HexBytes } from "../src/protocol/core.js";
import { calcPaddedLen, decrypt, encrypt, getConversationKey, messageKeys, NIP44_MAX_PLAINTEXT } from "../src/nostr/nip44.js";

function hex(value: string): Uint8Array {
  return Uint8Array.from(value.match(/../g)!.map((byte) => Number.parseInt(byte, 16)));
}

const SECRET_ONE = "00".repeat(31) + "01";
const PUBLIC_TWO = "c6047f9441ed7d6d3045406e95c07cd85c778e4b8cef3ca7abac09b95c709ee5";
const CONVERSATION_KEY = "c41c775356fd92eadc63ff5a0dc1da211b268cbea22316767095b2871ea1412d";
const NONCE = "00".repeat(31) + "01";

function official(): any {
  const bytes = readFileSync(join(process.cwd(), "..", "shared", "test-vectors", "nip44-official.json"));
  expect(createHash("sha256").update(bytes).digest("hex")).toBe("269ed0f69e4c192512cc779e78c555090cebc7c785b609e338a62afc3ce25040");
  return JSON.parse(bytes.toString("utf8")).v2;
}

function hexString(bytes: Uint8Array): string {
  return [...bytes].map((byte) => byte.toString(16).padStart(2, "0")).join("");
}

describe("current NIP-44 v2", () => {
  it("reproduces the official one-byte vector", () => {
    const key = getConversationKey(hex(SECRET_ONE), PUBLIC_TWO);
    expect(Buffer.from(key).toString("hex")).toBe(CONVERSATION_KEY);
    const payload = encrypt("a", key, hex(NONCE));
    expect(payload).toBe("AgAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABee0G5VSK0/9YypIObAtDKfYEAjD35uVkHyB0F4DwrcNaCXlCWZKaArsGrY6M9wnuTMxWfp1RTN9Xga8no+kF5Vsb");
    expect(decrypt(payload, key)).toBe("a");
  });

  it.each([
    [65535, 65536, "6d8c2810d1e870fbaa1f0a0937126cca837a15f9260e27060c331d70a3c0bc84"],
    [65536, 65536, "b7b4edb36ba92e267d322d56d9aebc22e7fa96ff52e3c12adc07f07a43cbc616"],
    [65537, 81920, "eeb7c7c5373894ea2c1547cfd3ccb15d5a0b2d619da852e5c79df792dcc9e435"],
  ])("matches the official extended-prefix boundary at %i bytes", async (length, paddedLength, payloadHash) => {
    const key = hex(CONVERSATION_KEY);
    const plaintext = "a".repeat(length);
    expect(calcPaddedLen(length)).toBe(paddedLength);
    const payload = encrypt(plaintext, key, hex(NONCE));
    expect(await sha256HexBytes(new TextEncoder().encode(payload))).toBe(payloadHash);
    expect(decrypt(payload, key)).toBe(plaintext);
  });

  it("rejects oversized inputs before encryption or base64 decoding", () => {
    expect(() => encrypt("a".repeat(NIP44_MAX_PLAINTEXT + 1), hex(CONVERSATION_KEY), hex(NONCE))).toThrow(/local NIP-44 limit/);
    expect(() => decrypt("A".repeat(1_500_000), hex(CONVERSATION_KEY))).toThrow(/payload size/);
  });

  it("passes the checksum-pinned official positive corpus", async () => {
    const vectors = official().valid;
    for (const vector of vectors.get_conversation_key) {
      expect(hexString(getConversationKey(hex(vector.sec1), vector.pub2))).toBe(vector.conversation_key);
    }
    for (const [length, padded] of vectors.calc_padded_len) expect(calcPaddedLen(length)).toBe(padded);
    for (const vector of vectors.get_message_keys.keys) {
      const keys = messageKeys(hex(vectors.get_message_keys.conversation_key), hex(vector.nonce));
      expect(hexString(keys.chachaKey)).toBe(vector.chacha_key);
      expect(hexString(keys.chachaNonce)).toBe(vector.chacha_nonce);
      expect(hexString(keys.hmacKey)).toBe(vector.hmac_key);
    }
    for (const vector of vectors.encrypt_decrypt) {
      const key = getConversationKey(hex(vector.sec1), getPublicKey(hex(vector.sec2)));
      expect(hexString(key)).toBe(vector.conversation_key);
      expect(encrypt(vector.plaintext, key, hex(vector.nonce))).toBe(vector.payload);
      expect(decrypt(vector.payload, key)).toBe(vector.plaintext);
    }
    for (const vector of vectors.encrypt_decrypt_long_msg) {
      const plaintext = vector.pattern.repeat(vector.repeat);
      expect(await sha256HexBytes(new TextEncoder().encode(plaintext))).toBe(vector.plaintext_sha256);
      const payload = encrypt(plaintext, hex(vector.conversation_key), hex(vector.nonce));
      expect(await sha256HexBytes(new TextEncoder().encode(payload))).toBe(vector.payload_sha256);
      expect(decrypt(payload, hex(vector.conversation_key))).toBe(plaintext);
    }
  });

  it("rejects every applicable official negative vector", () => {
    const vectors = official().invalid;
    for (const vector of vectors.get_conversation_key) {
      expect(() => getConversationKey(hex(vector.sec1), vector.pub2)).toThrow();
    }
    for (const vector of vectors.decrypt) {
      expect(() => decrypt(vector.payload, hex(vector.conversation_key))).toThrow();
    }
    // The corpus predates the extended prefix and lists 65536/100000 as
    // invalid; current NIP-44 explicitly makes them valid, so only zero and
    // our independently tested local resource cap remain negative here.
    expect(vectors.encrypt_msg_lengths).toContain(0);
    expect(() => encrypt("", hex(CONVERSATION_KEY), hex(NONCE))).toThrow();
  });
});
