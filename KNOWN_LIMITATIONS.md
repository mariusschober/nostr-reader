> Latest scoped Android UI completion: [UI_UX_COMPLETION_REPORT.md](UI_UX_COMPLETION_REPORT.md), source `ca06d1d`. TCL enlarged text/display, landscape, strict TalkBack controls, tables and review checks passed; this does not complete the broader beta campaigns below.

> Current hardening: see [RELIABILITY_HARDENING.md](RELIABILITY_HARDENING.md). [HARDENING_TEST_REPORT.md](HARDENING_TEST_REPORT.md) records the prior candidate checkpoint (Room v9, Chrome 136, Android 118). Finalization on `codex/reliability-finalize` moves to Room v10, Chrome 140, Android 120 unit tests with host gates passing; device validation of the new packages is NOT MEASURED here. Results below describe their recorded checkpoints.

# Known limitations — 0.9.0-beta.1 candidate

Latest focused evidence is in [ARCHIVE_REFRESH.md](ARCHIVE_REFRESH.md) and
[UI_REFRESH.md](UI_REFRESH.md). [BETA_TEST_REPORT.md](BETA_TEST_REPORT.md) records
the preceding beta checkpoint. These limits remain unless explicitly superseded
by a later scoped result; older checks are historical.

- **NOT MEASURED:** original 20-pairing/50-delivery/20-offline/replay/worker/browser-restart campaigns, exhaustive crash timing, full Doze/reboot/battery-saver/network-switch matrix and current public-network reliability. Focused controlled-relay checks do not establish a failure rate.
- **NOT MEASURED:** current authenticated provider UIs and optional NIP-07/Amber signers. Preview adapters remain preview; see [source matrix](docs/beta/SOURCE_SUPPORT_MATRIX.md).
- **Observed performance limit:** one TCL debug/instrumented 166,939-byte article opened in 1,160 ms including tap waits; six of 390 frames exceeded 100 ms, maximum 303.97 ms. p95 was 9.41 ms. This is a single run, not a release benchmark or leak assessment. The 1,000-document/10,000-highlight corpus was not measured.
- Very large articles use explicit bounded parts of approximately 196,608 canonical UTF-16 units. Selection and narration operate within the current part; dragging across part boundaries and automatic speech continuation are not implemented.
- **NOT MEASURED:** full process-death, tablet/foldable and exhaustive accessibility matrix. Activity recreation, exact native sharing, source deletion, handles/autoscroll and review swipes have scoped physical passes.
- TTS pauses on background and audio-focus loss. Native callbacks/speed/pause were tested; acoustic quality, real calls/headsets and a background media service are not claimed.
- Android background delivery uses OS-scheduled catch-up. Timing depends on the OS/network; a user force-stop requires reopen.
- Export is one-way Markdown/JSONL, not a restorable backup. Pairing keys are excluded. Export import/review-queue restore is not implemented.
- Current candidate acceptance includes TCL T807D and Samsung SM-S918B (S23 Ultra), both Android 16/API 36; see the exact hardening report scope. Historical API-26/other-device checks do not certify these final bytes. Current 16-KiB runtime/store-signing acceptance remains **NOT MEASURED**. Target/compile SDK remains 34.
- Chrome npm audit reports zero vulnerabilities at the recorded date. No equivalent complete Android CVE scan was performed. Public relay policies and retention can change.
- NIP-44 has no forward secrecy. Relays can observe routing keys, IP, timing and sizes. Compromised endpoints can access local plaintext and keys. The current native reader renders image descriptions as text without remote image fetches.
- Mac is a reference Swift package; iOS, UniFFI integration and store releases are incomplete. Chrome-to-Android is the implemented product path.
- Automatic inexpensive host checks are enabled for relevant code changes, but this branch has not run on GitHub. No hosted CI pass or store-ready conclusion is claimed.

- **FAIL:** full Android app byte reproducibility across a relocated clean source export. The build succeeds; two DEX files differ from the installed incremental artifact. Delivered APK hashes and their exact installed integrity check are verified.
