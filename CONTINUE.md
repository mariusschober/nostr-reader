# CONTINUE — Reader v2 repair handoff

Updated: 2026-09-05

Immutable baseline: `982920b4e91dd5af4af6046f56df281c7adfe545`

Working branch: `fix/pairing-delivery-hardening-982920b4`

## Current truth

Reader v2 now has an authenticated, durable Chrome-to-Android path:

```text
pair request -> Android consent -> pair response -> Chrome ACK
  -> Android completion -> active channel
  -> encrypted manifest/chunks -> durable Android document
  -> authenticated device ACK -> Chrome delivered
```

One fresh Chrome for Testing/TCL pairing completed over the six defaults plus
one custom relay. One synthetic article was stored exactly once and its bound
Android ACK moved Chrome from pending to delivered. This proves the complete
path can work; it is not the unexecuted 20/20 and 50/50 reliability series.

The complete acceptance gate is therefore **NOT MEASURED**, while the executed
unit, instrumentation, packaging, live-relay, and single-flow checks are
reported independently in `TEST_REPORT.md`.

## What changed

- Pairing is `reader-pair/2`, durable kind 1059 only, and requires both
  endpoints to authenticate the same session before either reports connected.
- NIP-59 wrapper routing, sender checks, NIP-44 v2 limits, strict JSON,
  NIP-42, BIP-340 signing, expiry, replay, gzip, hashes, chunk assembly, and
  endpoint ACK validation have executable regression coverage.
- Chrome persists pairing sessions and outbox state across MV3 suspension and
  restart. Relay acceptance and device delivery are separate states.
- Android stages pairing and transfers transactionally, revokes key-loss
  channels, performs rolling-window catch-up, and deduplicates by content.
- Reader uses six fixed public relays, a two-relay write quorum, and up to two
  user-added secure relays in Chrome Settings. Relay changes require re-pairing.
- The unsafe page-world signing bridge and inert settings controls were
  removed. Chrome key storage is restricted to trusted extension contexts.

## Evidence map

- Root cause: `PAIRING_ROOT_CAUSE.md`
- Normative protocol: `PROTOCOL.md`
- Architecture and state machines: `ARCHITECTURE_AND_PROTOCOL.md`,
  `RELIABILITY_STATE_MACHINES.md`
- Conformance: `NIP_CONFORMANCE_MATRIX.md`
- Security/privacy: `THREAT_MODEL.md`, `SECURITY_AND_PRIVACY_NOTES.md`
- Migration/rollback: `MIGRATION_AND_COMPATIBILITY.md`, `ROLLBACK.md`
- Live relays: `LIVE_RELAY_REPORT.md`
- Physical TCL work: `TCL_PHYSICAL_VALIDATION.md`
- Consolidated results and residuals: `TEST_REPORT.md`,
  `KNOWN_LIMITATIONS.md`
- Redacted raw evidence: `evidence/raw/`

## Reproduce the audited artifacts

Run `./scripts/build-audit-artifacts.sh` only from a clean tracked worktree. It
reinstalls locked Chrome dependencies, runs the Chrome/Android/Rust/Swift
gates, creates the debug APKs and extension ZIP, generates dependency evidence,
and writes ignored `artifacts/ARTIFACTS.json` plus `artifacts/SHA256SUMS`.

The manifest is the authority for exact source commit, hashes, build commands,
and post-build install results. The binaries are debug/developer artifacts,
not production releases.

## Physical devices and browser

- Decisive device: TCL T807D, ADB serial `ZXKRS4VKGQ8PWGEQ`.
- Secondary device: Samsung Galaxy S23, ADB serial `R3CW404GVBL`.
- The S23 has a previously confirmed physical OLED fault (bright horizontal
  line and intermittent lower-screen green illumination). Use ADB hierarchy or
  screen capture for app assertions and do not classify those panel artifacts
  as Reader defects.
- Browser validation uses Google Chrome for Testing, not Brave.
- Always provide an explicit ADB serial because both phones can be connected.

## Still required before a reliability PASS

- 20/20 clean pairings, 20/20 worker-termination pairings, and 20/20
  pre-reply Chrome-restart recoveries on the TCL.
- 50/50 normal deliveries with zero duplicate documents and verified device
  ACKs, plus 20/20 offline-to-online and 20/20 replay/duplicate attempts.
- The remaining controlled physical lifecycle/network/failure scenarios listed
  in `TCL_PHYSICAL_VALIDATION.md`.
- A public transfer using the exact final artifact only with explicit approval;
  do not infer authorization from build or device-test permission.
- Live third-party NIP-07/Amber signer checks, public NIP-42 challenge evidence,
  Android release signing, and a 16 KiB-page device remain unmeasured.

Android can currently republish an authenticated duplicate ACK batch when a
later catch-up encounters still-retained duplicate wrappers. This is bounded by
expiry and does not duplicate the document, but it remains an open P2 traffic
optimization.

## Safety boundary

Do not push, merge, publish, force-push, introduce a backend, use a real Nostr
identity, or expose pairing/private-key material. Preserve the immutable
baseline and the dedicated repair branch. Distinguish `PASS`, `FAIL`,
`NOT MEASURED`, and `BLOCKED` exactly.
