import { describe, expect, it } from "vitest";
import {
  deliveryCeilingReason,
  MAX_DELIVERY_ATTEMPTS,
  summarizeDeliveryStates,
} from "../src/protocol/delivery-state.js";

describe("durable delivery state", () => {
  it("has independent finite age and attempt ceilings", () => {
    expect(deliveryCeilingReason({ status: "queued", attemptCount: 0, expiresAt: 99 }, 100)).toBe("delivery-age-cap");
    expect(deliveryCeilingReason({ status: "queued", attemptCount: MAX_DELIVERY_ATTEMPTS, expiresAt: 200 }, 100)).toBe("delivery-attempt-cap");
    expect(deliveryCeilingReason({ status: "queued", attemptCount: MAX_DELIVERY_ATTEMPTS - 1, expiresAt: 200 }, 100)).toBeNull();
  });

  it("keeps every transport and endpoint state distinct", () => {
    expect(summarizeDeliveryStates([
      { status: "queued" },
      { status: "relay_accepted" },
      { status: "awaiting_device" },
      { status: "failed" },
    ], 3)).toEqual({ queued: 1, relayAccepted: 1, awaitingDevice: 1, delivered: 3, failed: 1 });
  });
});
