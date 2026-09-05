import { describe, it, expect } from "vitest";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { generateSecretKey, getPublicKey, finalizeEvent } from "nostr-tools/pure";
import { buildDeviceAuthorization, verifyProof } from "../src/signer/nip07.js";

describe("NIP-07 proof bridge", () => {
  it("does not expose a page-world signing bridge", () => {
    const manifest = JSON.parse(readFileSync(join(process.cwd(), "manifest.json"), "utf8"));
    expect(manifest).not.toHaveProperty("web_accessible_resources");
    expect(readFileSync(join(process.cwd(), "vite.config.ts"), "utf8")).not.toContain("bridge-main");
  });

  it("accepts an exact proof and rejects mutation", () => {
    const ext = generateSecretKey();
    const tpl = buildDeviceAuthorization({
      devicePubkey: getPublicKey(generateSecretKey()),
      externalPubkey: getPublicKey(ext),
      issuedAt: 1725400000, expiresAt: 1733176000, nonce: "abc123",
    });
    expect(JSON.parse(tpl.content).protocol).toBe("reader/2");
    const signed = finalizeEvent({ kind: tpl.kind, created_at: tpl.created_at, tags: tpl.tags, content: tpl.content }, ext) as unknown as Record<string, unknown>;
    expect(verifyProof(tpl, signed)).toBe(true);
    expect(verifyProof(tpl, { ...signed, content: '{"tampered":true}' })).toBe(false);
  });
});
