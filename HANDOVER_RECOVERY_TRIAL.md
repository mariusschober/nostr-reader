# Recovery trial pause — start here

The owner requested a pause for a 24-hour real-world trial on 5 September 2026. Finish this handoff and push the branch; do not continue feature implementation until the owner resumes. **This is not a completed 0.9 beta.**

## Repository and controlling brief

- Repository: https://github.com/mariusschober/nostr-reader
- Branch: `codex/reader-0.9-beta`.
- Audited starting commit: `0c9884e955558f478d7668b46e9c1411bfe95c5d`.
- First recovery checkpoint: `627e1da`.
- Current tested application source: `e322c300e5b623904626812b53e75bf4949fbd8e`. Later commits are documentation/evidence only unless explicitly stated otherwise.
- Local workspace: the existing `Nostr Reader` checkout; re-identify its path on another machine.
- Read [the complete execution brief](docs/beta/EXECUTION_BRIEF.md), [companion audit](docs/beta/RED_TEAM_AUDIT_2026-09-05.md), this handover, and `BETA_EXECUTION_LEDGER.md` before editing. Then read `PROTOCOL.md`, `THREAT_MODEL.md`, `RELIABILITY_STATE_MACHINES.md`, `KNOWN_LIMITATIONS.md`, and `TEST_REPORT.md`.
- Older `AI_CONTINUATION_CONTEXT.md` describes the audited baseline. This file supersedes its current-status/install claims for this branch. The original brief still governs remaining implementation; the pause overrides its instruction to continue indefinitely.

## What was implemented

1. Durable Chrome alarm reconciliation restores persisted deadlines without continually postponing earlier alarms. No live pairing means no pairing polling alarm. Startup resumes due outbox work.
2. Capture IDs distinguish user actions from documents/transfers. IndexedDB v2 stores retained captures atomically with outbox intent, reuses IDs after a lost response, and aborts synchronous failed write setup. The latter defect was caught with a failure-first executable test.
3. Content/toolbar capture immediately displays Saving, checks worker results, retries a lost response with the same ID, and distinguishes saved/unpaired/failure. Explicit short selection wins; context-menu capture now has visible/badge feedback. Manifest registers options and Alt+Shift+R.
4. Bounded relay receive: pre-queue frame/depth/tag/aggregate budgets, real EOSE requirements and typed failures. Android streams into a bounded queue using the shared client and a lifecycle-aware foreground session shared with WorkManager.
5. ACK history scans persist inclusive integer-second windows and report incomplete coverage. Consumption occurs before scan checkpointing. All-relay failure now fails explicitly and stores degraded coverage.
6. Chrome publication has one bounded owner, short generation-guarded state commits, persisted attempts and fragment/relay outcomes, one pool per transfer, and receipt-before-cleanup. A validated ACK can settle while redundant publication is delayed; late callbacks cannot resurrect the item.
7. Android Room v5 -> v6 stores bounded ACK refresh count. A newly authenticated unseen retransmission can reopen a completed ACK after five minutes, max eight refreshes / 168 lifetime attempts, without extending expiry. Replay/TTL tradeoffs remain documented, not a permanent retention guarantee.
8. Crypto profile validation and early sender rejection were tightened, and secret-key generation uses the uniform library generator.
9. CameraX 1.4.2 and DataStore 1.1.7 replace older native dependencies. All 12 packaged native libraries have >=16 KiB ELF load alignment; ZIP alignment passes. **16 KiB runtime testing is still NOT MEASURED.**

## Strongest executed evidence and its limits

- Chrome: **114/114 tests across 21 files**, typecheck and packaged build PASS. Ten tests execute the real worker with browser persistence/controlled network boundaries. Four source-shape tests remain supplementary. This does not close the full crash/interleaving campaign.
- Android normal-package JVM tests, lint, app build and instrumentation build PASS. Build log is retained; Gradle task count is not a test count.
- Samsung SM-S918B Android 16: earlier isolated recovery APK instrumentation **10/10 PASS**. It is an older APK and not evidence for the latest hashes.
- User initially requested S23, then explicitly switched testing to **TCL T807D Android 16**. Leave S23 alone unless newly authorized.
- Latest TCL isolated QA app/test artifacts: migration/crypto/gzip/transfer instrumentation **10/10 PASS**. Native manual-entry pairing UI test **1/1 PASS**; both endpoints authenticated completion.
- Packaged Chrome QA and TCL QA: two deliberate one-word actions (one saved while unpaired) yielded two authenticated endpoint receipts and exactly one document with matching canonical content hash `c4745b60855edff941e66d266f180b24333e1ccf4607d21d10263436315d97f3` (`Constellation\n`). These used six default public relays. They are not a 50-delivery or 20-pairing campaign.
- Final normal-package app/test pair installed on TCL and instrumentation **10/10 PASS**. Signature matched existing installation; exact key-file and settings-file bytes compared equal before/after. No owner data clear/uninstall. Owner article content was not exported for this check.
- The normal owner's seven-relay pairing was preserved and confirmed in the restored Chrome profile. Exact-hash E2E content verification above belongs to isolated QA; do not silently describe it as an exhaustive test of the owner's seven-relay path.
- Manual keyboard automation initially altered JSON. A QA-only opt-in instrumentation helper now enters the exact request through Android accessibility ACTION_SET_TEXT, then uses the production Review/Connect UI. It never provisions keys or database rows, reads clipboard, or grants camera permission. Automatic review rejected temporary camera access; the manual alternative passed. Camera QR scanning on this checkpoint remains untested.
- Full hostile-load, crash, offline, same-profile restart, live-site, UI feature, migration-corpus, performance, battery, minimum-API and 16 KiB runtime campaigns remain incomplete.

Evidence is in `evidence/beta/`, especially `RECOVERY_ARTIFACTS.json`, `tcl-recovery-delivery.json`, `tcl-trial-upgrade.json`, `tcl-trial-instrumentation.txt`, `tcl-pairing-ui.txt`, and `chrome-checkpoint-tests.txt`.

## Trial installation and artifacts

- Normal trial package: `com.reader.app`; matching test: `com.reader.app.test`. Version remains **0.1.0 / code 1**, deliberately not labelled beta-ready. A future beta needs aligned 0.9 versions and incremented versionCode.
- Debug certificate SHA-256: `3af50cab6e0515f478413e79786958671913b2cae67037defe537bb1e7465069` (not a store/release signing assurance).
- Installed normal app SHA-256: `62a96738a40dea7ba2b61d1db1e469ac1fb6d5f87f7cade7c5dd4feaff11ecfb`.
- Normal test APK SHA-256: `518230db2062a061746a528b8499e65748ba5d832e2d5a6b169eb59a53541a3c`.
- Packaged extension ZIP SHA-256: `3185f0ec9989998364ff56a931342e863e2fbdeee2c010cab8c5f77d108121a0`.
- All app/QA/test/ZIP hashes and relationships are in `evidence/beta/RECOVERY_ARTIFACTS.json`.
- Local downloads: `artifacts/recovery-checkpoint/{reader.apk,reader-test.apk,reader-qa.apk,reader-qa-test.apk,reader-chrome.zip,chrome-unpacked/,ARTIFACTS.json,SHA256SUMS}`. Generated binaries are gitignored; GitHub contains source/evidence, not a public release. No merge or store submission.
- Original paired Chrome extension reads `chrome/dist`, now updated with tested package bytes. Existing extension ID stays the same. The original profile was launched again and Reader loaded through normal Load unpacked at the original path; settings confirmed **Connected · 7 relays**, including `wss://relay.mostr.pub`. Its existing data was not cleared.
- At the final read-only check, the restored original profile showed **one pre-existing transfer awaiting the device and one older delivered receipt**. Its pending item was not erased or declared fixed; investigate this with the trial feedback. The two latest verified receipts belong to isolated QA.
- Only the isolated QA extension/profile was paired anew. Do not confuse QA with the owner’s library or re-pair the owner just to make a test pass.

## Remaining plan — preserve this order

| Scope | Remaining work |
|---|---|
| Owner trial feedback | Read the owner's 24-hour observations first. Reproduce losses, delays, state errors, or usability issues using synthetic equivalents. Preserve current data/keys/profile. |
| Core recovery R01-R05/R08/R09/R16/R17 | Complete real worker/process/browser interruption campaigns, >64/>256 and same-timestamp histories, storage crash points, ACK loss after completed quorum, slow relay fairness, invalid/unknown sender flood, staged byte/transfer/crypto budgets, lifecycle/network/Doze behavior. Focused tests currently pass; full findings are not closed. |
| Capture R02/R06/R07/R13 | Typed request/result contract across every route/frame; safe low-confidence preview; per-item retained history, retry/resend/export/discard; quota/storage pressure; live provider controls. Exact host matching and incremental mounting still need repair. Add Notebook, Grok, Substack and X support, update all source validators, scoped permissions, streaming/SPA/virtualization and rich DOM fixtures. Current broad adapters are unfinished. |
| Reader foundation R10-R12/R14 | Correct header/block/character cursor restoration, versioned rendered projection and separate narration projection; table/code/nested-list parsing fidelity; lightweight list projections and targeted flows; background bounded parser cache; debounced progress and saved navigation; real active TTS speed update and stale callbacks. |
| Themes/inbox R15 | SYSTEM/LIGHT/DARK, preserve PAPER/SOFT/INK/BLACK preferences, Follow app, coherent system bars; hide empty Inbox only after load, default Priority, keep Undo and stable destinations without focus theft. |
| Highlights R15 | Physical native selection spike before committing architecture; cross-block settled-selection session, four accessible Flexoki colors, deterministic overlap/Undo, exact anchors/context/UTF-16/graphemes, durable Room migrations; preserve quote after source deletion; export/restore boundaries. Do not assume LazyColumn + SelectionContainer solves this. |
| Share/review R15 | Quote-only native Sharesheet; Highlights feed; persistent seeded base pass plus bounded importance bonus scheduler exactly per brief; property tests; gestures, accessibility, process death, source-return and stable session behavior. None of these new features is implemented yet. |
| Final qualification R18 | Full required counts (20 pairings, 50 deliveries, 20 offline, 20 replay, 20 in-flight worker kills, 20 same-profile restarts), labelled controlled/public environments; real live-site evidence, screenshots inspected, latency/jank/memory/battery measurements, API26/current/16KiB runtime, nondestructive upgrade; freeze code, rebuild reproducibly, exact app/test/ZIP tests, final hashes and beta-readiness disposition. |
| Final documents | `BETA_TEST_REPORT.md`, `BETA_AUDIT_RESOLUTION.md`, final `ARTIFACTS.json`, current limitations/protocol/privacy/source matrix/migration/export documentation and handoff. This checkpoint handover is not those final deliverables. |

## Practical continuation notes

Use `adb devices -l` to re-identify devices; always specify the chosen serial and redact it from shared reports. Do not hardcode the old S23 target. Owner package is `com.reader.app`; isolated QA is `com.reader.app.qa` built with `-PreaderQa=true`. The opt-in UI test skips without its transient `qaPairing` argument and refuses the normal package. Do not print/store bearers in Git.

Builds: `cd chrome && npm ci && npm run typecheck && npm test && npm run build`; `cd android && ./gradlew test lint assembleDebug assembleDebugAndroidTest`. Use `READER_DIST` with an absolute isolated output directory while the owner's extension is running; the default build overwrites the live `chrome/dist`. `scripts/package-chrome-extension.sh` accepts that same output override. Read build-audit-artifacts before use: it requires a clean tracked tree, rebuilds default dist and normal app, and runs reference tests.

JDK17 / Node22 were used. Rust is installed in a rustup toolchain but may not be on PATH. The shell startup warning about missing `.cargo/env` was environmental. Final Rust/Swift suites and fresh-checkout reproducibility have not been established for this checkpoint. Local localhost/Gradle/ADB failures sometimes needed the supported sandbox approval path, not code changes.

Do not merge, force-push, publish releases, reset/clear owner data, or expand security permissions without authorization. The owner explicitly authorized pushing this branch/handover. Do not spawn subagents unless the user or applicable instructions explicitly authorize them. Public probes must remain paced, synthetic, encrypted and disposable; use controlled relays for fault/load campaigns.
