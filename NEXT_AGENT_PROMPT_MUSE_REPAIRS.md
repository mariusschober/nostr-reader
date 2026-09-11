# Implement the final Reader repairs after Muse's review

Work in `/Users/schober/Projects/Nostr Reader` on `codex/reliability-finalize`. Read `START_HERE.md`, then **`MUSE_REVIEW_PLAN_20260911.md`**, `CONTINUE.md` and the relevant entries in `docs/COPY_DECK.md`.

Implement that plan, not the superseded historical audit. The reviewed baseline is `80f39c4`, including Android `a4140c3` and Chrome `8b89a7f`; a later documentation-only review commit may be HEAD. Verify the actual tree and preserve newer work. Do not check out `f20828b` because an old prompt says to.

Complete these ordered packages:

1. Fix ordinary-menu Highlight → handle adjustment keeping one session/row, including the half-open overlap boundary. Preserve continuous highlighting, native Copy/Share/handles and guarded Undo.
2. Fix e-ink underline geometry across wrapped lines. Screenshots 07/08 in `evidence/muse-review-20260911/` show rules beginning under unselected text and disappearing on middle lines. Preserve native text, quote anchors and stored colors; use actual visual runs, correct vertical placement and clipping.
3. Restore Speed's normal-theme focal color. Preserve the visibly working monochrome bold/underline cue and stable focal position; check one long token and correct fitting only if needed.
4. Keep source attribution immediately available for long Review quotes and consolidate the Highlights feed's source/actions. Preserve full adaptive text, the Important badge, current compact search, filled Review CTA, its header fallback and existing scheduling/return behavior.
5. Perform the plan's focused verification, update copy and continuation documents, and install the final normal debug APK on the TCL in place with the existing signing identity. Verify installed bytes and non-secret owner-state integrity; retain an honest list of unmeasured areas.

Astra is the architect/final reviewer. Delegate coding/device iteration to an available implementation model at its highest supported reasoning. One implementer owns the native selection/rendering file and one operator owns TCL at a time. Keep separate reviewable commits for selection correctness, drawing, Speed, source context and handoff.

The TCL T807D is serial `ZXKRS4VKGQ8PWGEQ`; verify availability before controlling it. Use `com.reader.app.qa` for fixture mutations and removal/Undo checks. Never clear/uninstall the normal `com.reader.app`, replace owner keys, remove pairings, or put QA articles in the owner library. Current installed normal SHA is `8dc3d733635189852151f908de4c0b261fc131b0a130c974f0e7df8430c0b6e1`; QA is `025bdbacfe55cdbe64aac4610abaa3a37f95767f30d2cf0168ec5747da9af5e5`. These are baseline bytes, not the expected hashes of your repaired build.

Preserve Room v13, canonical article text, projection/quote anchors, existing preferences, reading/review history, encryption and pairing. Retain the native selectable TextView and 8 dp inset. Do not introduce an outer article scroller or range-based cleanup of existing highlights. Preserve the four pre-existing untracked XML files named in the plan.

Testing must remain efficient. Run narrow tests for the changed boundaries, one actual handle adjustment and the small walkthrough in the plan, then fast unit tests, lint, builds and alignment once at the final candidate. Connected tests must leave QA installed. No 100-article intake rerun, no 1,000 articles/10,000 highlights, no two-hour session, soak test, exhaustive matrix or statistical performance campaign. Repeat only failures or relevant changed paths. The user tests sustained use over seven days.

Do the work autonomously until the scoped repairs are complete or a real blocker prevents progress. Do not expand into new features or another general redesign. Distinguish code evidence, programmatic device tests, actual gestures and screenshots. Report PASS / FAIL / NOT MEASURED / BLOCKED honestly. Leave local commits, exact source/APK hashes, installed-byte proof, focused outcomes and known limitations. Do not push, merge or release.
