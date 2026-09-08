import { mkdirSync, writeFileSync, readFileSync } from 'node:fs';
import { resolve, join } from 'node:path';
import { spawn, execFileSync } from 'node:child_process';
import { startRelayHarness } from './qa-relay.mjs';

const [distArg, runArg, chromeBinary] = process.argv.slice(2);
if (!distArg || !runArg || !chromeBinary) throw new Error('Usage: qa-start.mjs DIST RUN_DIRECTORY CHROME_FOR_TESTING');
const dist = resolve(distArg), runDirectory = resolve(runArg);
const relays = JSON.parse(readFileSync(new URL('../shared/default-relays.json', import.meta.url))).relays;
const relayHosts = relays.map(url => new URL(url).hostname);
const hosts = [...relayHosts, 'relay-one.reader.test', 'relay-two.reader.test', 'chatgpt.com'];
mkdirSync(runDirectory, { recursive: true, mode: 0o700 });
const keyPath = join(runDirectory, 'tls-key.pem'), certPath = join(runDirectory, 'tls-cert.pem');
execFileSync('/usr/bin/openssl', ['req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '2',
  '-keyout', keyPath, '-out', certPath, '-subj', '/CN=Reader isolated QA',
  '-addext', `subjectAltName=${hosts.map(host => `DNS:${host}`).join(',')}`], { stdio: ['ignore', 'ignore', 'ignore'] });
const key = readFileSync(keyPath), cert = readFileSync(certPath);
const publicPem = execFileSync('/usr/bin/openssl', ['x509', '-pubkey', '-noout'], { input: cert });
const publicDer = execFileSync('/usr/bin/openssl', ['pkey', '-pubin', '-outform', 'der'], { input: publicPem });
const { createHash } = await import('node:crypto');
const spki = createHash('sha256').update(publicDer).digest('base64');
const fixtures = new Map();
fixtures.set('chatgpt.com/c/reader-qa', `<!doctype html><html><head><meta charset="utf-8"><title>Reader synthetic recovery case - ChatGPT</title><style>body{font:20px system-ui;background:#fffcf0;color:#100f0f;max-width:820px;margin:64px auto;padding:24px}header{font-size:14px;color:#575653}article{margin:32px 0;padding:28px;border:1px solid #cecdc3;border-radius:16px}button{font:inherit}</style></head><body><header>Synthetic local fixture — Reader recovery QA</header><main><article data-message-author-role="assistant"><h2>A small recovery experiment</h2><p>Each saved idea deserves a durable home. This synthetic paragraph tests the real extension capture path, authenticated transport, durable phone storage, and the return receipt.</p><p>Unicode stays exact: café, Καλημέρα, こんにちは, 🌱.</p><div role="group"><button aria-label="Copy response">Copy</button></div></article></main></body></html>`);
const harness = await startRelayHarness({ tls: { key, cert }, allowedHosts: hosts, fixtures });
const profile = join(runDirectory, 'chrome-profile');
const browser = spawn(chromeBinary, [
  `--user-data-dir=${profile}`, `--load-extension=${dist}`, `--disable-extensions-except=${dist}`,
  '--remote-debugging-port=0', '--no-first-run', '--no-default-browser-check', '--disable-sync',
  '--disable-background-networking', '--disable-component-update', '--password-store=basic',
  `--proxy-server=http://127.0.0.1:${harness.proxyPort}`, '--proxy-bypass-list=localhost;127.0.0.1;[::1]',
  `--ignore-certificate-errors-spki-list=${spki}`, 'about:blank',
], { stdio: 'ignore' });
const state = { runDirectory, dist, profile, browserPid: browser.pid,
  relayHosts: hosts.filter(host => host !== 'chatgpt.com'), certBase64: cert.toString('base64'),
  controlPort: harness.controlPort, proxyPort: harness.proxyPort, relayPort: harness.relayPort, token: harness.token };
writeFileSync(join(runDirectory, 'state.json'), JSON.stringify(state, null, 2), { mode: 0o600 });
console.log(JSON.stringify({ event: 'isolated-qa-ready', runDirectory, browserPid: browser.pid, relayCount: relayHosts.length }));
let closing = false;
const stop = async () => { if (closing) return; closing = true; browser.kill('SIGTERM'); await harness.close(); process.exit(0); };
process.on('SIGINT', stop); process.on('SIGTERM', stop);
browser.on('exit', () => console.log(JSON.stringify({ event: 'qa-browser-exited', harnessRetained: true })));

