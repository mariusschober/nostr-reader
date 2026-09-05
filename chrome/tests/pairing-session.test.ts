import { describe, expect, it } from "vitest";
import {
  cancelActivePairingSessions,
  isActivePairingState,
  pairingAckRetryDue,
  PAIR_ACK_RETRY_SECS,
  PAIRED_CHANNEL_STORAGE_KEYS,
  stripPairingSecret,
} from "../src/protocol/pairing-session.js";

describe("pairing session lifecycle hygiene", () => {
  it("classifies only recoverable pairing phases as active", () => {
    expect(["waiting_response", "response_validated", "waiting_completion"].every((state) => (
      isActivePairingState(state as never)
    ))).toBe(true);
    expect(["complete", "expired", "cancelled", "superseded"].some((state) => (
      isActivePairingState(state as never)
    ))).toBe(false);
  });

  it("physically removes a bootstrap secret instead of storing undefined", () => {
    const safe = stripPairingSecret({ state: "complete", pairingSeckey: "secret", sessionId: "s" });
    expect(safe).toEqual({ state: "complete", sessionId: "s" });
    expect(Object.hasOwn(safe, "pairingSeckey")).toBe(false);
  });

  it("disconnect cancels every active session and preserves terminal history", () => {
    const sessions = [
      { state: "waiting_response" as const, pairingSeckey: "one", id: 1 },
      { state: "waiting_completion" as const, pairingSeckey: "two", id: 2 },
      { state: "complete" as const, id: 3 },
    ];
    const cancelled = cancelActivePairingSessions(sessions, "Disconnected.");
    expect(cancelled.map((session) => session.state)).toEqual(["cancelled", "cancelled", "complete"]);
    expect(cancelled.slice(0, 2).every((session) => !Object.hasOwn(session, "pairingSeckey"))).toBe(true);
    expect(cancelled[2]).toBe(sessions[2]);
  });

  it("disconnect removes only channel binding—not identity, preferences, or outbox", () => {
    expect(PAIRED_CHANNEL_STORAGE_KEYS).toEqual([
      "channelPubkey",
      "channelDevicePubkey",
      "relays",
      "channelRelaySetDigest",
      "protocolVersion",
    ]);
    expect(PAIRED_CHANNEL_STORAGE_KEYS).not.toContain("deviceSeckey");
    expect(PAIRED_CHANNEL_STORAGE_KEYS).not.toContain("customRelays");
  });

  it("throttles pairing ACK retries without trusting corrupt future timestamps", () => {
    expect(pairingAckRetryDue(undefined, 100)).toBe(true);
    expect(pairingAckRetryDue(100, 100 + PAIR_ACK_RETRY_SECS - 1)).toBe(false);
    expect(pairingAckRetryDue(100, 100 + PAIR_ACK_RETRY_SECS)).toBe(true);
    expect(pairingAckRetryDue(10_000, 100)).toBe(true);
    expect(() => pairingAckRetryDue(0, -1)).toThrow("invalid pairing retry clock");
  });
});
