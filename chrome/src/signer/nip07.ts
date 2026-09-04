// Isolated-world NIP-07 client. Builds the EXACT proof template, verifies the
// returned event field-for-field (kind/content/tags/pubkey/id/sig). The page
// can never request arbitrary signatures through this path.
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
    content: JSON.stringify({ protocol: "reader/1", type: "device-auth", device: opts.devicePubkey, scope: "reader-provenance" }),
  };
}

export function verifyProof(tpl: ProofTemplate, signed: Record<string, unknown>): boolean {
  if (signed["kind"] !== tpl.kind) return false;
  if (signed["content"] !== tpl.content) return false;
  if (signed["pubkey"] !== tpl.pubkey) return false;
  if (JSON.stringify(signed["tags"]) !== JSON.stringify(tpl.tags)) return false;
  try { return verifyEvent(signed as never); } catch { return false; }
}

export function requestViaBridge(method: string, event?: ProofTemplate): Promise<Record<string, unknown>> {
  return new Promise((resolve, reject) => {
    const id = Math.random().toString(36).slice(2);
    const onMsg = (ev: MessageEvent) => {
      const d = ev.data as { ns?: string; id?: string; ok?: boolean } | null;
      if (!d || d.ns !== "reader-nip07" || d.id !== id) return;
      window.removeEventListener("message", onMsg);
      if (d.ok) resolve(d as unknown as Record<string, unknown>);
      else reject(new Error("signer refused"));
    };
    window.addEventListener("message", onMsg);
    window.postMessage({ ns: "reader-nip07", id, method, event }, "*");
    setTimeout(() => { window.removeEventListener("message", onMsg); reject(new Error("signer timeout")); }, 60000);
  });
}
