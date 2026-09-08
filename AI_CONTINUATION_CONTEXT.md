> Final executable source: `7dbb86922c841a500f98007c2bd89f3644c3a31f` on `codex/reliability-data-integrity`.
> Final host gates PASS; exact QA artifacts: TCL 20 tests PASS, S23 Ultra 21 tests PASS.
> Foreground local-relay trials PASS on both, including one failed relay. Normal APK/test
> pairs installed in place and hash-verified on both; S23 normal database integrity PASS.
> Read [HARDENING_TEST_REPORT.md](HARDENING_TEST_REPORT.md) for exact hashes, timings,
> remaining unmeasured combinations and scope. Nothing pushed, merged or released.
> Both isolated campaigns/apps were stopped. User's latest testing device is the S23
> Ultra (device serial redacted); preserve its one-handed mode. The TCL is detached.

> Current reliability work starts from main `1e55b7d4021637520402cb1773ac4e1ffd3b43cf`
> on `codex/reliability-data-integrity`. Read `RELIABILITY_HARDENING.md` first.
> The explicit hardening brief supersedes older minimal-test/trial instructions below.
> Do not return to the historical pairing repair branch. Final artifact qualification
> for this cycle is tracked separately from all earlier results.

> Historical checkpoints below are superseded by this hardening candidate. See [HARDENING_TEST_REPORT.md](HARDENING_TEST_REPORT.md) for the current executable commit, artifacts and measured gaps.

> Latest Android update: system-default TTS visibility/voice patch is installed. See `evidence/tts-default/README.md`; NekoSpeak is selected, but its service connection failed during playback verification. Prior beta artifact results remain historical.

# Reader continuation — beta candidate, 8 September 2026

Start with [BETA_TEST_REPORT.md](BETA_TEST_REPORT.md),
[BETA_AUDIT_RESOLUTION.md](BETA_AUDIT_RESOLUTION.md),
[ARTIFACTS.json](ARTIFACTS.json), and [KNOWN_LIMITATIONS.md](KNOWN_LIMITATIONS.md).
They supersede earlier pause/trial checkpoints for current status.

The branch is `codex/reader-0.9-beta` in `/Users/schober/Projects/Nostr Reader`.
Android application/test source: `a4b3782`; Chrome final source: `19e8394` (full hashes
in ARTIFACTS.json). Later commits
may contain reports/CI/evidence only. Preserve the owner's normal `com.reader.app`
data, preferences, channel keys, browser profile and `chrome/dist`. Controlled
faults use `com.reader.app.qa` and the ignored isolated Chrome profile. Never clear
the owner's app or rotate identities as a testing shortcut.

Implemented: durable capture/recovery, bounded transport and storage, foreground
intake/health, structured reader/cursor, reactive themes, highlights/Undo/export,
quote-only sharing, feed/review, active TTS speed and lifecycle pause. The physical
TCL investigation fixed grapheme rendering, styled native selection coordinates,
and native handle autoscroll. Chrome fixes preserve pre-injection selection,
refresh settings and show preview-confirmation feedback.

The owner explicitly requested **minimal testing; do not repeat successful tests**.
Do not resume the original 20/50-count campaigns without a new request. Use existing
passing evidence at its recorded source/artifact boundary. The candidate has scoped
physical/UI and controlled-relay proof, not blanket release or public-network
qualification. Current login-gated sites, process-death/accessibility/lifecycle
matrices and large-library performance remain limited as listed in the reports.

Artifacts are in `artifacts/beta-0.9.0-beta.1/`; hashes and installation/test
relationships are in ARTIFACTS.json. APKs are debug-signed, not store releases.
Chrome loads unpacked from the ZIP; the original paired profile was not overwritten.
Only synthetic/no-secret summaries belong in Git. Raw profiles, relay certificates,
private storage fingerprints, screenshots with owner titles and Perfetto traces
remain ignored local artifacts. No production publishing or merge is authorized.

Useful detailed evidence: `docs/beta/TCL_TESTING_2026-09-08.md`,
`docs/beta/CHROME_UI_TESTING_2026-09-08.md`, and `evidence/beta/resumed/`.
Manual GitHub verification is prepared in `.github/workflows/verify.yml`; it does
not run on documentation pushes and is not claimed executed remotely.
