import { SimplePool } from "nostr-tools/pool";
import { generateSecretKey, getPublicKey, finalizeEvent } from "nostr-tools/pure";
import * as nip44 from "nostr-tools/nip44";
const relays = ["wss://relay.damus.io", "wss://nos.lol", "wss://relay.nostr.band", "wss://offchain.pub"];
const pool = new SimplePool();
const a = generateSecretKey();
const b = generateSecretKey();
const results = [];
// 1. connect + NIP-11
for (const url of relays) {
  const r = { url, connect: false, nip11: false, write1059: false, readBack: false };
  try {
    const info = await fetch(url.replace(/^ws/, "http"), { headers: { Accept: "application/nostr+json" }, signal: AbortSignal.timeout(8000) })
      .then((x) => (x.ok ? x.json() : null)).catch(() => null);
    r.nip11 = !!info;
    const relay = await pool.ensureRelay(url);
    r.connect = !!relay;
    // 2. publish a minimal NIP-59-style wrapper (kind 1059) from throwaway key
    const now = Math.floor(Date.now() / 1000);
    const rumor = { kind: 30078, created_at: now, tags: [], content: JSON.stringify({ protocol: "reader/1", type: "smoke", note: "harmless fixture" }), pubkey: getPublicKey(a) };
    const seal = finalizeEvent({ kind: 13, created_at: now, tags: [], content: nip44.encrypt(JSON.stringify(rumor), nip44.getConversationKey(a, getPublicKey(b))) }, a);
    const wk = generateSecretKey();
    const wrap = finalizeEvent({ kind: 1059, created_at: now - 100, tags: [["expiration", String(now + 600)]], content: nip44.encrypt(JSON.stringify(seal), nip44.getConversationKey(wk, getPublicKey(b))) }, wk);
    try {
      await Promise.race([pool.publish([url], wrap), new Promise((_, rej) => setTimeout(() => rej(new Error("timeout")), 10000))]);
      r.write1059 = true;
    } catch (e) { r.writeErr = String(e).slice(0, 100); }
    results.push(r);
  } catch (e) { r.err = String(e).slice(0, 100); results.push(r); }
}
console.log(JSON.stringify({ when: new Date().toISOString(), results }, null, 2));
pool.close(relays);
process.exit(0);
