import { SimplePool } from "nostr-tools/pool";
import { finalizeEvent, generateSecretKey, getEventHash, getPublicKey } from "nostr-tools/pure";
import * as nip44 from "nostr-tools/nip44";

const defaults = [
  "wss://nos.lol",
  "wss://relay.primal.net",
  "wss://relay.nostr.net",
  "wss://nostr.oxtr.dev",
  "wss://offchain.pub",
  "wss://nostr-pub.wellorder.net",
];
const relays = process.argv.slice(2).length ? process.argv.slice(2) : defaults;

function bounded(promise, milliseconds, label) {
  return Promise.race([
    promise,
    new Promise((_, reject) => setTimeout(() => reject(new Error(`${label} timeout`)), milliseconds)),
  ]);
}

function cleanError(error) {
  return String(error instanceof Error ? error.message : error)
    .replace(/[0-9a-f]{48,}/gi, "[redacted]")
    .replace(/[A-Za-z0-9_+/=-]{48,}/g, "[redacted]")
    .slice(0, 140);
}

function giftWrap(senderKey, recipientKey) {
  const now = Math.floor(Date.now() / 1000);
  const recipientPubkey = getPublicKey(recipientKey);
  const rumorBase = {
    kind: 30078,
    created_at: now,
    tags: [],
    content: JSON.stringify({ protocol: "reader/2", type: "relay-probe" }),
    pubkey: getPublicKey(senderKey),
  };
  const rumor = { ...rumorBase, id: getEventHash(rumorBase) };
  const seal = finalizeEvent({
    kind: 13,
    created_at: now - 100,
    tags: [],
    content: nip44.encrypt(JSON.stringify(rumor), nip44.getConversationKey(senderKey, recipientPubkey)),
  }, senderKey);
  const wrapKey = generateSecretKey();
  return finalizeEvent({
    kind: 1059,
    created_at: now - 100,
    tags: [["p", recipientPubkey], ["expiration", String(now + 600)]],
    content: nip44.encrypt(JSON.stringify(seal), nip44.getConversationKey(wrapKey, recipientPubkey)),
  }, wrapKey);
}

async function probe(url) {
  const result = {
    url,
    connect: false,
    nip11: false,
    nip42: false,
    write1059: false,
    readBack: false,
    retained: false,
  };
  const pool = new SimplePool();
  const senderKey = generateSecretKey();
  const recipientKey = generateSecretKey();
  const wrap = giftWrap(senderKey, recipientKey);
  try {
    const info = await fetch(url.replace(/^wss:/, "https:").replace(/^ws:/, "http:"), {
      headers: { Accept: "application/nostr+json" },
      signal: AbortSignal.timeout(8000),
    }).then((response) => response.ok ? response.json() : null).catch(() => null);
    result.nip11 = !!info;
    result.nip42 = Array.isArray(info?.supported_nips) && info.supported_nips.includes(42);
    result.paymentRequired = info?.limitation?.payment_required === true;
    result.authRequired = info?.limitation?.auth_required === true;

    const relay = await bounded(pool.ensureRelay(url), 9000, "connect");
    result.connect = relay.connected;
    relay.onnotice = () => undefined;
    let authPromise = null;
    relay._onauth = () => {
      const pending = relay.auth(async (template) => finalizeEvent(template, senderKey));
      authPromise = pending;
      void pending.catch(() => undefined);
    };

    const publish = () => bounded(relay.publish(wrap), 10000, "publish");
    try {
      await publish();
    } catch (firstError) {
      await new Promise((resolve) => setTimeout(resolve, 250));
      if (!authPromise) throw firstError;
      await bounded(authPromise, 3500, "auth");
      await publish();
    }
    result.write1059 = true;

    const filter = { ids: [wrap.id], kinds: [1059], "#p": [getPublicKey(recipientKey)] };
    const firstRead = await bounded(pool.querySync([url], filter, { maxWait: 6000 }), 7500, "readback");
    result.readBack = firstRead.some((event) => event.id === wrap.id);
    await new Promise((resolve) => setTimeout(resolve, 2000));
    const secondRead = await bounded(pool.querySync([url], filter, { maxWait: 6000 }), 7500, "retention");
    result.retained = secondRead.some((event) => event.id === wrap.id);
  } catch (error) {
    result.error = cleanError(error);
  } finally {
    try { pool.close([url]); } catch { /* already closed */ }
  }
  return result;
}

const results = await Promise.all(relays.map(probe));
console.log(JSON.stringify({ when: new Date().toISOString(), results }, null, 2));
process.exit(results.every((result) => result.write1059 && result.readBack && result.retained) ? 0 : 1);
