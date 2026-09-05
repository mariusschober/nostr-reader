import { expect, it } from "vitest";
import { scanHistory, type ScanState } from "../src/nostr/history-scan.js";

it("finds ACKs behind more than 256 retained events across persisted restarts", async () => {
  const events = Array.from({ length: 700 }, (_, i) => ({ id: String(i), created_at: i }));
  let state: ScanState | undefined;
  const found = new Set<string>();
  for (let i = 0; i < 100; i++) {
    const result = await scanHistory({ since: 0, until: 800, state, budget: 2,
      query: async w => events.filter(e => e.created_at >= w.since && e.created_at <= w.until).reverse().slice(0, Math.min(64, w.limit)),
      checkpoint: async value => { state = JSON.parse(JSON.stringify(value)); },
    });
    result.events.forEach(e => found.add(e.id));
    if (result.complete) break;
  }
  expect(found.size).toBe(700);
  expect(state?.pending).toEqual([]);
});
it("preserves all timestamp ties when the relay honors a larger limit", async () => {
  const events = Array.from({ length: 300 }, (_, i) => ({ id: String(i), created_at: 5 }));
  const result = await scanHistory({ since: 5, until: 5, budget: 4,
    query: async w => events.slice(0, w.limit), checkpoint: async () => {},
  });
  expect(result.events).toHaveLength(300);
  // An opaque cap remains possible; report conservative coverage.
  expect(result.state.incompleteBuckets).toBe(1);
});
it("reports unrecoverable capped timestamp buckets without claiming completion", async () => {
  const result = await scanHistory({ since: 5, until: 5,
    query: async () => Array.from({ length: 64 }, (_, i) => ({ id: String(i), created_at: 5 })), checkpoint: async () => {},
  });
  expect(result.complete).toBe(false);
  expect(result.state.incompleteBuckets).toBe(1);
});
it("does not checkpoint a failed query as covered", async () => {
  let checkpoints = 0;
  await expect(scanHistory({ since: 0, until: 10,
    query: async () => { throw Error("offline"); }, checkpoint: async () => { checkpoints++; },
  })).rejects.toThrow("offline");
  expect(checkpoints).toBe(0);
});
it("commits authenticated effects before persisting coverage and retries interrupted consumption", async () => {
  const order: string[] = [];
  await expect(scanHistory({ since: 0, until: 10,
    query: async () => [{ id: "ack", created_at: 1 }],
    consume: async () => { order.push("consume"); throw Error("disk full"); },
    checkpoint: async () => { order.push("checkpoint"); },
  })).rejects.toThrow("disk full");
  expect(order).toEqual(["consume"]);
});
