import { readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { execFileSync } from 'node:child_process';
const [statePath, serial, appApk, testApk, evidenceDir, run = '1'] = process.argv.slice(2);
if (!evidenceDir) throw new Error('Usage: qa-camera.mjs STATE DEVICE QA_APP QA_TEST EVIDENCE_DIR');
const state = JSON.parse(readFileSync(statePath));
const adb = args => {
  try { return execFileSync('adb', ['-s', serial, ...args], { encoding: 'utf8', timeout: 90000 }); }
  catch (error) { if (error.stdout) process.stdout.write(error.stdout); if (error.stderr) process.stderr.write(error.stderr); throw new Error('QA Android command failed'); }
};
const permission = 'android.permission.CAMERA';
const app = 'com.reader.app.qa';
const tlsArgs = ['-e','qaRelayProxy',String(state.proxyPort),'-e','qaRelayHosts',state.relayHosts.join(','),
  '-e','qaRelayCert',state.certBase64];
let passed = true;
try {
  for (const apk of [appApk, testApk]) {
    if (!adb(['install', '-r', apk]).includes('Success')) throw new Error('QA install failed');
  }
  adb(['reverse', `tcp:${state.proxyPort}`, `tcp:${state.proxyPort}`]);
  for (const mode of ['fresh', 'denied']) {
    adb(['shell','pm','revoke',app,permission]);
    adb(['shell','pm','clear-permission-flags',app,permission,'user-set','user-fixed']);
    if (mode === 'denied') adb(['shell','pm','set-permission-flags',app,permission,'user-set','user-fixed']);
    const output = adb(['shell','am','instrument','-w','-e','class','com.reader.app.QaCameraPermissionInstrumentedTest',
      '-e','qaCamera',mode,...tlsArgs,'com.reader.app.qa.test/com.reader.app.QaTestRunner']);
    writeFileSync(join(evidenceDir,`camera-${mode}-${run}.log`),output);
    const ok = /OK \(1 test\)/.test(output); passed &&= ok;
    console.log(JSON.stringify({case:`camera-${mode}`,outcome:ok?'PASS':'FAIL'}));
    if (!ok) break;
  }
  for (const name of ['camera-request','camera-denied','camera-app-settings']) {
    try { adb(['pull',`/sdcard/Android/data/${app}/files/qa/${name}.png`,join(evidenceDir,`${name}-${run}.png`)]); } catch {}
  }
} finally {
  adb(['shell','pm','clear-permission-flags',app,permission,'user-fixed']);
  adb(['shell','pm','grant',app,permission]);
  adb(['reverse','--remove',`tcp:${state.proxyPort}`]);
}
if (!passed) process.exitCode = 1;
