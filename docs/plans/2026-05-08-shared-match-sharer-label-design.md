# Shared-match sharer label — design

## Problem

When a match is shared with a user, it is inserted into their match list with no indication of *who* shared it. The list already groups received matches under a "Shared with me" section header (`ClipListScreen.kt:129–139`), so the binary "shared vs. owned" distinction is communicated. What is missing is the identity of the sharer.

## Goal

Under each shared-match row, display a single muted line:

```
Shared by alice@example.com
```

with ellipsis truncation when the email overflows. Owned-match rows are unchanged.

## Non-goals

- No display-name lookup or user-profile table. The share is addressed by email; the inverse should also be the email.
- No "Shared" badge on the row. The "Shared with me" section header already conveys that.
- No change to how owned matches render.
- No change to the share-by-email RPC, the `match_shares` table, or RLS.

## Approach

Add an additive RPC that returns, for the current user, the set of received shares with the sharer's email resolved from `auth.users`. Fetch it alongside the existing clips query, build a `videoId → sharerEmail` map in the view model, and attach `sharerEmail` to `MatchSummary` for shared rows only.

The existing `rally_clips` PostgREST query is untouched. The `RallyClip` data model is untouched — `sharerEmail` is a UI-grouping concern and lives on `MatchSummary`, not on the clip.

### Why an additive RPC, not a unified query

The alternatives considered:

- **Unified `list_visible_clips()` RPC replacing the existing `postgrest.from("rally_clips")` call.** Larger blast radius — every code path that reads clips would have to migrate to the RPC for marginal benefit (one fewer round-trip on a screen that already loads videos and thumbnails).
- **SQL view joining clips + shares + `auth.users`.** Reads from a view but writes still go to `rally_clips`, creating an asymmetric model. Also requires `security_invoker` setup over `auth.users`.

The chosen approach (separate RPC, client-side join on `videoId`) keeps the existing read path unchanged and isolates the new behavior to one new function and a small view-model addition.

## Components

### 1. Database — new migration

`supabase/migrations/20260508000000_list_received_match_shares.sql`:

```sql
create or replace function public.list_received_match_shares()
returns table (video_id uuid, sharer_email text, shared_at timestamptz)
language sql
security definer
set search_path = public, auth
as $$
  select ms.video_id, u.email, ms.created_at
    from public.match_shares ms
    join auth.users u on u.id = ms.granted_by
   where ms.shared_with_user_id = auth.uid()
$$;

revoke all   on function public.list_received_match_shares() from public;
grant execute on function public.list_received_match_shares() to authenticated;
```

Mirrors the existing `list_match_shares` RPC (`supabase/migrations/20260506000000_match_shares.sql:163–178`) in security pattern and `auth.users` access.

### 2. Shared module — `SharesRepository`

In `shared/src/commonMain/kotlin/com/badmintontracker/shared/repo/SharesRepository.kt`:

- Add a serializable `ReceivedShare` data class:

  ```kotlin
  @Serializable
  data class ReceivedShare(
      @SerialName("video_id")     val videoId: String,
      @SerialName("sharer_email") val sharerEmail: String,
      @SerialName("shared_at")    val sharedAt: Instant,
  )
  ```

- Add to the `SharesRepository` interface and its impl:

  ```kotlin
  suspend fun listReceived(): List<ReceivedShare>
  ```

  Implementation calls `client.postgrest.rpc("list_received_match_shares")` and decodes to `List<ReceivedShare>`.

`RallyClip` is **not** modified.

### 3. View model — `ClipListViewModel`

In `androidApp/src/main/java/com/badmintontracker/android/cliplist/ClipListViewModel.kt`:

- Inject `SharesRepository` (already used by `ShareSheetViewModel`; constructor wiring will follow the same DI pattern).
- Add `val sharerEmail: String? = null` to `MatchSummary`. Owned rows leave it null.
- In the flow that builds `MatchSummary` (around line 40), in parallel with the clips fetch, call `sharesRepo.listReceived()` once per refresh and build a `Map<String, String>` keyed by `videoId`. Look up `sharerEmail` for each shared `MatchSummary`. Owned summaries skip the lookup.
- If `listReceived()` fails, log and proceed with `sharerEmail = null` for all rows. The label is a soft enhancement — the row must still render. The existing error-surfacing for clip-load failure is unchanged; share-lookup failure does not propagate to the screen-level error state.

### 4. UI — `ClipListScreen`

In `MatchRow` (`androidApp/src/main/java/com/badmintontracker/android/cliplist/ClipListScreen.kt:165–206`), under the existing date / rally-count line, add a conditional `Text`:

```kotlin
if (match.sharerEmail != null) {
    Text(
        text = "Shared by ${match.sharerEmail}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
```

Renders only when populated, so owned rows are untouched. No layout reflow when the label is absent.

## Data flow

```
User opens match list
  → ClipListViewModel.refresh()
      ├─ clipsRepo.refresh()                  (existing)
      └─ sharesRepo.listReceived()            (new, parallel)
  → combine into MatchSummary list
      - owned rows:  sharerEmail = null
      - shared rows: sharerEmail = sharerByVideoId[videoId]
  → ClipListScreen renders
      - "My matches" section:    no extra line
      - "Shared with me":        each row shows "Shared by …"
```

## Error handling

- **Clips fetch fails:** existing behavior unchanged — surfaces in the screen error state.
- **`listReceived()` fails:** log, proceed with empty map. Shared rows render without the email line. The user still sees their shared matches; they just lack the sharer attribution. This is intentional — the feature is a UX enhancement, not load-bearing.
- **Sharer email missing for a row** (e.g. share row exists but sharer was deleted from `auth.users` — should be impossible because of `on delete cascade`, but defensively): `sharerEmail` is null, label is omitted.

## Testing

- **DB migration test:** insert a share between users A and B, call `list_received_match_shares()` as B → expect one row with A's email; call as A → expect empty.
- **`ClipListViewModelTest`** (extend the existing test at `androidApp/src/test/java/com/badmintontracker/android/cliplist/ClipListViewModelTest.kt`):
  - Add a fake `SharesRepository` that returns a single `ReceivedShare` for one of the shared-clip `videoId`s.
  - Extend the existing `state_partitions_owned_and_shared_matches` test (or add a sibling) to assert that the shared `MatchSummary` has `sharerEmail` set to the expected value, and the owned `MatchSummary` has `sharerEmail == null`.
  - Add a test for the soft-failure path: when `listReceived()` throws, all `MatchSummary` items have `sharerEmail == null` and the screen-level error state remains clear.
- **UI:** no new instrumented test — the change is a single conditional `Text`. A Compose preview entry will be added if the file already has previews (verify when implementing).

## Files touched

| File | Change |
|---|---|
| `supabase/migrations/20260508000000_list_received_match_shares.sql` | new — RPC |
| `shared/src/commonMain/kotlin/com/badmintontracker/shared/repo/SharesRepository.kt` | add `ReceivedShare` model + `listReceived()` |
| `androidApp/src/main/java/com/badmintontracker/android/cliplist/ClipListViewModel.kt` | inject `SharesRepository`, add `sharerEmail` to `MatchSummary`, populate in refresh |
| `androidApp/src/main/java/com/badmintontracker/android/cliplist/ClipListScreen.kt` | conditional "Shared by …" line in `MatchRow` |
| `androidApp/src/test/java/com/badmintontracker/android/cliplist/ClipListViewModelTest.kt` | extend with sharer-email assertions + soft-failure test |
| DI wiring (whichever file constructs `ClipListViewModel`) | pass `SharesRepository` into the constructor |
