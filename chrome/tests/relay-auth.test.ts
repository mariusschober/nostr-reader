import { describe, expect, it } from "vitest";
import { getPublicKey } from "nostr-tools/pure";
import type { SimplePool } from "nostr-tools/pool";
import {
  publishPerRelay,
  queryRelayWithAuth,
  randomSeckey,
  validateAuthTemplate,
} from "../src/nostr/transport.js";

describe("NIP-42 relay authentication", () => {
  it("authenticates and retries a challenged publish with the supplied app key", async () => {
    const authKey = randomSeckey();
    let challengeAvailable = false;
    let authenticated = false;
    let publishCalls = 0;
    let authCalls = 0;
    let signedAuth: Record<string, unknown> | undefined;
    const relay = {
      _onauth: null,
      challenge: undefined as string | undefined,
      auth: async (sign: (template: Record<string, unknown>) => Promise<Record<string, unknown>>) => {
        authCalls += 1;
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
        (relay._onauth as null | ((challenge: string) => void))?.("challenge");
        await new Promise((resolve) => setTimeout(resolve, 0));
        return "";
      },
    };
    const pool = { ensureRelay: async () => relay } as unknown as SimplePool;
    const [result] = await publishPerRelay(pool, ["wss://relay.example"], {} as never, authKey);
    expect(result?.ok).toBe(true);
    expect(publishCalls).toBe(2);
    expect(authCalls).toBe(1);
    expect(signedAuth?.["kind"]).toBe(22242);
    expect(signedAuth?.["pubkey"]).toBe(getPublicKey(authKey));
  });

  it("rejects a changed late challenge without creating a signing loop", async () => {
    const authKey = randomSeckey();
    let authenticated = false;
    let authCalls = 0;
    const relay = {
      _onauth: null,
      challenge: undefined as string | undefined,
      auth: async (sign: (template: Record<string, unknown>) => Promise<Record<string, unknown>>) => {
        authCalls += 1;
        await sign({
          kind: 22242,
          created_at: Math.floor(Date.now() / 1000),
          tags: [["relay", "wss://relay.example/"], ["challenge", "first-challenge"]],
          content: "",
        });
        authenticated = true;
        return "";
      },
      publish: async () => {
        if (!authenticated) {
          relay.challenge = "first-challenge";
          (relay._onauth as null | ((challenge: string) => void))?.("first-challenge");
          throw new Error("auth-required: authenticate first");
        }
        relay.challenge = "changed-secret-challenge";
        (relay._onauth as null | ((challenge: string) => void))?.("changed-secret-challenge");
        return "";
      },
    };
    const pool = { ensureRelay: async () => relay } as unknown as SimplePool;
    const [result] = await publishPerRelay(pool, ["wss://relay.example"], {} as never, authKey);
    expect(result).toMatchObject({ ok: false, state: "PROTOCOL_ERROR" });
    expect(result?.reasonPrefix).not.toContain("first-challenge");
    expect(result?.reasonPrefix).not.toContain("changed-secret-challenge");
    expect(authCalls).toBe(1);
  });

  it("redacts a challenge exposed by the relay before an auth-required rejection", async () => {
    const authKey = randomSeckey();
    const relay = {
      _onauth: null,
      challenge: undefined as string | undefined,
      auth: async (sign: (template: Record<string, unknown>) => Promise<Record<string, unknown>>) => {
        await sign({
          kind: 22242,
          created_at: Math.floor(Date.now() / 1000),
          tags: [["relay", "wss://relay.example/"], ["challenge", "compact-secret-challenge"]],
          content: "",
        });
        throw new Error("restricted: compact-secret-challenge");
      },
      publish: async () => {
        relay.challenge = "compact-secret-challenge";
        throw new Error("auth-required: authenticate first");
      },
    };
    const pool = { ensureRelay: async () => relay } as unknown as SimplePool;
    const [result] = await publishPerRelay(pool, ["wss://relay.example"], {} as never, authKey);
    expect(result).toMatchObject({ ok: false, state: "AUTH_ERROR" });
    expect(result?.reasonPrefix).toContain("[redacted-challenge]");
    expect(result?.reasonPrefix).not.toContain("compact-secret-challenge");
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
        await sign({
          kind: 22242,
          created_at: Math.floor(Date.now() / 1000),
          tags: [["relay", "wss://relay.example/"], ["challenge", "c"]],
          content: "",
        });
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

  it("independently rejects misbound, stale, and expanded authentication templates", () => {
    const now = 1_800_000_000;
    const valid = {
      kind: 22242,
      created_at: now,
      tags: [["relay", "wss://relay.example/"], ["challenge", "exact-challenge"]],
      content: "",
    };
    expect(() => validateAuthTemplate(valid, "wss://relay.example", "exact-challenge", now)).not.toThrow();
    expect(() => validateAuthTemplate(
      { ...valid, tags: [["relay", "wss://other.example/"], ["challenge", "exact-challenge"]] },
      "wss://relay.example",
      "exact-challenge",
      now,
    )).toThrow(/binding/);
    expect(() => validateAuthTemplate(
      { ...valid, tags: [["relay", "not a relay URL"], ["challenge", "exact-challenge"]] },
      "wss://relay.example",
      "exact-challenge",
      now,
    )).toThrow(/binding/);
    expect(() => validateAuthTemplate(
      { ...valid, tags: [["relay", "wss://relay.example/"], ["challenge", "wrong"]] },
      "wss://relay.example",
      "exact-challenge",
      now,
    )).toThrow(/binding/);
    expect(() => validateAuthTemplate({ ...valid, created_at: now - 601 }, "wss://relay.example", "exact-challenge", now)).toThrow(/timestamp/);
    expect(() => validateAuthTemplate({ ...valid, extra: true }, "wss://relay.example", "exact-challenge", now)).toThrow(/fields/);
  });

  it("classifies a malicious library authentication template as a client protocol error", async () => {
    const authKey = randomSeckey();
    let authenticated = false;
    const relay = {
      _onauth: null,
      challenge: undefined as string | undefined,
      auth: async (sign: (template: Record<string, unknown>) => Promise<Record<string, unknown>>) => {
        await sign({
          kind: 22242,
          created_at: Math.floor(Date.now() / 1000),
          tags: [["relay", "wss://attacker.example/"], ["challenge", "challenge"]],
          content: "",
        });
        authenticated = true;
      },
      publish: async () => {
        if (!authenticated) {
          relay.challenge = "challenge";
          (relay._onauth as null | ((challenge: string) => void))?.("challenge");
          throw new Error("auth-required: authenticate first");
        }
      },
    };
    const pool = { ensureRelay: async () => relay } as unknown as SimplePool;
    const [result] = await publishPerRelay(pool, ["wss://relay.example"], {} as never, authKey);
    expect(result).toMatchObject({ ok: false, state: "PROTOCOL_ERROR" });
    expect(authenticated).toBe(false);
  });
});
