# Reader v2 rollback and recovery

Primary v2 implementation commit: `91aa1c49ebc98cb68ef0df1c9c0608d8b3c1791b`

Receiver ACK durability follow-up: `3c4546bfd8b27fa11ac3d5af576dba2050b5de6e`

Final runtime fix commit: `6ea80ee6f3732a192307661fd9cd5c485a4dd1dc`

Deterministic Chrome packaging commit: `a061ddcb111cf05048828e65fcddc9c797f1a6ec`

Final audited artifact/documentation commit: recorded in generated
`artifacts/ARTIFACTS.json` because that commit cannot contain its own hash.

Immutable pre-repair baseline: `982920b4e91dd5af4af6046f56df281c7adfe545`

Rollback is intentionally not an in-place protocol downgrade. Reader v2
changes the Android database, trust states, compression framing, pairing
transcript, retry ownership, and delivery ACK. An older build must never
reinterpret a v2 channel or Room v5 database. The stricter one-member decoder
does not change valid v2 bytes; it rejects only concatenated or suffixed input
that was already outside the documented contract.

## Current recovery rule

Current Android storage is Room v10. Preserve the installed owner application and data; older APKs are not an in-place downgrade path. Inspect older source in a separate worktree and run it only in an isolated package/device. Do not clear data or uninstall to downgrade. Exports are one-way readable files; full library/review/key restoration is not implemented. Use a forward fix for the owner installation. Historical baseline commit identifiers above remain useful for source comparison, not install instructions.

See [migration and compatibility](MIGRATION_AND_COMPATIBILITY.md), [current status](CONTINUE.md), and [known limitations](KNOWN_LIMITATIONS.md).
