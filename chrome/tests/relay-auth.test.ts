import { describe, expect, it } from "vitest";
import { getPublicKey } from "nostr-tools/pure";
import type { SimplePool } from "nostr-tools/pool";
import { publishPerRelay, queryRelayWithAuth, randomSeckey } from "../src/nostr/transport.js";

describe("NIP-42 relay authentication", () => {
  it("authenticates and retries a challenged publish with the supplied app key", async () => {
    const authKey = randomSeckey();
    let challengeAvailable = false;
    let authenticated = false;
    let publishCalls = 0;
    let signedAuth: Record<string, unknown> | undefined;
    const relay = {
      _onauth: null,
      challenge: undefined as string | undefined,
      auth: async (sign: (template: Record<string, unknown>) => Promise<Record<string, unknown>>) => {
        if (!challengeAvailable) throw new Error("can't perform auth, no challenge was received");
        signedAuth = await sign({
          kind: 22242,
          created_at: Math.floor(Date.now() / 1000),
          tags: [["relay", "wss://relay.example/"], ["challenge", "challenge"]],
          content: "",
        });
        authenticated = true;
        return "";
      },
      publish: async () => {
        publishCalls += 1;
        if (!authenticated) {
          challengeAvailable = true;
          relay.challenge = "challenge";
          (relay._onauth as null | ((challenge: string) => void))?.("challenge");
          throw new Error("auth-required: authenticate first");
        }
        return "";
      },
    };
    const pool = { ensureRelay: async () => relay } as unknown as SimplePool;
    const [result] = await publishPerRelay(pool, ["wss://relay.example"], {} as never, authKey);
    expect(result?.ok).toBe(true);
    expect(publishCalls).toBe(2);
    expect(signedAuth?.["kind"]).toBe(22242);
    expect(signedAuth?.["pubkey"]).toBe(getPublicKey(authKey));
  });

  it("authenticates and repeats a challenged catch-up query", async () => {
    const authKey = randomSeckey();
    let challengeAvailable = false;
    let authenticated = false;
    let queryCalls = 0;
    const relay = {
      _onauth: null,
      challenge: undefined as string | undefined,
      auth: async (sign: (template: Record<string, unknown>) => Promise<Record<string, unknown>>) => {
        if (!challengeAvailable) throw new Error("can't perform auth, no challenge was received");
        await sign({ kind: 22242, created_at: 1, tags: [["relay", "wss://relay.example/"], ["challenge", "c"]], content: "" });
        authenticated = true;
        return "";
      },
    };
    const expected = { id: "ab".repeat(32), created_at: 1 };
    const pool = {
      ensureRelay: async () => relay,
      querySync: async () => {
        queryCalls += 1;
        if (!authenticated) {
          challengeAvailable = true;
          relay.challenge = "c";
          (relay._onauth as null | ((challenge: string) => void))?.("c");
          throw new Error("auth-required: authenticate first");
        }
        return [expected];
      },
    } as unknown as SimplePool;
    const events = await queryRelayWithAuth(pool, "wss://relay.example", { kinds: [1059] }, authKey, 50);
    expect(events).toEqual([expected]);
    expect(queryCalls).toBe(2);
  });
});
