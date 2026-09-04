// Permanent regression test: NIP-59 randomizes wrap timestamps into the past,
// so `since = lastSync` LOSES messages. The rolling window must find them.
import { describe, it, expect } from "vitest";
import { syncSince } from "../src/protocol/core.js";

describe("randomized timestamp rule", () => {
  it("finds a wrap created after T but stamped before T", () => {
    const T = 1725400000;
    const wrapCreatedWallClock = T + 100; // actually created after sync
    const wrapCreatedAt = T - 3600; // randomized into the past
    const naiveSince = T;
    const rollingSince = syncSince(T + 100);
    expect(wrapCreatedAt < naiveSince).toBe(true); // naive query misses it
    expect(wrapCreatedAt >= rollingSince).toBe(true); // rolling query finds it
    expect(wrapCreatedWallClock).toBeGreaterThan(T);
  });
});
