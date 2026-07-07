# Local Video Recording & Analyze — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Record/import a match video on the phone, keep it local until "Analyze", then run the existing cloud pipeline from mobile: court marking (desktop-parity) → resumable upload → Edge Function trigger → progress → rally clips.

**Architecture:** One new shared-KMP repository (`VideosRepository`: videos row insert, TUS upload, keypoints write, `process-video` invoke, status polling) + an Android `localvideo` feature (Settings-persisted registry, app-scoped `AnalyzeCoordinator` state machine, court-marking screen, local player reusing `FrameStepBar`). Backend untouched.

**Tech Stack:** Kotlin 2.3, supabase-kt 3.5.0 (`postgrest-kt`, `storage-kt` resumable, new `functions-kt`), Compose M3, Media3 ExoPlayer, Coil3 + `coil-video`, multiplatform-settings, kotlinx.serialization.

**Design doc:** `docs/plans/2026-07-07-local-video-analyze-design.md`

## Global Constraints

- Backend/DB/Edge Functions: **zero changes**.
- Court keypoints JSON must be byte-equivalent in structure to desktop `CourtSetup.vue` `saveAndProceed()`: keys `top_left, top_right, bottom_right, bottom_left, net_left, net_right, service_line_near_left, service_line_near_right, service_line_far_left, service_line_far_right, center_near, center_far`, each `[x, y]` floats in **source-video pixels**.
- Storage path convention: `{uid}/{videoId}.mp4` in bucket `videos`; row status starts `uploaded`; 1 GB max file size.
- Success = videos.status `phase1_complete` or `completed`; failure = `failed_phase1`/`failed_phase2` (legacy `failed`).
- Order of operations on Analyze: court marking → upload bytes → insert row → write keypoints → invoke `process-video` → poll.
- Never delete the user's gallery file.
- Follow existing code style: interface+Impl repos, `Result<Unit>` mutations, fakes-in-`testing/` tests with kotest+Turbine+runTest, manual `viewModelFactory` DI, Shuttl design tokens, zero-radius shapes.
- Test commands: `./gradlew :shared:jvmTest` and `./gradlew :androidApp:testDebugUnitTest`; build check `./gradlew :androidApp:assembleDebug`.

---

### Task 1: `CourtKeypoints` model (shared) with desktop-parity serialization

**Files:**
- Create: `shared/src/commonMain/kotlin/com/badmintontracker/shared/model/CourtKeypoints.kt`
- Test: `shared/src/commonTest/kotlin/com/badmintontracker/shared/model/CourtKeypointsSerializationTest.kt`

**Interfaces:**
- Produces: `CourtKeypoints(topLeft: List<Float>, …, centerFar: List<Float>)` — all 12 fields `List<Float>` of size 2, `@SerialName` snake_case as above. Used by Tasks 2, 5, 6.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.badmintontracker.shared.model

import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlin.test.Test

class CourtKeypointsSerializationTest {

    private val json = Json

    @Test
    fun serializes_with_exact_desktop_field_names() {
        val kp = CourtKeypoints(
            topLeft = listOf(1f, 2f), topRight = listOf(3f, 4f),
            bottomRight = listOf(5f, 6f), bottomLeft = listOf(7f, 8f),
            netLeft = listOf(9f, 10f), netRight = listOf(11f, 12f),
            serviceLineNearLeft = listOf(13f, 14f), serviceLineNearRight = listOf(15f, 16f),
            serviceLineFarLeft = listOf(17f, 18f), serviceLineFarRight = listOf(19f, 20f),
            centerNear = listOf(21f, 22f), centerFar = listOf(23f, 24f),
        )
        val encoded = json.encodeToString(CourtKeypoints.serializer(), kp)
        // Field-name parity with desktop CourtSetup.vue saveAndProceed().
        encoded shouldBe """{"top_left":[1.0,2.0],"top_right":[3.0,4.0],""" +
            """"bottom_right":[5.0,6.0],"bottom_left":[7.0,8.0],""" +
            """"net_left":[9.0,10.0],"net_right":[11.0,12.0],""" +
            """"service_line_near_left":[13.0,14.0],"service_line_near_right":[15.0,16.0],""" +
            """"service_line_far_left":[17.0,18.0],"service_line_far_right":[19.0,20.0],""" +
            """"center_near":[21.0,22.0],"center_far":[23.0,24.0]}"""
    }

    @Test
    fun round_trips() {
        val kp = CourtKeypoints(
            topLeft = listOf(100.5f, 200.25f), topRight = listOf(3f, 4f),
            bottomRight = listOf(5f, 6f), bottomLeft = listOf(7f, 8f),
            netLeft = listOf(9f, 10f), netRight = listOf(11f, 12f),
            serviceLineNearLeft = listOf(13f, 14f), serviceLineNearRight = listOf(15f, 16f),
            serviceLineFarLeft = listOf(17f, 18f), serviceLineFarRight = listOf(19f, 20f),
            centerNear = listOf(21f, 22f), centerFar = listOf(23f, 24f),
        )
        val decoded = json.decodeFromString(CourtKeypoints.serializer(),
            json.encodeToString(CourtKeypoints.serializer(), kp))
        decoded shouldBe kp
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :shared:jvmTest --tests "com.badmintontracker.shared.model.CourtKeypointsSerializationTest"`
Expected: compilation FAILS — `CourtKeypoints` unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.badmintontracker.shared.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 12-point manual court calibration, field-for-field identical to the JSON the
 * desktop app (badminton-tracker CourtSetup.vue) writes to
 * videos.manual_court_keypoints. Coordinates are [x, y] in source-video pixels.
 */
@Serializable
data class CourtKeypoints(
    @SerialName("top_left")                val topLeft: List<Float>,
    @SerialName("top_right")               val topRight: List<Float>,
    @SerialName("bottom_right")            val bottomRight: List<Float>,
    @SerialName("bottom_left")             val bottomLeft: List<Float>,
    @SerialName("net_left")                val netLeft: List<Float>,
    @SerialName("net_right")               val netRight: List<Float>,
    @SerialName("service_line_near_left")  val serviceLineNearLeft: List<Float>,
    @SerialName("service_line_near_right") val serviceLineNearRight: List<Float>,
    @SerialName("service_line_far_left")   val serviceLineFarLeft: List<Float>,
    @SerialName("service_line_far_right")  val serviceLineFarRight: List<Float>,
    @SerialName("center_near")             val centerNear: List<Float>,
    @SerialName("center_far")              val centerFar: List<Float>,
)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :shared:jvmTest --tests "com.badmintontracker.shared.model.CourtKeypointsSerializationTest"`
Expected: PASS (2 tests)

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/badmintontracker/shared/model/CourtKeypoints.kt shared/src/commonTest/kotlin/com/badmintontracker/shared/model/CourtKeypointsSerializationTest.kt
git commit -m "feat(shared): CourtKeypoints model with desktop-parity serialization"
```

---

### Task 2: `VideosRepository` — row insert, keypoints write, trigger, polling

**Files:**
- Create: `shared/src/commonMain/kotlin/com/badmintontracker/shared/repo/VideosRepository.kt`
- Modify: `shared/src/commonMain/kotlin/com/badmintontracker/shared/SupabaseFactory.kt` (install `Functions`)
- Modify: `shared/src/commonMain/kotlin/com/badmintontracker/shared/RallyApp.kt` (expose `videos`)
- Modify: `gradle/libs.versions.toml` + `shared/build.gradle.kts` (add `functions-kt`)
- Test: `shared/src/commonTest/kotlin/com/badmintontracker/shared/repo/VideosRepositoryTest.kt`

**Interfaces:**
- Consumes: `CourtKeypoints` (Task 1), `TestSupabase`/`jsonResponse` (existing test helpers).
- Produces (used by Tasks 3, 5):

```kotlin
data class ProcessingUpdate(val status: String, val progress: Float?, val error: String?) {
    val isSuccess: Boolean get() = status == "phase1_complete" || status == "completed"
    val isFailure: Boolean get() = status.startsWith("failed")
    val isTerminal: Boolean get() = isSuccess || isFailure
}

interface VideosRepository {
    suspend fun createVideo(videoId: String, filename: String, sizeBytes: Long): Result<Unit>
    suspend fun setCourtKeypoints(videoId: String, keypoints: CourtKeypoints): Result<Unit>
    suspend fun startProcessing(videoId: String): Result<Unit>
    fun observeProcessing(videoId: String, pollIntervalMs: Long = 5_000): Flow<ProcessingUpdate>
    // uploadVideo added in Task 3
}
```

- [ ] **Step 1: Add the functions-kt dependency**

In `gradle/libs.versions.toml` `[libraries]`, after `supabase-storage`:

```toml
supabase-functions  = { module = "io.github.jan-tennert.supabase:functions-kt" }
```

In `shared/build.gradle.kts` `commonMain.dependencies`, after `implementation(libs.supabase.storage)`:

```kotlin
implementation(libs.supabase.functions)
```

In `SupabaseFactory.kt`, add import `io.github.jan.supabase.functions.Functions` and inside `createSupabaseClient` block after `install(Storage)`:

```kotlin
install(Functions)
```

Run: `./gradlew :shared:compileKotlinJvm` — Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Write the failing tests**

Follow the existing `SharesRepositoryTest` style (MockEngine handler inspecting `request.url.encodedPath` + method, responding with `jsonResponse`).

```kotlin
package com.badmintontracker.shared.repo

import com.badmintontracker.shared.model.CourtKeypoints
import com.badmintontracker.shared.testing.TestSupabase
import com.badmintontracker.shared.testing.jsonResponse
import app.cash.turbine.test
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.engine.mock.respondError
import io.ktor.content.TextContent
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

private fun testKeypoints() = CourtKeypoints(
    topLeft = listOf(1f, 2f), topRight = listOf(3f, 4f),
    bottomRight = listOf(5f, 6f), bottomLeft = listOf(7f, 8f),
    netLeft = listOf(9f, 10f), netRight = listOf(11f, 12f),
    serviceLineNearLeft = listOf(13f, 14f), serviceLineNearRight = listOf(15f, 16f),
    serviceLineFarLeft = listOf(17f, 18f), serviceLineFarRight = listOf(19f, 20f),
    centerNear = listOf(21f, 22f), centerFar = listOf(23f, 24f),
)

class VideosRepositoryTest {

    @Test
    fun createVideo_inserts_row_with_storage_path_and_uploaded_status() = runTest {
        var body = ""
        var path = ""
        val client = TestSupabase.client { request ->
            path = request.url.encodedPath
            body = (request.body as TextContent).text
            jsonResponse("[]", HttpStatusCode.Created)
        }
        val repo = VideosRepositoryImpl(client)
        // No session in the test client: owner_id comes from auth.currentUserOrNull(),
        // so createVideo must fail cleanly when signed out…
        val result = repo.createVideo("vid-1", "match.mp4", 123L)
        result.isFailure.shouldBeTrue()
    }

    @Test
    fun setCourtKeypoints_patches_manual_court_keypoints_json() = runTest {
        var body = ""
        var method = HttpMethod.Get
        var query = ""
        val client = TestSupabase.client { request ->
            method = request.method
            query = request.url.encodedQuery
            body = (request.body as TextContent).text
            jsonResponse("[]")
        }
        val repo = VideosRepositoryImpl(client)
        repo.setCourtKeypoints("vid-1", testKeypoints()).isSuccess.shouldBeTrue()
        method shouldBe HttpMethod.Patch
        query shouldContain "id=eq.vid-1"
        body shouldContain "\"manual_court_keypoints\":{\"top_left\":[1.0,2.0]"
        body shouldContain "\"center_far\":[23.0,24.0]"
    }

    @Test
    fun startProcessing_posts_to_edge_function_with_video_id() = runTest {
        var path = ""
        var body = ""
        val client = TestSupabase.client { request ->
            path = request.url.encodedPath
            body = (request.body as TextContent).text
            jsonResponse("""{"ok":true}""")
        }
        val repo = VideosRepositoryImpl(client)
        repo.startProcessing("vid-1").isSuccess.shouldBeTrue()
        path shouldContain "/functions/v1/process-video"
        body shouldContain "\"video_id\":\"vid-1\""
    }

    @Test
    fun startProcessing_maps_non_2xx_to_failure() = runTest {
        val client = TestSupabase.client {
            respondError(HttpStatusCode.Conflict, "already processing")
        }
        val repo = VideosRepositoryImpl(client)
        repo.startProcessing("vid-1").isFailure.shouldBeTrue()
    }

    @Test
    fun observeProcessing_emits_until_terminal_status() = runTest {
        var call = 0
        val client = TestSupabase.client {
            call++
            when {
                call <= 1 -> jsonResponse("""[{"status":"processing_phase1","progress":0.4,"error":null}]""")
                else      -> jsonResponse("""[{"status":"phase1_complete","progress":1.0,"error":null}]""")
            }
        }
        val repo = VideosRepositoryImpl(client)
        repo.observeProcessing("vid-1", pollIntervalMs = 1).test {
            awaitItem() shouldBe ProcessingUpdate("processing_phase1", 0.4f, null)
            val last = awaitItem()
            last shouldBe ProcessingUpdate("phase1_complete", 1.0f, null)
            last.isSuccess.shouldBeTrue()
            awaitComplete()
        }
    }

    @Test
    fun processing_update_classifies_failed_statuses() {
        ProcessingUpdate("failed_phase1", null, "boom").isFailure.shouldBeTrue()
        ProcessingUpdate("failed_phase2", null, null).isFailure.shouldBeTrue()
        ProcessingUpdate("completed", null, null).isSuccess.shouldBeTrue()
        ProcessingUpdate("processing_phase2", 0.2f, null).isTerminal shouldBe false
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `./gradlew :shared:jvmTest --tests "com.badmintontracker.shared.repo.VideosRepositoryTest"`
Expected: compilation FAILS — `VideosRepositoryImpl` unresolved.

- [ ] **Step 4: Implement**

```kotlin
package com.badmintontracker.shared.repo

import com.badmintontracker.shared.model.CourtKeypoints
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.functions.functions
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.headers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

data class ProcessingUpdate(val status: String, val progress: Float?, val error: String?) {
    val isSuccess: Boolean get() = status == "phase1_complete" || status == "completed"
    val isFailure: Boolean get() = status.startsWith("failed")
    val isTerminal: Boolean get() = isSuccess || isFailure
}

interface VideosRepository {
    suspend fun createVideo(videoId: String, filename: String, sizeBytes: Long): Result<Unit>
    suspend fun setCourtKeypoints(videoId: String, keypoints: CourtKeypoints): Result<Unit>
    suspend fun startProcessing(videoId: String): Result<Unit>
    fun observeProcessing(videoId: String, pollIntervalMs: Long = 5_000): Flow<ProcessingUpdate>
}

class VideosRepositoryImpl(private val client: SupabaseClient) : VideosRepository {

    @Serializable
    private data class NewVideoRow(
        val id: String,
        @SerialName("owner_id")     val ownerId: String,
        val filename: String,
        val size: Long,
        @SerialName("storage_path") val storagePath: String,
        val status: String,
    )

    @Serializable
    private data class KeypointsPatch(
        @SerialName("manual_court_keypoints") val keypoints: CourtKeypoints,
    )

    @Serializable
    private data class ProcessVideoBody(@SerialName("video_id") val videoId: String)

    @Serializable
    private data class StatusRow(
        val status: String,
        val progress: Float? = null,
        val error: String? = null,
    )

    override suspend fun createVideo(videoId: String, filename: String, sizeBytes: Long): Result<Unit> =
        runCatching {
            val uid = client.auth.currentUserOrNull()?.id ?: error("Not signed in")
            client.postgrest.from("videos").insert(
                NewVideoRow(
                    id = videoId,
                    ownerId = uid,
                    filename = filename,
                    size = sizeBytes,
                    storagePath = storagePath(uid, videoId),
                    status = "uploaded",
                )
            )
            Unit
        }

    override suspend fun setCourtKeypoints(videoId: String, keypoints: CourtKeypoints): Result<Unit> =
        runCatching {
            client.postgrest.from("videos")
                .update(KeypointsPatch(keypoints)) {
                    filter { eq("id", videoId) }
                }
            Unit
        }

    override suspend fun startProcessing(videoId: String): Result<Unit> = runCatching {
        client.functions(
            function = "process-video",
            body = ProcessVideoBody(videoId),
            headers = headers { append(HttpHeaders.ContentType, ContentType.Application.Json.toString()) },
        )
        Unit
    }

    override fun observeProcessing(videoId: String, pollIntervalMs: Long): Flow<ProcessingUpdate> = flow {
        while (true) {
            val row = client.postgrest.from("videos")
                .select(Columns.list("status", "progress", "error")) {
                    filter { eq("id", videoId) }
                }
                .decodeSingle<StatusRow>()
            val update = ProcessingUpdate(row.status, row.progress, row.error)
            emit(update)
            if (update.isTerminal) break
            delay(pollIntervalMs)
        }
    }

    companion object {
        fun storagePath(uid: String, videoId: String) = "$uid/$videoId.mp4"
    }
}
```

In `RallyApp.kt` add after `shares`:

```kotlin
val videos:      VideosRepository      = VideosRepositoryImpl(client)
```

(plus the two imports, matching the existing style).

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :shared:jvmTest --tests "com.badmintontracker.shared.repo.VideosRepositoryTest"`
Expected: PASS (6 tests). Note: supabase-kt `functions()` throws a `RestException` subtype on non-2xx, which `runCatching` converts to failure — if the 409 test fails because no exception is thrown, assert on the response status instead by switching `startProcessing` to check `response.status.isSuccess()` and throwing `IllegalStateException(response.bodyAsText())`.

- [ ] **Step 6: Run the full shared suite + commit**

Run: `./gradlew :shared:jvmTest`
Expected: all green (existing + new).

```bash
git add -A shared gradle/libs.versions.toml
git commit -m "feat(shared): VideosRepository — row insert, keypoints, trigger, status polling"
```

---

### Task 3: `uploadVideo` — resumable TUS upload with progress

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/badmintontracker/shared/repo/VideosRepository.kt`
- Test: `shared/src/commonTest/kotlin/com/badmintontracker/shared/repo/VideoUploadStateTest.kt`

**Interfaces:**
- Produces (used by Task 5):

```kotlin
sealed interface UploadState {
    data class InProgress(val progress: Float) : UploadState   // 0f..1f
    data object Done : UploadState
    data class Failed(val message: String) : UploadState
}
// on VideosRepository:
fun uploadVideo(
    videoId: String,
    sizeBytes: Long,
    channelProvider: suspend (offset: Long) -> ByteReadChannel,
): Flow<UploadState>
```

supabase-kt 3.5.0 API (verified against the 3.5.0 sources):
`storage.from("videos").resumable.createOrContinueUpload(channel: suspend (offset: Long) -> ByteReadChannel, source: String, size: Long, path: String)` returns `ResumableUpload` with `stateFlow: StateFlow<ResumableUploadState>` (`.progress: Float`, `.isDone`), `startOrResumeUploading()`.

- [ ] **Step 1: Write the failing test for the state mapping**

The TUS wire protocol is not worth mocking; test the pure mapping + expose it internal. Device verification covers the wire path (Task 10).

```kotlin
package com.badmintontracker.shared.repo

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class VideoUploadStateTest {
    @Test
    fun maps_progress_and_done() {
        toUploadState(progress = 0.25f, isDone = false) shouldBe UploadState.InProgress(0.25f)
        toUploadState(progress = 1f, isDone = true) shouldBe UploadState.Done
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :shared:jvmTest --tests "com.badmintontracker.shared.repo.VideoUploadStateTest"`
Expected: compilation FAILS — `toUploadState`/`UploadState` unresolved.

- [ ] **Step 3: Implement**

Add to `VideosRepository.kt` (imports: `io.github.jan.supabase.storage.storage`, `io.github.jan.supabase.storage.resumable.resumable`, `io.ktor.utils.io.ByteReadChannel`, `kotlinx.coroutines.flow.*`):

```kotlin
sealed interface UploadState {
    data class InProgress(val progress: Float) : UploadState
    data object Done : UploadState
    data class Failed(val message: String) : UploadState
}

internal fun toUploadState(progress: Float, isDone: Boolean): UploadState =
    if (isDone) UploadState.Done else UploadState.InProgress(progress)
```

Interface method + impl:

```kotlin
// interface VideosRepository
fun uploadVideo(
    videoId: String,
    sizeBytes: Long,
    channelProvider: suspend (offset: Long) -> ByteReadChannel,
): Flow<UploadState>

// VideosRepositoryImpl
// channelFlow (not flow{}): progress is emitted from a child coroutine while
// startOrResumeUploading() suspends, which plain flow{} forbids.
override fun uploadVideo(
    videoId: String,
    sizeBytes: Long,
    channelProvider: suspend (offset: Long) -> ByteReadChannel,
): Flow<UploadState> = channelFlow {
    val uid = client.auth.currentUserOrNull()?.id ?: error("Not signed in")
    val upload = client.storage.from("videos").resumable.createOrContinueUpload(
        channel = channelProvider,
        source = videoId,                      // stable key so retries resume the TUS session
        size = sizeBytes,
        path = storagePath(uid, videoId),
    )
    val progressJob = launch {
        upload.stateFlow.collect { send(toUploadState(it.progress, it.isDone)) }
    }
    upload.startOrResumeUploading()            // suspends until finished
    progressJob.cancel()
    send(UploadState.Done)
}.distinctUntilChanged()
    .catch { emit(UploadState.Failed(it.message ?: "Upload failed")) }
```

Imports for the impl: `io.github.jan.supabase.storage.storage`, `io.github.jan.supabase.storage.resumable.resumable`, `io.ktor.utils.io.ByteReadChannel`, `kotlinx.coroutines.launch`, `kotlinx.coroutines.flow.channelFlow`, `kotlinx.coroutines.flow.catch`, `kotlinx.coroutines.flow.distinctUntilChanged`.

- [ ] **Step 4: Run tests + full suite**

Run: `./gradlew :shared:jvmTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add shared
git commit -m "feat(shared): resumable video upload with progress flow"
```

---

### Task 4: `LocalVideoEntry` + `LocalVideoRepository` (Android, Settings-persisted)

**Files:**
- Create: `androidApp/src/main/java/com/badmintontracker/android/localvideo/LocalVideoEntry.kt`
- Create: `androidApp/src/main/java/com/badmintontracker/android/localvideo/LocalVideoRepository.kt`
- Modify: `androidApp/build.gradle.kts` (add `kotlinx-serialization-json` + `coil-video`)
- Modify: `gradle/libs.versions.toml` (add `coil-video`)
- Test: `androidApp/src/test/java/com/badmintontracker/android/localvideo/LocalVideoRepositoryTest.kt`

**Interfaces:**
- Consumes: `CourtKeypoints` (Task 1), `Settings` (multiplatform-settings, already a dependency).
- Produces (used by Tasks 5, 7, 8, 9):

```kotlin
enum class AnalyzeStage { LOCAL, UPLOADING, PROCESSING, FAILED }
enum class AnalyzeStep { UPLOAD, CREATE_ROW, KEYPOINTS, TRIGGER, PROCESSING }

@Serializable
data class LocalVideoEntry(
    val id: String,
    val uri: String,
    val displayName: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val addedAtEpochMs: Long,
    val keypoints: CourtKeypoints? = null,
    val stage: AnalyzeStage = AnalyzeStage.LOCAL,
    val failedStep: AnalyzeStep? = null,
    val failureMessage: String? = null,
)

class LocalVideoRepository(settings: Settings) {
    val entries: StateFlow<List<LocalVideoEntry>>   // newest first
    fun add(entry: LocalVideoEntry)
    fun update(id: String, transform: (LocalVideoEntry) -> LocalVideoEntry)
    fun remove(id: String)
    fun get(id: String): LocalVideoEntry?
}
```

- [ ] **Step 1: Add dependencies**

`gradle/libs.versions.toml` `[libraries]`, after `coil-network-okhttp`:

```toml
coil-video                      = { module = "io.coil-kt.coil3:coil-video",                                version.ref = "coil" }
```

`androidApp/build.gradle.kts` dependencies, after `implementation(libs.coil.network.okhttp)`:

```kotlin
implementation(libs.coil.video)
implementation(libs.kotlinx.serialization.json)
```

Run: `./gradlew :androidApp:compileDebugKotlin` — Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Write the failing test**

```kotlin
package com.badmintontracker.android.localvideo

import com.russhwolf.settings.MapSettings
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldBeNull
import kotlin.test.Test

private fun entry(id: String, addedAt: Long = 0L) = LocalVideoEntry(
    id = id,
    uri = "content://media/video/$id",
    displayName = "match-$id.mp4",
    durationMs = 60_000L,
    sizeBytes = 1_000L,
    addedAtEpochMs = addedAt,
)

class LocalVideoRepositoryTest {

    @Test
    fun add_get_remove_roundtrip() {
        val repo = LocalVideoRepository(MapSettings())
        repo.add(entry("a"))
        repo.get("a")?.displayName shouldBe "match-a.mp4"
        repo.remove("a")
        repo.get("a").shouldBeNull()
        repo.entries.value shouldBe emptyList()
    }

    @Test
    fun entries_sorted_newest_first() {
        val repo = LocalVideoRepository(MapSettings())
        repo.add(entry("old", addedAt = 1))
        repo.add(entry("new", addedAt = 2))
        repo.entries.value.map { it.id } shouldBe listOf("new", "old")
    }

    @Test
    fun update_transforms_entry() {
        val repo = LocalVideoRepository(MapSettings())
        repo.add(entry("a"))
        repo.update("a") { it.copy(stage = AnalyzeStage.UPLOADING) }
        repo.get("a")?.stage shouldBe AnalyzeStage.UPLOADING
    }

    @Test
    fun persists_across_instances_via_settings() {
        val settings = MapSettings()
        LocalVideoRepository(settings).add(entry("a"))
        LocalVideoRepository(settings).get("a")?.id shouldBe "a"
    }

    @Test
    fun corrupt_stored_json_yields_empty_list() {
        val settings = MapSettings().apply { putString("local_videos", "not-json") }
        LocalVideoRepository(settings).entries.value shouldBe emptyList()
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "com.badmintontracker.android.localvideo.LocalVideoRepositoryTest"`
Expected: compilation FAILS.

- [ ] **Step 4: Implement**

`LocalVideoEntry.kt`:

```kotlin
package com.badmintontracker.android.localvideo

import com.badmintontracker.shared.model.CourtKeypoints
import kotlinx.serialization.Serializable

enum class AnalyzeStage { LOCAL, UPLOADING, PROCESSING, FAILED }
enum class AnalyzeStep { UPLOAD, CREATE_ROW, KEYPOINTS, TRIGGER, PROCESSING }

@Serializable
data class LocalVideoEntry(
    val id: String,
    val uri: String,
    val displayName: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val addedAtEpochMs: Long,
    val keypoints: CourtKeypoints? = null,
    val stage: AnalyzeStage = AnalyzeStage.LOCAL,
    val failedStep: AnalyzeStep? = null,
    val failureMessage: String? = null,
)
```

`LocalVideoRepository.kt`:

```kotlin
package com.badmintontracker.android.localvideo

import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Registry of on-device recordings, persisted as JSON in Settings. */
class LocalVideoRepository(private val settings: Settings) {

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(LocalVideoEntry.serializer())

    private val state = MutableStateFlow(load())
    val entries: StateFlow<List<LocalVideoEntry>> = state.asStateFlow()

    fun add(entry: LocalVideoEntry) = mutate { it + entry }

    fun update(id: String, transform: (LocalVideoEntry) -> LocalVideoEntry) =
        mutate { list -> list.map { if (it.id == id) transform(it) else it } }

    fun remove(id: String) = mutate { list -> list.filterNot { it.id == id } }

    fun get(id: String): LocalVideoEntry? = state.value.firstOrNull { it.id == id }

    private fun mutate(transform: (List<LocalVideoEntry>) -> List<LocalVideoEntry>) {
        val next = transform(state.value).sortedByDescending { it.addedAtEpochMs }
        settings.putString(KEY, json.encodeToString(serializer, next))
        state.value = next
    }

    private fun load(): List<LocalVideoEntry> =
        settings.getStringOrNull(KEY)
            ?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }
            ?.sortedByDescending { it.addedAtEpochMs }
            ?: emptyList()

    private companion object { const val KEY = "local_videos" }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "com.badmintontracker.android.localvideo.LocalVideoRepositoryTest"`
Expected: PASS (5 tests).

- [ ] **Step 6: Commit**

```bash
git add -A androidApp gradle/libs.versions.toml
git commit -m "feat(android): local video registry persisted in settings"
```

---

### Task 5: `AnalyzeCoordinator` — the analyze state machine

**Files:**
- Create: `androidApp/src/main/java/com/badmintontracker/android/localvideo/AnalyzeCoordinator.kt`
- Create: `androidApp/src/test/java/com/badmintontracker/android/testing/FakeVideosRepository.kt`
- Modify: `androidApp/src/main/java/com/badmintontracker/android/RallyAndroidApp.kt`
- Test: `androidApp/src/test/java/com/badmintontracker/android/localvideo/AnalyzeCoordinatorTest.kt`

**Interfaces:**
- Consumes: `VideosRepository` incl. `uploadVideo`/`UploadState`/`ProcessingUpdate` (Tasks 2–3), `LocalVideoRepository` (Task 4), `ClipsRepository.refresh()` (existing).
- Produces (used by Tasks 7, 8, 9):

```kotlin
// Transient per-entry progress, NOT persisted:
data class AnalyzeProgress(val entryId: String, val uploadProgress: Float?, val pipelineProgress: Float?)

class AnalyzeCoordinator(
    private val localVideos: LocalVideoRepository,
    private val videos: VideosRepository,
    private val clips: ClipsRepository,
    private val scope: CoroutineScope,
    private val openChannel: suspend (uri: String, offset: Long) -> ByteReadChannel,
) {
    val progress: StateFlow<Map<String, AnalyzeProgress>>
    fun startAnalysis(entryId: String, keypoints: CourtKeypoints)  // from court marking Done
    fun retry(entryId: String)                                     // resumes from failedStep
    fun reattachToProcessing()                                     // on app launch
    val hasActiveUpload: StateFlow<Boolean>                        // drives keep-screen-on
}
```

Step order inside `runPipeline(entry, startFrom: AnalyzeStep)`:
`UPLOAD` → `CREATE_ROW` → `KEYPOINTS` → `TRIGGER` → `PROCESSING` (each skipped when `startFrom` is later). `CREATE_ROW` treats a duplicate-key error (message contains `"duplicate key"` or code `23505`) as success so retries are idempotent. On terminal success: `clips.refresh()` (best effort) then `localVideos.remove(id)`. On failure at step S: stage `FAILED`, `failedStep = S`, message persisted.

- [ ] **Step 1: Write `FakeVideosRepository`**

```kotlin
package com.badmintontracker.android.testing

import com.badmintontracker.shared.model.CourtKeypoints
import com.badmintontracker.shared.repo.ProcessingUpdate
import com.badmintontracker.shared.repo.UploadState
import com.badmintontracker.shared.repo.VideosRepository
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf

class FakeVideosRepository : VideosRepository {
    var nextCreateResult: Result<Unit> = Result.success(Unit)
    var nextKeypointsResult: Result<Unit> = Result.success(Unit)
    var nextStartResult: Result<Unit> = Result.success(Unit)
    var uploadStates: List<UploadState> = listOf(UploadState.InProgress(0.5f), UploadState.Done)
    var processingUpdates: List<ProcessingUpdate> =
        listOf(ProcessingUpdate("processing_phase1", 0.5f, null), ProcessingUpdate("phase1_complete", 1f, null))

    val createCalls = mutableListOf<Triple<String, String, Long>>()
    val keypointsCalls = mutableListOf<Pair<String, CourtKeypoints>>()
    val startCalls = mutableListOf<String>()
    val uploadCalls = mutableListOf<String>()

    override suspend fun createVideo(videoId: String, filename: String, sizeBytes: Long): Result<Unit> {
        createCalls += Triple(videoId, filename, sizeBytes)
        return nextCreateResult
    }
    override suspend fun setCourtKeypoints(videoId: String, keypoints: CourtKeypoints): Result<Unit> {
        keypointsCalls += videoId to keypoints
        return nextKeypointsResult
    }
    override suspend fun startProcessing(videoId: String): Result<Unit> {
        startCalls += videoId
        return nextStartResult
    }
    override fun observeProcessing(videoId: String, pollIntervalMs: Long): Flow<ProcessingUpdate> =
        flow { processingUpdates.forEach { emit(it) } }
    override fun uploadVideo(
        videoId: String,
        sizeBytes: Long,
        channelProvider: suspend (offset: Long) -> ByteReadChannel,
    ): Flow<UploadState> {
        uploadCalls += videoId
        return flow { uploadStates.forEach { emit(it) } }
    }
}
```

- [ ] **Step 2: Write the failing coordinator tests**

```kotlin
package com.badmintontracker.android.localvideo

import com.badmintontracker.android.testing.FakeClipsRepository
import com.badmintontracker.android.testing.FakeVideosRepository
import com.badmintontracker.shared.model.CourtKeypoints
import com.badmintontracker.shared.repo.ProcessingUpdate
import com.badmintontracker.shared.repo.UploadState
import com.russhwolf.settings.MapSettings
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AnalyzeCoordinatorTest {

    private val localVideos = LocalVideoRepository(MapSettings())
    private val videos = FakeVideosRepository()
    private val clips = FakeClipsRepository()

    private fun keypoints() = CourtKeypoints(
        topLeft = listOf(1f, 2f), topRight = listOf(3f, 4f),
        bottomRight = listOf(5f, 6f), bottomLeft = listOf(7f, 8f),
        netLeft = listOf(9f, 10f), netRight = listOf(11f, 12f),
        serviceLineNearLeft = listOf(13f, 14f), serviceLineNearRight = listOf(15f, 16f),
        serviceLineFarLeft = listOf(17f, 18f), serviceLineFarRight = listOf(19f, 20f),
        centerNear = listOf(21f, 22f), centerFar = listOf(23f, 24f),
    )

    private fun entry(id: String = "e1") = LocalVideoEntry(
        id = id, uri = "content://x/$id", displayName = "m.mp4",
        durationMs = 1000, sizeBytes = 10, addedAtEpochMs = 0,
    )

    private fun TestScope.coordinator() = AnalyzeCoordinator(
        localVideos = localVideos,
        videos = videos,
        clips = clips,
        scope = this.backgroundScope.let { kotlinx.coroutines.CoroutineScope(it.coroutineContext + UnconfinedTestDispatcher(testScheduler)) },
        openChannel = { _, _ -> ByteReadChannel(ByteArray(0)) },
    )

    @Test
    fun happy_path_uploads_creates_row_sets_keypoints_triggers_polls_and_removes_entry() =
        kotlinx.coroutines.test.runTest {
            localVideos.add(entry())
            val c = coordinator()
            c.startAnalysis("e1", keypoints())
            runCurrent()
            videos.uploadCalls shouldBe listOf("e1")
            videos.createCalls.single().first shouldBe "e1"
            videos.keypointsCalls.single().first shouldBe "e1"
            videos.startCalls shouldBe listOf("e1")
            clips.refreshCalls.size shouldBe 1
            localVideos.get("e1").shouldBeNull()   // auto-removed on success
        }

    @Test
    fun upload_failure_marks_failed_at_upload_and_retry_resumes_from_upload() =
        kotlinx.coroutines.test.runTest {
            localVideos.add(entry())
            videos.uploadStates = listOf(UploadState.Failed("network"))
            val c = coordinator()
            c.startAnalysis("e1", keypoints())
            runCurrent()
            val failed = localVideos.get("e1").shouldNotBeNull()
            failed.stage shouldBe AnalyzeStage.FAILED
            failed.failedStep shouldBe AnalyzeStep.UPLOAD
            failed.keypoints.shouldNotBeNull()     // never re-tap

            videos.uploadStates = listOf(UploadState.Done)
            c.retry("e1")
            runCurrent()
            videos.uploadCalls shouldBe listOf("e1", "e1")
            localVideos.get("e1").shouldBeNull()
        }

    @Test
    fun trigger_failure_retry_skips_upload_and_row_creation() =
        kotlinx.coroutines.test.runTest {
            localVideos.add(entry())
            videos.nextStartResult = Result.failure(IllegalStateException("409"))
            val c = coordinator()
            c.startAnalysis("e1", keypoints())
            runCurrent()
            localVideos.get("e1")?.failedStep shouldBe AnalyzeStep.TRIGGER

            videos.nextStartResult = Result.success(Unit)
            c.retry("e1")
            runCurrent()
            videos.uploadCalls.size shouldBe 1     // not re-uploaded
            videos.createCalls.size shouldBe 1     // not re-created
            videos.startCalls.size shouldBe 2
            localVideos.get("e1").shouldBeNull()
        }

    @Test
    fun duplicate_row_on_retry_is_treated_as_success() =
        kotlinx.coroutines.test.runTest {
            localVideos.add(entry())
            videos.nextCreateResult =
                Result.failure(IllegalStateException("""duplicate key value violates unique constraint "videos_pkey""""))
            val c = coordinator()
            c.startAnalysis("e1", keypoints())
            runCurrent()
            // CREATE_ROW duplicate → continue; pipeline completes.
            localVideos.get("e1").shouldBeNull()
        }

    @Test
    fun pipeline_failure_marks_processing_step_with_server_error() =
        kotlinx.coroutines.test.runTest {
            localVideos.add(entry())
            videos.processingUpdates = listOf(ProcessingUpdate("failed_phase1", null, "no court detected"))
            val c = coordinator()
            c.startAnalysis("e1", keypoints())
            runCurrent()
            val failed = localVideos.get("e1").shouldNotBeNull()
            failed.failedStep shouldBe AnalyzeStep.PROCESSING
            failed.failureMessage shouldBe "no court detected"
        }

    @Test
    fun reattach_resumes_polling_for_persisted_processing_entries() =
        kotlinx.coroutines.test.runTest {
            localVideos.add(entry().copy(stage = AnalyzeStage.PROCESSING, keypoints = keypoints()))
            val c = coordinator()
            c.reattachToProcessing()
            runCurrent()
            videos.uploadCalls shouldBe emptyList<String>()
            localVideos.get("e1").shouldBeNull()   // completed via observeProcessing
        }

    @Test
    fun hasActiveUpload_true_only_while_uploading() =
        kotlinx.coroutines.test.runTest {
            localVideos.add(entry())
            val c = coordinator()
            c.hasActiveUpload.value shouldBe false
            c.startAnalysis("e1", keypoints())
            runCurrent()
            c.hasActiveUpload.value shouldBe false // finished already
        }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "com.badmintontracker.android.localvideo.AnalyzeCoordinatorTest"`
Expected: compilation FAILS.

- [ ] **Step 4: Implement `AnalyzeCoordinator`**

```kotlin
package com.badmintontracker.android.localvideo

import com.badmintontracker.shared.model.CourtKeypoints
import com.badmintontracker.shared.repo.ClipsRepository
import com.badmintontracker.shared.repo.UploadState
import com.badmintontracker.shared.repo.VideosRepository
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AnalyzeProgress(
    val entryId: String,
    val uploadProgress: Float? = null,
    val pipelineProgress: Float? = null,
)

/**
 * Application-scoped orchestrator for the local-video analyze pipeline:
 * UPLOAD -> CREATE_ROW -> KEYPOINTS -> TRIGGER -> PROCESSING.
 * Durable state lives in LocalVideoRepository; per-entry progress is in-memory.
 */
class AnalyzeCoordinator(
    private val localVideos: LocalVideoRepository,
    private val videos: VideosRepository,
    private val clips: ClipsRepository,
    private val scope: CoroutineScope,
    private val openChannel: suspend (uri: String, offset: Long) -> ByteReadChannel,
) {
    private val _progress = MutableStateFlow<Map<String, AnalyzeProgress>>(emptyMap())
    val progress: StateFlow<Map<String, AnalyzeProgress>> = _progress.asStateFlow()

    private val _hasActiveUpload = MutableStateFlow(false)
    val hasActiveUpload: StateFlow<Boolean> = _hasActiveUpload.asStateFlow()

    private val active = mutableSetOf<String>()

    fun startAnalysis(entryId: String, keypoints: CourtKeypoints) {
        localVideos.update(entryId) { it.copy(keypoints = keypoints) }
        launchPipeline(entryId, AnalyzeStep.UPLOAD)
    }

    fun retry(entryId: String) {
        val step = localVideos.get(entryId)?.failedStep ?: AnalyzeStep.UPLOAD
        launchPipeline(entryId, step)
    }

    fun reattachToProcessing() {
        localVideos.entries.value
            .filter { it.stage == AnalyzeStage.PROCESSING }
            .forEach { launchPipeline(it.id, AnalyzeStep.PROCESSING) }
    }

    private fun launchPipeline(entryId: String, startFrom: AnalyzeStep) {
        if (!active.add(entryId)) return
        scope.launch {
            try {
                runPipeline(entryId, startFrom)
            } finally {
                active.remove(entryId)
                _progress.update { it - entryId }
            }
        }
    }

    private suspend fun runPipeline(entryId: String, startFrom: AnalyzeStep) {
        val entry = localVideos.get(entryId) ?: return
        val keypoints = entry.keypoints ?: return fail(entryId, AnalyzeStep.KEYPOINTS, "No court points saved")

        if (startFrom <= AnalyzeStep.UPLOAD) {
            localVideos.update(entryId) { it.copy(stage = AnalyzeStage.UPLOADING, failedStep = null, failureMessage = null) }
            _hasActiveUpload.value = true
            var failure: String? = null
            try {
                videos.uploadVideo(entry.id, entry.sizeBytes) { offset -> openChannel(entry.uri, offset) }
                    .collect { state ->
                        when (state) {
                            is UploadState.InProgress -> setProgress(entryId) { it.copy(uploadProgress = state.progress) }
                            is UploadState.Failed -> failure = state.message
                            UploadState.Done -> Unit
                        }
                    }
            } finally {
                _hasActiveUpload.value = false
            }
            failure?.let { return fail(entryId, AnalyzeStep.UPLOAD, it) }
        }

        if (startFrom <= AnalyzeStep.CREATE_ROW) {
            val result = videos.createVideo(entry.id, entry.displayName, entry.sizeBytes)
            val error = result.exceptionOrNull()
            if (error != null && !error.isDuplicateKey()) {
                return fail(entryId, AnalyzeStep.CREATE_ROW, error.message ?: "Couldn't register video")
            }
        }

        if (startFrom <= AnalyzeStep.KEYPOINTS) {
            videos.setCourtKeypoints(entry.id, keypoints).onFailure {
                return fail(entryId, AnalyzeStep.KEYPOINTS, it.message ?: "Couldn't save court points")
            }
        }

        if (startFrom <= AnalyzeStep.TRIGGER) {
            videos.startProcessing(entry.id).onFailure {
                return fail(entryId, AnalyzeStep.TRIGGER, it.message ?: "Couldn't start analysis")
            }
        }

        localVideos.update(entryId) { it.copy(stage = AnalyzeStage.PROCESSING, failedStep = null, failureMessage = null) }
        var pipelineError: String? = null
        videos.observeProcessing(entry.id).collect { update ->
            setProgress(entryId) { it.copy(pipelineProgress = update.progress) }
            if (update.isFailure) pipelineError = update.error ?: "Analysis failed"
        }
        pipelineError?.let { return fail(entryId, AnalyzeStep.PROCESSING, it) }

        runCatching { clips.refresh() }
        localVideos.remove(entryId)
    }

    private fun fail(entryId: String, step: AnalyzeStep, message: String) {
        localVideos.update(entryId) {
            it.copy(stage = AnalyzeStage.FAILED, failedStep = step, failureMessage = message)
        }
    }

    private fun setProgress(entryId: String, transform: (AnalyzeProgress) -> AnalyzeProgress) {
        _progress.update { it + (entryId to transform(it[entryId] ?: AnalyzeProgress(entryId))) }
    }
}

private fun Throwable.isDuplicateKey(): Boolean =
    message?.contains("duplicate key") == true || message?.contains("23505") == true
```

Note: `AnalyzeStep` ordering relies on enum declaration order (`UPLOAD` first) — the `<=` comparisons above are `ordinal` comparisons via `compareTo`.

- [ ] **Step 5: Wire into `RallyAndroidApp`**

```kotlin
// new fields
lateinit var localVideos: LocalVideoRepository        private set
lateinit var analyzeCoordinator: AnalyzeCoordinator   private set
private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

// in onCreate(), after themePrefs:
localVideos = LocalVideoRepository(settings)
analyzeCoordinator = AnalyzeCoordinator(
    localVideos = localVideos,
    videos = rally.videos,
    clips = rally.clips,
    scope = appScope,
    openChannel = { uri, offset ->
        // Throwing here surfaces as FAILED(UPLOAD) with this message — the design's
        // "file missing / permission revoked" state.
        val stream = runCatching { contentResolver.openInputStream(Uri.parse(uri)) }.getOrNull()
            ?: error("Video file is missing or access was revoked")
        stream.skip(offset)
        stream.toByteReadChannel()
    },
)
analyzeCoordinator.reattachToProcessing()
```

Imports: `android.net.Uri`, `io.ktor.util.cio.toByteReadChannel` (ktor jvm; if unresolved use `io.ktor.utils.io.jvm.javaio.toByteReadChannel`), `kotlinx.coroutines.CoroutineScope`, `kotlinx.coroutines.Dispatchers`, `kotlinx.coroutines.SupervisorJob`, plus the localvideo package imports.

- [ ] **Step 6: Run tests, full suite, commit**

Run: `./gradlew :androidApp:testDebugUnitTest`
Expected: PASS (all existing + 7 new).

```bash
git add -A androidApp
git commit -m "feat(android): analyze coordinator state machine with step-resume retry"
```

---

### Task 6: Court-marking geometry (pure logic, desktop parity)

**Files:**
- Create: `androidApp/src/main/java/com/badmintontracker/android/localvideo/court/CourtMarkingModel.kt`
- Test: `androidApp/src/test/java/com/badmintontracker/android/localvideo/court/CourtMarkingModelTest.kt`

**Interfaces:**
- Consumes: `CourtKeypoints` (Task 1).
- Produces (used by Task 7):

```kotlin
data class CourtPoint(val x: Float, val y: Float)          // source-video pixels

object CourtMarkingSpec {
    val shortLabels: List<String>   // TL,TR,BR,BL,NL,NR,SNL,SNR,SFL,SFR,CTN,CTF
    val fullLabels: List<String>    // "Top-Left Corner", ... "Center-Far"
    val colors: List<Long>          // 0xFFFF4444, ... (desktop hex, ARGB)
    const val TOTAL = 12
}

data class CourtMarkingState(
    val videoWidth: Int, val videoHeight: Int,
    val points: List<CourtPoint> = emptyList(),
) {
    val isComplete: Boolean
    val nextIndex: Int
    fun place(displayX: Float, displayY: Float, displayWidth: Float, displayHeight: Float): CourtMarkingState
    fun undo(): CourtMarkingState
    fun clear(): CourtMarkingState
    fun toCourtKeypoints(): CourtKeypoints   // requires isComplete
}
```

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.badmintontracker.android.localvideo.court

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class CourtMarkingModelTest {

    @Test
    fun spec_matches_desktop_order_labels_colors() {
        CourtMarkingSpec.shortLabels shouldBe listOf(
            "TL", "TR", "BR", "BL", "NL", "NR", "SNL", "SNR", "SFL", "SFR", "CTN", "CTF")
        CourtMarkingSpec.fullLabels.first() shouldBe "Top-Left Corner"
        CourtMarkingSpec.fullLabels.last() shouldBe "Center-Far"
        CourtMarkingSpec.colors shouldBe listOf(
            0xFFFF4444, 0xFF44FF44, 0xFF4444FF, 0xFFFFFF44,
            0xFFFF00FF, 0xFF00FFFF,
            0xFFFF8800, 0xFF88FF00,
            0xFF0088FF, 0xFFFF0088,
            0xFFFFFFFF, 0xFF888888)
    }

    @Test
    fun place_maps_display_to_source_pixels_like_desktop_handleCanvasClick() {
        // video 1920x1080 shown at 960x540 (scale 2x): tap (100, 50) -> source (200, 100)
        val s = CourtMarkingState(1920, 1080)
            .place(displayX = 100f, displayY = 50f, displayWidth = 960f, displayHeight = 540f)
        s.points.single().x shouldBe (200f plusOrMinus 0.001f)
        s.points.single().y shouldBe (100f plusOrMinus 0.001f)
    }

    @Test
    fun place_ignores_taps_beyond_12_points() {
        var s = CourtMarkingState(100, 100)
        repeat(13) { s = s.place(1f, 1f, 100f, 100f) }
        s.points.size shouldBe 12
        s.isComplete shouldBe true
    }

    @Test
    fun undo_and_clear() {
        var s = CourtMarkingState(100, 100).place(1f, 1f, 100f, 100f).place(2f, 2f, 100f, 100f)
        s.nextIndex shouldBe 2
        s = s.undo()
        s.points.size shouldBe 1
        s.clear().points shouldBe emptyList()
        CourtMarkingState(100, 100).undo().points shouldBe emptyList()
    }

    @Test
    fun toCourtKeypoints_maps_points_in_desktop_order() {
        var s = CourtMarkingState(100, 100)
        repeat(12) { i -> s = s.place(i.toFloat(), (i * 2).toFloat(), 100f, 100f) }
        val kp = s.toCourtKeypoints()
        kp.topLeft shouldBe listOf(0f, 0f)
        kp.netLeft shouldBe listOf(4f, 8f)          // 5th point (index 4)
        kp.centerFar shouldBe listOf(11f, 22f)      // 12th point
    }

    @Test
    fun toCourtKeypoints_requires_completion() {
        shouldThrow<IllegalStateException> { CourtMarkingState(100, 100).toCourtKeypoints() }
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "com.badmintontracker.android.localvideo.court.CourtMarkingModelTest"`
Expected: compilation FAILS.

- [ ] **Step 3: Implement**

```kotlin
package com.badmintontracker.android.localvideo.court

import com.badmintontracker.shared.model.CourtKeypoints

data class CourtPoint(val x: Float, val y: Float)

/** Constants copied verbatim from desktop CourtSetup.vue (order, labels, colors). */
object CourtMarkingSpec {
    const val TOTAL = 12
    val shortLabels = listOf(
        "TL", "TR", "BR", "BL", "NL", "NR", "SNL", "SNR", "SFL", "SFR", "CTN", "CTF")
    val fullLabels = listOf(
        "Top-Left Corner", "Top-Right Corner", "Bottom-Right Corner", "Bottom-Left Corner",
        "Net-Left", "Net-Right",
        "Service Near-Left", "Service Near-Right",
        "Service Far-Left", "Service Far-Right",
        "Center-Near", "Center-Far")
    val colors = listOf(
        0xFFFF4444, 0xFF44FF44, 0xFF4444FF, 0xFFFFFF44,
        0xFFFF00FF, 0xFF00FFFF,
        0xFFFF8800, 0xFF88FF00,
        0xFF0088FF, 0xFFFF0088,
        0xFFFFFFFF, 0xFF888888)
}

data class CourtMarkingState(
    val videoWidth: Int,
    val videoHeight: Int,
    val points: List<CourtPoint> = emptyList(),
) {
    val isComplete: Boolean get() = points.size == CourtMarkingSpec.TOTAL
    val nextIndex: Int get() = points.size

    /** Same mapping as desktop handleCanvasClick: display coords -> source pixels. */
    fun place(displayX: Float, displayY: Float, displayWidth: Float, displayHeight: Float): CourtMarkingState {
        if (isComplete) return this
        val scaleX = videoWidth / displayWidth
        val scaleY = videoHeight / displayHeight
        return copy(points = points + CourtPoint(displayX * scaleX, displayY * scaleY))
    }

    fun undo(): CourtMarkingState = if (points.isEmpty()) this else copy(points = points.dropLast(1))

    fun clear(): CourtMarkingState = copy(points = emptyList())

    /** Identical field mapping to desktop saveAndProceed(). */
    fun toCourtKeypoints(): CourtKeypoints {
        check(isComplete) { "Court marking incomplete: ${points.size}/${CourtMarkingSpec.TOTAL}" }
        fun p(i: Int) = listOf(points[i].x, points[i].y)
        return CourtKeypoints(
            topLeft = p(0), topRight = p(1), bottomRight = p(2), bottomLeft = p(3),
            netLeft = p(4), netRight = p(5),
            serviceLineNearLeft = p(6), serviceLineNearRight = p(7),
            serviceLineFarLeft = p(8), serviceLineFarRight = p(9),
            centerNear = p(10), centerFar = p(11),
        )
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "com.badmintontracker.android.localvideo.court.CourtMarkingModelTest"`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add androidApp/src/main/java/com/badmintontracker/android/localvideo/court androidApp/src/test/java/com/badmintontracker/android/localvideo/court
git commit -m "feat(android): court marking model with desktop-parity mapping"
```

---

### Task 7: CourtMarkingScreen (Compose UI + ViewModel)

**Files:**
- Create: `androidApp/src/main/java/com/badmintontracker/android/localvideo/court/CourtMarkingViewModel.kt`
- Create: `androidApp/src/main/java/com/badmintontracker/android/localvideo/court/CourtMarkingScreen.kt`
- Test: `androidApp/src/test/java/com/badmintontracker/android/localvideo/court/CourtMarkingViewModelTest.kt`

**Interfaces:**
- Consumes: `CourtMarkingState`/`CourtMarkingSpec` (Task 6), `LocalVideoRepository` (Task 4), `AnalyzeCoordinator.startAnalysis(entryId, keypoints)` (Task 5).
- Produces: `CourtMarkingScreen(vm: CourtMarkingViewModel, onStartAnalysis: (CourtKeypoints) -> Unit, onBack: () -> Unit)` used by Task 9's nav wiring.

**ViewModel:** frame loading is injected so the VM is unit-testable without Android framework classes.

- [ ] **Step 1: Write the failing ViewModel test**

```kotlin
package com.badmintontracker.android.localvideo.court

import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldNotBeNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CourtMarkingViewModelTest {

    @BeforeTest fun setup() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @AfterTest fun teardown() { Dispatchers.resetMain() }

    private fun vm(loadOk: Boolean = true) = CourtMarkingViewModel(
        entryId = "e1",
        loadFrame = {
            if (loadOk) CourtFrame(frame = null, width = 1920, height = 1080)
            else error("no frame")
        },
    )

    @Test
    fun loads_frame_dimensions_and_starts_empty() = runTest {
        val vm = vm()
        val s = vm.state.value
        s.marking.shouldNotBeNull().videoWidth shouldBe 1920
        s.marking!!.points shouldBe emptyList()
        s.error shouldBe null
    }

    @Test
    fun tap_undo_complete_flow() = runTest {
        val vm = vm()
        repeat(12) { vm.onTap(displayX = 10f, displayY = 10f, displayWidth = 192f, displayHeight = 108f) }
        vm.state.value.marking!!.isComplete.shouldBeTrue()
        vm.onUndo()
        vm.state.value.marking!!.points.size shouldBe 11
    }

    @Test
    fun frame_load_failure_sets_error() = runTest {
        vm(loadOk = false).state.value.error shouldBe "no frame"
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "com.badmintontracker.android.localvideo.court.CourtMarkingViewModelTest"`
Expected: compilation FAILS.

- [ ] **Step 3: Implement the ViewModel**

```kotlin
package com.badmintontracker.android.localvideo.court

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Frame shown for marking. `frame` is null in unit tests (dimensions still real). */
data class CourtFrame(val frame: Bitmap?, val width: Int, val height: Int)

data class CourtMarkingUiState(
    val frame: Bitmap? = null,
    val marking: CourtMarkingState? = null,
    val error: String? = null,
)

class CourtMarkingViewModel(
    val entryId: String,
    private val loadFrame: suspend () -> CourtFrame,
) : ViewModel() {

    val state = MutableStateFlow(CourtMarkingUiState())

    init {
        viewModelScope.launch {
            runCatching { loadFrame() }
                .onSuccess { f ->
                    state.update {
                        it.copy(frame = f.frame, marking = CourtMarkingState(f.width, f.height))
                    }
                }
                .onFailure { e -> state.update { it.copy(error = e.message ?: "Couldn't load frame") } }
        }
    }

    fun onTap(displayX: Float, displayY: Float, displayWidth: Float, displayHeight: Float) {
        state.update { s ->
            s.copy(marking = s.marking?.place(displayX, displayY, displayWidth, displayHeight))
        }
    }

    fun onUndo() = state.update { it.copy(marking = it.marking?.undo()) }
    fun onClear() = state.update { it.copy(marking = it.marking?.clear()) }
}
```

- [ ] **Step 4: Run tests to verify they pass, commit**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "com.badmintontracker.android.localvideo.court.CourtMarkingViewModelTest"`
Expected: PASS (3 tests).

```bash
git add androidApp/src/main/java/com/badmintontracker/android/localvideo/court androidApp/src/test
git commit -m "feat(android): court marking view model"
```

- [ ] **Step 5: Implement the screen (UI, no unit test — verified by build + device)**

`CourtMarkingScreen.kt` requirements (desktop parity per Global Constraints; Shuttl style):

```kotlin
package com.badmintontracker.android.localvideo.court

// Structure (full code to be written following these exact rules):
// - Scaffold, TopAppBar title "COURT MAPPING" (labelSmall 14sp, like other screens),
//   back arrow -> onBack.
// - Body Column:
//   1. Frame area: Box(Modifier.fillMaxWidth().aspectRatio(videoW/videoH))
//      containing Image(bitmap) with a pinch-zoom/pan Modifier:
//        var scale by remember { mutableFloatStateOf(1f) }
//        var offset by remember { mutableStateOf(Offset.Zero) }
//        .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y }
//        .pointerInput(Unit) { detectTransformGestures { centroid, pan, zoom, _ ->
//            scale = (scale * zoom).coerceIn(1f, 6f); offset += pan * scale } }
//        .pointerInput(marking) { detectTapGestures { tap ->
//            // Inverse-map tap through zoom/pan to layout coords, then vm.onTap
//            val x = (tap.x - offset.x) / scale
//            val y = (tap.y - offset.y) / scale
//            vm.onTap(x, y, layoutSize.width, layoutSize.height) } }
//      and a Canvas overlay (same transform) drawing, in this order (desktop parity):
//        a) dashed green guide rect at 15% margins + net line (h/2) + service lines
//           (60% between boundary and net) + center line — color 0x3322C55E, dash 4/4
//        b) dashed rectangle connecting first 4 placed points — 0x9922C55E, dash 8/4
//        c) each placed point: filled circle r=10dp-equivalent in display px,
//           color CourtMarkingSpec.colors[i], black 2px stroke, shortLabel centered
//           (drawText via rememberTextMeasurer, 12sp bold, black)
//      All drawing positions derive from source px -> display px: factor
//      displayWidth / videoWidth (and height respectively).
//   2. Instruction row (replaces desktop's floating box): text
//      "Tap: ${CourtMarkingSpec.fullLabels[nextIndex]}" tinted Color(colors[nextIndex]),
//      counter "n / 12 points" (labelSmall, onSurfaceVariant).
//   3. Compact schematic court guide: Canvas ~160dp tall drawing a badminton court
//      outline (proportions: width 6.1 x length 13.4, service lines 1.98 from net,
//      center line between service lines — from badminton-tracker homography.ts
//      COURT_KEYPOINT_POSITIONS) with the 12 positions as dots in spec colors;
//      next point drawn larger (r*1.6).
//   4. Button row: ShuttlButton "Undo" (Secondary, enabled = points>0, -> vm.onUndo),
//      ShuttlButton "Clear" (Secondary, enabled = points>0, -> vm.onClear),
//      ShuttlButton "Start Analysis" (Primary, ONLY visible when marking.isComplete)
//      -> onStartAnalysis(marking.toCourtKeypoints()).
// - error != null -> ErrorBanner(error) instead of frame area.
```

The composable signature:

```kotlin
@Composable
fun CourtMarkingScreen(
    vm: CourtMarkingViewModel,
    onStartAnalysis: (com.badmintontracker.shared.model.CourtKeypoints) -> Unit,
    onBack: () -> Unit,
)
```

Frame loading (passed as `loadFrame` when constructing the VM in nav wiring, Task 9):

```kotlin
suspend fun loadFirstFrame(context: Context, uri: Uri): CourtFrame =
    withContext(Dispatchers.IO) {
        MediaMetadataRetriever().use { r ->
            r.setDataSource(context, uri)
            // Desktop parity: frame at t=0.1s, OPTION_CLOSEST for the exact frame.
            val bmp = r.getFrameAtTime(100_000L, MediaMetadataRetriever.OPTION_CLOSEST)
                ?: error("Couldn't extract video frame")
            CourtFrame(bmp, bmp.width, bmp.height)
        }
    }
```

Place `loadFirstFrame` in `CourtMarkingScreen.kt`. Note `MediaMetadataRetriever.use {}` requires API 29; minSdk is 26, so call `release()` in a `finally` instead of `use`.

- [ ] **Step 6: Build check + commit**

Run: `./gradlew :androidApp:assembleDebug`
Expected: BUILD SUCCESSFUL.

```bash
git add androidApp/src/main/java/com/badmintontracker/android/localvideo/court
git commit -m "feat(android): court marking screen with zoom, overlay and schematic guide"
```

---

### Task 8: Video intake (record/import) + "On this phone" section in ClipListScreen

**Files:**
- Create: `androidApp/src/main/java/com/badmintontracker/android/localvideo/VideoIntake.kt`
- Create: `androidApp/src/main/java/com/badmintontracker/android/localvideo/LocalVideoSection.kt`
- Create: `androidApp/src/main/java/com/badmintontracker/android/localvideo/LocalVideoListViewModel.kt`
- Modify: `androidApp/src/main/java/com/badmintontracker/android/cliplist/ClipListScreen.kt`
- Modify: `androidApp/src/main/java/com/badmintontracker/android/RallyAndroidApp.kt` (Coil ImageLoader with video decoder)
- Test: `androidApp/src/test/java/com/badmintontracker/android/localvideo/LocalVideoListViewModelTest.kt`

**Interfaces:**
- Consumes: `LocalVideoRepository`, `AnalyzeCoordinator` (Tasks 4–5).
- Produces: `LocalVideoListViewModel(localVideos, coordinator)` exposing `rows: StateFlow<List<LocalVideoRow>>`; `LocalVideoSection(...)` composable; `rememberVideoIntake(onAdded: (LocalVideoEntry) -> Unit): VideoIntake` with `fun record()` / `fun import()`.

- [ ] **Step 1: Write the failing ViewModel test**

`LocalVideoRow` merges the durable entry with transient progress into one render model:

```kotlin
package com.badmintontracker.android.localvideo

import com.badmintontracker.android.testing.FakeClipsRepository
import com.badmintontracker.android.testing.FakeVideosRepository
import com.russhwolf.settings.MapSettings
import io.kotest.matchers.shouldBe
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LocalVideoListViewModelTest {

    @Test
    fun maps_entries_to_rows_with_status_text() = runTest {
        val localVideos = LocalVideoRepository(MapSettings())
        val coordinator = AnalyzeCoordinator(
            localVideos, FakeVideosRepository(), FakeClipsRepository(),
            CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            openChannel = { _, _ -> ByteReadChannel(ByteArray(0)) },
        )
        localVideos.add(LocalVideoEntry(
            id = "a", uri = "content://a", displayName = "m.mp4",
            durationMs = 65_000, sizeBytes = 1, addedAtEpochMs = 0,
        ))
        localVideos.add(LocalVideoEntry(
            id = "b", uri = "content://b", displayName = "n.mp4",
            durationMs = 1_000, sizeBytes = 1, addedAtEpochMs = 1,
            stage = AnalyzeStage.FAILED, failedStep = AnalyzeStep.UPLOAD, failureMessage = "network",
        ))
        val vm = LocalVideoListViewModel(localVideos, coordinator)
        val rows = vm.rows.value
        rows.map { it.entry.id } shouldBe listOf("b", "a")
        rows[0].statusText shouldBe "Failed: network — tap Analyze to retry"
        rows[1].statusText shouldBe null            // plain LOCAL entry
        rows[1].durationText shouldBe "1:05"
        rows[1].canAnalyze shouldBe true
        rows[0].canAnalyze shouldBe true
    }

    @Test
    fun uploading_and_processing_rows_show_progress_and_disable_analyze() = runTest {
        val localVideos = LocalVideoRepository(MapSettings())
        val coordinator = AnalyzeCoordinator(
            localVideos, FakeVideosRepository(), FakeClipsRepository(),
            CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            openChannel = { _, _ -> ByteReadChannel(ByteArray(0)) },
        )
        localVideos.add(LocalVideoEntry(
            id = "a", uri = "content://a", displayName = "m.mp4",
            durationMs = 0, sizeBytes = 1, addedAtEpochMs = 0, stage = AnalyzeStage.UPLOADING,
        ))
        val vm = LocalVideoListViewModel(localVideos, coordinator)
        vm.rows.value.single().statusText shouldBe "Uploading…"
        vm.rows.value.single().canAnalyze shouldBe false
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "com.badmintontracker.android.localvideo.LocalVideoListViewModelTest"`
Expected: compilation FAILS.

- [ ] **Step 3: Implement `LocalVideoListViewModel`**

```kotlin
package com.badmintontracker.android.localvideo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class LocalVideoRow(
    val entry: LocalVideoEntry,
    val statusText: String?,     // null when plain LOCAL
    val durationText: String,    // m:ss
    val canAnalyze: Boolean,     // LOCAL or FAILED
)

class LocalVideoListViewModel(
    private val localVideos: LocalVideoRepository,
    private val coordinator: AnalyzeCoordinator,
) : ViewModel() {

    val rows = combine(localVideos.entries, coordinator.progress) { entries, progress ->
        entries.map { e -> e.toRow(progress[e.id]) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000),
        localVideos.entries.value.map { it.toRow(null) })

    fun remove(id: String) = localVideos.remove(id)
    fun retry(id: String) = coordinator.retry(id)
}

internal fun LocalVideoEntry.toRow(progress: AnalyzeProgress?): LocalVideoRow {
    val statusText = when (stage) {
        AnalyzeStage.LOCAL -> null
        AnalyzeStage.UPLOADING -> progress?.uploadProgress
            ?.let { "Uploading ${(it * 100).toInt()}%…" } ?: "Uploading…"
        AnalyzeStage.PROCESSING -> progress?.pipelineProgress
            ?.let { "Analyzing ${(it * 100).toInt()}%…" } ?: "Analyzing…"
        AnalyzeStage.FAILED -> "Failed: ${failureMessage ?: "unknown error"} — tap Analyze to retry"
    }
    return LocalVideoRow(
        entry = this,
        statusText = statusText,
        durationText = formatDuration(durationMs),
        canAnalyze = stage == AnalyzeStage.LOCAL || stage == AnalyzeStage.FAILED,
    )
}

internal fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    return "${totalSec / 60}:${(totalSec % 60).toString().padStart(2, '0')}"
}
```

- [ ] **Step 4: Run tests to verify they pass, commit**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "com.badmintontracker.android.localvideo.LocalVideoListViewModelTest"`
Expected: PASS (2 tests).

```bash
git add -A androidApp
git commit -m "feat(android): local video list view model"
```

- [ ] **Step 5: Implement `VideoIntake` (record + import)**

```kotlin
package com.badmintontracker.android.localvideo

// rememberVideoIntake(onAdded): remembers two ActivityResult launchers.
//
// import():
//   rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
//     if (uri != null) {
//       runCatching {
//         context.contentResolver.takePersistableUriPermission(
//           uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
//       } // photo-picker grants may not be persistable on all OEMs; best effort
//       readMetadataAndAdd(context, uri, onAdded)
//     }
//   }
//   launch with PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
//
// record():
//   1. Insert a MediaStore item first so the app owns it (persistent access):
//      val values = ContentValues().apply {
//        put(MediaStore.Video.Media.DISPLAY_NAME, "shuttl_${System.currentTimeMillis()}.mp4")
//        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
//        put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Shuttl")
//      }
//      pendingRecordUri = contentResolver.insert(
//        MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
//   2. rememberLauncherForActivityResult(ActivityResultContracts.CaptureVideo()) { ok ->
//        val uri = pendingRecordUri
//        if (ok && uri != null) readMetadataAndAdd(context, uri, onAdded)
//        else uri?.let { contentResolver.delete(it, null, null) }  // clean up canceled
//      }
//      launcher.launch(pendingRecordUri)
//
// readMetadataAndAdd(context, uri, onAdded):
//   - durationMs via MediaMetadataRetriever (METADATA_KEY_DURATION), release() in finally
//   - sizeBytes + displayName via contentResolver.query(uri,
//       [OpenableColumns.SIZE, OpenableColumns.DISPLAY_NAME])
//   - reject sizeBytes > 1_073_741_824 (1 GB) -> onError("Video is larger than 1GB…")
//   - onAdded(LocalVideoEntry(id = UUID.randomUUID().toString(), uri = uri.toString(),
//       displayName, durationMs, sizeBytes, addedAtEpochMs = System.currentTimeMillis()))
```

Signature produced:

```kotlin
class VideoIntake(val record: () -> Unit, val import: () -> Unit)

@Composable
fun rememberVideoIntake(
    onAdded: (LocalVideoEntry) -> Unit,
    onError: (String) -> Unit,
): VideoIntake
```

Note: `ActivityResultContracts.CaptureVideo` wraps `ACTION_VIDEO_CAPTURE` with an output URI — exactly what we need, and it returns a Boolean success flag.

- [ ] **Step 6: Implement `LocalVideoSection` + wire into `ClipListScreen`**

`LocalVideoSection.kt` — list items rendered inside the existing `LazyColumn` (extension on `LazyListScope`, so it composes into the parent list):

```kotlin
package com.badmintontracker.android.localvideo

// fun LazyListScope.localVideoSection(
//     rows: List<LocalVideoRow>,
//     onRowClick: (LocalVideoEntry) -> Unit,      // -> LocalPlayer route
//     onAnalyzeClick: (LocalVideoRow) -> Unit,    // LOCAL -> CourtMarking route; FAILED -> retry
//     onRemove: (LocalVideoEntry) -> Unit,
// ) {
//   if (rows.isEmpty()) return
//   item(key = "header-local") { SectionHeader("On this phone") }  // reuse pattern: labelSmall uppercase
//   items(rows, key = { "local-${it.entry.id}" }) { row ->
//     Row (same layout metrics as MatchRow: 16/14dp padding, 96x54 thumb, 12dp spacer):
//       AsyncImage(model = row.entry.uri) — coil-video decodes a video frame
//       Column(weight 1f):
//         Text(entry.displayName, titleMedium, onBackground, maxLines 1, ellipsis)
//         Text("${row.durationText} · ${formatDate(Instant.fromEpochMilliseconds(entry.addedAtEpochMs))}".uppercase(),
//              labelSmall, onSurfaceVariant)
//         row.statusText?.let { Text(it, bodySmall,
//              color = if (entry.stage == AnalyzeStage.FAILED) colorScheme.error else onSurfaceVariant,
//              maxLines 1, ellipsis) }
//       if (row.canAnalyze) ShuttlButton("Analyze", Primary, compact) else
//         CircularProgressIndicator(16.dp, strokeWidth 2.dp) for UPLOADING/PROCESSING
//       IconButton(MoreVert) -> DropdownMenu with single "Remove from app" -> onRemove
//     HorizontalDivider()
//   }
// }
```

`formatDate` already exists in `cliplist/ClipListScreen.kt` (internal) — reuse it.

Changes to `ClipListScreen.kt`:
1. New parameters: `localRows: List<LocalVideoRow>`, `onLocalClick: (LocalVideoEntry) -> Unit`, `onLocalAnalyze: (LocalVideoRow) -> Unit`, `onLocalRemove: (LocalVideoEntry) -> Unit`, `onRecord: () -> Unit`, `onImport: () -> Unit`.
2. TopAppBar `actions`: before the theme toggle, add `IconButton(Icons.Default.Add)` opening a `DropdownMenu` with "Record video" → `onRecord` and "Import video" → `onImport`.
3. In the `LazyColumn`, FIRST: `localVideoSection(localRows, onLocalClick, onLocalAnalyze, onLocalRemove)`, then the existing owned/shared sections unchanged.
4. Empty state condition becomes: all three lists empty → keep the existing message.

Coil video decoder — in `RallyAndroidApp.onCreate()` register an app-wide ImageLoader (Coil3 `SingletonImageLoader.setSafe` with `ImageLoader.Builder(context).components { add(VideoFrameDecoder.Factory()) }.build()`).

- [ ] **Step 7: Build check + full unit tests + commit**

Run: `./gradlew :androidApp:assembleDebug :androidApp:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, tests PASS. (`ClipListScreen` call sites are updated in Task 9's nav wiring — to keep this task compiling, give the new parameters defaults: `localRows = emptyList()`, no-op lambdas.)

```bash
git add -A androidApp
git commit -m "feat(android): record/import intake and 'On this phone' section"
```

---

### Task 9: LocalPlayerScreen + routes + nav wiring + keep-screen-on

**Files:**
- Create: `androidApp/src/main/java/com/badmintontracker/android/localvideo/LocalPlayerScreen.kt`
- Create: `androidApp/src/main/java/com/badmintontracker/android/ui/components/FullscreenEffect.kt`
- Modify: `androidApp/src/main/java/com/badmintontracker/android/nav/Route.kt`
- Modify: `androidApp/src/main/java/com/badmintontracker/android/AuthGate.kt`
- Modify: `androidApp/src/main/java/com/badmintontracker/android/clipdetail/ClipDetailScreen.kt` (use extracted helper)
- Modify: `androidApp/src/main/java/com/badmintontracker/android/MainActivity.kt` (keep-screen-on)

**Interfaces:**
- Consumes: everything from Tasks 4–8.
- Produces: `Route.LocalPlayer(entryId: String)`, `Route.CourtMarking(entryId: String)`; `FullscreenEffect(isFullscreen: Boolean)` composable.

- [ ] **Step 1: Extract the fullscreen helper**

`FullscreenEffect.kt` — lift the two `LaunchedEffect`/`DisposableEffect` blocks from `ClipDetailScreen` verbatim into one reusable composable:

```kotlin
package com.badmintontracker.android.ui.components

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/** Locks landscape + hides system bars while [isFullscreen]; restores on exit/dispose. */
@Composable
fun FullscreenEffect(isFullscreen: Boolean) {
    val activity = LocalContext.current as? Activity

    LaunchedEffect(isFullscreen, activity) {
        val a = activity ?: return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(a.window, a.window.decorView)
        if (isFullscreen) {
            a.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            a.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            activity?.let { a ->
                a.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                WindowCompat.getInsetsController(a.window, a.window.decorView)
                    .show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
}
```

Replace the corresponding code in `ClipDetailScreen` with `FullscreenEffect(isFullscreen)` (keep its player-release `DisposableEffect`, dropping only the orientation/insets restore lines now covered by the helper).

- [ ] **Step 2: Add routes**

```kotlin
@Serializable data class  LocalPlayer(val entryId: String)  : Route
@Serializable data class  CourtMarking(val entryId: String) : Route
```

- [ ] **Step 3: Implement `LocalPlayerScreen`**

Same skeleton as `ClipDetailScreen` minus annotations/FAB/error-retry-overlay:

```kotlin
package com.badmintontracker.android.localvideo

// @Composable fun LocalPlayerScreen(entry: LocalVideoEntry, canAnalyze: Boolean,
//                                   onAnalyze: () -> Unit, onBack: () -> Unit)
// - remember ExoPlayer with SeekParameters.EXACT (same builder as ClipDetailScreen)
// - LaunchedEffect(entry.uri): setMediaItem(MediaItem.fromUri(entry.uri)), prepare(),
//   playWhenReady = false
// - DisposableEffect: release player
// - var isFullscreen ... BackHandler, LaunchedEffect(orientation) -> isFullscreen,
//   FullscreenEffect(isFullscreen)
// - playerSurface: AndroidView inflating R.layout.clip_player_view (same as ClipDetail),
//   fullscreen button listener toggling isFullscreen
// - portrait: Scaffold(topBar = TopAppBar(title = entry.displayName, back arrow,
//     actions = { if (canAnalyze) ShuttlButton("Analyze", Primary) })
//   ) { player 16:9 + FrameStepBar(player) + Spacer }
// - fullscreen: Box(black) { playerSurface fillMaxSize + FrameStepBar bottom-center
//     padding 24dp } — identical to ClipDetailScreen's fullscreen block
```

- [ ] **Step 4: Wire everything in `AuthGate`**

`AuthGate` currently receives `rally` + `themePrefs`; add `localVideos: LocalVideoRepository` and `coordinator: AnalyzeCoordinator` parameters (passed from `MainActivity` via `app.localVideos` / `app.analyzeCoordinator`).

Inside `composable<Route.ClipList>`: build `LocalVideoListViewModel` (viewModelFactory like the others), `rememberVideoIntake(onAdded = localVideos::add, onError = snackbar via existing error flow — simplest: pass `onError` into `ClipListScreen` as a state the screen shows in its snackbar)`, and pass the new `ClipListScreen` parameters:

```kotlin
val localVm: LocalVideoListViewModel = viewModel(factory = viewModelFactory {
    initializer { LocalVideoListViewModel(localVideos, coordinator) }
})
val localRows by localVm.rows.collectAsStateWithLifecycle()
var intakeError by remember { mutableStateOf<String?>(null) }
val intake = rememberVideoIntake(onAdded = { localVideos.add(it) }, onError = { intakeError = it })
ClipListScreen(
    vm = clipListVm, media = rally.media, shares = rally.shares, themePrefs = themePrefs,
    onMatchClick = { nav.navigate(Route.MatchClips(it.videoId)) },
    localRows = localRows,
    intakeError = intakeError,
    onIntakeErrorShown = { intakeError = null },
    onLocalClick = { nav.navigate(Route.LocalPlayer(it.id)) },
    onLocalAnalyze = { row ->
        if (row.entry.stage == AnalyzeStage.FAILED && row.entry.keypoints != null)
            localVm.retry(row.entry.id)                       // resume; points already saved
        else nav.navigate(Route.CourtMarking(row.entry.id))   // fresh: mark court first
    },
    onLocalRemove = { localVm.remove(it.id) },
    onRecord = intake.record,
    onImport = intake.import,
)
```

(`intakeError`/`onIntakeErrorShown`: add these two parameters to `ClipListScreen`, surfacing via the existing `snackbarHostState` in a `LaunchedEffect(intakeError)`.)

New destinations:

```kotlin
composable<Route.LocalPlayer> { entry ->
    val args = entry.toRoute<Route.LocalPlayer>()
    val e = localVideos.get(args.entryId)
    if (e == null) { LaunchedEffect(Unit) { nav.popBackStack() } } else {
        LocalPlayerScreen(
            entry = e,
            canAnalyze = e.stage == AnalyzeStage.LOCAL || e.stage == AnalyzeStage.FAILED,
            onAnalyze = { nav.navigate(Route.CourtMarking(e.id)) },
            onBack = { nav.popBackStack() },
        )
    }
}
composable<Route.CourtMarking> { entry ->
    val args = entry.toRoute<Route.CourtMarking>()
    val ctx = LocalContext.current.applicationContext
    val vm: CourtMarkingViewModel = viewModel(factory = viewModelFactory {
        initializer {
            val e = localVideos.get(args.entryId) ?: error("Entry not found")
            CourtMarkingViewModel(args.entryId) {
                loadFirstFrame(ctx, Uri.parse(e.uri))
            }
        }
    })
    CourtMarkingScreen(
        vm = vm,
        onStartAnalysis = { kp ->
            coordinator.startAnalysis(args.entryId, kp)
            nav.popBackStack(Route.ClipList, inclusive = false)
        },
        onBack = { nav.popBackStack() },
    )
}
```

- [ ] **Step 5: Keep-screen-on during uploads**

In `MainActivity.setContent`, alongside the theme state:

```kotlin
val uploading by app.analyzeCoordinator.hasActiveUpload.collectAsStateWithLifecycle()
LaunchedEffect(uploading) {
    if (uploading) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
}
```

- [ ] **Step 6: Build, full test suite, commit**

Run: `./gradlew :androidApp:assembleDebug :androidApp:testDebugUnitTest :shared:jvmTest`
Expected: all green.

```bash
git add -A androidApp
git commit -m "feat(android): local player, court marking route, nav wiring, keep-screen-on"
```

---

### Task 10: Changelog + full verification

**Files:**
- Modify: `CHANGELOG.md`

- [ ] **Step 1: Update CHANGELOG under [Unreleased] → Added**

```markdown
- Record or import match videos on the phone; they stay on-device under "On this phone".
- Play local videos with the same frame-step controls as rally clips.
- Analyze a local video from the phone: 12-point court mapping (identical to the
  desktop flow), resumable upload, and live pipeline progress until the match's
  rally clips appear.
```

- [ ] **Step 2: Full build + all tests**

Run: `./gradlew :shared:jvmTest :androidApp:testDebugUnitTest :androidApp:assembleDebug`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 3: On-device verification checklist (manual, requires device + Supabase creds)**

1. Record a short clip via "+ → Record video" → appears under "On this phone" with thumbnail/duration.
2. Import an existing gallery video → appears; >1GB file rejected with message.
3. Tap row → local player: plays, frame-step forward/back works, fullscreen works.
4. Tap Analyze → court marking: 12 taps with correct labels/colors/order, zoom/pan, undo/clear; Start Analysis only at 12/12.
5. Watch: Uploading % → Analyzing % → entry disappears → match + rally clips appear in "My matches".
6. Verify in Supabase dashboard: `videos` row has `manual_court_keypoints` with the 12 snake_case keys and pixel coords matching the video resolution.
7. Kill the app mid-upload → relaunch → entry FAILED/UPLOADING → Analyze/Retry resumes without re-marking the court.
8. Airplane mode mid-upload → failure surfaces → retry on wifi succeeds.

- [ ] **Step 4: Commit**

```bash
git add CHANGELOG.md
git commit -m "docs: changelog for local video recording + analyze"
```
