import { normalizePairingRelay } from "./pairing.js";

/**
 * Public bootstrap relays shared with Android via shared/default-relays.json.
 * Keep this list ordered and byte-for-byte equivalent to the Android constant;
 * the cross-platform contract test enforces that invariant.
 */
export const DEFAULT_RELAYS = [
  "wss://nos.lol",
  "wss://relay.primal.net",
  "wss://relay.nostr.net",
  "wss://nostr.oxtr.dev",
  "wss://offchain.pub",
  "wss://nostr-pub.wellorder.net",
] as const;

export const RELAY_WRITE_QUORUM = 2;
export const MAX_CUSTOM_RELAYS = 2;

const defaultRelaySet = new Set<string>(DEFAULT_RELAYS);

/** Validate the user-owned extension to the fixed six-relay bootstrap set. */
export function normalizeCustomRelays(value: unknown): string[] {
  if (!Array.isArray(value) || !value.every((relay) => typeof relay === "string")) {
    throw new Error("Custom relays must be a list of secure WebSocket URLs.");
  }
  const normalized = [...new Set(value.map((relay) => normalizePairingRelay(relay.trim())))]
    .filter((relay) => !defaultRelaySet.has(relay));
  if (normalized.length > MAX_CUSTOM_RELAYS) {
    throw new Error(`You can add up to ${MAX_CUSTOM_RELAYS} custom relays.`);
  }
  return normalized;
}

export function configuredRelays(customRelays: readonly string[]): string[] {
  return [...DEFAULT_RELAYS, ...customRelays];
}

export function sameRelayOrder(left: readonly string[], right: readonly string[]): boolean {
  return left.length === right.length && left.every((relay, index) => relay === right[index]);
}

/**
 * Revalidate a durable channel relay set before any network use.
 *
 * A paired Reader channel always contains every fixed default in canonical
 * order, followed by zero to two normalized custom relays. Persisted state is
 * an input boundary: never let missing/corrupt state turn the write quorum
 * into zero or silently select a different relay set.
 */
export function validatePairedRelaySet(value: unknown): string[] {
  if (!Array.isArray(value) || !value.every((relay) => typeof relay === "string")) {
    throw new Error("paired relay set is unavailable");
  }
  if (value.length < DEFAULT_RELAYS.length || value.length > DEFAULT_RELAYS.length + MAX_CUSTOM_RELAYS) {
    throw new Error("paired relay set has an invalid size");
  }
  const customRelays = normalizeCustomRelays(value.slice(DEFAULT_RELAYS.length));
  const canonical = configuredRelays(customRelays);
  if (!sameRelayOrder(value, canonical)) throw new Error("paired relay set does not match the authenticated channel");
  return canonical;
}

/**
 * Both phases use the exact set chosen by the owner on a trusted settings
 * page and committed into this locally-created pairing transcript. An incoming
 * response never supplies a hostname. The envelope must still authenticate.
 */
export function pairingReadRelays(
  phase: "bootstrap_response" | "authenticated_completion",
  value: unknown,
): string[] {
  const relays = validatePairedRelaySet(value);
  return relays;
}
