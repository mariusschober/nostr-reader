import { describe, it, expect } from "vitest";
import { generateSecretKey, getPublicKey, finalizeEvent } from "nostr-tools/pure";
import { buildDeviceAuthorization, verifyProof } from "../src/signer/nip07.js";

describe("NIP-07 proof bridge", () => {
  it("accepts an exact proof and rejects mutation", () => {
    const ext = generateSecretKey();
    const tpl = buildDeviceAuthorization({
      devicePubkey: getPublicKey(generateSecretKey()),
      externalPubkey: getPublicKey(ext),
      issuedAt: 1725400000, expiresAt: 1733176000, nonce: "abc123",
    });
    const signed = finalizeEvent({ kind: tpl.kind, created_at: tpl.created_at, tags: tpl.tags, content: tpl.content }, ext) as unknown as Record<string, unknown>;
    expect(verifyProof(tpl, signed)).toBe(true);
    expect(verifyProof(tpl, { ...signed, content: '{"tampered":true}' })).toBe(false);
  });
});
