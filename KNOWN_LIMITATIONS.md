> **2026-09-08 checkpoint:** Implementation is paused before reliability campaigns/intensive testing at the user’s request. See [PRE_CAMPAIGN_CHECKPOINT.md](docs/beta/PRE_CAMPAIGN_CHECKPOINT.md) for current source scope, exact build hashes, passing local checks and unverified limits. Older device evidence does not certify this candidate.

# Known limitations

Every item is evidence-based and uses the required status vocabulary.

| Limitation | Consequence | Status |
|---|---|---|
| Mandatory statistical reliability gates were not run | no 20/20 pairing, 50/50 delivery, or 20/20 offline/replay rate claim can be made | NOT MEASURED |
| Only one fresh repaired Chrome/TCL pairing and one synthetic article transfer were observed | proves the complete path can work, not its field reliability | PASS for 1/1 observation; larger gates NOT MEASURED |
| Latest post-hardening source was not exercised by another public article transmission without separate approval | the earlier E2E transfer predates the final serialized outbox/pairing, AUTH-template, retry-ownership, and strict-gzip hardening; the latest artifacts were verified without creating another public payload | NOT MEASURED for another latest-build public E2E |
| The new ACK-retry path was not interrupted physically between response authentication, ACK acceptance, and completion | deterministic recovery/timing tests and exact post-pair restart evidence do not substitute for the required in-flight pairing kill/restart series | NOT MEASURED physically |
| An existing Room v4 installation has no historical wrapper/ACK ledger | the first v5 catch-up may emit one authenticated recovery ACK batch for a still-retained completed transfer; subsequent catch-ups suppress it | bounded migration behavior; one TCL migration/repeat observation PASS |
| Android background work is WorkManager catch-up, not instant push | delivery timing is OS/network dependent; force-stop requires user reopen | known platform constraint |
| Full Android lifecycle matrix (reboot, Doze, battery saver, screen-off, network switch, captive network, storage failure) was not physically executed | no claim for those cases | NOT MEASURED |
| Public relays can change policy, limits, uptime, retention, DNS, or operator | default health is dated evidence, not a permanent guarantee | ongoing operational risk |
| Public AUTH was not encountered | NIP-42 is verified in local Chrome/Android fault harnesses only | NOT MEASURED on public relays |
| NIP-07 and Amber/NIP-55 were not tested with live third-party signers | optional provenance compatibility is unproven | NOT MEASURED |
| NIP-44 has no forward secrecy/ratchet | later channel-key compromise may expose retained historical ciphertext | protocol property |
| Relay metadata remains visible | IP, timing, recipient routing key, subscription cadence, and size can be correlated | privacy limitation |
| Remote article images are fetched directly | image host learns reader IP/timing | privacy limitation |
| Android target/compile SDK remains 34 and lint reports outdated dependency/icon/performance warnings | release maintenance remains; no lint errors | PASS build, warnings retained |
| Only the final reproducible app APK was installed after the toolchain-only rebuild | the exact app hash passed in-place install, pull-back byte comparison, and cold startup on TCL; the matching test APK was not installed and instrumentation was not rerun | PASS for final app install/startup on TCL; NOT MEASURED for final-hash instrumentation and other targets |
| 16 KiB page-size behavior was not validated on a 16 KiB physical device | third-party native-library compatibility remains a release gate | NOT MEASURED |
| Tablet/foldable layouts and audible TTS output were not revalidated in this repair | no broad device/UI/audio claim | NOT MEASURED |
| Mac transport, UniFFI binding, iOS client, and store builds are incomplete | Chrome + Android are the current repaired path | NOT MEASURED/not implemented |
| Android transitive dependencies were inventoried but no local OSV/Trivy/Snyk-compatible scanner was installed | no claim of zero Android CVEs | NOT MEASURED |
| A compromised trusted extension service worker or compromised endpoint OS remains inside the trust boundary | local arbitrary code execution can access plaintext/keys | out of cryptographic scope |

## Explicit non-limitations

- Six default relays plus up to two custom `wss://` relays are implemented and
  relay-set changes require authenticated re-pairing.
- `OK=true` and device delivery are distinct in code/UI/evidence.
- Content scripts cannot read key-bearing Chrome local storage in the executed
  real-browser negative test.
- Version 1 pairings are not silently trusted after migration.
- Android persists authenticated wrapper IDs and ACK intent/outcomes; a
  completed two-relay ACK quorum cannot be restarted by later copies of the
  same transfer.
- Chrome serializes all mutations for one outbox transfer and persists a
  delivered receipt before deleting its durable payload.
- Chrome validates the private-device-key/public-channel binding before
  reporting paired or sending; key loss preserves captures but requires
  explicit re-pairing.
- The pinned Gradle 8.9/AGP 8.7.2/D8 8.7.18 toolchain produced byte-identical
  app and instrumentation APKs across independent clean builds. Together with
  the exact TCL pull-back comparison this proves final app installation and
  startup there, not final-hash instrumentation or other targets.
- Relay rejection of an AUTH event is distinct from rejection of the original
  Reader event, and Chrome validates the exact NIP-42 template before signing.
- Pairing response authentication removes the one-time Chrome bootstrap secret
  before ACK network work; Chrome throttles later ACK attempts and Android
  retains a retryable one-time owner while pairing remains pending.
- All four codec runtimes reject concatenated gzip members and arbitrary
  trailing bytes under the same one-member contract.
