# Video removal: swipe-to-remove UI + remote delete API

Date: 2026-07-18
Status: approved

## Goal

Let users remove any row in the videos list ("My videos" screen) with the
standard mobile swipe gesture, on both Android and iOS:

- **Local videos** ("On this phone") — already removable via the row's
  overflow/ellipsis menu; add swipe as a shortcut.
- **Own matches** ("My matches") — no removal exists today; add a permanent
  server-side delete (video row, rally clips, annotations, shares, storage
  files).
- **Shared with me** — no removal exists today; add "leave share" so the row
  disappears from the recipient's list without touching the owner's data.

## Non-goals

- No undo/trash. Deletes are immediate (owned-match delete confirms first).
- No changes to the desktop/web app or its schema beyond the additive
  migration below.
- No batch/multi-select removal.

## Swipe behavior (both platforms)

- Swipe right-to-left on a row reveals a red action; a full swipe triggers it
  directly (iOS Mail style).
- **iOS**: rows already live in a SwiftUI `List`
  (`iosApp/Sources/ClipList/ClipListView.swift`), so use
  `.swipeActions(edge: .trailing, allowsFullSwipe: true)` with a
  `role: .destructive` button.
- **Android**: rows live in a `LazyColumn`
  (`androidApp/.../cliplist/ClipListScreen.kt`); wrap each row in a Material 3
  `SwipeToDismissBox` (end-to-start only) showing a red background with a
  trash icon. Releasing past the positional threshold triggers the action.
  State is remembered per item id so recycled rows don't inherit swipe state.
- Existing menu-based "Remove from app" on local rows stays (discoverability).

Per-row action and confirmation:

| Row type        | Swipe action label | Confirmation | Effect |
|-----------------|--------------------|--------------|--------|
| Local video     | Remove             | none         | Existing flow: `LocalVideoRepository.remove(id)` + annotation cleanup; iOS also deletes the app's file copy |
| Own match       | Delete             | dialog: "Delete this match and all its rally clips? This can't be undone." | New `VideosRepository.deleteMatch(videoId)` |
| Shared with me  | Remove             | none         | New `SharesRepository.leaveShare(videoId)` |

On Android, a cancelled confirmation dialog resets the swipe state so the row
snaps back.

## Backend: new migration `supabase/migrations/20260718000000_delete_match.sql`

The base schema lives outside this repo, so the migration is defensive:
idempotent policy drops, explicit row deletes (no reliance on unknown
cascades). It must be applied to the Supabase project (`supabase db push`)
before the app feature works.

1. **`delete_match(p_video_id uuid)`** — SECURITY DEFINER RPC, follows the
   `share_match` pattern (revoke public / grant authenticated). Raises
   `unauthenticated` / `not_owner` errors like the existing RPCs. Deletes, in
   order: `rally_annotations` (for the video's clips), `rally_clips`,
   `match_shares`, `videos` row — all in the function's single transaction.
2. **`leave_shared_match(p_video_id uuid)`** — SECURITY DEFINER RPC; deletes
   `match_shares` where `video_id = p_video_id and shared_with_user_id =
   auth.uid()`. Recipient-side mirror of `unshare_match`.
3. **Owner DELETE policies on `storage.objects`**:
   - `videos` bucket: `(storage.foldername(name))[1] = auth.uid()::text`
   - `clips` bucket: a `rally_clips` row with `clip_storage_path = name` and
     `owner_id = auth.uid()` exists
   - `thumbnails` bucket: same via `thumbnail_storage_path`

## Shared code (KMP)

- **`VideosRepository.deleteMatch(videoId): Result<Unit>`**
  (`shared/.../repo/VideosRepository.kt`):
  1. Select the video's `rally_clips` rows (`clip_storage_path`,
     `thumbnail_storage_path`).
  2. Best-effort delete storage objects: clip files, thumbnails, and the
     original `videos/{uid}/{videoId}.mp4` — done BEFORE row deletion because
     the storage policies check the rows. Storage failures are swallowed
     (orphaned file beats an unremovable match).
  3. Call the `delete_match` RPC. Its failure is the function's failure,
     annotated via the existing `annotateHttpStatus()`.
- **`SharesRepository.leaveShare(videoId): Result<Unit>`** — calls
  `leave_shared_match`.
- After success, callers invoke `ClipsRepository.refresh()` so the in-memory
  clips cache (and thus the match list) updates.

## Platform wiring

- **Android**: `ClipListViewModel` gains `deleteMatch(videoId)` and
  `leaveShare(videoId)` (launch, call repo, refresh on success, expose error
  for the screen's existing error surface). `ClipListScreen`/`MatchRow` and
  `LocalVideoSection` add the swipe wrappers; the owned-match path shows the
  `AlertDialog` first.
- **iOS**: `ClipListModel` gains the same two async methods; rows in
  `ClipListView` get `.swipeActions`; owned-match action shows a
  `confirmationDialog`/`alert` first. Local rows call the existing
  `intake.remove(entry:)` + thumbnail evict.

## Error handling

- RPC/network failure: row stays in the list; error shown via each screen's
  existing error presentation. No retry queue.
- Local removal cannot meaningfully fail (settings-backed store; iOS file
  delete already best-effort).

## Testing

- Shared unit tests (existing fakes extended: `FakeClipsRepository`, new fake
  for delete/leave): successful delete refreshes/prunes list state; failure
  keeps the row and surfaces the error.
- Swipe gestures and the RPCs against Supabase are verified manually on both
  platforms (build, swipe each row type, confirm list + server state).
