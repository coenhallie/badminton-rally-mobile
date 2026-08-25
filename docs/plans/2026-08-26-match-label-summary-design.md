# Design: Match label summary

**Date:** 2026-08-26
**Status:** Draft, pending review

## Goal

Give a match one place that answers "how did I tag this video": how many
labelled notes it carries, which labels dominate, and which rally drew the most
attention. It sits on the match page (`MatchClipsScreen` on Android,
`MatchClipsView` on iOS), the screen that already lists every rally of one
video.

Today that screen shows per-rally note counts and nothing above them. A user
who tagged twenty rallies has to scroll and add up in their head.

## Scope

In:
- A shared, pure aggregation over one match's clips and annotations.
- One new read: annotations for a set of clip ids.
- A summary strip above the rally list on both platforms, showing the top
  labels with counts.
- A sheet behind that strip with the full label breakdown and a link into the
  most-labelled rally.

Out:
- Coverage ("14 of 22 rallies tagged"), unlabelled-note counts, unused labels.
  Deliberately cut to keep the readout lean; the aggregation leaves room for
  them.
- Distribution across rally index, label co-occurrence, cross-match trends.
- Filtering the rally list by label. Considered and deferred; see "Why the
  strip does not filter".
- Local videos. `LocalAnnotationsRepository` keys notes to the video with no
  clips in between, so the per-video rally page this feature hangs off does not
  exist there.
- Any database migration. The feature is a read over tables that already exist.

## Decisions

| Question | Choice |
| --- | --- |
| What counts as a label? | An annotation whose `label_name` is non-blank. Body-only notes are excluded. |
| How are renamed labels tallied? | Grouped on `label_name.trim().lowercase()`, matching `annotation_labels_owner_name_key`. |
| Which colour wins in a group? | The most recent annotation's, by `(created_at, id)`. |
| Where does aggregation live? | `shared/commonMain`, one pure function, unit tested once for both platforms. |
| How are annotations fetched? | `clip_id in (...)`, chunked at 100 ids, from clip ids already in memory. |
| Server-side aggregate instead? | No. Tens of rallies and hand-made notes per match. |
| Shared matches | One combined tally, not split by author. |
| Where on screen? | First row of the rally list, above the description. Not a toolbar action. |
| What opens it? | Tapping the strip anywhere. Chips are not individually tappable. |
| Zero labelled notes | No strip at all. |
| Failed fetch | Silent. No strip, no banner. |

## Why "labels" is not "notes"

`20260824000000_annotation_labels.sql` ends with a CHECK that admits a row with
a label and no body, or a body and no label:

```sql
check (
    (label_name is not null and length(trim(label_name)) > 0)
    or (body is not null and length(trim(body)) > 0)
)
```

That is intentional. `20260505000000_annotation_kind.sql` dropped `body`'s
NOT NULL precisely so a badge-only annotation could exist. The consequence for
this feature is that `rally_clips.annotation_count`, which drives the existing
"N NOTES" line and the `MostNotes` sort, counts notes, while a label tally
counts something narrower.

Whatever maintains `annotation_count` lives in the web repo, not this one, so
that it counts every annotation regardless of label is an inference from the
CHECK above rather than something read off a trigger. It does not need to be
settled: this feature computes its own count from the annotation rows and never
reads `annotation_count`. What it does mean is that the two numbers may differ
on real data, and the design assumes they will.

So "most labelled rally" gets its own ranking rather than reusing the
`MostNotes` sort, and every user-facing string in this feature says "labelled
notes", never "notes", so a strip reading 31 above a list of rows summing to 38
is legible rather than a bug.

## Why grouping is case-folded on the name

`label_name` and `label_color` are snapshots, deliberately: the model comment
says they are stored that way so a share recipient needs no read on the
sharer's labels and deleting a label cannot orphan an annotation.

The cost is that label identity is not stable across a rename. Rename "Good
shot" to "Winner" and a naive `groupBy { it.labelName }` reports two labels
where the user sees one.

Folding on `name.trim().lowercase()` is the same identity rule the database
already enforces on the live palette
(`annotation_labels_owner_name_key on (owner_id, lower(name))`), so the summary
agrees with what the Labels screen treats as one label. It does not repair a
rename, which is not recoverable from snapshots alone, but it does keep casing
and stray whitespace from splitting a tally.

Within a group the display name and colour come from the most recent
annotation, ordered by `created_at` then `id`. The tiebreak on `id` is not
decoration: two annotations added in the same second would otherwise leave the
chip's colour dependent on row order.

## Why the strip does not filter

Making a label chip filter the rally list is the natural next feature and the
screen is already shaped for it, with a sort menu carrying `RallyOrder` and
`MostNotes`. It is out of this change on purpose: filtering introduces a
selection state that interacts with sort, with pull-to-refresh, and with
returning from a clip, and none of that is needed to answer the question this
feature was asked to answer.

The consequence for this design is one constraint, not zero: the strip's chips
must not be individually tappable, because a tappable chip that does not filter
is a promise the screen does not keep. The whole strip is one target.

## Shared model

New file, `shared/src/commonMain/kotlin/com/badmintontracker/shared/model/MatchLabelSummary.kt`:

```kotlin
data class LabelCount(
    val name: String,
    val colorKey: String?,
    val count: Int,
    /** Rounded here, not per platform, so Android and iOS never show 45% and 46%. */
    val sharePercent: Int,
)

data class TopRally(
    val clipId: String,
    val rallyIndex: Int,
    val labelCount: Int,
)

data class MatchLabelSummary(
    val labelledNoteCount: Int,
    val labels: List<LabelCount>,
    val topRally: TopRally?,
) {
    val isEmpty: Boolean get() = labelledNoteCount == 0
}

fun buildMatchLabelSummary(
    clips: List<RallyClip>,
    annotations: List<RallyAnnotation>,
): MatchLabelSummary

/** The name as the strip's chips show it, cut at 16 characters. */
fun stripLabelName(name: String): String
```

`stripLabelName` is shared for the same reason the aggregation is. A label may
be 24 characters, two of those beside their counts and a chevron do not fit a
narrow phone, and a cut length maintained separately per platform is a cut
length that will drift. It takes no default argument, because Kotlin defaults do
not survive into the generated Swift initializer, so the cap has to live inside
the function rather than at each call site. The sheet always shows the full
name.

The contract, in full, because these are the rules the tests pin:

- Only annotations with a non-blank `labelName` are counted.
- Only annotations whose `clipId` appears in `clips` are counted. The fetch is
  chunked and could in principle return a row for a clip that has since been
  pruned from the cache; ignoring it keeps `labelledNoteCount` and the sum of
  `labels` equal by construction.
- Labels are grouped on `name.trim().lowercase()`; display `name` and
  `colorKey` come from the group's most recent annotation by `(createdAt, id)`.
- `labels` is sorted by `count` descending, then display name case-insensitively
  ascending, then grouping key ascending. Fully deterministic, because a strip
  that reorders two equal-count labels between refreshes reads as a glitch.
- `sharePercent` is `((count * 100.0) / labelledNoteCount).roundToInt()`, half-up.
  Written as a double deliberately: `count * 100 / labelledNoteCount` in Kotlin
  is integer division and truncates, so 1 of 6 would read 16 where the bar next
  to it is drawn at 17. The values are not adjusted to sum to 100 in either
  direction: counts of 3 and 3 out of 7 show 43 and 43, and counts of 1, 1 and 4
  out of 6 show 17, 17 and 67.
- `topRally` is the clip with the most labelled annotations, ties broken by
  lower `rallyIndex`. It is `null` when nothing is labelled, and never names a
  clip with zero labels.
- `buildMatchLabelSummary(emptyList(), emptyList())` returns a summary with
  count 0, no labels and a null `topRally`. Empty is a value, not a null.

The function takes `clips` already filtered to one video. It does not know
about `videoId`, which keeps it testable from a handful of constructed rows.

Placing this in `commonMain` breaks with `matchTitle`, `clipRowTitle` and
`matchRowPrimary`, which are hand-ported into Swift in `MatchGrouping.swift`.
That precedent holds for three-line formatters where a port is cheaper than a
bridge. It does not hold here: the rules above are seven behaviours with
tiebreaks, and two implementations of them would drift silently, since nothing
compares Android's strip against iOS's.

Swift sees the function as `MatchLabelSummaryKt.buildMatchLabelSummary(clips:annotations:)`
and the three types directly. No `SwiftInterop` wrapper is needed for it: the
signature is lists in, one value out, with no `kotlin.Result` and no `Map` or
`Pair` in sight.

## Repository

`AnnotationsRepository` gains one method:

```kotlin
/** Annotations for many clips at once, for match-level rollups. */
suspend fun listForClips(clipIds: List<String>): Result<List<RallyAnnotation>>
```

Named for what it filters on. `rally_annotations` has no `video_id`; the link
to a video runs through `rally_clips`, and every caller here already holds the
clip ids from `ClipListState.clips` or the iOS `clips` array, so there is no
lookup round trip to hide behind a `listForVideo` name.

Implementation: `clipIds.chunked(100)`, one select per chunk with
`filter { isIn("clip_id", chunk) }`, results flattened. No repository here uses
`isIn` today, so it was checked rather than assumed:
`PostgrestFilterBuilder.isIn(column: String, values: List<Any>)` is present in
supabase-kt 3.5.0, the version pinned in `libs.versions.toml`. The chunking is a guard
against URL length on a long match, not a normal path. An empty `clipIds`
returns `Result.success(emptyList())` without issuing a request, since an empty
`in.()` is both pointless and malformed.

No ordering is requested. The aggregation groups and sorts, and rows arriving
unordered cannot change its output, since every tiebreak is on a field the row
carries.

RLS needs no change. `20260506000000_match_shares.sql:56` already grants SELECT
on `rally_annotations` to owners and share recipients, scoped through
`rally_clips`, which is what the clip detail screen has been reading all along.
A recipient therefore gets a tally over every note on the match, their own and
the sharer's, combined. That is the right default for a shared review, and
splitting by author would need `owner_id` semantics this screen does not
otherwise carry.

`SwiftInterop.kt` gains the soft-failing wrapper, following
`listMatchMetadataOrNull`:

```kotlin
/** Soft-failing read: nil means "show no summary", never an error banner. */
suspend fun AnnotationsRepository.listForClipsOrNull(clipIds: List<String>): List<RallyAnnotation>? =
    listForClips(clipIds).getOrNull()
```

`androidApp/src/test/java/com/badmintontracker/android/testing/FakeAnnotationsRepository.kt`
implements the interface and gains the method too.

## Android wiring

`MatchClipsScreen` already receives its own route-scoped `ClipListViewModel`
(`AuthGate.kt:158-173`). A second view model joins it rather than growing the
first, which is already doing list, share, delete and metadata duty:

```kotlin
class MatchSummaryViewModel(
    private val clips: ClipsRepository,
    private val annotations: AnnotationsRepository,
    private val videoId: String,
) : ViewModel() {
    val summary: StateFlow<MatchLabelSummary?>
    fun refresh()
}
```

It observes `clips.observeClips()` itself, filtered to `videoId`, so it does not
depend on the list view model's state shape. It fetches when the set of clip
ids changes, and on an explicit `refresh()`.

`null` is "nothing to show yet", distinct from an empty summary. The strip is
hidden for both, so a match with no labels and a match still loading look the
same, which is deliberate: a skeleton that resolves to nothing is worse than a
row that was never there.

Two triggers keep it current. Pull-to-refresh calls `vm.refresh()` alongside the
list refresh. `LifecycleResumeEffect` calls it on resume, which covers the case
that matters most in practice: adding a note in `ClipDetail` and coming back.
`refresh()` is a no-op while a load is already in flight, since the resume
effect and the first clip emission both fire on the same screen entry and
restarting a healthy fetch would only delay the strip.

A clip set that empties, from a delete or a leave-share, drops the summary back
to `null` rather than leaving a rollup of clips that no longer exist.

Failure leaves the previous summary in place and surfaces nothing, the same
contract the metadata and shares lookups already use in `ClipListViewModel`.

New composables in `androidApp/src/main/java/com/badmintontracker/android/cliplist/`:

- `MatchLabelStrip` renders as the first `item` of the existing `LazyColumn`,
  keyed `match-label-summary`, above the description item, with a
  `HorizontalDivider` under it, so it scrolls with the list exactly as the
  description does. Content: up to two chips in `labels` order, each name cut by
  `stripLabelName`, then a `+N` overflow chip when there are more, then a
  trailing chevron. The whole row is one clickable target with a content
  description naming the total.

  Two rather than three is a width decision, not a taste one: three 16-character
  pills with their counts leave no room for the chevron at 360dp, and Compose
  cannot drop a chip on measurement without either an experimental `FlowRow`
  overflow API or a scrolling row that would fight the row's own tap target.
- `LabelCountChip` composes the existing `clipdetail.LabelBadge` for the pill
  and puts the count beside it in `labelSmall` / `onSurfaceVariant`.
  `LabelBadge` is not touched: it carries an invariant comment about blank
  names, and folding a count into `name` would make that pill lie about what it
  renders.
- `MatchSummarySheet`, a `ModalBottomSheet`: the headline
  `"31 labelled notes"`, then every label as pill, count, percentage and a bar
  whose width is `sharePercent` so the bar and the number cannot disagree, then
  a footer row `"Most labelled · <rally name> · 6 labels"` which dismisses the
  sheet and calls `onClipClick`.

The rally name in that footer comes from the existing
`clipRowTitle(clip, matchTitle)`, resolved by looking `topRally.clipId` up in
the clips the screen already has. A named rally reads by its name here, the same
as in the row below it.

## iOS wiring

The mirror, in `iosApp/Sources/ClipList/`.

`MatchClipsView` gains `@State private var summary: MatchLabelSummary?` and a
single `refreshSummary()` that fetches through `listForClipsOrNull` and calls
`buildMatchLabelSummary`.

It is driven from three places, and the third is the one worth being explicit
about. `.task(id: clipIds)` covers the first load and a changed clip set.
`.refreshable` covers pull to refresh, alongside `rally.clips.refresh()`. And
`.onAppear` covers returning from `ClipDetailView`, which neither of the others
does: pushing a detail view does not remove `MatchClipsView` from the
hierarchy, so its `.task` is not cancelled and restarted on pop, and adding a
note does not change the clip id set, so the keyed task would not re-fire even
if it were. Without the `.onAppear` trigger iOS would silently lack the refresh
that Android's `LifecycleResumeEffect` provides, and no unit test on either
side would show it.

`refreshSummary()` guards against a second concurrent fetch, since `.onAppear`
and `.task` both fire on the first appearance.

The existing metadata `.task` in this view has the same reappearance property.
It is not changed here: match title and description cannot change while the
user is inside a rally, so a stale read costs nothing.

`MatchLabelStripView` is the first row of the `List`, above the description row,
with the same two-chips-plus-overflow-plus-chevron content and the same single
tap target. `MatchSummarySheet` presents with
`.presentationDetents([.medium, .large])`, following `AddAnnotationSheet`'s use
of detents while letting a match with many labels be dragged open.

The sheet's "most labelled" row pushes a clip programmatically, which this view
has no mechanism for today: its rows are `NavigationLink`s. It gets one, a
`navigationDestination(item:)` over a small `Identifiable` id wrapper.

The push cannot happen in the row's own action. A `navigationDestination` on the
view that is currently presenting a sheet drops pushes made inside the dismissal
transaction, and this is the one interactive path in the feature that no test
exercises. So the row records the clip id, the sheet dismisses, and the parent's
`onDismiss` performs the navigation once the sheet is fully closed.

The chip reuses `Components/LabelBadge.swift` unchanged, for the same reason
Android's is left alone.

## Error handling

Every failure path here is silent by design. The summary is an aid to review,
not a record, and the rally list underneath it is fully usable without it. A
banner or retry button would interrupt the screen for something the user can
recover by pulling to refresh.

Concretely: a failed fetch leaves the last good summary on screen, or no strip
at all if there never was one. There is no error state in
`MatchLabelSummary`, no `error` field on the view model, and no snackbar.

## Testing

`shared/commonTest/.../model/MatchLabelSummaryTest.kt`, one test per contract
rule above:

- Body-only annotations are excluded from the count and from `labels`.
- `"Good shot"`, `"good shot"` and `" Good Shot "` tally as one label.
- The display name and colour come from the most recent annotation, with the
  `id` tiebreak exercised on two annotations sharing a `created_at`.
- Ordering: count descending, then name, then key, on a set constructed to make
  every tiebreak fire.
- `sharePercent` rounds half-up, and neither a set summing to 99 nor one
  summing to 101 is adjusted.
- `topRally` picks the most labelled clip and breaks a tie on the lower rally
  index; it is null when nothing is labelled.
- An annotation whose `clipId` is not among `clips` is ignored, and
  `labelledNoteCount` still equals the sum of `labels`.
- Empty input yields an empty summary, not a null.
- `stripLabelName` leaves a short name untouched and cuts a long one.

`shared/commonTest/.../repo/AnnotationsRepositoryTest.kt`, extending the
existing URL-capturing pattern:

- `listForClips` requests `rally_annotations` with `clip_id=in.(c1,c2)`.
- An empty id list issues no request at all.
- 150 ids issue two requests and the results are merged.

`androidApp/test`: `MatchSummaryViewModelTest` for refetch on clip-set change,
and for a failed fetch leaving the previous summary untouched.

`iosApp/Tests`: the aggregation is shared, so the iOS-only logic is the top
rally's display name. One case in `MatchGroupingTests` covering
`clipRowTitle` resolution from a `TopRally.clipId`.

Beyond unit tests, three runs on a real signed-in session:

Owner path. A match with a mix of labelled notes, body-only notes and at least
one renamed label. Confirm the strip's total counts only labelled notes, that
the renamed label appears once, and that the top rally link lands on the right
clip.

Return path, on both platforms. Open a rally from the list, add a labelled note,
go back, and confirm the strip's total has gone up without a pull to refresh.
This is the case the Android resume effect and the iOS `.onAppear` trigger exist
for, and it is invisible to every unit test in this design.

Shared path. The same match viewed by a recipient account, confirming the tally
is combined across authors. This is the run that exercises the RLS assumption
the design rests on. If no second account is available, record it here as a
known unverified edge rather than dropping it quietly.

**Not performed as of 2026-08-26, and recorded here as that known unverified
edge.** No second account was available during implementation. What rests on it:
the design changed no policy, on the reading that `annotations: select own or
shared` (`20260506000000_match_shares.sql:56`) already lets a recipient read every
annotation on a shared match's clips. If that reading is wrong, a recipient sees
an empty or partial strip; nothing crashes and no owner sees anything different.

Also not performed: the on-device and simulator visual checks. Both platforms
build and their suites pass, but no one has yet looked at the strip or the sheet
on a screen, and the sheet-to-push handoff in particular has no automated
coverage.

Empty path. A match with notes but no labels, confirming no strip renders and
the description still sits at the top of the list.

Signing in on the iOS simulator requires the user, since the session is
Keychain only.
