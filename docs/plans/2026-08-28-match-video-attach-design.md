# Design: attaching a video to a scored match

**Date:** 2026-08-28
**Status:** Proposal, pending approval
**Follows:** `2026-08-26-live-scoring-review-design.md` (§7 L2),
`2026-08-27-live-scoring-match-record-plan.md`, `2026-08-27-live-scoring-surface-plan.md`
**Supersedes:** one sentence of §7 L2 in the review design (see §4.2)

The coach's flow has two halves and they do not meet yet. He can create a match
and score it point by point, and he can import a video and get one clip per
rally, but the match he scored and the video he filmed are two unrelated rows in
two unrelated lists. This document is about the seam.

Scope, in his words: create a match, score it, be asked whether to add the
video; if not now, add it later from the match itself; that starts the pipeline
and says so while it runs; when it finishes the clips are part of the match.

---

## 1. What exists today

Verified by reading the code, not assumed. File references are the evidence.

### 1.1 The scoring half

- `score_logs` is this app's own table (`supabase/migrations/20260827000000_score_logs.sql`).
  It carries `video_id uuid references public.videos(id) on delete set null`,
  nullable, plus a `status` of `live | unbound | bound | reconciled` and a
  trigger that forces `unbound` when a delete nulls `video_id`.
- `ScoreLogsRepository` is local first. Every mutation lands in memory and in
  `Settings` synchronously; `sync()` is the only suspending write, and it
  upserts every dirty row in one call before pulling the server's list.
- The board (`ScoringScreen.kt`, `ScoringView.swift`) has two terminal exits:
  the `Done` button, which appears only once `match.isOver`, and `Finish match`
  in the overflow behind a confirm. Both end in a plain back navigation.
- The match record (`ScoreMatchScreen.kt`, `ScoreMatchView.swift`) shows the
  score, the tag tally and every point. Its share button is present but
  permanently disabled, with "Add a video to share this match" as the reason.

### 1.2 The video half

- Intake (`VideoIntake.kt`, `LocalVideoIntake.swift`) mints a client UUID and
  persists a `LocalVideoEntry` at stage `LOCAL`. **That id becomes `videos.id`**
  at `CREATE_ROW`. The video's identity therefore exists from the instant the
  coach picks the file.
- Immediately after intake both platforms auto-open `MatchDetailsSheet` so the
  coach can name the match. That name rides along on the `videos` INSERT, and
  `videos.title` has no UPDATE grant, so this is the only moment it can be set.
  `canEditLocalVideoDetails` enforces exactly that: editing stops the moment the
  entry leaves `LOCAL`.
- Analyze is a two-step gesture: an `Analyze` button on the row navigates to
  `CourtMarking`, and `onStartAnalysis` calls
  `AnalyzeCoordinator.startAnalysis(entryId, keypoints)`, then pops back to the
  list.
- `AnalyzeCoordinator` runs `UPLOAD -> CREATE_ROW -> KEYPOINTS -> TRIGGER ->
  PROCESSING` in an app-scoped coroutine, publishes per-entry progress, resumes
  from the failed step on `retry()`, re-attaches after process death via
  `reattachToProcessing()`, and **removes the local entry on success** unless it
  carries local annotations.
- Clips arrive as `rally_clips` rows and are grouped into a match by `videoId`
  (`toMatches`, `MatchGrouping.matches`).

### 1.3 Where the two already touch

Exactly one place: the match list. `MatchRow` is a sealed interface of `Video`
and `Score`, and `mergeMatchRows` interleaves them newest first with a tie-break
on the key. Ported line for line between `MatchRow.kt` and `MatchGrouping.swift`.

Everything else is separate: separate list sections ("On this phone" vs "My
matches"), separate detail screens (`MatchClipsScreen` vs `ScoreMatchScreen`),
separate routes.

---

## 2. The one fact the design turns on

`LocalVideoEntry.id` becomes `videos.id`.

So a video has a stable identity from the moment it is picked, long before the
`videos` row exists on the server. The client can therefore know which match a
video belongs to immediately, and the server cannot be told until later. That
gap is the whole of §4.

---

## 3. Constraints, verified

**3.1 The FK forbids an early `video_id`.** `score_logs.video_id` references
`videos(id)`. There is no `videos` row until `CREATE_ROW` runs. Pushing the
binding before then is not a row that fails, it is a *batch* that fails:
`sync()` does `postgrest.from(TABLE).upsert(dirty)` in one call, so one FK
violation stops every pending score log on the phone from syncing. And
`ScoreLogsRepository.edit()` marks dirty unconditionally; there is no
local-only field today.

**3.2 `videos.title` is insert-only.** By deliberate design (`0008` documents
the absent UPDATE grant). A video attached to a named match must carry that
name onto the INSERT, or the match ends up with two names, and it cannot be
corrected afterwards.

**3.3 The pipeline deletes its own entry on success.** `runPipeline` ends in
`localVideos.remove(entryId)`. Anything the client needs to remember about the
attachment has to be durable before that line.

**3.4 `retry()` skips completed steps.** A run that failed at `TRIGGER` resumes
at `TRIGGER` and never re-enters the `CREATE_ROW` branch. Any work hung off that
branch's body would silently not happen on the retry path.

**3.5 Rally count is not point count.** From §6.3 of the review design:
`min_rally_duration_s`, `min_gap_duration_s` and a shot-count floor mean the
detector drops service faults and can split a long rally. Point *N* is not
reliably rally *N*.

**3.6 No new grant pass is needed.** This change writes only to `score_logs`,
which this app owns outright with all four owner policies. `videos` and
`rally_clips` are untouched. That is the payoff of the `score_logs`-owns-the-FK
decision made in §7 L1 and it holds here.

---

## 4. The design

### 4.1 The link lives on the entry until the server can hold it

`LocalVideoEntry` gains one nullable field:

```kotlin
/** The match this video was picked for, or null for a video-first import. */
val scoreLogId: String? = null,
```

Defaulted, like `title` and `description` before it, so a registry written by
the current build still decodes (`LocalVideoRepository.load()` swallows a decode
failure and returns an empty library, which would lose every local video).

This is the client's answer to "which match is this video for" for the entry's
entire life: while it sits at `LOCAL` waiting for court marking, while it
uploads, and after the row exists. It survives process death because the
registry is persisted.

### 4.2 `score_logs.video_id` is written after `CREATE_ROW`, not before

**This supersedes §7 L2 of the review design**, which says the binding "is set
at the start". It cannot be: §3.1. The reason is worth keeping in the file
because the sentence reads perfectly reasonable and implementing it would break
syncing for every match on the phone, not just this one.

`ScoreLogsRepository` gains two local-first mutations alongside `rename` and
`finish`:

```kotlin
fun attachVideo(id: String, videoId: String) =
    edit(id) { it.copy(videoId = videoId, status = ScoreLogStatus.BOUND) }

fun detachVideo(id: String) =
    edit(id) { it.copy(videoId = null, status = ScoreLogStatus.UNBOUND) }
```

`attachVideo` is called from the pipeline the moment the `videos` row is known
to exist, and it persists synchronously, which satisfies §3.3 with room to
spare.

Placement, given §3.4: **immediately after the `CREATE_ROW` block, not inside
it.** After that block the row exists whether it was just inserted, was a
duplicate-key no-op, or was created on an earlier run that failed later. Calling
it there is unconditional and idempotent.

**Status vocabulary.** `BOUND` means "this match has a video". `RECONCILED`
stays reserved for "a human has confirmed the point-to-rally map", which is §6.
This is slightly narrower than the review design's gloss on `bound` and it is
the reading the database trigger already assumes: it flips `bound` back to
`unbound` when the video goes away, which is a statement about the video and not
about a map.

### 4.3 The coordinator does not learn about scoring

The review design promised `AnalyzeCoordinator` "grows no dependency on
scoring", and that is worth keeping: it is a pipeline over one video, and its
tests should not need a scoring fixture to compile.

So the coordinator takes a narrow hook, wired in the app graph:

```kotlin
class AnalyzeCoordinator(
    ...
    /** Called once the videos row is known to exist. */
    private val onVideoRowReady: (entryId: String) -> Unit = {},
)
```

`createRallyApp` wires it to look the entry's `scoreLogId` up and call
`scoreLogs.attachVideo`. The coordinator knows an entry id and a moment; it does
not know what a match is. Existing tests keep their current constructor call
through the default.

### 4.4 One match, one row

Today an attached video would appear twice while it clips: once in "On this
phone" and once as its match. Two rules fix that, and they belong in the shared
merge rather than in each platform's list:

1. **An entry with `scoreLogId != null` is never rendered in "On this phone".**
   Its state belongs on its match row.
2. **A video match and a score match with the same id are one row.** Once clips
   land, `toMatches` produces a `MatchSummary` for the same video the score log
   points at. Unmerged, that is the duplicate again, in the same section.

`MatchRow` therefore grows from "two kinds of thing" to "one thing with
facets":

```kotlin
sealed interface MatchRow {
    /** A video imported on its own. No score log, and shared matches are always this. */
    data class Video(val match: MatchSummary) : MatchRow

    /** A scored match, with whatever video it has acquired. */
    data class Score(
        val card: ScoreMatchCard,
        /** Non-null once the pipeline has produced clips. */
        val video: MatchSummary?,
        /** Non-null while a video is attached but not yet clipped. */
        val pipeline: AttachStatus?,
    ) : MatchRow
}
```

`AttachStatus` is a small shared type derived from the entry's stage and the
coordinator's progress, so both platforms print the same sentence: "Video added,
court not marked", "Uploading 42%", "Clipping…", "Analysis failed".

**Sort key stays the score log's `createdAt`.** A merged row must not jump down
the list the moment its clips arrive: the coach created that match on Tuesday
and it belongs where he left it.

### 4.5 The flow, end to end

**Finishing a match asks.** The prompt fires on the transition to finished, from
either exit (`Done` after `isOver`, or `Finish match` in the overflow), so the
coach who uses the overflow is not the one who never gets asked. Three choices:
`Import video`, `Record video`, `Not now`.

All three then land on the match page, replacing the board in the stack rather
than stacking on it. That is a fix in its own right: today `Done` pops to the
list, so the record of the match just played is one tap further away than the
list of matches not played.

**The match page owns attaching.** The picker is plumbed to exactly one screen,
not to the board as well. `Import`/`Record` from the finish prompt navigate to
the match page carrying a "start the picker" intent; "Add video" on the match
page starts the same picker directly. One owner, one code path, two entry
points.

**Picking goes straight to court marking.** The coach has already said he wants
this video analysed; making him find an `Analyze` button afterwards is a second
decision for a question he answered. So intake, then straight to
`CourtMarking(entryId)`, then `startAnalysis` pops back to the match page, which
is now showing "Clipping…". Backing out of court marking is not a dead end: the
entry sits at `LOCAL` and the match page offers "Mark court" to resume.

**The details sheet does not auto-open.** For an attached video the name already
exists, and §3.2 says this is the only moment it can be written. So intake
pre-fills `entry.title` from the score log's title and skips the prompt
entirely. The auto-open stays exactly as it is for video-first imports, which
still have nothing to name them.

**Add video is offered only on a finished match.** A live match's action is
Score/Resume. This is not a restriction the coach will feel (he attaches the
video of a match that has been played) and it removes an interaction between two
state machines: `LIVE -> UNBOUND -> BOUND` is a line, not a lattice. It is also
already the shape of the finish prompt.

**One video per match.** "Add video" disappears once the match has one, in
either sense (a `videoId`, or an entry pointing at it). Replacing means removing
the video first, which §4.7 already has to handle.

### 4.6 Where the clips live

**Decided: one match page.** The alternative considered was a "Rallies (24)"
row on the score page pushing today's `MatchClipsScreen` unchanged, which risks
nothing but leaves a coach with two pages for one match and which one he lands
on depending on how that match was made.

The match page becomes one page with two facets, and shows whichever it has:

| The match has | The page shows |
| --- | --- |
| Points only | Header, tag tally, point list. Today's `ScoreMatchScreen`. |
| Clips only | Header, rally list, sort, label summary. Today's `MatchClipsScreen`. |
| Both | The same header, plus a `Points | Rallies` selector over the two lists. |

The argument for unifying rather than bolting a rally list onto the score page:
the match list already models a match as `videoId? + scoreLogId?`. Unification
extends an abstraction the codebase has on both platforms rather than
introducing a second one, and it means a coach has one page per match instead of
one page per way the match was made. Shared matches, which have no score log,
land on the same page showing only the rally facet, which is byte for byte what
they see today.

The cost is honest and worth naming: `MatchClipsScreen` (221 lines) and
`MatchClipsView.swift` (181) carry the sort menu, the share sheet, the label
summary sheet and pull-to-refresh, and all four have to come through the merge
intact on both platforms. That is the regression surface, and §7 says how it is
covered.

**The two lists stay two lists.** Before reconcile (§6) there is no trustworthy
map from point *N* to rally *N* (§3.5), so nothing on a point row may imply one:
no clip thumbnail on a point, no score on a rally. §6.3 of the review design
says drift must be visible and never silent; the UI equivalent is not drawing a
correspondence the data does not have yet.

### 4.7 Removal, and what must survive it

- **Deleting the video of a bound match.** The server trigger nulls `video_id`
  and forces `unbound`, but the local cache does not learn that until the next
  sync, and until then the row advertises clips for a video that is gone. So
  `deleteMatch(videoId)` also calls `scoreLogs.detachVideo` for any log pointing
  at it. Idempotent against the trigger, which has already done the same thing.
  **The points and tags survive**, which is the entire reason `score_logs` owns
  the FK: `delete_match` cascades `rally_annotations` away, and the score log is
  where the coach's courtside work still exists afterwards.
- **Deleting a bound match from the list.** Two things now hang off one gesture.
  The confirm has to say so: deleting the match deletes its clips and its
  points.
- **Removing the local entry mid-attach.** Already blocked while the pipeline
  runs (`canRemoveLocalVideo`). Before it starts, removing the entry must also
  clear the match's pending attachment, or the match page offers "Clipping…"
  forever for a file that is gone.

### 4.8 Failure states, on the match page

- **Pipeline failed.** The message and a `Retry` inline on the match page, which
  resumes from the failed step. The existing auto-dialog on the list is
  untouched.
- **Analysis found no rallies.** Special case worth calling out because it looks
  like success from the database's side: the `videos` row exists, so the match is
  `BOUND`, and there are zero clips. The page has to say "No rallies found in
  this video" and offer Retry, not render an empty rally list.
- **Video row created, app killed before clips.** `reattachToProcessing()`
  already covers it; the match page reads the same entry state and shows
  "Clipping…" again.

---

## 5. Task 0, and it is a hard prerequisite

`supabase/migrations/20260827000000_score_logs.sql` **is not applied to the
server.** Score-only matches work today because the store is local first, but
`sync()` and `delete()` both fail server-side, which is why deleting a scored
match currently reports "It's gone from this phone."

Nothing in this design can be verified end to end until that migration is
applied: the binding write targets a column in a table that does not exist. This
is L1a Task 1, it was reported and deliberately left out of the scoring work,
and it has to be the first task of this one.

---

## 6. Deliberately not in this pass

**Reconcile: mapping points onto rallies.** §7 L2 of the review design specifies
it in full - the offset question ("which detected rally is the first point?"),
the plus-or-minus nudge from any row onward, skipping a detected rally that was
not a point, and "bound *k* of *n* points" stated rather than implied. What
binding then writes is also already designed: the courtside tags become
`rally_annotations` rows on the matched clip, and the score situation
(`score_at_start`, `serving_side`, `game_index`, `point_won_by`) is denormalised
onto `rally_clips`.

It is left out here for two reasons. It needs a migration against `rally_clips`,
which the Modal pipeline writes, so it needs its own revoke-then-grant-by-column
pass in the style of `0004` and `0008`. And it is only worth designing against
real drift: the first bound match tells us how far the detector actually is from
the point log, and that number should shape the reconcile UI rather than be
guessed at now.

Everything in this document is a prerequisite for it, and nothing in it has to
be undone to build it. `RECONCILED` is already reserved for its terminal state.

**Attaching a video-first match to a score log after the fact.** A coach who
imported the video before creating the match has no way to marry them. Real, but
a different gesture ("link to a scored match") on a different screen, and not
the flow that was asked for.

---

## 7. How this is verified

**Shared, unit:** `attachVideo` / `detachVideo` set both fields and mark the row
dirty; a `LocalVideoEntry` written without `scoreLogId` still decodes; the
`AttachStatus` derivation for every stage; and `mergeMatchRows` producing one
row, not two, for a match whose clips have landed.

**Coordinator:** the hook fires once after `CREATE_ROW`, fires on a `retry` that
resumes at `TRIGGER` (§3.4), and fires before the entry is removed (§3.3).

**Both platforms, view model:** twelve-case parity as the scoring work
established. The finish transition raises the prompt from either exit; the match
page offers "Add video" only when the match is finished and has none; a failed
pipeline surfaces its message and Retry.

**On device, by hand:** the whole flow once per platform. Create a match, score
it, finish, accept the prompt, pick a video, mark the court, watch the match row
say "Clipping…", and see the rallies appear inside the match. Then the two
awkward ones, which are where this kind of feature actually breaks: kill the app
mid-upload and relaunch, and delete the video of a bound match and confirm the
points and tags are still there.
