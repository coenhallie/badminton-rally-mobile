# Design: Local video title and description

**Date:** 2026-08-25
**Status:** Draft, pending review

## Goal

Let a video picked up on the phone carry a user-supplied match name and
description from the moment it lands in the app, and have both survive the
analyze pipeline so they show on the match once it is back from the cloud.

The web app already does half of this. `VideoUpload.vue` collects an optional
match name, writes it to `videos.title` on insert, and Modal copies it into
`rally_clips.title`, which the mobile app renders in place of its
"Rally #{index}" fallback. The phone has no equivalent: `createVideo` inserts
`filename` and nothing else, so a video analyzed from the phone is nameless.

There is no `description` anywhere in the system yet, on either side.

## Scope

In:
- `title` and `description` on `LocalVideoEntry`, editable while the entry is
  still local.
- An edit sheet that opens automatically after import or recording, and stays
  reachable from the row's overflow menu.
- Both values written on the `videos` INSERT in `createVideo`.
- `videos.description` column and a `list_match_metadata` RPC.
- Match name and description shown on the match card and the match detail
  header, on both platforms.

Out:
- Renaming a match after analysis has started. See "Why editing stops at LOCAL".
- Backfilling descriptions onto matches uploaded before this ships.
- Any change to the web app or to the Modal processor.
- Per-rally descriptions. Description is match level only.
- Showing description on the local list row. It lives on the player screen
  until the match is analyzed.

## Decisions

| Question | Choice |
| --- | --- |
| Where does description live? | `videos.description`, one row per match. Not copied per clip. |
| How does the phone read it back? | `list_match_metadata()`, a security definer RPC returning three columns. |
| Why not widen `videos_owner_select`? | It would expose `storage_path`, `results_meta`, `error` and `player_labels` to share recipients. |
| Why not mirror the title pattern via Modal? | Denormalizes a match level field across every clip, splits the feature across two repos, and needs a Modal redeploy. |
| When is metadata editable? | While `stage == LOCAL` only. |
| When does the edit sheet appear? | Automatically after intake, skippable, plus on demand from the row menu. |
| Which repo owns the migration? | This one, date stamped, per the `match_shares` precedent. |
| Title length cap | 60, matching the web app's `MAX_TITLE_LENGTH`. |
| Description length cap | 500. |
| Blank input | Normalized to `null`, never `""`. |
| Fate of `VideoSummary` | Deleted. Dead code, and its shape does not fit the RPC. |

## Why `videos.description` rather than a per-clip copy

`title` is denormalized onto `rally_clips` for a reason that does not generalize:
it doubles as each rally's display name, so every clip genuinely needs its own
copy. A description has no per-rally meaning. Copying it onto twenty clips to
display it once is a modeling error that a later feature would have to undo.

The cost of keeping it normalized is that the phone must read the `videos` table,
which it does not do today. The match list is built entirely by grouping
`rally_clips` by `video_id`. That is what the RPC is for.

## Why an RPC rather than a policy

`videos_owner_select` is owner scoped and was never widened when sharing landed:
`20260506000000_match_shares.sql` extended SELECT on `rally_clips` and
`rally_annotations` to share recipients and left `videos` alone. So a recipient
of a shared match can read its clips but not its `videos` row.

Widening that policy would work, and would also hand recipients `storage_path`,
`results_meta`, `error`, `processed_video_path` and `player_labels`. A
`security definer` function returning exactly `(video_id, title, description)`
grants the three columns the feature needs and nothing else. The repo already
uses this shape for share scoped reads in `list_received_match_shares`,
`list_match_shares` and `leave_shared_match`.

## Why editing stops at LOCAL

`0008_video_title.sql` in the web repo deliberately omits
`grant update (title) on public.videos to authenticated`, because `0002` revokes
UPDATE and re-grants column by column. Title is insert only by design, and
description follows it.

That makes the edit window a data integrity rule rather than a UI convention. If
metadata could be edited after `CREATE_ROW` had run, the phone would show a name
the database does not have, with no way to reconcile it. The concrete case:
`CREATE_ROW` succeeds with title "A", `TRIGGER` fails, the user edits to "B" and
taps Re-analyze. `AnalyzeCoordinator.retry` resumes at `TRIGGER`, `createVideo`
never runs again, and the two diverge permanently.

Locking edits to `LOCAL` closes that off. `CREATE_ROW` only ever runs after the
entry has left `LOCAL`, so what is inserted is always what the user last saw.

The accepted cost: a `FAILED` entry cannot be renamed, including one that failed
at `UPLOAD` and therefore has no row yet. Lifting that later means adding the
UPDATE grants that `0008` withheld, and is a separate change.

## Database migration: `supabase/migrations/20260825000000_video_description.sql`

Written to converge from either starting point, exactly as `0008` was. That file
records that on the live project `videos.title` was added by hand in the SQL
Editor and carries no CHECK, while a fresh rebuild has both. The same divergence
must be assumed here, so the column and the constraint are added independently,
each guarded.

```sql
alter table public.videos
  add column if not exists description text;

do $$
begin
  if not exists (
    select 1 from pg_constraint
    where conrelid = 'public.videos'::regclass
      and conname = 'videos_description_length_check'
  ) then
    alter table public.videos
      add constraint videos_description_length_check
      check (description is null or length(description) between 1 and 500);
  end if;
end $$;
```

No `grant update (description) ... to authenticated`, deliberately, mirroring
`title`. The migration carries a comment saying so, and saying that a rename
after upload flow would make the grant mandatory. As `0008` notes, a
service_role smoke test will not catch its absence, because service_role has
BYPASSRLS.

The RPC:

```sql
create or replace function public.list_match_metadata()
returns table (video_id uuid, title text, description text)
language sql
security definer
set search_path = public
as $$
  select v.id, v.title, v.description
    from public.videos v
   where v.owner_id = auth.uid()
      or exists (
        select 1 from public.match_shares ms
         where ms.video_id = v.id
           and ms.shared_with_user_id = auth.uid()
      )
$$;

revoke all   on function public.list_match_metadata() from public;
grant execute on function public.list_match_metadata() to authenticated;
```

No arguments. The match list needs metadata for every match it can see, and the
payload is three text columns per row.

The `exists` clause is deliberately a structural copy of the `using` clause on
`clips: select own or shared` (`20260506000000_match_shares.sql:39-49`), which
is the authority on which matches a user can see. Verified identical: owner
check, then `match_shares` on `video_id` and `shared_with_user_id = auth.uid()`,
with no state column and no join through `videos`. If the two ever diverge, a
card's title would appear or vanish independently of the match itself, so they
must be changed together.

## Data model

`LocalVideoEntry` gains two fields, both defaulted:

```kotlin
val title: String? = null,
val description: String? = null,
```

The defaults are load bearing. `LocalVideoRepository.load()` is
`runCatching { json.decodeFromString(...) }.getOrNull() ?: emptyList()`, so a
field without a default would make every already persisted registry fail to
decode and silently return an empty list. `ignoreUnknownKeys = true` already
protects a downgrade; the defaults protect the upgrade.

New in commonMain, next to `canRemoveLocalVideo`:

```kotlin
fun canEditLocalVideoDetails(stage: AnalyzeStage): Boolean = stage == AnalyzeStage.LOCAL
```

`LocalVideoEntry.kt` already carries a comment requiring both platforms to share
the removal rule. The edit rule is shared for the same reason.

New `LocalVideoDetails` in commonMain, pure and unit tested:

```kotlin
fun normalizeTitle(raw: String): String?        // trim, cap 60,  blank -> null
fun normalizeDescription(raw: String): String?  // trim, cap 500, blank -> null
```

Over-long input is truncated, not rejected, matching the web app's
`matchTitle.trim().slice(0, MAX_TITLE_LENGTH) || null`. Order is fixed: trim,
truncate to the cap, trim the tail again so truncation cannot leave a trailing
space, then map empty to `null`. The input fields also cap typing at the same
lengths, so truncation is a guard against paste, not the normal path.

Blank becomes `null` and never `""`. `videos_title_length_check` rejects the
empty string, and a rejected insert surfaces to the user as a `CREATE_ROW`
pipeline failure with nothing actionable in it. Because the live project may
carry no CHECK at all, this normalization is the real guard, not the database.

New shared model, the RPC's return type:

```kotlin
@Serializable
data class MatchMetadata(
    @SerialName("video_id") val videoId: String,
    val title: String? = null,
    val description: String? = null,
)
```

`VideoSummary` and `VideoSummarySerializationTest` are deleted. `VideoSummary`
is referenced by nothing but its own test: the match list is built from
`rally_clips`, so the `videos` table has never been read on the phone. Its shape
(`id`, `filename`, `created_at`) does not fit the RPC, and keeping a dead model
next to a live one that does the same job invites picking the wrong one.

## Repository

`LocalVideoRepository` gains a single purpose extension beside
`acknowledgeResult`:

```kotlin
fun LocalVideoRepository.setDetails(id: String, title: String?, description: String?) =
    update(id) { it.copy(title = title, description = description) }
```

Swift cannot call Kotlin's `copy()`, which is why `acknowledgeResult` exists in
that file. This follows the pattern rather than making an exception to it.

`VideosRepository.createVideo` takes the two values:

```kotlin
suspend fun createVideo(
    videoId: String,
    filename: String,
    sizeBytes: Long,
    title: String?,
    description: String?,
): Result<Unit>
```

`NewVideoRow` gains both fields. `AnalyzeCoordinator`'s `CREATE_ROW` step passes
`entry.title` and `entry.description`. No other pipeline step touches metadata.

`VideosRepository` also gains:

```kotlin
suspend fun listMatchMetadata(): Result<List<MatchMetadata>>
```

implemented as `client.postgrest.rpc("list_match_metadata")`, following
`SharesRepository.listReceived`.

Both fakes update: `shared/src/commonTest/.../FakeVideosRepository.kt` and
`androidApp/src/test/.../FakeVideosRepository.kt`.

## Platform wiring

**Intake.** Both platforms persist the `LOCAL` entry first, then open the edit
sheet for it. Ordering matters: a dismissed sheet, a backgrounded app or a
crash must never cost the user the video they just recorded. On Android,
`ClipListScreen`'s `onAdded` callback sets the dialog target after
`localVideos.add`. On iOS, `LocalVideoIntake.add` surfaces the new entry id for
`ClipListView` to present on.

`LocalVideoIntake.swift` spells out all eleven `LocalVideoEntry` parameters, and
Kotlin default arguments do not survive into the generated Swift initializer, so
that call site must be updated to pass `title: nil, description: nil` or it
fails to compile. iOS never constructs an entry with metadata already on it: the
sheet writes through `setDetails` after the entry exists. Android is unaffected,
since `VideoIntake.addEntryFromUri` uses named arguments and gets the defaults.

`createVideo` has exactly one production caller, `AnalyzeCoordinator:133`, and
no `SwiftInterop` wrapper. The signature change reaches only that line, the
interface, the implementation and the two fakes.

**The sheet.** One component per platform, used for both entry points. Two
fields: "Match name", single line, 60 cap, live `n/60` counter, mirroring the
web field; and "Description", multi line, 500 cap, same counter treatment. The
dismiss action reads "Skip" when auto opened and "Cancel" when reached from the
menu. Save normalizes, then calls `setDetails`. Android reuses
`ShuttlOutlinedTextField` and `FieldLabel`; iOS follows the existing
`LabelsView` sheet.

**The row menu.** "Edit details" sits above "Remove from app". The menu renders
today only when `canRemove`; it becomes "render when either action applies",
with each item gated on its own rule.

**Before analysis.** The local row's primary text becomes
`title ?: displayName`. Both player screens use the same expression for their
nav title (`LocalPlayerScreen`'s `TopAppBar`, `LocalPlayerView`'s
`.navigationTitle`), and show the description in a block above the notes list
when it is set. The description stays off the list row, which already carries a
duration and date line plus a conditional status line.

**After analysis.** `MatchSummary` gains `title` and `description` on both
platforms. A match card with a title shows it as the primary line, with the date
demoted into the label line as `"MMM D · N RALLIES"`, and the description as a
third line, two lines maximum, ellipsized. `MatchClipsScreen`'s top bar and the
iOS equivalent show the title in place of `"MATCH · <date>"`, with the
description as a header row above the rally list. Without a title, every one of
these renders exactly what it renders today. Rally rows keep
`clip.title ?: "Rally #{index}"`, unchanged.

**Read back.** `ClipListViewModel.refresh()` fans out two `async` jobs today;
metadata becomes a third, with the shares lookup's soft failure contract: on
failure leave the previous map untouched and surface no error. A missing title
degrades to `"Match · <date>"`, which is not worth interrupting the screen for.
`toMatches` takes the metadata map and fills the two new fields.

iOS mirrors it: `ClipListModel.refresh()` fetches with `try?`, and
`MatchGrouping.matches` gains a `metadataByVideoId` parameter so the merge stays
a pure function that `MatchGroupingTests` can exercise without Kotlin
construction.

## Error handling

An RPC failure is silent and non blocking, as above.

A failed `createVideo` is already a `CREATE_ROW` pipeline failure surfaced
through `AnalyzeResultDialog`, and `annotateHttpStatus` prefixes it with the
HTTP status. Client side normalization is what keeps a length or empty string
violation from reaching that path at all.

`setDetails` is a local `Settings` write with no failure mode worth surfacing,
matching every other `LocalVideoRepository` mutation.

## Testing

`shared/commonTest`:
- `LocalVideoDetailsTest`: trim, whitespace only to `null`, cap boundaries at
  60 and 500, text already at the cap.
- `LocalVideoEntrySerializationTest`: decode persisted JSON that predates both
  fields and assert nulls. This is the regression that would otherwise wipe a
  real user's whole local registry, silently, on first launch after update.
- `MatchMetadataSerializationTest`: `video_id` casing against the RPC.
- `LocalVideoRepositoryTest`: `setDetails` round trip.
- `AnalyzeCoordinatorTest`: `createVideo` receives title and description.
- `VideosRepositoryTest`: the insert body carries both.

`androidApp/test`:
- `ClipListViewModelTest`: `toMatches` merges metadata, and falls back when a
  video id is absent from the map.
- `LocalVideoListViewModelTest`: edit gating per stage.

`iosApp/Tests`:
- `MatchGroupingTests`: the same merge and fallback.
- `LocalVideoStatusTests`: edit gating per stage.

Beyond unit tests, two end to end runs on a real signed in session.

Owner path: import, name it, analyze, then confirm the `videos` row carries both
values and the match card renders them.

Shared path: share that analyzed match with a second account and confirm the
recipient's card shows the title and description. This is the run that tests the
reason Approach A was chosen over reading `videos` directly, so skipping it
would leave the design's central rationale unverified. If no second account is
available, record it here as a known unverified edge rather than quietly
dropping it.

Sign in on the iOS simulator requires the user, since the session is Keychain
only.
