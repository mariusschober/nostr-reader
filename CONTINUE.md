# Current Reader handover — 9 September 2026

Branch: `codex/reliability-finalize`. Application source: `ca06d1d3ca4005550f1b8cc7a6922c0b34f93ee0`. Later commits update documentation only. App version remains `0.9.0-beta.1` (versionCode 2); this is a source-build beta, not a new store release. Room is v10; Chrome IndexedDB is v3. Wire protocols remain `reader/2` and `reader-pair/2`.

The user authorized pushing this branch and carefully cleaning obsolete documentation on September 9. Main is not merged by that push. Use live Git refs for publication state; do not treat historical local-only statements as current branch status.

## Start here

- [README](README.md): features and source-build setup.
- [UI completion report](UI_UX_COMPLETION_REPORT.md): final installed Android build, tests and scope.
- [ARTIFACTS.json](ARTIFACTS.json): `latestUiCompletion` is the current Android package; preceding entries are historical.
- [Reliability implementation](RELIABILITY_HARDENING.md), [protocol](PROTOCOL.md), [migration](MIGRATION_AND_COMPATIBILITY.md), and [known limitations](KNOWN_LIMITATIONS.md).
- [Documentation index](docs/README.md): current references and retained historical evidence.

## Verified checkpoint

TCL T807D, Android 16/API 36: final normal APK installed in place, exact hash verified, storage files byte-identical across installation and installed database integrity passed. Test packages removed; owner app opened. Display/font/rotation/accessibility settings match their pre-test values. This cycle did not update the S23 or the owner’s Chrome installation.

Android debug/release unit tests: 137 each, lint/build passed. Chrome: 140 tests plus typecheck/build/package passed. Core TCL run: 34 executed tests and one assumption skip on `19c8937`; final imports, strict TalkBack and three additional layout configurations passed on `ca06d1d`. Manual enlarged highlight-editing and Android edge-back checks passed. The core run was not repeated after the narrow final HTML whitespace fix. See the report for the retained failed foreground-guard attempt and unchanged-byte successful retry.

## Preserve these boundaries

Do not clear owner app data, uninstall the normal app, rotate keys, or alter the paired browser profile to make tests pass. Re-identify devices and always specify an ADB serial. Synthetic/fault work uses isolated QA packages/profiles. Generated artifacts and private storage/screenshots stay local; only synthetic screenshots and sanitized summaries are tracked.

Do not infer full beta, public-relay, tablet/foldable, other-device, store-signing, acoustic speech or exhaustive process-death acceptance from these focused checks. Already-flattened imports require reimport; do not rewrite immutable article text or saved quote anchors speculatively. Exports are one-way and exclude pairing keys. No further feature work or repeated test campaign is required by this handover.
