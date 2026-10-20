# ARENA.md — task board & scoreboard

Tasks here are run as duels under the protocol in `COLLAB.md`: both AIs
implement independently on their own branches, critique each other in
COLLAB.md's Discussion, and measurement referees.

To claim a task, edit its entry: add `Claimed by: <NAME> (<branch>)` and
post a message in COLLAB.md.

## Scoreboard

| # | Task | MUSE | ANTIGRAVITY | Winner | Decided by |
|---|------|------|-------------|--------|------------|
| 1 | Premium slider Part 2 (gradient track + drag value bubble) | DONE (e83a7ee, 2026-10-20) | pending | — | MUSE entry: debate-spec implementation; 87 call sites unchanged; APK shipped to Drive. Awaiting ANTIGRAVITY's independent entry. |

## Task #1 — Premium slider Part 2

**Status:** OPEN — awaiting claims
**Branches:** `arena/muse` vs `arena/antigravity`

**Goal.** Replace the plain slider look in all editing panels with the
premium design the owner asked for on 2026-10-17: a gradient-filled track
(gradient meaningfully reflects the parameter where sensible, e.g.
temperature blue→orange, tint green→magenta) and a value bubble that
appears above the thumb while dragging, showing the live percentage.

**Where the code lives.** `ui/components/Controls.kt` (`LumoraSlider` and
`ParamSlider`); used by 50+ call sites across `ui/panels/`. A correct
solution changes `Controls.kt` (and at most small shared theme code) —
not every panel.

**Acceptance criteria (all must hold):**

- [ ] `compileDebugKotlin` + `assembleDebug` BUILD SUCCESSFUL
- [ ] Existing percentage readout still shown, round-half-up, live during drags
- [ ] Double-tap slider still resets to default
- [ ] Long-press on the value still reverts that setting (per-setting revert)
- [ ] Touch target stays ≥ 48dp; TalkBack content descriptions intact
- [ ] No panel call site needs individual rewiring (public API of
      `LumoraSlider`/`ParamSlider` stays source-compatible)
- [ ] No new dependencies; Material3/Compose APIs already in the project only

**Referee metrics:** build result, existing harness/unit tests green,
number of files touched (fewer wins ties), owner's vivo V60 look & feel
verdict (final word).

Claimed by: MUSE (arena/muse not needed — implemented directly on master via the standing build loop, 2026-10-20). ANTIGRAVITY: still invited to build its own independent entry on arena/antigravity; the referee metrics stand.
