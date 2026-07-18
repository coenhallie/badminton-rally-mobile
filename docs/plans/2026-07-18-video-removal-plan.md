# Video Removal (Swipe UI + Remote Delete API) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Swipe-to-remove on every row of the videos list (local videos, owned matches, shared-with-me matches) on Android and iOS, backed by new server-side delete/leave RPCs.

**Architecture:** A new Supabase migration adds `delete_match` / `leave_shared_match` SECURITY DEFINER RPCs plus owner DELETE policies on storage. Shared KMP repos gain `VideosRepository.deleteMatch` (storage cleanup first, then RPC), `SharesRepository.leaveShare`, and `ClipsRepository.pruneVideo` (instant local cache prune). Platform view models call these and refresh; the UI adds `SwipeToDismissBox` wrappers (Android) and `.swipeActions` (iOS), with a confirmation dialog only for owned-match deletion.

**Tech Stack:** Kotlin Multiplatform, supabase-kt 3.5.0, Jetpack Compose (BOM 2025.01.00, Material 3), SwiftUI, Postgres/Supabase.

## Global Constraints

- Spec: `docs/plans/2026-07-18-video-removal-design.md`. Read it before starting.
- Test commands: `./gradlew :shared:jvmTest`, `./gradlew :androidApp:testDebugUnitTest`, build check `./gradlew :androidApp:assembleDebug`.
- iOS builds need: `export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer`, `xattr -cr iosApp` before building, and `CODE_SIGNING_ALLOWED=NO` on xcodebuild. Full test command: `xcodebuild test -project iosApp/iosApp.xcodeproj -scheme iosApp -destination "platform=iOS Simulator,name=iPhone 17 Pro" -derivedDataPath iosApp/build/DerivedData CODE_SIGNING_ALLOWED=NO`.
- Do NOT run `supabase db push`. The migration is committed here; applying it to the hosted project is a user step (surface it in the final report).
- Copy strings verbatim: confirmation text "Delete this match and all its rally clips? This can't be undone."; errors "Couldn't delete the match. Please try again." and "Couldn't remove the shared match. Please try again."; swipe labels "Remove" (local + shared) and "Delete" (owned).
- The existing "Remove from app" menu items on local rows must remain on both platforms.

---

### Task 1: Supabase migration — delete_match, leave_shared_match, storage delete policies

**Files:**
- Create: `supabase/migrations/20260718000000_delete_match.sql`

**Interfaces:**
- Consumes: existing tables `videos` (has `id`, `owner_id`, `storage_path`), `rally_clips` (has `video_id`, `owner_id`, `clip_storage_path`, `thumbnail_storage_path`), `rally_annotations` (has `clip_id`), `match_shares` (has `video_id`, `shared_with_user_id`). Base schema lives outside this repo — write defensively (idempotent policy drops, explicit row deletes, no reliance on cascades).
- Produces: RPCs `delete_match(p_video_id uuid)` and `leave_shared_match(p_video_id uuid)`, callable by `authenticated`; owner DELETE policies on `storage.objects` for buckets `videos`, `clips`, `thumbnails`. Task 3 and Task 4 call these RPCs by name.

- [ ] **Step 1: Write the migration**

```sql
-- Match removal: owner delete + recipient leave.
-- See docs/plans/2026-07-18-video-removal-design.md

------------------------------------------------------------------
-- 1. RPC: delete_match — owner permanently deletes a match's rows.
--    Storage objects are deleted by the client BEFORE calling this
--    (the storage policies below check these rows).
------------------------------------------------------------------

create or replace function public.delete_match(p_video_id uuid)
returns void
language plpgsql
security definer
set search_path = public
as $$
begin
  if auth.uid() is null then
    raise exception 'unauthenticated' using errcode = 'P0001';
  end if;
  if not exists (
    select 1 from public.videos
    where id = p_video_id and owner_id = auth.uid()
  ) then
    raise exception 'not_owner' using errcode = 'P0002';
  end if;
  delete from public.rally_annotations
    where clip_id in (select id from public.rally_clips where video_id = p_video_id);
  delete from public.rally_clips  where video_id = p_video_id;
  delete from public.match_shares where video_id = p_video_id;
  delete from public.videos       where id = p_video_id;
end $$;

revoke all    on function public.delete_match(uuid) from public;
grant execute on function public.delete_match(uuid) to authenticated;

------------------------------------------------------------------
-- 2. RPC: leave_shared_match — recipient removes a received share.
--    Recipient-side mirror of unshare_match.
------------------------------------------------------------------

create or replace function public.leave_shared_match(p_video_id uuid)
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
      and shared_with_user_id = auth.uid();
end $$;

revoke all    on function public.leave_shared_match(uuid) from public;
grant execute on function public.leave_shared_match(uuid) to authenticated;

------------------------------------------------------------------
-- 3. Storage: owners may delete their own objects. Required for the
--    client-side cleanup that precedes delete_match.
------------------------------------------------------------------

drop policy if exists "storage videos: owner delete" on storage.objects;
create policy "storage videos: owner delete"
    on storage.objects for delete to authenticated
    using (
        bucket_id = 'videos'
        and (storage.foldername(name))[1] = auth.uid()::text
    );

drop policy if exists "storage clips: owner delete" on storage.objects;
create policy "storage clips: owner delete"
    on storage.objects for delete to authenticated
    using (
        bucket_id = 'clips'
        and exists (
            select 1 from public.rally_clips rc
            where rc.clip_storage_path = storage.objects.name
              and rc.owner_id = auth.uid()
        )
    );

drop policy if exists "storage thumbnails: owner delete" on storage.objects;
create policy "storage thumbnails: owner delete"
    on storage.objects for delete to authenticated
    using (
        bucket_id = 'thumbnails'
        and exists (
            select 1 from public.rally_clips rc
            where rc.thumbnail_storage_path = storage.objects.name
              and rc.owner_id = auth.uid()
        )
    );
```

- [ ] **Step 2: Sanity-check the SQL reads consistently**

No local Supabase stack exists in this repo, so this is a review step, not an execution step: confirm the function names match what Tasks 3/4 call (`delete_match`, `leave_shared_match`), argument name is `p_video_id` in both, and every `drop policy` name exactly matches its `create policy` name.

- [ ] **Step 3: Commit**

```bash
git add supabase/migrations/20260718000000_delete_match.sql
git commit -m "feat(db): delete_match + leave_shared_match RPCs, owner storage delete policies"
```

---

### Task 2: Shared — ClipsRepository.pruneVideo

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/badmintontracker/shared/repo/ClipsRepository.kt`
- Modify: `shared/src/commonTest/kotlin/com/badmintontracker/shared/testing/FakeClipsRepository.kt`
- Modify: `androidApp/src/test/java/com/badmintontracker/android/testing/FakeClipsRepository.kt`
- Test: `shared/src/commonTest/kotlin/com/badmintontracker/shared/repo/ClipsRepositoryTest.kt`

**Interfaces:**
- Produces: `fun pruneVideo(videoId: String)` on the `ClipsRepository` interface — synchronously drops the video's clips from the in-memory cache so the UI updates instantly after a server-side delete. Tasks 6 and 9 call it.

- [ ] **Step 1: Write the failing test**

Add to `ClipsRepositoryTest` (both existing clips in `twoClips` have `video_id` `v1`):

```kotlin
    @Test
    fun pruneVideo_drops_cached_clips_for_that_video() = runTest {
        val client = TestSupabase.client { _ -> jsonResponse(twoClips) }
        val repo = ClipsRepositoryImpl(client)

        turbineScope {
            val flow = repo.observeClips().testIn(backgroundScope)
            flow.awaitItem() shouldBe emptyList()
            repo.refresh()
            flow.awaitItem() shouldHaveSize 2
            repo.pruneVideo("v1")
            flow.awaitItem() shouldBe emptyList()
            flow.cancelAndIgnoreRemainingEvents()
        }
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :shared:jvmTest --tests "com.badmintontracker.shared.repo.ClipsRepositoryTest" 2>&1 | tail -5`
Expected: compilation FAILURE — `pruneVideo` unresolved.

- [ ] **Step 3: Implement**

In `ClipsRepository.kt`, add to the interface (after `countClipsForVideo`):

```kotlin
    /** Drop a video's clips from the in-memory cache (instant UI update after a server delete). */
    fun pruneVideo(videoId: String)
```

Add to `ClipsRepositoryImpl`:

```kotlin
    override fun pruneVideo(videoId: String) {
        _clips.value = _clips.value.filterNot { it.videoId == videoId }
    }
```

Add the identical override to BOTH fakes (`shared/src/commonTest/.../testing/FakeClipsRepository.kt` and `androidApp/src/test/.../testing/FakeClipsRepository.kt`):

```kotlin
    override fun pruneVideo(videoId: String) {
        clips.value = clips.value.filterNot { it.videoId == videoId }
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :shared:jvmTest :androidApp:testDebugUnitTest 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/badmintontracker/shared/repo/ClipsRepository.kt \
        shared/src/commonTest/kotlin/com/badmintontracker/shared/testing/FakeClipsRepository.kt \
        shared/src/commonTest/kotlin/com/badmintontracker/shared/repo/ClipsRepositoryTest.kt \
        androidApp/src/test/java/com/badmintontracker/android/testing/FakeClipsRepository.kt
git commit -m "feat(shared): ClipsRepository.pruneVideo for instant cache prune"
```

---

### Task 3: Shared — SharesRepository.leaveShare

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/badmintontracker/shared/repo/SharesRepository.kt`
- Modify: `androidApp/src/test/java/com/badmintontracker/android/testing/FakeSharesRepository.kt`
- Create: `shared/src/commonTest/kotlin/com/badmintontracker/shared/repo/SharesRepositoryTest.kt`

**Interfaces:**
- Consumes: `leave_shared_match` RPC from Task 1.
- Produces: `suspend fun leaveShare(videoId: String): Result<Unit>` on `SharesRepository`. Tasks 6 and 9 call it. Android fake gains `val leaveShareCalls: MutableList<String>` and `var nextLeaveShareResult: Result<Unit>`.

- [ ] **Step 1: Write the failing test**

Create `shared/src/commonTest/kotlin/com/badmintontracker/shared/repo/SharesRepositoryTest.kt`:

```kotlin
package com.badmintontracker.shared.repo

import com.badmintontracker.shared.testing.TestSupabase
import com.badmintontracker.shared.testing.jsonResponse
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class SharesRepositoryTest {

    @Test
    fun leaveShare_calls_leave_shared_match_rpc_with_video_id() = runTest {
        var captured: Pair<String, String>? = null
        val client = TestSupabase.client { request ->
            captured = request.url.encodedPath to ((request.body as? TextContent)?.text ?: "")
            jsonResponse("null")
        }
        val repo = SharesRepositoryImpl(client)

        val result = repo.leaveShare("v1")

        result.isSuccess shouldBe true
        captured!!.first shouldContain "rpc/leave_shared_match"
        captured!!.second shouldContain """"p_video_id":"v1""""
    }

    @Test
    fun leaveShare_failure_returns_failure() = runTest {
        val client = TestSupabase.client { _ ->
            jsonResponse("""{"message":"boom"}""", HttpStatusCode.InternalServerError)
        }
        val repo = SharesRepositoryImpl(client)

        repo.leaveShare("v1").isFailure shouldBe true
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :shared:jvmTest --tests "com.badmintontracker.shared.repo.SharesRepositoryTest" 2>&1 | tail -5`
Expected: compilation FAILURE — `leaveShare` unresolved.

- [ ] **Step 3: Implement**

In `SharesRepository.kt`, add to the interface (after `listReceived`):

```kotlin
    /** Recipient removes a received share so the match leaves their list. */
    suspend fun leaveShare(videoId: String): Result<Unit>
```

In `SharesRepositoryImpl`, add (reuses the existing `ListArgs(p_video_id)` serializable):

```kotlin
    override suspend fun leaveShare(videoId: String): Result<Unit> = runCatching {
        client.postgrest.rpc("leave_shared_match", ListArgs(videoId))
        Unit
    }
```

In `androidApp/src/test/.../testing/FakeSharesRepository.kt`, add fields next to the existing ones and the override:

```kotlin
    val leaveShareCalls = mutableListOf<String>()
    var nextLeaveShareResult: Result<Unit> = Result.success(Unit)

    override suspend fun leaveShare(videoId: String): Result<Unit> {
        leaveShareCalls += videoId
        return nextLeaveShareResult
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :shared:jvmTest :androidApp:testDebugUnitTest 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/badmintontracker/shared/repo/SharesRepository.kt \
        shared/src/commonTest/kotlin/com/badmintontracker/shared/repo/SharesRepositoryTest.kt \
        androidApp/src/test/java/com/badmintontracker/android/testing/FakeSharesRepository.kt
git commit -m "feat(shared): SharesRepository.leaveShare via leave_shared_match RPC"
```

---

### Task 4: Shared — VideosRepository.deleteMatch

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/badmintontracker/shared/repo/VideosRepository.kt`
- Modify: `shared/src/commonTest/kotlin/com/badmintontracker/shared/testing/FakeVideosRepository.kt`
- Modify: `androidApp/src/test/java/com/badmintontracker/android/testing/FakeVideosRepository.kt`
- Create: `shared/src/commonTest/kotlin/com/badmintontracker/shared/repo/VideosRepositoryDeleteMatchTest.kt`

**Interfaces:**
- Consumes: `delete_match` RPC and storage delete policies from Task 1.
- Produces: `suspend fun deleteMatch(videoId: String): Result<Unit>` on `VideosRepository`. Storage cleanup happens BEFORE the RPC (policies check the rows) and is best-effort. Both fakes gain `val deleteMatchCalls: MutableList<String>` and `var nextDeleteMatchResult: Result<Unit>`. Tasks 6 and 9 call it.

- [ ] **Step 1: Write the failing test**

Create `shared/src/commonTest/kotlin/com/badmintontracker/shared/repo/VideosRepositoryDeleteMatchTest.kt`:

```kotlin
package com.badmintontracker.shared.repo

import com.badmintontracker.shared.testing.TestSupabase
import com.badmintontracker.shared.testing.jsonResponse
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class VideosRepositoryDeleteMatchTest {

    private val clipRows = """
      [
        {"clip_storage_path":"u1/v1/c0.mp4","thumbnail_storage_path":"u1/v1/c0.jpg"},
        {"clip_storage_path":"u1/v1/c1.mp4","thumbnail_storage_path":null}
      ]
    """.trimIndent()

    private fun handler(calls: MutableList<String>, storageStatus: HttpStatusCode = HttpStatusCode.OK) =
        TestSupabase.client { request ->
            val path = request.url.encodedPath
            calls += "${request.method.value} $path"
            when {
                path.contains("/rest/v1/rpc/delete_match") -> jsonResponse("null")
                path.contains("/rest/v1/rally_clips")      -> jsonResponse(clipRows)
                path.contains("/rest/v1/videos")           -> jsonResponse("""[{"storage_path":"u1/v1.mp4"}]""")
                path.contains("/storage/v1/object/")       -> jsonResponse("[]", storageStatus)
                else                                       -> jsonResponse("[]")
            }
        }

    @Test
    fun deleteMatch_deletes_storage_objects_then_calls_rpc() = runTest {
        val calls = mutableListOf<String>()
        val repo = VideosRepositoryImpl(handler(calls))

        repo.deleteMatch("v1").isSuccess shouldBe true

        val rpcIndex = calls.indexOfFirst { it.contains("rpc/delete_match") }
        rpcIndex shouldBeGreaterThanOrEqual 0
        // clips, thumbnails, and original video buckets each got a delete...
        calls.count { it.startsWith("DELETE /storage/v1/object/") } shouldBe 3
        // ...and every storage delete happened before the row-deleting RPC.
        calls.forEachIndexed { i, call ->
            if (call.startsWith("DELETE /storage/v1/object/")) (i < rpcIndex) shouldBe true
        }
    }

    @Test
    fun deleteMatch_storage_failure_is_swallowed_and_rpc_still_runs() = runTest {
        val calls = mutableListOf<String>()
        val repo = VideosRepositoryImpl(handler(calls, storageStatus = HttpStatusCode.InternalServerError))

        repo.deleteMatch("v1").isSuccess shouldBe true

        calls.any { it.contains("rpc/delete_match") } shouldBe true
    }

    @Test
    fun deleteMatch_rpc_failure_returns_failure() = runTest {
        val client = TestSupabase.client { request ->
            val path = request.url.encodedPath
            when {
                path.contains("/rest/v1/rpc/delete_match") ->
                    jsonResponse("""{"message":"not_owner"}""", HttpStatusCode.Forbidden)
                path.contains("/rest/v1/rally_clips") -> jsonResponse("[]")
                path.contains("/rest/v1/videos")      -> jsonResponse("[]")
                else                                  -> jsonResponse("[]")
            }
        }
        val repo = VideosRepositoryImpl(client)

        repo.deleteMatch("v1").isFailure shouldBe true
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :shared:jvmTest --tests "com.badmintontracker.shared.repo.VideosRepositoryDeleteMatchTest" 2>&1 | tail -5`
Expected: compilation FAILURE — `deleteMatch` unresolved.

- [ ] **Step 3: Implement**

In `VideosRepository.kt`, add to the interface (after `uploadVideo`):

```kotlin
    /**
     * Permanently delete an owned match: best-effort storage cleanup (clips,
     * thumbnails, original video) FIRST — the storage delete policies check the
     * DB rows — then the delete_match RPC removes all rows transactionally.
     * A storage failure never blocks the delete; orphaned files beat a match
     * the user can't remove.
     */
    suspend fun deleteMatch(videoId: String): Result<Unit>
```

In `VideosRepositoryImpl`, add these serializables next to the existing ones:

```kotlin
    @Serializable
    private data class ClipPathsRow(
        @SerialName("clip_storage_path")      val clipPath: String,
        @SerialName("thumbnail_storage_path") val thumbnailPath: String? = null,
    )

    @Serializable
    private data class VideoPathRow(@SerialName("storage_path") val storagePath: String)

    @Serializable
    private data class DeleteMatchArgs(@SerialName("p_video_id") val videoId: String)
```

and the implementation:

```kotlin
    override suspend fun deleteMatch(videoId: String): Result<Unit> = runCatching {
        val clipRows = runCatching {
            client.postgrest.from("rally_clips")
                .select(Columns.list("clip_storage_path", "thumbnail_storage_path")) {
                    filter { eq("video_id", videoId) }
                }
                .decodeList<ClipPathsRow>()
        }.getOrElse { emptyList() }
        val videoPath = runCatching {
            client.postgrest.from("videos")
                .select(Columns.list("storage_path")) {
                    filter { eq("id", videoId) }
                }
                .decodeList<VideoPathRow>()
                .firstOrNull()?.storagePath
        }.getOrNull()

        val clipPaths = clipRows.map { it.clipPath }
        if (clipPaths.isNotEmpty()) runCatching { client.storage.from("clips").delete(clipPaths) }
        val thumbnailPaths = clipRows.mapNotNull { it.thumbnailPath }
        if (thumbnailPaths.isNotEmpty()) runCatching { client.storage.from("thumbnails").delete(thumbnailPaths) }
        if (videoPath != null) runCatching { client.storage.from("videos").delete(listOf(videoPath)) }

        client.postgrest.rpc("delete_match", DeleteMatchArgs(videoId))
        Unit
    }.annotateHttpStatus()
```

Add the import `io.github.jan.supabase.postgrest.rpc` to the file's imports.

Add to BOTH fakes (`shared/src/commonTest/.../testing/FakeVideosRepository.kt` and `androidApp/src/test/.../testing/FakeVideosRepository.kt`) — fields next to the existing ones, override at the bottom:

```kotlin
    var nextDeleteMatchResult: Result<Unit> = Result.success(Unit)
    val deleteMatchCalls = mutableListOf<String>()

    override suspend fun deleteMatch(videoId: String): Result<Unit> {
        deleteMatchCalls += videoId
        return nextDeleteMatchResult
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :shared:jvmTest :androidApp:testDebugUnitTest 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/badmintontracker/shared/repo/VideosRepository.kt \
        shared/src/commonTest/kotlin/com/badmintontracker/shared/repo/VideosRepositoryDeleteMatchTest.kt \
        shared/src/commonTest/kotlin/com/badmintontracker/shared/testing/FakeVideosRepository.kt \
        androidApp/src/test/java/com/badmintontracker/android/testing/FakeVideosRepository.kt
git commit -m "feat(shared): VideosRepository.deleteMatch — storage cleanup then delete_match RPC"
```

---

### Task 5: Shared — Swift interop wrappers for delete/leave

**Files:**
- Modify: `shared/src/iosMain/kotlin/com/badmintontracker/shared/SwiftInterop.kt`

**Interfaces:**
- Consumes: `VideosRepository.deleteMatch` (Task 4), `SharesRepository.leaveShare` (Task 3).
- Produces: `suspend fun VideosRepository.deleteMatchOrMessage(videoId: String): String?` and `suspend fun SharesRepository.leaveShareOrMessage(videoId: String): String?` — null on success, display-ready message on failure (the file's existing convention). Task 9 calls them from Swift as `SwiftInteropKt.deleteMatchOrMessage(...)` / `SwiftInteropKt.leaveShareOrMessage(...)`.

- [ ] **Step 1: Implement (no unit test — iosMain has no test target; verified by compile)**

Add the import `com.badmintontracker.shared.repo.VideosRepository` to `SwiftInterop.kt`, then add after `listSharesOrNull`:

```kotlin
suspend fun VideosRepository.deleteMatchOrMessage(videoId: String): String? =
    deleteMatch(videoId).exceptionOrNull()?.let { "Couldn't delete the match. Please try again." }

suspend fun SharesRepository.leaveShareOrMessage(videoId: String): String? =
    leaveShare(videoId).exceptionOrNull()?.let { "Couldn't remove the shared match. Please try again." }
```

- [ ] **Step 2: Verify the iOS source set compiles**

Run: `export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer && ./gradlew :shared:compileKotlinIosSimulatorArm64 2>&1 | tail -3`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add shared/src/iosMain/kotlin/com/badmintontracker/shared/SwiftInterop.kt
git commit -m "feat(shared): Swift interop wrappers for deleteMatch/leaveShare"
```

---

### Task 6: Android — ClipListViewModel.deleteMatch / leaveShare + wiring

**Files:**
- Modify: `androidApp/src/main/java/com/badmintontracker/android/cliplist/ClipListViewModel.kt`
- Modify: `androidApp/src/main/java/com/badmintontracker/android/AuthGate.kt` (two `ClipListViewModel(...)` constructor calls, lines ~106 and ~147)
- Test: `androidApp/src/test/java/com/badmintontracker/android/cliplist/ClipListViewModelTest.kt`

**Interfaces:**
- Consumes: `VideosRepository.deleteMatch`, `SharesRepository.leaveShare`, `ClipsRepository.pruneVideo`; fakes' `deleteMatchCalls` / `nextDeleteMatchResult` / `leaveShareCalls` / `nextLeaveShareResult`.
- Produces: `ClipListViewModel(clips, auth, shares, videos)` — note the NEW 4th constructor param `videos: VideosRepository` — plus `fun deleteMatch(videoId: String)` and `fun leaveShare(videoId: String)`. Task 8's UI calls these. Error copy: "Couldn't delete the match. Please try again." / "Couldn't remove the shared match. Please try again."

- [ ] **Step 1: Write the failing tests**

In `ClipListViewModelTest.kt`, add imports `com.badmintontracker.android.testing.FakeVideosRepository`, then add tests:

```kotlin
    @Test
    fun deleteMatch_success_prunes_row_and_refreshes() = runTest {
        val clips = FakeClipsRepository().apply { this.clips.value = listOf(ownedClip("a", "v-own")) }
        val auth = FakeAuthRepository().apply { currentUserIdValue = "user-self" }
        val videos = FakeVideosRepository()
        val vm = ClipListViewModel(clips, auth, FakeSharesRepository(), videos)
        vm.state.test {
            var s = awaitItem()
            while (s.ownedMatches.isEmpty()) s = awaitItem()
            vm.deleteMatch("v-own")
            while (s.ownedMatches.isNotEmpty()) s = awaitItem()
            videos.deleteMatchCalls shouldBe listOf("v-own")
            s.error shouldBe null
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun deleteMatch_failure_surfaces_error_and_keeps_row() = runTest {
        val clips = FakeClipsRepository().apply { this.clips.value = listOf(ownedClip("a", "v-own")) }
        val auth = FakeAuthRepository().apply { currentUserIdValue = "user-self" }
        val videos = FakeVideosRepository().apply {
            nextDeleteMatchResult = Result.failure(IllegalStateException("boom"))
        }
        val vm = ClipListViewModel(clips, auth, FakeSharesRepository(), videos)
        vm.state.test {
            var s = awaitItem()
            while (s.ownedMatches.isEmpty()) s = awaitItem()
            vm.deleteMatch("v-own")
            while (s.error == null) s = awaitItem()
            s.error shouldBe "Couldn't delete the match. Please try again."
            s.ownedMatches shouldHaveSize 1
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun leaveShare_success_prunes_row() = runTest {
        val clips = FakeClipsRepository().apply { this.clips.value = listOf(sharedClip("b", "v-shared")) }
        val auth = FakeAuthRepository().apply { currentUserIdValue = "user-self" }
        val shares = FakeSharesRepository()
        val vm = ClipListViewModel(clips, auth, shares, FakeVideosRepository())
        vm.state.test {
            var s = awaitItem()
            while (s.sharedMatches.isEmpty()) s = awaitItem()
            vm.leaveShare("v-shared")
            while (s.sharedMatches.isNotEmpty()) s = awaitItem()
            shares.leaveShareCalls shouldBe listOf("v-shared")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun leaveShare_failure_surfaces_error_and_keeps_row() = runTest {
        val clips = FakeClipsRepository().apply { this.clips.value = listOf(sharedClip("b", "v-shared")) }
        val auth = FakeAuthRepository().apply { currentUserIdValue = "user-self" }
        val shares = FakeSharesRepository().apply {
            nextLeaveShareResult = Result.failure(IllegalStateException("boom"))
        }
        val vm = ClipListViewModel(clips, auth, shares, FakeVideosRepository())
        vm.state.test {
            var s = awaitItem()
            while (s.sharedMatches.isEmpty()) s = awaitItem()
            vm.leaveShare("v-shared")
            while (s.error == null) s = awaitItem()
            s.error shouldBe "Couldn't remove the shared match. Please try again."
            s.sharedMatches shouldHaveSize 1
            cancelAndIgnoreRemainingEvents()
        }
    }
```

Also update every EXISTING `ClipListViewModel(...)` 3-arg construction in this test file to pass `FakeVideosRepository()` as the 4th argument (6 call sites).

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "com.badmintontracker.android.cliplist.ClipListViewModelTest" 2>&1 | tail -5`
Expected: compilation FAILURE — 4-arg constructor / `deleteMatch` unresolved.

- [ ] **Step 3: Implement**

In `ClipListViewModel.kt`: add import `com.badmintontracker.shared.repo.VideosRepository`; add the constructor param:

```kotlin
class ClipListViewModel(
    private val clips: ClipsRepository,
    private val auth: AuthRepository,
    private val shares: SharesRepository,
    private val videos: VideosRepository,
) : ViewModel() {
```

Add after `signOut`/`dismissError`:

```kotlin
    fun deleteMatch(videoId: String) {
        viewModelScope.launch {
            videos.deleteMatch(videoId)
                .onSuccess { clips.pruneVideo(videoId); refresh() }
                .onFailure { errors.value = "Couldn't delete the match. Please try again." }
        }
    }

    fun leaveShare(videoId: String) {
        viewModelScope.launch {
            shares.leaveShare(videoId)
                .onSuccess { clips.pruneVideo(videoId); refresh() }
                .onFailure { errors.value = "Couldn't remove the shared match. Please try again." }
        }
    }
```

In `AuthGate.kt`, update BOTH constructor calls (in `composable<Route.ClipList>` and `composable<Route.MatchClips>`):

```kotlin
                            initializer { ClipListViewModel(rally.clips, rally.auth, rally.shares, rally.videos) }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :androidApp:testDebugUnitTest 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add androidApp/src/main/java/com/badmintontracker/android/cliplist/ClipListViewModel.kt \
        androidApp/src/main/java/com/badmintontracker/android/AuthGate.kt \
        androidApp/src/test/java/com/badmintontracker/android/cliplist/ClipListViewModelTest.kt
git commit -m "feat(android): deleteMatch/leaveShare in ClipListViewModel"
```

---

### Task 7: Android — SwipeToRemoveRow component + swipe on local rows

**Files:**
- Create: `androidApp/src/main/java/com/badmintontracker/android/ui/components/SwipeToRemoveRow.kt`
- Modify: `androidApp/src/main/java/com/badmintontracker/android/localvideo/LocalVideoSection.kt` (the `items` block, lines ~52-60)

**Interfaces:**
- Produces: `@Composable fun SwipeToRemoveRow(label: String, onSwiped: () -> Boolean, content: @Composable () -> Unit)`. `onSwiped` fires when the user swipes past the threshold (end-to-start only); return `true` to let the row dismiss (ONLY when the row is guaranteed to leave the list synchronously), `false` to snap back. Task 8 reuses this for match rows.

- [ ] **Step 1: Create the component**

```kotlin
package com.badmintontracker.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * End-to-start swipe that reveals a red delete affordance. [onSwiped] must
 * return true only when the row synchronously leaves the list; return false
 * to snap back (e.g. while a confirmation dialog or network call is pending).
 */
@Composable
fun SwipeToRemoveRow(
    label: String,
    onSwiped: () -> Boolean,
    content: @Composable () -> Unit,
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) onSwiped() else false
        },
    )
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.error),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = label,
                    tint = MaterialTheme.colorScheme.onError,
                    modifier = Modifier.padding(end = 24.dp),
                )
            }
        },
    ) {
        // Opaque backing so the red background stays hidden until the swipe.
        Box(Modifier.background(MaterialTheme.colorScheme.background)) { content() }
    }
}
```

- [ ] **Step 2: Wrap local rows**

In `LocalVideoSection.kt`, add import `com.badmintontracker.android.ui.components.SwipeToRemoveRow` and change the `items` block of `localVideoSection` to:

```kotlin
    items(rows, key = { "local-${it.entry.id}" }) { row ->
        SwipeToRemoveRow(
            label = "Remove",
            // Local removal is synchronous and can't fail, so dismissing is safe.
            onSwiped = { onRemove(row.entry); true },
        ) {
            LocalVideoRowItem(
                row = row,
                onClick = { onRowClick(row.entry) },
                onAnalyze = { onAnalyzeClick(row) },
                onRemove = { onRemove(row.entry) },
            )
        }
        HorizontalDivider()
    }
```

The existing overflow-menu "Remove from app" item stays untouched.

- [ ] **Step 3: Build**

Run: `./gradlew :androidApp:assembleDebug 2>&1 | tail -3`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add androidApp/src/main/java/com/badmintontracker/android/ui/components/SwipeToRemoveRow.kt \
        androidApp/src/main/java/com/badmintontracker/android/localvideo/LocalVideoSection.kt
git commit -m "feat(android): swipe-to-remove on local video rows"
```

---

### Task 8: Android — swipe on match rows + delete confirmation dialog

**Files:**
- Modify: `androidApp/src/main/java/com/badmintontracker/android/cliplist/ClipListScreen.kt` (owned/shared `items` blocks lines ~185-208, plus dialog at the end of `ClipListScreen`)

**Interfaces:**
- Consumes: `SwipeToRemoveRow` (Task 7), `vm.deleteMatch` / `vm.leaveShare` (Task 6).
- Produces: nothing new — UI behavior only.

- [ ] **Step 1: Implement**

In `ClipListScreen.kt` add imports `androidx.compose.material3.AlertDialog`, `androidx.compose.material3.TextButton`, and `com.badmintontracker.android.ui.components.SwipeToRemoveRow`.

Add state next to `var sheetVideoId` (line ~84):

```kotlin
    var deleteTarget by remember { mutableStateOf<MatchSummary?>(null) }
```

Wrap the owned rows (remote deletes are async and confirmable, so `onSwiped` always returns `false` — the row snaps back and disappears when the cache updates):

```kotlin
                        items(state.ownedMatches, key = { "owned-${it.videoId}" }) { match ->
                            SwipeToRemoveRow(
                                label = "Delete",
                                onSwiped = { deleteTarget = match; false },
                            ) {
                                MatchRow(
                                    match = match,
                                    media = media,
                                    onClick = { onMatchClick(match) },
                                    onShareClick = { sheetVideoId = match.videoId },
                                )
                            }
                            HorizontalDivider()
                        }
```

Wrap the shared rows:

```kotlin
                        items(state.sharedMatches, key = { "shared-${it.videoId}" }) { match ->
                            SwipeToRemoveRow(
                                label = "Remove",
                                onSwiped = { vm.leaveShare(match.videoId); false },
                            ) {
                                MatchRow(
                                    match = match,
                                    media = media,
                                    onClick = { onMatchClick(match) },
                                    onShareClick = null,
                                )
                            }
                            HorizontalDivider()
                        }
```

Add the confirmation dialog after the `resultDialog?.let { ... }` block at the end of `ClipListScreen`:

```kotlin
    deleteTarget?.let { match ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete match?") },
            text = { Text("Delete this match and all its rally clips? This can't be undone.") },
            confirmButton = {
                TextButton(onClick = { vm.deleteMatch(match.videoId); deleteTarget = null }) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            },
        )
    }
```

- [ ] **Step 2: Build and run unit tests**

Run: `./gradlew :androidApp:assembleDebug :androidApp:testDebugUnitTest 2>&1 | tail -3`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add androidApp/src/main/java/com/badmintontracker/android/cliplist/ClipListScreen.kt
git commit -m "feat(android): swipe-to-delete matches with confirm, swipe-to-leave shares"
```

---

### Task 9: iOS — model methods + swipe actions + confirmation dialog

**Files:**
- Modify: `iosApp/Sources/ClipList/ClipListModel.swift`
- Modify: `iosApp/Sources/ClipList/ClipListView.swift`

**Interfaces:**
- Consumes: `SwiftInteropKt.deleteMatchOrMessage` / `SwiftInteropKt.leaveShareOrMessage` (Task 5), `rally.clips.pruneVideo` (Task 2), existing `intake.remove(entry:)` + `thumbnails.evict(id:)`.
- Produces: `ClipListModel.deleteMatch(videoId:) async` and `ClipListModel.leaveShare(videoId:) async`.

- [ ] **Step 1: Add model methods**

In `ClipListModel.swift`, after `signOut()`:

```swift
    func deleteMatch(videoId: String) async {
        if let message = try? await SwiftInteropKt.deleteMatchOrMessage(rally.videos, videoId: videoId) {
            error = message
            return
        }
        rally.clips.pruneVideo(videoId: videoId)
        await refresh()
    }

    func leaveShare(videoId: String) async {
        if let message = try? await SwiftInteropKt.leaveShareOrMessage(rally.shares, videoId: videoId) {
            error = message
            return
        }
        rally.clips.pruneVideo(videoId: videoId)
        await refresh()
    }
```

- [ ] **Step 2: Add swipe actions and dialog to the view**

In `ClipListView.swift`, add state next to `@State private var shareTarget`:

```swift
    @State private var deleteTarget: MatchSummary? = nil
```

Local rows — append after the `LocalVideoRowView(...)` call inside its `ForEach` (keep the existing ellipsis menu):

```swift
                        .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                            Button(role: .destructive) {
                                intake.remove(entry: entry)
                                thumbnails.evict(id: entry.id)
                            } label: {
                                Label("Remove", systemImage: "trash")
                            }
                        }
```

Owned rows — change `row(match, model: model)` inside the "My matches" `ForEach` to:

```swift
                        row(match, model: model)
                            .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                                Button(role: .destructive) {
                                    deleteTarget = match
                                } label: {
                                    Label("Delete", systemImage: "trash")
                                }
                            }
```

Shared rows — change `row(match, model: model)` inside the "Shared with me" `ForEach` to:

```swift
                        row(match, model: model)
                            .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                                Button(role: .destructive) {
                                    Task { await model.leaveShare(videoId: match.videoId) }
                                } label: {
                                    Label("Remove", systemImage: "trash")
                                }
                            }
```

Confirmation dialog — attach to the `List` in `content(_:)`, after `.listStyle(.plain)`:

```swift
        .confirmationDialog(
            "Delete this match and all its rally clips? This can't be undone.",
            isPresented: Binding(
                get: { deleteTarget != nil },
                set: { if !$0 { deleteTarget = nil } }
            ),
            titleVisibility: .visible,
            presenting: deleteTarget
        ) { match in
            Button("Delete", role: .destructive) {
                let videoId = match.videoId
                deleteTarget = nil
                Task { await model.deleteMatch(videoId: videoId) }
            }
            Button("Cancel", role: .cancel) { deleteTarget = nil }
        }
```

Note `content(_:)` receives `model` as a non-optional parameter, so `model.deleteMatch` is directly callable there.

- [ ] **Step 3: Build and run iOS tests**

Run:
```bash
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
xattr -cr iosApp
xcodebuild test -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -destination "platform=iOS Simulator,name=iPhone 17 Pro" \
  -derivedDataPath iosApp/build/DerivedData CODE_SIGNING_ALLOWED=NO 2>&1 | tail -5
```
Expected: `** TEST SUCCEEDED **`.

- [ ] **Step 4: Commit**

```bash
git add iosApp/Sources/ClipList/ClipListModel.swift iosApp/Sources/ClipList/ClipListView.swift
git commit -m "feat(ios): swipe-to-remove rows, match delete with confirm, leave shares"
```

---

### Task 10: Full verification sweep

**Files:** none new.

- [ ] **Step 1: Run everything**

Run:
```bash
./gradlew :shared:jvmTest :androidApp:testDebugUnitTest :androidApp:assembleDebug 2>&1 | tail -3
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
xattr -cr iosApp
xcodebuild test -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -destination "platform=iOS Simulator,name=iPhone 17 Pro" \
  -derivedDataPath iosApp/build/DerivedData CODE_SIGNING_ALLOWED=NO 2>&1 | tail -5
```
Expected: BUILD SUCCESSFUL and `** TEST SUCCEEDED **`.

- [ ] **Step 2: Report the deployment step**

In the final summary, tell the user the migration `supabase/migrations/20260718000000_delete_match.sql` must be applied to the hosted Supabase project (e.g. `supabase db push` from a linked checkout, or paste into the SQL editor) before remote delete/leave works in the apps, and that swipe gestures should be manually verified on an emulator/simulator (local row, owned match incl. cancel path, shared match).
