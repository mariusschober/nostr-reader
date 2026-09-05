import { describe, it, expect } from "vitest";
import { generateSecretKey, getEventHash, getPublicKey } from "nostr-tools/pure";
import * as nip44 from "nostr-tools/nip44";
import type { SimplePool } from "nostr-tools/pool";
import { publishPerRelay, publishQuorum, sealAndWrap, unwrapAndVerify } from "../src/nostr/transport.js";

describe("NIP-59 roundtrip", () => {
  it("seal+wrap then full-checklist unwrap returns the payload", async () => {
    const a = generateSecretKey();
    const b = generateSecretKey();
    const payload = { protocol: "reader/2", type: "chunk", transferId: "t1", index: 0 };
    const { wrap } = await sealAndWrap({ senderSeckey: a, recipientPubkey: getPublicKey(b), payload });
    const out = await unwrapAndVerify({ wrap: wrap as never, recipientSeckey: b, expectedSenderPubkey: getPublicKey(a) });
    expect(out["transferId"]).toBe("t1");
  });
  it("rejects untrusted sender", async () => {
    const a = generateSecretKey();
    const b = generateSecretKey();
    const evil = generateSecretKey();
    const { wrap } = await sealAndWrap({ senderSeckey: a, recipientPubkey: getPublicKey(b), payload: { protocol: "reader/2", type: "ping" } });
    await expect(unwrapAndVerify({ wrap: wrap as never, recipientSeckey: b, expectedSenderPubkey: getPublicKey(evil) })).rejects.toThrow("untrusted sender");
  });
  it("rejects tampered outer signature", async () => {
    const a = generateSecretKey();
    const b = generateSecretKey();
    const { wrap } = await sealAndWrap({ senderSeckey: a, recipientPubkey: getPublicKey(b), payload: { protocol: "reader/2", type: "ping" } });
    const bad = JSON.parse(JSON.stringify(wrap)) as Record<string, unknown>;
    bad["sig"] = "0".repeat(128);
    await expect(unwrapAndVerify({ wrap: bad as never, recipientSeckey: b, expectedSenderPubkey: getPublicKey(a) })).rejects.toThrow();
  });
  it("rejects wrong recipient", async () => {
    const a = generateSecretKey();
    const b = generateSecretKey();
    const c = generateSecretKey();
    const { wrap } = await sealAndWrap({ senderSeckey: a, recipientPubkey: getPublicKey(b), payload: { protocol: "reader/2", type: "ping" } });
    await expect(unwrapAndVerify({ wrap: wrap as never, recipientSeckey: c, expectedSenderPubkey: getPublicKey(a) })).rejects.toThrow();
  });

  it("routes the outer event to the intended recipient and gives the rumor an id", async () => {
    const sender = generateSecretKey();
    const recipient = generateSecretKey();
    const recipientPubkey = getPublicKey(recipient);
    const { wrap } = await sealAndWrap({
      senderSeckey: sender,
      recipientPubkey,
      payload: { protocol: "reader/2", type: "ping" },
    });
    const outer = wrap as { pubkey: string; content: string; tags: string[][] };
    expect(outer.tags).toContainEqual(["p", recipientPubkey]);

    const seal = JSON.parse(nip44.decrypt(
      outer.content,
      nip44.getConversationKey(recipient, outer.pubkey),
    )) as { pubkey: string; content: string };
    const rumor = JSON.parse(nip44.decrypt(
      seal.content,
      nip44.getConversationKey(recipient, seal.pubkey),
    )) as Record<string, unknown>;
    expect(rumor["id"]).toBe(getEventHash(rumor as never));
    expect(rumor).not.toHaveProperty("sig");
  });

  it("rejects an expired outer event before using its payload", async () => {
    const sender = generateSecretKey();
    const recipient = generateSecretKey();
    const { wrap } = await sealAndWrap({
      senderSeckey: sender,
      recipientPubkey: getPublicKey(recipient),
      payload: { protocol: "reader/2", type: "ping" },
      expireSecs: -1,
    });
    await expect(unwrapAndVerify({
      wrap: wrap as never,
      recipientSeckey: recipient,
      expectedSenderPubkey: getPublicKey(sender),
    })).rejects.toThrow(/expired/i);
  });

  it("rejects a valid ephemeral 21059 wrapper in the durable reader/2 protocol", async () => {
    const sender = generateSecretKey();
    const recipient = generateSecretKey();
    const { wrap } = await sealAndWrap({
      senderSeckey: sender,
      recipientPubkey: getPublicKey(recipient),
      payload: { protocol: "reader/2", type: "ping" },
      wrapKind: 21059,
    });
    await expect(unwrapAndVerify({
      wrap: wrap as never,
      recipientSeckey: recipient,
      expectedSenderPubkey: getPublicKey(sender),
    })).rejects.toThrow(/bad wrap kind/);
  });
});

describe("nostr-tools 2.7.1 relay publication contract", () => {
  it("awaits each per-relay Promise exactly once before counting quorum", async () => {
    const calls: string[][] = [];
    const settled: string[] = [];
    const pool = {
      publish(relays: string[]) {
        calls.push([...relays]);
        return relays.map((url) => {
          const result = new Promise<string>((resolve, reject) => {
            queueMicrotask(() => {
              settled.push(url);
              if (url === "wss://reject.example") reject(new Error("blocked: policy"));
              else resolve("saved");
            });
          });
          // Attach a side handler because the broken baseline never awaits the
          // returned Promise array. The original Promise remains rejected for
          // the repaired implementation to classify.
          void result.catch(() => undefined);
          return result;
        });
      },
    } as unknown as SimplePool;

    const ok = await publishQuorum(
      pool,
      ["wss://one.example", "wss://reject.example", "wss://two.example"],
      {} as never,
      2,
    );

    expect(calls).toEqual([
      ["wss://one.example"],
      ["wss://reject.example"],
      ["wss://two.example"],
    ]);
    expect(settled).toEqual([
      "wss://one.example",
      "wss://reject.example",
      "wss://two.example",
    ]);
    expect(ok).toEqual(["wss://one.example", "wss://two.example"]);
  });

  it("keeps explicit rejection, close, socket, TLS, and no-OK timeout distinct", async () => {
    const never = new Promise<string>(() => undefined);
    const outcomes: Record<string, Promise<string>> = {
      "wss://reject.example": Promise.reject(new Error("blocked: policy")),
      "wss://closed.example": Promise.reject(new Error("relay connection closed")),
      "wss://socket.example": Promise.reject(new Error("websocket error")),
      "wss://tls.example": Promise.reject(new Error("TLS certificate invalid")),
      "wss://timeout.example": never,
    };
    Object.values(outcomes).forEach((promise) => { void promise.catch(() => undefined); });
    const pool = {
      publish(relays: string[]) { return relays.map((relay) => outcomes[relay]!); },
    } as unknown as SimplePool;
    const result = await publishPerRelay(pool, Object.keys(outcomes), {} as never, undefined, 5);
    expect(Object.fromEntries(result.map((item) => [item.url, item.state]))).toEqual({
      "wss://reject.example": "OK_FALSE",
      "wss://closed.example": "CLOSED",
      "wss://socket.example": "SOCKET_ERROR",
      "wss://tls.example": "TLS_ERROR",
      "wss://timeout.example": "NO_OK_TIMEOUT",
    });
  });
});
