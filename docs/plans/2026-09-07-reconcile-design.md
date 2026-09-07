# Design: reconcile the points to the rallies

**Date:** 2026-09-07
**Status:** Approved 2026-09-07; implementation plan not yet written
**Follows:** `2026-08-26-live-scoring-review-design.md` §7 L2 (which specified
this), `2026-08-28-match-video-attach-design.md` (which made `BOUND` reachable
and reserved `RECONCILED` for this), `2026-08-28-coaching-capability-review-design.md`
(which ranks it "build first")

A match scored courtside holds a point sequence. The same match, filmed and
analysed, holds a sequence of detected rally clips. Today they sit on one page
as two facets that know nothing of each other. This binds them: point N onto
rally N, ordinally, corrected by the coach where the detector drifted, and
written once so every existing reader of clips and annotations sees the
result without learning anything new.

The review design specified the mechanism in full and this document does not
re-argue it. What it adds is what was found by reading the code: where the
write must go, what the schema actually permits, how a re-bind finds its own
rows, and how the offset-nudge-skip vocabulary reduces to three one-tap edits
over a pure function.

---

## 1. What exists today

Verified by reading the code, not assumed. File references are the evidence.

### 1.1 The point log already carries the binding key

- `ScoredPoint` (`MatchState.kt:84-95`) carries `ordinal`, `gameIndex`,
  `wonBy`, `scoreBefore`, `scoreAfter`, `servedBy`, `tags`, `comment`. Its doc
  comment (`MatchState.kt:75-83`) names `ordinal` as the binding key and the
  other four as exactly what L2 denormalises onto clips.
- `ordinal` is **0-based** (`MatchState.kt:220`).
- `PointTag` (`ScoreEvent.kt:25-28`) is `labelName` plus `labelColor`, a
  snapshot in the same shape `RallyAnnotation` uses (`RallyAnnotation.kt:7-23`).
  The two are field-compatible.
- The score is never stored; `ScoreLog.state()` folds it on demand
  (`ScoreLog.kt:23-29`).

### 1.2 The status vocabulary is waiting

- `ScoreLogStatus` (`ScoreLog.kt:8-21`): `LIVE`, `UNBOUND`, `BOUND`,
  `RECONCILED`, the last documented "Not reachable until L2".
- `attachVideo` sets `videoId` and `status = BOUND` (`ScoreLogsRepository.kt:163-164`).
  `RECONCILED` is decodable (`ScoreLogTest.kt:64`) but never written.
- Every mutation on the repository is a named method over a private `edit`
  (`ScoreLogsRepository.kt:182-187`). Marking a log reconciled needs a named
  method, not a public `edit`.
- The `score_logs_unbind_on_video_delete` trigger
  (`20260827000000_score_logs.sql:84-100`) flips `bound` and `reconciled` back
  to `unbound` when the video goes. `delete_match`
  (`20260718000000_delete_match.sql:26-28`) deletes the video's annotations and
  clips. So everything this feature writes onto clips dies with the video,
  and `score_logs` is the durable copy. That was the intent
  (`20260827000000_score_logs.sql:9-14`).

### 1.3 The clip side, and what the client may write

- `rally_clips` columns: `0001_initial_schema.sql:44-56` in `badminton-tracker`
  plus `thumbnail_storage_path`, `title`, `annotation_count` from `0004:8-11`.
  `owner_id` is its own column (`0001:46`), set by Modal
  (`modal_supabase_processor.py:285`).
- `rally_index` is **1-based** at the writer: every detector assigns `i + 1`
  (`rally_detection.py:105`, `rally_detection_shot_gap.py:93,141,201`) and the
  on-device cutter agrees (`ClipCutter.kt:42-46`). Nothing in the schema
  enforces it.
- The client's only permitted UPDATE on `rally_clips` is `title`
  (`0004:46-47`, revoke then grant by column). There is no INSERT policy
  (`0002:27`, service role only).
- **There is no row-level UPDATE policy on `rally_clips` in either repo.**
  `0002` created only `clips_owner_select` (`0002:25-26`); this repo's
  `20260506000000_match_shares.sql:35` says the owner-only INSERT/UPDATE/DELETE
  policies "remain untouched", but none were ever created. With RLS enabled
  and no UPDATE policy, an UPDATE matches zero rows and reports no error. This
  means `ClipsRepository.updateTitle` (`ClipsRepository.kt:39-45`) is probably
  a silent no-op today. That is a defect outside this document's scope, noted
  in §9, and it settles how this feature must write (§3.2).
- `ClipsRepository` has no per-video read (`ClipsRepository.kt:28-31` fetches
  everything visible, newest first); every surface filters client-side
  (`MatchScreen.kt:173`, `MatchModel.swift:129-131`) and sorts by `rallyIndex`
  itself (`RalliesFacet.kt:22`, `ClipSort.swift:20`).

### 1.4 Annotations

- `rally_annotations`: `clip_id` and `timestamp_seconds` are NOT NULL
  (`0004:14-21`); `body` became nullable (`20260505000000:16`); `label_name` and
  `label_color` were added and the fixed `kind` column dropped
  (`20260824000000:76-78, 107-111`); a row needs a non-blank label or body
  (`20260824000000:113-118`).
- `bump_annotation_count` is a security-definer, FOR EACH ROW trigger
  (`0004:52-67`), so a bulk insert keeps the counts right for free.
- `RallyAnnotation.body` is non-nullable Kotlin with no default
  (`RallyAnnotation.kt:12`) although the column allows NULL. A label-only row
  must be written with `body = ""`, as the clip detail already does
  (`ClipDetailViewModel.kt:140-141`).
- `AnnotationsRepository.add` inserts one row per call (`AnnotationsRepository.kt:61-73`).
  There is no bulk insert.
- Nothing distinguishes an annotation the app wrote on the coach's behalf from
  one the coach typed.
- Labels carry `usage in ('both','scoreboard','clips')`
  (`20260830000000_label_usage.sql:9-17`), governing which pickers offer them.

### 1.5 The match page

- Android: `Facet { Points, Rallies }` (`MatchScreen.kt:78`); the selector
  renders only when `hasPoints && hasRallies` (`MatchScreen.kt:179-180`); one
  `LazyColumn` (`:359`) takes `pointsFacet` (`PointsFacet.kt:41`) or
  `ralliesFacet` (`RalliesFacet.kt`). `AttachStatusBanner` sits above the
  selector (`MatchScreen.kt:341-343`). The overflow holds "Export as text",
  "Change video", "Remove video" (`MatchScreen.kt:279-308`).
- iOS mirrors it: `MatchView.swift:21-24, 132-137, 242-260, 514-533, 581-598`.
- `attachStatus` returns null once `clipCount > 0` (`AttachStatus.kt:67`), so
  a reconcile prompt cannot be a new `AttachKind`; it is a sibling row with its
  own shared predicate.
- Page state: `hasServerVideo = log.videoId != null`
  (`MatchViewModel.kt:157`, `MatchModel.swift:183`); clip count is
  `allClips.count { it.videoId == log.videoId }` (`MatchViewModel.kt:154`,
  `MatchModel.swift:178`).
- Point rows are display-only (`PointsFacet.kt:106-140`); rally rows are
  `ClipRow` (`RalliesFacet.kt:85`) and `NavigationLink` to `ClipDetailView`
  (`RalliesFacet.swift:30-45`).

### 1.6 The house rules this follows

- One shared pure function both platforms call, tested in `commonTest`:
  `buildMatchLabelSummary`, `buildScoreMatchCard`, `attachStatus`,
  `canRemoveMatchVideo`, `buildScoreTagSummary`. The reason is stated at
  `AttachStatus.kt:13-15`: two platforms writing the same sentence are two
  chances to write it differently.
- The `...OrMessage` idiom (`MatchVideoRemoval.kt:86-88`): a suspend shared
  function returns null on success or a display string on failure.
- A security-definer RPC for a multi-table write the client may not perform
  row by row: `delete_match` (`20260718000000_delete_match.sql:10-35`).
- Migrations that alter tracker-owned tables live in this repo:
  `20260720000000_analyze_status_reset_grant.sql`, `20260505000000_annotation_kind.sql`,
  `20260824000000_annotation_labels.sql`.

---

## 2. The fact the design turns on

**There is no clock, so the binding is ordinal, and ordinal binding drifts.**
Review design §6.1 to §6.3 established both halves: no capture instant exists
on either side, and the detector does not produce one clip per point. Service
faults and two-shot rallies fall under its floors; a long pause splits a rally;
warm-up hitting produces rallies with no point.

So the screen is not a confirmation dialog over a computed map. It is the
mechanism by which the map becomes right, and it has to make drift visible at
the row where it starts and correctable from that row onward. Everything below
follows from that: a pure alignment function so the corrections are testable,
three edits that each shift the tail of the sequence by one, and an atomic
write so a map is either applied or not.

---

## 3. Decisions

| Decision | Chosen | Rejected, and why |
| --- | --- | --- |
| Where the mapping is computed | A pure function in `commonMain` over the point list, the clip list and a three-field draft | Per-platform view logic: the offset arithmetic between a 0-based ordinal and a 1-based rally index is exactly what two platforms get backwards |
| The edit vocabulary | Three one-tap actions: start here, not a point, no clip | A signed nudge control per row: a nudge is one of the two skips in disguise, and a signed integer needs explaining where "not a point" does not |
| How the write reaches the server | One security-definer RPC, `reconcile_match`, in one transaction | Client-side updates and inserts: `rally_clips` has no UPDATE policy (§1.3), and a partial apply, some clips stamped and some annotations written, is the corruption the design must rule out |
| Commit model | Explicit "Bind" button; Cancel discards the draft | The house idiom of immediate local-first mutation: it is right for a single row and wrong for a map, because the server write is one transaction and the screen should present one decision |
| Score columns | `score_home_at_start` and `score_away_at_start` as integers | The review design's single `score_at_start`: "points lost when serving above 15" should be a query, not string parsing |
| How the map persists | `point_ordinal` on each bound clip; nothing else | A `binding` JSON on the score log: two copies of one map drift, and the clip columns are where the readers already look |
| How a re-bind finds its own annotations | `point_ordinal` on `rally_annotations`, non-null only when reconcile wrote the row | Deleting every annotation on the clip: destroys notes the coach typed. Storing created ids on the log: a second copy of state that a cascade can invalidate |
| Annotation timestamp | 0 seconds, the clip's start | Deriving it from tag semantics: the review design warned against it (§7 L2). Draggable later if wanted |
| Entry point | A row above the facet selector, sibling to `AttachStatusBanner` | The overflow menu: this is the promise the video-attach work made, not a utility |
| Scope of one screen | The whole match, with game boundaries as section headers | Game by game: drift crosses game boundaries and the correction should follow it |
| Scoreboard-scoped labels | Written onto clips like any other tag | Filtering by `usage`: the scope governs what a picker offers, and a snapshot on a clip is not a picker |

---

## 4. Shared model

New package `com.badmintontracker.shared.scoring.reconcile`.

### 4.1 The draft

```kotlin
/**
 * Everything the coach can change. Deliberately small: the map is a function
 * of this and the two sequences, never stored on its own.
 */
data class ReconcileDraft(
    /** The rally_index (1-based) that holds the first point. */
    val firstRallyIndex: Int,
    /** Rallies that are not points: warm-up, a split rally's second half. */
    val skippedRallies: Set<Int>,
    /** Points with no clip: a service fault, a rally under the detector's floor. */
    val unclippedPoints: Set<Int>,
)
```

`firstRallyIndex` defaults to 1 and the screen still asks the question (§6.2),
because the default is wrong whenever there was warm-up and that is the worst
place for drift to begin.

### 4.2 The alignment

```kotlin
sealed interface ReconcileRow {
    data class Bound(val point: ScoredPoint, val clip: RallyClip) : ReconcileRow
    data class PointOnly(val point: ScoredPoint) : ReconcileRow
    data class RallyOnly(val clip: RallyClip, val reason: RallyOnlyReason) : ReconcileRow
}
enum class RallyOnlyReason { BEFORE_FIRST, SKIPPED, AFTER_LAST }

data class Alignment(
    val rows: List<ReconcileRow>,
    val boundCount: Int,
    val pointCount: Int,
    val unusedRallyCount: Int,
)

fun alignPoints(points: List<ScoredPoint>, clips: List<RallyClip>, draft: ReconcileDraft): Alignment
```

Rules, in order:

1. Points are taken in `ordinal` order, dropping `unclippedPoints`. Clips are
   taken in `rallyIndex` order from `firstRallyIndex`, dropping
   `skippedRallies`. The two filtered sequences are zipped; each pair is a
   `Bound` row.
2. Rows are emitted in rally order with the unclipped points interleaved at
   their ordinal position, so the list reads as one timeline rather than two.
3. Clips before `firstRallyIndex` are `RallyOnly(BEFORE_FIRST)`; skipped ones
   `RallyOnly(SKIPPED)`; clips past the last bound point `RallyOnly(AFTER_LAST)`.
   Points past the last clip are `PointOnly`.
4. `boundCount` is the number of `Bound` rows. `pointCount` is `points.size`.
   The summary string "Bound 38 of 41 points, 3 rallies unused" comes from a
   shared `reconcileSummary(alignment)`, for the §1.6 reason.

The three edits map onto the draft without arithmetic at the call site: "start
here" sets `firstRallyIndex` and drops any skipped rally below it; "not a
point" toggles membership in `skippedRallies`; "no clip" toggles membership in
`unclippedPoints`.

### 4.3 What the write carries

```kotlin
@Serializable
data class ClipBinding(
    @SerialName("clip_id") val clipId: String,
    @SerialName("point_ordinal") val pointOrdinal: Int,
    @SerialName("game_index") val gameIndex: Int,
    @SerialName("score_home_at_start") val scoreHomeAtStart: Int,
    @SerialName("score_away_at_start") val scoreAwayAtStart: Int,
    @SerialName("serving_side") val servingSide: Side,
    @SerialName("point_won_by") val pointWonBy: Side,
    val tags: List<PointTag>,
)

fun bindings(alignment: Alignment): List<ClipBinding>
```

One entry per `Bound` row, built from `scoreBefore`, `servedBy`, `gameIndex`,
`wonBy` and `tags`. The comment on a point is not carried: it is the coach's
private note on the log, and the clip's notes are what share recipients see.

### 4.4 The entry-point predicate

```kotlin
data class ReconcilePrompt(val text: String, val action: String)

fun reconcilePrompt(log: ScoreLog, clipCount: Int, boundCount: Int?): ReconcilePrompt?
```

Null unless `log.videoId != null && clipCount > 0`. For a `BOUND` log:
"Match the points to the rallies" with action "Reconcile". For a `RECONCILED`
log: "Reconciled, k of n points" with action "Edit", where `k` is the count of
this video's clips carrying a `point_ordinal`.

### 4.5 Reconciling, as an `OrMessage`

```kotlin
suspend fun reconcileOrMessage(
    log: ScoreLog,
    alignment: Alignment,
    scoreLogs: ScoreLogsRepository,
    clips: ClipsRepository,
): String?
```

Order matters and is fixed here rather than on each platform:

1. `scoreLogs.sync()` first. The RPC reads `score_logs` on the server, and a
   log that is dirty locally may not be there yet. A failed sync returns its
   message; nothing else runs.
2. `clips.reconcile(log.id, bindings(alignment))`, the RPC call.
3. `scoreLogs.markReconciled(log.id)`, the new named mutation, then
   `clips.refresh()` so the stamped columns arrive.

A failure at step 2 leaves the log `BOUND` and the clips as they were, because
the RPC is a transaction. A failure at step 3 leaves the server `reconciled`
and the phone `bound`; the next `sync()` takes the server's row (the local row
is not dirty), and the prompt corrects itself.

---

## 5. Storage

One migration in this repo, `2026090700000_reconcile.sql`, in the pattern of
`20260720000000_analyze_status_reset_grant.sql`.

### 5.1 Columns

```sql
alter table public.rally_clips
  add column point_ordinal        int,
  add column game_index           int,
  add column score_home_at_start  int,
  add column score_away_at_start  int,
  add column serving_side         text check (serving_side in ('home','away')),
  add column point_won_by         text check (point_won_by in ('home','away')),
  add constraint rally_clips_binding_all_or_nothing check (
    (point_ordinal is null) = (game_index is null)
    and (point_ordinal is null) = (score_home_at_start is null)
    and (point_ordinal is null) = (score_away_at_start is null)
    and (point_ordinal is null) = (serving_side is null)
    and (point_ordinal is null) = (point_won_by is null)
  );

alter table public.rally_annotations
  add column point_ordinal int;
```

No column grant to `authenticated`. The client never updates these directly;
the RPC does, as `security definer`. That is also why the missing UPDATE policy
(§1.3) does not block this feature, and why fixing it is not part of it.

Modal's `rally_clips` insert (`modal_supabase_processor.py:280-290`) names its
columns, so new nullable columns do not disturb it. `RallyClip` (Kotlin) gains
the six fields as nullable with defaults, so rows written before the migration
decode unchanged.

### 5.2 The RPC

```sql
create or replace function public.reconcile_match(p_score_log_id uuid, p_bindings jsonb)
returns void language plpgsql security definer set search_path = public as $$
declare v_video_id uuid;
begin
  if auth.uid() is null then raise exception 'unauthenticated' using errcode = 'P0001'; end if;
  select video_id into v_video_id from public.score_logs
    where id = p_score_log_id and owner_id = auth.uid();
  if v_video_id is null then raise exception 'not_owner_or_unbound' using errcode = 'P0002'; end if;
  if not exists (select 1 from public.videos where id = v_video_id and owner_id = auth.uid()) then
    raise exception 'not_owner' using errcode = 'P0002';
  end if;
  if exists (
    select 1 from jsonb_array_elements(p_bindings) b
    left join public.rally_clips rc on rc.id = (b->>'clip_id')::uuid
    where rc.id is null or rc.video_id <> v_video_id
  ) then raise exception 'clip_not_in_video' using errcode = 'P0003'; end if;

  -- Re-binding is delete-and-reinsert of the rows this binding created.
  delete from public.rally_annotations
    where point_ordinal is not null
      and clip_id in (select id from public.rally_clips where video_id = v_video_id);
  update public.rally_clips set point_ordinal = null, game_index = null,
    score_home_at_start = null, score_away_at_start = null,
    serving_side = null, point_won_by = null
    where video_id = v_video_id;

  update public.rally_clips rc set
    point_ordinal = (b->>'point_ordinal')::int, game_index = (b->>'game_index')::int,
    score_home_at_start = (b->>'score_home_at_start')::int,
    score_away_at_start = (b->>'score_away_at_start')::int,
    serving_side = b->>'serving_side', point_won_by = b->>'point_won_by'
    from jsonb_array_elements(p_bindings) b where rc.id = (b->>'clip_id')::uuid;

  insert into public.rally_annotations
    (clip_id, owner_id, timestamp_seconds, body, label_name, label_color, point_ordinal)
  select (b->>'clip_id')::uuid, auth.uid(), 0, '', t->>'label_name', t->>'label_color',
         (b->>'point_ordinal')::int
    from jsonb_array_elements(p_bindings) b, jsonb_array_elements(b->'tags') t;

  update public.score_logs set status = 'reconciled'
    where id = p_score_log_id and status = 'bound';
end $$;
revoke all on function public.reconcile_match(uuid, jsonb) from public;
grant execute on function public.reconcile_match(uuid, jsonb) to authenticated;
```

Points of note, so the plan does not rediscover them:

- Every check runs before any write, and the whole body is one transaction, so
  a rejected payload changes nothing.
- The clip-membership check is what stops a caller stamping another video's
  clips, since `security definer` bypasses RLS inside the function.
- `bump_annotation_count` fires per inserted and per deleted row and keeps
  `annotation_count` right without help.
- The trailing `status` update is conditional on `bound` so an already
  reconciled log re-reconciled stays `reconciled` and a log that is somehow
  `unbound` is not promoted.
- `ClipsRepository.reconcile(scoreLogId, bindings)` calls it through
  `postgrest.rpc`, the way `delete_match` is called today.

### 5.3 Reading the result

`RallyClip` gains the six nullable fields. Share recipients read them through
`"clips: select own or shared"` unchanged; the reconcile-written annotations
reach them through `"annotations: select own or shared"` unchanged. That is
the whole reason the review design put the snapshot on the clip.

---

## 6. Screens

### 6.1 Entry, on the match page

A row between `AttachStatusBanner` and the facet selector, on both facets,
driven by `reconcilePrompt` (§4.4). Android: a `ListItem` with a trailing
`TextButton`; iOS: the same shape as the attach banner. Tapping pushes the
reconcile screen.

### 6.2 The reconcile screen

A pushed destination on both platforms (`Route.Reconcile(scoreLogId)`;
`ReconcileView(scoreLogId:)`), structured like court marking: fixed top bar,
scrolling list, fixed bottom action bar.

**Top card, the one question.** "Which rally is the first point?" over a
horizontal strip of the first eight rallies as thumbnail chips with their
duration. The selected chip is `firstRallyIndex`. It is a card on the same
screen, not a wizard step, so changing it later is a scroll up rather than a
back.

**The list.** One row per `ReconcileRow`, in timeline order, with a section
header at each game boundary ("Game 2"). Each row is two halves:

- Left, the point: score after the point, who won it, its tag badges. Empty
  with a dashed outline for a `RallyOnly` row.
- Right, the rally: "Rally 7", thumbnail, duration. Empty with a dashed
  outline for a `PointOnly` row.
- A trailing overflow with the two edits that apply: on a row with a rally,
  "Not a point" or "Is a point"; on a row with a point, "No clip" or "Has a
  clip"; on a rally row, "Start here".

Tapping a thumbnail opens the existing clip player (`ClipDetail` on Android,
`ClipDetailView` on iOS). Watching is the only real check that a rally is the
point it is paired with, and the draft survives the round trip because it
lives in the screen's view model.

**Bottom bar.** Primary: "Bind 38 of 41 points", disabled while the RPC runs.
Secondary: "Cancel", which pops without writing. No confirm dialog: the write
is reversible by reconciling again, and the button already states what it will
do.

**Failure.** The `OrMessage` string in a snackbar (Android) or alert (iOS),
draft intact.

### 6.3 The match page after binding

- Rally rows gain a score chip when the clip carries `point_ordinal`:
  "G2 · 11-9 · Home". Built by a shared `clipScoreChip(clip, log)` so the two
  platforms cannot format it differently.
- Point rows become tappable when a clip carries their ordinal, and open that
  clip. The lookup is a shared `clipForPoint(clips, ordinal)`.
- The prompt row reads "Reconciled, 38 of 41 points" with "Edit".

---

## 7. Sync and lifecycle

- The score log must be on the server before the RPC runs; §4.5 syncs first.
  It normally is, because `attachVideo` runs after `CREATE_ROW`
  (`2026-08-28-match-video-attach-design.md` §4.2) and the log syncs long
  before clips exist.
- Removing or changing the video: the trigger flips the log to `unbound`,
  `delete_match` cascades the stamped clips and the written annotations, and
  the prompt disappears with the clips. Attaching a new video makes the prompt
  reappear on a `BOUND` log. Nothing new is needed.
- Editing points after reconciling: no flow edits a finished match today
  (`PointsFacet.kt:65` shows the board only while `LIVE`). If one is added, the
  snapshot goes stale exactly as a renamed label leaves old annotations, and
  re-reconciling is the correction. That is the house rule already.
- Reconciling again replaces the previous binding entirely (§5.2). The screen
  opens with the draft reconstructed from the clips' `point_ordinal`, so
  editing a reconciled match starts from what was bound, not from rally 1.

---

## 8. What the first reconciled match tells us

The attach design deferred this until "the first bound match tells us how far
the detector actually is from the point log". A read-only query for that
number was prepared during this design and could not be run from the agent
session, so the number is still unknown. The screen is designed to work at
any drift, and the summary line makes the number visible every time: rallies
detected, points scored, bound k of n. Record it in the plan's final report
from the first real match on each platform.

---

## 9. Deliberately not in this pass

- **The error ledger, the serve ledger, player identity.** The next items in
  the assessment's running order, each its own design.
- **Attaching a video-first match to a score log.** A different gesture on a
  different screen, deferred by the attach design for the same reason.
- **Dragging a tag's timestamp within the clip.** Every tag lands at 0 s.
- **Editing points after reconciling.** §7.
- **Fixing the missing UPDATE policy on `rally_clips`.** §1.3. A defect that
  predates this feature, that this feature routes around, and that deserves
  its own verification against the server: rename a clip from the app and
  check whether the title changed.
- **A comment on a point becoming a clip note.** §4.3.

---

## 10. How this is verified

### 10.1 Shared, `commonTest`

`ReconcileAlignmentTest`, against `alignPoints`:

- Identity: n points, n clips, draft at rally 1, all bound, k = n.
- Offset: `firstRallyIndex = 3` on n clips binds points 0.. to rallies 3..,
  and rallies 1 and 2 are `RallyOnly(BEFORE_FIRST)`.
- "Not a point" on rally 5 shifts every later point up by one rally, and the
  row order still reads as one timeline.
- "No clip" on point 4 shifts every later point down by one rally; point 4 is
  `PointOnly` at its ordinal position.
- More rallies than points: the tail is `RallyOnly(AFTER_LAST)`.
- More points than rallies: the tail is `PointOnly`.
- The 0-based ordinal to 1-based rally index conversion, by inspecting a
  `Bound` row's `clip.rallyIndex` against its `point.ordinal + 1` at draft
  rally 1. Mutating the constant fails this test.
- `reconcileSummary` phrasing for one point, many points, zero unused.

`ReconcileBindingsTest`: `bindings` carries `scoreBefore` not `scoreAfter`,
`servedBy` as `serving_side`, `wonBy` as `point_won_by`, and the tags verbatim;
`ClipBinding` serialises to the JSON the RPC reads (`serving_side` as
`"home"`/`"away"`).

`ReconcilePromptTest`: null without a video, null with a video and no clips,
"Reconcile" on `BOUND`, "Edit" with the count on `RECONCILED`.

`reconcileOrMessage` against the fake client `AnalyzeCoordinatorTest` uses:
a failing sync runs nothing else; a failing RPC leaves the log `BOUND`.

### 10.2 The migration

Committed, not applied: `supabase db push` is the owner's step, and the final
report says so. The RPC's rejection paths (not owner, clip from another video)
are exercised by hand in the SQL editor before the app is pointed at it.

### 10.3 On device

- Both platforms: score a short match, attach and analyse its video,
  reconcile with at least one of each edit, bind, and confirm the rally rows
  show score chips, the point rows open their clips, the clip detail shows the
  courtside tags as notes, and the prompt reads "Reconciled".
- Reconcile again with a different first rally and confirm the previous tags
  are gone and the new ones present, with `annotation_count` right.
- A share recipient sees the score chips and the tag notes.
- Screenshots of the prompt, the screen with each row kind, and the match page
  after binding, in both themes, into `docs/screenshots/`.
