import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { finalizeEvent, generateSecretKey, getPublicKey } from "nostr-tools/pure";
import { SimplePool, useWebSocketImplementation } from "nostr-tools/pool";
import { WebSocket as NodeWebSocket, WebSocketServer } from "ws";
import {
  MAX_RELAY_EVENT_BYTES,
  publishPerRelay,
  publishQuorum,
  queryRelayWithAuth,
} from "../src/nostr/transport.js";

type Event = ReturnType<typeof finalizeEvent>;

const stored = new Map<string, Event[]>();
const requestCounts = new Map<string, number>();
const sockets = new Set<any>();
let server: any;
let port = 0;

function modeUrl(mode: string): string {
  return `ws://127.0.0.1:${port}/${mode}`;
}

function send(socket: any, frame: unknown): void {
  socket.send(JSON.stringify(frame));
}

function matching(events: Event[], filter: Record<string, unknown>): Event[] {
  return events.filter((event) => {
    if (Array.isArray(filter.kinds) && !filter.kinds.includes(event.kind)) return false;
    if (typeof filter.since === "number" && event.created_at < filter.since) return false;
    const recipients = filter["#p"];
    if (Array.isArray(recipients) && !event.tags.some((tag) => tag[0] === "p" && recipients.includes(tag[1]))) return false;
    return true;
  });
}

beforeAll(async () => {
  useWebSocketImplementation(NodeWebSocket);
  server = new WebSocketServer({ port: 0, host: "127.0.0.1" });
  server.on("connection", (socket: any, request: any) => {
    sockets.add(socket);
    socket.on("close", () => sockets.delete(socket));
    const mode = new URL(request.url ?? "/", "ws://fault.test").pathname.slice(1);
    let authenticated = false;
    if (mode === "auth" || mode === "stale-auth") setTimeout(() => send(socket, ["AUTH", "fault-challenge"]), 0);
    if (mode === "close-before") setTimeout(() => socket.close(1000, "closed before send"), 0);

    socket.on("message", (data: any, binary: boolean) => {
      if (binary) return;
      let frame: any[];
      try { frame = JSON.parse(data.toString()) as any[]; } catch { return; }
      if (frame[0] === "AUTH") {
        const authEvent = frame[1] as Event;
        if (mode === "stale-auth") {
          send(socket, ["OK", authEvent.id, false, "stale: authentication rejected"]);
        } else {
          authenticated = true;
          send(socket, ["OK", authEvent.id, true, "authenticated"]);
        }
        return;
      }
      if (frame[0] === "EVENT") {
        const event = frame[1] as Event;
        const events = stored.get(mode) ?? [];
        if (!events.some((candidate) => candidate.id === event.id)) events.push(event);
        stored.set(mode, events);
        if (mode === "ok") send(socket, ["OK", event.id, true, "saved"]);
        else if (mode === "reject") send(socket, ["OK", event.id, false, "blocked: policy"]);
        else if (mode === "no-ok") return;
        else if (mode === "delayed") setTimeout(() => send(socket, ["OK", event.id, true, "saved"]), 25);
        else if (mode === "mismatch") send(socket, ["OK", "00".repeat(32), true, "wrong id"]);
        else if (mode === "duplicate-ok") {
          send(socket, ["OK", event.id, true, "saved"]);
          send(socket, ["OK", event.id, true, "duplicate"]);
        } else if (mode === "notice") {
          send(socket, ["NOTICE", "test notice"]);
          send(socket, ["OK", event.id, true, "saved"]);
        } else if (mode === "close-after" || mode === "store-disconnect") socket.close(1000, "closed after send");
        else if (mode === "malformed") {
          socket.send("{");
          send(socket, ["OK", event.id, true, "saved"]);
        } else if (mode === "binary") {
          socket.send(Buffer.from([0xff, 0x00]), { binary: true });
          send(socket, ["OK", event.id, true, "saved"]);
        } else if (mode === "fragmented") {
          const ok = JSON.stringify(["OK", event.id, true, "saved"]);
          socket.send(ok.slice(0, 8), { fin: false });
          socket.send(ok.slice(8), { fin: true });
        } else if (mode === "message-size") {
          send(socket, ["OK", event.id, false, "restricted: message too large"]);
        } else if (mode === "created-at") {
          send(socket, ["OK", event.id, false, "invalid: created_at too old"]);
        } else if (mode === "auth" || mode === "stale-auth") {
          send(socket, ["OK", event.id, authenticated, authenticated ? "saved" : "auth-required: sign challenge"]);
        }
        return;
      }
      if (frame[0] === "REQ") {
        const subId = String(frame[1]);
        const filter = (frame[2] ?? {}) as Record<string, unknown>;
        const count = (requestCounts.get(mode) ?? 0) + 1;
        requestCounts.set(mode, count);
        if (mode === "restore" && count === 1) {
          socket.close(1012, "subscription interrupted");
          return;
        }
        if (mode === "closed-frame") {
          send(socket, ["CLOSED", subId, "restricted: test closure"]);
          return;
        }
        const found = matching(stored.get(mode) ?? [], filter);
        const delivered = mode === "reordered" ? [...found].reverse() : found;
        for (const event of delivered) send(socket, ["EVENT", subId, event]);
        if (mode === "reordered" && delivered.length) send(socket, ["EVENT", subId, delivered[0]]);
        if (mode === "subscriber-disconnect" && count === 1) {
          socket.close(1012, "subscriber interrupted after event")
          return;
        }
        send(socket, ["EOSE", subId]);
      }
    });
  });
  await new Promise<void>((resolve) => server.on("listening", resolve));
  const address = server.address();
  port = typeof address === "object" && address ? address.port : 0;
});

afterAll(async () => {
  for (const socket of sockets) socket.terminate();
  await new Promise<void>((resolve) => server.close(resolve));
});

function signedEvent(recipient = "11".repeat(32), content = "synthetic fault payload", createdAt = Math.floor(Date.now() / 1000)): Event {
  return finalizeEvent({
    kind: 1059,
    created_at: createdAt,
    tags: [["p", recipient], ["expiration", String(createdAt + 3600)]],
    content,
  }, generateSecretKey());
}

async function publish(mode: string, event = signedEvent(), timeoutMs = 150) {
  const pool = new SimplePool();
  const url = modeUrl(mode);
  try {
    return (await publishPerRelay(pool, [url], event as never, undefined, timeoutMs))[0]!;
  } finally {
    pool.close([url]);
  }
}

describe("deterministic local Nostr fault relay", () => {
  it.each(["ok", "delayed", "duplicate-ok", "notice", "malformed", "binary", "fragmented"])(
    "accepts an exact matching OK despite the %s branch",
    async (mode) => {
      expect(await publish(mode)).toMatchObject({ ok: true, state: "OK_TRUE" });
    },
  );

  it("distinguishes explicit rejection, absent/mismatched OK, and socket closure", async () => {
    expect(await publish("reject")).toMatchObject({ ok: false, state: "OK_FALSE", reasonPrefix: "blocked: policy" });
    expect(await publish("no-ok", signedEvent(), 60)).toMatchObject({ ok: false, state: "NO_OK_TIMEOUT" });
    expect(await publish("mismatch", signedEvent(), 60)).toMatchObject({ ok: false, state: "NO_OK_TIMEOUT" });
    expect(await publish("close-before")).toMatchObject({ ok: false, state: "CLOSED" });
    expect(await publish("close-after")).toMatchObject({ ok: false, state: "CLOSED" });
  });

  it("handles a real NIP-42 challenge and classifies stale authentication separately", async () => {
    const authKey = generateSecretKey();
    for (const [mode, expected] of [["auth", "OK_TRUE"], ["stale-auth", "AUTH_ERROR"]] as const) {
      const pool = new SimplePool();
      const url = modeUrl(mode);
      try {
        const [result] = await publishPerRelay(pool, [url], signedEvent() as never, authKey, 300);
        expect(result?.state, JSON.stringify(result)).toBe(expected);
      } finally {
        pool.close([url]);
      }
    }
  });

  it("classifies relay message-size and created_at policy failures", async () => {
    expect(await publish("message-size", signedEvent(undefined, "x".repeat(1024)))).toMatchObject({ ok: false, state: "OK_FALSE" });
    expect(await publish("created-at", signedEvent(undefined, "old", 1))).toMatchObject({ ok: false, state: "OK_FALSE" });
  });

  it("reads back an event stored before the publisher disconnected", async () => {
    const recipientKey = generateSecretKey();
    const recipient = getPublicKey(recipientKey);
    const event = signedEvent(recipient);
    expect(await publish("store-disconnect", event)).toMatchObject({ ok: false, state: "CLOSED" });
    const pool = new SimplePool();
    const url = modeUrl("store-disconnect");
    try {
      const found = await queryRelayWithAuth(pool, url, { kinds: [1059], "#p": [recipient], since: 0 }, recipientKey, 100);
      expect(found.map((item) => item.id)).toContain(event.id);
    } finally {
      pool.close([url]);
    }
  });

  it("restores a subscription after a deterministic disconnect", async () => {
    const key = generateSecretKey();
    const recipient = getPublicKey(key);
    const event = signedEvent(recipient);
    stored.set("restore", [event]);
    requestCounts.set("restore", 0);
    const firstPool = new SimplePool();
    await expect(queryRelayWithAuth(firstPool, modeUrl("restore"), { kinds: [1059], "#p": [recipient], since: 0 }, key, 80)).resolves.toEqual([]);
    firstPool.close([modeUrl("restore")]);
    const secondPool = new SimplePool();
    try {
      const found = await queryRelayWithAuth(secondPool, modeUrl("restore"), { kinds: [1059], "#p": [recipient], since: 0 }, key, 100);
      expect(found.map((item) => item.id)).toEqual([event.id]);
    } finally {
      secondPool.close([modeUrl("restore")]);
    }
  });

  it("deduplicates and orders replayed relay events", async () => {
    const key = generateSecretKey();
    const recipient = getPublicKey(key);
    const older = signedEvent(recipient, "older", 1_700_000_000);
    const newer = signedEvent(recipient, "newer", 1_700_000_100);
    stored.set("reordered", [older, newer]);
    const pool = new SimplePool();
    const url = modeUrl("reordered");
    try {
      const found = await queryRelayWithAuth(pool, url, { kinds: [1059], "#p": [recipient], since: 0 }, key, 100);
      expect(found.map((item) => item.id)).toEqual([older.id, newer.id]);
    } finally {
      pool.close([url]);
    }
  });

  it("retains an event seen before subscriber disconnect and catches up cleanly", async () => {
    const key = generateSecretKey();
    const recipient = getPublicKey(key);
    const event = signedEvent(recipient);
    stored.set("subscriber-disconnect", [event]);
    requestCounts.set("subscriber-disconnect", 0);
    const first = new SimplePool();
    const url = modeUrl("subscriber-disconnect");
    const partial = await queryRelayWithAuth(first, url, { kinds: [1059], "#p": [recipient], since: 0 }, key, 100);
    first.close([url]);
    expect(partial.map((item) => item.id)).toEqual([event.id]);
    const second = new SimplePool();
    try {
      const recovered = await queryRelayWithAuth(second, url, { kinds: [1059], "#p": [recipient], since: 0 }, key, 100);
      expect(recovered.map((item) => item.id)).toEqual([event.id]);
    } finally {
      second.close([url]);
    }
  });

  it("treats a relay CLOSED subscription frame as an empty safe result", async () => {
    const key = generateSecretKey();
    const pool = new SimplePool();
    const url = modeUrl("closed-frame");
    try {
      await expect(queryRelayWithAuth(pool, url, { kinds: [1059], "#p": [getPublicKey(key)], since: 0 }, key, 100)).resolves.toEqual([]);
    } finally {
      pool.close([url]);
    }
  });

  it("drops oversized incoming events before application processing", async () => {
    const key = generateSecretKey();
    const recipient = getPublicKey(key);
    stored.set("oversized-query", [signedEvent(recipient, "x".repeat(MAX_RELAY_EVENT_BYTES + 1))]);
    const pool = new SimplePool();
    const url = modeUrl("oversized-query");
    try {
      const found = await queryRelayWithAuth(pool, url, { kinds: [1059], "#p": [recipient], since: 0 }, key, 200);
      expect(found).toEqual([]);
    } finally {
      pool.close([url]);
    }
  });

  it("computes one-success quorum and preserves three different all-fail outcomes", async () => {
    const event = signedEvent();
    const onePool = new SimplePool();
    const oneUrls = [modeUrl("ok"), modeUrl("reject"), modeUrl("close-after")];
    try {
      await expect(publishQuorum(onePool, oneUrls, event as never, 1)).resolves.toEqual([modeUrl("ok")]);
    } finally {
      onePool.close(oneUrls);
    }
    const allPool = new SimplePool();
    const allUrls = [modeUrl("reject"), modeUrl("close-after"), modeUrl("mismatch")];
    try {
      const outcomes = await publishPerRelay(allPool, allUrls, event as never, undefined, 70);
      expect(outcomes.map((item) => item.state).sort()).toEqual(["CLOSED", "NO_OK_TIMEOUT", "OK_FALSE"]);
    } finally {
      allPool.close(allUrls);
    }
  });
});
