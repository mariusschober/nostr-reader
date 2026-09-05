import { describe, expect, it } from "vitest";
import {
  cancelActivePairingSessions,
  isActivePairingState,
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
    expect(PAIRED_CHANNEL_STORAGE_KEYS).toEqual(["channelPubkey", "relays", "protocolVersion"]);
    expect(PAIRED_CHANNEL_STORAGE_KEYS).not.toContain("deviceSeckey");
    expect(PAIRED_CHANNEL_STORAGE_KEYS).not.toContain("customRelays");
  });
});
