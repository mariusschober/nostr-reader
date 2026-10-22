import { describe, expect, it } from "vitest";
import { createDisconnectPayload, DISCONNECT_TTL_SECS, validateDisconnectPayload } from "../src/protocol/disconnect.js";

const now = 1_800_000_000;

describe("reader/2 disconnect control payload", () => {
  it("creates a bounded payload without keys, content, or transfer identity", () => {
    const payload = createDisconnectPayload(now);
    expect(payload).toEqual({
      protocol: "reader/2",
      type: "disconnect",
      createdAt: now,
      expiresAt: now + DISCONNECT_TTL_SECS,
      reasonCode: "user-disconnect",
    });
    expect(Object.keys(payload).sort()).toEqual(["createdAt", "expiresAt", "protocol", "reasonCode", "type"]);
  });

  it("accepts a fresh payload", () => {
    expect(validateDisconnectPayload(createDisconnectPayload(now), now + 1)).toMatchObject({
      type: "disconnect",
      reasonCode: "user-disconnect",
    });
  });

  it("rejects wrong type, expired, stale, and non-object payloads", () => {
    expect(() => validateDisconnectPayload({ ...createDisconnectPayload(now), type: "ack" }, now)).toThrow();
    expect(() => validateDisconnectPayload({ ...createDisconnectPayload(now), expiresAt: now }, now)).toThrow();
    expect(() => validateDisconnectPayload({ ...createDisconnectPayload(now), createdAt: now - 200_000 }, now)).toThrow();
    expect(() => validateDisconnectPayload({ ...createDisconnectPayload(now), reasonCode: "Bad Reason" }, now)).toThrow();
    expect(() => validateDisconnectPayload("disconnect", now)).toThrow();
    expect(() => validateDisconnectPayload(null, now)).toThrow();
  });

  it("rejects a payload whose declared lifetime is unbounded", () => {
    const payload = { ...createDisconnectPayload(now), expiresAt: now + DISCONNECT_TTL_SECS + 301 };
    expect(() => validateDisconnectPayload(payload, now)).toThrow();
  });
});
