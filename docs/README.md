# Documentation

Start with the [README](../README.md) for the product and build steps, [status](STATUS.md) for current source boundaries and dated evidence, and [installation guide](INSTALL.md) for the ordinary workflow.

| Document | Owns |
|---|---|
| [October 1 audit](AUDIT_20261001.md) | Current repository reconciliation, findings and priorities |
| [October 1 execution plan](PLAN_20261001.md) | Ordered implementation phases and bounded acceptance criteria |
| [October 1 handover and start prompt](../artifacts/candidate-20261001/EVIDENCE.md) | Completed repairs, verified Chrome package and remaining Android and direct checks |
| [Status](STATUS.md) | Open defect, verification limits, owner-data boundaries |
| [Source support](SOURCE_SUPPORT.md) | Provider adapter qualification |
| [Copy and interaction contract](COPY_DECK.md) | Implemented UI wording and behavior |
| [Protocol](../PROTOCOL.md) | Normative v2 wire contract |
| [Architecture](../ARCHITECTURE_AND_PROTOCOL.md) | Client components and trust/ownership map |
| [Reliability state machines](../RELIABILITY_STATE_MACHINES.md) | Pairing, transport, recovery and receipt transitions |
| [Storage and integrity](../RELIABILITY_HARDENING.md) | Capture ownership, durable outcomes, selection journal and exports |
| [Migration](../MIGRATION_AND_COMPATIBILITY.md) / [recovery](../ROLLBACK.md) | Schema/trust compatibility and safe forward repair |
| [Security](../SECURITY.md), [privacy](../SECURITY_AND_PRIVACY_NOTES.md), [threat model](../THREAT_MODEL.md) | Security assumptions, metadata and endpoint boundaries |
| [NIP conformance](../NIP_CONFORMANCE_MATRIX.md) | Implemented protocol scope and pinned references |
| [Platforms](PLATFORMS.md) | Rust/Swift reference and future Mac/iOS limits |

[Dated September 14 evidence](../artifacts/trial-fixes-20260914/EVIDENCE.md) records that frozen Android candidate. `evidence/` contains older immutable captures; it does not certify current source or installed devices. Obsolete plans, reports and agent prompts were removed during September 30 cleanup after consolidating unresolved requirements. Tracked predecessors remain in Git history. Rebuild generated outputs rather than expecting obsolete artifact paths to exist.
