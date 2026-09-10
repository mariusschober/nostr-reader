# Start here — Reader focused UX handoff

Read **[NEXT_AGENT_PLAN.md](NEXT_AGENT_PLAN.md)** first: it is the detailed remaining-work plan, with **[NEXT_AGENT_PROMPT.md](NEXT_AGENT_PROMPT.md)** for a fresh agent. Then read [FOCUSED_HANDOFF_20260910.md](FOCUSED_HANDOFF_20260910.md), [docs/COPY_DECK.md](docs/COPY_DECK.md) and [KNOWN_LIMITATIONS.md](KNOWN_LIMITATIONS.md).

Branch: `codex/reliability-finalize`. Installed implementation/source: `b14f128`; baseline: clean `15a77ff`. Later handover commits change docs/evidence only. Work is local; do not push, merge or release without a new instruction.

The user's approved plan supersedes the former D/E/F batches and extensive acceptance campaigns. Implemented scope: Shelf/Highlights/Settings navigation; four visible shelves; continue reading; constrained local search and labels; compact reader appearance/tools; contents/find/return; native highlighting and quote inspection; deliberate finish/archive with guarded Undo; quieter saving and settings.

Preserve Room v13, canonical text and quote anchors, the selectable native TextView with its 8dp inset, existing preferences, owner articles/highlights, encryption, keys and pairings. Do not add another scrolling container around native article text.

Verification is intentionally focused: 50 real Chrome saves + 50 Android link saves in isolated QA identities, five representative articles, the important feature walkthrough, fast host checks and a few targeted device tests. No 1,000/10,000 corpus, soak test, 120-minute session or exhaustive matrix. The user evaluates sustained comfort/reliability over seven days.

This is a trial checkpoint, not a claim that every original acceptance item passed. Read the exact installed build/hash, measured results, and prioritized remaining work in the handoff. Older reports are historical evidence, not outstanding mandatory campaigns. Do not restart optional testing or feature expansion during the user's trial.
