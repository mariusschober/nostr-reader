# Start here — Reader final usability pass

**11 September independent-audit correction:** the installed `f20828b` pass has remaining defects. Read [ASTRA_AUDIT_20260911.md](ASTRA_AUDIT_20260911.md), then execute [READER_POLISH_PLAN_20260911.md](READER_POLISH_PLAN_20260911.md) when asked to implement. Starter: [NEXT_AGENT_PROMPT_DEEPSEEK_POLISH.md](NEXT_AGENT_PROMPT_DEEPSEEK_POLISH.md). Those documents supersede conflicting completion claims below; the audit changed no product source or normal installation. The previous implementation/evidence history follows.

Read **[FINAL_USABILITY_SPEC_20260911.md](FINAL_USABILITY_SPEC_20260911.md)** first: it is the authoritative specification for the current pass (four reading actions, true fullscreen focus, once-only highlight help, e-ink theme, Inter, complete adaptive highlight cards, focused Review, floating Review banner, Reader rename, text-boundary fix). Then read [FOCUSED_HANDOFF_20260910.md](FOCUSED_HANDOFF_20260910.md), [NEXT_AGENT_PLAN.md](NEXT_AGENT_PLAN.md), [docs/COPY_DECK.md](docs/COPY_DECK.md) and [KNOWN_LIMITATIONS.md](KNOWN_LIMITATIONS.md).

Branch: `codex/reliability-finalize`. Implemented source: `fd1b813` (documentation `f98aa9f`); earlier baseline: clean `15a77ff`. The user's final usability spec supersedes the earlier focused plan where they conflict. Work is local; do not push, merge or release without a new instruction.

Previously implemented scope (superseded where the new spec says so): Shelf/Highlights/Settings navigation; four visible shelves; continue reading; constrained local search and labels; compact reader appearance/tools; contents/find/return; native highlighting and quote inspection; deliberate finish/archive with guarded Undo; quieter saving and settings. The new spec replaces the top Review card with a floating banner, replaces the two reading actions with four, and changes tapping a highlight from inspection to Review-from-that-quote.

Preserve Room v13, canonical text and quote anchors, the selectable native TextView with its 8dp inset, existing preferences, owner articles/highlights, encryption, keys and pairings. Do not add another scrolling container around native article text.

Verification is intentionally focused: 50 real Chrome saves + 50 Android link saves in isolated QA identities, five representative articles, the important feature walkthrough, fast host checks and a few targeted device tests. No 1,000/10,000 corpus, soak test, 120-minute session or exhaustive matrix. The user evaluates sustained comfort/reliability over seven days.

This is a trial checkpoint, not a claim that every original acceptance item passed. Read the exact installed build/hash, measured results, and prioritized remaining work in the handoff. Older reports are historical evidence, not outstanding mandatory campaigns. Do not restart optional testing or feature expansion during the user's trial.
