# Shareable matches — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Let an owner share a match with another registered Shuttl user (looked up by email). The recipient can view the match, its clips, and its annotations as read-only; the owner retains full edit rights and changes propagate via the same database rows.

**Architecture:** New `match_shares` table. Existing SELECT policies on `rally_clips`, `rally_annotations`, and the `clips` / `thumbnails` storage buckets are extended so recipients see shared rows / files. Three `security definer` RPCs (`share_match`, `unshare_match`, `list_match_shares`) handle email lookup and grant management without exposing `auth.users` to the client. Mobile app gains a "Share" sheet on owned matches and a "Shared with me" section on the matches list.

**Design doc:** `docs/plans/2026-05-06-shareable-matches-design.md`

**Tech Stack:** Supabase (Postgres + RLS + Storage), Kotlin Multiplatform (`shared` module), Jetpack Compose / Material 3 (Android), Ktor MockEngine + kotest + turbine (tests).

---

## Conventions used by this codebase (reference)

- **Migration filename**: `YYYYMMDDhhmmss_description.sql` under `supabase/migrations/`. Single file per feature.
- **Shared Kotlin tests**: `shared/src/commonTest/kotlin/...`, run via `./gradlew :shared:jvmTest` (or `:shared:allTests` for all targets).
- **Android tests**: `androidApp/src/test/java/...`, run via `./gradlew :androidApp:testDebugUnitTest`.
- **Repository test pattern**: Ktor `MockEngine` via `TestSupabase.client { request -> jsonResponse(...) }` (`shared/src/commonTest/kotlin/com/badmintontracker/shared/testing/SupabaseTestClient.kt`).
- **ViewModel test pattern**: `Fake*Repository` doubles in `androidApp/src/test/java/com/badmintontracker/android/testing/`, `StandardTestDispatcher` + `Dispatchers.setMain`, `turbine` for Flow assertions.
- **Existing RLS policies on `rally_clips` / `rally_annotations`** were created remotely (not in version control). The migration must use `drop policy if exists` defensively against likely names.

---

## Task 1: Migration — schema, RLS, storage policies, RPCs

**Files:**
- Create: `supabase/migrations/20260506000000_match_shares.sql`

**Step 1: Identify existing SELECT policies on `rally_clips` and `rally_annotations`**

We need to drop the existing owner-only SELECT policies so we can replace them with the extended ones. Their exact names are unknown (created via Supabase Studio).

Run from the project root:

```bash
supabase db remote query "select schemaname, tablename, policyname from pg_policies where schemaname = 'public' and tablename in ('rally_clips', 'rally_annotations') order by tablename, policyname;"
```

Record the exact policy names that handle SELECT (look at `cmd` if needed: `... and cmd = 'SELECT'`). The migration below uses `drop policy if exists` for these names plus the canonical names from the design doc — adjust the list if your project has policies named differently.

**Step 2: Write the migration file**

`supabase/migrations/20260506000000_match_shares.sql`:

```sql
-- Shareable matches.
-- See docs/plans/2026-05-06-shareable-matches-design.md

------------------------------------------------------------------
-- 1. match_shares table
------------------------------------------------------------------

create table if not exists public.match_shares (
    video_id            uuid        not null,
    shared_with_user_id uuid        not null references auth.users(id) on delete cascade,
    granted_by          uuid        not null references auth.users(id) on delete cascade,
    created_at          timestamptz not null default now(),
    primary key (video_id, shared_with_user_id),
    constraint no_self_share check (shared_with_user_id <> granted_by)
);

create index if not exists match_shares_recipient_idx
    on public.match_shares (shared_with_user_id);

alter table public.match_shares enable row level security;

drop policy if exists "shares: select own or received" on public.match_shares;
create policy "shares: select own or received"
    on public.match_shares for select to authenticated
    using (granted_by = auth.uid() or shared_with_user_id = auth.uid());

-- Intentionally no INSERT/UPDATE/DELETE policies: writes go through
-- SECURITY DEFINER RPCs only.

------------------------------------------------------------------
-- 2. Extend SELECT policies on rally_clips and rally_annotations
------------------------------------------------------------------

-- TODO: if your project's existing SELECT policies have different names,
-- add their names to the drop list below.
drop policy if exists "clips: select own"             on public.rally_clips;
drop policy if exists "rally_clips_select_own"        on public.rally_clips;
drop policy if exists "Enable read access for owner"  on public.rally_clips;
drop policy if exists "clips: select own or shared"   on public.rally_clips;

create policy "clips: select own or shared"
    on public.rally_clips for select to authenticated
    using (
        owner_id = auth.uid()
        or exists (
            select 1 from public.match_shares ms
            where ms.video_id = public.rally_clips.video_id
              and ms.shared_with_user_id = auth.uid()
        )
    );

drop policy if exists "annotations: select own"             on public.rally_annotations;
drop policy if exists "rally_annotations_select_own"        on public.rally_annotations;
drop policy if exists "Enable read access for owner"        on public.rally_annotations;
drop policy if exists "annotations: select own or shared"   on public.rally_annotations;

create policy "annotations: select own or shared"
    on public.rally_annotations for select to authenticated
    using (
        exists (
            select 1 from public.rally_clips rc
            where rc.id = public.rally_annotations.clip_id
              and (
                  rc.owner_id = auth.uid()
                  or exists (
                      select 1 from public.match_shares ms
                      where ms.video_id = rc.video_id
                        and ms.shared_with_user_id = auth.uid()
                  )
              )
        )
    );

------------------------------------------------------------------
-- 3. Storage policies for shared viewers
------------------------------------------------------------------

drop policy if exists "storage clips: shared viewers"      on storage.objects;
drop policy if exists "storage thumbnails: shared viewers" on storage.objects;

create policy "storage clips: shared viewers"
    on storage.objects for select to authenticated
    using (
        bucket_id = 'clips'
        and exists (
            select 1
              from public.rally_clips rc
              join public.match_shares ms on ms.video_id = rc.video_id
             where rc.clip_storage_path = storage.objects.name
               and ms.shared_with_user_id = auth.uid()
        )
    );

create policy "storage thumbnails: shared viewers"
    on storage.objects for select to authenticated
    using (
        bucket_id = 'thumbnails'
        and exists (
            select 1
              from public.rally_clips rc
              join public.match_shares ms on ms.video_id = rc.video_id
             where rc.thumbnail_storage_path = storage.objects.name
               and ms.shared_with_user_id = auth.uid()
        )
    );

------------------------------------------------------------------
-- 4. RPCs: share_match, unshare_match, list_match_shares
------------------------------------------------------------------

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

revoke all   on function public.share_match(uuid, text) from public;
grant execute on function public.share_match(uuid, text) to authenticated;

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

revoke all   on function public.unshare_match(uuid, uuid) from public;
grant execute on function public.unshare_match(uuid, uuid) to authenticated;

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

revoke all   on function public.list_match_shares(uuid) from public;
grant execute on function public.list_match_shares(uuid) to authenticated;
```

**Step 3: Apply the migration**

```bash
supabase db push
```

Expected: migration applied without error. If a `policy already exists` error appears for clips/annotations, the existing policy name wasn't covered by the `drop policy if exists` list — add it and re-run.

**Step 4: Verify policies in place**

```bash
supabase db remote query "select tablename, policyname from pg_policies where schemaname = 'public' and tablename in ('match_shares', 'rally_clips', 'rally_annotations') order by tablename, policyname;"
supabase db remote query "select policyname from pg_policies where schemaname = 'storage' and policyname like 'storage %: shared viewers';"
supabase db remote query "select proname from pg_proc where pronamespace = 'public'::regnamespace and proname in ('share_match','unshare_match','list_match_shares');"
```

Expected: all three RPCs present, four shared/owner SELECT policies present, two storage policies present.

**Step 5: Smoke-test `share_match` errors**

Sign in as user A in the Studio SQL editor (set `auth.uid()` via JWT), then:

```sql
select share_match('00000000-0000-0000-0000-000000000000', 'nobody@nowhere.test');
-- expected: ERROR (not_owner P0002 — the video doesn't exist for caller)

select share_match(<a real video_id owned by user A>, 'definitely-not-a-user@example.com');
-- expected: ERROR (no_such_user P0003)
```

This confirms the function and its grants work end-to-end.

**Step 6: Commit**

```bash
git add supabase/migrations/20260506000000_match_shares.sql
git commit -m "feat(supabase): match_shares table, RLS, storage policies, RPCs"
```

---

## Task 2: Add `ownerId` to `RallyClip`

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/badmintontracker/shared/model/RallyClip.kt`
- Modify: `shared/src/commonTest/kotlin/com/badmintontracker/shared/model/RallyClipSerializationTest.kt`
- Modify (test fixtures): `shared/src/commonTest/kotlin/com/badmintontracker/shared/repo/ClipsRepositoryTest.kt`, `androidApp/src/test/java/com/badmintontracker/android/cliplist/ClipListViewModelTest.kt`, any other test fixture constructing `RallyClip(...)`.

**Step 1: Update the failing serialization test**

Add `"owner_id": "..."` to both payloads in `RallyClipSerializationTest.kt`, and add an assertion in the first test:

```kotlin
val payload = """
    {
      "id": "11111111-1111-1111-1111-111111111111",
      "video_id": "22222222-2222-2222-2222-222222222222",
      "owner_id": "33333333-3333-3333-3333-333333333333",
      "rally_index": 7,
      ...
    }
""".trimIndent()
...
clip.ownerId shouldBe "33333333-3333-3333-3333-333333333333"
```

**Step 2: Run the test, expect a compile error or failure**

```bash
./gradlew :shared:jvmTest --tests "com.badmintontracker.shared.model.RallyClipSerializationTest"
```

Expected: compile error (`ownerId` unresolved) or test failure.

**Step 3: Add `ownerId` to the model**

`shared/src/commonMain/kotlin/com/badmintontracker/shared/model/RallyClip.kt`:

```kotlin
data class RallyClip(
    val id: String,
    @SerialName("video_id")               val videoId: String,
    @SerialName("owner_id")               val ownerId: String,   // NEW
    @SerialName("rally_index")            val rallyIndex: Int,
    ...
)
```

**Step 4: Update existing tests that construct `RallyClip(...)`**

Find every callsite and add `ownerId = "owner-1"` (or similar):

```bash
grep -rn "RallyClip(" shared/src/commonTest androidApp/src/test
```

Update each. Likely files:
- `shared/src/commonTest/kotlin/com/badmintontracker/shared/repo/ClipsRepositoryTest.kt` (also add `"owner_id"` to the JSON fixture string)
- `androidApp/src/test/java/com/badmintontracker/android/cliplist/ClipListViewModelTest.kt` (the `clip(id)` helper)
- Any other ViewModel test that builds a `RallyClip`.

**Step 5: Run all tests**

```bash
./gradlew :shared:jvmTest :androidApp:testDebugUnitTest
```

Expected: all green.

**Step 6: Commit**

```bash
git add shared/src/commonMain/kotlin/com/badmintontracker/shared/model/RallyClip.kt \
        shared/src/commonTest/kotlin/com/badmintontracker/shared/model/RallyClipSerializationTest.kt \
        shared/src/commonTest/kotlin/com/badmintontracker/shared/repo/ClipsRepositoryTest.kt \
        androidApp/src/test/java/com/badmintontracker/android/cliplist/ClipListViewModelTest.kt
git commit -m "feat(shared): RallyClip carries owner_id"
```

---

## Task 3: Expose `currentUserId()` on `AuthRepository`

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/badmintontracker/shared/repo/AuthRepository.kt`
- Modify: `shared/src/commonTest/kotlin/com/badmintontracker/shared/repo/AuthRepositoryTest.kt`
- Modify: `androidApp/src/test/java/com/badmintontracker/android/testing/FakeAuthRepository.kt`

**Step 1: Write a failing test for `currentUserId()`**

In `AuthRepositoryTest.kt`, add a test that asserts `currentUserId()` returns the id of the signed-in user (and `null` when not signed in). Mirror the existing test setup for that file (read it first to match the pattern). If the existing test file uses `TestSupabase.client { ... }` you'll mock `auth.currentUserOrNull()`. If the existing test uses a different pattern (e.g. integrating with the Supabase auth gotrue mock), follow that. If unclear, the simplest version is a contract test on the interface only: skip and move to step 3, then add unit coverage at the ViewModel layer instead.

**Step 2: Run the test, expect failure**

```bash
./gradlew :shared:jvmTest --tests "com.badmintontracker.shared.repo.AuthRepositoryTest"
```

**Step 3: Add `currentUserId()` to `AuthRepository`**

`shared/src/commonMain/kotlin/com/badmintontracker/shared/repo/AuthRepository.kt`:

```kotlin
interface AuthRepository {
    val sessionFlow: Flow<SessionStatus>
    fun currentUserId(): String?            // NEW
    suspend fun signInEmail(email: String, password: String): Result<Unit>
    suspend fun signInWithGoogle(): Result<Unit>
    suspend fun signOut(): Result<Unit>
}

class AuthRepositoryImpl(private val client: SupabaseClient) : AuthRepository {
    override val sessionFlow: Flow<SessionStatus> = client.auth.sessionStatus
    override fun currentUserId(): String? = client.auth.currentUserOrNull()?.id
    ...
}
```

**Step 4: Update `FakeAuthRepository`**

`androidApp/src/test/java/com/badmintontracker/android/testing/FakeAuthRepository.kt`:

```kotlin
class FakeAuthRepository : AuthRepository {
    val session = MutableStateFlow<SessionStatus>(SessionStatus.NotAuthenticated(false))
    override val sessionFlow = session
    var currentUserIdValue: String? = "user-self"   // NEW
    override fun currentUserId(): String? = currentUserIdValue
    ...
}
```

**Step 5: Run tests**

```bash
./gradlew :shared:jvmTest :androidApp:testDebugUnitTest
```

Expected: all green.

**Step 6: Commit**

```bash
git add shared/src/commonMain/kotlin/com/badmintontracker/shared/repo/AuthRepository.kt \
        shared/src/commonTest/kotlin/com/badmintontracker/shared/repo/AuthRepositoryTest.kt \
        androidApp/src/test/java/com/badmintontracker/android/testing/FakeAuthRepository.kt
git commit -m "feat(shared): AuthRepository.currentUserId()"
```

---

## Task 4: Add `MatchShare` model

**Files:**
- Create: `shared/src/commonMain/kotlin/com/badmintontracker/shared/model/MatchShare.kt`
- Create: `shared/src/commonTest/kotlin/com/badmintontracker/shared/model/MatchShareSerializationTest.kt`

**Step 1: Write the failing serialization test**

`shared/src/commonTest/kotlin/com/badmintontracker/shared/model/MatchShareSerializationTest.kt`:

```kotlin
package com.badmintontracker.shared.model

import io.kotest.matchers.shouldBe
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlin.test.Test

class MatchShareSerializationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun decodes_postgrest_payload() {
        val payload = """
            {
              "shared_with_user_id": "33333333-3333-3333-3333-333333333333",
              "email": "coach@example.com",
              "created_at": "2026-05-06T12:00:00Z"
            }
        """.trimIndent()

        val share = json.decodeFromString(MatchShare.serializer(), payload)

        share.sharedWithUserId shouldBe "33333333-3333-3333-3333-333333333333"
        share.email shouldBe "coach@example.com"
        share.createdAt shouldBe Instant.parse("2026-05-06T12:00:00Z")
    }
}
```

**Step 2: Run the test, expect compile error**

```bash
./gradlew :shared:jvmTest --tests "com.badmintontracker.shared.model.MatchShareSerializationTest"
```

**Step 3: Create the model**

`shared/src/commonMain/kotlin/com/badmintontracker/shared/model/MatchShare.kt`:

```kotlin
package com.badmintontracker.shared.model

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MatchShare(
    @SerialName("shared_with_user_id") val sharedWithUserId: String,
    val email: String,
    @SerialName("created_at")          val createdAt: Instant,
)
```

**Step 4: Run the test, expect pass**

```bash
./gradlew :shared:jvmTest --tests "com.badmintontracker.shared.model.MatchShareSerializationTest"
```

**Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/badmintontracker/shared/model/MatchShare.kt \
        shared/src/commonTest/kotlin/com/badmintontracker/shared/model/MatchShareSerializationTest.kt
git commit -m "feat(shared): MatchShare model"
```

---

## Task 5: Add `SharesRepository` + `ShareError`

**Files:**
- Create: `shared/src/commonMain/kotlin/com/badmintontracker/shared/repo/ShareError.kt`
- Create: `shared/src/commonMain/kotlin/com/badmintontracker/shared/repo/SharesRepository.kt`
- Create: `shared/src/commonTest/kotlin/com/badmintontracker/shared/repo/SharesRepositoryTest.kt`

**Step 1: Write the failing tests**

`shared/src/commonTest/kotlin/com/badmintontracker/shared/repo/SharesRepositoryTest.kt`:

```kotlin
package com.badmintontracker.shared.repo

import com.badmintontracker.shared.testing.TestSupabase
import com.badmintontracker.shared.testing.jsonResponse
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class SharesRepositoryTest {

    @Test
    fun share_calls_share_match_rpc_with_video_id_and_email() = runTest {
        var capturedUrl: String? = null
        var capturedBody: String? = null
        val client = TestSupabase.client { req ->
            capturedUrl = req.url.toString()
            capturedBody = (req.body as? TextContent)?.text
            jsonResponse("\"00000000-0000-0000-0000-000000000001\"")
        }
        val repo = SharesRepositoryImpl(client)

        val result = repo.share(videoId = "v1", email = "coach@example.com")

        result.isSuccess shouldBe true
        capturedUrl!!.shouldContain("/rest/v1/rpc/share_match")
        capturedBody!!.shouldContain("\"p_video_id\":\"v1\"")
        capturedBody!!.shouldContain("\"p_email\":\"coach@example.com\"")
    }

    @Test
    fun share_maps_no_such_user_error() = runTest {
        val client = TestSupabase.client { _ ->
            respondError(
                status = HttpStatusCode.BadRequest,
                content = """{"code":"P0003","message":"no_such_user"}""",
                headers = io.ktor.http.headersOf(io.ktor.http.HttpHeaders.ContentType, "application/json"),
            )
        }
        val repo = SharesRepositoryImpl(client)

        val result = repo.share("v1", "ghost@example.com")

        (result.exceptionOrNull() is ShareError.NoSuchUser) shouldBe true
    }

    @Test
    fun share_maps_not_owner_error() = runTest {
        val client = TestSupabase.client { _ ->
            respondError(
                status = HttpStatusCode.BadRequest,
                content = """{"code":"P0002","message":"not_owner"}""",
                headers = io.ktor.http.headersOf(io.ktor.http.HttpHeaders.ContentType, "application/json"),
            )
        }
        val result = SharesRepositoryImpl(client).share("v1", "x@y")
        (result.exceptionOrNull() is ShareError.NotOwner) shouldBe true
    }

    @Test
    fun share_maps_self_share_error() = runTest {
        val client = TestSupabase.client { _ ->
            respondError(
                status = HttpStatusCode.BadRequest,
                content = """{"code":"P0004","message":"cannot_share_with_self"}""",
                headers = io.ktor.http.headersOf(io.ktor.http.HttpHeaders.ContentType, "application/json"),
            )
        }
        val result = SharesRepositoryImpl(client).share("v1", "me@example.com")
        (result.exceptionOrNull() is ShareError.CannotShareSelf) shouldBe true
    }

    @Test
    fun listShares_decodes_rpc_response() = runTest {
        val client = TestSupabase.client { _ ->
            jsonResponse("""[
              {"shared_with_user_id":"u1","email":"a@x","created_at":"2026-05-06T12:00:00Z"},
              {"shared_with_user_id":"u2","email":"b@x","created_at":"2026-05-06T12:01:00Z"}
            ]""")
        }
        val result = SharesRepositoryImpl(client).listShares("v1")
        result.getOrThrow() shouldHaveSize 2
        result.getOrThrow()[0].email shouldBe "a@x"
    }

    @Test
    fun unshare_calls_unshare_match_rpc() = runTest {
        var capturedUrl: String? = null
        var capturedBody: String? = null
        val client = TestSupabase.client { req ->
            capturedUrl = req.url.toString()
            capturedBody = (req.body as? TextContent)?.text
            jsonResponse("null")
        }
        val result = SharesRepositoryImpl(client).unshare("v1", "u1")
        result.isSuccess shouldBe true
        capturedUrl!!.shouldContain("/rest/v1/rpc/unshare_match")
        capturedBody!!.shouldContain("\"p_video_id\":\"v1\"")
        capturedBody!!.shouldContain("\"p_user_id\":\"u1\"")
    }
}
```

**Step 2: Run, expect compile error**

```bash
./gradlew :shared:jvmTest --tests "com.badmintontracker.shared.repo.SharesRepositoryTest"
```

**Step 3: Implement `ShareError`**

`shared/src/commonMain/kotlin/com/badmintontracker/shared/repo/ShareError.kt`:

```kotlin
package com.badmintontracker.shared.repo

sealed class ShareError(message: String) : Exception(message) {
    data object NotOwner          : ShareError("not_owner")
    data object NoSuchUser        : ShareError("no_such_user")
    data object CannotShareSelf   : ShareError("cannot_share_with_self")
    data class  Unknown(val cause: Throwable) : ShareError(cause.message ?: "unknown")
}

internal fun Throwable.toShareError(): ShareError = when {
    this is ShareError                                    -> this
    message?.contains("not_owner")              == true  -> ShareError.NotOwner
    message?.contains("no_such_user")           == true  -> ShareError.NoSuchUser
    message?.contains("cannot_share_with_self") == true  -> ShareError.CannotShareSelf
    else                                                  -> ShareError.Unknown(this)
}
```

**Step 4: Implement `SharesRepository`**

`shared/src/commonMain/kotlin/com/badmintontracker/shared/repo/SharesRepository.kt`:

```kotlin
package com.badmintontracker.shared.repo

import com.badmintontracker.shared.model.MatchShare
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

interface SharesRepository {
    suspend fun share(videoId: String, email: String): Result<Unit>
    suspend fun unshare(videoId: String, userId: String): Result<Unit>
    suspend fun listShares(videoId: String): Result<List<MatchShare>>
}

class SharesRepositoryImpl(private val client: SupabaseClient) : SharesRepository {

    @Serializable private data class ShareArgs(
        @SerialName("p_video_id") val videoId: String,
        @SerialName("p_email")    val email: String,
    )
    @Serializable private data class UnshareArgs(
        @SerialName("p_video_id") val videoId: String,
        @SerialName("p_user_id")  val userId: String,
    )
    @Serializable private data class ListArgs(
        @SerialName("p_video_id") val videoId: String,
    )

    override suspend fun share(videoId: String, email: String): Result<Unit> = runCatching {
        client.postgrest.rpc("share_match", ShareArgs(videoId, email))
        Unit
    }.recoverCatching { throw it.toShareError() }

    override suspend fun unshare(videoId: String, userId: String): Result<Unit> = runCatching {
        client.postgrest.rpc("unshare_match", UnshareArgs(videoId, userId))
        Unit
    }

    override suspend fun listShares(videoId: String): Result<List<MatchShare>> = runCatching {
        client.postgrest.rpc("list_match_shares", ListArgs(videoId))
            .decodeList<MatchShare>()
    }
}
```

**Step 5: Wire `SharesRepository` into the app's DI**

Find where `ClipsRepositoryImpl(client)` etc. are constructed (likely a `ServiceLocator` / module in `androidApp`). Construct a `SharesRepositoryImpl(client)` alongside and expose it. Adjust the test for that DI module if one exists.

```bash
grep -rn "ClipsRepositoryImpl(" androidApp/src/main shared/src/commonMain
```

Add `SharesRepositoryImpl(client)` next to it.

**Step 6: Run tests**

```bash
./gradlew :shared:jvmTest :androidApp:testDebugUnitTest
```

Expected: all green.

**Step 7: Commit**

```bash
git add shared/src/commonMain/kotlin/com/badmintontracker/shared/repo/ShareError.kt \
        shared/src/commonMain/kotlin/com/badmintontracker/shared/repo/SharesRepository.kt \
        shared/src/commonTest/kotlin/com/badmintontracker/shared/repo/SharesRepositoryTest.kt \
        <DI file you modified>
git commit -m "feat(shared): SharesRepository with share/unshare/list RPCs"
```

---

## Task 6: Add `FakeSharesRepository` test double

**Files:**
- Create: `androidApp/src/test/java/com/badmintontracker/android/testing/FakeSharesRepository.kt`

**Step 1: Create the fake**

```kotlin
package com.badmintontracker.android.testing

import com.badmintontracker.shared.model.MatchShare
import com.badmintontracker.shared.repo.SharesRepository

class FakeSharesRepository : SharesRepository {
    val shareCalls   = mutableListOf<Pair<String, String>>()
    val unshareCalls = mutableListOf<Pair<String, String>>()
    var nextShareResult:   Result<Unit>             = Result.success(Unit)
    var nextUnshareResult: Result<Unit>             = Result.success(Unit)
    var sharesByVideo: Map<String, List<MatchShare>> = emptyMap()

    override suspend fun share(videoId: String, email: String): Result<Unit> {
        shareCalls += videoId to email
        return nextShareResult
    }
    override suspend fun unshare(videoId: String, userId: String): Result<Unit> {
        unshareCalls += videoId to userId
        return nextUnshareResult
    }
    override suspend fun listShares(videoId: String) =
        Result.success(sharesByVideo[videoId].orEmpty())
}
```

**Step 2: Run android tests to confirm nothing breaks**

```bash
./gradlew :androidApp:testDebugUnitTest
```

**Step 3: Commit**

```bash
git add androidApp/src/test/java/com/badmintontracker/android/testing/FakeSharesRepository.kt
git commit -m "test(android): FakeSharesRepository"
```

---

## Task 7: `ClipListViewModel` — partition into owned vs. shared matches

**Files:**
- Modify: `androidApp/src/main/java/com/badmintontracker/android/cliplist/ClipListViewModel.kt`
- Modify: `androidApp/src/test/java/com/badmintontracker/android/cliplist/ClipListViewModelTest.kt`

**Step 1: Write failing tests**

In `ClipListViewModelTest.kt` add:

```kotlin
private fun ownedClip(id: String, videoId: String) = clip(id).copy(videoId = videoId, ownerId = "user-self")
private fun sharedClip(id: String, videoId: String) = clip(id).copy(videoId = videoId, ownerId = "user-other")

@Test
fun state_partitions_owned_and_shared_matches() = runTest {
    val clips = FakeClipsRepository().apply {
        this.clips.value = listOf(
            ownedClip("a", "v-own"),
            sharedClip("b", "v-shared"),
            sharedClip("c", "v-shared"),
        )
    }
    val auth = FakeAuthRepository().apply { currentUserIdValue = "user-self" }
    val vm = ClipListViewModel(clips, auth)
    vm.state.test {
        var s = awaitItem()
        while (s.ownedMatches.isEmpty() && s.sharedMatches.isEmpty()) s = awaitItem()
        s.ownedMatches.map { it.videoId } shouldBe listOf("v-own")
        s.sharedMatches.map { it.videoId } shouldBe listOf("v-shared")
        s.ownedMatches.first().isOwned shouldBe true
        s.sharedMatches.first().isOwned shouldBe false
        cancelAndIgnoreRemainingEvents()
    }
}
```

Update the existing `clip(id)` helper in this test to set `ownerId = "user-self"` so existing tests still pass for owned matches.

**Step 2: Run tests, expect failure**

```bash
./gradlew :androidApp:testDebugUnitTest --tests "com.badmintontracker.android.cliplist.ClipListViewModelTest"
```

**Step 3: Update `MatchSummary` and the ViewModel**

`androidApp/src/main/java/com/badmintontracker/android/cliplist/ClipListViewModel.kt`:

```kotlin
data class MatchSummary(
    val videoId: String,
    val rallyCount: Int,
    val latestCreatedAt: Instant,
    val coverClip: RallyClip,
    val isOwned: Boolean,            // NEW
)

data class ClipListState(
    val clips: List<RallyClip> = emptyList(),
    val ownedMatches: List<MatchSummary> = emptyList(),    // RENAMED/SPLIT
    val sharedMatches: List<MatchSummary> = emptyList(),   // NEW
    val isRefreshing: Boolean = false,
    val error: String? = null,
)

private fun List<RallyClip>.toMatches(currentUserId: String?): List<MatchSummary> =
    groupBy { it.videoId }
        .map { (videoId, list) ->
            val cover = list.minByOrNull { it.rallyIndex } ?: list.first()
            MatchSummary(
                videoId = videoId,
                rallyCount = list.size,
                latestCreatedAt = list.maxOf { it.createdAt },
                coverClip = cover,
                isOwned = currentUserId != null && cover.ownerId == currentUserId,
            )
        }
        .sortedByDescending { it.latestCreatedAt }

class ClipListViewModel(
    private val clips: ClipsRepository,
    private val auth: AuthRepository,
) : ViewModel() {
    private val refreshing = MutableStateFlow(false)
    private val errors     = MutableStateFlow<String?>(null)

    val state = combine(clips.observeClips(), refreshing, errors) { list, r, e ->
        val matches = list.toMatches(auth.currentUserId())
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
    ...
}
```

**Step 4: Run tests, expect green**

```bash
./gradlew :androidApp:testDebugUnitTest
```

**Step 5: Commit**

```bash
git add androidApp/src/main/java/com/badmintontracker/android/cliplist/ClipListViewModel.kt \
        androidApp/src/test/java/com/badmintontracker/android/cliplist/ClipListViewModelTest.kt
git commit -m "feat(cliplist): partition matches into owned and shared"
```

---

## Task 8: `ClipListScreen` — render two sections

**Files:**
- Modify: `androidApp/src/main/java/com/badmintontracker/android/cliplist/ClipListScreen.kt`

**Step 1: Update the `LazyColumn` body**

Replace the current `items(state.matches, ...)` block with:

```kotlin
LazyColumn(modifier = Modifier.fillMaxSize()) {
    if (state.ownedMatches.isNotEmpty()) {
        item(key = "header-owned") { SectionHeader("My matches") }
        items(state.ownedMatches, key = { "owned-${it.videoId}" }) { match ->
            MatchRow(match, media, onClick = { onMatchClick(match) })
            HorizontalDivider()
        }
    }
    if (state.sharedMatches.isNotEmpty()) {
        item(key = "header-shared") { SectionHeader("Shared with me") }
        items(state.sharedMatches, key = { "shared-${it.videoId}" }) { match ->
            MatchRow(match, media, onClick = { onMatchClick(match) })
            HorizontalDivider()
        }
    }
}
```

Add a small composable:

```kotlin
@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text.uppercase(Locale.ROOT),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
}
```

Update the empty-state guard so it triggers only when **both** lists are empty:

```kotlin
if (state.ownedMatches.isEmpty() && state.sharedMatches.isEmpty() && !state.isRefreshing) { ... }
```

**Step 2: Build the app**

```bash
./gradlew :androidApp:assembleDebug
```

Expected: success.

**Step 3: Manual visual check**

Run on a device/emulator. Confirm: signed in as user with their own matches → see "My matches" section. Sign in as user who has no shares → no "Shared with me" header.

**Step 4: Commit**

```bash
git add androidApp/src/main/java/com/badmintontracker/android/cliplist/ClipListScreen.kt
git commit -m "feat(cliplist): two-section list (My matches / Shared with me)"
```

---

## Task 9: `ClipDetailViewModel` — expose `isOwner`, gate annotation actions

**Files:**
- Modify: `androidApp/src/main/java/com/badmintontracker/android/clipdetail/ClipDetailViewModel.kt`
- Modify: `androidApp/src/test/java/com/badmintontracker/android/clipdetail/ClipDetailViewModelTest.kt`

**Step 1: Read the current ViewModel and test to understand its shape**

```bash
cat androidApp/src/main/java/com/badmintontracker/android/clipdetail/ClipDetailViewModel.kt
cat androidApp/src/test/java/com/badmintontracker/android/clipdetail/ClipDetailViewModelTest.kt
```

**Step 2: Write failing tests**

Add to `ClipDetailViewModelTest.kt`:

```kotlin
@Test
fun isOwner_true_when_current_user_matches_clip_owner() = runTest {
    // Arrange clips repo to return a clip with ownerId = "user-self"
    // Arrange auth.currentUserIdValue = "user-self"
    // Build ViewModel, observe state, assert state.isOwner == true
}

@Test
fun isOwner_false_when_current_user_differs_from_clip_owner() = runTest {
    // Arrange clip ownerId = "user-other", auth.currentUserIdValue = "user-self"
    // Assert state.isOwner == false
}
```

(Match the existing test scaffolding — `StandardTestDispatcher`, `Dispatchers.setMain`, fake repos, turbine.)

**Step 3: Run, expect failure**

```bash
./gradlew :androidApp:testDebugUnitTest --tests "com.badmintontracker.android.clipdetail.ClipDetailViewModelTest"
```

**Step 4: Implement**

Inject `AuthRepository` into `ClipDetailViewModel` if it isn't already. Add `isOwner` to the state:

```kotlin
data class ClipDetailState(
    ...,
    val isOwner: Boolean = false,
)

// In whatever flow loads the clip:
val isOwner = clip.ownerId == auth.currentUserId()
```

Also: in `addAnnotation()` / `deleteAnnotation()`, early-return when `!isOwner` (defense in depth — RLS already blocks server-side):

```kotlin
fun addAnnotation(...) {
    if (!state.value.isOwner) return
    ...
}
```

**Step 5: Run tests, expect green**

```bash
./gradlew :androidApp:testDebugUnitTest
```

**Step 6: Commit**

```bash
git add androidApp/src/main/java/com/badmintontracker/android/clipdetail/ClipDetailViewModel.kt \
        androidApp/src/test/java/com/badmintontracker/android/clipdetail/ClipDetailViewModelTest.kt
git commit -m "feat(clipDetail): expose isOwner and gate annotation writes"
```

---

## Task 10: `ClipDetailScreen` — hide write affordances when `!isOwner`

**Files:**
- Modify: `androidApp/src/main/java/com/badmintontracker/android/clipdetail/ClipDetailScreen.kt`

**Step 1: Locate the FAB / "Add annotation" button and the swipe-to-delete in the annotation list**

```bash
grep -n "FloatingActionButton\|swipeToDismiss\|SwipeToDismiss\|deleteAnnotation\|AddAnnotation" \
    androidApp/src/main/java/com/badmintontracker/android/clipdetail/ClipDetailScreen.kt
```

**Step 2: Wrap each write affordance in an `if (state.isOwner)` guard**

- The "Add annotation" FAB / button: render only when `state.isOwner`.
- The annotation row's delete swipe: pass an `isDeletable = state.isOwner` flag to the row composable, and skip wrapping in `SwipeToDismissBox` (or pass an empty `onDelete`) when false.

**Step 3: Build the app**

```bash
./gradlew :androidApp:assembleDebug
```

**Step 4: Manual check**

Run on device. Open an owned clip → see Add and delete affordances. Open a shared clip (after Task 11 lets you share one) → no Add button, no delete swipe. (You can simulate by temporarily hardcoding `state.isOwner = false` if you don't yet have a shared match; remove the hack before commit.)

**Step 5: Commit**

```bash
git add androidApp/src/main/java/com/badmintontracker/android/clipdetail/ClipDetailScreen.kt
git commit -m "feat(clipDetail): hide write affordances for shared (read-only) viewers"
```

---

## Task 11: `ShareSheetViewModel`

**Files:**
- Create: `androidApp/src/main/java/com/badmintontracker/android/share/ShareSheetViewModel.kt`
- Create: `androidApp/src/test/java/com/badmintontracker/android/share/ShareSheetViewModelTest.kt`

**Step 1: Write failing tests**

`ShareSheetViewModelTest.kt`:

```kotlin
package com.badmintontracker.android.share

import app.cash.turbine.test
import com.badmintontracker.android.testing.FakeSharesRepository
import com.badmintontracker.shared.model.MatchShare
import com.badmintontracker.shared.repo.ShareError
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

class ShareSheetViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @BeforeTest fun setMain() = Dispatchers.setMain(dispatcher)
    @AfterTest  fun resetMain() = Dispatchers.resetMain()

    @Test
    fun init_loads_existing_recipients() = runTest {
        val shares = FakeSharesRepository().apply {
            sharesByVideo = mapOf("v1" to listOf(
                MatchShare("u1", "a@x", Instant.parse("2026-05-06T12:00:00Z")),
            ))
        }
        val vm = ShareSheetViewModel(videoId = "v1", shares = shares)
        advanceUntilIdle()
        vm.state.value.recipients.map { it.email } shouldBe listOf("a@x")
    }

    @Test
    fun share_success_clears_email_and_refreshes_list() = runTest {
        val shares = FakeSharesRepository()
        val vm = ShareSheetViewModel("v1", shares)
        vm.onEmailChange("coach@example.com")
        shares.sharesByVideo = mapOf("v1" to listOf(
            MatchShare("u1", "coach@example.com", Instant.parse("2026-05-06T12:00:00Z")),
        ))
        vm.onShareClicked()
        advanceUntilIdle()
        vm.state.value.email shouldBe ""
        vm.state.value.error shouldBe null
        vm.state.value.recipients.map { it.email } shouldBe listOf("coach@example.com")
        shares.shareCalls shouldBe listOf("v1" to "coach@example.com")
    }

    @Test
    fun share_no_such_user_sets_user_facing_error() = runTest {
        val shares = FakeSharesRepository().apply {
            nextShareResult = Result.failure(ShareError.NoSuchUser)
        }
        val vm = ShareSheetViewModel("v1", shares)
        vm.onEmailChange("ghost@example.com")
        vm.onShareClicked()
        advanceUntilIdle()
        vm.state.value.error shouldBe "No Shuttl user found with that email."
    }

    @Test
    fun unshare_calls_repo_and_refreshes() = runTest {
        val shares = FakeSharesRepository().apply {
            sharesByVideo = mapOf("v1" to listOf(
                MatchShare("u1", "a@x", Instant.parse("2026-05-06T12:00:00Z")),
            ))
        }
        val vm = ShareSheetViewModel("v1", shares)
        advanceUntilIdle()
        shares.sharesByVideo = mapOf("v1" to emptyList())
        vm.onUnshare("u1")
        advanceUntilIdle()
        shares.unshareCalls shouldBe listOf("v1" to "u1")
        vm.state.value.recipients shouldBe emptyList()
    }
}
```

**Step 2: Run, expect failure**

```bash
./gradlew :androidApp:testDebugUnitTest --tests "com.badmintontracker.android.share.ShareSheetViewModelTest"
```

**Step 3: Implement the ViewModel**

`ShareSheetViewModel.kt`:

```kotlin
package com.badmintontracker.android.share

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.badmintontracker.shared.model.MatchShare
import com.badmintontracker.shared.repo.ShareError
import com.badmintontracker.shared.repo.SharesRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ShareSheetState(
    val email: String = "",
    val recipients: List<MatchShare> = emptyList(),
    val isBusy: Boolean = false,
    val error: String? = null,
)

class ShareSheetViewModel(
    private val videoId: String,
    private val shares: SharesRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ShareSheetState())
    val state = _state.asStateFlow()

    init { refresh() }

    fun onEmailChange(value: String) { _state.value = _state.value.copy(email = value, error = null) }

    fun onShareClicked() {
        val email = _state.value.email.trim()
        if (email.isEmpty()) return
        _state.value = _state.value.copy(isBusy = true, error = null)
        viewModelScope.launch {
            shares.share(videoId, email)
                .onSuccess {
                    _state.value = _state.value.copy(email = "", isBusy = false)
                    refresh()
                }
                .onFailure { e ->
                    _state.value = _state.value.copy(isBusy = false, error = (e as? ShareError).toMessage())
                }
        }
    }

    fun onUnshare(userId: String) {
        viewModelScope.launch {
            shares.unshare(videoId, userId).onSuccess { refresh() }
        }
    }

    private fun refresh() {
        viewModelScope.launch {
            shares.listShares(videoId).onSuccess {
                _state.value = _state.value.copy(recipients = it)
            }
        }
    }
}

private fun ShareError?.toMessage(): String = when (this) {
    ShareError.NotOwner        -> "You can only share matches you uploaded."
    ShareError.NoSuchUser      -> "No Shuttl user found with that email."
    ShareError.CannotShareSelf -> "You can't share a match with yourself."
    is ShareError.Unknown      -> "Could not share — please try again."
    null                       -> "Unknown error."
}
```

**Step 4: Run tests, expect green**

```bash
./gradlew :androidApp:testDebugUnitTest
```

**Step 5: Commit**

```bash
git add androidApp/src/main/java/com/badmintontracker/android/share/ShareSheetViewModel.kt \
        androidApp/src/test/java/com/badmintontracker/android/share/ShareSheetViewModelTest.kt
git commit -m "feat(share): ShareSheetViewModel with error mapping and refresh"
```

---

## Task 12: `MatchClipsScreen` — Share button + ShareSheet UI

**Files:**
- Modify: `androidApp/src/main/java/com/badmintontracker/android/cliplist/MatchClipsScreen.kt`
- Create: `androidApp/src/main/java/com/badmintontracker/android/share/ShareSheet.kt`

**Step 1: Read the current MatchClipsScreen to see how it gets the match's `isOwned`**

```bash
cat androidApp/src/main/java/com/badmintontracker/android/cliplist/MatchClipsScreen.kt
```

If it doesn't currently know `isOwned`, derive it the same way the ViewModel does: compare the loaded clip list's `ownerId` against `AuthRepository.currentUserId()`. The simplest path is to add an `isOwned: Boolean` to the screen's state (computed in its ViewModel) — mirror the change made in Task 7 for `ClipListViewModel`.

**Step 2: Add the Share action**

In the `TopAppBar.actions` block, add (only when `state.isOwned`):

```kotlin
var sheetOpen by remember { mutableStateOf(false) }
if (state.isOwned) {
    IconButton(onClick = { sheetOpen = true }) {
        Icon(Icons.Default.PersonAdd, contentDescription = "Share match")
    }
}
if (sheetOpen) {
    ShareSheet(
        videoId = state.videoId,
        sharesRepository = sharesRepository,        // pass in via screen params/DI
        onDismiss = { sheetOpen = false },
    )
}
```

**Step 3: Implement `ShareSheet`**

`androidApp/src/main/java/com/badmintontracker/android/share/ShareSheet.kt`:

```kotlin
package com.badmintontracker.android.share

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.badmintontracker.shared.repo.SharesRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareSheet(
    videoId: String,
    sharesRepository: SharesRepository,
    onDismiss: () -> Unit,
) {
    val vm: ShareSheetViewModel = viewModel(key = "share-$videoId") {
        ShareSheetViewModel(videoId, sharesRepository)
    }
    val state by vm.state.collectAsStateWithLifecycle()

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(16.dp)) {
            Text("Share match", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = state.email,
                onValueChange = vm::onEmailChange,
                label = { Text("Email") },
                singleLine = true,
                isError = state.error != null,
                supportingText = { state.error?.let { Text(it) } },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = vm::onShareClicked,
                enabled = !state.isBusy && state.email.isNotBlank(),
                modifier = Modifier.align(Alignment.End),
            ) { Text("Share") }
            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Text(
                "People with access",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(vertical = 12.dp),
            )
            state.recipients.forEach { r ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(r.email, modifier = Modifier.weight(1f))
                    IconButton(onClick = { vm.onUnshare(r.sharedWithUserId) }) {
                        Icon(Icons.Default.Close, contentDescription = "Remove access")
                    }
                }
            }
        }
    }
}
```

**Step 4: Pass `SharesRepository` to `MatchClipsScreen`**

Update the navigation graph / DI so `MatchClipsScreen` receives the `SharesRepository`. Match the pattern used for `MediaRepository`.

**Step 5: Build the app**

```bash
./gradlew :androidApp:assembleDebug
```

**Step 6: Manual check**

Open an owned match → tap share icon → bottom sheet opens with email field. Enter a real registered user's email → "Share" → snackbar/text confirmation, recipient appears in list. Enter a non-existent email → "No Shuttl user found with that email." appears as supporting text. Open a shared (non-owned) match → no share icon.

**Step 7: Commit**

```bash
git add androidApp/src/main/java/com/badmintontracker/android/cliplist/MatchClipsScreen.kt \
        androidApp/src/main/java/com/badmintontracker/android/share/ShareSheet.kt \
        <DI / nav graph file you modified>
git commit -m "feat(share): MatchClipsScreen share button + ShareSheet bottom-sheet"
```

---

## Task 13: End-to-end manual smoke test

**Step 1: On two devices (or one device + one emulator), sign in as user A and user B**

A and B should both have working Shuttl accounts with confirmed emails.

**Step 2: A shares a match with B**

- A: open the app → My matches → tap a match → top-right Share icon → enter B's email → Share.
- A: confirm B's email appears in the "People with access" list.

**Step 3: B sees the shared match**

- B: open the app → pull to refresh on the matches list.
- Verify a "Shared with me" section appears with A's match.

**Step 4: B opens the shared match**

- B: tap the match → rally list shows the same rallies as A sees.
- Verify the **share icon is absent** in the top app bar.
- Tap a rally → ClipDetail loads, video plays.
- Verify **no "Add annotation" button** is rendered.
- Verify swipe-to-delete on existing annotations does **not** show a delete affordance (or is disabled).

**Step 5: A adds a new annotation; B sees it after refresh**

- A: open same rally → add annotation "Smash, line was good" at 5s.
- B: pull to refresh on the same rally screen → annotation appears.

**Step 6: A revokes the share**

- A: top-right Share icon → "Remove" next to B's email.
- B: pull to refresh on the matches list → "Shared with me" section disappears (or the match disappears from it).
- B: if they had the rally screen still open and try to fetch — request returns empty / 401-equivalent (RLS will simply return zero rows on the next select).

**Step 7: Negative checks**

- A tries to share with their own email → "You can't share a match with yourself."
- A tries to share with `not-a-real-user@example.com` → "No Shuttl user found with that email."

**Step 8: No commit — manual verification only**

Document any deviations as follow-up issues. If everything passes, the feature is complete.

---

## Wrap-up

**Step 1: Run the full test suite once more**

```bash
./gradlew :shared:allTests :androidApp:testDebugUnitTest :androidApp:assembleDebug
```

Expected: all green.

**Step 2: Verify all commits are present**

```bash
git log --oneline main..HEAD
```

You should have ~12 commits, one per Task 1–12.

**Step 3: Use superpowers:finishing-a-development-branch to decide how to integrate.**
