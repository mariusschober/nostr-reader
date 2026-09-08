import { readFileSync } from 'node:fs';
import { spawn, execFileSync } from 'node:child_process';
const [statePath, serial, appApk, testApk] = process.argv.slice(2);
if (!serial || !appApk || !testApk) throw new Error('Usage: qa-android.mjs STATE DEVICE_SERIAL QA_APP_APK QA_TEST_APK');
const state = JSON.parse(readFileSync(statePath));
const adb = (...args) => execFileSync('adb', ['-s', serial, ...args], { encoding: 'utf8' });
for (const apk of [appApk, testApk]) {
  const result = adb('install', '-r', apk);
  if (!result.includes('Success')) throw new Error('QA install failed');
}
for (const port of [state.proxyPort, state.controlPort]) adb('reverse', `tcp:${port}`, `tcp:${port}`);
const child = spawn('adb', ['-s', serial, 'shell', 'am', 'instrument', '-w',
  '-e', 'class', 'com.reader.app.QaCampaignInstrumentedTest',
  '-e', 'qaRelayProxy', String(state.proxyPort), '-e', 'qaRelayHosts', state.relayHosts.join(','),
  '-e', 'qaRelayCert', state.certBase64, '-e', 'qaControlPort', String(state.controlPort),
  '-e', 'qaControlToken', state.token, 'com.reader.app.qa.test/com.reader.app.QaTestRunner',
], { stdio: ['ignore', 'pipe', 'pipe'] });
child.stdout.pipe(process.stdout); child.stderr.pipe(process.stderr);
child.on('exit', code => {
  for (const port of [state.proxyPort, state.controlPort]) { try { adb('reverse', '--remove', `tcp:${port}`); } catch {} }
  process.exitCode = code ?? 1;
});
process.on('SIGTERM', () => child.kill('SIGTERM'));

