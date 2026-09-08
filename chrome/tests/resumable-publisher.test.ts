import { expect, it, vi } from "vitest";
import { publishFragments, type PublicationSnapshot } from "../src/background/resumable-publisher.js";

it("resumes a durably checkpointed prefix after interruption", async () => {
  const snapshot: PublicationSnapshot = { relays: ["a", "b"], payloadCount: 4, progress: {} };
  const sends: string[] = [];
  let alive = true;
  const run = () => publishFragments({ snapshot, now: () => 1000, current: async () => alive,
    send: async (i, r) => { sends.push(`${i}${r}`); return { state: "OK_TRUE", at: 1000 }; },
    checkpoint: async (i, r, outcome) => { (snapshot.progress[String(i)] ??= {})[r] = outcome; if (i === 1) alive = false; return true; },
  });
  expect(await run()).toBe(false);
  alive = true;
  await run();
  expect(sends).toHaveLength(8);
  expect(new Set(sends).size).toBe(8);
});
it("a delivered/discarded item cannot be resurrected by a delayed publisher", async () => {
  let durable = true;
  let writes = 0;
  await publishFragments({ snapshot: { relays: ["a"], payloadCount: 3, progress: {} }, current: async () => durable,
    send: async () => { durable = false; return { state: "OK_TRUE", at: 1 }; },
    checkpoint: async () => { if (!durable) return false; writes++; return true; },
  });
  expect(writes).toBe(0);
});
it("stale accepted coverage is retransmitted; fresh coverage is reused", async () => {
  const sends: string[] = [];
  await publishFragments({ snapshot: { relays: ["a", "b"], payloadCount: 1,
    progress: { "0": { a: { state: "OK_TRUE", at: 0 }, b: { state: "OK_TRUE", at: 999_999 } } } },
    now: () => 1_000_000, current: async () => true,
    send: async (_i, r) => { sends.push(r); return { state: "OK_TRUE", at: 1_000_000 }; }, checkpoint: async () => true,
  });
  expect(sends).toEqual(["a"]);
});

it("healthy relays finish all fragments while a redundant relay is still waiting on its manifest", async () => {
  let release!: () => void;
  const blocked = new Promise<void>(resolve => { release = resolve; });
  const accepted: string[] = [];
  let alive = true;
  const run = publishFragments({
    snapshot: { relays: ["healthy-a", "healthy-b", "slow"], payloadCount: 4, progress: {} },
    current: async () => alive,
    send: async (i, relay) => {
      if (relay === "slow") await blocked;
      return { state: "OK_TRUE", at: 1 };
    },
    checkpoint: async (i, relay) => { if (!alive) return false; accepted.push(`${relay}:${i}`); return true; },
  });
  try {
    await vi.waitFor(() => expect(accepted.filter(value => !value.startsWith("slow"))).toHaveLength(8), { timeout: 250 });
    alive = false; // authenticated device ACK wins without waiting for redundancy
  } finally { release(); await run; }
});

it("explicit receipt recovery refreshes only the manifest despite recent relay acceptance", async () => {
  const sent: string[] = [];
  const accepted = { a: { state: "OK_TRUE", at: 999 }, b: { state: "OK_TRUE", at: 999 } };
  await publishFragments({
    snapshot: { relays: ["a", "b"], payloadCount: 3, progress: { "0": accepted, "1": accepted, "2": accepted } },
    refreshManifest: true, now: () => 1000, current: async () => true,
    send: async (i, relay) => { sent.push(`${i}:${relay}`); return { state: "OK_TRUE", at: 1000 }; },
    checkpoint: async () => true,
  });
  expect(sent.sort()).toEqual(["0:a", "0:b"]);
});
