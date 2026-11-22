# Recovery and safe source comparison

Current Android storage is Room **v13**. Preserve the installed owner application and data. Older APKs are not a supported in-place downgrade path: they may not understand newer schema or trust states. Inspect older source through Git in a separate worktree and run it only in an isolated package/device. Do not clear data or uninstall to downgrade or bypass signing mismatches.

Use a forward fix for the owner installation. Record exact source, APK digest and signing identity before an authorized in-place update, then compare installed bytes and owner integrity. Exports are readable one-way files; library/review/key restoration is not implemented. A protocol downgrade must never reinterpret a v2 channel as v1. Stricter single-member gzip validation rejects previously invalid trailing/concatenated data, not valid v2 messages.

See [migration](MIGRATION_AND_COMPATIBILITY.md) and [status](docs/STATUS.md). Historical commits are source comparison anchors, not install instructions.
