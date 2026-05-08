# Shared-match sharer label — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show a single muted line "Shared by alice@example.com" under each row in the "Shared with me" section of the Android match list.

**Architecture:** Add one additive `security definer` RPC `list_received_match_shares()` that joins `match_shares` + `auth.users` for the current user. Fetch it in `ClipListViewModel` in parallel with the existing clips refresh, cache the resulting `videoId → sharerEmail` map in a StateFlow, and combine it into `MatchSummary` so shared rows carry an optional `sharerEmail`. Render the email under shared rows in `ClipListScreen.MatchRow`. The existing `rally_clips` PostgREST query and `RallyClip` model are untouched.

**Design doc:** `docs/plans/2026-05-08-shared-match-sharer-label-design.md`

**Tech Stack:** Supabase (Postgres + RLS), Kotlin Multiplatform (`shared` module), Jetpack Compose / Material 3 (Android), Ktor MockEngine + kotest + turbine (tests).

---

## Conventions used by this codebase (reference)

- **Migration filename**: `YYYYMMDDhhmmss_description.sql` under `supabase/migrations/`. Single file per feature.
- **Shared Kotlin tests**: `shared/src/commonTest/kotlin/...`, run via `./gradlew :shared:jvmTest`.
- **Android tests**: `androidApp/src/test/java/...`, run via `./gradlew :androidApp:testDebugUnitTest`.
- **Repository test pattern**: Ktor `MockEngine` via `TestSupabase.client { request -> jsonResponse(...) }` (`shared/src/commonTest/kotlin/com/badmintontracker/shared/testing/SupabaseTestClient.kt`).
- **ViewModel test pattern**: `Fake*Repository` doubles in `androidApp/src/test/java/com/badmintontracker/android/testing/`, `StandardTestDispatcher` + `Dispatchers.setMain`, `turbine` for Flow assertions.
- **DI**: All shared repositories are constructed once in `RallyApp` (`shared/src/commonMain/kotlin/com/badmintontracker/shared/RallyApp.kt`). `ClipListViewModel` is created via `viewModel { initializer { ... } }` blocks in `androidApp/src/main/java/com/badmintontracker/android/AuthGate.kt` (two call sites at lines 73 and 88).

---

## Task 1: Migration — `list_received_match_shares()` RPC

**Files:**
- Create: `supabase/migrations/20260508000000_list_received_match_shares.sql`

This is an additive, security-definer SQL function. It mirrors `list_match_shares` (same security pattern, same `auth.users` access) except it returns *received* shares for the calling user.

- [ ] **Step 1: Write the migration file**

Create `supabase/migrations/20260508000000_list_received_match_shares.sql`:

```sql
-- Sharer-email lookup for received matches.
-- See docs/plans/2026-05-08-shared-match-sharer-label-design.md

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

- [ ] **Step 2: Apply the migration locally**

Run from project root:

```bash
supabase db reset
```

Expected: migration applies without errors. The `supabase` CLI prints "Finished `supabase db reset`."

If the project is using a remote-only workflow (no local DB), use:

```bash
supabase db push
```

Expected: the new migration is listed and applied.

- [ ] **Step 3: Smoke-test the RPC**

In `psql` against the local Supabase DB (`supabase db --help` shows the connection string; typical URL is shown by `supabase status` under "DB URL"):

```sql
-- as the recipient (replace UUIDs with two real auth.users IDs)
set local "request.jwt.claims" = '{"sub":"<recipient-uuid>"}';
select * from public.list_received_match_shares();
```

Expected: returns rows for shares where `shared_with_user_id` matches the JWT `sub`. Returns zero rows when called as a user with no received shares.

This step is exploratory only — no automated DB test is required because the function is a single SELECT joined to RLS-protected tables and is exercised end-to-end by the repository test in Task 2.

- [ ] **Step 4: Commit**

```bash
git add supabase/migrations/20260508000000_list_received_match_shares.sql
git commit -m "feat(db): add list_received_match_shares RPC

Returns video_id + sharer email + shared_at for the calling
recipient, joining match_shares with auth.users.

Used by the match list to display 'Shared by <email>' under
each received-match row."
```

---

## Task 2: `ReceivedShare` model + `SharesRepository.listReceived()`

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/badmintontracker/shared/repo/SharesRepository.kt`
- Modify: `shared/src/commonTest/kotlin/com/badmintontracker/shared/repo/SharesRepositoryTest.kt`

- [ ] **Step 1: Write the failing repository test**

Open `shared/src/commonTest/kotlin/com/badmintontracker/shared/repo/SharesRepositoryTest.kt`. Append the following imports if missing (some are already present — check the existing imports first; only add what's not there):

```kotlin
import kotlinx.datetime.Instant
```

Add this test inside the `SharesRepositoryTest` class (alongside the existing tests):

```kotlin
@Test
fun listReceived_decodes_rpc_response() = runTest {
    var capturedUrl: String? = null
    val client = TestSupabase.client { req ->
        capturedUrl = req.url.toString()
        jsonResponse("""[
          {"video_id":"v1","sharer_email":"alice@example.com","shared_at":"2026-05-07T10:00:00Z"},
          {"video_id":"v2","sharer_email":"bob@example.com","shared_at":"2026-05-08T11:00:00Z"}
        ]""")
    }

    val result = SharesRepositoryImpl(client).listReceived()

    capturedUrl!!.shouldContain("/rest/v1/rpc/list_received_match_shares")
    result shouldHaveSize 2
    result[0].videoId shouldBe "v1"
    result[0].sharerEmail shouldBe "alice@example.com"
    result[0].sharedAt shouldBe Instant.parse("2026-05-07T10:00:00Z")
    result[1].sharerEmail shouldBe "bob@example.com"
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
./gradlew :shared:jvmTest --tests "com.badmintontracker.shared.repo.SharesRepositoryTest.listReceived_decodes_rpc_response"
```

Expected: FAILS with a compilation error — `listReceived` is not defined on `SharesRepository`, and `ReceivedShare` does not exist.

- [ ] **Step 3: Add the `ReceivedShare` model and the repository method**

Edit `shared/src/commonMain/kotlin/com/badmintontracker/shared/repo/SharesRepository.kt`. Add the `ReceivedShare` data class at file scope (above or below the existing `interface SharesRepository`):

```kotlin
@Serializable
data class ReceivedShare(
    @SerialName("video_id")     val videoId: String,
    @SerialName("sharer_email") val sharerEmail: String,
    @SerialName("shared_at")    val sharedAt: kotlinx.datetime.Instant,
)
```

If `kotlinx.datetime.Instant` is not yet imported in this file, add the import at the top:

```kotlin
import kotlinx.datetime.Instant
```

…and use the unqualified `Instant` in the data class instead of the fully-qualified form.

Add the method to the `SharesRepository` interface:

```kotlin
suspend fun listReceived(): List<ReceivedShare>
```

Implement it in `SharesRepositoryImpl`. The existing impl uses `client.postgrest.rpc("name", args)` for parameterized RPCs; this RPC takes no arguments, so call the no-args overload. Add this method to the class body alongside `listShares`:

```kotlin
override suspend fun listReceived(): List<ReceivedShare> =
    client.postgrest.rpc("list_received_match_shares")
        .decodeList<ReceivedShare>()
```

Note: this method does **not** wrap in `Result` / `runCatching`. The view model (Task 4) decides the soft-failure policy by catching at the call site. The other `Result`-returning methods on this repository signal user-facing share/unshare errors; `listReceived` is a background-data fetch and should not be conflated with that pattern.

- [ ] **Step 4: Run the test to verify it passes**

```bash
./gradlew :shared:jvmTest --tests "com.badmintontracker.shared.repo.SharesRepositoryTest.listReceived_decodes_rpc_response"
```

Expected: PASSES.

- [ ] **Step 5: Run the full shared test suite**

```bash
./gradlew :shared:jvmTest
```

Expected: all tests in `:shared:jvmTest` pass. No tests were modified except by addition.

- [ ] **Step 6: Commit**

```bash
git add shared/src/commonMain/kotlin/com/badmintontracker/shared/repo/SharesRepository.kt \
        shared/src/commonTest/kotlin/com/badmintontracker/shared/repo/SharesRepositoryTest.kt
git commit -m "feat(shared): add SharesRepository.listReceived()

Adds a ReceivedShare model and a no-args RPC call to
list_received_match_shares, returning the current user's
received shares with the sharer's email."
```

---

## Task 3: Extend `FakeSharesRepository` with `listReceived()`

**Files:**
- Modify: `androidApp/src/test/java/com/badmintontracker/android/testing/FakeSharesRepository.kt`

The fake currently implements the original three methods. The view-model test in Task 4 needs to control what `listReceived()` returns and to simulate failure.

- [ ] **Step 1: Update the fake**

Replace the contents of `androidApp/src/test/java/com/badmintontracker/android/testing/FakeSharesRepository.kt` with:

```kotlin
package com.badmintontracker.android.testing

import com.badmintontracker.shared.model.MatchShare
import com.badmintontracker.shared.repo.ReceivedShare
import com.badmintontracker.shared.repo.SharesRepository

class FakeSharesRepository : SharesRepository {
    val shareCalls   = mutableListOf<Pair<String, String>>()
    val unshareCalls = mutableListOf<Pair<String, String>>()
    var nextShareResult:   Result<Unit> = Result.success(Unit)
    var nextUnshareResult: Result<Unit> = Result.success(Unit)
    var sharesByVideo: Map<String, List<MatchShare>> = emptyMap()

    var receivedShares: List<ReceivedShare> = emptyList()
    var listReceivedError: Throwable? = null
    var listReceivedCalls: Int = 0

    override suspend fun share(videoId: String, email: String): Result<Unit> {
        shareCalls += videoId to email
        return nextShareResult
    }

    override suspend fun unshare(videoId: String, userId: String): Result<Unit> {
        unshareCalls += videoId to userId
        return nextUnshareResult
    }

    override suspend fun listShares(videoId: String): Result<List<MatchShare>> =
        Result.success(sharesByVideo[videoId].orEmpty())

    override suspend fun listReceived(): List<ReceivedShare> {
        listReceivedCalls += 1
        listReceivedError?.let { throw it }
        return receivedShares
    }
}
```

- [ ] **Step 2: Compile-check the test source set**

```bash
./gradlew :androidApp:compileDebugUnitTestKotlin
```

Expected: compiles successfully. (Existing tests don't yet use the new fields; they'll just default to empty.)

- [ ] **Step 3: Commit**

```bash
git add androidApp/src/test/java/com/badmintontracker/android/testing/FakeSharesRepository.kt
git commit -m "test: extend FakeSharesRepository with listReceived support"
```

---

## Task 4: `ClipListViewModel` — fetch shares, attach `sharerEmail` to `MatchSummary`

**Files:**
- Modify: `androidApp/src/main/java/com/badmintontracker/android/cliplist/ClipListViewModel.kt`
- Modify: `androidApp/src/test/java/com/badmintontracker/android/cliplist/ClipListViewModelTest.kt`
- Modify: `androidApp/src/main/java/com/badmintontracker/android/AuthGate.kt`

Strategy:
- Add `sharerEmail: String?` to `MatchSummary` (default null).
- The view model holds a `sharerByVideoId: MutableStateFlow<Map<String, String>>` that is refreshed in `refresh()` alongside `clips.refresh()`.
- The `state` flow includes this StateFlow in its `combine`, so changes to either clips or sharer-map produce a fresh `ClipListState`.
- On failure of `sharesRepo.listReceived()`, the map is left as-is (empty on first load) and no error is surfaced to the screen.

- [ ] **Step 1: Write the failing tests**

Edit `androidApp/src/test/java/com/badmintontracker/android/cliplist/ClipListViewModelTest.kt`. Add these imports to the existing import block (the existing file already imports `FakeAuthRepository` and `FakeClipsRepository`; only add what's missing):

```kotlin
import com.badmintontracker.android.testing.FakeSharesRepository
import com.badmintontracker.shared.repo.ReceivedShare
```

Update every existing `ClipListViewModel(...)` construction in the file to pass a `FakeSharesRepository` as a third argument. The four call sites (line numbers from current file) become:

```kotlin
// init_triggers_refresh, line ~42:
ClipListViewModel(clips, FakeAuthRepository(), FakeSharesRepository())

// state_reflects_observed_clips, line ~50:
val vm = ClipListViewModel(clips, FakeAuthRepository(), FakeSharesRepository())

// state_partitions_owned_and_shared_matches, line ~69:
val vm = ClipListViewModel(clips, auth, FakeSharesRepository())

// refresh_failure_surfaces_in_error, line ~84:
val vm = ClipListViewModel(clips, FakeAuthRepository(), FakeSharesRepository())
```

Then add these two new tests at the bottom of the class (just before the closing `}`):

```kotlin
@Test
fun shared_matches_carry_sharer_email() = runTest {
    val clips = FakeClipsRepository().apply {
        this.clips.value = listOf(
            ownedClip("a", "v-own"),
            sharedClip("b", "v-shared"),
        )
    }
    val auth = FakeAuthRepository().apply { currentUserIdValue = "user-self" }
    val shares = FakeSharesRepository().apply {
        receivedShares = listOf(
            ReceivedShare(
                videoId = "v-shared",
                sharerEmail = "alice@example.com",
                sharedAt = Instant.parse("2026-05-07T10:00:00Z"),
            ),
        )
    }
    val vm = ClipListViewModel(clips, auth, shares)
    vm.state.test {
        var s = awaitItem()
        while (s.sharedMatches.isEmpty() || s.sharedMatches.first().sharerEmail == null) {
            s = awaitItem()
        }
        s.sharedMatches.first().sharerEmail shouldBe "alice@example.com"
        s.ownedMatches.first().sharerEmail shouldBe null
        cancelAndIgnoreRemainingEvents()
    }
}

@Test
fun listReceived_failure_does_not_surface_error_or_block_rows() = runTest {
    val clips = FakeClipsRepository().apply {
        this.clips.value = listOf(sharedClip("b", "v-shared"))
    }
    val auth = FakeAuthRepository().apply { currentUserIdValue = "user-self" }
    val shares = FakeSharesRepository().apply {
        listReceivedError = IllegalStateException("shares fetch failed")
    }
    val vm = ClipListViewModel(clips, auth, shares)
    vm.state.test {
        var s = awaitItem()
        while (s.sharedMatches.isEmpty()) s = awaitItem()
        s.sharedMatches.first().sharerEmail shouldBe null
        s.error shouldBe null
        cancelAndIgnoreRemainingEvents()
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
./gradlew :androidApp:testDebugUnitTest --tests "com.badmintontracker.android.cliplist.ClipListViewModelTest"
```

Expected: compilation failure on the new third argument to `ClipListViewModel(...)` (parameter does not exist) and on `MatchSummary.sharerEmail` (property does not exist).

- [ ] **Step 3: Update `MatchSummary` and `ClipListViewModel`**

Replace the contents of `androidApp/src/main/java/com/badmintontracker/android/cliplist/ClipListViewModel.kt` with:

```kotlin
package com.badmintontracker.android.cliplist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.badmintontracker.shared.model.RallyClip
import com.badmintontracker.shared.repo.AuthRepository
import com.badmintontracker.shared.repo.ClipsRepository
import com.badmintontracker.shared.repo.SharesRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant

data class MatchSummary(
    val videoId: String,
    val rallyCount: Int,
    val latestCreatedAt: Instant,
    val coverClip: RallyClip,
    val isOwned: Boolean,
    val sharerEmail: String? = null,
)

data class ClipListState(
    val clips: List<RallyClip> = emptyList(),
    val ownedMatches: List<MatchSummary> = emptyList(),
    val sharedMatches: List<MatchSummary> = emptyList(),
    val isRefreshing: Boolean = false,
    val error: String? = null,
)

private fun List<RallyClip>.toMatches(
    currentUserId: String?,
    sharerByVideoId: Map<String, String>,
): List<MatchSummary> =
    groupBy { it.videoId }
        .map { (videoId, list) ->
            val cover = list.minByOrNull { it.rallyIndex } ?: list.first()
            val owned = currentUserId != null && cover.ownerId == currentUserId
            MatchSummary(
                videoId = videoId,
                rallyCount = list.size,
                latestCreatedAt = list.maxOf { it.createdAt },
                coverClip = cover,
                isOwned = owned,
                sharerEmail = if (owned) null else sharerByVideoId[videoId],
            )
        }
        .sortedByDescending { it.latestCreatedAt }

class ClipListViewModel(
    private val clips: ClipsRepository,
    private val auth: AuthRepository,
    private val shares: SharesRepository,
) : ViewModel() {
    private val refreshing      = MutableStateFlow(true)
    private val errors          = MutableStateFlow<String?>(null)
    private val sharerByVideoId = MutableStateFlow<Map<String, String>>(emptyMap())

    val state = combine(
        clips.observeClips(),
        sharerByVideoId,
        refreshing,
        errors,
    ) { list, sharerMap, r, e ->
        val matches = list.toMatches(auth.currentUserId(), sharerMap)
        val (owned, shared) = matches.partition { it.isOwned }
        ClipListState(
            clips = list,
            ownedMatches = owned,
            sharedMatches = shared,
            isRefreshing = r,
            error = e,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ClipListState())

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            refreshing.value = true
            coroutineScope {
                val clipsJob = async {
                    runCatching { clips.refresh() }.onFailure { errors.value = it.message }
                }
                val sharesJob = async {
                    runCatching { shares.listReceived() }
                        .onSuccess { received ->
                            sharerByVideoId.value =
                                received.associate { it.videoId to it.sharerEmail }
                        }
                        // Soft failure: leave sharerByVideoId untouched, no user-facing error.
                }
                clipsJob.await()
                sharesJob.await()
            }
            refreshing.value = false
        }
    }

    fun signOut() = viewModelScope.launch { auth.signOut() }
    fun dismissError() { errors.value = null }
}
```

- [ ] **Step 4: Update DI wiring in `AuthGate.kt`**

In `androidApp/src/main/java/com/badmintontracker/android/AuthGate.kt`, both initializer blocks construct `ClipListViewModel(rally.clips, rally.auth)`. Update both call sites to pass `rally.shares`.

Edit lines 73 and 88. Each is:

```kotlin
initializer { ClipListViewModel(rally.clips, rally.auth) }
```

Change both to:

```kotlin
initializer { ClipListViewModel(rally.clips, rally.auth, rally.shares) }
```

- [ ] **Step 5: Run the view-model tests to verify they pass**

```bash
./gradlew :androidApp:testDebugUnitTest --tests "com.badmintontracker.android.cliplist.ClipListViewModelTest"
```

Expected: all tests in `ClipListViewModelTest` pass — the four pre-existing tests, plus the two new ones.

- [ ] **Step 6: Run the full Android unit-test suite**

```bash
./gradlew :androidApp:testDebugUnitTest
```

Expected: all Android unit tests pass. (No other test class constructs `ClipListViewModel`, so no further updates are needed.)

- [ ] **Step 7: Commit**

```bash
git add androidApp/src/main/java/com/badmintontracker/android/cliplist/ClipListViewModel.kt \
        androidApp/src/main/java/com/badmintontracker/android/AuthGate.kt \
        androidApp/src/test/java/com/badmintontracker/android/cliplist/ClipListViewModelTest.kt
git commit -m "feat(cliplist): attach sharer email to shared MatchSummary

ClipListViewModel now fetches list_received_match_shares in
parallel with clips.refresh() and exposes sharerEmail on each
shared MatchSummary. Listed-shares failure is soft: rows still
render without the email line and no error surfaces."
```

---

## Task 5: `ClipListScreen` — render "Shared by ..." line

**Files:**
- Modify: `androidApp/src/main/java/com/badmintontracker/android/cliplist/ClipListScreen.kt`

- [ ] **Step 1: Add the `TextOverflow` import**

In `androidApp/src/main/java/com/badmintontracker/android/cliplist/ClipListScreen.kt`, add this import alongside the other `androidx.compose.ui` imports:

```kotlin
import androidx.compose.ui.text.style.TextOverflow
```

- [ ] **Step 2: Render the conditional sharer line in `MatchRow`**

In `MatchRow` (lines 165–206), the inner `Column(Modifier.weight(1f)) { ... }` currently contains two `Text` composables (title and rally count). Add a third, after the rally-count `Text`, that conditionally renders the sharer email. The replacement `Column` block becomes:

```kotlin
Column(Modifier.weight(1f)) {
    Text(
        "Match · ${formatDate(match.latestCreatedAt)}",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onBackground,
    )
    Text(
        "${match.rallyCount} ${if (match.rallyCount == 1) "rally" else "rallies"}".uppercase(Locale.ROOT),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (match.sharerEmail != null) {
        Text(
            text = "Shared by ${match.sharerEmail}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
```

- [ ] **Step 3: Build the Android app**

```bash
./gradlew :androidApp:assembleDebug
```

Expected: build succeeds. No new warnings related to this change.

- [ ] **Step 4: Manually verify (smoke test)**

The user is the only owner of the build/install pipeline; the agent should not attempt to launch an emulator. Instead, verify by reading the modified file once and confirming visually:

1. The conditional `if (match.sharerEmail != null) { Text(...) }` is present inside `Column(Modifier.weight(1f))` in `MatchRow`.
2. The text reads `"Shared by ${match.sharerEmail}"`.
3. `maxLines = 1` and `overflow = TextOverflow.Ellipsis` are both set.
4. The owned-row code path is unchanged (the `if (onShareClick != null)` block at the row's end is still there).

If any of those is wrong, fix and re-run Step 3.

- [ ] **Step 5: Commit**

```bash
git add androidApp/src/main/java/com/badmintontracker/android/cliplist/ClipListScreen.kt
git commit -m "feat(cliplist): show 'Shared by <email>' under shared matches

Adds a third line to MatchRow that renders only for shared
matches, showing the sharer's email with ellipsis truncation."
```

---

## Task 6: Final verification

**Files:** none modified.

- [ ] **Step 1: Run all unit tests**

```bash
./gradlew :shared:jvmTest :androidApp:testDebugUnitTest
```

Expected: all tests pass.

- [ ] **Step 2: Build the Android debug APK**

```bash
./gradlew :androidApp:assembleDebug
```

Expected: build succeeds.

- [ ] **Step 3: Confirm git log**

```bash
git log --oneline -10
```

Expected: five new commits in order (migration, repository + test, fake update, view model + test + DI, screen). No unintended files changed.

- [ ] **Step 4: Hand off to the user for on-device verification**

Report back to the user with:

- The five commit hashes (one per task that produced a commit).
- A note that the migration must be applied to the remote Supabase project (`supabase db push`) before the feature works against production data — the local change alone is not enough.
- A request that the user open the app on a device, share a match from one account to another, and confirm the recipient sees the "Shared by …" line under the shared match in the "Shared with me" section.

---

## Notes for the implementing agent

- **Do not modify `RallyClip`.** The sharer email is a UI-grouping concern; it lives on `MatchSummary`, not on the clip.
- **Do not change the existing clip-fetch path.** The new RPC is additive and runs in parallel with `clips.refresh()`.
- **Do not surface share-fetch failures to the user.** The label is a soft enhancement; failure must leave `sharerEmail = null` and the row must render normally.
- **The `auth.users` join is intentionally inside a `security definer` function.** Do not attempt to query `auth.users` from the client.
