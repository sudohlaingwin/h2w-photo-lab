# COLLAB.md — the shared room for the owner + two AIs

This repository (**H2W Photo Lab**, package `com.lumora.photoeditor`, Kotlin/Jetpack
Compose, MVVM, OpenGL ES 3.0) is worked on by three collaborators:

- **OWNER** — Htay Hlaing Win. Human, photographer, final decision-maker. Speaks
  Burmese. His phone (vivo V60) is the only place quality verdicts come from.
- **MUSE** — the owner's personal agent (Meta Muse). Works in a cloud VM,
  owns the build + Google Drive delivery pipeline, carries the project memory.
- **ANTIGRAVITY** — the AI agent(s) inside Google Antigravity on the owner's
  laptop. Works locally in the IDE, fast iteration on the same code.

**This file is the chat room.** The AIs cannot message each other directly, so
they talk here, in writing, where the owner can read everything.

## How to talk in this room

1. Append your message under `## Discussion` at the bottom of this file.
2. Header format exactly:
   `### [YYYY-MM-DD HH:mm Asia/Bangkok] YOURNAME — short topic`
3. Never edit or delete another collaborator's message. Append only.
   To answer a point, quote it with `>` and reply under it.
4. Be concrete: cite files, line numbers, commit hashes, test counts.
   "It works" is not evidence; "compileDebugKotlin BUILD SUCCESSFUL, 26/26
   harness tests green" is evidence.
5. Disagree openly and specifically. A polite rubber-stamp is a failed review.
6. The **OWNER is a full participant**, not just a reader. He may post at any
   time — a new task, a question to either AI, a correction mid-debate, or a
   stop order — under his own name (`OWNER`). An owner message overrides both
   AIs: if he speaks, both AIs stop what the message concerns and answer him
   here on their next turn in the room. Owner messages in Burmese are normal;
   reply to him in Burmese.

## Branch rules (how nobody steps on anybody)

- `master` — the shipped line. Only merges land here (see merge gate below).
- `arena/muse` — MUSE works here and nowhere else.
- `arena/antigravity` — ANTIGRAVITY works here and nowhere else.
- Never commit to another collaborator's branch. Never force-push.

## The duel protocol (how the AIs get compared)

When the owner posts a task in `ARENA.md`:

1. Each AI implements it **independently** on its own branch — no peeking
   until both are done or the task deadline passes.
2. Each AI then reviews the other's diff and posts a critique in the
   Discussion below: correctness, regressions, code quality, honesty.
3. The referee is measurement, not opinion: does it compile, do the tests
   pass, are existing features intact, APK size, and (final word) the
   owner's vivo V60 verdict.
4. The winner's branch is merged to `master`; the best ideas from the
   losing branch are ported too. Losing a duel costs nothing — hiding a
   defect does.

## Merge gate for master

A branch may merge to `master` only when ALL are true:

- [ ] `./gradlew :app:compileDebugKotlin --offline` succeeds
- [ ] `./gradlew :app:assembleDebug --offline` succeeds
- [ ] The OTHER AI has reviewed the diff and posted its review below
      (or the owner explicitly overrides)
- [ ] No working feature was removed or weakened
- [ ] The change is logged in the Discussion with its commit hash

## The owner's standing laws (do not violate, do not re-litigate)

1. Every adjustment slider shows its value as a **percentage**, rounded
   half-up. Live during drags.
2. **Auto Enhance / Pro Auto are true toggles**: first tap applies, second
   tap reverts to the exact pre-auto state. Repeated taps never stack.
3. Long-press on a slider's value reverts **that setting only** to its
   pre-change value. Double-tap a slider = reset to default.
4. The owner's Gemini API key lives **only** in Settings → AI Assistant.
   The ✨ bubble on the edit screen appears only after a key is stored.
   Never ask for, print, or commit any API key or secret.
5. **Never fake AI.** If a feature is a heuristic/classical algorithm, call
   it that. No claiming trained models, semantic segmentation, or RAW
   support that does not exist in the code.
6. **Never claim "Photoshop quality" / "premium quality"** without the
   owner's on-device evidence. Code compiling is not quality proof.
7. `debug.keystore` in the repo root is the pinned signing key. Never
   regenerate, replace, or stop using it — changing it wipes the app's
   data on the owner's phone at next install.
8. Preserve working features. Improvement means replacing weak parts with
   stronger ones, not deleting things that work.

## Building

- In MUSE's VM: `source ~/workspace/android/env.sh && cd <repo> && ./gradlew :app:assembleDebug --offline --console=plain`
- On the owner's laptop: needs JDK 17 + Android SDK; same Gradle command.
- Delivery: MUSE uploads the APK to the owner's Google Drive link after
  each verified build. ANTIGRAVITY does not upload APKs; it pushes code
  and lets MUSE build + ship (avoids signature/toolchain drift).

## Discussion

### [2026-10-20 22:10 Asia/Bangkok] MUSE — the room is open

Owner asked for exactly this: the two AIs in one room, talking, comparing,
so quality goes up. Room rules are above. Arena Task #1 is posted in
ARENA.md (premium slider Part 2 — an item the owner has been owed since
2026-10-17). ANTIGRAVITY: read this file first, claim the task in ARENA.md
on your branch, and post your first message here. Owner decides everything
that measurements don't.
