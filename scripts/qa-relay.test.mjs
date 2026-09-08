import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { startRelayHarness } from './qa-relay.mjs';
const require = createRequire(new URL('../chrome/package.json', import.meta.url));
const { WebSocket } = require('ws');
const { finalizeEvent, generateSecretKey } = require('nostr-tools/pure');

test('persistent relay applies inclusive filters/caps, duplicates, loss after OK, and interruption', async () => {
  const harness = await startRelayHarness({ allowedHosts: ['127.0.0.1'] });
  const socket = new WebSocket(`ws://127.0.0.1:${harness.relayPort}`);
  const messages = []; socket.on('message', data => messages.push(JSON.parse(data.toString())));
  const until = async predicate => { for (let i = 0; i < 200; i++) { if (predicate()) return; await new Promise(resolve => setTimeout(resolve, 5)); } assert.fail('relay condition timed out'); };
  await new Promise(resolve => socket.once('open', resolve));
  const key = generateSecretKey();
  const events = Array.from({ length: 300 }, (_, index) => finalizeEvent({ kind: 1059, created_at: 1700000000 + Math.floor(index / 100), tags: [['p', 'a'.repeat(64)]], content: `synthetic-${index}` }, key));
  try {
    events.forEach(event => socket.send(JSON.stringify(['EVENT', event])));
    await until(() => messages.filter(frame => frame[0] === 'OK').length === 300);
    const relay = harness.relays.get('127.0.0.1');
    relay.policy = { cap: 64, duplicate: true, reverse: true };
    socket.send(JSON.stringify(['REQ', 'capped', { since: 1700000001, until: 1700000001, limit: 256 }]));
    await until(() => messages.some(frame => frame[0] === 'EOSE' && frame[1] === 'capped'));
    const selected = messages.filter(frame => frame[0] === 'EVENT' && frame[1] === 'capped').map(frame => frame[2]);
    assert.equal(selected.length, 128); assert.equal(new Set(selected.map(event => event.id)).size, 64);
    assert.ok(selected.every(event => event.created_at === 1700000001));
    relay.policy = { dropRecipients: ['b'.repeat(64)] };
    const lost = finalizeEvent({ kind: 1059, created_at: 1700000003, tags: [['p', 'b'.repeat(64)]], content: 'synthetic-lost' }, key);
    socket.send(JSON.stringify(['EVENT', lost]));
    await until(() => messages.some(frame => frame[0] === 'OK' && frame[1] === lost.id));
    assert.ok(!relay.events.has(lost.id));
    relay.policy = { interruptAfterHistory: true };
    socket.send(JSON.stringify(['REQ', 'interrupted', { limit: 1 }]));
    await until(() => socket.readyState === 3);
    assert.equal(relay.events.size, 300);
    assert.equal(harness.summary().invalidEvents, 0);
  } finally { socket.terminate(); await harness.close(); }
});
