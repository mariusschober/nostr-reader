import { describe, it, expect } from "vitest";
import { generateSecretKey, getPublicKey } from "nostr-tools/pure";
import { sealAndWrap, unwrapAndVerify } from "../src/nostr/transport.js";

describe("NIP-59 roundtrip", () => {
  it("seal+wrap then full-checklist unwrap returns the payload", async () => {
    const a = generateSecretKey();
    const b = generateSecretKey();
    const payload = { protocol: "reader/1", type: "chunk", transferId: "t1", index: 0 };
    const { wrap } = await sealAndWrap({ senderSeckey: a, recipientPubkey: getPublicKey(b), payload });
    const out = await unwrapAndVerify({ wrap: wrap as never, recipientSeckey: b, expectedSenderPubkey: getPublicKey(a) });
    expect(out["transferId"]).toBe("t1");
  });
  it("rejects untrusted sender", async () => {
    const a = generateSecretKey();
    const b = generateSecretKey();
    const evil = generateSecretKey();
    const { wrap } = await sealAndWrap({ senderSeckey: a, recipientPubkey: getPublicKey(b), payload: { protocol: "reader/1", type: "ping" } });
    await expect(unwrapAndVerify({ wrap: wrap as never, recipientSeckey: b, expectedSenderPubkey: getPublicKey(evil) })).rejects.toThrow("untrusted sender");
  });
  it("rejects tampered outer signature", async () => {
    const a = generateSecretKey();
    const b = generateSecretKey();
    const { wrap } = await sealAndWrap({ senderSeckey: a, recipientPubkey: getPublicKey(b), payload: { protocol: "reader/1", type: "ping" } });
    const bad = JSON.parse(JSON.stringify(wrap)) as Record<string, unknown>;
    bad["sig"] = "0".repeat(128);
    await expect(unwrapAndVerify({ wrap: bad as never, recipientSeckey: b, expectedSenderPubkey: getPublicKey(a) })).rejects.toThrow();
  });
  it("rejects wrong recipient", async () => {
    const a = generateSecretKey();
    const b = generateSecretKey();
    const c = generateSecretKey();
    const { wrap } = await sealAndWrap({ senderSeckey: a, recipientPubkey: getPublicKey(b), payload: { protocol: "reader/1", type: "ping" } });
    await expect(unwrapAndVerify({ wrap: wrap as never, recipientSeckey: c, expectedSenderPubkey: getPublicKey(a) })).rejects.toThrow();
  });
});
