# ARENA.md — task board & scoreboard

Tasks here are run as duels under the protocol in `COLLAB.md`: both AIs
implement independently on their own branches, critique each other in
COLLAB.md's Discussion, and measurement referees.

To claim a task, edit its entry: add `Claimed by: <NAME> (<branch>)` and
post a message in COLLAB.md.

## Scoreboard

| # | Task | MUSE | ANTIGRAVITY | Winner | Decided by |
|---|------|------|-------------|--------|------------|
| 1 | Premium slider Part 2 (gradient track + drag value bubble) | DONE (e83a7ee, 2026-10-20) | DONE (a7645e2, 2026-10-21) | pending owner V60 verdict | Both entries built green. MUSE review of ANTIGRAVITY posted in COLLAB.md 2026-10-21 01:04: correctness edge MUSE (identity keying; ANTIGRAVITY mis-keys Detail-panel "Luminance" NR slider via label-substring matching), presentation edge ANTIGRAVITY (floating bubble, compact-slider coverage). ANTIGRAVITY test APK on Drive for owner's A/B feel test. |

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

---

## Duel Protocol — owner-ordered 2026-10-21 ~21:37 +07 (alternating review-and-improve)

The owner proposed the format, and both AIs accept it. This replaces
parallel monologues with a real duel: each side must BEAT the other's
latest work, not just defend its own.

**How one round works:**

1. Side A pushes an implementation (a real commit on its own branch).
2. Side B reviews it in COLLAB.md: honest critique, concrete defects
   with file/line evidence — no vague praise, no invented flaws.
3. Side B then pushes its OWN improved version: it must fix every defect
   it just listed AND keep everything that already worked.
4. Side A reviews Side B's version the same way, then pushes a version
   better than that. Repeat.
5. A round ends when one side's version stands unchallenged (the other
   side writes "no further objections" in COLLAB.md) OR the owner calls it.

**Rules (non-negotiable, from the owner's standing laws):**

- Every claim must be verifiable: build green + tests green, or say so
  plainly. A version that doesn't compile is not "better".
- "Better" is measured, in order: (a) the previous review's defect list
  is fully addressed, (b) no regressions (existing tests + UX laws:
  percentages, toggle law, per-setting revert, 48dp, TalkBack),
  (c) fewer files touched wins ties, (d) the owner's vivo V60 verdict
  is the FINAL word over both AIs.
- No fake AI, no fake tests, no claiming the other side's work.
  Credit the other side's good ideas by name when you port them.
- Each side pushes only to its own branch (`arena/muse`,
  `arena/antigravity`); the room thread lives in `COLLAB.md` on master.
- The owner may interrupt, re-point, or stop the duel at any time.
  His word outranks both AIs.

**Scoreboard:**

| Round | Task | Standing |
|-------|------|----------|
| 1 | Premium slider (Task #1, continued) | IN PROGRESS — see below |
| — | (next duel task: owner's pick) | OPEN |

### Round 1 — Premium slider, duel format (started 2026-10-21 ~21:37 +07)

State when the duel format began: MUSE entry on master (`e83a7ee`,
identity-keyed gradients, Hue/Luminance pinned Neutral, 20dp bubble
lane); ANTIGRAVITY entry on `arena/antigravity` (`a7645e2`, substring
gradients, floating bubble, compact coverage) + fix commit `bb54344`
(Hue exact-match, NR-Luminance re-keyed to detail group — both MUSE
review objections accepted and fixed).

**Duel sequence for Round 1:**

1. ANTIGRAVITY: rebase onto current master, push (its improved entry).
2. MUSE: review the rebased entry in COLLAB.md, then push a version
   better than it (ports the floating bubble + compact coverage, keeps
   identity keying, fixes the remaining substring edges).
3. ANTIGRAVITY: review MUSE's version, then push a version better
   than that. Repeat until one side stands unchallenged or the owner
   calls the round.
4. Winner merges to master; the loser's best ideas are ported by name.
   Final word: the owner's V60 A/B verdict.
