# Reader implementation handoff — focused repair and TCL trial

Date: 11 September 2026
Branch: `codex/reliability-finalize`
Branch tip: `eca253a`
Product source tip: `75b369b`

The scoped Reader polish plan has been implemented through product commit `75b369b`. `eca253a` is the follow-up test-only correction for the current Highlights sheet navigation; it does not change the shipped app. Focused host and device checks are recorded below. The normal `com.reader.app` package is now installed on the TCL in place, its local APK hash matches the installed `base.apk`, owner summaries match before and after installation, and it was launched once after that comparison. The APK is ready for the user's seven-day trial.

Three small visual observations remain deliberately **NOT MEASURED** on the post-fix build: the Green/double wrapped E-ink rule, the corrected normal/E-ink Speed focal cue, and one enlarged-system-text layout check. Their pre-fix findings and the source repairs are recorded, and the ordinary wrapped monochrome mark plus the key Review path passed on the S23. These are bounded follow-ups if the next quiet QA opportunity exists; they are not a reason to run another campaign or to disturb the owner's trial device. There is no release, cloud, or seven-day comfort claim here.

## Current source

The product chain is local on `codex/reliability-finalize`:

| Commit | Scope | Status |
| --- | --- | --- |
| `6b0ff3a` | One selection-session identity through native Highlight, handle changes and Undo | Product repair |
| `377bdfb` | Monochrome highlight edges derived from native layout geometry | Product repair |
| `952e793` | Restores the normal-theme Speed focal color while retaining E-ink emphasis | Product repair |
| `fa1bea7` | Keeps source context reachable from feed and Review | Product repair |
| `a4140c3` | Android polish packages A–E: navigation, reading controls, focus window, highlight presentation, Review entry, search/Find states and feedback | Product foundation |
| `8b89a7f` | Chrome package F: per-element visual-block extraction markers and boundary counterexamples | Product foundation |
| `80f39c4` | Copy deck, implementation record and prior evidence | Historical record |
| `108d882` | Native monochrome edge metrics use actual `TextPaint`/span runs below API 34; API 34 path retained | Product repair |
| `b9995be` | RSVP focal fragments share the first-baseline metrics while normal blue and E-ink bold/underline roles remain | Product repair |
| `33d450b` | Removes the duplicate Compose `style` argument introduced by the RSVP correction | Compile-only repair |
| `75b369b` | Gives the monochrome double edge a dedicated thinner stroke so two rules remain visible in the Comfort gap | Product repair; current source |
| `88830f5`, `bb85210`, `24b3cca` | Focused instrumentation corrections (callback, Review setup, partial bidi endpoint) | Test only |
| `eca253a` | Removes a stale `Back to highlights` tap and accepts the localized notice close action in the single completion test | Test only; branch tip |

The implementation keeps Room v13, canonical article text and quote anchors, the native selectable article view and its 8 dp inset, existing preferences, encryption, owner keys, pairings and the four-shelf Reader model. The reading dock exposes **Highlight · Contents · Listen · Speed**. E-ink/NXTPAPER uses the app-wide monochrome and reduced-motion policy. No backend, schema, account, pairing or capture-security redesign was introduced in this repair chain.

## Artifacts and hashes

The latest normal and QA APKs are archived locally. APK binaries remain local build artifacts and are not committed.

| Artifact | Source | SHA-256 | Status |
| --- | --- | --- | --- |
| [`artifacts/repairs-20260911/reader-debug-75b369b.apk`](artifacts/repairs-20260911/reader-debug-75b369b.apk) | `75b369b` normal | `d3bdcdae7dc1dae525d5b04805fe31aaf331eb4cb98420df8163dd8278ea0236` | Installed on TCL; exact local source artifact |
| [`artifacts/repairs-20260911/reader-qa-75b369b.apk`](artifacts/repairs-20260911/reader-qa-75b369b.apk) | `75b369b` QA | `825277511e2d74a0ed531f52ca99b7004694c00bfdf794227c8216c6205f79db` | Installed on S23 for focused checks; isolated package |
| [`artifacts/reader-debug.apk`](artifacts/reader-debug.apk) | copy of `75b369b` normal | `d3bdcdae7dc1dae525d5b04805fe31aaf331eb4cb98420df8163dd8278ea0236` | Convenience copy of the installed candidate |
| [`artifacts/repairs-20260911/reader-debug-pre-repairs.apk`](artifacts/repairs-20260911/reader-debug-pre-repairs.apk) | pre-repair owner build | `8dc3d733635189852151f908de4c0b261fc131b0a130c974f0e7df8430c0b6e1` | Preserved rollback/reference |
| [`artifacts/repairs-20260911/reader-debug-108d882.apk`](artifacts/repairs-20260911/reader-debug-108d882.apk) | `108d882` normal | `db7bf6415f18dcb8ce0f9669faf3d6a628aca38d0ed1b5531cc17295f4e740bc` | Historical focused candidate |
| [`artifacts/repairs-20260911/reader-qa-108d882.apk`](artifacts/repairs-20260911/reader-qa-108d882.apk) | `108d882` QA | `1d664824f4792484af3f41a05a23b8f68defabf999d1cea5d452fc30a325b69e` | Historical TCL QA candidate |
| [`artifacts/repairs-20260911/reader-debug-33d450b.apk`](artifacts/repairs-20260911/reader-debug-33d450b.apk) | `b9995be` + `33d450b` normal | `53a5337b02d8886d27d4d271d99232996faa5f4960fb370876afc0c23a77201e` | Historical pre-`75b369b` candidate |
| [`artifacts/repairs-20260911/reader-qa-33d450b.apk`](artifacts/repairs-20260911/reader-qa-33d450b.apk) | `b9995be` + `33d450b` QA | `3af1f3889f61c345dee3d13e9eed9cf9f8b6d3ee04955cdba3d7af3e7d7830b9` | Historical pre-`75b369b` QA candidate |

The public Android debug certificate hash for the latest normal APK is `3af50cab6e0515f478413e79786958671913b2cae67037defe537bb1e7465069` ([`normal-75b-certificate.txt`](evidence/repairs-20260911/normal-75b-certificate.txt)). No private key or key-bearing preference value was exported.

## Host checks

Checks remain intentionally proportionate to the user's request. There was no 1,000/10,000 corpus, 100-article intake, 120-minute session, soak run, exhaustive device matrix or statistical performance campaign in this checkpoint.

- The fast Android unit suite recorded **PASS** at `fa1bea7` (the exact aggregate count is not reasserted here). `NativeMonoFallbackTest` recorded **1/1 PASS** after `108d882`; the visual-only `75b369b` delta did not justify rerunning the entire suite.
- `lintDebug`, normal `assembleDebug` and QA `assembleDebug -PreaderQa=true` passed for the current product source. The only reported lint item was the existing `RsvpScreen.kt:106` condition warning.
- `:app:verify16KbAlignment` passed for all 12 native library entries. Whole-APK ZIP/container alignment was not measured and must not be inferred from this result.
- Earlier Chrome extraction/typecheck/build checks remain historical evidence for `8b89a7f`; this repair did not repeat the 50 + 50 intake.

## Device results

### TCL — `ZXKRS4VKGQ8PWGEQ` (T807D, Android 16/API 36)

The normal package was installed in place after the final source build. The exact serial includes the trailing `Q`.

1. The WAL-aware owner summary before installation recorded 2 documents, 2 highlights and 1 channel across eight compared Room tables, schema 13, plus a preferences digest. The after summary is identical and reports `ownerDataPreserved: true`: [`owner-final75b-before.json`](evidence/focused-20260910/owner-final75b-before.json), [`owner-final75b-after.json`](evidence/focused-20260910/owner-final75b-after.json).
2. The on-device digest of `shared_prefs/reader_keys.xml` before and after install was recorded without reading or exporting its contents: [`tcl-owner-key-digest-before.txt`](evidence/repairs-20260911/tcl-owner-key-digest-before.txt) and [`tcl-owner-key-digest-after.txt`](evidence/repairs-20260911/tcl-owner-key-digest-after.txt). Both are `c076b845a4eab1d06b2797df4c946c54a59c396b90d72aab8df59118b4596203`.
3. `com.reader.app` was updated with `artifacts/repairs-20260911/reader-debug-75b369b.apk`. The pulled installed `base.apk` hash is `d3bdcdae7dc1dae525d5b04805fe31aaf331eb4cb98420df8163dd8278ea0236`, matching the local file: [`tcl-normal-installed-base-sha256.txt`](evidence/repairs-20260911/tcl-normal-installed-base-sha256.txt).
4. The app was launched once **after** the before/after comparison. The owner's Reader library, Continue reading card and Reader/Highlights/Settings navigation were visible in [`tcl-normal-post-install.png`](evidence/repairs-20260911/tcl-normal-post-install.png). Any later owner reading change belongs to the user's trial, not installation proof.
5. The earlier isolated TCL QA package was the `108d882` candidate (`1d664824…`). Its `HighlightSessionInstrumentedTest` recorded **2/2 PASS** for one handle adjustment and one ordinary-menu Highlight plus boundary adjustment ([`highlight-session-results.xml`](evidence/repairs-20260911/highlight-session-results.xml)). The corrected bidi geometry test was not rerun on TCL. The QA walkthrough left QA-only session/orphan rows; no owner package or owner data was used for those mutations. The latest `75b369b` QA APK was not installed on TCL after the normal install.

**TCL installation verdict: PASS for in-place APK/hash, logical owner-summary preservation and equal on-device key digests; physical e-ink refresh, ghosting, battery and sustained comfort: NOT MEASURED.**

### S23 — `R3CW404GVBL` (SM-S918B)

The isolated `com.reader.app.qa` package used the latest QA APK (`825277511e2d74a0ed531f52ca99b7004694c00bfdf794227c8216c6205f79db`). The normal S23 package was never installed or mutated. The device is no longer part of the handoff gate.

- Corrected `NativeMonoGeometryInstrumentedTest`: **1/1 PASS**, [`s23-native-mono-results.xml`](evidence/repairs-20260911/s23-native-mono-results.xml) and [`s23-native-mono-test-results.log`](evidence/repairs-20260911/s23-native-mono-test-results.log).
- `UiCompletionInstrumentedTest#quoteTapStartsReviewAndSingleRemovalHasUndo`: **1/1 PASS** after the test-only sheet-route/localization correction, [`s23-ui-completion-results-pass.xml`](evidence/repairs-20260911/s23-ui-completion-results-pass.xml) and [`s23-ui-completion-test-results-pass.log`](evidence/repairs-20260911/s23-ui-completion-test-results-pass.log). The first timeout was a stale test tap for `Back to highlights`, not a product failure; its raw evidence is retained for auditability.
- Ordinary long-press → handle extension → Highlight → Back produced a wrapped two-line monochrome mark whose fill and edge geometry agree: [`s23-native-wrapped-mono.png`](evidence/repairs-20260911/s23-native-wrapped-mono.png), **PASS**.
- The Green/double screenshot was captured before `75b369b`'s thinner-stroke repair and still looked single-line: [`s23-native-wrapped-double.png`](evidence/repairs-20260911/s23-native-wrapped-double.png), **NOT MEASURED post-fix**.
- Review card → Review at the quote → Open source at the saved passage → Back returned to the same quote: [`s23-review-source.png`](evidence/repairs-20260911/s23-review-source.png), [`s23-review-open-source.png`](evidence/repairs-20260911/s23-review-open-source.png), [`s23-review-back.png`](evidence/repairs-20260911/s23-review-back.png), **PASS**. This path does not need to be repeated.
- Important removal through Review → feed More → Remove passed in [`s23-highlight-removed.png`](evidence/repairs-20260911/s23-highlight-removed.png). The manual Snackbar Undo tap was **NOT MEASURED** because the automation round-trip outlasted the notice; the single completion test above is the focused Undo proof.
- The pre-fix normal-theme Speed baseline showed the blue focal `n` lower than its neighbors in [`s23-speed-normal.png`](evidence/repairs-20260911/s23-speed-normal.png). `b9995be`/`33d450b` corrected the source alignment; post-fix normal and E-ink screenshots were **NOT MEASURED**.
- An attempted one-line `ACTION_SEND` payload was split by shell quoting and saved no article. [`s23-share-saved.png`](evidence/repairs-20260911/s23-share-saved.png) and [`s23-share-one-line.png`](evidence/repairs-20260911/s23-share-one-line.png) are honest blocked-path evidence, not intake proof. The final QA residue was one sample Welcome article; no normal S23 data changed.

## Remaining work plan

The remaining work is deliberately small and can be postponed while the user evaluates the installed build. Do not turn it into a new acceptance campaign.

1. **Optional visual closure on an isolated QA device.** Install only `reader-qa-75b369b.apk` in `com.reader.app.qa` when a quiet device is available. Use the existing sample or one tiny saved-text fixture; do not seed the database directly. Capture exactly three views: one wrapped Green/double mark in E-ink, one paused Speed word in normal color, and one paused Speed word in E-ink. The enlarged-system-text layout spot check remains unmeasured and can be observed naturally during the trial rather than adding a matrix. If the double rule still collapses or the focal glyph clips/drops, make the smallest source correction, run the affected lint/build or focused test, and repeat only that failed view. One 18–20 letter token such as `internationalization` is enough to expose obvious clipping; do not run a font/token matrix.
2. **Keep known passes closed.** Do not rerun the two identity tests, Review source/Back flow, ordinary wrapped mono flow, 100-article intake, broad instrumentation, or performance campaign merely to fill a checklist. Treat the stale-route timeout and the failed shell-quoted share as harness/fixture limitations already explained above.
3. **Seven-day user trial.** Leave the normal TCL build in place. The user should read naturally and note only visible stalls, lost position, incorrect highlight ranges/colors, missing Review entry/CTA, search/Find failures, broken focus space, or Speed/TTS regressions. A report with the article, mode (normal/E-ink), and one screenshot is enough to target the next fix. Do not add telemetry or ask the user to perform a timed session.
4. **If a trial bug appears.** Reproduce it first in QA with the smallest realistic fixture, preserve article text/anchors and existing preferences, fix only the affected layer, run the relevant fast check, then install a new normal build only after repeating the same digest-only owner proof on TCL. Never clear, uninstall or seed `com.reader.app`.
5. **Close documentation after the optional view.** Add the three captures and exact result to `evidence/repairs-20260911/manifest.json`, update this handoff and the top pointers in `START_HERE.md`/`CONTINUE.md`, and retain the four pre-existing XML captures under `evidence/polish-20260911/`. Keep APKs local and commits local; do not push, merge or release.

## Safety and ownership protocol

- `com.reader.app` is the owner's package. It may remain installed and may receive the authorized in-place TCL update already recorded above. It must not be cleared, uninstalled, seeded with QA content, or used for destructive tests. A future reinstall requires a new digest-only before/after comparison.
- `com.reader.app.qa` is the isolated package for fixtures, removal, Review and focused instrumentation. Its residue is not owner state and should be described rather than silently “cleaned” through the normal package.
- Never pull, print, copy or decode `reader_keys.xml`, private keys or pairing secrets. On-device hashes of key-bearing files are the maximum permitted proof. Public package certificate hashes are safe.
- The exact normal APK, installed hash, source commit, device serial/model and owner-summary equality must be recorded for every future in-place update. Launch only after the comparison; record post-launch reading changes separately.
- Preserve the user's seven-day trial as the next evidence. No cloud action, publication, push, merge, release or unrelated app interaction is authorized by this handoff.

## Starter prompt for the next agent

> Work locally in `/Users/schober/Projects/Nostr Reader` on branch `codex/reliability-finalize`. Read `IMPLEMENTATION_HANDOFF_20260911.md`, `evidence/repairs-20260911/manifest.json`, `START_HERE.md`, `CONTINUE.md` and `docs/COPY_DECK.md`. Product source is `75b369b`; branch tip `eca253a` adds only the focused completion-test correction. The normal `com.reader.app` package is already installed on TCL serial `ZXKRS4VKGQ8PWGEQ` with local/installed APK SHA-256 `d3bdcdae7dc1dae525d5b04805fe31aaf331eb4cb98420df8163dd8278ea0236`; owner summaries matched before and after. Do not clear, uninstall, seed or destructive-test the owner package. Do not touch S23 normal. Use `com.reader.app.qa` for any optional fixture. Do not repeat the 100-article intake, broad instrumentation, soak session, performance campaign, or the passed identity/Review paths. The only unmeasured focused views are post-`75b369b` Green/double wrapped E-ink rendering, post-fix Speed in normal and E-ink, and one enlarged-system-text layout spot check; capture at most the three views if a device is quiet. If one fails, make the smallest fix and rerun that affected check. If they are not needed, stop and leave the installed build for the user's seven-day trial. Report `PASS`, `FAIL`, `NOT MEASURED` or `BLOCKED` with exact evidence paths; never claim physical e-ink quality or long-term comfort from screenshots.

## Final status

| Axis | Status |
| --- | --- |
| Scoped product implementation | **PASS through `75b369b`** |
| Host fast checks | **PASS** (full fast suite at `fa1bea7`, lint/build, native-library alignment; exact aggregate count and whole APK alignment are not reasserted) |
| TCL normal install and owner logical preservation | **PASS**; installed hash verified, launched once after comparison |
| TCL key continuity after install | **PASS** (on-device before/after digest equal; values never exported) |
| S23 native geometry and completion/Undo focused tests | **PASS 1/1 each** |
| S23 ordinary wrapped monochrome highlight | **PASS** |
| S23 Review source/Back round trip | **PASS** |
| Green/double wrapped E-ink post-fix view | **NOT MEASURED** |
| Speed post-fix normal/E-ink view | **NOT MEASURED** |
| Enlarged-system-text layout spot check | **NOT MEASURED** |
| Physical E-ink refresh, performance campaign and sustained comfort | **NOT MEASURED** |
| User's seven-day trial | **PENDING user observation** |

This is a local focused implementation and trial handoff. It is not a release certification or a claim that every possible device, article or lifecycle path has been exercised.
