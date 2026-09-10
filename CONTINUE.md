# Continue with the final usability pass

Execute **[FINAL_USABILITY_SPEC_20260911.md](FINAL_USABILITY_SPEC_20260911.md)** when asked to continue. Start with [START_HERE.md](START_HERE.md), then [FOCUSED_HANDOFF_20260910.md](FOCUSED_HANDOFF_20260910.md), [NEXT_AGENT_PLAN.md](NEXT_AGENT_PLAN.md) and [docs/COPY_DECK.md](docs/COPY_DECK.md). The new spec supersedes the earlier focused plan and the former batch D/E/F plan where they conflict.

The latest user instruction prioritized conserving usage and installing a trial build. The original plan is not fully accepted: the handoff lists remaining focused checks and refinements. The next product input is the user's seven-day trial. The latest remaining design priorities are title/header consistency, compact beautiful search, and Review as a major feature. When a bug is reported, reproduce that exact path, make the smallest complete fix and repeat only affected checks. Do not create a new broad campaign.

Keep work on `codex/reliability-finalize`; preserve the owner's normal `com.reader.app` data and pairing. Use `com.reader.app.qa` and an isolated browser profile for test articles or destructive fixtures. Never clear normal app data, replace its keys, import the QA corpus into it, or remove the owner's Chrome profile.

The relevant state boundaries are `LibrarySearchRequest/Result`, stable label IDs, per-destination list state, reader inspection origin/current cursor, `FinishReceipt`, `HighlightMutation`, and `NoticeCoordinator`. Reuse the existing reading-session journal and bounded article repository. Keep copy and behavior assertions consistent with `docs/COPY_DECK.md`.

A normal debug update is an in-place signed install. Record its source commit and installed APK SHA-256; distinguish code validation, real TCL behavior and unmeasured long-term use. Local commits only unless explicitly told otherwise.
