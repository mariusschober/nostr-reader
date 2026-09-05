import { describe, expect, it } from "vitest";
import {
  decodeStoredSecret,
  inspectDeviceBinding,
  isValidXOnlyPubkey,
} from "../src/protocol/device-binding.js";

const DEVICE_SECRET = "00".repeat(31) + "01";
const DEVICE_PUBKEY = "79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798";
const OTHER_PUBKEY = decodeStoredSecret("00".repeat(31) + "02").pubkey;

describe("durable Chrome device binding", () => {
  it("decodes a canonical in-range key and derives its exact x-only public key", () => {
    const identity = decodeStoredSecret(DEVICE_SECRET);
    expect(identity.pubkey).toBe(DEVICE_PUBKEY);
    expect(identity.seckey).toHaveLength(32);
  });

  it("distinguishes unbound, legacy-migratable, exact, and mismatched state", () => {
    expect(inspectDeviceBinding(DEVICE_SECRET, undefined, false).state).toBe("unbound");
    expect(inspectDeviceBinding(DEVICE_SECRET, undefined, true).state).toBe("legacy");
    expect(inspectDeviceBinding(DEVICE_SECRET, DEVICE_PUBKEY, true).state).toBe("bound");
    expect(inspectDeviceBinding(DEVICE_SECRET, OTHER_PUBKEY, true).state).toBe("mismatch");
  });

  it("fails closed for missing, malformed, uppercase, zero, or corrupt public state", () => {
    for (const value of [undefined, "ab", "00".repeat(31) + "0A", "00".repeat(32)]) {
      expect(inspectDeviceBinding(value, DEVICE_PUBKEY, true).state).toBe("invalid");
    }
    expect(inspectDeviceBinding(DEVICE_SECRET, "ab", true).state).toBe("invalid");
    expect(isValidXOnlyPubkey(DEVICE_PUBKEY)).toBe(true);
    expect(isValidXOnlyPubkey("ff".repeat(32))).toBe(false);
  });
});
