// MAIN-world NIP-07 bridge endpoint. Runs in page context; talks ONLY to our
// isolated content script via narrow validated messages. Never auto-signs.
window.addEventListener("message", async (ev: MessageEvent) => {
  if (ev.source !== window || !ev.data || (ev.data as { ns?: string }).ns !== "reader-nip07") return;
  const { id, method, event } = ev.data as { id: string; method: string; event?: Record<string, unknown> };
  try {
    const nostr = (window as unknown as { nostr?: { getPublicKey(): Promise<string>; signEvent(e: unknown): Promise<unknown> } }).nostr;
    if (!nostr) throw new Error("no browser signer");
    if (method === "getPublicKey") {
      const pubkey = await nostr.getPublicKey();
      window.postMessage({ ns: "reader-nip07", id, ok: true, pubkey }, "*");
    } else if (method === "signProof" && event) {
      const signed = await nostr.signEvent(event) as Record<string, unknown>;
      // Structural echo check happens in the isolated world; re-post raw.
      window.postMessage({ ns: "reader-nip07", id, ok: true, event: signed }, "*");
    } else {
      throw new Error("unsupported method");
    }
  } catch (e) {
    window.postMessage({ ns: "reader-nip07", id, ok: false, error: String(e).slice(0, 200) }, "*");
  }
});
