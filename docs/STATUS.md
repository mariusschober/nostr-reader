# Reader status and remaining work

Updated 1 October 2026 at the requested execution handover. Required repairs are implemented locally; Chrome source/package checks and Android focused tests/QA fixture compilation pass. Final Android integrated gates and all current direct acceptance remain pending. No browser/device was operated, application installed or work published. Remote branch/PR state was revalidated. See the [current handover and start prompt](../artifacts/candidate-20261001/EVIDENCE.md); historical PASS results below describe their dated candidate only.

## Source and product boundary

- Branch: `codex/reliability-finalize`, HEAD `c46acef`; the four repairs and existing documentation cleanup are local, uncommitted and unstaged. Remote main is 122 commits behind. The [source manifest](../artifacts/candidate-20261001/source-manifest.json) identifies the handover checkpoint; no final current Android APK exists.
- Android and Chrome declare `0.9.0-beta.1`. Android uses Room **v13**, minimum API 26 and compile/target API 34. Chrome declares version 120+. Those minimums are declarations, not proof of every supported OS/browser version.
- Chrome → Android encrypted capture/delivery and Android local import, URL capture, reading, highlights, global/article Review, labels and service-owned listening are implemented. The source has separate App theme and Display preferences, monochrome light/dark, and a compact focus-compatible pen dock.
- Chrome and Android use TypeScript/Kotlin ports of the shared contract. Rust and Swift are reference packages; there is no finished Mac app, iOS app, or runtime UniFFI integration. See [platform boundaries](PLATFORMS.md).
- This is a source-build beta. No store release or public download is established. The September 14 record says the branch was pushed at the owner's request; that is historical, not a fresh remote check.

## Repair checkpoint 1 October

- **PASS:** journal recovery tests 8/8; actual serialized startup failures reproduced before repair.
- **PASS:** Chrome typecheck, affected tests 59/59, full tests 183/183, isolated build/ZIP verification. Original `chrome/dist` preserved.
- **PASS:** speech focused tests 17/17 and QA fixture compilation.
- **PASS at policy checkpoint:** pen focused tests 6/6; final source/QA fixtures compile. The final ordinary-clear adjustment followed that unit run; its instrumentation assertion remains unexecuted.
- **NOT MEASURED:** final full Android unit/lint/normal and QA APK/alignment gates, resulting-build pen/audio observations, current ChatGPT/Gemini and authenticated capture/offline recovery.
- **NOT PERFORMED:** owner installation, local integration staging/commit, public push/PR/merge/release. The [handover](../artifacts/candidate-20261001/EVIDENCE.md#remaining-work-in-order) gives exact next steps.

## Cleanup verification — 30 September

These checks apply to the current checkout including pre-existing Chrome edits, separately from historical Android proof:

- **PASS:** Chrome typecheck and build (including packaged-output verification).
- **FAIL:** Chrome tests: 147 passed and 2 failed across the full run and a focused relay rerun. The initial run passed 129 tests; 18 relay tests were blocked by localhost restrictions and then passed outside the sandbox. The two failures are ChatGPT/Gemini cases in pre-existing untracked `chrome/tests/inline-mount.test.ts`. No product fix was included in cleanup.
- **PASS:** Android `:app:assembleDebug` rebuilt successfully after downloading missing public dependencies. No Android tests, lint, alignment or device checks were repeated. Temporary build/cache folders were removed after this check.

## Latest recorded Android device candidate — 14 September

Source: `0ca22ac`, Back-cancels-pen-gesture repair. Normal `com.reader.app` APK SHA-256: `003b4863a61aec09d7d4fbc2b4fad82cece9d224ebc89b180834cd77b7d2b0de`.

The [dated evidence record](../artifacts/trial-fixes-20260914/EVIDENCE.md) reports:

- **PASS (host):** lint with 0 errors, 268 unit tests, normal/QA debug builds, and 16 KiB library alignment.
- **PASS (TCL T807D, Android 16):** normal package installed in place; pulled APK matched; eight compared Room tables were unchanged; encrypted key-file digest was unchanged; preferences changed only by one-time `displayMode = STANDARD` materialization.
- **PASS (isolated TCL QA package):** monochrome light/dark and fullscreen dark without a light border; one gesture produced one highlight; Back during a gesture persisted nothing, while the control gesture persisted one row.
- **Historical S23 walkthrough:** the earlier `d58067f` candidate exercised scoped Review, label badges, ordinary selection, Undo, the appearance sheet and listening through Home. That OLED run did not establish NXTPAPER appearance.

Current installed packages, owner data, and live device behavior are **NOT MEASURED** at this handover. Retained `evidence/` is dated historical evidence, not current acceptance. Older APKs, QA APKs, obsolete reports and agent prompts were removed; tracked history remains available through Git. The exact normal September 14 APK and its dated record are retained locally. Generated binaries are excluded from Git.

## Open defect and focused next acceptance

**The historical native-handle defect has a local source repair awaiting direct acceptance.** September 14 TCL/S23 observations found correct stored data but lingering selection. The October 1 repair scopes acknowledgements to the completed intentional gesture and exact native range, guards stale ActionMode teardown, and preserves ordinary final range flushing. Six focused host tests passed before the last ordinary-clear edit; final source and strengthened native/Room fixtures compile. These checks do not prove actual TCL handles disappear.

Required direct outcome remains one stable row/final range, automatic selection removal, immediate second gesture, stale-ack safety, Back/cancellation, visible save failure/retry, ordinary platform actions and bottom-edge autoscroll. Observe the resulting isolated QA build on TCL in monochrome dark and normal/light. No callback-only check or older screenshot earns that pass. Follow the [handover](../artifacts/candidate-20261001/EVIDENCE.md) for final host gates and bounded QA scope. Owner installation requires separate authorization and preservation/signing checks.

Deferred items:

- Appearance/Done header scrolls away at full expansion; drag handle remains.
- Opt-in `ReaderFlowInstrumentedTest` has a recorded Android 16/One UI share-sheet layout assumption. That fixture failure does not establish a product sharing defect.
- Sustained reading/listening comfort, battery and physical electrophoretic refresh/ghosting are unmeasured. NXTPAPER screenshots cannot establish electrophoretic behavior.

## Limits that still matter

- Current authenticated provider interfaces and optional external signers are **NOT MEASURED**. See [source support](SOURCE_SUPPORT.md). Fixture tests do not qualify a signed-in provider UI.
- Statistical delivery reliability, current public-relay behavior, exhaustive OS lifecycle/Doze/reboot/network switching, platform/accessibility matrix, scale/leak/performance and sustained use are **NOT MEASURED**. Controlled relay success does not establish public-network reliability. No new campaign is required by this cleanup.
- Background delivery depends on Android scheduling, connectivity and relay availability. Force-stop requires reopening the app. Background listening is now service-owned; old claims that all speech pauses on Home are superseded. Calls/headsets and sustained playback remain unqualified.
- Very large articles use bounded parts. Native selection is limited to the current part; cross-part dragging is not implemented. Service narration loads subsequent parts automatically; the October 1 source repair preserves Pause intent during loading. Actual section-boundary playback acceptance remains pending.
- Search is local SQLite substring matching: ordinary terms combine with AND, quoted phrases remain contiguous. No semantic ranking, stemming or universal accent folding is claimed; unavailable FTS5 must not break search.
- URL capture fetches static HTML without cookies, authentication, JavaScript execution or subresources. Login/paywall/JS-only/uncertain pages retain an honest link fallback. Infoboxes/references may be dropped. A recapture can create a new content version; older saved text is immutable. RTL indentation refinement remains pending.
- Article images render descriptions; Reader does not fetch remote article images. Explicit external links open outside Reader and have their own network/privacy behavior.
- Export is a readable, checksum-verified ZIP of articles and quote metadata, not a restorable backup. Keys/settings/pairings and exact Review restoration are not a supported restore path. Existing libraries must not be cleared to bypass an upgrade/signature problem.
- NIP-44 has no forward secrecy. Relays observe routing keys, IP, timing and approximate sizes; expiry/deletion cannot erase relay copies or earlier exports. No anonymity or independent security audit is claimed.
- No current dependency audit, hosted CI, reproducibility or release certification was rerun during cleanup. Use actual results for a chosen candidate; historical counts do not certify edited source.

## Data and execution boundaries

Preserve the owner's `com.reader.app` library, keys, preferences and browser profile. Preserve Room v13, canonical article text and quote anchors, the selectable native TextView and its 8dp inset; do not add an outer scrolling container around the article. Never clear or uninstall it as a testing or downgrade shortcut. Use `com.reader.app.qa` and isolated browser profiles for destructive fixtures. Keep connected test APKs installed only when appropriate by using `-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true`.

The four intentionally untracked files `evidence/polish-20260911/21-feed.xml`, `22-feed.xml`, `22-reader-tab.xml` and `23-archive.xml` remain uncommitted. A clean status is not an acceptance requirement. Do not export key contents; the [owner integrity helper](../scripts/qa-owner-integrity.py) uses digests. No release, merge, push or device mutation follows from this status document.
