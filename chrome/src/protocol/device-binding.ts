import { getPublicKey } from "nostr-tools/pure";
import { schnorr } from "@noble/curves/secp256k1";

export type DeviceBindingState = "unbound" | "legacy" | "bound" | "invalid" | "mismatch";

export interface DeviceBindingInspection {
  state: DeviceBindingState;
  seckey?: Uint8Array;
  pubkey?: string;
}

/** Reject lower-case 32-byte strings that are not liftable BIP-340 public keys. */
export function isValidXOnlyPubkey(value: unknown): value is string {
  if (typeof value !== "string" || !/^[0-9a-f]{64}$/.test(value)) return false;
  try {
    schnorr.utils.lift_x(BigInt(`0x${value}`));
    return true;
  } catch {
    return false;
  }
}

/** Decode a lower-case, in-range secp256k1 scalar from trusted local storage. */
export function decodeStoredSecret(value: unknown): { seckey: Uint8Array; pubkey: string } {
  if (typeof value !== "string" || !/^[0-9a-f]{64}$/.test(value)) {
    throw new Error("stored transport key is invalid");
  }
  const seckey = new Uint8Array(32);
  for (let index = 0; index < 32; index += 1) {
    seckey[index] = Number.parseInt(value.slice(index * 2, index * 2 + 2), 16);
  }
  try {
    return { seckey, pubkey: getPublicKey(seckey) };
  } catch {
    throw new Error("stored transport key is invalid");
  }
}

/**
 * Bind a completed channel to the exact Chrome device identity that completed
 * its authenticated transcript. `legacy` is the one-time migration state for
 * earlier reader/2 channels that predate the explicit public-key marker.
 */
export function inspectDeviceBinding(
  storedSecret: unknown,
  storedChannelDevicePubkey: unknown,
  hasChannelBinding: boolean,
): DeviceBindingInspection {
  let identity: { seckey: Uint8Array; pubkey: string };
  try {
    identity = decodeStoredSecret(storedSecret);
  } catch {
    return { state: hasChannelBinding ? "invalid" : "unbound" };
  }
  if (!hasChannelBinding) return { state: "unbound", ...identity };
  if (storedChannelDevicePubkey === undefined) return { state: "legacy", ...identity };
  if (!isValidXOnlyPubkey(storedChannelDevicePubkey)) return { state: "invalid", ...identity };
  return {
    state: storedChannelDevicePubkey === identity.pubkey ? "bound" : "mismatch",
    ...identity,
  };
}
