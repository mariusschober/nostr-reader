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
