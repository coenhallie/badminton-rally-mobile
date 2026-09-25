# Undo takes back the last rally

**Problem.** Coen: "there is no easy way of reverting a mistaken score." Undo existed on
both boards, but it was a small plain "Undo" text button, and it dropped the last *log
entry* rather than the last rally. Tags and notes are entries too, so on a tagged rally
the first taps took the tags off one at a time and left the score exactly as it was. From
the court, that looked like a mis-scored point could not be taken back.

The live-scoring engine plan (2026-08-26) chose entry-level undo on purpose ("the second
undo is the tag, and the coach sees it go"). This design reverses that choice. The tag
row is a toggle, so a tag is already taken off by tapping its chip again, and undo only
has to handle the score.

## Behaviour

- **Undo takes back the last rally together with its tags and note** -
  `List<ScoreEvent>.undoLastPoint()`, which is `dropPointsFrom(pointCount - 1)`. The cut
  is by ordinal, so a note written about an earlier rally *after* the last one (the
  interval case) survives. The old undo would have deleted it first.
- **Enabled only when there is a rally to take back** (`pointCount > 0`), including on a
  finished match, which reopens exactly as before.
- **The tag row goes back to the rally before**, so the board looks exactly as it did
  just after that rally was scored.
- **The status strip confirms the undo for 5 seconds**, for example "Point to Away undone",
  with a **Redo** button. The strip is the board's "what just happened" line, and it sits
  clear of the halves that score. An overlay on the board would eat scoring taps. The
  notice is announced to screen readers: a polite live region on Android, a
  UIAccessibility announcement on iOS.
- **Redo is only valid while the log is exactly what the undo left.** `MatchScorer`
  compares the current events with the undo's post-cut list. Any other change retires the
  redo, whether a rally, a tag, a reset, finishing, or a sync. Otherwise putting the old
  log back would erase that change.
- No confirm dialog. Undo is the control reached for in a hurry, and Redo covers a
  mis-tap on it.

## Shape

Shared (`MatchScorer`): `undo(): UndoneRally?`, `canRedo(UndoneRally)`,
`redo(UndoneRally)`, and `ScoreLog.undoneNotice(UndoneRally)` for the copy, so the two
boards cannot word it differently. `UndoneRally` holds the full log from before and after
the cut, so a redo is one comparison and one write.

The platforms (`ScoringViewModel`, `ScoringModel`) hold the last `UndoneRally`. They
derive the notice from the live log, so the redo is retired without every caller having
to clear it. The views run the 5-second timer keyed on the undo itself, so a second undo
restarts it even when the words are the same.

Board: Undo is now an outlined button with an undo glyph (Lucide `undo-2` on Android,
`arrow.uturn.backward` on iOS). The caption beside it wraps to two lines, because the
opening hint no longer fits on one line next to the wider button.

## Verification

Unit tests on all three layers, run as mirrored lists (`ScoringViewModelTest` /
`ScoringModelTests`). `ScoringBoardUITests.testUndoTakesBackATaggedRallyAndRedoPutsItBack`
drives the real iOS board. Android was driven by hand on the Pixel_9a emulator: score,
tag, undo, redo, and the timeout.
