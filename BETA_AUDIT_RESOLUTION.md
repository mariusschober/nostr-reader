> Current hardening candidate: see [HARDENING_TEST_REPORT.md](HARDENING_TEST_REPORT.md) and [RELIABILITY_HARDENING.md](RELIABILITY_HARDENING.md). Results below describe their recorded checkpoints.

# Beta audit resolution — 8 September 2026

Candidate: 0.9.0-beta.1. Tested application source is recorded in `ARTIFACTS.json`.
This resolves the supplied audit against the newer preserved branch. An implemented
fix and its scoped passing tests do not imply full field reliability. The owner
requested minimal testing and no repetition of successful tests; the original
20/50-count campaigns are therefore not release gates claimed completed here.

| Finding | Disposition and evidence | Residual qualification |
|---|---|---|
| R01 durable scheduling | Fixed: persisted deadlines restored without postponement; failure-first worker/alarm regressions pass (`core-chrome-tests-4.log`). Focused nonempty-outbox worker interruption recorded. | Full sleep/wake and same-profile browser-restart matrix not measured on this candidate. |
| R02 capture feedback | Fixed: durable capture IDs, committed-save boundary, retained recovery actions, authenticated receipt wording. Physical packaged-browser selection/export/preview checks found and fixed stale settings, selection contamination and missing preview feedback. | Quota exhaustion and every message-loss timing point not measured physically. |
| R03 bounded intake | Fixed: typed transport outcomes, bounded frames/JSON/staging, independent healthy-relay progress and cleanup. Failure-first and TCL core instrumentation passed. | CPU/heap attack campaign not measured. |
| R04 ACK history | Fixed in source and deterministic tests: bounded resumable scan state, explicit incomplete coverage, randomized rolling window. | Large capped-history physical matrix not repeated. |
| R05 ACK loss | Implemented bounded authenticated demand refresh: five-minute cooldown, maximum eight refreshes, same-transfer replay suppression. Deterministic tests pass. | Focused controlled-relay result is reported separately; no public-network reliability rate. |
| R06 short selection | Fixed: every nonblank selection takes precedence and is escaped literally. Snapshot taken before injecting Reader controls. Packaged selection export equals pre-injection text. | Current live provider selections are not implied by synthetic fixtures. |
| R07 adapters | Structured conversion and incremental scoped controls implemented; permission and preview UI tested in packaged Chrome. | Login-gated providers and preview route aliases remain unverified; see source support matrix. |
| R08 publication | Fixed: independent resumable per-relay fragments, short durable mutations, one frame per relay in flight, receipt-driven publisher cancellation. Failure-first tests pass. | Exhaustive crash-point physical matrix not measured. |
| R09 visible receiving | Implemented arrival-driven foreground intake with persisted visible health and independent ACK dispatcher. Controlled physical delivery hashes and ACKs pass. | Background timing remains OS-dependent. |
| R10 cursor | Implemented versioned semantic projection, within-block offsets and restoration. Real native selection, Speed/return and theme-position checks pass. | Full process-death/font/accessibility matrix not measured. |
| R11 fidelity | Structured lists/code/tables and shared projection contracts implemented and fixture-tested. Unicode native quote sharing passes. | Wide-table narrow-screen and live-provider visual acceptance incomplete. |
| R12 resources | Summary-only lists, separate large content storage, bounded reader parts. Seven physical storage/migration tests pass. One 167-KiB article measurement retained. | Slow frames observed; 1,000-document/10,000-highlight performance corpus not measured. |
| R13 recovery UX | Discoverable settings, per-item retry/export/discard, permission controls and reactive send status implemented. Native Chrome controls exercised. | Exhaustive storage-pressure UX not measured. |
| R14 speech | Active speed, stale callback guards, semantic position, audio focus and background pause implemented. Real Android TTS callbacks, 1.25x speed, focus loss, next/previous and return passed. | Acoustic quality, phone call/headset and background media service not claimed. |
| R15 reader features | Themes, loaded empty Inbox, exact durable highlights, colors, Undo, source deletion, native quote-only share, feed and persistent review implemented. Physical handle extension/reversal/autoscroll and swipe semantics passed. | Part-boundary selection, process-death and broad accessibility matrix remain limited. |
| R16 assurance | Owner data/key files preserved; strict transport/key boundaries retained; current npm audit reports zero vulnerabilities including development dependencies. | No claim of zero Android CVEs or NIP-44 forward secrecy; external signers untested. |
| R17 orchestration | Executable worker, relay, transaction/migration and isolated physical UI harnesses added/retained. Failures are preserved, not relabeled as passes. | Repeated statistical and all crash-point campaigns not run. |
| R18 artifacts | Aligned beta versions, source freeze, APK/test pair, ZIP/unpacked bytes, exact hashes, installation and scoped instrumentation documented. Manual CI workflow added. | Candidate, not store-ready release; hosted CI/manual workflow and broader compatibility remain unmeasured. |

Evidence paths above are relative to `evidence/beta/resumed/`. See
`BETA_TEST_REPORT.md` for counts, artifact boundaries and performance.

## New confirmed defects

- Grapheme iteration accidentally resolved the iterator's empty `text`: fixed explicit initialization; failure-first regression passes.
- Native precomputed styled text produced wrong selection coordinates on TCL: ordinary native layout restored exact selection.
- A wrap-content TextView inside ScrollView prevented native handle edge scrolling: text now owns its bounded viewport; physical extension, reversal and autoscroll pass.
- Chrome selection included newly injected Reader controls: capture snapshots selection before injection; exported bytes match.
- Preview confirmation saved successfully but its old feedback identity suppressed status: a trusted Save gesture creates fresh feedback identity; native Save now visibly confirms.
- Settings did not reliably refresh recent sends and contained stale beta version text: refresh behavior and installed-version label corrected.

## Disproved or narrowed suspicions

The dark-reader status-bar screenshot was captured during a reactive transition.
A focused settled-frame check verifies light status/navigation icons on the dark
reader; no production patch was needed. Several early review failures were test
navigation/timing/fixture/receiver errors, documented in the TCL report. They do
not erase the real native layout and autoscroll defects above.

Final focused R05 check found explicit Retry could reuse accepted manifest checkpoints and make no fresh receipt demand. Commit `19e8394` forces only the manifest on an awaiting-device Retry. Failure-first test and final packaged physical recovery pass with exactly one refreshed ACK. Original failed attempt remains recorded.

Packaging residual: clean Android source export builds, but app DEX bytes differ from the installed incremental build. Full relocated byte reproducibility is FAIL; see the test report.
