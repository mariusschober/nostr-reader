# Documentation index

## Current

| Document | Purpose |
|---|---|
| [README](../README.md) | Features, screenshots, source-build setup |
| [Current handover](../CONTINUE.md) | Source, installed build, verification and preservation boundaries |
| [UI completion report](../UI_UX_COMPLETION_REPORT.md) | September 9 UI/import/accessibility acceptance |
| [Artifact manifest](../ARTIFACTS.json) | Exact packages; read `latestUiCompletion` first |
| [Install and trial](beta/INSTALL_AND_TRIAL.md) | Build, update, pairing and reading workflow |
| [Known limitations](../KNOWN_LIMITATIONS.md) | Outstanding product and acceptance limits |
| [Protocol](../PROTOCOL.md) | Authoritative wire contract |
| [Architecture](../ARCHITECTURE_AND_PROTOCOL.md) / [state machines](../RELIABILITY_STATE_MACHINES.md) | Client ownership and lifecycle |
| [Reliability hardening](../RELIABILITY_HARDENING.md) | Current persistence, capture and deletion contracts |
| [Migration](../MIGRATION_AND_COMPATIBILITY.md) / [rollback](../ROLLBACK.md) | Upgrade preservation and safe source comparison |
| [Security notes](../SECURITY_AND_PRIVACY_NOTES.md) / [threat model](../THREAT_MODEL.md) | Trust and privacy boundaries |
| [Source support](beta/SOURCE_SUPPORT_MATRIX.md) | Capture providers and qualification limits |

## Historical evidence

Dated beta, hardening, relay, device and UI-refresh reports remain as evidence of their own commits. They do not certify current bytes or override the current handover. Original briefs/audits remain historical requirements and findings. Raw owner state and generated binaries are not in Git.

## September 9 cleanup

Removed four superseded operational documents: `AI_CONTINUATION_CONTEXT.md`, `CONTINUE_READER_PROMPT.md`, `HANDOVER_RECOVERY_TRIAL.md`, and `UI_UX_COMPLETION_REVIEW.md`. Their old instructions pointed to stale branches, pauses or pre-implementation work. Current guidance is consolidated in `CONTINUE.md`; completed scope is in the UI report. All four remain recoverable from commit `4755fad` and earlier Git history. Stable screenshot filenames now contain final-build synthetic captures. Historical test evidence, protocol specifications, source, fixtures and owner data were retained.
