# Implementation checkpoint — 2026-09-08

**PAUSED before reliability campaigns and intensive testing, as requested. This is not beta acceptance.**

The repaired branch's newer work remains in place. The owner Android application, its data/keys, the paired Chrome profile, and `chrome/dist` were not replaced during this implementation stage. The new artifacts below have not been installed or accepted on a physical device. Earlier recovery/selection evidence belongs to earlier builds and is not transferred to this candidate.

## Implemented in source

- Bounded Android content storage and non-destructive schema migrations; metadata-only library queries; independent article observation; bounded section parsing/cache; strict local file intake with the 20 MiB UTF-8 byte limit.
- Versioned rendered-text and narration projections, native selectable reader text, background text measurement, within-block cursor restoration, targeted progress writes, saved navigation, active TTS speed changes/stale-callback guards, audio-focus pause, grapheme-safe RSVP and foreground lifecycle handling.
- Reactive System/Light/Dark themes with preserved explicit reader backgrounds; loaded-state-aware empty Inbox behavior; stable library destinations and original-list Undo.
- Persistent exact quotes, semantic/version/context anchors, four shared Flexoki colors, deterministic overlap display, selection-session updates with Undo, quote-only native sharing, source snapshots retained after article deletion, and one-way JSONL highlight export alongside streamed Markdown article export.
- Highlights feed with stable session Shuffle/Newest; persistent seeded review with base coverage, bounded importance bonuses, explicit actions and swipe accelerators. Quote taps open the source; returning resumes the card.
- Durable Android sync health and manual sync. Speech pauses on background/audio-focus loss; this candidate does not claim background media-service acceptance.
- Chrome optional per-provider site permissions and dynamic registration; shared themes; sanitized structured conversion for code/tables/lists/citations; uncertain-extraction preview; incremental response-control reconciliation; recent sends with individual retry/resend/export/discard actions.
- Conservative preview scopes for Google Notebook, Grok, Substack Home post details, and X details, with matching source-type handling. These are **candidate adapters, not verified live compatibility claims**. The NotebookLM alias and embedded Grok on X are not enabled implicitly.

## Routine verification of this checkpoint

| Check | Result | Evidence |
|---|---|---|
| Android unit tests | PASS: 107 tests, 21 suites, no skips/failures | `evidence/beta/resumed/implementation-android-final.log` and local Gradle XML results |
| Android app and instrumentation APK compilation | PASS | same build log |
| Chrome unit/fixture tests | PASS: 132 tests, 22 files | `evidence/beta/resumed/implementation-chrome-tests-3.log` |
| Chrome type checking | PASS | `evidence/beta/resumed/implementation-chrome-typecheck-final.log` |
| Isolated extension build/package verification | PASS | `evidence/beta/resumed/implementation-chrome-build-3.log` |
| Highlight foreground/background contrast | PASS: all eight combinations; minimum 7.779:1 | `evidence/beta/resumed/highlight-contrast.json` |
| Exact new APK installation, physical UI/selection/sharing, packaged-browser acceptance | NOT MEASURED | deferred by user |
| Final pairing/delivery/offline/replay/worker-kill/browser-restart campaigns | NOT MEASURED for this candidate | deferred by user |

Routine compilation/unit checks do not prove Room upgrade behavior on the owner's installed data, native selection semantics, timing, audio behavior, browser permissions, current provider DOM compatibility, or delivery to Android.

## Known implementation limits and open acceptance work

1. Very large articles use explicit bounded parts (196,608 canonical UTF-16 units per part before synthetic continuation context). Selection can span paragraphs within a part; it cannot drag across the explicit part boundary. Wide tables preserve cell boundaries but narrow-screen wrapping and accessibility need device judgment.
2. Native selection, font changes, transient source emphasis, highlighted links, reverse handle movement, large fonts, gesture Back, process interruption and Sharesheet return still need real-device verification. Source re-resolution is checked within the selected part; an unresolved/ambiguous location preserves the quote and reports unavailable rather than guessing.
3. Provider preview selectors, routes, streaming completion, virtualization, partial/collapsed-content signaling, and captured-source fidelity require actual authenticated product evidence. Do not upgrade a fixture or preview adapter to “supported” without it. Canonical/legacy notebook route verification remains open.
4. The recent-send controls and optional permission lifecycle have local compilation/unit coverage, but their new flows need packaged Chrome UI acceptance. Receipt history is bounded; pending content is retained. More granular per-item receipt refresh and storage-pressure UX can be assessed during that review.
5. The highlight/review repository uses transactional writes and revision-guarded Undo; rapid edits, overlaps, deletion/restoration and process-death races still require database/instrumented acceptance. Feed excerpts are limited to 800 characters; the review screen retains the full quote (up to 128 KiB UTF-8).
6. Progress is coalesced and flushed synchronously on stop; storage contention/lifecycle latency is not measured. TTS covers the current bounded part and does not automatically play the next part. Android platform selection action-mode appearance and themed link spans need visual verification.
7. Export is **one-way export**, not a restorable backup. It includes exact quotes and their review metadata, but no transport keys or pairing secrets. Review queue restore from an exported archive is not implemented or claimed.
8. Final dependency/security recheck, release-candidate freeze, reproducible rebuild comparison, and final exact-artifact cross-runtime/device campaigns remain pending. Do not publish or replace the owner's installed build based on this checkpoint.

## Local artifacts

These are build products for the paused checkpoint, not final tested release artifacts:

- `artifacts/beta-work/pre-campaign/reader-implementation-debug.apk`
- `artifacts/beta-work/pre-campaign/reader-implementation-debug-androidTest.apk`
- `artifacts/beta-work/pre-campaign/reader-implementation-chrome.zip`
- `artifacts/beta-work/pre-campaign/manifest.json`

Exact SHA-256 values are also committed in `evidence/beta/resumed/pre-campaign-artifact-hashes.json`. The APK uses the normal debug application ID: it has deliberately **not** been installed over the owner application.

## Resume boundary

Wait for the user's next prompt before starting reliability campaigns or intensive testing. On continuation, first assess the known implementation limits above, then freeze the exact candidate before measuring it. Preserve the owner data, keys and paired browser profile; use the already isolated QA application/profile for destructive fault injection. Do not call the overall goal complete until the original final-deliverable and exact-candidate evidence requirements are actually satisfied.
