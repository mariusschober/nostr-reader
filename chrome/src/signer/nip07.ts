// Optional NIP-07 provenance proof contract. Runtime signing is intentionally
// not exposed to page-world code; a future trusted extension page may use this
// exact template and must verify the returned event field-for-field.
import { verifyEvent } from "nostr-tools/pure";

export interface ProofTemplate { kind: number; content: string; tags: string[][]; created_at: number; pubkey: string }

export function buildDeviceAuthorization(opts: {
  devicePubkey: string; externalPubkey: string; issuedAt: number; expiresAt: number; nonce: string;
}): ProofTemplate {
  return {
    kind: 30078,
    created_at: opts.issuedAt,
    pubkey: opts.externalPubkey,
    tags: [["d", "reader-device-auth"], ["device", opts.devicePubkey], ["expires", String(opts.expiresAt)], ["nonce", opts.nonce]],
    content: JSON.stringify({ protocol: "reader/2", type: "device-auth", device: opts.devicePubkey, scope: "reader-provenance" }),
  };
}

export function verifyProof(tpl: ProofTemplate, signed: Record<string, unknown>): boolean {
  if (signed["kind"] !== tpl.kind) return false;
  if (signed["content"] !== tpl.content) return false;
  if (signed["pubkey"] !== tpl.pubkey) return false;
  if (JSON.stringify(signed["tags"]) !== JSON.stringify(tpl.tags)) return false;
  try { return verifyEvent(signed as never); } catch { return false; }
}
