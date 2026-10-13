# Design: moments, labels and notes without a score

**Date:** 2026-10-13
**Status:** Approved 2026-10-13. Plan: `2026-10-13-match-moments-plan.md`.
**Follows:** `2026-08-26-live-scoring-review-design.md`,
`2026-08-30-scoreboard-label-scope-design.md`
**Stacked on:** `feat/heatmap-rallies` (PR #15). The board described below,
Edit score included, exists only on the unmerged stack #7-#15, not on main.

Feedback from coaches: not every match is scored. Some are practice games or
drills where nobody keeps the count, and the coach still wants to put down
"good shot", "unforced error" or a sentence about the backhand clear. Today the
board refuses: at 0-0 every chip is greyed and Note is disabled, because a label
can only ever land on a scored rally.

This document adds a second thing the label row can produce, a **moment**: a
label or a note, or both, logged at a point in time without a rally to hang it on.
Rally tagging is unchanged. The board's layout is unchanged. The label row
simply stops being dead when it has no rally to point at.

---

## 1. What exists today

Verified by reading the code on this branch. File references are the evidence.

### 1.1 A tag can only name a scored rally

- The match is its event log. `ScoreEvent` has three entries: `PointTo`,
  `TagPoint` and `Retire` (`ScoreEvent.kt:52`). Labels and the note travel only on
  `TagPoint`, which names a rally by `pointOrdinal` (`ScoreEvent.kt:67`).
- The fold resolves tags by ordinal up front (`MatchState.kt:160`) and attaches
  them while it builds each `ScoredPoint` (`MatchState.kt:184`). At 0-0 there is
  no `ScoredPoint`, so there is nothing a tag could be attached to.
- Events carry no timestamp. Order in the list is the only "when" a log has.

### 1.2 The board points its label row at the last rally, or at nothing

- Android: `ScoringViewModel.pending` starts as `null`
  (`ScoringViewModel.kt:69`) and becomes the last ordinal after a score, undo,
  redo or score edit; reset and finish set it back to `null`.
- iOS mirrors it: `pendingTagOrdinal` starts `nil` (`ScoringModel.swift:28`).
- With no target, chips are drawn greyed and inert (`ScoringScreen.kt:583`,
  `ScoringView.swift:431`) and Note is disabled (`ScoringScreen.kt:666`,
  `ScoringView.swift:489`).
- One consequence nobody asked for: a board **reopened** on a match at 5-3 also
  starts with no target, so its row is dead until the next rally is scored.
- The caption beside Edit score is written twice, once per platform
  (`ScoringScreen.kt:712` `tagRowCaption`, `ScoringView.swift:549` `caption`).

### 1.3 The strip already confirms an action and offers to reverse it

Undo shows "Point to Home undone" in the status strip with a Redo button for
5 seconds (`ScoringScreen.kt` `StatusStrip`, `ScoringView.swift:123`), backed by
`UndoneRally` (`MatchScorer.kt:159`). Redo is only offered while the log is
unchanged since the undo (`MatchScorer.kt:106`).

### 1.4 Everything downstream reads tags off points

| Surface | Reads | Today with no points |
| --- | --- | --- |
| `PointsFacet.kt` / `PointsFacet.swift` | `state.points` | empty list, "Score" button |
| `buildScoreTagSummary` (`ScoreTagSummary.kt:28`) | `point.tags` | `EMPTY`, tally hidden |
| tally header (`PointsFacet.kt:121`, `PointsFacet.swift:65`) | `taggedPointCount` | hidden |
| `exportMatchText` (`ScoreTagSummary.kt:50`) | `state.points` | header only |
| `scoreLine` (`ScoreMatchCard.kt:38`) | `points`, `completedGames` | "Not started" |
| match list `started` (`MatchListBuilder.kt:261`) | `points`, `completedGames` | no SCORE analysis |

### 1.5 "Has anything happened" is asked as "is the log empty"

`canSwitchFirstServer` is `log.events.isEmpty()` in two places
(`MatchScorer.kt:37`, `ScoringViewModel.kt:115`), and the write it guards,
`ScoreLogsRepository.setFirstServer`, refuses on the same test. A moment would
therefore silently take the SERVE switch away, and changing only the first two
would leave SERVE offered with a tap that does nothing. All three sites change
with this design (§5.6).

### 1.6 Storage needs no change

`events` is unchecked `jsonb` (`20260827000000_score_logs.sql`), and
badminton-tracker never reads `score_logs`. **No migration is needed.**

The app is not in production, so there is no compatibility layer for builds
without this change. An older build decodes the new event type as an error
(`ScoreEvent` is a sealed type with a `"type"` discriminator, `ScoreLogTest.kt:88`).
The answer to that is updating the build, not a fallback.

---

## 2. The fact the design turns on

**A moment is a thing that happened, not a part of the score.**

So nothing that edits the score may remove one: not undo, not reset game, not
the score editor. And a moment has no rally, so it has no `rally_index` and no
binding onto a clip. What it does have is a time and a place in the sequence of
rallies, and those are what it is stored with.

---

## 3. The product calls

All three were put to the owner in a mockup review on 2026-10-13. Each recorded
answer was the recommended option.

| Question | Chosen | Rejected, and why |
| --- | --- | --- |
| Where does one moment end and the next begin? | **Every chip tap is its own moment.** Note composes a richer one (several labels plus text). | One open moment collecting ✓ toggles until a point or "New moment". It mirrors rally tagging, but tapping "Good shot" again for a second good shot would *remove* the first, and it needs a new action-row button. |
| Moments in the middle of a scored match? | **Not in this pass.** In a scored match, labels go on rallies. | A "Moment" control that retargets the row until the next point. Costs action-row width at the largest fonts and gives every chip tap a second meaning mid-rally. |
| Reopening a scored match at 5-3? | **Open with the row on the last rally.** | Keep opening untargeted, which with moments would turn a tap meant for the last rally into a moment. |

---

## 4. Data and the fold

### 4.1 The event

```kotlin
@Serializable
@SerialName("moment")
data class Moment(
    /** Stable across rewrites: a later Moment with the same id replaces this one. */
    val id: String,
    /** Rallies played when it was logged. Its place in the sequence, not a binding. */
    @SerialName("after_points") val afterPoints: Int,
    /** The device clock when it was logged, for display and a later video alignment. */
    @SerialName("logged_at") val loggedAt: Instant,
    val tags: List<PointTag>,
    val comment: String?,
) : ScoreEvent
```

- **Explicit anchor rather than list position.** The fold already treats tags as
  order independent so a later sync layer can deliver them out of order
  (`MatchState.kt:156` comment). An explicit `afterPoints` keeps moments to the
  same rule, and survives the score editor rewriting the event list.
- **Edits are appends.** Changing a moment appends another `Moment` with the same
  `id`, and the fold reads the last one, exactly as re-tagging a rally appends
  another `TagPoint`.
- **Removal is an append too.** A version with no tags and no comment is a
  tombstone: the moment folds to nothing. Putting it back appends the previous
  version again.
- `PointTag` is reused, so a moment's labels are the same name and colour
  snapshot a rally's are (scoreboard-label-scope §2).

### 4.2 The fold

`MatchState` gains `moments: List<LoggedMoment>`:

- resolved up front, like `tagsByOrdinal`: the last version per `id`;
- tombstones dropped;
- `afterPoints` clamped to `pointCount`, so a moment logged after a rally that
  undo then took back sits after the rally before it;
- sorted by `(afterPoints, loggedAt)`.

Moments never affect the score, the server, the interval or the winner. They
are read independently of the fold's `if (winner != null) break`
(`MatchState.kt:177`), so a moment logged after match point still folds.

---

## 5. Log operations

### 5.1 Logging

The shipped API, all on `MatchScorer`:

- `logMoment(tags)` appends a new `Moment` with `randomUuid()` (as
  `ScoreLogsRepository.kt:152` does for a match), `afterPoints = pointCount` and
  `loggedAt` now. It returns the `LoggedMoment`, so the board can offer Undo, and
  null, writing nothing, when `tags` is empty.
- `openMomentDraft()` starts the composer's `MomentDraft`, fixing `loggedAt` and
  `afterPoints` when it opens; `commitMomentDraft(draft)` writes it as one moment,
  or nothing and null when it is empty.
- `removeMoment(id)` appends the empty version (the tombstone) and returns the
  moment as it was; `restoreMoment(moment)` appends it back unchanged.
- `lastMoment()` is the live moment logged or changed most recently, in log
  order, which is the one "Remove last moment" takes.

### 5.2 Undo last rally, redo

`dropPointsFrom` (`ScoreEvent.kt:123`) keeps every `Moment` unconditionally.
The clamp in §4.2 places a moment that was anchored after the undone rally. Redo restores the whole log, as it does now; logging a
moment after an undo retires the redo, like any other change
(`MatchScorer.kt:106`).

### 5.3 Reset game

Keeps moments (§2). The cut keeps them via §5.2.

### 5.4 Edit score

`planScoreEdit` cuts at the game's start and rebuilds its rallies
(`ScoreEdit.kt:88`). Moments survive the cut via §5.2, but a removal shifts the
rallies after it, so any moment anchored inside the edited game is rewritten
with `afterPoints = start + (kept rallies before its old anchor)`. That keeps a
moment between the same two surviving rallies, the same promise the edit
already makes for tags. The editor's sentence (`describe`) does not mention
moments, because it never removes one.

### 5.5 Remove the last moment

A new overflow item, **"Remove last moment"**, shown only when the match has a
live moment. It tombstones the latest one by `loggedAt`, and the strip says
"Moment removed: Good shot" with Undo.

This is a change from the mockup, which had "Undo last rally" turn into it.
After Reset game the row targets moments while earlier games still have
rallies, and renaming the item there would have taken away undoing the last
rally of the previous game. Two items, each with one meaning, is the simpler
rule.

### 5.6 The first-server switch

`canSwitchFirstServer` asks "has a rally or a retirement been recorded" instead
of "is the log empty", in `MatchScorer` and in `ScoringViewModel`, which computes
it separately today and should ask the scorer instead. `setFirstServer` asks the
same question through the same predicate (`hasRallyOrRetirement`), so the board
never offers a switch the store then refuses. Logging moments before the first point must leave
SERVE switchable.

---

## 6. The board

### 6.1 What the row targets

The pending ordinal becomes a target, with one rule:

- **Rally(ordinal)** whenever there is a rally to tag. This is unchanged, plus one
  new case: the board **opens** on the last rally if the match has any (§3).
- **Moments** otherwise: no rallies yet, just after Reset game, after Finish, or
  after undo has taken the match back to no rallies.

`ScoringUiState.pendingTagOrdinal` becomes `target: BoardTarget?`, mirrored in
`ScoringModel`. Null still means "no match on this phone".

### 6.2 Chips and Note while targeting moments

- Chips are drawn at full colour, without ✓. A tap calls `logMoment` with that
  one label. The chip flashes once to confirm the tap, which costs no layout:
  no badge, no count, nothing that changes the chip's width and reflows the row
  under the coach's finger.
  - The flash is a press-in to 94% plus a white wash fading over about 260 ms,
    with a light haptic, because a moment changes nothing else on the board.
  - Under Reduce Motion the press-in is dropped; the wash and the haptic stay.
  - Each chip names its action for screen readers: "Logs a moment", "Adds it to
    this moment" or "Tags this rally".
- Note is enabled and opens a **composer**. While it is open, chip taps toggle ✓
  onto the draft instead of logging moments. The placeholder reads "Note on this
  moment".
- **A target change closes the composer, then commits the draft.** Scoring a
  side moves the target to that rally, and the field must not stay open as a
  rally note on it: Android draws the note field whenever `noteOpen` holds and a
  rally is targeted (`ScoringScreen.kt:590`). So any target change sets
  `noteOpen` false before the draft is saved. The draft is saved as one moment when the field goes away: Note
  tapped again, a side scored, the screen left. That is the same commit point
  rally notes use today (`ScoringScreen.kt:594`). An empty draft saves nothing.
  `loggedAt` is when the composer opened, which is when the thing was seen.
- The draft has to be reachable from the chip row, so it lives in the view model
  (`momentDraft`) rather than in the field's local state as the rally note's does.

### 6.3 The strip

The undo notice generalises to a board notice with a message and one action:

| After | Message | Action |
| --- | --- | --- |
| undo last rally (today) | Point to Home undone | Redo |
| a moment is logged | Moment added: Good shot | Undo (tombstones it) |
| a composed moment is saved | Moment added: Unforced error, Net / Moment added | Undo |
| Remove last moment | Moment removed: Good shot | Undo (re-appends it) |

The 5-second timer, the live region and the newest-wins behaviour are as they
are today. At the largest fonts the strip truncates with an ellipsis like every
other notice. Whether "Moment added: Unforced error" fits on a 360dp phone
at font 2x is a device check (§9.4), not an assumption.

### 6.4 The caption

Moves to shared, `boardCaption(...)`, so the two platforms stop maintaining two
copies (§1.2):

| State | Caption |
| --- | --- |
| Rally target | Point 3: 2-1 (unchanged) |
| Moments, none yet, playable | Tap a side to score, or a label to log a moment |
| Moments, some logged | 6 moments logged |
| Moments, match over | Match over · 6 moments logged (or "Match over" with none) |
| Composer open | New moment: Unforced error, Net / New moment |
| Board has no labels at all | today's text, unchanged |

The opening caption gives up "or SERVE to switch server" when the board has
labels: two hints do not fit in two lines beside Edit score. The SERVE pill is
still drawn on the serving side and announces itself to screen readers. This is
a deliberate loss, recorded here so it is not rediscovered as a regression.

### 6.5 After Finish

Moments after Finish are not reachable: every exit from Finish leaves the board,
and an UNBOUND match has no Resume, so whether a finished match should take a
post-match moment is an open product question.

### 6.6 What does not change

The halves, the action row and its contents, Edit score, Done, the interval and
game-point announcements, and every rally-tagging interaction. No new control is
added to the action row, so the AX5 (iOS) and font-2x (Android) layouts fixed in
the score-edit work are not reopened.

---

## 7. Downstream surfaces

All new rules live in shared, called by both platforms, following the
`buildMatchLabelSummary` pattern.

- **Timeline.** A shared `matchTimeline(state)` returns points and moments
  interleaved, newest first: a moment anchored at `afterPoints = k` sits after
  rally `k - 1` and before rally `k`. Both `PointsFacet`s render from it. A moment
  row is its clock time (device time zone at display), its label badges and its
  note, in the same row style as a point.
- **Tally.** `ScoreTagSummary` gains `momentCount`. Labels roll up from points
  and moments together through `rollUpLabels`, using timeline position as
  recency so the two sources share one scale. The header, moved to shared:
  "3 rallies tagged", "6 moments", or "3 rallies tagged · 6 moments".
- **Export.** `exportMatchText` interleaves moments as
  `At 19:42: Unforced error, Net (backhand clear lands short)`. It no longer
  returns the header alone when there are no points but there are moments.
- **Match card.** `scoreLine` says "6 moments" instead of "Not started" when
  there are moments and no rallies.
- **Match page button.** "Score" becomes "Resume" when the match has moments but
  no rallies, and stays "Resume scoring" once it has rallies.
- **Match list.** `started` (`MatchListBuilder.kt:261`) does **not** count
  moments. `MatchAnalysis.SCORE` is the row's "#" glyph and the Score filter
  (`MatchStatusLine.kt:137`, `MatchFilterRow.swift:222`). Its removal copy
  promises "every point you scored stays" (`MatchListCopy.kt:146`). A
  moments-only match has no score, so it gets none of these. Its row still says
  "6 moments" through `scoreLine`.

---

## 8. Deliberately not in this pass

- **Moments mid-match** in a scored match (§3).
- **Editing or deleting older moments from the match page.** The model supports
  it (append a version or a tombstone); the UI is a follow-on.
- **A "Keep score" switch on New match** that hides the halves and gives the
  board to the labels. Moments make that a layout change later, not a model change.
- **Aligning moments onto a video** by `loggedAt` against the recording's start
  time. The field is stored for it; nothing reads it that way yet.
- **Rally-level heatmaps or analytics for moments.** A moment has no rally.

---

## 9. How this is verified

### 9.1 Shared, `commonTest`

- Wire format: a `Moment` encodes as `"type":"moment"` with the four snake_case
  fields, and the existing three types encode byte-for-byte as before.
- Fold: the last version per id wins; a tombstone removes; `afterPoints` clamps;
  ordering by `(afterPoints, loggedAt)`; a moment after match point still folds;
  moments change no score field.
- Operations: undo, reset and `dropPointsFrom` keep moments; the score editor
  re-anchors a moment between the same two surviving rallies;
  `canSwitchFirstServer` stays true with moments only.
- Surfaces: the interleaving in `matchTimeline`, the tally counts and header,
  export with and without points, and `scoreLine` and `started` for a
  moments-only match.

### 9.2 Android

`ScoringViewModelTest`: the target on open (last rally / moments), a chip tap at
0-0 logs one moment per tap, the composer toggles and commits once, an empty
composer commits nothing, the notice Undo tombstones the moment, and Remove last
moment.

### 9.3 iOS

`ScoringModelTests` for the same cases. `ScoringBoardUITests`: chips are
hittable at 0-0 and Note opens there. The sim account has no cloud matches, so
the test creates its own.

### 9.4 On a device

The last step is on a real phone, handed over explicitly rather than closed out.

- On the S23 and an iPhone simulator, in light and dark:
  - score nothing and log six moments, including a composed one;
  - check the strip, the caption and the chip flash;
  - check the match page, the tally, the export and the match list row.
- At font 2x (Android) and AX5 (iOS): the strip's "Moment added: …" and the
  caption at 0-0, looking for truncation and overlap.
- Mixed match: log moments at 0-0, score a game, and confirm the moments sit
  before rally 1 in the timeline and that SERVE was switchable throughout.
