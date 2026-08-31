# Design: which labels belong on the scoreboard

**Date:** 2026-08-30
**Status:** Proposal, pending approval
**Follows:** `2026-08-24-custom-annotation-labels-design.md` (this reopens one
line from its "Out" list), `2026-08-27-live-scoring-surface-plan.md`

The board works. Scoring a rally is one tap on the half that won it, and the
label row underneath is already on screen before the rally it will tag, which is
what makes tagging a second tap rather than a dialog.

What does not work is reaching the label. Every label the account owns is drawn
into one horizontally scrolling row, and the `Note` button sits at the end of
that same row. Past four or five labels, both the label you want and the `Note`
button are off the right edge, and finding either costs a scroll - courtside,
between rallies, which is exactly where there is no time for one.

Two things are wrong and they are separable. The row scrolls because it holds
labels that have no business being on a board (a label made for reviewing clips
is still offered every rally), and `Note` is unreachable because it was put
inside the scrolling region rather than beside it. This document fixes both: a
per-label scope so the board draws a chosen subset, and a wrapping container so
that subset is visible at once with `Note` anchored outside it.

A scope has to be chosen somewhere, and that pulls in a third change. iOS can
currently create a label from inside a note picker, where there is no room to
ask about scope and no reason the question would make sense; Android cannot.
That path is removed (§8.3), leaving the Labels screen as the single place a
label is made - with its name, colour and scope all chosen together.

---

## 1. What exists today

Verified by reading the code, not assumed. File references are the evidence.

### 1.1 The board's label row

- `ScoringScreen.kt:468` `ControlBar` draws `state.labels` into a `Row` carrying
  `Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())`
  (`ScoringScreen.kt:488`). The `Note` `TextButton` is the last child *inside*
  that row (`ScoringScreen.kt:507`), so it scrolls with the chips.
- `ScoringView.swift:349` `controlBar` is the same shape: a
  `ScrollView(.horizontal, showsIndicators: false)`
  (`ScoringView.swift:353`) whose final element is `Button("Note")`
  (`ScoringView.swift:371`).
- Both surfaces already render a placeholder when the palette is empty
  (`ScoringScreen.kt:500`, `ScoringView.swift:366`) with the same sentence:
  "No labels yet - add them on the labels screen."
- Chips are deliberately drawn greyed-and-inert rather than hidden until a rally
  exists to tag (`ScoringScreen.kt:562`, `ScoringView.swift:416`), so the row
  occupies its space before the first point. That property must survive.

### 1.2 The palette, and everything that reads it

- `annotation_labels` (`supabase/migrations/20260824000000_annotation_labels.sql`)
  is `id, owner_id, name, color_key, created_at`. There is no notion of where a
  label may be used. A signup trigger seeds three rows; a backfill gave the same
  three to accounts that already existed.
- `AnnotationLabel` (`shared/.../model/AnnotationLabel.kt`) is a four-field
  `@Serializable` data class. It stores `colorKey` as a raw string and derives
  `color: LabelColor?` from it, so a swatch key this build does not know renders
  neutral instead of throwing. That pattern is reused below.
- `AnnotationLabelsRepository` (`shared/.../repo/AnnotationLabelsRepository.kt`)
  exposes one `labels: StateFlow<List<AnnotationLabel>>` plus `refresh`,
  `create`, `rename`, `recolor`, `delete`. It is local-first: the flow is seeded
  from an owner-stamped `Settings` cache so the picker survives a cold start in
  a sports hall with no signal.

Eight surfaces read that single flow:

| Surface | Reads | Role |
| --- | --- | --- |
| `ScoringViewModel.kt:60` | `labels.labels` | the board |
| `ScoringModel.swift:60` | `labelsRepository.labels` | the board |
| `ClipDetailViewModel.kt:55` | `labels.labels` | cloud clip notes |
| `ClipDetailModel.swift:72` | `rally.labels.labels` | cloud clip notes |
| `LocalPlayerViewModel.kt:30` | `labels.labels` | local video notes |
| `LocalPlayerModel.swift:42` | `rally.labels.labels` | local video notes |
| `LabelsViewModel.kt:48` | `labels.labels` | management |
| `LabelsModel.swift:23` | `rally.labels.labels` | management |

Six of those eight want a subset. Two want everything.

### 1.3 Two ways to make a label on iOS, one on Android

`AddAnnotationSheet` renders a "+ New label" button whenever `canCreateLabel` is
true (`AddAnnotationSheet.swift:25`), and both of its call sites pass `true`
unconditionally:

- `ClipDetailView.swift:87` presents it for a cloud clip, wired to
  `ClipDetailModel.createLabel` (`ClipDetailModel.swift:84`).
- `LocalPlayerView.swift:141` presents it for a local video, wired to
  `LocalPlayerModel.createLabel` (`LocalPlayerModel.swift:54`).

Both call the name-only overload `createLabelForSwift(name)`
(`SwiftInterop.kt:71`), which delegates to `createLabelForSwift(name, color)`
(`SwiftInterop.kt:80`) with a null colour so the repository picks the next
unused swatch. The sheet then auto-selects the label as it arrives back through
the `labels` list (`AddAnnotationSheet.swift:63`).

**Android has no inline creation at all** - `ClipDetailViewModel` and
`LocalPlayerViewModel` contain no call to `create`. Labels are made only on the
Labels screen there.

The owner's intent is Android's shape: **a label is made on the Labels screen
and nowhere else.** §8.3 removes the iOS affordance as part of this change,
which is what lets §5 drop a scope-guessing decision it would otherwise have to
make (see §6).

### 1.4 What the prior design deliberately left out

`2026-08-24-custom-annotation-labels-design.md` listed "Filtering or statistics
by label" and "Manual reordering" under Out. Nothing speculative was built for
either, so there is no half-finished scoping mechanism to reconcile with. This
is an addition to a clean surface, not a reversal.

---

## 2. The fact the design turns on

**A label is never referenced by a tag. It is copied into one.**

- `rally_annotations` carries `label_name` and `label_color` as columns on the
  annotation row, not a foreign key. That was the 2026-08-24 decision, made so a
  share recipient sees a badge without owning the label.
- `PointTag(labelName, labelColor)` on a score event is the same snapshot.
- `ScoreTagSummary.summarise` builds its `LabelRef`s from `point.tags`
  (`ScoreTagSummary.kt:33`), and `buildMatchLabelSummary` rolls up from
  annotation rows. **Neither reads the live palette.** Grepped and confirmed.

So a scope column is presentation-only, by construction. It changes which chips
a picker offers from here on and can never invalidate, hide, or re-colour a tag
already applied. `RalliesFacet`, `PointsFacet`, `MatchSummaryView` and the match
label summary are all downstream of snapshots and are untouched by this change.

This is what makes the whole thing safe enough to ship without a migration of
existing data beyond a column default.

---

## 3. The two decisions, and what they rule out

Both were put to the owner as a choice; both answers are recorded here because
the rejected options are the ones a later reader will wonder about.

### 3.1 One setting with three values, not two booleans

A label's scope is a single value: `both` (the default), `scoreboard`, or
`clips`.

Two independent booleans (`on_scoreboard`, `on_clips`) carry the same
information but admit a fourth state - neither - which is a label that exists
and appears nowhere. That state has no meaning, and defending against it means
either a CHECK that forbids it, a guard in the UI, or living with labels that
silently do nothing. Three values make it unrepresentable.

A strict either/or - a label is a board label *or* a clip label, never both -
was also rejected. "Good shot" is wanted in both places, and forcing a duplicate
row to say so would reintroduce the name collisions the unique index on
`lower(name)` exists to prevent.

### 3.2 The board wraps and shrinks; there is no cap

When more labels are scoped to the board than fit one line, they wrap onto
further lines and the board gives up that height. There is no maximum and there
is never a scroll.

The alternative was a hard cap (say six) that would keep the board's height
identical in every match. It was rejected because it trades a rule you run into
for a cost you can already see: with wrapping, the price of scoping a ninth
label to the board is visible the moment you do it, on the board itself, and the
remedy is the same control that caused it. A cap needs an error message, a
number chosen for reasons that will not hold on every screen size, and a
Labels screen that starts refusing edits.

Wrapping also preserves the property from §1.1 that the row occupies its space
before the first point is scored: the container's height is a function of the
scoped label set, not of whether a rally has been played.

---

## 4. Data and shared model

### 4.1 The column

`supabase/migrations/20260830000000_label_usage.sql`:

```sql
alter table public.annotation_labels
    add column if not exists usage text not null default 'both';

alter table public.annotation_labels
    drop constraint if exists annotation_labels_usage_check,
    add constraint annotation_labels_usage_check
        check (usage in ('both','scoreboard','clips'));
```

The constraint is added separately from the column so a re-run over a database
that already has the column still converges, matching the `drop ... if exists`
then `add` shape the 2026-08-24 migration already uses for its own constraints.

`default 'both'` is the entire upgrade story. Every existing label - the three
seeded ones and anything the owner has made since - keeps appearing in every
picker, on both platforms, with no backfill statement and no client-side
migration.

`seed_annotation_labels()` needs no change. Its `insert` names
`(owner_id, name, color_key)` and does not mention `usage`, so the column
default applies and newly signed-up accounts get three `both` labels, which is
what they get today.

### 4.2 `LabelUsage`

New file `shared/.../model/LabelUsage.kt`:

```kotlin
enum class LabelUsage(val key: String) {
    BOTH("both"),
    SCOREBOARD("scoreboard"),
    CLIPS("clips"),
    ;

    val onScoreboard: Boolean get() = this != CLIPS
    val onClips: Boolean get() = this != SCOREBOARD

    companion object {
        fun from(key: String?): LabelUsage? = entries.firstOrNull { it.key == key }
    }
}
```

`onScoreboard` and `onClips` are defined as negations rather than as a
whitelist so that `BOTH` cannot drift out of either set when a value is added
later. There is exactly one place each predicate is written down.

### 4.3 `AnnotationLabel`

```kotlin
@Serializable
data class AnnotationLabel(
    val id: String,
    val name: String,
    @SerialName("color_key")  val colorKey: String,
    @SerialName("created_at") val createdAt: Instant,
    val usage: String = LabelUsage.BOTH.key,
) {
    val color: LabelColor? get() = LabelColor.from(colorKey)
    /** Unknown or absent scope reads as [LabelUsage.BOTH] - see below. */
    val scope: LabelUsage get() = LabelUsage.from(usage) ?: LabelUsage.BOTH
}
```

The raw string is the serialized field and the enum is derived, mirroring
`colorKey`/`color` exactly. A `@Serializable enum` would be terser but would
throw on a value this build does not know, and that failure is not local: the
list is decoded with `decodeList<AnnotationLabel>()`, so one unrecognised scope
takes down the entire palette rather than one row.

The fallback direction is deliberate. An unknown scope resolves to `BOTH`, so a
label written by a newer build shows up in both pickers. The failure mode is a
chip you did not expect, not a chip that has vanished with no way to find it.

The default on the property covers a JSON payload with no `usage` key at all -
the on-disk `Settings` cache written by the current build, read by the next one.
Without it, the first launch after upgrade would fail to decode its own cache
and show an empty picker offline.

Naming: the stored property is `usage` (matching the column, so no `@SerialName`
is needed) and the derived enum is `scope`. `usage`/`usageKey` was considered and
rejected - `colorKey`/`color` puts the suffix on the raw side, but here the raw
side is what the column is called, and renaming the wire field to keep a
suffix convention would cost a `@SerialName` for no gain.

---

## 5. Repository

### 5.1 Two derived flows and one mutator

`AnnotationLabelsRepository` gains:

```kotlin
/** The subset the courtside board may tag a rally with. */
val scoreboardLabels: StateFlow<List<AnnotationLabel>>
/** The subset the clip and local-video note pickers offer. */
val clipLabels: StateFlow<List<AnnotationLabel>>

suspend fun setUsage(id: String, usage: LabelUsage): Result<Unit>
```

The filtering lives here, not at the call sites. Six of the eight consumers in
§1.2 want a subset, and a predicate exported for each of them to apply is six
chances for one surface to disagree with another about what "on the board"
means. Two flows means the question is answered once.

In `AnnotationLabelsRepositoryImpl` both are `labels.map { ... }.stateIn(scope,
SharingStarted.Eagerly, ...)` on the scope the class already owns. That scope's
doc comment already explains why a never-cancelled coroutine is acceptable on a
process-lifetime singleton; two more eagerly-started derivations of an in-memory
list do not change that argument. `Eagerly` rather than `WhileSubscribed` for
the same reason the board's own state is eager: the value must be correct the
instant a screen reads it, and there is nothing to defer.

`setUsage` follows `recolor` line for line - a `UsagePatch` payload, a filtered
update, then `publish` so the in-memory flow and the on-disk cache move
together. It is a separate method rather than a parameter on `rename` because
the Labels screen writes the two independently.

### 5.2 `create` gains a scope

```kotlin
suspend fun create(name: String, color: LabelColor?, usage: LabelUsage): Result<AnnotationLabel>
```

Not defaulted. A default would bridge to Swift as a missing parameter (Kotlin
default arguments do not cross the ObjC interop boundary), so the Swift call
path would have to name it anyway; making it explicit in Kotlin keeps the two
platforms reading the same way.

After §8.3 there are exactly two callers - `LabelsViewModel.create` and
`LabelsModel.create` - and both are the Labels screen's draft row, which now
carries a scope control of its own. So the parameter is always a value the
owner chose, never one the code guessed.

### 5.3 Rewiring the eight consumers

| Surface | Now reads |
| --- | --- |
| `ScoringViewModel`, `ScoringModel` | `scoreboardLabels` |
| `ClipDetailViewModel`, `ClipDetailModel` | `clipLabels` |
| `LocalPlayerViewModel`, `LocalPlayerModel` | `clipLabels` |
| `LabelsViewModel`, `LabelsModel` | `labels`, unchanged |

`ScoringModel`'s test seam needs one honest rename. Today it takes
`labels: [AnnotationLabel]` because Swift cannot cheaply stand in for a Kotlin
interface, and the tests hand it a fixed palette. That parameter becomes
`scoreboardLabels:`, because after this change the array a test passes *is* the
already-scoped set - the filtering is the repository's job and is asserted in
`commonTest` against the repository, not re-asserted through a view model that
never performs it.

### 5.4 The Swift bridge

`SwiftInterop.kt` changes in three places:

- `createLabelForSwift(name)` (`:71`) - the name-only overload - is **deleted**.
  Its only two callers are the inline-creation paths §8.3 removes, and it exists
  solely to let a caller create a label without choosing a colour, which after
  §8.3 nothing does.
- `createLabelForSwift(name, color)` (`:80`) gains a `usage` parameter and
  forwards it. Its one remaining caller is `LabelsModel.create`
  (`LabelsModel.swift:55`).
- New `setLabelUsageOrMessage(id, usage): String?`, following
  `recolorLabelOrMessage` (`:89`) including its soft-failing shape.

`CreateLabelOutcome` stays as it is: the Labels screen still needs to tell a
rejected duplicate name from a created row, which is the whole reason that type
exists.

An earlier draft of this design had the name-only overload pass
`usage = LabelUsage.CLIPS`, so that a label born in a clip picker did not land
on the board. Deleting the overload removes the question rather than answering
it - see §6.

---

## 6. Decisions

| Question | Choice | Why |
| --- | --- | --- |
| Scope model | One `usage` value: `both` / `scoreboard` / `clips` | Two booleans admit a meaningless fourth state; strict either/or forces duplicate rows for "Good shot". |
| Default for existing labels | `both`, via the column default | No backfill, no client migration, nothing disappears on upgrade. |
| Board overflow | Wrap onto further lines, board shrinks | The cost of a ninth board label is visible where you caused it. A cap needs an arbitrary number and starts refusing edits. |
| Unknown scope value | Falls back to `BOTH` | A surprise chip beats a chip that has silently vanished. Decoding the raw string also stops one bad row from taking down the whole list. |
| Where filtering lives | Two derived flows on the repository | Six consumers want a subset; a shared predicate is six chances to disagree. |
| Inline creation from a clip picker | Removed from iOS (§8.3) | It only ever existed on iOS. Keeping it would force this design to guess a scope for a label the owner created without being shown the control; removing it means every label is made in one place, with its scope chosen there. |
| Where `Note` lives | The fixed caption row, outside the label container | It is unreachable today purely because it was placed inside a scrolling region. |
| Scope on the collapsed Labels row | A `BOARD` / `CLIPS` caption on rows that are not `both` | The split is unreadable if it can only be seen by opening every row one at a time. |
| Reordering board labels | Not in this pass | Still out, as in 2026-08-24. Creation order is the order. |

---

## 7. The board

Both `ControlBar` (`ScoringScreen.kt:468`) and `controlBar`
(`ScoringView.swift:349`) change in the same two ways.

### 7.1 The label container wraps

The scrolling row becomes a wrapping container. Neither platform needs new
layout machinery:

- **Android**: `FlowRow`, already used at `AnnotationUi.kt:117` and
  `PlaybackControlBar.kt:119`.
- **iOS**: `ChipFlow: Layout`, which already exists at
  `PlaybackControlBar.swift:180` with a doc comment saying it exists to do what
  Android's `FlowRow` does. It is `private` to that file today, so it moves to
  `iosApp/Sources/Components/ChipFlow.swift` and becomes internal, unchanged
  otherwise.

That move touches a file this change is not otherwise concerned with. It is
called out here so it does not read as an unexplained diff: the alternative is a
second copy of a wrapping layout in the same target, which is worse.

### 7.2 `Note` leaves the label area

`Note` moves out of the label container and into the fixed caption row beneath
it, which today holds `Undo`, the "Point 18: 11-7" caption, and the conditional
`Done`:

```
+--------------------------------------+
|                                      |
|        11              7             |
|                                      |
+--------------------------------------+
| Good shot   Forced error             |
| Unforced    Net        Smash         |
+--------------------------------------+
| Undo   Point 18: 11-7   [Note] [Done]|
+--------------------------------------+
```

That row never scrolls and never reflows, so `Note` is in the same place at the
same size for the whole match. It keeps its existing `enabled`/`disabled`
binding on whether a rally exists to annotate.

### 7.3 The empty state learns to tell two situations apart

One sentence covers two different problems today. It becomes two:

- No labels on the account at all: "No labels yet - add them on the labels
  screen." Unchanged.
- Labels exist, none scoped to the board: "No board labels - choose them on the
  labels screen."

The second is reachable the moment someone scopes their last board label to
clips, and without it the board would claim the account has no labels while the
Labels screen plainly shows several.

---

## 8. The Labels screen

### 8.1 The control

A three-way segmented control labelled **Use**, with options **Both**,
**Scoreboard**, **Clips**. It sits in the shared in-place editor between the
name field and the swatch grid: `LabelEditorFields` on Android
(`LabelsScreen.kt`), `LabelEditor` on iOS (`LabelsView.swift`). Both are already
shared by the expanded existing row and the not-yet-created draft row, so the
control is written once per platform and both callers get it.

The wiring differs exactly the way colour already does, and for the same reason:

- **An expanded existing row** writes through immediately on selection, via
  `setUsage`. There is an id to write against.
- **The draft row** holds the choice locally and submits it with the name and
  colour in the single `create` call. There is no id yet.

The draft row defaults to `BOTH`, matching the column default, so creating a
label on the Labels screen and ignoring the control behaves exactly as it does
today.

### 8.2 The collapsed row

A collapsed row is a colour dot and a name. It gains a small trailing caption -
`BOARD` or `CLIPS` - on rows whose scope is not `both`. Rows that are `both`
get nothing, so the common case stays as quiet as it is now and the caption
reads as an exception rather than as a column.

Without this the split is invisible from the list, and answering "which labels
are on my board?" would mean opening every row in turn.

### 8.3 This screen becomes the only way to make a label

iOS loses the "+ New label" affordance in the Add-note sheet (§1.3), leaving one
creation path per platform and the same one on both.

This is not tidiness. Scope is now a property every label has, and the Labels
screen is the only surface that shows the control for it. A label created from a
note picker would be created without its owner ever seeing that control, so the
code would have to pick a scope on their behalf - and whichever it picked would
be wrong for someone. Removing the second path removes the guess.

Deleted:

| File | What goes |
| --- | --- |
| `AddAnnotationSheet.swift` | `canCreateLabel` and `onCreateLabel` parameters; the `creating` / `newName` state and the `TextField` they gate; the `seenIds` state and the `.task` / `.onChange(of: labels)` pair (`:62`-`:69`) that auto-selected a just-created label |
| `ClipDetailView.swift:89` | the `canCreateLabel:` / `onCreateLabel:` arguments |
| `LocalPlayerView.swift:143` | the same two arguments |
| `ClipDetailModel.swift:84` | `createLabel(_:)` |
| `LocalPlayerModel.swift:54` | `createLabel(_:)` |
| `SwiftInterop.kt:71` | the name-only `createLabelForSwift` overload (§5.4) |

The sheet keeps everything else: it still lists `clipLabels` as selectable
chips, still allows a note with no label, and still requires either a label or a
body before `Add` enables.

No test references any of the deleted symbols (grepped across `iosApp/Tests` and
`iosApp/UITests`), so this removes code without removing coverage.

The empty case gets slightly worse and that is accepted: someone with no clip
labels who opens the Add-note sheet now has to leave for the Labels screen
rather than typing a name in place. That is exactly what an Android user does
today, and it is the trade for having one place where a label's name, colour and
scope are all chosen together.

---

## 9. Deliberately not in this pass

- **A cap on board labels.** Decided against in §3.2.
- **Reordering.** Creation order remains the order, on the board and in the
  list. Still out, as in 2026-08-24.
- **Adding inline creation to Android.** The §1.3 asymmetry is closed by
  removing the iOS path (§8.3), not by building a second one. Nothing here
  argues that in-picker creation is a good idea worth having twice.
- **An empty-state shortcut to the Labels screen** from the Add-note sheet. §8.3
  accepts the extra navigation as-is. If it turns out to bite, a "Manage labels"
  link in that sheet is a small, separable follow-up.
- **Per-match or per-video label sets.** Scope is a property of the label, owned
  by the account, not a per-context override. Nothing in the request needs more.
- **Filtering or statistics by scope.** Still out.
- **Anything downstream of a tag.** `ScoreTagSummary`, `MatchLabelSummary`,
  `RalliesFacet`, `PointsFacet` and `MatchSummaryView` read snapshots and are
  untouched (§2).

---

## 10. How this is verified

### 10.1 Shared, `commonTest`

- `AnnotationLabelSerializationTest`: `usage` round-trips; a payload with no
  `usage` key decodes to `BOTH`; a payload with an unrecognised value decodes to
  `BOTH` **and the surrounding list still decodes**, which is the property the
  raw-string choice in §4.3 exists to buy.
- New `LabelUsageTest`: `onScoreboard` and `onClips` partition the three values
  as documented.
- `AnnotationLabelsRepositoryTest`: `scoreboardLabels` and `clipLabels` each
  contain `both` plus their own value and exclude the other; `setUsage` moves a
  label between the two flows and persists through the `Settings` cache; a
  cold start reads scope back out of that cache.

### 10.2 Android

- `ScoringViewModelTest`: the board's palette excludes a `clips`-scoped label
  and includes `both` and `scoreboard` ones.
- `LabelsViewModelTest`: the screen still sees all three scopes; `setUsage`
  surfaces a failure through the same snackbar path as `rename`.
- `ClipDetailViewModelTest` / `LocalPlayerViewModelTest`: the note picker
  excludes a `scoreboard`-scoped label.
- `FakeAnnotationLabelsRepository` gains the two flows and `setUsage`, deriving
  them from its own backing state so the fake cannot answer differently from the
  real one.

### 10.3 iOS

- `ScoringModelTests`: the one `AnnotationLabel(...)` construction site
  (`ScoringModelTests.swift:14`) gains the new argument - Kotlin defaults do not
  bridge, and this is the whole cost of that on the Swift side.
- `LabelsLogicTests`: unchanged; expansion state is untouched by this change.
- No test changes are needed for the §8.3 deletions - nothing in `iosApp/Tests`
  or `iosApp/UITests` references `createLabel`, `canCreateLabel` or
  `AddAnnotationSheet`. The build itself is the check: `ClipDetailView` and
  `LocalPlayerView` fail to compile if either argument survives the sheet's
  changed signature.

### 10.4 On device

Both simulators, per the flow that produced the complaint:

1. Scope two of three labels to `Scoreboard`, one to `Clips`.
2. Open the board. The two board labels are visible without scrolling, `Note` is
   on the fixed row, and the board still fills what is left.
3. Score a rally, tag it, open `Note`, type, score the next rally. Confirm the
   note commits on dismissal and undo takes back the rally rather than a letter.
4. Open a clip's Add-note sheet. The `Clips` label is offered; neither
   `Scoreboard` label is; **and on iOS there is no "+ New label" button** - on a
   cloud clip and on a local video, since §8.3 removes both.
5. Scope every label to `Clips`. The board shows "No board labels", not "No
   labels yet".
6. Check `RalliesFacet` on a match tagged before the change: badges unchanged,
   counts unchanged.
7. Make a label on the Labels screen with all three settings in one pass - name,
   colour, scope - and confirm it appears in exactly the pickers its scope names.
