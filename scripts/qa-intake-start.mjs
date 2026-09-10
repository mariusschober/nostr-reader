// Focused intake only: public articles load directly; paired transport stays isolated.
import { mkdirSync, writeFileSync, readFileSync } from 'node:fs';
import { resolve, join } from 'node:path';
import { spawn, execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import http from 'node:http';
import { startRelayHarness } from './qa-relay.mjs';
const [distArg, runArg, chromeBinary] = process.argv.slice(2);
if (!distArg || !runArg || !chromeBinary) throw Error('DIST RUN_DIRECTORY CHROME_FOR_TESTING required');
const dist = resolve(distArg), runDirectory = resolve(runArg);
const hosts = JSON.parse(readFileSync(new URL('../shared/default-relays.json', import.meta.url))).relays.map(url=>new URL(url).hostname);
mkdirSync(runDirectory,{recursive:true, mode:0o700});
const keyPath=join(runDirectory,'tls-key.pem'), certPath=join(runDirectory,'tls-cert.pem');
execFileSync('/usr/bin/openssl',['req','-x509','-newkey','rsa:2048','-nodes','-days','2','-keyout',keyPath,'-out',certPath,'-subj','/CN=Reader focused QA','-addext',`subjectAltName=${hosts.map(x=>'DNS:'+x).join(',')}`],{stdio:'ignore'});
const key=readFileSync(keyPath),cert=readFileSync(certPath);
const pub=execFileSync('/usr/bin/openssl',['x509','-pubkey','-noout'],{input:cert});
const der=execFileSync('/usr/bin/openssl',['pkey','-pubin','-outform','der'],{input:pub});
const harness=await startRelayHarness({tls:{key,cert},allowedHosts:hosts});
const pac=http.createServer((req,res)=>{res.writeHead(200,{'Content-Type':'application/x-ns-proxy-autoconfig'});res.end(`function FindProxyForURL(url,host){var hosts=${JSON.stringify(hosts)};return hosts.indexOf(host)>=0?'PROXY 127.0.0.1:${harness.proxyPort}':'DIRECT';}`);});
await new Promise(resolve=>pac.listen(0,'127.0.0.1',resolve));
const profile=join(runDirectory,'chrome-profile');
const browser=spawn(chromeBinary,[`--user-data-dir=${profile}`,`--load-extension=${dist}`,`--disable-extensions-except=${dist}`,
  '--enable-unsafe-extension-debugging','--remote-debugging-port=0','--no-first-run','--no-default-browser-check','--disable-sync','--disable-background-networking','--password-store=basic',
  `--proxy-pac-url=http://127.0.0.1:${pac.address().port}/proxy.pac`, `--ignore-certificate-errors-spki-list=${createHash('sha256').update(der).digest('base64')}`,'about:blank'],{stdio:'ignore'});
writeFileSync(join(runDirectory,'state.json'),JSON.stringify({runDirectory,dist,profile,browserPid:browser.pid,relayHosts:hosts,certBase64:cert.toString('base64'),controlPort:harness.controlPort,proxyPort:harness.proxyPort,relayPort:harness.relayPort,token:harness.token}),{mode:0o600});
console.log(JSON.stringify({event:'focused-intake-ready',profile,relayCount:hosts.length}));
const stop=async()=>{browser.kill('SIGTERM');pac.close();await harness.close();process.exit(0);};
process.on('SIGTERM',stop);process.on('SIGINT',stop);
