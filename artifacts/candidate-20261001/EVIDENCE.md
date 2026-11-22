# Reader repair handover

Updated 1 October 2026. This is the execution checkpoint and continuation handover for [the ordered plan](../../docs/PLAN_20261001.md), following [the audit](../../docs/AUDIT_20261001.md). The user requested this handover before final integrated builds and direct acceptance. No further implementation or builds are running.

All four required repairs have been implemented locally. Chrome source checks and its isolated package pass. Android focused tests and final QA fixture compilation pass, with the pen coverage timing qualification below. **The full final Android unit/lint/build/alignment gate has not run, no current Android APK has been assembled, and no device/browser acceptance, owner installation or publication has occurred.** Continue with verification and packaging; do not repeat the audit or recreate completed repairs.

## Repository and branch state

| Item | Revalidated state |
|---|---|
| Local checkout | `/Users/schober/Projects/Nostr Reader` |
| GitHub repository | [mariusschober/nostr-reader](https://github.com/mariusschober/nostr-reader) |
| Active local branch | `codex/reliability-finalize` |
| Local HEAD and remote development tip | `c46acefda74a26c70dbfee3fae1aacd8e8d11ab0` |
| Remote main | `1e55b7d4021637520402cb1773ac4e1ffd3b43cf`, 122 commits behind development |
| Historical local branch | `codex/reliability-data-integrity` has one unique documentation commit, `73991c9`; it is not a missing implementation package |
| Open PR | [PR 1 Add Cloud Agent development environment](https://github.com/mariusschober/nostr-reader/pull/1), unrelated to product integration |
| Git index | Empty; no files staged and no new commits created |

Remote refs and PR state were checked again when writing this handover. **The repairs and cleanup are in the dirty local checkout, not on GitHub.** Another clone or a new worktree will not contain them automatically. Continue in this checkout or reconcile every relevant tracked and untracked change explicitly. Do not reset to the hashes above if newer work appears.

The initial 106 status entries included the authorized documentation cleanup, pending Chrome edits, audit/plan and protected XML captures. The many deleted old handoffs/screenshots/scripts are intentional cleanup. Preserve that cleanup; do not restore the deleted documentation set or merge historical branches wholesale.

A local [source manifest](source-manifest.json) records every nonignored file under `android/`, `chrome/` and `shared/`, including new untracked tests. It is a **handover snapshot, not final Android build proof**:

- Runtime source manifest SHA-256: `d4157f85a73bc8e87c69b2ded850d44b9ba1fe19c1aace6678a7ec970aed5c50`.
- Tracked runtime binary patch SHA-256 against HEAD: `18ad63457ecb478a0fc617f9c3056b31b45796894c715418929a40e0c4e131bc`.
- Local checkpoint helper: `/private/tmp/reader-20261001-freeze.py`; `--check` compares current runtime files with the saved manifest. Revalidate temporary-file availability before relying on it.

## Completed repairs and verification

| Plan phase | Implemented behavior | Executed checks | Direct acceptance |
|---|---|---|---|
| 1 Highlight recovery | Valid storage/cleanup failures remain pending; only malformed/oversized input is quarantined; same-ID ordering and idempotent cleanup; visible typed recovery issue | **PASS 8 focused tests**, including four real serialized startup-journal fault cases; Kotlin/UI compiled | **NOT MEASURED** on device; fault injection used temporary host files |
| 2 Chrome control safety | Conservative editable/ancestor guards, real observer reconciliation, scheduled cooldown, two-remount cap, streaming/replacement safety, source-text preservation | **PASS** typecheck, **59 affected tests**, **183 total tests in 25 files**, isolated build, package verification and ZIP parity | **NOT MEASURED** on current authenticated ChatGPT/Gemini |
| 3 Speech intent | Pause/focus loss survives initial/next-part loading; guarded pending START/replacement; Back keeps speech; Speed pauses | **PASS 17 focused tests**; isolated QA speech fixtures compile | **NOT MEASURED** for physical playback, audible speech, screen-off or notification controls |
| 4 Pen settlement | Acknowledgement owns the completed gesture and exact range; stale mode/ack protection; ordinary committed range flushed before clear; failures retained | **PASS 6 focused tests at policy checkpoint**; final source and strengthened QA fixtures compile | **NOT MEASURED** for actual handles, next gesture, themes, autoscroll or platform actions |

### Highlight recovery

Files: [ReadingSession.kt](../../android/app/src/main/java/com/reader/app/data/ReadingSession.kt), [ReadingSessionTest.kt](../../android/app/src/test/java/com/reader/app/ReadingSessionTest.kt), recovery UI in [PreparedReaderScreen.kt](../../android/app/src/main/java/com/reader/app/ui/screens/PreparedReaderScreen.kt). Existing [HighlightRepository.kt](../../android/app/src/main/java/com/reader/app/data/HighlightRepository.kt) idempotence was retained.

The original serialized startup replay failed the new save-failure test by turning valid `.json` into `.bad`. The final implementation retains valid work after save or post-commit cleanup failure, quarantines invalid UTF-8/JSON/anchors/oversized input, preserves old quarantine evidence, and prevents later commands for a failed identity from overtaking it. Unrelated selections can continue. Same-process cleanup retry reuses the committed mutation; restart relies on existing repository idempotence. Retry scans exclude files already owned by live commands. Cancellation is not reported as corruption or a storage error.

The reader shows the actual recovery issue. Retry appears only for retryable work. An unrelated successful save does not hide retained or quarantined work.

```sh
# From android/, with the environment below
./gradlew --no-daemon :app:testDebugUnitTest --tests com.reader.app.ReadingSessionTest
```

**PASS 8/8.** New cases cover transient save failure and exactly-once recovery; corrupt/oversized quarantine plus later valid save; commit/cleanup failure, ordering and idempotent restart/final range; cancellation retention. The first repair attempt caught and then corrected an ordering error. Logs: `reading-session-red.log`, `reading-session-green-attempt1.log`, final `reading-session-green.log` in the local log directory below.

### Chrome controls and package

Files: [capture.ts](../../chrome/src/content/capture.ts), [adapters.ts](../../chrome/src/providers/adapters.ts), [inline-mount.test.ts](../../chrome/tests/inline-mount.test.ts), new [inline-observer.test.ts](../../chrome/tests/inline-observer.test.ts), [capture-selection.test.ts](../../chrome/tests/capture-selection.test.ts), [package.json](../../chrome/package.json) and [package-lock.json](../../chrome/package-lock.json).

The two baseline action-row failures were foreign-window JSDOM elements checked against production's ambient `Element`. The fixtures now use the ambient document; production safety was not weakened. Actual MutationObserver fixtures exercise editable response/ancestor transitions and return to read-only, removal without a later unrelated mutation, bounded repeated stripping, streaming completion, cloned response replacement and provider mutations during insertion. Composer/textbox/link/button/code/table ancestors receive no inline control. Extraction excludes owned UI while preserving ordinary Reader text, Markdown, selection and uncertain-preview behavior.

DOMPurify alone changed from 3.4.14 to 3.4.16 for [GHSA-p98j-92pf-mc4p](https://github.com/advisories/GHSA-p98j-92pf-mc4p). No Reader-reachable exploit or comprehensive new dependency audit is claimed.

```sh
# From chrome/
npm run typecheck
npm test -- tests/inline-mount.test.ts tests/inline-observer.test.ts tests/capture-selection.test.ts tests/capture-feedback.test.ts tests/extraction.test.ts
npm test
READER_DIST='/Users/schober/Projects/Nostr Reader/artifacts/candidate-20261001/dist' npm run build

# From repository root
READER_DIST='/Users/schober/Projects/Nostr Reader/artifacts/candidate-20261001/dist' sh scripts/package-chrome-extension.sh artifacts/candidate-20261001/reader-chrome-extension.zip
```

**PASS:** typecheck; affected 59/59; full 183/183 once on final Chrome source; build and standalone content-script/manifest/Chromium-text verification; ZIP integrity and exact parity with dist. Localhost relay fixtures used the authorized host environment. Logs: `chrome-typecheck.log`, `chrome-affected.log`, `chrome-full.log`, `chrome-build.log`, `chrome-package.log`, `chrome-package-source.json`.

Ready local outputs, excluded from Git:

| Output | SHA-256 |
|---|---|
| `artifacts/candidate-20261001/dist/content.js` | `466d1c8f4a80a1e50670e5c9953f83b339dcaf38ef0f7dfeb6a1ab1f09e83e13` |
| `artifacts/candidate-20261001/reader-chrome-extension.zip` | `715a7067f2439b7236f4d665bd9f0cf4b0a9121c6d7d882be7d3e940c9541722` |

The isolated `dist/` is ready to load; the original `chrome/dist` was preserved. Do not rerun passing Chrome gates after documentation-only edits. If Chrome source changes, repeat affected checks and rebuild before accepting it.

### Speech intent and navigation

Files: [TtsNarrator.kt](../../android/app/src/main/java/com/reader/app/tts/TtsNarrator.kt), [TtsPlaybackService.kt](../../android/app/src/main/java/com/reader/app/tts/TtsPlaybackService.kt), [TtsPlaybackClient.kt](../../android/app/src/main/java/com/reader/app/tts/TtsPlaybackClient.kt), [TtsMediaPlayer.kt](../../android/app/src/main/java/com/reader/app/tts/TtsMediaPlayer.kt), [TtsPlaybackState.kt](../../android/app/src/main/java/com/reader/app/tts/TtsPlaybackState.kt). UI changes are in [MainActivity.kt](../../android/app/src/main/java/com/reader/app/ui/MainActivity.kt), [PreparedReaderScreen.kt](../../android/app/src/main/java/com/reader/app/ui/screens/PreparedReaderScreen.kt), [TtsBar.kt](../../android/app/src/main/java/com/reader/app/ui/screens/TtsBar.kt) and [NowPlayingBar.kt](../../android/app/src/main/java/com/reader/app/ui/screens/NowPlayingBar.kt).

Tests: [TtsNarratorTest.kt](../../android/app/src/test/java/com/reader/app/TtsNarratorTest.kt), new [TtsPendingStartTest.kt](../../android/app/src/test/java/com/reader/app/TtsPendingStartTest.kt), new [TtsNavigationInstrumentedTest.kt](../../android/app/src/androidTest/java/com/reader/app/TtsNavigationInstrumentedTest.kt); controller tests remain in [RsvpTtsTest.kt](../../android/app/src/test/java/com/reader/app/RsvpTtsTest.kt).

Deferred-loader tests first reproduced **9 tests, 4 expected failures**. Final checks passed 11 narrator, 4 actual service pending-START coordinator and 2 controller tests. Requested playback is distinct from active speech, keeping Pause available during loading. Stop/replacement invalidate old metadata and engine callbacks. Back/library navigation preserves service speech and spoken progress. Listen replacement, Speed and Finish & archive explicitly pause; deletion/Stop retain deliberate stop policy. Visible native cursor updates do not overwrite the spoken cursor while playback intent owns progress. Google remains the explicitly selected engine.

```sh
./gradlew --no-daemon :app:testDebugUnitTest --tests com.reader.app.TtsNarratorTest --tests com.reader.app.TtsPendingStartTest --tests com.reader.app.TtsControllerTest
./gradlew --no-daemon -PreaderQa=true :app:compileDebugAndroidTestKotlin
```

**PASS 17/17; PASS compile.** Logs: `tts-red.log`, `tts-green.log`, `tts-instrumentation-compile.log`. QA class `com.reader.app.TtsNavigationInstrumentedTest` requires `qaSpeechLifecycle=true`. It prepares Play → Back/library → Home → Media3 Pause/resume → Speed, persisted spoken progress, and a bounded real-Google-engine deferred section-boundary Pause/resume. These assertions have not executed. MediaSession state alone cannot certify audibility; screen-off and actual notification UI remain separate observations.

### Pen settlement

Files: [NativeArticleView.kt](../../android/app/src/main/java/com/reader/app/ui/screens/NativeArticleView.kt), the pen callback in [PreparedReaderScreen.kt](../../android/app/src/main/java/com/reader/app/ui/screens/PreparedReaderScreen.kt), [PenSettlementInstrumentedTest.kt](../../android/app/src/androidTest/java/com/reader/app/PenSettlementInstrumentedTest.kt), [HighlightSessionInstrumentedTest.kt](../../android/app/src/androidTest/java/com/reader/app/HighlightSessionInstrumentedTest.kt), new [PenSettlementPolicyTest.kt](../../android/app/src/test/java/com/reader/app/PenSettlementPolicyTest.kt).

The old acknowledgement required the live ActionMode session ID to equal the saved ID, although platform teardown/recreation could replace that owner without a new intentional gesture. Completion now checks intentional-gesture generation and the exact native range. New gestures/cancellation invalidate old acknowledgements; delayed destruction of an old ActionMode cannot reset its replacement. `clearSelection()` flushes an already committed ordinary range before finishing the mode, preserving a pending final handle adjustment. Cancelled pen gestures remain unsaved. Preparation errors show failure; queued save failures retain the draft and visible Retry state. Native text, its 8dp inset and scrolling/autoscroll were preserved.

```sh
./gradlew --no-daemon :app:testDebugUnitTest --tests com.reader.app.PenSettlementPolicyTest --tests com.reader.app.NativeArticleSelectionTest
./gradlew --no-daemon -PreaderQa=true :app:compileDebugAndroidTestKotlin
```

**PASS 6 tests** (policy 5, overlap 1), then **PASS final compile**. Logs: `pen-policy.log`, `pen-instrumentation-compile.log`. **Coverage timing:** the unit run preceded the final ordinary `clearSelection()` preservation edit; the ownership policy did not change afterward. The final ordinary-clear assertion compiled but did not execute. The required final integrated unit/lint/build gate remains pending.

The strengthened isolated QA fixtures acknowledge only after actual ReadingSession/Room persistence. They assert stable IDs/final quotes, selection removal, an immediate second gesture, stale acknowledgement without an artificial intermediate clear, Back/cancellation, retained selection after save failure, retry of the same draft, and ordinary clear before debounce. They are compile evidence only. Actual native handles and physical bottom-edge autoscroll must still be observed on TCL in monochrome dark and normal/light.

## Toolchain and local evidence

- Node `22.16.0`, npm `10.9.2`; locked Chrome dependencies restored with `npm ci`. The first sandbox DNS attempt failed; the authorized host retry passed. This was an environment failure.
- Gradle wrapper `8.9`; task-local Temurin JDK `17.0.20.1+1`; Android SDK platform/build-tools 34. System Java is not the intended JDK.
- Current Android configuration: version `0.9.0-beta.1`, code 2, Room v13, min API 26, compile/target API 34. `-PreaderQa=true` produces `com.reader.app.qa` using the **same debug output path** as normal `com.reader.app`.
- Existing cache: `/Users/schober/.gradle`. Its writes required the authorized host environment. Do not retry a forbidden lock path; use that authorization or a writable task cache.
- Command logs and starting-state fingerprints: `/Users/schober/Projects/Nostr Reader/artifacts/qa-runs/20261001/`, intentionally excluded from Git. Selected Gradle runs replace the current XML report set; retained phase logs are the evidence for earlier checks.
- Initial preservation snapshot: `/var/folders/xy/bg1y6lf52nd48j5q9ymy8_th0000gn/T/reader-20261001-qehltj3m` (status, tracked binary patch, untracked copies, Chrome dist ZIP). Initial patch SHA-256 `1d1abafa67fd3e3a68e28f654eab1221800eee4ac3b2a564f1291b870c0fc33a`.

```sh
export JAVA_HOME='/private/tmp/reader-20261001-jdk17/jdk-17.0.20.1+1/Contents/Home'
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_HOME='/Users/schober/Library/Android/sdk'
export GRADLE_USER_HOME='/Users/schober/.gradle'
```

Revalidate these paths before continuing; temporary JDK/snapshot/helper files are not permanent project dependencies.

## Preservation and authorization

No device or browser was operated. No owner data/key contents were read or used as a fixture. Current installed APK/data are **NOT MEASURED**; the audit recorded the September 14 owner candidate, but this handover does not establish what is installed now.

The original `chrome/dist/content.js` still hashes to `7b6343887ba15b6aa8f83c012030e03856069eb2104c231f91be356df6761812`. Retained `artifacts/trial-fixes-20260914/reader-debug-0ca22ac.apk` still hashes to `003b4863a61aec09d7d4fbc2b4fad82cece9d224ebc89b180834cd77b7d2b0de`. Its verified debug signing certificate SHA-256 is `3af50cab6e0515f478413e79786958671913b2cae67037defe537bb1e7465069`; compare the new normal APK before any separately authorized owner update.

The four captures below were rehashed unchanged and remain untracked/uncommitted. Never include them in integration:

- `evidence/polish-20260911/21-feed.xml`
- `evidence/polish-20260911/22-feed.xml`
- `evidence/polish-20260911/22-reader-tab.xml`
- `evidence/polish-20260911/23-archive.xml`

Preserve normal `com.reader.app` library, keys, pairing, preferences, Review, highlights and signing identity. No uninstall, clear-data, downgrade or synthetic seeding. Mutating tests use only `com.reader.app.qa` and an isolated browser profile. Preserve Room v13, immutable canonical text/quote anchors, receipts, native selectable TextView and 8dp inset. No backend, AI dependency or relay-set change. Do not edit saved provider text to strip ordinary Reader strings.

Device availability is unknown. Historical TCL serial: `ZXKRS4VKGQ8PWGEQ`, T807D, Android 16. The audit saw Movetrics foregrounded; acquire exclusive use before launches, QA installation, settings changes or tests. Finish all independent source/build work first. Real-account messages/private-draft changes require explicit authorization; ordinary local edits/builds do not.

## Remaining work in order

1. **Final lead review and Android host gates.** Revalidate dirty state and source manifest. Review the completed pen diff and final ordinary-clear path. From `android/`, run the required gate once on final applicable source:

   ```sh
   ./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest :app:verify16KbAlignment
   ```

   No current APK output exists at handover. Archive the normal APK before the QA flag overwrites `app/build/outputs/apk/debug/app-debug.apk`. Then build `-PreaderQa=true :app:assembleDebug :app:assembleDebugAndroidTest :app:verify16KbAlignment`; archive QA app/test APKs separately. Verify actual package IDs, certificate/update compatibility, build configuration and SHA-256 values. Freeze one integrated candidate and record its source identity. Rust/Swift gates are unnecessary unless shared contracts/their source change. Do not repeat the unchanged passing Chrome suite.

2. **Direct isolated QA acceptance after access is available.** Verify actual serial/package and exclusive device ownership. Run exact class filters for `PenSettlementInstrumentedTest`, `HighlightSessionInstrumentedTest` and `TtsNavigationInstrumentedTest` (`qaSpeechLifecycle=true`); connected Gradle runs must also use `-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true`. Observe actual TCL handles, immediate second gesture, cancellation/save retry, ordinary platform selection and bottom-edge autoscroll in monochrome dark and normal/light. Perform the short listening sequence and a bounded section-boundary Pause. Check audible speech, progress and no double narrator directly; mark audibility/screen-off unmeasured if not observable. No 100-article campaign, every-theme matrix or soak.

3. **Current provider and delivery acceptance.** Load the isolated candidate dist into a permitted test profile. On non-sensitive permitted ChatGPT/Gemini conversations, observe normal/code answers, streaming, edit/Canvas where available, rerender/navigation, safe unoccluded placement and one correct trusted capture. Do not send real-account messages without permission. With isolated test identities, perform one ordinary Chrome → Android capture through authenticated storage receipt and one bounded queued/offline → reopen recovery. Compare payload identity/content, one stored article and truthful sender status. Existing [qa-start.mjs](../../scripts/qa-start.mjs), [qa-cdp.mjs](../../scripts/qa-cdp.mjs), [qa-android.mjs](../../scripts/qa-android.mjs) can support a controlled relay/profile; inspect before use, preserve the relay set and label controlled results as controlled. Browser/device access has not been requested during implementation.

4. **Complete the canonical docs and local integration.** Status/copy/source-support/readme/install/storage/privacy docs were partially reconciled; final result counts, Android artifacts and direct outcomes remain to be added to this record. Correct Google and multipart narration wording throughout current docs, keep dated historical evidence intact, and avoid another stack of handoffs. Prepare the concrete local integration note/title/body and stage only intended cleanup/source/tests/docs, excluding all four XML. Do not automatically merge main.

5. **Owner installation and publication are separate final actions.** Neither is authorized by the plan alone. Only if separately authorized and the device is free, use [qa-owner-integrity.py](../../scripts/qa-owner-integrity.py), compare signing compatibility, install the normal APK in place, compare pulled APK hash and successful owner data/key digests. Never hash failed-command text as key evidence. Public push, product PR, merge, release/store submission also require their own authorization. PR 1 is not the integration PR.

Optional Speed checkpoints, appearance header and extension terminal-status repairs remain **deferred until required repairs and acceptance pass**. Backup/restore, broad device/accessibility/performance qualification, Mac/iOS and new sources remain separate work. Do not spend the remaining effort expanding them.

## Local integration description

Suggested title: **Preserve highlight recovery and make capture and listening obey user actions**.

Suggested body: Valid pending highlights remain recoverable after storage/cleanup failures and surface unresolved work. Chrome controls reconcile editor/rerender transitions without modifying source text. Listening preserves Pause during loading and continues on Back while Speed pauses. Pen acknowledgements are scoped to the completed gesture and range, protecting newer selections and preserving ordinary final adjustments. Includes the existing authorized documentation cleanup. Chrome checks/package and Android focused tests/fixture compilation pass; final Android gates and direct device/provider/delivery acceptance are still pending. No owner update or publication has occurred.

Keep this description truthful as final evidence arrives. It is a local draft, not a published PR.

## Start prompt for the next agent

Continue the remaining execution of `/Users/schober/Projects/Nostr Reader/docs/PLAN_20261001.md`. First read `/Users/schober/Projects/Nostr Reader/artifacts/candidate-20261001/EVIDENCE.md` in full, then the original audit/plan and canonical docs required by the plan. This handover is the latest execution checkpoint; the September 30 status and September 14 device evidence are historical.

Use the existing local checkout `/Users/schober/Projects/Nostr Reader`, branch `codex/reliability-finalize`, repository `https://github.com/mariusschober/nostr-reader`. Recorded HEAD/remote development tip is `c46acefda74a26c70dbfee3fae1aacd8e8d11ab0`; remote main is 122 commits behind. Revalidate actual state and preserve newer work. The completed repairs, new tests and authorized cleanup are local, uncommitted and unstaged. Do not reset, restore intentionally deleted handoffs, start from stale main, or assume a new clone/worktree carries these changes.

Phases 1–4 are implemented. Journal tests passed 8; Chrome typecheck, 183 tests, isolated build/package passed; speech tests passed 17; pen policy/selection tests passed 6. Final Android QA fixture compilation passed. The pen unit run preceded the final ordinary-clear preservation edit, which compiled but whose instrumentation assertion has not run. The Chrome candidate is under `artifacts/candidate-20261001/dist` with a verified ZIP beside it. No current Android APK has been assembled. No direct device/browser acceptance, installation or publication occurred. Logs and source manifest are linked in the handover; do not mistake focused/compile evidence for final acceptance.

State a brief bounded estimate and checklist. Finish final lead review and the required full Android unit/lint/normal build/test-APK/alignment gate first. Archive the normal APK, then separately build/archive QA app/test APKs using `-PreaderQa=true`, since both use the same output paths. Revalidate JDK17/SDK34/cache paths, actual IDs/signatures/hashes and freeze one candidate. Repeat passing checks only if affected source changes; Chrome has already passed its full suite once.

Only after independent host work, acquire exclusive TCL/browser access for the bounded QA and current ChatGPT/Gemini checks in the handover, plus one authenticated capture and one queued/offline recovery using isolated identities. Preserve normal `com.reader.app` data/keys/pairing and Room v13; use only `com.reader.app.qa` for mutating fixtures. Never uninstall/clear/downgrade/seed the owner package. Preserve native TextView/8dp inset, canonical text/anchors, Google TTS and receipt semantics. Keep all four protected `evidence/polish-20260911/{21-feed.xml,22-feed.xml,22-reader-tab.xml,23-archive.xml}` files unchanged and uncommitted. No real-account message/private-draft change, owner replacement, relay-set change, paid API, push/PR/merge/release without explicit authorization.

Update this existing handover/evidence and canonical docs with actual final results; prepare local integration with named-file staging. Report source checks, direct acceptance, owner installation and publication separately as PASS, FAIL, NOT MEASURED or BLOCKED with the exact reason. If access is unavailable, leave the fully built candidate and exact missing acceptance steps. Keep optional plan work deferred. Use one implementation worker only if a substantial repair remains; lead owns review, Git index, build integration and exclusive devices. Do not recreate completed work or run a broad intake/soak/device campaign.
