# AI continuation context — Nostr Reader

Updated: 2026-09-05

This is the authoritative entry point for an AI or human continuing the
project. It records what exists, what was proved, what remains unproved, and
how to resume without overstating evidence or damaging the preserved state.
Read the current code and generated evidence as authoritative if anything here
later drifts.

## 1. Repository and evidence boundary

- Repository: `https://github.com/mariusschober/nostr-reader`
- Immutable audit baseline: `982920b4e91dd5af4af6046f56df281c7adfe545`
- Dedicated repair branch: `fix/pairing-delivery-hardening-982920b4`
- Runtime repair milestone: `6ea80ee6f3732a192307661fd9cd5c485a4dd1dc`
- Current handoff revision: resolve the branch `HEAD`; do not hardcode a commit
  into a file that participates in that same commit.
- Generated `artifacts/ARTIFACTS.json`, when present locally, is authoritative
  for exact artifact source revision, hashes, commands, and install status.

The branch starts at the immutable baseline and contains the repair as a
linear series of local commits. At handoff it is twenty-plus commits ahead of
`origin/main`; inspect `git log --oneline 982920b4..HEAD` for the exact series.

Status vocabulary is strict:

- `PASS` means the named scope was executed and met its expected result.
- `FAIL` means it was executed and contradicted the expectation.
- `NOT MEASURED` means it was not executed or the evidence is too narrow.
- `BLOCKED: reason` means a concrete dependency prevented execution.

Never promote a narrow unit, emulator, single-flow, or prior-hash result into a
broad physical/reliability claim.

## 2. Product and component status

| Component | Current reality | Verification |
|---|---|---|
| Chrome MV3 extension | Implemented sender, extraction, pairing UI, settings, durable outbox, relay/auth handling, and device ACK state | 17 files / 101 tests; typecheck/build/package PASS; exact ZIP verified in Chrome for Testing |
| Android app | Implemented Compose reader, camera pairing, relay sync, Room v5 durability, TTS, RSVP, triage, archive, and settings | 86 JVM tests and 0 lint errors; runtime checkpoint passed 10/10 instrumentation on TCL, S23, and API-26 |
| Rust core | Tested reference implementation for canonicalization, hashing, deterministic gzip/chunking, limits, rolling window, word count, narration, and RSVP | 6/6 tests; not connected to app runtimes; no UniFFI yet |
| Mac | Swift package containing a core/sender mirror and tests | 7/7 tests; no runnable Mac app, persistence, pairing, relay client, or UI |
| iOS | Planned only | no source target or executable |
| Backend | Deliberately absent | direct encrypted public-relay architecture; no accounts or telemetry |

The only complete user path currently implemented is Chrome -> Android.
Mac/iOS language in planning documents must not be reported as shipped.

## 3. User-visible flow and current observed state

```text
Chrome capture
  -> reader-pair/2 authenticated channel
  -> encrypted reader/2 manifest and chunks on Nostr relays
  -> Android durable document
  -> authenticated device ACK
  -> Chrome marks delivered and deletes plaintext outbox payload
```

One real Chrome for Testing/TCL pairing and one synthetic article delivery
completed across seven relays. The Android document was stored once, its ACK
cleared Chrome's pending item, and the pairing survived a controlled extension
reload and full same-profile browser restart. The preserved read-only status
was: paired, protocol v2, device binding verified, relay digest matched, seven
relays, pending 0, delivered 1, failed 0.

That is a `PASS` for one observed end-to-end flow, not a reliability-rate
claim. No real identity or personal article was used.

## 4. Relay policy

`shared/default-relays.json` is the cross-platform authority:

1. `wss://nos.lol`
2. `wss://relay.primal.net`
3. `wss://relay.nostr.net`
4. `wss://nostr.oxtr.dev`
5. `wss://offchain.pub`
6. `wss://nostr-pub.wellorder.net`

The write quorum is two matching positive relay confirmations. Chrome Settings
accepts up to two additional secure `wss://` relays. The exact canonical relay
set and digest are bound into pairing; changing it requires re-pairing. Do not
silently fall back to defaults for a paired channel or reduce quorum based on
corrupt stored state.

Public relay health is dated operational evidence. It is never a permanent
availability guarantee.

## 5. Protocol and security invariants

Read `PROTOCOL.md` before changing wire behavior. The repaired path requires:

- durable kind 1059 pairing request, response, authenticated Chrome ACK, and
  Android completion before either endpoint becomes active;
- NIP-59 outer routing with the recipient `p` tag, correct inner sender checks,
  rumor IDs, strict schemas, expiry, and replay rejection;
- NIP-44 v2 limits and official vectors, canonical NIP-01 event IDs, and
  BIP-340 signing that passes the official corpus;
- NIP-42 AUTH template validation, one challenge/signature per operation,
  exact event-ID attribution, and challenge redaction from diagnostics;
- explicit per-relay accepted/rejected/transport/auth outcomes—relay `OK` is
  not endpoint delivery;
- one deterministic gzip member with exact header, CRC32/ISIZE, expanded-size
  limit, and rejection of concatenated members or trailing bytes;
- durable sender outbox and receiver ACK intent, exact transfer/channel/device
  binding, idempotent document commit, and plaintext deletion only after an
  authenticated device ACK;
- no private key, pairing bootstrap secret, plaintext article, or exact AUTH
  challenge in logs/evidence.

Security analysis and trust boundaries live in `THREAT_MODEL.md` and
`SECURITY_AND_PRIVACY_NOTES.md`. Do not weaken QR validation, DNS/private-target
rejection, Chrome trusted-context storage access, Android Keystore wrapping, or
device-key/channel binding to make a test easier.

## 6. Persistence and lifecycle ownership

Chrome persists device/channel state, pairing sessions, and receipts in
`chrome.storage.local`; plaintext outbox items live in IndexedDB. Service-worker
startup, alarms, UI calls, and retry handlers converge on serialized state
transitions. A late publisher must not recreate an ACK-deleted item. A
missing/replaced device key invalidates the channel but preserves never-sent
captures for explicit re-pairing.

Android uses Room schema v5 plus DataStore/Keystore and WorkManager. Pairing is
staged until Chrome ACK/completion. Sync uses a rolling ten-day window because
NIP-59 timestamps are randomized; it does not rely on a strict last-sync
cursor. Authenticated wrapper IDs, processed events, ACK intents, and outcomes
are durable. A v4 -> v5 installation can emit one bounded recovery ACK batch
because v4 has no historical ledger; repeats are then suppressed.

## 7. Source layout

- `chrome/src/background/` — MV3 service-worker orchestration.
- `chrome/src/content/` and `chrome/src/extraction/` — page capture and
  deterministic extraction.
- `chrome/src/nostr/`, `protocol/`, `signer/` — Nostr crypto/transport,
  pairing/delivery state, strict JSON, device binding, and optional NIP-07.
- `chrome/src/ui/` — popup, pairing, and relay settings.
- `android/app/src/main/java/com/reader/app/nostr/` — NIP-44/59, signing,
  relay client, pairing validation, and strict JSON.
- `android/app/src/main/java/com/reader/app/sync/` — pairing coordinator,
  ingestion, transfer assembly, durable ACK lifecycle, and WorkManager.
- `android/app/src/main/java/com/reader/app/data/` — Room schema/migrations;
  preserve migration tests.
- `android/app/src/main/java/com/reader/app/ui/`, `tts/`, `rsvp/`, `cursor/` —
  reader experience.
- `rust-core/` — normative/reference algorithms, not an integrated FFI layer.
- `mac/` — Swift library/test skeleton; `MAC.md` defines future product scope.
- `shared/schemas/` — normative message schemas.
- `shared/test-vectors/` — public cross-runtime conformance gates.
- `shared/fixtures/` — extraction fixtures.
- `scripts/` — deterministic package and artifact/provenance generation.
- `evidence/raw/` — redacted baseline and final execution evidence.

## 8. Documentation authority map

| Question | Read |
|---|---|
| What failed originally and why? | `PAIRING_ROOT_CAUSE.md` |
| What is the normative wire contract? | `PROTOCOL.md` |
| Architecture and state machines? | `ARCHITECTURE_AND_PROTOCOL.md`, `RELIABILITY_STATE_MACHINES.md` |
| NIP support and deviations? | `NIP_CONFORMANCE_MATRIX.md` |
| Threats, secrets, privacy, trust? | `THREAT_MODEL.md`, `SECURITY_AND_PRIVACY_NOTES.md` |
| Migration and rollback? | `MIGRATION_AND_COMPATIBILITY.md`, `ROLLBACK.md` |
| What was actually tested? | `TEST_REPORT.md` |
| Physical TCL evidence/quotas? | `TCL_PHYSICAL_VALIDATION.md` |
| Dated public relay observations? | `LIVE_RELAY_REPORT.md` |
| Residual risks? | `KNOWN_LIMITATIONS.md` |
| Cross-platform reality and future plan? | `CROSS_PLATFORM.md`, `MAC.md`, `mac/docs/` |
| Concise repair handoff? | `CONTINUE.md` |
| Generated artifact policy? | `artifacts/README.md` |

Root `TEST_REPORT.md` supersedes the preserved historical
`artifacts/TEST-REPORT.md`.

## 9. Build and test commands

Prerequisites observed at handoff: Node 22, JDK 17, Android SDK 34, Android
Build Tools 35, Gradle wrapper 8.9, Rust/Cargo, and Swift 6.3. The wrapper pins
the official Gradle distribution SHA-256.

Complete clean audit build:

```sh
./scripts/build-audit-artifacts.sh
```

It refuses a dirty tracked worktree, performs npm advisory checks, runs all four
language gates, packages Chrome twice and compares ZIP bytes, builds Android
twice and compares both APK pairs, and writes ignored provenance artifacts.

Individual gates:

```sh
cd chrome && npm ci && npm run typecheck && npm test && npm run build
cd android && ./gradlew clean test lint assembleDebug assembleDebugAndroidTest
cd rust-core && cargo test
cd mac && swift test
```

Current verified counts are Chrome 101 tests, Android 86 JVM tests, Android
lint 0 errors/17 warnings, Rust 6 tests, and Swift 7 tests. The online npm audit
returned zero known vulnerabilities at its recorded UTC time; that result is
time-bounded. Once all dependencies are cached, add `--offline` to the Android
command for a network-independent repeat.

## 10. Artifacts and installation

Generated debug artifacts are intentionally Git-ignored; rebuild them on a
remote checkout. The locally generated handoff hashes are documented in
`artifacts/README.md`.

Android, preserving existing app data:

```sh
adb devices -l
adb -s SERIAL install -r artifacts/reader-debug.apk
adb -s SERIAL install -r artifacts/reader-debug-androidTest.apk
adb -s SERIAL shell am instrument -w com.reader.app.test/androidx.test.runner.AndroidJUnitRunner
```

Chrome: use Google Chrome/Chrome for Testing, never substitute Brave for the
recorded evidence. Run the Chrome build, open `chrome://extensions`, enable
Developer mode, choose **Load unpacked**, and select `chrome/dist`.

At handoff, the final reproducible APK pair had not been installed after the
toolchain-only rebuild because the user retained that final use test. The prior
runtime-checkpoint pair passed 10/10 on TCL T807D, Samsung S23, and API-26, but
that evidence must not be transferred to the new hashes.

## 11. Physical/browser environment notes

Revalidate all external state before relying on it:

- TCL T807D was the decisive device; observed ADB serial
  `ZXKRS4VKGQ8PWGEQ`.
- Samsung Galaxy S23 was the secondary device; observed ADB serial
  `R3CW404GVBL`.
- The S23 has an independently observed OLED panel fault. Use ADB hierarchy or
  screenshots for app assertions; do not classify the panel artifact as a
  Reader defect.
- Browser evidence used Google Chrome for Testing 151.0.7922.34 with a
  disposable/preserved local profile. Profile paths and extension IDs are
  environment-specific and must be rediscovered.
- A preserved paired Chrome profile contains local private device material.
  Never commit, upload, print, or copy that profile into evidence.
- Always pass an explicit ADB serial when more than one target is attached.
- Do not clear app or extension data unless a test explicitly requires a fresh
  state and the user has authorized losing the preserved pairing.

## 12. Evidence already established

- Baseline TCL failure reproduced: one matching negative relay `OK` for an
  expired ephemeral event plus two transport failures.
- Root causes traced to old event kind/timestamp behavior, missing recipient
  routing tag, wrong sender comparison, incompatible fields, absent completion
  consumer, premature Android trust, false Promise-array accounting, and
  zlib/gzip drift.
- Shared request/response/ACK/completion transcript passes TypeScript, Kotlin,
  and Android runtime validation.
- Six default relays passed two dated disposable-key publish/read-back rounds;
  the custom Mostr relay passed its probe and single physical flow.
- Exact Chrome package/worker bytes and content-script storage isolation were
  verified in Chrome for Testing.
- Paired state recovered through one full browser restart and 20/20 distinct
  post-pair service-worker terminations without outbox mutation.
- Runtime-checkpoint Android APKs passed 10/10 instrumentation on both physical
  phones and API-26 with on-device hash matching.
- Chrome ZIP and both final Android APKs are byte-reproducible on the handoff
  host. APK alignment and debug signature verification pass.

See the evidence files for exact scope; screenshots or green tests never
substitute for the missing statistical series.

## 13. Work still required for full acceptance

The overall acceptance status remains **NOT MEASURED** until all applicable
quotas and physical scenarios are executed:

- 20/20 clean pairings;
- 20/20 pairings with service-worker termination before Android reply;
- 20/20 pre-reply full Chrome restarts;
- 50/50 normal deliveries with zero duplicate documents and verified ACKs;
- 20/20 offline-to-online deliveries within retention;
- 20/20 duplicate/replay attempts with no duplicate local effect;
- the remaining reboot, Doze, battery-saver, screen-off, network-switch,
  captive-network, and storage-failure cases in `TCL_PHYSICAL_VALIDATION.md`;
- installation and instrumentation of the final reproducible APK hashes;
- a new public transfer from the exact final artifact only if explicitly
  authorized;
- live third-party NIP-07/Amber checks, public NIP-42 challenge evidence,
  production signing, and a 16-KiB-page runtime target.

Do not generate quota traffic casually on public relays. Use disposable keys,
redacted evidence, rate limits, and explicit user authorization for any new
pairing or public payload.

## 14. Safe continuation checklist

1. Confirm `git status --short --branch`, current `HEAD`, remote branch, and
   absence of unrelated changes.
2. Read this file, `CONTINUE.md`, `TEST_REPORT.md`, and
   `KNOWN_LIMITATIONS.md`; then open the domain document for the intended edit.
3. Treat `shared/schemas/`, `shared/test-vectors/`, and `PROTOCOL.md` as one
   versioned contract. Never change only one runtime.
4. Run the smallest relevant regression first, then the complete clean audit
   build before claiming readiness.
5. For device/browser work, rediscover targets, preserve existing data, and
   record exact artifact hashes before installation.
6. Record evidence with exact counts and status vocabulary. Never convert
   `NOT MEASURED` to `PASS` by inference.
7. Do not merge, release, publish binaries, force-push, introduce a backend,
   use real identities, or expose secrets without a new explicit user request.

The 2026-09-05 instruction authorized pushing this dedicated branch to the
configured GitHub remote. It did not authorize merging it into `main` or
publishing a release.
