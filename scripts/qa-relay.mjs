import http from 'node:http';
import https from 'node:https';
import net from 'node:net';
import { randomBytes } from 'node:crypto';
import { createRequire } from 'node:module';
const require = createRequire(new URL('../chrome/package.json', import.meta.url));
const { WebSocketServer } = require('ws');
const { verifyEvent } = require('nostr-tools/pure');

export function matches(event, filter) {
  if (filter.ids && !filter.ids.some(id => event.id.startsWith(id))) return false;
  if (filter.authors && !filter.authors.some(id => event.pubkey.startsWith(id))) return false;
  if (filter.kinds && !filter.kinds.includes(event.kind)) return false;
  if (filter.since !== undefined && event.created_at < filter.since) return false;
  if (filter.until !== undefined && event.created_at > filter.until) return false;
  for (const [key, values] of Object.entries(filter)) {
    if (key.startsWith('#') && !event.tags.some(tag => tag[0] === key.slice(1) && values.includes(tag[1]))) return false;
  }
  return true;
}

const listen = server => new Promise((resolve, reject) => {
  server.once('error', reject); server.listen(0, '127.0.0.1', () => resolve(server.address().port));
});
const close = server => new Promise(resolve => server.close(resolve));
const json = (response, data, status = 200) => {
  const bytes = Buffer.from(JSON.stringify(data));
  response.writeHead(status, { 'Content-Type': 'application/json', 'Content-Length': bytes.length, 'Connection': 'close' });
  response.end(bytes);
};

/** Loopback-only synthetic relay. No endpoint exports retained event bodies. */
export async function startRelayHarness({ tls, allowedHosts = ['qa.reader.test'], fixtures = new Map() } = {}) {
  const token = randomBytes(24).toString('hex');
  const relays = new Map();
  const connections = new Set();
  const tunnels = new Set();
  const timers = new Set();
  const androidCommands = [];
  const androidResults = new Map();
  const metrics = { connects: 0, peakConnections: 0, bytesIn: 0, bytesOut: 0, publications: 0, subscriptions: 0, invalidEvents: 0 };
  const state = name => {
    if (!relays.has(name)) relays.set(name, { events: new Map(), bytes: 0, policy: {}, subscribers: new Map() });
    return relays.get(name);
  };
  const later = (fn, ms) => {
    if (!ms) return fn();
    const timer = setTimeout(() => { timers.delete(timer); fn(); }, Math.min(ms, 120000)); timers.add(timer);
  };
  const send = (socket, frame, delay = 0) => later(() => {
    if (socket.readyState !== 1) return;
    const raw = JSON.stringify(frame); metrics.bytesOut += Buffer.byteLength(raw); socket.send(raw);
  }, delay);
  const relayServer = (tls ? https : http).createServer(tls ?? {}, (request, response) => {
    const fixture = fixtures.get(`${request.headers.host}${request.url}`);
    if (fixture) {
      response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' }); response.end(fixture); return;
    }
    json(response, { name: 'Reader isolated fault relay', supported_nips: [1, 40, 42], limitation: { max_message_length: 2097152, max_limit: 4096 } });
  });
  const ws = new WebSocketServer({ server: relayServer, maxPayload: 2097152 });
  ws.on('connection', (socket, request) => {
    const host = request.headers.host?.split(':')[0];
    const name = `${host}${new URL(request.url, 'http://qa.invalid').pathname.replace(/\/$/, '')}`;
    if (!allowedHosts.includes(host)) { socket.close(1008); return; }
    const relay = state(name);
    if (relay.policy.offline) { socket.close(1013); return; }
    connections.add(socket); metrics.connects++; metrics.peakConnections = Math.max(metrics.peakConnections, connections.size);
    const subscriptions = new Map();
    relay.subscribers.set(socket, subscriptions);
    let authenticated = false;
    const challenge = randomBytes(24).toString('hex');
    if (relay.policy.auth) send(socket, ['AUTH', challenge]);
    socket.on('close', () => { connections.delete(socket); relay.subscribers.delete(socket); });
    socket.on('error', () => {});
    socket.on('message', (data, binary) => {
      if (binary) return;
      metrics.bytesIn += data.length;
      let frame;
      try { frame = JSON.parse(data.toString()); if (!Array.isArray(frame)) return; } catch { return; }
      const policy = relay.policy;
      if (frame[0] === 'AUTH') {
        const event = frame[1];
        const expected = `wss://${host}${request.url === '/' ? '/' : request.url}`;
        const valid = event?.kind === 22242 && verifyEvent(event) && event.content === '' && event.tags.length === 2 &&
          event.tags.some(tag => tag[0] === 'challenge' && tag[1] === challenge) &&
          event.tags.some(tag => tag[0] === 'relay' && tag[1].replace(/\/$/, '') === expected.replace(/\/$/, '')) &&
          Math.abs(event.created_at - Date.now() / 1000) < 120;
        authenticated = valid && policy.auth !== 'reject';
        send(socket, ['OK', event?.id ?? '', authenticated, authenticated ? 'authenticated' : 'restricted: authentication']);
        if (policy.auth === 'change') send(socket, ['AUTH', randomBytes(24).toString('hex')]);
        return;
      }
      if (frame[0] === 'CLOSE') { subscriptions.delete(frame[1]); return; }
      if (frame[0] === 'EVENT') {
        const event = frame[1]; metrics.publications++;
        if (policy.auth && !authenticated) { send(socket, ['OK', event?.id ?? '', false, 'auth-required: sign']); return; }
        if (!event || !verifyEvent(event)) { metrics.invalidEvents++; send(socket, ['OK', event?.id ?? '', false, 'invalid: signature']); return; }
        if (policy.ok === false) { send(socket, ['OK', event.id, false, 'blocked: controlled rejection']); return; }
        const expiration = Number(event.tags.find(tag => tag[0] === 'expiration')?.[1] ?? Infinity);
        if (expiration < Date.now() / 1000) { send(socket, ['OK', event.id, false, 'invalid: expired']); return; }
        const discard = (policy.dropRecipients ?? []).some(recipient => event.tags.some(tag => tag[0] === 'p' && tag[1] === recipient));
        if (!discard && !relay.events.has(event.id)) {
          const bytes = Buffer.byteLength(JSON.stringify(event));
          if (relay.events.size >= 100000 || relay.bytes + bytes > 256 * 1024 * 1024) { send(socket, ['OK', event.id, false, 'restricted: capacity']); return; }
          relay.events.set(event.id, event); relay.bytes += bytes;
          for (const [subscriber, filters] of relay.subscribers) for (const [subId, values] of filters) {
            if (values.some(filter => matches(event, filter)) && !policy.pauseDelivery) {
              send(subscriber, ['EVENT', subId, event], policy.deliverDelayMs);
              if (policy.duplicate) send(subscriber, ['EVENT', subId, event], policy.deliverDelayMs);
            }
          }
        }
        if (policy.ok !== null) send(socket, ['OK', event.id, true, 'stored'], policy.okDelayMs);
        if (policy.interruptAfterPublish) socket.terminate();
        return;
      }
      if (frame[0] === 'REQ') {
        metrics.subscriptions++;
        const subId = String(frame[1]);
        const filters = frame.slice(2, 4);
        if (policy.auth && !authenticated) { send(socket, ['CLOSED', subId, 'auth-required: sign']); return; }
        if (policy.closed) { send(socket, ['CLOSED', subId, 'restricted: controlled closure']); return; }
        subscriptions.set(subId, filters);
        if (policy.notice) send(socket, ['NOTICE', 'controlled test notice']);
        const found = new Map();
        for (const filter of filters) {
          const sorted = [...relay.events.values()].filter(event => matches(event, filter))
            .sort((a, b) => b.created_at - a.created_at || a.id.localeCompare(b.id));
          const limited = sorted.slice(0, Math.min(filter.limit ?? 4096, policy.cap ?? 4096, 4096));
          limited.forEach(event => found.set(event.id, event));
        }
        let events = [...found.values()];
        if (policy.reverse) events.reverse();
        if (!policy.pauseDelivery) events.forEach(event => {
          send(socket, ['EVENT', subId, event], policy.deliverDelayMs);
          if (policy.duplicate) send(socket, ['EVENT', subId, event], policy.deliverDelayMs);
        });
        if (policy.interruptAfterHistory) { later(() => socket.terminate(), policy.deliverDelayMs); return; }
        if (!policy.noEose) send(socket, ['EOSE', subId], policy.deliverDelayMs);
      }
    });
  });
  const relayPort = await listen(relayServer);
  const proxy = http.createServer((_, response) => json(response, { error: 'CONNECT only' }, 405));
  proxy.on('connect', (request, socket, head) => {
    const host = request.url.split(':')[0];
    if (!allowedHosts.includes(host) || request.url.split(':')[1] !== '443') { socket.end('HTTP/1.1 403 Forbidden\r\n\r\n'); return; }
    const upstream = net.connect(relayPort, '127.0.0.1');
    tunnels.add(socket); tunnels.add(upstream);
    upstream.once('connect', () => {
      socket.write('HTTP/1.1 200 Connection Established\r\n\r\n');
      if (head.length) upstream.write(head); socket.pipe(upstream).pipe(socket);
    });
    socket.on('error', () => upstream.destroy()); upstream.on('error', () => socket.destroy());
    socket.on('close', () => { tunnels.delete(socket); upstream.destroy(); });
    upstream.on('close', () => { tunnels.delete(upstream); socket.destroy(); });
  });
  const proxyPort = await listen(proxy);
  const summary = () => ({ ...metrics, activeConnections: connections.size, relays: [...relays].map(([name, relay]) => ({ name, retainedEvents: relay.events.size, retainedBytes: relay.bytes, policy: { ...relay.policy, dropRecipients: (relay.policy.dropRecipients ?? []).length } })) });
  const control = http.createServer(async (request, response) => {
    if (request.method === 'GET' && request.url.startsWith('/fixtures/')) {
      const fixture = fixtures.get(request.url.slice('/fixtures/'.length));
      if (!fixture) { json(response, { error: 'Fixture unavailable' }, 404); return; }
      response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' }); response.end(fixture); return;
    }
    if (request.headers['x-reader-qa'] !== token) { json(response, { error: 'Not authorized' }, 403); return; }
    const chunks = []; let bytes = 0;
    for await (const chunk of request) { bytes += chunk.length; if (bytes > 2 * 1024 * 1024) { json(response, {}, 413); return; } chunks.push(chunk); }
    let body;
    try { body = JSON.parse(Buffer.concat(chunks).toString() || '{}'); } catch { json(response, {}, 400); return; }
    if (request.url === '/android/next') { json(response, androidCommands.shift() ?? {}); return; }
    if (request.url === '/android/result') { androidResults.set(body.id, body.result); json(response, {}); return; }
    if (request.url === '/android/enqueue') { androidCommands.push(body); json(response, {}); return; }
    if (request.url === '/android/results') { json(response, androidResults.get(body.id) ?? null); return; }
    if (request.url === '/summary') { json(response, summary()); return; }
    if (request.url === '/policy') {
      for (const name of body.names ?? [...relays.keys()]) {
        const relay = state(name); relay.policy = { ...relay.policy, ...body.policy };
        if (body.interrupt || body.policy?.offline) for (const socket of relay.subscribers.keys()) socket.terminate();
      }
      json(response, {}); return;
    }
    if (request.url === '/delete') {
      let deleted = 0;
      for (const name of body.names ?? [...relays.keys()]) {
        const relay = state(name);
        for (const [id, event] of relay.events) if (matches(event, body.filter ?? {})) {
          relay.bytes -= Buffer.byteLength(JSON.stringify(event)); relay.events.delete(id); deleted++;
        }
      }
      json(response, { deleted }); return;
    }
    json(response, { error: 'Unknown QA endpoint' }, 404);
  });
  const controlPort = await listen(control);
  return { token, relayPort, proxyPort, controlPort, relays, fixtures, summary, androidCommands, androidResults,
    async close() {
      for (const timer of timers) clearTimeout(timer);
      for (const socket of connections) socket.terminate();
      for (const tunnel of tunnels) tunnel.destroy();
      await Promise.all([close(control), close(proxy), close(relayServer)]);
    },
  };
}
