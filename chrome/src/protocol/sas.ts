import { sha256HexBytes } from "./core.js";

/**
 * Short authentication string for out-of-band pairing comparison. Both sides
 * derive it from the same public QR transcript; it contains no secret.
 */
export const SAS_WORDS = [
  "amber", "anchor", "apple", "arrow", "aspen", "beacon", "birch", "bloom",
  "breeze", "bridge", "cabin", "cactus", "canyon", "cedar", "cobalt", "comet",
  "coral", "cosmos", "creek", "crown", "dawn", "delta", "ember", "falcon",
  "fern", "fjord", "flame", "forest", "glacier", "granite", "harbor", "hazel",
  "heron", "indigo", "island", "ivory", "jasmine", "juniper", "lagoon", "lantern",
  "maple", "meadow", "meteor", "mint", "nectar", "nova", "oak", "ocean",
  "olive", "orchid", "pearl", "pine", "quartz", "raven", "river", "saffron",
  "silver", "spruce", "summit", "thistle", "timber", "valley", "willow", "zephyr",
] as const;

export interface SasTranscript {
  sessionId: string;
  chromeDevicePubkey: string;
  pairingPubkey: string;
  nonce: string;
}

export async function pairingSas(transcript: SasTranscript): Promise<string> {
  const canonical = [
    "reader-pair-sas/2",
    transcript.sessionId,
    transcript.chromeDevicePubkey,
    transcript.pairingPubkey,
    transcript.nonce,
  ].join("\n");
  const digest = await sha256HexBytes(new TextEncoder().encode(canonical));
  const bytes = digest.match(/.{2}/g) ?? [];
  return Array.from({ length: 6 }, (_, index) => SAS_WORDS[Number.parseInt(bytes[index] ?? "ff", 16) & 63]).join(" ");
}
