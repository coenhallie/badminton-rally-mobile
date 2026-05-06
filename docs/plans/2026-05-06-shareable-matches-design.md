# Shareable matches — design

Status: approved 2026-05-06.

## Goal

Let an owner share a match (a `video_id` and all its `rally_clips` + `rally_annotations`) with another registered Shuttl user, looked up by email. The recipient can view; they cannot add, edit, or delete clips or annotations. Owner-side changes propagate automatically because both sides read from the same rows.

## Out of scope

- Notifications (in-app, email, push).
- Public / unauthenticated links.
- Recipients adding their own annotations on a shared match.
- Realtime updates — pull-to-refresh is sufficient.
- Sharing individual clips (only whole matches).

## Approach summary

A new `match_shares` table records grants. Existing SELECT policies on `rally_clips` and `rally_annotations` are extended so recipients see shared rows. Storage `SELECT` policies on `clips` and `thumbnails` buckets are extended so signed-URL generation works for recipients. Three `security definer` RPCs (`share_match`, `unshare_match`, `list_match_shares`) handle email lookup and grant management without exposing `auth.users` to the client. The mobile app gains a "Share" sheet on owned matches, a "Shared with me" section on the matches list, and gates write affordances on an `isOwner` check.

## 1. Schema

```sql
create table public.match_shares (
    video_id            uuid        not null,
    shared_with_user_id uuid        not null references auth.users(id) on delete cascade,
    granted_by          uuid        not null references auth.users(id) on delete cascade,
    created_at          timestamptz not null default now(),
    primary key (video_id, shared_with_user_id),
    constraint no_self_share check (shared_with_user_id <> granted_by)
);

create index match_shares_recipient_idx on public.match_shares (shared_with_user_id);
```

Notes:
- No FK on `video_id`: the rally_clips → match grouping is implicit (no separate `videos` table). Ownership of a `video_id` is enforced inside `share_match`.
- Composite PK makes share calls idempotent via `on conflict do nothing`.
- `granted_by` recorded for auditability and to constrain `unshare_match` to the original sharer.

## 2. RLS / storage policies

### `match_shares`

```sql
alter table match_shares enable row level security;

create policy "shares: select own or received"
  on match_shares for select to authenticated
  using (granted_by = auth.uid() or shared_with_user_id = auth.uid());

-- No INSERT/UPDATE/DELETE policies — all writes go through SECURITY DEFINER RPCs.
```

### `rally_clips`

```sql
drop policy if exists "clips: select own" on rally_clips;
create policy "clips: select own or shared"
  on rally_clips for select to authenticated
  using (
    owner_id = auth.uid()
    or exists (
      select 1 from match_shares ms
      where ms.video_id = rally_clips.video_id
        and ms.shared_with_user_id = auth.uid()
    )
  );
-- INSERT / UPDATE / DELETE: unchanged, owner-only.
```

### `rally_annotations`

```sql
drop policy if exists "annotations: select own" on rally_annotations;
create policy "annotations: select own or shared"
  on rally_annotations for select to authenticated
  using (
    exists (
      select 1 from rally_clips rc
      where rc.id = rally_annotations.clip_id
        and (
          rc.owner_id = auth.uid()
          or exists (
            select 1 from match_shares ms
            where ms.video_id = rc.video_id and ms.shared_with_user_id = auth.uid()
          )
        )
    )
  );
-- INSERT / UPDATE / DELETE: unchanged, owner-only.
```

### Storage (`clips` and `thumbnails` buckets)

```sql
create policy "storage clips: shared viewers"
  on storage.objects for select to authenticated
  using (
    bucket_id = 'clips'
    and exists (
      select 1 from rally_clips rc
      join match_shares ms on ms.video_id = rc.video_id
      where rc.clip_storage_path = storage.objects.name
        and ms.shared_with_user_id = auth.uid()
    )
  );

create policy "storage thumbnails: shared viewers"
  on storage.objects for select to authenticated
  using (
    bucket_id = 'thumbnails'
    and exists (
      select 1 from rally_clips rc
      join match_shares ms on ms.video_id = rc.video_id
      where rc.thumbnail_storage_path = storage.objects.name
        and ms.shared_with_user_id = auth.uid()
    )
  );
```

Owner storage policies are unchanged.

## 3. Server functions (RPCs)

### `share_match(p_video_id uuid, p_email text) returns uuid`

```sql
create or replace function public.share_match(p_video_id uuid, p_email text)
returns uuid
language plpgsql
security definer
set search_path = public, auth
as $$
declare
  v_user uuid;
begin
  if auth.uid() is null then
    raise exception 'unauthenticated' using errcode = 'P0001';
  end if;
  if not exists (
    select 1 from public.rally_clips
    where video_id = p_video_id and owner_id = auth.uid()
  ) then
    raise exception 'not_owner' using errcode = 'P0002';
  end if;
  select id into v_user from auth.users where lower(email) = lower(trim(p_email));
  if v_user is null then
    raise exception 'no_such_user' using errcode = 'P0003';
  end if;
  if v_user = auth.uid() then
    raise exception 'cannot_share_with_self' using errcode = 'P0004';
  end if;
  insert into public.match_shares (video_id, shared_with_user_id, granted_by)
    values (p_video_id, v_user, auth.uid())
    on conflict (video_id, shared_with_user_id) do nothing;
  return v_user;
end $$;

revoke all on function public.share_match(uuid, text) from public;
grant execute on function public.share_match(uuid, text) to authenticated;
```

### `unshare_match(p_video_id uuid, p_user_id uuid) returns void`

```sql
create or replace function public.unshare_match(p_video_id uuid, p_user_id uuid)
returns void
language plpgsql
security definer
set search_path = public
as $$
begin
  if auth.uid() is null then
    raise exception 'unauthenticated' using errcode = 'P0001';
  end if;
  delete from public.match_shares
    where video_id = p_video_id
      and shared_with_user_id = p_user_id
      and granted_by = auth.uid();
end $$;

revoke all on function public.unshare_match(uuid, uuid) from public;
grant execute on function public.unshare_match(uuid, uuid) to authenticated;
```

### `list_match_shares(p_video_id uuid)`

```sql
create or replace function public.list_match_shares(p_video_id uuid)
returns table (shared_with_user_id uuid, email text, created_at timestamptz)
language sql
security definer
set search_path = public, auth
as $$
  select ms.shared_with_user_id, u.email, ms.created_at
    from public.match_shares ms
    join auth.users u on u.id = ms.shared_with_user_id
   where ms.video_id = p_video_id
     and ms.granted_by = auth.uid()
   order by ms.created_at desc
$$;

revoke all on function public.list_match_shares(uuid) from public;
grant execute on function public.list_match_shares(uuid) to authenticated;
```

### Error → UI mapping

| SQLSTATE | Meaning | UI message |
|---|---|---|
| `P0001` | unauthenticated | (sign-in screen) |
| `P0002` | not owner of this match | "You can only share matches you uploaded." |
| `P0003` | no user with that email | "No Shuttl user found with that email." |
| `P0004` | self-share attempt | "You can't share a match with yourself." |

## 4. Shared Kotlin layer

### New model

```kotlin
// shared/src/commonMain/kotlin/com/badmintontracker/shared/model/MatchShare.kt
@Serializable
data class MatchShare(
    @SerialName("shared_with_user_id") val sharedWithUserId: String,
    val email: String,
    @SerialName("created_at")          val createdAt: Instant,
)
```

### Existing `RallyClip` gains `ownerId`

```kotlin
data class RallyClip(
    val id: String,
    @SerialName("video_id") val videoId: String,
    @SerialName("owner_id") val ownerId: String,   // NEW
    ...
)
```

### New repository

```kotlin
// shared/src/commonMain/kotlin/com/badmintontracker/shared/repo/SharesRepository.kt
interface SharesRepository {
    suspend fun share(videoId: String, email: String): Result<Unit>
    suspend fun unshare(videoId: String, userId: String): Result<Unit>
    suspend fun listShares(videoId: String): Result<List<MatchShare>>
}
```

`SharesRepositoryImpl` issues Postgrest RPC calls and maps Postgres error codes / messages to a sealed `ShareError` hierarchy:

```kotlin
sealed class ShareError(message: String) : Exception(message) {
    data object NotOwner          : ShareError("not_owner")
    data object NoSuchUser        : ShareError("no_such_user")
    data object CannotShareSelf   : ShareError("cannot_share_with_self")
    data class  Unknown(val cause: Throwable) : ShareError(cause.message ?: "unknown")
}
```

`ClipsRepository` is unchanged: RLS expands the row set automatically.

`AuthRepository` gains a `currentUserId(): String?` accessor (or its existing equivalent is exposed) so the UI layer can compute `isOwner`.

## 5. Android UI

### Navigation

```kotlin
sealed interface Route {
    @Serializable data object SignIn   : Route
    @Serializable data object ClipList : Route
    @Serializable data class  MatchClips(val videoId: String)   : Route
    @Serializable data class  ClipDetail(val clipId: String)    : Route
    @Serializable data class  ManageShares(val videoId: String) : Route   // NEW
}
```

`ManageShares` is the route the share sheet opens against; in the Android impl it can be a `ModalBottomSheet` overlaid on `MatchClipsScreen` rather than a separate destination — the route exists as a stable handle for the VM scope.

### `ClipListScreen`

Single screen, two labelled sections in one `LazyColumn`: **My matches** and **Shared with me**. Hide the second section entirely when empty. `ClipListState` splits matches:

```kotlin
data class ClipListState(
    val ownedMatches:  List<MatchSummary> = emptyList(),
    val sharedMatches: List<MatchSummary> = emptyList(),
    val isRefreshing:  Boolean = false,
    val error:         String? = null,
)
```

`MatchSummary` gains `isOwned: Boolean`, computed by comparing `coverClip.ownerId` with the current user id.

### `MatchClipsScreen`

- Top app bar action "Share" (`Icons.Default.PersonAdd`) — visible only when the match is owned. Opens a `ModalBottomSheet`:
  - email text field + "Share" button
  - existing recipients list from `list_match_shares` with a per-row "Remove" icon calling `unshare_match`
  - inline error text driven by `ShareError`
- For non-owned matches: no share action; rest of the screen is identical.

A small screen-scoped `ShareSheetViewModel` holds the email field, recipient list, and in-flight state to keep `MatchClipsScreen` thin.

### `ClipDetailScreen`

`ClipDetailViewModel` exposes `isOwner: Boolean` on its state, computed as `clip.ownerId == auth.currentUserId()`. The view:
- hides the FAB / "Add annotation" button when `!isOwner`
- hides (or disables) swipe-to-delete on annotation rows when `!isOwner`

Defense in depth: RLS rejects writes server-side regardless.

### Refresh

Existing pull-to-refresh on `ClipListScreen` already calls `clips.refresh()`, which now picks up newly shared / unshared matches automatically.

## 6. Testing

### Database / RLS (SQL)

- `share_match` happy path inserts row, returns recipient id.
- `share_match` raises with each of `P0002`/`P0003`/`P0004` for the corresponding bad inputs.
- `share_match` is idempotent under repeat calls.
- `unshare_match` deletes only when caller is `granted_by`; recipient calling it is a no-op.
- `rally_clips` SELECT: recipient sees shared, non-recipient does not.
- `rally_annotations` SELECT: recipient sees shared, non-recipient does not.
- `rally_annotations` write blocks: recipient INSERT/UPDATE/DELETE rejected.
- `storage.objects` SELECT: recipient signed-URL generation succeeds for shared paths, fails for unrelated paths.

### Shared Kotlin (`commonTest`, MockEngine)

- `SharesRepositoryImpl.share` issues `POST /rest/v1/rpc/share_match` with the expected JSON body.
- `SharesRepositoryImpl.share` maps each Postgres error response to the right `ShareError` variant.
- `SharesRepositoryImpl.listShares` decodes a list response into `List<MatchShare>`.
- `MatchShare` serialization round-trip.
- `RallyClip` serialization extended for `owner_id`.

### Android (ViewModel-level)

- `ClipListViewModel` partitions a mixed clip list into owned vs. shared correctly.
- `ClipDetailViewModel.isOwner` resolves correctly for owned vs. shared clips.
- `ShareSheetViewModel`: error mapping → user-facing message; clears email on success; refreshes recipient list after share/unshare.

### Manual smoke test

Two devices, users A and B. A shares match M with B's email → B pulls to refresh → match appears under "Shared with me" → B opens it → no share button, no add-annotation FAB, no delete affordance → A adds an annotation → B pulls to refresh → annotation appears → A revokes → B pulls to refresh → match disappears.

## Migration order

1. Migration: create `match_shares`, indexes, RLS policies on `match_shares`.
2. Migration: extend SELECT policies on `rally_clips` and `rally_annotations`.
3. Migration: add storage policies for `clips` and `thumbnails` buckets.
4. Migration: create the three RPC functions with grants.
5. Shared Kotlin: add `owner_id` to `RallyClip`, add `MatchShare`, add `SharesRepository`, add `ShareError` mapping.
6. Android: extend ViewModels (`ClipListViewModel`, `ClipDetailViewModel`), add `ShareSheetViewModel`, update screens.
7. Tests at each step (TDD).
