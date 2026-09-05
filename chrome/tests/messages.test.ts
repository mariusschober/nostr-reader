import { describe, expect, it } from "vitest";
import { manifestIdentity, validateEndpointAck } from "../src/protocol/messages.js";
import { READER_PROTOCOL } from "../src/protocol/core.js";

const TRANSFER = "01".repeat(16);
const DOCUMENT = "02".repeat(32);
const MANIFEST = "03".repeat(32);
const CHANNEL = "04".repeat(32);
const DEVICE = "05".repeat(32);

describe("v2 manifest and endpoint ACK binding", () => {
  it("derives a stable manifest identity from every assembly-critical field", async () => {
    const base = {
      transferId: TRANSFER,
      documentId: DOCUMENT,
      compressedSha256: "06".repeat(32),
      compressedBytes: 100,
      chunkCount: 2,
      senderDevicePubkey: DEVICE,
      recipientChannelPubkey: CHANNEL,
      expiresAt: 1_900_000_000,
    };
    const id = await manifestIdentity(base);
    expect(id).toMatch(/^[0-9a-f]{64}$/);
    await expect(manifestIdentity({ ...base, chunkCount: 3 })).resolves.not.toBe(id);
  });

  it("accepts only an authenticated ACK bound to both endpoints and the manifest", () => {
    const now = 1_800_000_000;
    const ack = {
      protocol: READER_PROTOCOL,
      type: "ack",
      transferId: TRANSFER,
      documentId: DOCUMENT,
      manifestId: MANIFEST,
      contentHash: DOCUMENT,
      senderChannelPubkey: CHANNEL,
      recipientDevicePubkey: DEVICE,
      status: "stored",
      receivedAt: now,
      expiresAt: now + 600,
    };
    const expected = { transferId: TRANSFER, documentId: DOCUMENT, manifestId: MANIFEST, channelPubkey: CHANNEL, devicePubkey: DEVICE };
    expect(validateEndpointAck(ack, expected, CHANNEL, now).status).toBe("stored");
    expect(() => validateEndpointAck({ ...ack, recipientDevicePubkey: "07".repeat(32) }, expected, CHANNEL, now)).toThrow(/binding/);
    expect(() => validateEndpointAck(ack, expected, "07".repeat(32), now)).toThrow(/untrusted/);
    expect(() => validateEndpointAck({ ...ack, expiresAt: now }, expected, CHANNEL, now)).toThrow(/expired/);
  });
});
