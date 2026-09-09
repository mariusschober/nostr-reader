> Historical checkpoint: results and instructions below apply to their recorded source and date. Start with [current status](CONTINUE.md) for the installed build and remaining limits.

# Reliability hardening candidate — 2026-09-08

Baseline: `1e55b7d4021637520402cb1773ac4e1ffd3b43cf`.
Executable source: `7dbb86922c841a500f98007c2bd89f3644c3a31f`, branch
`codex/reliability-data-integrity`. Later documentation commits do not change the
packaged executable source. No push, merge, hosted deployment or release was performed.

## Host checks

| Check | Result | Evidence |
|---|---|---|
| Chrome test + typecheck | PASS — 136 tests, 23 files | `artifacts/hardening/chrome-final-tests.log` |
| Chrome production build + ZIP verification | PASS | `artifacts/hardening/chrome-final-build.log` |
| Android debug and release unit suites | PASS — 118 tests each | `artifacts/hardening/android-final-gates-2.log`; Gradle XML results |
| Android lint, app and matching test APK build | PASS | Same log; isolated build in `android-final-qa-build.log` |
| Rust `cargo test --locked` | PASS — 6 tests | `artifacts/hardening/rust-final.log` |
| Swift `swift test` | PASS — 7 tests | `artifacts/hardening/swift-final.log` |
| Automatic remote CI | NOT MEASURED — workflow enabled, branch not pushed | `.github/workflows/verify.yml` |

The first final-gate attempt caught a nullable-cursor mistake in a new test. It was
corrected before the successful gate and packaging. Intermediate packages do not
certify the final packages.

## S23 continuation

After the TCL was detached, the same final artifact pair was installed in-place in the
S23 Ultra QA package: Samsung SM-S918B, Android 16 / API 36 (device serial redacted).
**PASS — 21 tests, 117.317 seconds**, including the same 20 checks and Google speech
completion through the production AndroidTtsEngine. This establishes callback completion,
not acoustic quality or a full audio-focus/call/headset matrix. The phone's one-handed
window mode and speech defaults were left unchanged. Relay access was injected only by
the test APK and restricted to the local harness throughout.

| S23 expanded bytes | Ingestion | First-section preparation |
|---|---:|---:|
| 4,097 | 242.7 ms | 71.3 ms |
| 262,145 | 582.0 ms | 1,012.9 ms |
| 19,922,945 | 40,329.3 ms | 10,938.4 ms |

S23 native gesture (view coordinates, one-handed mode): −12,331.5 px/s touch velocity;
1,627.9 px finger travel; 1,485 px scrolling during touch; **zero momentum after release**.
Seven rendered-frame samples had p95/max 3.19 ms; 56 frame-cadence samples had p95
16.67 ms and max 16.83 ms. The small sample and different device/window conditions
cannot support a cross-device performance ranking. Near-limit ingestion remains costly.

Evidence: `artifacts/hardening/final/s23/device-tests.log` and `s23/measurements/`.
S23 foreground: PASS, three new 2,083-byte captures. Capture→storage times were
1,092 / 444 / 366 ms; storage→observed Chrome receipt times were 1,198 / 795 / 1,100 ms.
The third trial had one local relay offline. Clock uncertainty remained ±104 ms. Final
Chrome stores held zero transport items, zero legacy captures and three capture IDs.
Evidence: `artifacts/hardening/final/s23/foreground-e2e.json`.

The pairing helper initially missed the German camera-denial label and did not drive
the normal Chrome pairing-page polling while awaiting Android. Camera was kept denied
only for the QA package (user-set/user-fixed flags); the existing unexpired QA request
was reused in memory. Pairing ultimately committed on both clients, after which the
measurements above ran. These harness timeouts are retained; they are not a benchmark
of the ordinary pairing page. Normal app permissions and the reduced-screen workaround
were unchanged.

## Exact artifacts

All paths below are relative to `artifacts/hardening/final/` (ignored local evidence).

| Artifact | SHA-256 |
|---|---|
| `normal/app.apk` | `0b82f16149a5ed09b59e0b817b1669642d1e84b9afa2ee4842dcd01c981f03b7` |
| `normal/test.apk` | `dbab446c572cb917ff8c5f2f57bdb5886265dd395d814b4e4f0faa21ea5e05c7` |
| `qa/app.apk` | `2c3db6162b30de86efb784b4896cdcd371b038ddd1476610cb8ae12abd3c338e` |
| `qa/test.apk` | `eaed075dbeda642ee957a0aa4fc1815f30f11206855710b2132fe67570a33c30` |
| `reader-chrome-extension.zip` | `e87eeeca39da12975a83c1c79d6e9cc4f12da4422daaec8b31fc4911565a35c1` |

Manifest: `artifact-manifest.json`. TCL T807D, Android 16 / API 36 (device serial
redacted); Chrome for Testing `147.0.7727.57` in a fresh profile.
The normal Chrome profile was not touched. Fault tests use `com.reader.app.qa`.

Normal/QA installation verification: see `artifacts/hardening/final/installed-artifacts.json` (TCL) and `s23/installed-artifacts.json` in that directory (S23).
Normal installation uses `install -r` with no data clear. First-install timestamps
remain unchanged and installed APK hashes match. Destructive/fault fixtures run only
in the QA package. S23 normal database: **PASS — 1 test**, including SQLite integrity and opening the
preserved document/highlight/channel tables (`s23/normal-database-integrity.log`); this
checks the migrated database without exposing personal article text, not every article
or every retained quotation individually. TCL normal-library runtime was NOT MEASURED
before its disconnection.

SAF archive SHA-256: `e4f48cd5e490cd9c5395d089530460aadff64d04bf406460958d36584ea38d7d`.
Evidence: `artifacts/hardening/final/saf-export.zip` and its verification JSON.

## Scenario qualification

PASS means the stated experiment passed, not that every adjacent real-world condition
was measured. NOT MEASURED identifies the unexecuted extension of a test explicitly.

| Scenario | Result and scope |
|---|---|
| Chrome ACK before cleanup crash; quota rollback; duplicate capture ID; explicit discard | PASS — production worker fault tests, including durable receipt before payload cleanup |
| Legacy retained-store migration, export and explicit deletion | PASS — production worker IndexedDB migration tests preserve rows and register lightweight IDs atomically |
| Separate library survives ACK/discard | PASS — transport actions preserve the independently owned legacy library |
| Store T, withhold receipt, archive/delete, exact and fresh-wrapper replay; new T2 same text | PASS — final QA artifact |
| Room 3→9 and 8→9 migrations and historical receipt evidence | PASS — final QA artifact |
| Selection finalization at 0/50/100/200 ms, pen off, detach, projection replacement, delayed save | PASS — final QA artifact — 12 timing/exit combinations |
| Native pen handles/menu gating and drag/autoscroll | PASS — final QA artifact |
| Real later-part recreation and delayed-save Speed transition | PASS — final QA artifact |
| Application-owner loss with durable draft journal recovery | PASS — final QA artifact — simulated owner cancellation/startup replay; not an OS kill |
| Selection save failure and ordered retry | PASS — production ReadingSession host fault test |
| Full timing matrix for Back, swipe, action-mode end, background, rotation and OS process death | NOT MEASURED — shared paths implemented; the entire cross-product was not run |
| Repeated text, emoji, combining marks, nested rendered text, bounded sections | PASS for basic projection/Unicode boundaries and native exact repeated Unicode quotes; nested-text anchor matrix NOT MEASURED |
| Every font/theme change with live selection in later parts | NOT MEASURED as a complete device matrix |
| Held Room transaction during actual MainActivity.onStop | PASS — final QA artifact |
| Paused intake then channel revocation | PASS — final QA artifact — no document/processed-event/ACK commit |
| Paused successful ACK publication then revocation | PASS — final QA artifact — late success must not mutate reserved receipt state |
| Stored relay digest / receiver-key validation | Implemented before network work; full corruption matrix NOT MEASURED |
| History starvation hypothesis | PASS reproduction — controlled MockWebServer returns the same 32-event prefix twice and omits event 33 |
| Resumable histories with caps 32/64/256 and 1,200 events | PASS — production scanner with controlled capped-query callback; full network matrix NOT MEASURED |
| Timestamp ties, storage failure before checkpoint | PASS — incomplete bucket retained and failed durable consume does not advance checkpoint |
| Byte budgets and malformed receive behavior | PASS existing bounded receive/parser tests; sustained flood plus full scanner integration NOT MEASURED |
| One failed relay beside healthy relays | PASS — final APK/extension, one local relay offline while the remaining relays deliver and receipt |
| Delayed Important/Next review race | PASS — failure reproduced before current-ID guard, regression passes after fix |
| Single reading/audio owner in rapid Listen/Speed cycles | Mode pause/ownership implemented and controller host tests PASS; full audible rapid-mode device matrix NOT MEASURED |
| Google configured voice/language and network capability | Preserved/inspected in implementation; natural completion/error audio-focus behavior NOT MEASURED acoustically on final device artifact |
| Portable snapshot: all components, provenance, orphan quotes, no keys | PASS — final QA artifact |
| SAF destination opens outside Reader | PASS — explicit Downloads picker saved `reader-hardening-qa-7dbb869.zip`; independent host ZIP reader verified all 10 components / 4 synthetic articles |
| Concurrent export writes, full provider storage, cancellation and undeletable partial output | NOT MEASURED as an actual provider fault matrix; coherent transaction, temporary validation, readback and cleanup paths implemented |
| Native fling hypothesis | Measured separately below; no speculative scrolling rewrite |

The packaged extension loaded in Chrome for Testing with zero manifest/runtime errors.
The loaded directory matched every ZIP entry byte-for-byte. The first pairing UI probe
hit its 60-second timeout (the harness awaited Android before actively polling the
Chrome pairing session); subsequent independent durable-state checks confirmed one
active Android channel and paired Chrome. This eventual completion is not a passing
60-second pairing-time claim.

## Measurements

Final-device suite: **PASS — 20 tests**, 322.983 seconds, exact final QA app/test pair.
Log: `artifacts/hardening/final/device-tests.log`.

| Expanded / compressed synthetic bytes | Ingestion | First-section preparation |
|---|---:|---:|
| 4,097 / 103 | 287.5 ms | 124.0 ms |
| 262,145 / 855 | 717.8 ms | 924.6 ms |
| 19,922,945 / 58,064 | 29,900.4 ms | 12,422.5 ms |

These highly compressible synthetic documents do not represent worst-case compressed
size or complex Markdown. Ingestion is timed separately from section preparation;
these numbers do not include visible rendering. No claim of improved end-to-end latency
is made without an equivalent pre-change measurement.

Controlled native gesture: touch velocity −8,460.6 px/s; finger travel 1,199.5 px;
scroll during touch 1,089 px; scroll after release **0 px**. There is no momentum in
this experiment. No scroll-physics change was made. Twenty-two rendered frame samples
had p95/max 13.37 ms; 86 frame-cadence samples had p95 11.09 ms and max 11.24 ms.
This is instrumented native touch dispatch, not a human-perceived smoothness verdict.
Files: `final/measurements/ingestion-measurements.json` and
`final/measurements/native-scroll-measurement.json` under `artifacts/hardening/`.

Foreground production capture pipeline, three new 2,083-byte synthetic captures over
local TLS relays (Android visible; no Doze/background blend):

| Trial | Capture request → phone storage | Storage → receipt observed in Chrome |
|---|---:|---:|
| All relays available, 1 | 629 ms | 1,019 ms |
| All relays available, 2 | 495 ms | 731 ms |
| One relay offline | 491 ms | 1,142 ms |

Clock calibration uncertainty: ±104 ms. Receipt observations include polling and are
upper-bound observations, not exact receipt-commit timestamps. This measures the trusted
extension capture entry point, not page extraction/button-click latency. Stored content
hashes matched. Final IndexedDB counts: zero transport items, zero retained captures,
four lightweight capture IDs (one preliminary harness capture plus three measured ones).
The preliminary harness expected an untrimmed hash; correcting that test expectation
required no production changes. Evidence: `artifacts/hardening/final/foreground-e2e.json`.

Intermediate discovery is retained rather than relabelled:
19 MiB fixture construction originally spent most sampled CPU in per-word regex
compilation (10-second Simpleperf capture, 2,450 samples, zero lost). Hoisting the same
three regexes preserves semantics. That profile measured fixture construction, not
transfer ingestion. Evidence: `artifacts/hardening/qa-intermediate-3/near-limit*`.

The invalid first scroll harness had a zero-height view and collected no valid gesture
result. The corrected harness requires a real attached Compose-hosted native viewport.
A screen-level recreation harness also initially sampled the old part before the next
part loaded; its wait was corrected and the actual screen regression then passed.

## Reproduction commands

From the repository root unless a directory is shown:

```sh
cd chrome
npm test
npm run typecheck
READER_DIST='/absolute/path/to/isolated/extension' npm run build
cd ..
READER_DIST='/absolute/path/to/isolated/extension' scripts/package-chrome-extension.sh artifacts/hardening/final/reader-chrome-extension.zip
cd android
./gradlew --no-daemon --max-workers=2 test lint assembleDebug assembleDebugAndroidTest
# Preserve normal outputs before building the QA variant.
./gradlew --no-daemon --max-workers=2 -PreaderQa=true assembleDebug assembleDebugAndroidTest
```

Physical tests use `adb -s <device-serial> install -r` on the final QA pair, followed
by `am instrument -w -e class` selecting SelectionFinalization, NativeScrollMeasurement,
TransferManager, ReaderDbMigration, HardeningStorage, HighlightMenuGate, ReaderTransition
and StopResponsiveness instrumented test classes (all `com.reader.app.*InstrumentedTest`)
and runner `com.reader.app.qa.test/com.reader.app.QaTestRunner`.

Controlled foreground harness: `scripts/qa-start.mjs`, `scripts/qa-android.mjs`,
`artifacts/hardening/final/browser-e2e.mjs`. Test TLS certificates and transient pairing
bearers stay in ignored private evidence or memory, never in committed reports.
No public-relay campaign, normal-profile reset, production-data clear or speech-default
change was performed. Export remains explicitly distinct from restore.

## Cleanup boundary

The isolated test campaign and QA app were stopped after measurement. Automatic approval
review rejected a command that would have reset a local relay-availability policy; no
policy reset was performed. A narrower stop-only action succeeded. This did not block
artifact verification or require changing production network guards.
