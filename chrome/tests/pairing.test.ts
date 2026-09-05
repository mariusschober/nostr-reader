import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import {
  createPairAck,
  createPairingRequest,
  PAIRING_CAPABILITIES,
  PAIRING_PROTOCOL,
  validatePairComplete,
  validatePairResponse,
} from "../src/protocol/pairing.js";

const PAIRING_KEY = "f9308a019258c31049344f85f89d5229b531c845836f99b08601f113bce036f9";
const DEVICE_KEY = "dff1d77f2a671c5f36183726db2341be58feae1da2deced843240f7b502ba659";
const CHANNEL_KEY = "dd308afec5777e13121fa72b9cc1b7cc0139715309b086c960e18fd969774eb8";
const NOW = 1_800_000_000;

async function request() {
  return createPairingRequest({
    sessionId: "01".repeat(16),
    pairingPubkey: PAIRING_KEY,
    chromeDevicePubkey: DEVICE_KEY,
    nonce: "02".repeat(32),
    relays: ["wss://Relay.Example/", "wss://second.example"],
    nowSecs: NOW,
  });
}

describe("pairing v2 transcript", () => {
  it("matches the shared Android-Chrome pairing transcript byte-for-field", async () => {
    const vector = JSON.parse(readFileSync(
      join(process.cwd(), "..", "shared", "test-vectors", "pairing-v2.json"),
      "utf8",
    ));
    const expectedRequest = vector.request;
    const qr = await createPairingRequest({
      sessionId: expectedRequest.sessionId,
      pairingPubkey: expectedRequest.pairingPubkey,
      chromeDevicePubkey: expectedRequest.chromeDevicePubkey,
      nonce: expectedRequest.nonce,
      relays: ["wss://Relay.Example/", "wss://second.example"],
      nowSecs: expectedRequest.createdAt,
    });
    expect(qr).toEqual(expectedRequest);

    expect(validatePairResponse(
      vector.response,
      qr,
      vector.verifiedSenders.androidChannelPubkey,
      vector.validationNow.response,
    )).toEqual(vector.response);

    expect(createPairAck(
      qr,
      vector.verifiedSenders.androidChannelPubkey,
      vector.ack.acceptedRelays,
      vector.ack.createdAt,
    )).toEqual(vector.ack);

    expect(validatePairComplete(
      vector.complete,
      qr,
      vector.verifiedSenders.androidChannelPubkey,
      vector.verifiedSenders.androidChannelPubkey,
      vector.validationNow.complete,
    )).toEqual(vector.complete);
  });

  it("normalizes and binds the exact relay set", async () => {
    const qr = await request();
    expect(qr.protocol).toBe(PAIRING_PROTOCOL);
    expect(qr.relays).toEqual(["wss://relay.example", "wss://second.example"]);
    expect(qr.relaySetDigest).toMatch(/^[0-9a-f]{64}$/);
    expect(qr.capabilities).toEqual([...PAIRING_CAPABILITIES]);
  });

  it("authenticates a bootstrap response with the verified inner sender", async () => {
    const qr = await request();
    const response = {
      protocol: PAIRING_PROTOCOL,
      type: "pair-response",
      sessionId: qr.sessionId,
      nonce: qr.nonce,
      recipientPairingPubkey: qr.pairingPubkey,
      chromeDevicePubkey: qr.chromeDevicePubkey,
      androidChannelPubkey: CHANNEL_KEY,
      relaySetDigest: qr.relaySetDigest,
      appVersion: "0.2.0-test",
      capabilities: [...PAIRING_CAPABILITIES],
      createdAt: NOW + 5,
      expiresAt: NOW + 305,
    } as const;
    expect(validatePairResponse(response, qr, CHANNEL_KEY, NOW + 6).androidChannelPubkey).toBe(CHANNEL_KEY);
    expect(() => validatePairResponse(response, qr, PAIRING_KEY, NOW + 6)).toThrow(/does not own channel key/);
    expect(() => validatePairResponse({ ...response, nonce: "03".repeat(32) }, qr, CHANNEL_KEY, NOW + 6)).toThrow(/transcript/);
    expect(() => validatePairResponse({ ...response, capabilities: ["gzip"] }, qr, CHANNEL_KEY, NOW + 6)).toThrow(/capabilities/);
  });

  it("builds an ACK over the same relay set and requires signed completion", async () => {
    const qr = await request();
    const ack = createPairAck(qr, CHANNEL_KEY, [qr.relays[1]!], NOW + 10);
    expect(ack.acceptedRelays).toEqual(["wss://second.example"]);
    const completion = {
      protocol: PAIRING_PROTOCOL,
      type: "pair-complete",
      sessionId: qr.sessionId,
      chromeDevicePubkey: qr.chromeDevicePubkey,
      androidChannelPubkey: CHANNEL_KEY,
      relaySetDigest: qr.relaySetDigest,
      status: "connected",
      createdAt: NOW + 20,
      expiresAt: NOW + 320,
    } as const;
    expect(validatePairComplete(completion, qr, CHANNEL_KEY, CHANNEL_KEY, NOW + 21).status).toBe("connected");
    expect(() => validatePairComplete(completion, qr, CHANNEL_KEY, PAIRING_KEY, NOW + 21)).toThrow(/untrusted/);
    expect(() => validatePairComplete({ ...completion, createdAt: NOW - 61 }, qr, CHANNEL_KEY, CHANNEL_KEY, NOW + 21)).toThrow(/timestamp/);
  });

  it("rejects URLs that can target local or ambiguous hosts", async () => {
    for (const relay of [
      "ws://relay.example",
      "wss://127.0.0.1",
      "wss://2130706433",
      "wss://relay.local",
      "wss://user:pass@relay.example",
      "wss://relay.example/path?redirect=wss://safe.example",
    ]) {
      await expect(createPairingRequest({
        sessionId: "01".repeat(16), pairingPubkey: PAIRING_KEY,
        chromeDevicePubkey: DEVICE_KEY, nonce: "02".repeat(32),
        relays: [relay], nowSecs: NOW,
      })).rejects.toThrow();
    }
  });
});
