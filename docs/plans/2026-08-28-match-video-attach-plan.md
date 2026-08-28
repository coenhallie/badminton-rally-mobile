# Attaching a video to a scored match - Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A match created and scored on the phone can take a video afterwards, run it through the clipping pipeline, say so while it runs, and end up as one match holding both its points and its rally clips.

**Architecture:** The link lives on `LocalVideoEntry.scoreLogId` from the moment the video is picked, because `score_logs.video_id` has a foreign key to `videos(id)` and no such row exists until the pipeline's `CREATE_ROW` step. A narrow hook on `AnalyzeCoordinator` writes `score_logs.video_id` the instant that row exists, without the coordinator learning what a match is. The match list folds a video match into its score match so one match is one row, and the two detail screens merge into one match page with Points and Rallies facets.

**Tech Stack:** Kotlin Multiplatform (shared/androidApp/iosApp), Gradle, Jetpack Compose + Material 3, SwiftUI with `@Observable`/`@MainActor`, SKIE bridging, kotest assertions in Kotlin tests, XCTest on iOS, Supabase postgrest.

**Spec:** `docs/plans/2026-08-28-match-video-attach-design.md`

## Global Constraints

- No em dash (`—`) anywhere written by hand: code comments, commit messages, docs, UI copy. Use a plain dash `-`. Existing file content that is not otherwise being rewritten is left alone.
- No agent attribution on commits: no `Co-Authored-By` trailer, no "Generated with" footer.
- Do not hand-edit `iosApp/iosApp.xcodeproj/project.pbxproj`. Run `xcodegen generate` from `iosApp/` after adding or deleting Swift files. `project.yml` lists `sources: [Sources, Assets.xcassets]` as directories, so new files under `Sources/` need no `project.yml` change.
- Every iOS build/test command must be prefixed with `export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer`, and simulator builds need `CODE_SIGNING_ALLOWED=NO`. Run `xattr -cr iosApp` first if codesign complains about `com.apple.provenance`.
- iOS test names mirror Android test names one for one. That parity is the cross-platform check; if the two lists diverge, the two surfaces have diverged.
- TDD: write the failing test, run it and see it fail for the stated reason, implement, run it and see it pass, commit.
- `docs/plans/2026-08-26-live-scoring-review-design.md` §7 L2 says `score_logs.video_id` "is set at the start". It is superseded by §4.2 of this plan's spec. Do not implement that sentence.

**Test commands:**

```bash
# shared, one class
./gradlew :shared:jvmTest --tests "com.badmintontracker.shared.scoring.ScoreLogsRepositoryTest"
# shared, everything
./gradlew :shared:jvmTest
# androidApp
./gradlew :androidApp:testDebugUnitTest
# iOS (unit + UI)
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
xcodebuild test -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -destination "platform=iOS Simulator,name=iPhone 17 Pro" \
  -derivedDataPath iosApp/build/DerivedData CODE_SIGNING_ALLOWED=NO
```

---

## Task 0: Apply the `score_logs` migration

**Hard prerequisite.** `supabase/migrations/20260827000000_score_logs.sql` exists in the repo but has never been applied to the server. Score-only matches work today only because the store is local first; `sync()` and `delete()` both fail server-side. Nothing after this task can be verified end to end, because the binding write targets a column in a table that does not exist.

**Files:**
- Modify: none (the migration file is already written and reviewed)

- [ ] **Step 1: Confirm the table is absent**

```bash
supabase db diff --schema public --linked | grep -i score_logs || echo "no score_logs on the server"
```

Expected: the table is missing.

- [ ] **Step 2: Apply it**

```bash
supabase db push
```

Expected: `20260827000000_score_logs.sql` applied. If the CLI is not linked, this needs the user: ask them to run `supabase link --project-ref <ref>` (the ref is in `supabase/.temp/project-ref`) and re-run.

- [ ] **Step 3: Verify the table, the trigger and the policies exist**

```bash
supabase db diff --schema public --linked
```

Expected: no diff involving `score_logs`.

- [ ] **Step 4: Verify from the app**

Launch the Android app on the emulator, pull to refresh on the match list, and confirm no error snackbar. Then delete a scored match and confirm the message "Couldn't delete the match everywhere. It's gone from this phone." no longer appears.

- [ ] **Step 5: Commit**

Nothing to commit - the migration file is already in git. Note in the session that Task 0 is done.

---

## Task 1: `LocalVideoEntry.scoreLogId`

The client's durable answer to "which match is this video for", from intake until the pipeline removes the entry.

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/badmintontracker/shared/localvideo/LocalVideoEntry.kt`
- Test: `shared/src/commonTest/kotlin/com/badmintontracker/shared/localvideo/LocalVideoEntrySerializationTest.kt`

**Interfaces:**
- Produces: `LocalVideoEntry.scoreLogId: String?`, defaulting to `null`, declared **last** in the constructor.

- [ ] **Step 1: Write the failing test**

Append to `LocalVideoEntrySerializationTest.kt`:

```kotlin
    @Test
    fun a_registry_written_before_score_log_ids_existed_still_decodes() {
        // LocalVideoRepository.load() swallows a decode failure and returns an empty
        // library, so a non-defaulted field here would silently wipe every local
        // video on the phone the first time this build runs.
        val legacy = """
            {"id":"e1","uri":"content://x/e1","displayName":"m.mp4","durationMs":1000,
             "sizeBytes":10,"addedAtEpochMs":0}
        """.trimIndent()
        val entry = Json { ignoreUnknownKeys = true }
            .decodeFromString(LocalVideoEntry.serializer(), legacy)
        entry.scoreLogId.shouldBeNull()
    }

    @Test
    fun a_video_picked_for_a_match_remembers_which_match() {
        val entry = LocalVideoEntry(
            id = "e1", uri = "content://x/e1", displayName = "m.mp4",
            durationMs = 1000, sizeBytes = 10, addedAtEpochMs = 0,
            scoreLogId = "log-1",
        )
        val json = Json { ignoreUnknownKeys = true }
        val round = json.decodeFromString(
            LocalVideoEntry.serializer(),
            json.encodeToString(LocalVideoEntry.serializer(), entry),
        )
        round.scoreLogId shouldBe "log-1"
    }
```

Add whatever imports the file is missing: `io.kotest.matchers.nulls.shouldBeNull`, `io.kotest.matchers.shouldBe`, `kotlinx.serialization.json.Json`.

- [ ] **Step 2: Run it and watch it fail**

```bash
./gradlew :shared:jvmTest --tests "com.badmintontracker.shared.localvideo.LocalVideoEntrySerializationTest"
```

Expected: compilation failure, `No parameter with name 'scoreLogId' found`.

- [ ] **Step 3: Add the field**

In `LocalVideoEntry.kt`, add as the **last** constructor parameter, after `resultSeen`:

```kotlin
    /**
     * The match this video was picked for, or null for a video-first import.
     *
     * Held here rather than on the score log because score_logs.video_id has a
     * foreign key to videos(id), and no videos row exists until the pipeline's
     * CREATE_ROW step. Pushing the binding earlier would not fail one row: sync()
     * upserts every dirty row in one call, so it would stop every match on the
     * phone from syncing. See the 2026-08-28 design, §3.1.
     *
     * Last in the parameter list on purpose: Swift constructs this type with every
     * argument spelled out, so appending is a one-line change there.
     */
    val scoreLogId: String? = null,
```

- [ ] **Step 4: Run it and watch it pass**

```bash
./gradlew :shared:jvmTest --tests "com.badmintontracker.shared.localvideo.LocalVideoEntrySerializationTest"
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/badmintontracker/shared/localvideo/LocalVideoEntry.kt \
        shared/src/commonTest/kotlin/com/badmintontracker/shared/localvideo/LocalVideoEntrySerializationTest.kt
git commit -m "feat(shared): remember which match a local video was picked for"
```

---

## Task 2: `attachVideo` and `detachVideo` on the score store

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/badmintontracker/shared/scoring/ScoreLogsRepository.kt`
- Test: `shared/src/commonTest/kotlin/com/badmintontracker/shared/scoring/ScoreLogsRepositoryTest.kt`

**Interfaces:**
- Produces: `ScoreLogsRepository.attachVideo(id: String, videoId: String)`, `ScoreLogsRepository.detachVideo(id: String)`. Both local first and synchronous, both marking the row dirty through the existing private `edit`.

- [ ] **Step 1: Write the failing tests**

Append to `ScoreLogsRepositoryTest.kt`, before the `FakeScoreLogsServer` class:

```kotlin
    @Test
    fun attaching_a_video_binds_the_match_and_survives_a_relaunch() {
        val settings = MapSettings()
        val created = repo(settings).newMatch()
        repo(settings).attachVideo(created.id, "vid-1")

        val reopened = repo(settings).get(created.id)
        reopened?.videoId shouldBe "vid-1"
        reopened?.status shouldBe ScoreLogStatus.BOUND
    }

    @Test
    fun detaching_leaves_the_match_unbound_and_keeps_every_point() {
        // The whole reason score_logs owns the foreign key: delete_match cascades
        // rally_annotations away, so this row is where the coach's courtside work
        // still exists once the video is gone.
        val repo = repo()
        val created = repo.newMatch()
        repo.replaceEvents(created.id, listOf(ScoreEvent.PointTo(Side.HOME)))
        repo.attachVideo(created.id, "vid-1")

        repo.detachVideo(created.id)

        val after = repo.get(created.id)
        after?.videoId.shouldBeNull()
        after?.status shouldBe ScoreLogStatus.UNBOUND
        after?.state()?.currentGame shouldBe SideScore(home = 1, away = 0)
    }

    @Test
    fun attaching_or_detaching_a_match_that_is_gone_is_a_no_op() {
        val repo = repo()
        repo.attachVideo("nope", "vid-1")
        repo.detachVideo("nope")
        repo.logs.value.shouldBeEmpty()
    }
```

And after `syncing_pushes_local_matches_and_then_stops_pushing_them`:

```kotlin
    @Test
    fun a_binding_is_pushed_to_the_server_like_any_other_change() {
        // It is only ever written after CREATE_ROW, so by the time this pushes, the
        // videos row the foreign key points at exists.
        val server = FakeScoreLogsServer()
        val repo = syncingRepo(server)
        val created = repo.newMatch()
        repo.sync().isSuccess shouldBe true

        repo.attachVideo(created.id, "vid-1")
        repo.sync().isSuccess shouldBe true

        server.posts().shouldHaveSize(2)
        server.posts().last().second.shouldContain(""""video_id":"vid-1"""")
        server.posts().last().second.shouldContain(""""status":"bound"""")
    }
```

The last test needs `= runTest` on its signature, matching the other sync tests.

- [ ] **Step 2: Run them and watch them fail**

```bash
./gradlew :shared:jvmTest --tests "com.badmintontracker.shared.scoring.ScoreLogsRepositoryTest"
```

Expected: compilation failure, `Unresolved reference 'attachVideo'`.

- [ ] **Step 3: Add the two mutations**

In `ScoreLogsRepository.kt`, immediately after `fun finish(id: String)`:

```kotlin
    /**
     * Binds this match to a video. Called only once the videos row is known to
     * exist: score_logs.video_id has a foreign key to it, and sync() upserts every
     * dirty row in a single call, so pushing a binding early would stop every match
     * on the phone from syncing rather than just this one.
     */
    fun attachVideo(id: String, videoId: String) =
        edit(id) { it.copy(videoId = videoId, status = ScoreLogStatus.BOUND) }

    /**
     * Takes the video away and leaves the points. Mirrors what the database does on
     * its own when a video is deleted (the ON DELETE SET NULL plus the
     * unbind_score_log_on_video_delete trigger), so that the phone does not go on
     * advertising clips for a video that is gone until the next sync.
     */
    fun detachVideo(id: String) =
        edit(id) { it.copy(videoId = null, status = ScoreLogStatus.UNBOUND) }
```

- [ ] **Step 4: Run them and watch them pass**

```bash
./gradlew :shared:jvmTest --tests "com.badmintontracker.shared.scoring.ScoreLogsRepositoryTest"
```

Expected: PASS, 4 new tests.

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/badmintontracker/shared/scoring/ScoreLogsRepository.kt \
        shared/src/commonTest/kotlin/com/badmintontracker/shared/scoring/ScoreLogsRepositoryTest.kt
git commit -m "feat(shared): attach and detach a video on a scored match"
```

---

## Task 3: The coordinator hook, and the app graph that wires it

`AnalyzeCoordinator` must not learn what a match is: it is a pipeline over one video and its tests should not need a scoring fixture. It gets a hook; `RallyApp` supplies the meaning.

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/badmintontracker/shared/localvideo/AnalyzeCoordinator.kt`
- Modify: `shared/src/commonMain/kotlin/com/badmintontracker/shared/RallyApp.kt`
- Modify: `androidApp/src/main/java/com/badmintontracker/android/RallyAndroidApp.kt`
- Modify: `iosApp/Sources/RallyIOSApp.swift`
- Test: `shared/src/commonTest/kotlin/com/badmintontracker/shared/localvideo/AnalyzeCoordinatorTest.kt`

**Interfaces:**
- Consumes: `ScoreLogsRepository.attachVideo` (Task 2), `LocalVideoEntry.scoreLogId` (Task 1).
- Produces: `AnalyzeCoordinator(..., onVideoRowReady: (entryId: String) -> Unit = {})`, and `RallyApp.analyzeCoordinator(scope, openChannel, log)` as the single place both platforms build a coordinator.

- [ ] **Step 1: Write the failing tests**

Append to `AnalyzeCoordinatorTest.kt`:

```kotlin
    @Test
    fun the_video_row_hook_fires_once_the_row_exists() {
        // Nothing may be told about the videos row before it exists: score_logs
        // has a foreign key to it.
        runTest {
            val ready = mutableListOf<String>()
            localVideos.add(entry())
            clips.clips.value = listOf(clipFor("e1"))
            val c = AnalyzeCoordinator(
                localVideos = localVideos, videos = videos, clips = clips,
                scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)),
                openChannel = { _, _ -> ByteReadChannel(ByteArray(0)) },
                localAnnotations = localAnnotations,
                onVideoRowReady = { ready += it },
            )
            c.startAnalysis("e1", keypoints())
            runCurrent()
            ready shouldBe listOf("e1")
        }
    }

    @Test
    fun the_video_row_hook_fires_on_a_retry_that_resumes_after_create_row() {
        // retry() resumes from the failed step, so a run that failed at TRIGGER
        // never re-enters the CREATE_ROW branch. Hanging the hook off that branch
        // body would silently skip it here, and the match would stay unbound.
        runTest {
            val ready = mutableListOf<String>()
            localVideos.add(entry())
            videos.startProcessingResult = Result.failure(IllegalStateException("boom"))
            val c = AnalyzeCoordinator(
                localVideos = localVideos, videos = videos, clips = clips,
                scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)),
                openChannel = { _, _ -> ByteReadChannel(ByteArray(0)) },
                localAnnotations = localAnnotations,
                onVideoRowReady = { ready += it },
            )
            c.startAnalysis("e1", keypoints())
            runCurrent()
            localVideos.get("e1")?.failedStep shouldBe AnalyzeStep.TRIGGER
            ready.clear()

            videos.startProcessingResult = Result.success(Unit)
            clips.clips.value = listOf(clipFor("e1"))
            c.retry("e1")
            runCurrent()
            ready shouldBe listOf("e1")
        }
    }

    @Test
    fun the_video_row_hook_fires_before_the_entry_is_removed() {
        // runPipeline ends by deleting the entry. Anything the client needs to
        // remember about the attachment has to be durable before that line, or a
        // crash in between orphans the link with no entry left to recover it from.
        runTest {
            var entryAtHook: LocalVideoEntry? = null
            localVideos.add(entry())
            clips.clips.value = listOf(clipFor("e1"))
            val c = AnalyzeCoordinator(
                localVideos = localVideos, videos = videos, clips = clips,
                scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)),
                openChannel = { _, _ -> ByteReadChannel(ByteArray(0)) },
                localAnnotations = localAnnotations,
                onVideoRowReady = { entryAtHook = localVideos.get(it) },
            )
            c.startAnalysis("e1", keypoints())
            runCurrent()
            entryAtHook.shouldNotBeNull()
            localVideos.get("e1").shouldBeNull()
        }
    }
```

Check `FakeVideosRepository` for the name of its `startProcessing` result knob (`shared/src/commonTest/kotlin/com/badmintontracker/shared/testing/FakeVideosRepository.kt`) and use the real one; if there is no settable result, add one in the same style as the fake's existing knobs.

- [ ] **Step 2: Run them and watch them fail**

```bash
./gradlew :shared:jvmTest --tests "com.badmintontracker.shared.localvideo.AnalyzeCoordinatorTest"
```

Expected: compilation failure, `No parameter with name 'onVideoRowReady' found`.

- [ ] **Step 3: Add the hook**

In `AnalyzeCoordinator.kt`, add the constructor parameter after `localAnnotations`:

```kotlin
    /**
     * Called once the videos row for this entry is known to exist, and before the
     * entry is removed. Deliberately an entry id and a moment rather than anything
     * about matches: this class is a pipeline over one video and must not grow a
     * dependency on scoring.
     */
    private val onVideoRowReady: (entryId: String) -> Unit = {},
```

Then in `runPipeline`, immediately **after** the `if (startFrom <= AnalyzeStep.CREATE_ROW) { ... }` block and before the `KEYPOINTS` block:

```kotlin
        // Unconditional, not inside the branch above: retry() resumes from the
        // failed step, so a run that failed at TRIGGER never re-enters CREATE_ROW,
        // and the row exists on every path that reaches here.
        onVideoRowReady(entry.id)
```

- [ ] **Step 4: Run them and watch them pass**

```bash
./gradlew :shared:jvmTest --tests "com.badmintontracker.shared.localvideo.AnalyzeCoordinatorTest"
```

Expected: PASS, all pre-existing coordinator tests still green.

- [ ] **Step 5: Give `RallyApp` the one place a coordinator is built**

Both platforms build their own coordinator today, so the wiring would otherwise have to be written twice and could drift. Add to `RallyApp.kt`:

```kotlin
    /**
     * Builds the analyze pipeline with the scoring link already wired in. Both
     * platforms call this rather than constructing a coordinator themselves, so
     * "a finished upload binds its match" cannot be true on one phone and not the
     * other.
     */
    fun analyzeCoordinator(
        scope: CoroutineScope,
        openChannel: suspend (uri: String, offset: Long) -> ByteReadChannel,
        log: (String) -> Unit = {},
    ): AnalyzeCoordinator = AnalyzeCoordinator(
        localVideos = localVideos,
        videos = videos,
        clips = clips,
        scope = scope,
        openChannel = openChannel,
        log = log,
        localAnnotations = localAnnotations,
        onVideoRowReady = { entryId ->
            // The videos row now exists, so the foreign key can be satisfied. A
            // video-first import has no match and this is a no-op.
            localVideos.get(entryId)?.scoreLogId?.let { scoreLogs.attachVideo(it, entryId) }
        },
    )
```

Add the imports `com.badmintontracker.shared.localvideo.AnalyzeCoordinator`, `io.ktor.utils.io.ByteReadChannel` and `kotlinx.coroutines.CoroutineScope`.

- [ ] **Step 6: Point both platforms at it**

In `androidApp/.../RallyAndroidApp.kt` and `iosApp/Sources/RallyIOSApp.swift`, replace the direct `AnalyzeCoordinator(...)` construction with a call to `rally.analyzeCoordinator(scope:openChannel:log:)`, passing the same scope, channel opener and logger those files already pass. Read each file first; do not guess at their current argument values.

- [ ] **Step 7: Build both apps**

```bash
./gradlew :androidApp:assembleDebug
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
xcodebuild build -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -destination "platform=iOS Simulator,name=iPhone 17 Pro" \
  -derivedDataPath iosApp/build/DerivedData CODE_SIGNING_ALLOWED=NO
```

Expected: both succeed.

- [ ] **Step 8: Commit**

```bash
git add shared/src/commonMain/kotlin/com/badmintontracker/shared/localvideo/AnalyzeCoordinator.kt \
        shared/src/commonMain/kotlin/com/badmintontracker/shared/RallyApp.kt \
        shared/src/commonTest/kotlin/com/badmintontracker/shared/localvideo/AnalyzeCoordinatorTest.kt \
        androidApp/src/main/java/com/badmintontracker/android/RallyAndroidApp.kt \
        iosApp/Sources/RallyIOSApp.swift
git commit -m "feat(shared): bind a match to its video the moment the video row exists"
```

---

## Task 4: `AttachStatus`

One shared sentence per state, so the two platforms cannot describe the same pipeline differently. The precedence matters: after a successful run the entry is gone and the coordinator has dropped its progress, so "no entry" means two different things depending on whether clips have arrived.

**Files:**
- Create: `shared/src/commonMain/kotlin/com/badmintontracker/shared/scoring/AttachStatus.kt`
- Test: `shared/src/commonTest/kotlin/com/badmintontracker/shared/scoring/AttachStatusTest.kt`

**Interfaces:**
- Consumes: `LocalVideoEntry` and `AnalyzeStage` (Task 1).
- Produces: `enum class AttachKind { COURT_NOT_MARKED, UPLOADING, CLIPPING, FAILED, FINISHING_UP }`, `data class AttachStatus(val text: String, val kind: AttachKind)`, and `fun attachStatus(hasVideo: Boolean, entry: LocalVideoEntry?, uploadPercent: Int?, clipCount: Int): AttachStatus?`.

A data class plus an enum rather than a sealed hierarchy: Swift consumes this in a `switch` and a flat pair crosses the bridge without ceremony.

- [ ] **Step 1: Write the failing test**

Create `shared/src/commonTest/kotlin/com/badmintontracker/shared/scoring/AttachStatusTest.kt`:

```kotlin
package com.badmintontracker.shared.scoring

import com.badmintontracker.shared.localvideo.AnalyzeStage
import com.badmintontracker.shared.localvideo.AnalyzeStep
import com.badmintontracker.shared.localvideo.LocalVideoEntry
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class AttachStatusTest {

    private fun entry(
        stage: AnalyzeStage = AnalyzeStage.LOCAL,
        failureMessage: String? = null,
    ) = LocalVideoEntry(
        id = "vid-1", uri = "content://x/vid-1", displayName = "m.mp4",
        durationMs = 1000, sizeBytes = 10, addedAtEpochMs = 0,
        stage = stage,
        failedStep = if (stage == AnalyzeStage.FAILED) AnalyzeStep.PROCESSING else null,
        failureMessage = failureMessage,
        scoreLogId = "log-1",
    )

    @Test
    fun a_match_with_no_video_at_all_has_nothing_to_say() {
        attachStatus(hasVideo = false, entry = null, uploadPercent = null, clipCount = 0).shouldBeNull()
    }

    @Test
    fun a_picked_video_whose_court_is_unmarked_asks_for_the_court() {
        val status = attachStatus(hasVideo = false, entry = entry(), uploadPercent = null, clipCount = 0)
        status?.kind shouldBe AttachKind.COURT_NOT_MARKED
        status?.text shouldBe "Video added, court not marked"
    }

    @Test
    fun an_upload_reports_its_percentage_when_it_has_one() {
        attachStatus(false, entry(AnalyzeStage.UPLOADING), uploadPercent = 42, clipCount = 0)
            ?.text shouldBe "Uploading 42%"
    }

    @Test
    fun an_upload_with_no_percentage_yet_still_says_it_is_uploading() {
        attachStatus(false, entry(AnalyzeStage.UPLOADING), uploadPercent = null, clipCount = 0)
            ?.text shouldBe "Uploading…"
    }

    @Test
    fun the_pipeline_running_is_called_clipping_rather_than_analyzing() {
        // The coach asked for the clips, not for an analysis. The word on the match
        // row is the word he used.
        val status = attachStatus(true, entry(AnalyzeStage.PROCESSING), null, clipCount = 0)
        status?.kind shouldBe AttachKind.CLIPPING
        status?.text shouldBe "Clipping…"
    }

    @Test
    fun a_failure_shows_the_pipelines_own_message() {
        val status = attachStatus(
            true, entry(AnalyzeStage.FAILED, "Analysis finished but found no rallies in this video."),
            null, clipCount = 0,
        )
        status?.kind shouldBe AttachKind.FAILED
        status?.text shouldBe "Analysis finished but found no rallies in this video."
    }

    @Test
    fun a_failure_with_no_message_still_says_something() {
        attachStatus(true, entry(AnalyzeStage.FAILED), null, 0)?.text shouldBe "Analysis failed"
    }

    @Test
    fun a_bound_match_whose_clips_have_landed_says_nothing_at_all() {
        // The rally facet is the answer at that point; a status line as well would
        // be noise on every finished match forever.
        attachStatus(hasVideo = true, entry = null, uploadPercent = null, clipCount = 12).shouldBeNull()
    }

    @Test
    fun a_bound_match_with_no_entry_and_no_clips_yet_is_finishing_up() {
        // The real gap between the pipeline succeeding, which removes the entry and
        // drops its progress, and clips.refresh() bringing the rows back. Two absent
        // signals, two different meanings, and this is the one that needs saying.
        val status = attachStatus(hasVideo = true, entry = null, uploadPercent = null, clipCount = 0)
        status?.kind shouldBe AttachKind.FINISHING_UP
        status?.text shouldBe "Finishing up…"
    }

    @Test
    fun an_analyzed_entry_kept_for_its_notes_is_not_a_pipeline_state() {
        // ANALYZED entries are the ones the pipeline kept because they carry local
        // annotations. Nothing is in flight.
        attachStatus(true, entry(AnalyzeStage.ANALYZED), null, clipCount = 3).shouldBeNull()
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

```bash
./gradlew :shared:jvmTest --tests "com.badmintontracker.shared.scoring.AttachStatusTest"
```

Expected: compilation failure, `Unresolved reference 'attachStatus'`.

- [ ] **Step 3: Write it**

Create `shared/src/commonMain/kotlin/com/badmintontracker/shared/scoring/AttachStatus.kt`:

```kotlin
package com.badmintontracker.shared.scoring

import com.badmintontracker.shared.localvideo.AnalyzeStage
import com.badmintontracker.shared.localvideo.LocalVideoEntry

/** Which pipeline state a match's video is in, for the surfaces that branch on it. */
enum class AttachKind { COURT_NOT_MARKED, UPLOADING, CLIPPING, FAILED, FINISHING_UP }

/**
 * What a match row says about the video being attached to it.
 *
 * [text] is built here rather than per platform for the same reason
 * [buildScoreMatchCard] is: two platforms writing the same sentence are two
 * chances to write it differently.
 */
data class AttachStatus(val text: String, val kind: AttachKind)

/**
 * Derives what to say, from the three signals a match row can see.
 *
 * The precedence is the point. After a successful run the pipeline removes the
 * entry (`runPipeline` ends in `localVideos.remove`) and drops its progress in a
 * `finally`, so "no entry" means one thing when clips have arrived and another
 * when they have not, and the two must not be confused. In particular the second
 * of those is *not* the "no rallies found" case: that is a FAILED entry carrying
 * the pipeline's own message, one branch above.
 *
 * @param hasVideo the score log's video_id is set - the binding reached the server.
 * @param entry the local video picked for this match, while it still exists.
 * @param uploadPercent the coordinator's transient upload progress, 0..100.
 * @param clipCount how many rally clips this match already has.
 */
fun attachStatus(
    hasVideo: Boolean,
    entry: LocalVideoEntry?,
    uploadPercent: Int?,
    clipCount: Int,
): AttachStatus? = when {
    entry != null -> when (entry.stage) {
        AnalyzeStage.LOCAL ->
            AttachStatus("Video added, court not marked", AttachKind.COURT_NOT_MARKED)
        AnalyzeStage.UPLOADING -> AttachStatus(
            uploadPercent?.let { "Uploading $it%" } ?: "Uploading…",
            AttachKind.UPLOADING,
        )
        AnalyzeStage.PROCESSING -> AttachStatus("Clipping…", AttachKind.CLIPPING)
        AnalyzeStage.FAILED ->
            AttachStatus(entry.failureMessage ?: "Analysis failed", AttachKind.FAILED)
        // Kept only because it carries local notes; nothing is in flight.
        AnalyzeStage.ANALYZED -> null
    }
    // No entry and no binding: this match has never been given a video.
    !hasVideo -> null
    // Bound with clips on screen: the rally facet already says everything.
    clipCount > 0 -> null
    else -> AttachStatus("Finishing up…", AttachKind.FINISHING_UP)
}
```

- [ ] **Step 4: Run it and watch it pass**

```bash
./gradlew :shared:jvmTest --tests "com.badmintontracker.shared.scoring.AttachStatusTest"
```

Expected: PASS, 10 tests.

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/badmintontracker/shared/scoring/AttachStatus.kt \
        shared/src/commonTest/kotlin/com/badmintontracker/shared/scoring/AttachStatusTest.kt
git commit -m "feat(shared): one sentence per state of a video attaching to a match"
```

---

## Task 5: `ScoreMatchCard` learns the video id, and stops calling a finished match live

Two things at once because they are the same line of code. The card needs `videoId` so the list can fold a video match into its score match, and `isLive` is currently derived from the fold rather than the status, which makes a match ended early through the overflow say "Scoring" on two screens.

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/badmintontracker/shared/scoring/ScoreMatchCard.kt`
- Test: `shared/src/commonTest/kotlin/com/badmintontracker/shared/scoring/ScoreMatchCardTest.kt`

**Interfaces:**
- Produces: `ScoreMatchCard.videoId: String?`; `fun ScoreLog.isPlayable(): Boolean`; `ScoreMatchCard.isLive` now equal to `log.isPlayable()`.

`isPlayable` is the predicate the rest of the feature gates on, so it is defined once here: a match is playable while it is still `LIVE` **and** the rules have not ended it. A match sitting at 21-18 that the coach left with `Done` is still `LIVE` (that is deliberate - it is what keeps undo reachable), but no further point can be scored on it, so it counts as finished for the purpose of adding a video.

- [ ] **Step 1: Write the failing tests**

Append to `ScoreMatchCardTest.kt`:

```kotlin
    @Test
    fun a_match_ended_early_by_hand_does_not_claim_to_still_be_in_progress() {
        // Reachable through "Finish match" in the board's overflow: status goes
        // UNBOUND with nobody having won. isLive read the fold, not the status, so
        // this match said "Scoring" on its record page and on its list row.
        val card = buildScoreMatchCard(log(toScore(5, 3), status = ScoreLogStatus.UNBOUND))
        card.isLive shouldBe false
        card.statusLine shouldBe "Ended early"
    }

    @Test
    fun a_match_the_rules_have_ended_is_not_playable_even_while_still_marked_live() {
        // The board's Done button navigates without finishing, on purpose: undo has
        // to stay reachable for a match that ended on a mis-tap. So a won match can
        // still be LIVE, and "can another point be scored" is the real question.
        val card = buildScoreMatchCard(log(toScore(21, 18) + toScore(21, 15), status = ScoreLogStatus.LIVE))
        card.isLive shouldBe false
    }

    @Test
    fun a_match_being_scored_right_now_is_live() {
        buildScoreMatchCard(log(toScore(5, 3), status = ScoreLogStatus.LIVE)).isLive shouldBe true
    }

    @Test
    fun the_card_carries_the_video_id_so_a_list_can_fold_the_two_rows_together() {
        val card = buildScoreMatchCard(log(status = ScoreLogStatus.BOUND, videoId = "vid-1"))
        card.videoId shouldBe "vid-1"
        card.hasVideo shouldBe true
    }
```

`log(...)` and `toScore(...)` are the existing helpers at the top of that file; read them before writing so the argument names match.

- [ ] **Step 2: Run them and watch them fail**

```bash
./gradlew :shared:jvmTest --tests "com.badmintontracker.shared.scoring.ScoreMatchCardTest"
```

Expected: FAIL. `videoId` is unresolved, and `statusLine` is `"Scoring"` where `"Ended early"` was expected.

- [ ] **Step 3: Implement**

In `ScoreMatchCard.kt`, add to the data class after `scoreLogId`:

```kotlin
    /** The video attached to this match, or null. The list folds rows on it. */
    val videoId: String?,
```

Add above `buildScoreMatchCard`:

```kotlin
/**
 * Whether another point can still be scored on this match.
 *
 * Not the same as `status == LIVE`. The board's Done button navigates without
 * finishing, deliberately, so that a match ended on a mis-tap can still be undone
 * - which leaves a won match sitting at LIVE. And "Finish match" in the overflow
 * leaves an UNBOUND match with no winner. This is the predicate every surface
 * should ask, rather than either half of it.
 */
fun ScoreLog.isPlayable(): Boolean =
    status == ScoreLogStatus.LIVE && !state().isOver
```

And in `buildScoreMatchCard`, replace the `statusLine` and `isLive` assignments and add `videoId`:

```kotlin
        videoId = log.videoId,
        statusLine = when {
            winner == Side.HOME -> "${sideLabel(log.homePlayers)} won"
            winner == Side.AWAY -> "${sideLabel(log.awayPlayers)} won"
            log.isPlayable() -> "Scoring"
            // Closed by hand before anyone won. The score line above already says
            // where it stopped, so this only has to say that it did.
            else -> "Ended early"
        },
        isLive = log.isPlayable(),
```

- [ ] **Step 4: Run the whole shared suite**

```bash
./gradlew :shared:jvmTest
```

Expected: PASS. Existing `ScoreMatchCardTest` cases still hold: a LIVE match with no winner is live, a won match is not.

- [ ] **Step 5: Fix the two call sites that construct a card by hand**

`androidApp/src/test/java/com/badmintontracker/android/cliplist/MatchRowMergeTest.kt` and `iosApp/Tests/MatchGroupingTests.swift` both build a `ScoreMatchCard` literal and now need `videoId`. Add `videoId = null` / `videoId: nil` to both.

```bash
./gradlew :androidApp:testDebugUnitTest
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add shared/src/commonMain/kotlin/com/badmintontracker/shared/scoring/ScoreMatchCard.kt \
        shared/src/commonTest/kotlin/com/badmintontracker/shared/scoring/ScoreMatchCardTest.kt \
        androidApp/src/test/java/com/badmintontracker/android/cliplist/MatchRowMergeTest.kt \
        iosApp/Tests/MatchGroupingTests.swift
git commit -m "fix(shared): a match ended early no longer reports itself as still being scored"
```

---

## Task 6: Android - one match, one row

**Files:**
- Modify: `androidApp/src/main/java/com/badmintontracker/android/cliplist/MatchRow.kt`
- Modify: `androidApp/src/main/java/com/badmintontracker/android/cliplist/ClipListViewModel.kt`
- Test: `androidApp/src/test/java/com/badmintontracker/android/cliplist/MatchRowMergeTest.kt`

**Interfaces:**
- Consumes: `AttachStatus` (Task 4), `ScoreMatchCard.videoId` (Task 5), `LocalVideoEntry.scoreLogId` (Task 1).
- Produces: `MatchRow.Score(card: ScoreMatchCard, video: MatchSummary?, attach: AttachStatus?)`; `mergeMatchRows(videoMatches, scoreMatches, attachByScoreLogId)`.

- [ ] **Step 1: Write the failing tests**

Add to `MatchRowMergeTest.kt`. Update the `scoreMatch` helper to take a video id:

```kotlin
    private fun scoreMatch(id: String, atMillis: Long, videoId: String? = null) = ScoreMatchCard(
        scoreLogId = id,
        videoId = videoId,
        title = "Thu League",
        createdAtEpochMs = atMillis,
        playersLine = "Coen vs Marco",
        scoreLine = "11-9",
        statusLine = "Scoring",
        isLive = true,
        hasVideo = videoId != null,
    )
```

Then:

```kotlin
    @Test
    fun a_bound_match_is_one_row_and_not_two() {
        // Once the pipeline produces clips, toMatches builds a video match for the
        // same video the score log points at. Unmerged that is the same match twice,
        // in the same section, which is the defect this fold exists to prevent.
        val rows = mergeMatchRows(
            videoMatches = listOf(videoMatch("v1", 300)),
            scoreMatches = listOf(scoreMatch("s1", 200, videoId = "v1")),
        )
        rows.map { it.key } shouldBe listOf("score-s1")
        (rows[0] as MatchRow.Score).video?.videoId shouldBe "v1"
    }

    @Test
    fun a_bound_match_keeps_the_place_its_score_log_earned() {
        // The coach created that match on Tuesday. It must not jump to the top of
        // the list on Friday just because its clips arrived.
        val rows = mergeMatchRows(
            videoMatches = listOf(videoMatch("v1", 900), videoMatch("v2", 500)),
            scoreMatches = listOf(scoreMatch("s1", 100, videoId = "v1")),
        )
        rows.map { it.key } shouldBe listOf("video-v2", "score-s1")
    }

    @Test
    fun a_video_imported_on_its_own_is_still_its_own_row() {
        val rows = mergeMatchRows(listOf(videoMatch("v1", 300)), listOf(scoreMatch("s1", 200)))
        rows.map { it.key } shouldBe listOf("video-v1", "score-s1")
    }

    @Test
    fun a_match_whose_video_is_still_clipping_carries_the_status_but_no_video() {
        val rows = mergeMatchRows(
            videoMatches = emptyList(),
            scoreMatches = listOf(scoreMatch("s1", 200, videoId = "v1")),
            attachByScoreLogId = mapOf("s1" to AttachStatus("Clipping…", AttachKind.CLIPPING)),
        )
        val row = rows.single() as MatchRow.Score
        row.video.shouldBeNull()
        row.attach?.text shouldBe "Clipping…"
    }
```

Imports to add: `com.badmintontracker.shared.scoring.AttachKind`, `com.badmintontracker.shared.scoring.AttachStatus`, `io.kotest.matchers.nulls.shouldBeNull`.

- [ ] **Step 2: Run them and watch them fail**

```bash
./gradlew :androidApp:testDebugUnitTest --tests "com.badmintontracker.android.cliplist.MatchRowMergeTest"
```

Expected: compilation failure, `No value passed for parameter 'video'` / `Unresolved reference 'attachByScoreLogId'`.

- [ ] **Step 3: Grow the row and the merge**

Replace `MatchRow.Score` and `mergeMatchRows` in `MatchRow.kt`:

```kotlin
    /**
     * A scored match, with whatever video it has acquired. Both facets on one row
     * rather than two rows: a match the coach scored and then filmed is one match.
     */
    data class Score(
        val card: ScoreMatchCard,
        /** Non-null once the pipeline has produced clips for this match's video. */
        val video: MatchSummary? = null,
        /** Non-null while a video is attached but not yet clipped. */
        val attach: AttachStatus? = null,
    ) : MatchRow {
        override val key: String get() = "score-${card.scoreLogId}"
        override val sortAtEpochMs: Long get() = card.createdAtEpochMs
    }
```

```kotlin
/**
 * Interleaves the two kinds into one newest-first list, folding a video match into
 * the score match that claims it. Score logs are owner-only by RLS, so this only
 * ever builds the owned section; the shared section is untouched.
 *
 * Sorting a bound row on the score log's createdAt rather than its clips' is
 * deliberate: the match was created before the video existed and must not jump
 * down the list when the clips arrive.
 *
 * The tie-break on [MatchRow.key] is not decoration: two rows created in the same
 * second must not swap places between refreshes.
 */
internal fun mergeMatchRows(
    videoMatches: List<MatchSummary>,
    scoreMatches: List<ScoreMatchCard>,
    attachByScoreLogId: Map<String, AttachStatus> = emptyMap(),
): List<MatchRow> {
    val videoById = videoMatches.associateBy { it.videoId }
    val claimed = scoreMatches.mapNotNull { it.videoId }.toSet()
    val scoreRows = scoreMatches.map { card ->
        MatchRow.Score(
            card = card,
            video = card.videoId?.let(videoById::get),
            attach = attachByScoreLogId[card.scoreLogId],
        )
    }
    val videoRows = videoMatches.filterNot { it.videoId in claimed }.map(MatchRow::Video)
    return (videoRows + scoreRows)
        .sortedWith(compareByDescending<MatchRow> { it.sortAtEpochMs }.thenBy { it.key })
}
```

- [ ] **Step 4: Feed the view model the two new inputs**

In `ClipListViewModel.kt`, take `localVideos: LocalVideoRepository` and `coordinator: AnalyzeCoordinator` as constructor parameters and build the attach map. Replace the `scoreCards` property and the final `combine` with:

```kotlin
    private val scoreCards = scoreLogs.logs.map { logs -> logs.map(::buildScoreMatchCard) }

    /**
     * What each scored match's video is doing, keyed by score log id. Built here
     * because it needs three sources at once: the entry, the coordinator's
     * transient progress, and how many clips the match already has.
     */
    private val attachStatuses = combine(
        localVideos.entries,
        coordinator.progress,
        scoreLogs.logs,
        clips.observeClips(),
    ) { entries, progress, logs, allClips ->
        logs.mapNotNull { log ->
            val entry = entries.firstOrNull { it.scoreLogId == log.id }
            val percent = entry
                ?.let { progress[it.id]?.uploadProgress }
                ?.let { (it * 100).toInt() }
            attachStatus(
                hasVideo = log.videoId != null,
                entry = entry,
                uploadPercent = percent,
                clipCount = allClips.count { it.videoId == log.videoId },
            )?.let { log.id to it }
        }.toMap()
    }
```

and change the tail of `state` to:

```kotlin
    }.combine(scoreCards) { base, cards -> base to cards }
        .combine(attachStatuses) { (base, cards), attach ->
            base.copy(ownedRows = mergeMatchRows(base.ownedMatches, cards, attach))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ClipListState())
```

Add imports for `AnalyzeCoordinator`, `LocalVideoRepository` and `attachStatus`. Then update both construction sites in `AuthGate.kt` (the `Route.ClipList` and `Route.MatchClips` destinations both build a `ClipListViewModel`) to pass `localVideos` and `coordinator`, which `AuthGate` already receives.

- [ ] **Step 5: Run them and watch them pass**

```bash
./gradlew :androidApp:testDebugUnitTest
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add androidApp/src/main/java/com/badmintontracker/android/cliplist/MatchRow.kt \
        androidApp/src/main/java/com/badmintontracker/android/cliplist/ClipListViewModel.kt \
        androidApp/src/main/java/com/badmintontracker/android/AuthGate.kt \
        androidApp/src/test/java/com/badmintontracker/android/cliplist/MatchRowMergeTest.kt
git commit -m "feat(android): a match with a video is one row, not two"
```

---

## Task 7: Android - the match row shows what its video is doing

**Files:**
- Modify: `androidApp/src/main/java/com/badmintontracker/android/cliplist/ClipListScreen.kt`

**Interfaces:**
- Consumes: `MatchRow.Score.video`, `MatchRow.Score.attach` (Task 6), `AttachKind` (Task 4).

- [ ] **Step 1: Keep attached videos out of "On this phone"**

In `ClipListScreen.kt`, change the `localVideoSection` call's `rows` argument:

```kotlin
                    localVideoSection(
                        // A video picked for a match is represented by that match's
                        // row. Listing it here as well is the same match twice.
                        rows = localRows.filter { it.entry.scoreLogId == null },
```

Apply the same filter to the auto-opened result dialog's `LaunchedEffect(localRows)` lookup and to the emptiness check `localRows.isEmpty()`, so a match-attached entry never makes the list look non-empty on its own. Extract `val standaloneRows = localRows.filter { it.entry.scoreLogId == null }` once at the top of the composable and use it in all three places.

- [ ] **Step 2: Give `ScoreMatchRow` the video and the status**

Change its signature and body:

```kotlin
@Composable
private fun ScoreMatchRow(
    row: MatchRow.Score,
    media: MediaRepository,
    onClick: () -> Unit,
    onShareClick: (() -> Unit)?,
    onMarkCourt: () -> Unit,
    onRetry: () -> Unit,
) {
    val card = row.card
    val thumbUrl by produceState<String?>(initialValue = null, row.video?.videoId) {
        val cover = row.video?.coverClip ?: return@produceState
        value = runCatching { media.signedThumbnailUrl(cover) }.getOrNull()
    }
    ...
}
```

The leading 96x54 slot renders the thumbnail when `thumbUrl != null` and the existing `Icons.Default.List` placeholder otherwise, so a bound match looks like every other match in the list.

Under the players line, render the attach status when there is one:

```kotlin
            row.attach?.let { attach ->
                Text(
                    text = attach.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (attach.kind == AttachKind.FAILED) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
```

And in the trailing slot, branch on the kind:

```kotlin
        when (row.attach?.kind) {
            AttachKind.COURT_NOT_MARKED ->
                ShuttlButton(text = "Mark court", onClick = onMarkCourt,
                    variant = ShuttlButtonVariant.Primary, compact = true)
            AttachKind.FAILED ->
                ShuttlButton(text = "Retry", onClick = onRetry,
                    variant = ShuttlButtonVariant.Primary, compact = true)
            AttachKind.UPLOADING, AttachKind.CLIPPING, AttachKind.FINISHING_UP ->
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            // Sharing needs a video: match_shares is keyed on video_id. Present but
            // disabled with the reason until there is one, rather than absent.
            null -> IconButton(onClick = { onShareClick?.invoke() }, enabled = onShareClick != null) {
                Icon(
                    imageVector = Icons.Default.Share,
                    contentDescription = if (onShareClick != null) "Share match"
                                         else "Add a video to share this match",
                )
            }
        }
```

- [ ] **Step 3: Wire the new callbacks at the call site**

In the owned-rows `items` block:

```kotlin
                                    is MatchRow.Score -> ScoreMatchRow(
                                        row = row,
                                        media = media,
                                        onClick = { onScoreMatchClick(row.card) },
                                        onShareClick = row.video?.let { { sheetVideoId = it.videoId } },
                                        onMarkCourt = { onAttachedMarkCourt(row.card.scoreLogId) },
                                        onRetry = { onAttachedRetry(row.card.scoreLogId) },
                                    )
```

Add `onAttachedMarkCourt: (String) -> Unit = {}` and `onAttachedRetry: (String) -> Unit = {}` to `ClipListScreen`'s parameters. In `AuthGate.kt`, wire them by finding the entry for that score log:

```kotlin
                        onAttachedMarkCourt = { scoreLogId ->
                            localVideos.entries.value
                                .firstOrNull { it.scoreLogId == scoreLogId }
                                ?.let { nav.navigate(Route.CourtMarking(it.id)) }
                        },
                        onAttachedRetry = { scoreLogId ->
                            localVideos.entries.value
                                .firstOrNull { it.scoreLogId == scoreLogId }
                                ?.let { localVm.retry(it.id) }
                        },
```

- [ ] **Step 4: Build and install**

```bash
./gradlew :androidApp:installDebug
```

Expected: BUILD SUCCESSFUL. Open the app and confirm the existing score-only rows still render with their placeholder icon and disabled share button, and no row has appeared twice.

- [ ] **Step 5: Commit**

```bash
git add androidApp/src/main/java/com/badmintontracker/android/cliplist/ClipListScreen.kt \
        androidApp/src/main/java/com/badmintontracker/android/AuthGate.kt
git commit -m "feat(android): a scored match's row shows its video's thumbnail and progress"
```

---

## Task 8: iOS - one match, one row, and the row shows what its video is doing

The Swift port of Tasks 6 and 7. One task rather than two: the merge and its rendering live in adjacent files and a reviewer would not accept one without the other.

**Files:**
- Modify: `iosApp/Sources/ClipList/MatchGrouping.swift`
- Modify: `iosApp/Sources/ClipList/ClipListModel.swift`
- Modify: `iosApp/Sources/ClipList/ClipListView.swift`
- Test: `iosApp/Tests/MatchGroupingTests.swift`

**Interfaces:**
- Consumes: the same shared types as Task 6.
- Produces: `MatchRow.score(ScoreRowContent)` where `struct ScoreRowContent { let card: ScoreMatchCard; let video: MatchSummary?; let attach: AttachStatus? }`; `mergeMatchRows(videoMatches:scoreMatches:attachByScoreLogId:)`.

- [ ] **Step 1: Write the failing tests**

Port Task 6's four new cases into `MatchGroupingTests.swift` **with the same names**, in Swift casing:

```swift
    func testABoundMatchIsOneRowAndNotTwo() {
        let rows = mergeMatchRows(
            videoMatches: [videoMatch("v1", 300)],
            scoreMatches: [scoreMatch("s1", 200, videoId: "v1")],
            attachByScoreLogId: [:]
        )
        XCTAssertEqual(rows.map(\.id), ["score-s1"])
        guard case .score(let content) = rows[0] else { return XCTFail("expected a score row") }
        XCTAssertEqual(content.video?.videoId, "v1")
    }

    func testABoundMatchKeepsThePlaceItsScoreLogEarned() {
        let rows = mergeMatchRows(
            videoMatches: [videoMatch("v1", 900), videoMatch("v2", 500)],
            scoreMatches: [scoreMatch("s1", 100, videoId: "v1")],
            attachByScoreLogId: [:]
        )
        XCTAssertEqual(rows.map(\.id), ["video-v2", "score-s1"])
    }

    func testAVideoImportedOnItsOwnIsStillItsOwnRow() {
        let rows = mergeMatchRows(
            videoMatches: [videoMatch("v1", 300)],
            scoreMatches: [scoreMatch("s1", 200)],
            attachByScoreLogId: [:]
        )
        XCTAssertEqual(rows.map(\.id), ["video-v1", "score-s1"])
    }

    func testAMatchWhoseVideoIsStillClippingCarriesTheStatusButNoVideo() {
        let rows = mergeMatchRows(
            videoMatches: [],
            scoreMatches: [scoreMatch("s1", 200, videoId: "v1")],
            attachByScoreLogId: ["s1": AttachStatus(text: "Clipping…", kind: .clipping)]
        )
        guard case .score(let content) = rows[0] else { return XCTFail("expected a score row") }
        XCTAssertNil(content.video)
        XCTAssertEqual(content.attach?.text, "Clipping…")
    }
```

Update the file's existing `scoreMatch` helper to take `videoId: String? = nil` and pass it to the `ScoreMatchCard` initializer, and update the existing `case .score(let card)` pattern matches to `case .score(let content)` plus `content.card`.

- [ ] **Step 2: Run them and watch them fail**

```bash
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
xcodebuild test -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -destination "platform=iOS Simulator,name=iPhone 17 Pro" \
  -derivedDataPath iosApp/build/DerivedData CODE_SIGNING_ALLOWED=NO \
  -only-testing:iosAppTests/MatchGroupingTests
```

Expected: compilation failure, no `ScoreRowContent` and no `attachByScoreLogId:` label.

- [ ] **Step 3: Port the merge**

In `MatchGrouping.swift`:

```swift
/// A scored match plus whatever video it has acquired. Port of Android's
/// `MatchRow.Score`.
struct ScoreRowContent: Identifiable {
    let card: ScoreMatchCard
    /// Non-nil once the pipeline has produced clips for this match's video.
    let video: MatchSummary?
    /// Non-nil while a video is attached but not yet clipped.
    let attach: AttachStatus?

    var id: String { card.scoreLogId }
}

enum MatchRow: Identifiable {
    case video(MatchSummary)
    case score(ScoreRowContent)

    var id: String {
        switch self {
        case .video(let match):   return "video-\(match.videoId)"
        case .score(let content): return "score-\(content.card.scoreLogId)"
        }
    }

    var sortAtEpochMs: Int64 {
        switch self {
        case .video(let match):   return match.latestCreatedAtMillis
        case .score(let content): return content.card.createdAtEpochMs
        }
    }
}

/// Interleaves the two kinds into one newest-first list, folding a video match
/// into the score match that claims it. Port of Android's `mergeMatchRows`; the
/// tie-break on `id` must match Android's exactly, because the same account on
/// two phones has to produce the same order.
func mergeMatchRows(
    videoMatches: [MatchSummary],
    scoreMatches: [ScoreMatchCard],
    attachByScoreLogId: [String: AttachStatus]
) -> [MatchRow] {
    let videoById = Dictionary(uniqueKeysWithValues: videoMatches.map { ($0.videoId, $0) })
    let claimed = Set(scoreMatches.compactMap(\.videoId))
    let scoreRows = scoreMatches.map { card in
        MatchRow.score(ScoreRowContent(
            card: card,
            video: card.videoId.flatMap { videoById[$0] },
            attach: attachByScoreLogId[card.scoreLogId]
        ))
    }
    let videoRows = videoMatches.filter { !claimed.contains($0.videoId) }.map(MatchRow.video)
    return (videoRows + scoreRows).sorted {
        $0.sortAtEpochMs != $1.sortAtEpochMs
            ? $0.sortAtEpochMs > $1.sortAtEpochMs
            : $0.id < $1.id
    }
}
```

- [ ] **Step 4: Build the attach map in the model**

In `ClipListModel.swift`, add stored state and a derivation, mirroring Android's `attachStatuses`:

```swift
    private var localEntries: [LocalVideoEntry] = []
    private var uploadPercentByEntryId: [String: Int] = [:]
    private var scoreLogs: [ScoreLog] = []
```

Add two `Task` loops inside `start()`, alongside the score-logs loop, one over `rally.localVideos.entries` and one over the coordinator's `progress`, each assigning and then calling `regroup()`. The model needs the coordinator, so add `let analyze: AnalyzeCoordinator` to its init and pass it from `ClipListView`, which already holds one.

Keep the score logs themselves (not just the cards), because `attachStatus` needs `videoId`:

```swift
    private func attachMap() -> [String: AttachStatus] {
        var result: [String: AttachStatus] = [:]
        for log in scoreLogs {
            let entry = localEntries.first { $0.scoreLogId == log.id }
            let percent = entry.flatMap { uploadPercentByEntryId[$0.id] }
            let clipCount = clips.filter { $0.videoId == log.videoId }.count
            if let status = AttachStatusKt.attachStatus(
                hasVideo: log.videoId != nil,
                entry: entry,
                uploadPercent: percent.map { KotlinInt(int: Int32($0)) },
                clipCount: Int32(clipCount)
            ) {
                result[log.id] = status
            }
        }
        return result
    }
```

and in `regroup()`:

```swift
        ownedRows = mergeMatchRows(
            videoMatches: owned,
            scoreMatches: scoreCards,
            attachByScoreLogId: attachMap()
        )
```

If SKIE exposes the nullable `Int` parameter as `KotlinInt?` rather than accepting `Int32?`, use whatever the generated header says; build once and let the compiler name the type.

- [ ] **Step 5: Render it**

In `ClipListView.swift`:

- Filter the "On this phone" section and the emptiness check to `localEntries.filter { $0.scoreLogId == nil }`, and filter the `resultEntry` auto-alert lookup the same way.
- Change `case .score(let card)` to `case .score(let content)` and pass the whole `content` to `scoreRow`.
- In `scoreRow`, use an `AsyncImage` over `model.thumbnailUrls[content.video?.coverClipId ?? ""]` when there is a video, falling back to the existing `list.number` placeholder; add `.task { if let v = content.video { await model.thumbnail(forCoverOf: v) } }`.
- Add the attach line under the players line, in `Shuttl.textSecondary`, or `.red` when `content.attach?.kind == .failed`.
- Replace the always-disabled share button with a `switch content.attach?.kind` mirroring Android's: `.courtNotMarked` gives a "Mark court" button that sets `navigationTarget = CourtMarkingRoute(entryId:)`, `.failed` gives "Retry" calling `analyze.retry(entryId:)`, the three running kinds give a `ProgressView().controlSize(.small)`, and `nil` gives the share button, enabled only when `content.video != nil`.

- [ ] **Step 6: Run the tests and build**

```bash
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
xcodebuild test -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -destination "platform=iOS Simulator,name=iPhone 17 Pro" \
  -derivedDataPath iosApp/build/DerivedData CODE_SIGNING_ALLOWED=NO
```

Expected: PASS, and the four new test names match Android's four.

- [ ] **Step 7: Commit**

```bash
git add iosApp/Sources/ClipList/MatchGrouping.swift iosApp/Sources/ClipList/ClipListModel.swift \
        iosApp/Sources/ClipList/ClipListView.swift iosApp/Tests/MatchGroupingTests.swift
git commit -m "feat(ios): a match with a video is one row, and it shows the pipeline's progress"
```

---

## Task 9: Android - the unified match page, rallies facet

Build the new page as a shell that renders only the rallies facet, and move `Route.MatchClips` onto it. Video-first and shared matches must be indistinguishable from today at the end of this task; the points facet arrives in Task 10.

**Files:**
- Create: `androidApp/src/main/java/com/badmintontracker/android/match/MatchScreen.kt`
- Create: `androidApp/src/main/java/com/badmintontracker/android/match/RalliesFacet.kt`
- Modify: `androidApp/src/main/java/com/badmintontracker/android/nav/Route.kt`
- Modify: `androidApp/src/main/java/com/badmintontracker/android/AuthGate.kt`
- Delete: `androidApp/src/main/java/com/badmintontracker/android/cliplist/MatchClipsScreen.kt` (its `ClipSort` and `sortClips` move to `RalliesFacet.kt`)

**Interfaces:**
- Produces: `Route.Match(scoreLogId: String? = null, videoId: String? = null)`; `@Composable fun MatchScreen(...)`; `fun LazyListScope.ralliesFacet(...)`.

- [ ] **Step 1: Add the route**

In `Route.kt`, replace `MatchClips` and add:

```kotlin
    /**
     * One match, however it was made. At least one of the two ids is non-null: a
     * video-first or shared match has only a video, a scored match has a score log
     * and gains a video later.
     */
    @Serializable data class Match(
        val scoreLogId: String? = null,
        val videoId: String? = null,
    ) : Route
```

Leave `Route.ScoreMatch` in place for now; Task 10 removes it.

- [ ] **Step 2: Move the rallies body into a facet**

Create `RalliesFacet.kt` holding `enum class ClipSort`, `fun sortClips(...)` (both moved verbatim from `MatchClipsScreen.kt`) and:

```kotlin
/**
 * The rally half of a match: the label summary strip, the match description and
 * one row per clip. A `LazyListScope` extension rather than a screen, because the
 * match page renders it inside the same list as the points facet and one scroll
 * container per page is the whole point of merging the two screens.
 */
fun LazyListScope.ralliesFacet(
    clips: List<RallyClip>,
    summary: MatchLabelSummary?,
    matchTitle: String?,
    description: String?,
    media: MediaRepository,
    onSummaryClick: () -> Unit,
    onClipClick: (RallyClip) -> Unit,
) { ... }
```

with the body lifted from `MatchClipsScreen`'s `LazyColumn` contents, including the "No rallies in this match." empty state.

- [ ] **Step 3: Write the shell**

Create `MatchScreen.kt` with the `Scaffold`, the top bar (back, sort menu when the rallies facet is showing, share when `match?.isOwned == true`, theme toggle), the `PullToRefreshBox`, the summary sheet and the share sheet - all lifted from `MatchClipsScreen`. It takes the same view models `MatchClipsScreen` did plus the ids:

```kotlin
@Composable
fun MatchScreen(
    vm: ClipListViewModel,
    summaryVm: MatchSummaryViewModel,
    media: MediaRepository,
    shares: SharesRepository,
    themePrefs: ThemePreferenceRepository,
    scoreLogId: String?,
    videoId: String?,
    onBack: () -> Unit,
    onClipClick: (RallyClip) -> Unit,
)
```

For this task the body renders the header title and `ralliesFacet` only.

- [ ] **Step 4: Point navigation at it**

In `AuthGate.kt`, replace the `composable<Route.MatchClips>` block with `composable<Route.Match>`, reading both nullable args and passing them through. Change the list's `onMatchClick` to `nav.navigate(Route.Match(videoId = it.videoId))`.

`MatchSummaryViewModel` needs a video id; when `videoId` is null it must not be constructed. Resolve the effective video id inside the destination:

```kotlin
                composable<Route.Match> { entry ->
                    val args = entry.toRoute<Route.Match>()
                    val logs by rally.scoreLogs.logs.collectAsStateWithLifecycle()
                    // The score log is authoritative: a match bound after this page
                    // was opened must start showing its rallies without a re-entry.
                    val effectiveVideoId = args.videoId
                        ?: logs.firstOrNull { it.id == args.scoreLogId }?.videoId
                    ...
                }
```

Pass `effectiveVideoId` to `MatchSummaryViewModel` and to `MatchScreen`. When it is null, skip building the summary view model and render an empty rallies facet.

- [ ] **Step 5: Build, install and check by hand**

```bash
./gradlew :androidApp:installDebug
```

Open a video-backed match from the list and confirm: the title, the label summary strip, the description, the sort menu, the share sheet, pull-to-refresh and tapping a rally all behave exactly as before. Do the same for a shared match.

- [ ] **Step 6: Commit**

```bash
git add androidApp/src/main/java/com/badmintontracker/android/match/ \
        androidApp/src/main/java/com/badmintontracker/android/nav/Route.kt \
        androidApp/src/main/java/com/badmintontracker/android/AuthGate.kt
git rm androidApp/src/main/java/com/badmintontracker/android/cliplist/MatchClipsScreen.kt
git commit -m "refactor(android): one match page, rallies facet moved onto it"
```

---

## Task 10: Android - the points facet, and the facet selector

**Files:**
- Create: `androidApp/src/main/java/com/badmintontracker/android/match/PointsFacet.kt`
- Create: `androidApp/src/main/java/com/badmintontracker/android/match/MatchViewModel.kt`
- Modify: `androidApp/src/main/java/com/badmintontracker/android/match/MatchScreen.kt`
- Modify: `androidApp/src/main/java/com/badmintontracker/android/nav/Route.kt`
- Modify: `androidApp/src/main/java/com/badmintontracker/android/AuthGate.kt`
- Delete: `androidApp/src/main/java/com/badmintontracker/android/scoring/ScoreMatchScreen.kt`, `.../scoring/ScoreMatchViewModel.kt`
- Test: `androidApp/src/test/java/com/badmintontracker/android/match/MatchViewModelTest.kt`

**Interfaces:**
- Consumes: `ScoreLog.isPlayable()` (Task 5).
- Produces: `MatchViewModel(scoreLogs, scoreLogId)` exposing `MatchPageState(log, match, card, tally, canAddVideo)`; `fun LazyListScope.pointsFacet(...)`. Whether the match has rallies is not on this state: the clips come from `ClipListViewModel`, which the page already holds, and duplicating the answer in two view models is two chances to disagree.

- [ ] **Step 1: Write the failing test**

Create `androidApp/src/test/java/com/badmintontracker/android/match/MatchViewModelTest.kt`:

```kotlin
package com.badmintontracker.android.match

import com.badmintontracker.shared.scoring.MatchSetup
import com.badmintontracker.shared.scoring.ScoreLogsRepository
import com.badmintontracker.shared.scoring.ScoringRules
import com.badmintontracker.shared.scoring.Side
import com.russhwolf.settings.MapSettings
import io.kotest.matchers.shouldBe
import kotlinx.datetime.Instant
import org.junit.Test

class MatchViewModelTest {

    private val t0 = Instant.parse("2026-08-28T18:00:00Z")

    private fun store() = ScoreLogsRepository(MapSettings(), { t0 }, { "owner-1" })

    private fun ScoreLogsRepository.newMatch() = create(
        title = "Thu League",
        homePlayers = listOf("Coen"),
        awayPlayers = listOf("Marco"),
        rules = ScoringRules.BWF_21,
        setup = MatchSetup(doubles = false, firstServer = Side.HOME),
    )

    @Test
    fun a_match_still_being_scored_cannot_take_a_video_yet() {
        // Its action is Score/Resume. Offering both would put "add the video" beside
        // a match that has not been played, and would need LIVE and BOUND to mean
        // something together.
        val store = store()
        val log = store.newMatch()
        val vm = MatchViewModel(store, scoreLogId = log.id)
        vm.state.value.canAddVideo shouldBe false
    }

    @Test
    fun a_match_the_rules_have_ended_can_take_a_video_even_while_still_marked_live() {
        val store = store()
        val log = store.newMatch()
        store.replaceEvents(log.id, List(42) { com.badmintontracker.shared.scoring.ScoreEvent.PointTo(Side.HOME) })
        val vm = MatchViewModel(store, scoreLogId = log.id)
        vm.state.value.canAddVideo shouldBe true
    }

    @Test
    fun a_match_ended_by_hand_can_take_a_video() {
        val store = store()
        val log = store.newMatch()
        store.finish(log.id)
        MatchViewModel(store, scoreLogId = log.id).state.value.canAddVideo shouldBe true
    }

    @Test
    fun a_match_that_already_has_a_video_is_not_offered_another_one() {
        // Replacing means removing the first, which is the delete gesture, not a
        // second picker on the same page.
        val store = store()
        val log = store.newMatch()
        store.finish(log.id)
        store.attachVideo(log.id, "vid-1")
        MatchViewModel(store, scoreLogId = log.id).state.value.canAddVideo shouldBe false
    }

    @Test
    fun a_deleted_match_leaves_the_page_inert_rather_than_crashing() {
        val store = store()
        val log = store.newMatch()
        val vm = MatchViewModel(store, scoreLogId = log.id)
        store.removeLocally(log.id)
        vm.state.value.log shouldBe null
        vm.state.value.canAddVideo shouldBe false
    }
}
```

`ScoringRules.BWF_21` needs 42 points to end a game plus a second game, so the second test's event list must actually finish the match. Check `ScoringRulesTest` for the shortest event list that produces `isOver`, and use that instead of guessing at 42.

- [ ] **Step 2: Run it and watch it fail**

```bash
./gradlew :androidApp:testDebugUnitTest --tests "com.badmintontracker.android.match.MatchViewModelTest"
```

Expected: compilation failure, `Unresolved reference 'MatchViewModel'`.

- [ ] **Step 3: Write the view model**

Create `MatchViewModel.kt`:

```kotlin
/**
 * One match, folded. Everything is derived from the store's flow rather than
 * fetched once, so a point scored elsewhere or a video finishing its pipeline
 * redraws this page without a refresh.
 */
data class MatchPageState(
    /** Null for a video-first or shared match, and for one deleted while open. */
    val log: ScoreLog? = null,
    val match: MatchState? = null,
    val card: ScoreMatchCard? = null,
    val tally: ScoreTagSummary = ScoreTagSummary.EMPTY,
    /** A finished match with no video. The one condition "Add video" is offered on. */
    val canAddVideo: Boolean = false,
)

class MatchViewModel(
    scoreLogs: ScoreLogsRepository,
    private val scoreLogId: String?,
) : ViewModel() {

    val state = scoreLogs.logs
        .map { logs ->
            val log = logs.firstOrNull { it.id == scoreLogId } ?: return@map MatchPageState()
            val match = log.state()
            MatchPageState(
                log = log,
                match = match,
                card = buildScoreMatchCard(log),
                tally = buildScoreTagSummary(match),
                // isPlayable, not `status != LIVE`: the board's Done button leaves a
                // won match at LIVE on purpose, so undo stays reachable.
                canAddVideo = !log.isPlayable() && log.videoId == null,
            )
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, MatchPageState())
}
```

`SharingStarted.Eagerly` rather than `WhileSubscribed`, because `state.value` is read directly in the tests and by the navigation callbacks.

- [ ] **Step 4: Run it and watch it pass**

```bash
./gradlew :androidApp:testDebugUnitTest --tests "com.badmintontracker.android.match.MatchViewModelTest"
```

Expected: PASS.

- [ ] **Step 5: Move the points body into a facet and add the selector**

Create `PointsFacet.kt` holding `fun LazyListScope.pointsFacet(...)`, `TagTally` and `PointRow`, lifted from `ScoreMatchScreen.kt`.

In `MatchScreen.kt`, add:

```kotlin
    // Shown only when the match has both. A selector over one facet is a control
    // that does nothing, and a match that has only rallies must look exactly like
    // it did before this page existed.
    val hasPoints = state.log != null
    val hasRallies = clipsForMatch.isNotEmpty()
    var facet by remember(hasPoints, hasRallies) {
        mutableStateOf(if (hasPoints) Facet.Points else Facet.Rallies)
    }
```

and render a Material 3 `SingleChoiceSegmentedButtonRow` with "Points" and "Rallies" when `hasPoints && hasRallies`. Header: the score line, players line and status line from `state.card` when there is a score log; otherwise the existing date/rally-count headline.

The top bar gains "Export as text" in an overflow when there is a log, keeps Share when `match?.isOwned == true`, and shows the sort menu only while the rallies facet is selected.

- [ ] **Step 6: Retire the old screen**

Delete `ScoreMatchScreen.kt` and `ScoreMatchViewModel.kt`, remove `Route.ScoreMatch`, and change the list's `onScoreMatchClick` to `nav.navigate(Route.Match(scoreLogId = it.scoreLogId))`. Point `Route.Scoring`'s "Done" navigation at `Route.Match(scoreLogId = ...)` too.

- [ ] **Step 7: Run everything, install and check by hand**

```bash
./gradlew :androidApp:testDebugUnitTest :shared:jvmTest && ./gradlew :androidApp:installDebug
```

Open a score-only match (points only, no selector), a video-only match (rallies only, no selector) and confirm each looks like its old screen.

- [ ] **Step 8: Commit**

```bash
git add androidApp/src/main/java/com/badmintontracker/android/match/ \
        androidApp/src/main/java/com/badmintontracker/android/nav/Route.kt \
        androidApp/src/main/java/com/badmintontracker/android/AuthGate.kt \
        androidApp/src/test/java/com/badmintontracker/android/match/MatchViewModelTest.kt
git rm androidApp/src/main/java/com/badmintontracker/android/scoring/ScoreMatchScreen.kt \
       androidApp/src/main/java/com/badmintontracker/android/scoring/ScoreMatchViewModel.kt
git commit -m "feat(android): one match page with points and rallies facets"
```

---

## Task 11: iOS - the unified match page

The Swift port of Tasks 9 and 10 together. One task, because `MatchClipsView` and `ScoreMatchView` are 181 and 177 lines and the merge is a single edit to the navigation graph.

**Files:**
- Create: `iosApp/Sources/Match/MatchView.swift`, `iosApp/Sources/Match/MatchModel.swift`, `iosApp/Sources/Match/PointsFacet.swift`, `iosApp/Sources/Match/RalliesFacet.swift`
- Modify: `iosApp/Sources/ClipList/ClipListView.swift`
- Delete: `iosApp/Sources/ClipList/MatchClipsView.swift`, `iosApp/Sources/Scoring/ScoreMatchView.swift`
- Test: `iosApp/Tests/MatchModelTests.swift`

**Interfaces:**
- Produces: `struct MatchRoute: Hashable { let scoreLogId: String?; let videoId: String? }`; `MatchModel` with the same five properties as `MatchPageState`.

- [ ] **Step 1: Write the failing tests**

Create `iosApp/Tests/MatchModelTests.swift` with Task 10's five cases under the same names, using `SwiftInteropKt.testScoreLogsRepository(now:ownerId:)` as `ScoringModelTests` does. Read `ScoringModelTests.swift` first and copy its fixture shape exactly.

- [ ] **Step 2: Run them and watch them fail**

```bash
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
xcodebuild test -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -destination "platform=iOS Simulator,name=iPhone 17 Pro" \
  -derivedDataPath iosApp/build/DerivedData CODE_SIGNING_ALLOWED=NO \
  -only-testing:iosAppTests/MatchModelTests
```

Expected: `cannot find 'MatchModel' in scope`.

- [ ] **Step 3: Write the model, the two facets and the view**

`MatchModel` mirrors `MatchViewModel`: it iterates `rally.scoreLogs.logs` and publishes `log`, `match`, `card`, `tally` and `canAddVideo`, with `canAddVideo` computed as `!ScoreMatchCardKt.isPlayable(log:) && log.videoId == nil`.

`RalliesFacet.swift` takes `MatchClipsView`'s body: the summary strip, description, clip rows, `refreshSummary` with its generation guard, the summary sheet and the sort picker. `PointsFacet.swift` takes `ScoreMatchView`'s header, tally and point rows.

`MatchView.swift` owns the `List`, the header, the `Picker(...).pickerStyle(.segmented)` shown only when both facets exist, the toolbar (share via `ShareSheetView` when the match is owned and has a video, `ShareLink` for the text export when there is a log, the sort menu while the rallies facet is selected) and the `.task` loops.

- [ ] **Step 4: Re-point navigation**

In `ClipListView.swift`, replace both `.navigationDestination(for: String.self)` and `.navigationDestination(for: ScoreMatchRoute.self)` with one `.navigationDestination(for: MatchRoute.self)`. The video row's `NavigationLink(value:)` becomes `MatchRoute(scoreLogId: nil, videoId: match.videoId)`, the score row's becomes `MatchRoute(scoreLogId: content.card.scoreLogId, videoId: content.card.videoId)`. Delete `ScoreMatchRoute`.

- [ ] **Step 5: Regenerate the project**

```bash
cd iosApp && xcodegen generate && cd ..
```

- [ ] **Step 6: Run the tests**

```bash
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
xcodebuild test -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -destination "platform=iOS Simulator,name=iPhone 17 Pro" \
  -derivedDataPath iosApp/build/DerivedData CODE_SIGNING_ALLOWED=NO
```

Expected: PASS, including `ScoringBoardUITests`, whose `deleteMatch` helper navigates back to the list.

- [ ] **Step 7: Commit**

```bash
cd iosApp && xcodegen generate && cd ..
git add iosApp/Sources/Match/ iosApp/Tests/MatchModelTests.swift \
        iosApp/Sources/ClipList/ClipListView.swift iosApp/iosApp.xcodeproj/project.pbxproj
git rm iosApp/Sources/ClipList/MatchClipsView.swift iosApp/Sources/Scoring/ScoreMatchView.swift
git commit -m "feat(ios): one match page with points and rallies facets"
```

---

## Task 12: Android - the attach flow

Finishing a match asks. The match page owns the picker. Picking goes straight to court marking.

**Files:**
- Modify: `androidApp/src/main/java/com/badmintontracker/android/scoring/ScoringScreen.kt`
- Modify: `androidApp/src/main/java/com/badmintontracker/android/match/MatchScreen.kt`
- Modify: `androidApp/src/main/java/com/badmintontracker/android/localvideo/VideoIntake.kt`
- Modify: `androidApp/src/main/java/com/badmintontracker/android/AuthGate.kt`

**Interfaces:**
- Consumes: `MatchPageState.canAddVideo` (Task 10), `LocalVideoEntry.scoreLogId` (Task 1).
- Produces: `rememberVideoIntake(onAdded:onError:)` unchanged in shape, but `addEntryFromUri` gains `scoreLogId: String?` and `title: String?`.

- [ ] **Step 1: Carry the match through intake**

In `VideoIntake.kt`, add two parameters to `VideoIntake`'s lambdas so a caller can say which match a pick is for:

```kotlin
/** Entry points for getting a video into the app. */
class VideoIntake(
    val record: (forMatch: MatchTarget?) -> Unit,
    val import: (forMatch: MatchTarget?) -> Unit,
)

/**
 * The match a pick is for. The title is not decoration: it rides along on the
 * videos INSERT and the database grants no UPDATE on videos.title, so this is the
 * only moment the video can be given the name the coach already chose.
 */
data class MatchTarget(val scoreLogId: String, val title: String)
```

Hold the pending target in a `remember { mutableStateOf<MatchTarget?>(null) }` between launch and result, the way `pendingRecordUri` already works, and pass it into `addEntryFromUri`, which sets `scoreLogId = target?.scoreLogId` and `title = target?.title` on the new `LocalVideoEntry`.

- [ ] **Step 2: Do not auto-open the details sheet for an attached video**

In `AuthGate.kt`'s `onAdded` callback:

```kotlin
                        onAdded = { entry ->
                            localVideos.add(entry)
                            // A video picked for a match already carries that match's
                            // name, and videos.title is insert-only, so there is
                            // nothing to ask and nowhere to change it later.
                            if (entry.scoreLogId == null) autoDetailsEntryId = entry.id
                        },
```

- [ ] **Step 3: Ask when the match finishes**

In `ScoringScreen.kt`, add above the `Column`:

```kotlin
    // Fires on the transition to un-scoreable, from either exit: the Done button
    // once the rules end it, and "Finish match" in the overflow. Asked once per
    // visit to the board, so a coach who says "not now" and then undoes a rally to
    // fix the last point is not asked again the moment he re-finishes.
    var addVideoAsked by remember { mutableStateOf(false) }
    var addVideoOpen by remember { mutableStateOf(false) }
    LaunchedEffect(match.isOver, log.status) {
        if (!addVideoAsked && !log.isPlayable()) {
            addVideoAsked = true
            addVideoOpen = true
        }
    }
```

and the dialog:

```kotlin
    if (addVideoOpen) {
        AlertDialog(
            onDismissRequest = { addVideoOpen = false; onFinished() },
            title = { Text("Add the video?") },
            text = { Text("Import or record the video of this match and Shuttl will cut it into one clip per rally. You can also do this later from the match itself.") },
            confirmButton = {
                TextButton(onClick = { addVideoOpen = false; onFinished(AttachIntent.Import) }) {
                    Text("Import video")
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { addVideoOpen = false; onFinished(AttachIntent.Record) }) {
                        Text("Record")
                    }
                    TextButton(onClick = { addVideoOpen = false; onFinished() }) { Text("Not now") }
                }
            },
        )
    }
```

Change `ScoringScreen`'s signature from `onBack: () -> Unit` to `onBack: () -> Unit, onFinished: (AttachIntent?) -> Unit = { }`, where `enum class AttachIntent { Import, Record }`, and have the `Done` button call `onFinished(null)` instead of `onBack`.

- [ ] **Step 4: Land on the match page, with the intent**

In `AuthGate.kt`, `Route.Scoring`'s `onFinished` navigates to the match page carrying the intent:

```kotlin
                        onFinished = { intent ->
                            nav.navigate(
                                Route.Match(scoreLogId = args.scoreLogId, attach = intent?.name)
                            ) { popUpTo(Route.Scoring(args.scoreLogId)) { inclusive = true } }
                        },
```

Add `val attach: String? = null` to `Route.Match`. Note the consequence: two `Route.Match` values for the same match no longer compare equal if their `attach` differs, so nothing may pop or `popUpTo` a `Route.Match` by reconstructing it.

- [ ] **Step 5: The match page owns the picker**

In `MatchScreen.kt`, add an "Add video" button in the header when `state.canAddVideo`, plus:

```kotlin
    // The picker is plumbed to this screen and nowhere else, so "attach a video to
    // this match" has one implementation and two entry points: this button, and the
    // prompt the board raises when a match finishes.
    LaunchedEffect(attach) {
        when (attach) {
            "Import" -> onAddVideo(AttachIntent.Import)
            "Record" -> onAddVideo(AttachIntent.Record)
            else -> Unit
        }
    }
```

`onAddVideo` is a new `MatchScreen` parameter. In `AuthGate.kt` it closes the match (if the log is still `LIVE`) and launches intake:

```kotlin
                        onAddVideo = { intent ->
                            val log = rally.scoreLogs.get(args.scoreLogId ?: return@MatchScreen)
                                ?: return@MatchScreen
                            // A match acquires a video only once it is closed:
                            // attaching to a log still marked live would leave a
                            // bound match advertising "Resume scoring".
                            if (log.status == ScoreLogStatus.LIVE) rally.scoreLogs.finish(log.id)
                            val target = MatchTarget(log.id, log.title)
                            when (intent) {
                                AttachIntent.Import -> intake.import(target)
                                AttachIntent.Record -> intake.record(target)
                            }
                        },
```

`rememberVideoIntake` has to be hoisted so both the list destination and the match destination can use it; move it above the `NavHost` and pass the resulting `VideoIntake` into both.

- [ ] **Step 6: Straight to court marking, and back to the match**

The match destination's `onAdded` must push court marking rather than leaving the entry sitting:

```kotlin
                            onAdded = { entry ->
                                localVideos.add(entry)
                                if (entry.scoreLogId == null) autoDetailsEntryId = entry.id
                                // The coach already said he wants this video
                                // analysed; making him find an Analyze button
                                // afterwards is a second decision for a question he
                                // answered.
                                else nav.navigate(Route.CourtMarking(entry.id))
                            },
```

and `Route.CourtMarking`'s `onStartAnalysis` must pop back to whichever page launched it rather than always to the list:

```kotlin
                        onStartAnalysis = { keypoints ->
                            coordinator.startAnalysis(args.entryId, keypoints)
                            if (localVideos.get(args.entryId)?.scoreLogId != null) {
                                // A single pop, not popBackStack(Route.Match(...)):
                                // typed-route popping matches on the serialized
                                // route, and the instance on the stack carries the
                                // `attach` argument this one would not. The match
                                // page is directly below court marking anyway.
                                nav.popBackStack()
                            } else {
                                // Video-first can arrive here from LocalPlayer as
                                // well as from the list, so this one still names its
                                // destination.
                                nav.popBackStack(Route.ClipList, inclusive = false)
                            }
                        },
```

- [ ] **Step 7: Build, install and walk the flow**

```bash
./gradlew :androidApp:installDebug
```

Create a match, score it to 21, tap Done, accept the prompt, pick a video, mark the court, and confirm the match row says "Clipping…" and the match page shows the same. Then repeat choosing "Not now" and confirm "Add video" is on the match page.

- [ ] **Step 8: Commit**

```bash
git add androidApp/src/main/java/com/badmintontracker/android/
git commit -m "feat(android): finishing a match offers its video, and the match page takes it"
```

---

## Task 13: iOS - the attach flow

The Swift port of Task 12.

**Files:**
- Modify: `iosApp/Sources/Scoring/ScoringView.swift`
- Modify: `iosApp/Sources/Match/MatchView.swift`
- Modify: `iosApp/Sources/LocalVideo/LocalVideoIntake.swift`
- Modify: `iosApp/Sources/ClipList/ClipListView.swift`
- Modify: `iosApp/Sources/CourtMarking/CourtMarkingView.swift`

- [ ] **Step 1: Carry the match through intake**

`LocalVideoIntake.add(tempURL:suggestedName:isRecording:)` gains `forMatch: MatchTarget?`, a Swift struct mirroring Android's, and sets `scoreLogId:` and `title:` on the `LocalVideoEntry` it constructs. The `LocalVideoEntry(...)` call there lists every parameter, so append `scoreLogId: forMatch?.scoreLogId` last.

- [ ] **Step 2: Do not auto-open the details sheet for an attached video**

In `ClipListView.swift`'s `.onChange(of: intake.lastAddedId)`, return early when `entry.scoreLogId != nil`.

- [ ] **Step 3: Ask when the match finishes**

In `ScoringView.swift`, add `@State private var addVideoAsked = false` and `@State private var addVideoOpen = false`, an `.onChange(of: model.canScore)` (or of `match.isOver` plus `log.status`) that raises the prompt exactly as Android's `LaunchedEffect` does, and a `.confirmationDialog` with "Import video", "Record", "Not now". All three call `onFinished(_:)`, a new closure parameter.

- [ ] **Step 4: Land on the match page with the intent**

`ClipListView` holds the scoring destination, so `onFinished` sets `matchTarget = MatchRoute(scoreLogId:videoId:attach:)`, adding `attach: AttachIntent?` to `MatchRoute`. Because `MatchRoute` is `Hashable`, `AttachIntent` must be too.

- [ ] **Step 5: The match page owns the picker**

`MatchView` gains `showImporter`/`showRecorder` state, an "Add video" button shown when `model.canAddVideo`, and an `.onAppear` acting on `route.attach`. Its picker calls `intake.add(..., forMatch: MatchTarget(scoreLogId:title:))` and then sets `navigationTarget = CourtMarkingRoute(entryId:)`. Finish the log first when it is still `.live`, calling `rally.scoreLogs.finish(id:)`.

- [ ] **Step 6: Court marking returns to the match**

`CourtMarkingView`'s "Start analysis" currently dismisses to the list. Give it a completion that dismisses back to whichever view pushed it - on iOS `@Environment(\.dismiss)` already does the right thing, since the match page is the pusher; verify by hand rather than assuming.

- [ ] **Step 7: Regenerate, test and walk the flow**

```bash
cd iosApp && xcodegen generate && cd ..
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
xcodebuild test -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -destination "platform=iOS Simulator,name=iPhone 17 Pro" \
  -derivedDataPath iosApp/build/DerivedData CODE_SIGNING_ALLOWED=NO
```

Then walk the same flow on the simulator as Task 12 Step 7, driving it with a UI test plus a `xcrun simctl io <udid> screenshot` poll every 2 seconds (there is no `simctl` touch command).

- [ ] **Step 8: Commit**

```bash
cd iosApp && xcodegen generate && cd ..
git add iosApp/
git commit -m "feat(ios): finishing a match offers its video, and the match page takes it"
```

---

## Task 14: Deleting a video leaves the match and its points behind

**Files:**
- Modify: `androidApp/src/main/java/com/badmintontracker/android/cliplist/ClipListViewModel.kt`
- Modify: `iosApp/Sources/ClipList/ClipListModel.swift`
- Modify: `androidApp/src/main/java/com/badmintontracker/android/cliplist/ClipListScreen.kt`
- Modify: `iosApp/Sources/ClipList/ClipListView.swift`
- Test: `androidApp/src/test/java/com/badmintontracker/android/cliplist/ClipListViewModelDeleteTest.kt` (create)

- [ ] **Step 1: Write the failing test**

The database already unbinds on its own (`ON DELETE SET NULL` plus the `unbind_score_log_on_video_delete` trigger), but the local cache does not learn that until the next sync, so until then the row advertises clips for a video that is gone. Create `ClipListViewModelDeleteTest.kt` asserting that after `deleteMatch(videoId)`, any score log pointing at that video is locally `videoId == null` and `status == UNBOUND`, and still holds every event it had.

Use a `ScoreLogsRepository(MapSettings(), ...)` local-only store and a fake `VideosRepository` whose `deleteMatch` succeeds, in the style of `shared/src/commonTest/.../testing/FakeVideosRepository.kt`.

- [ ] **Step 2: Run it and watch it fail**

```bash
./gradlew :androidApp:testDebugUnitTest --tests "com.badmintontracker.android.cliplist.ClipListViewModelDeleteTest"
```

Expected: FAIL, the log still reports `videoId == "vid-1"`.

- [ ] **Step 3: Unbind on the client too**

In `ClipListViewModel.deleteMatch`:

```kotlin
            videos.deleteMatch(videoId)
                .onSuccess {
                    clips.pruneVideo(videoId)
                    // The database does this too, through ON DELETE SET NULL and the
                    // unbind trigger, but the phone would not learn it until the next
                    // sync and would go on advertising clips for a deleted video.
                    // Idempotent against the trigger, which has already done it.
                    scoreLogs.logs.value
                        .filter { it.videoId == videoId }
                        .forEach { scoreLogs.detachVideo(it.id) }
                    refresh()
                }
```

and the same in `ClipListModel.deleteMatch(videoId:)` on iOS.

- [ ] **Step 4: Say what the delete takes with it**

A bound match's delete confirmation now removes two things. In `ClipListScreen.kt`, choose the message from the row:

```kotlin
    deleteTarget?.let { match ->
        ConfirmDialog(
            title = "Delete match?",
            message = "Delete this match and all its rally clips? This can't be undone.",
            ...
```

For `MatchRow.Score` rows that have a video, the swipe must delete both the clips and the score log, with the message "Delete this match, every point you scored and all its rally clips? This can't be undone." Add a third confirmation target for that case and route the swipe to it when `row.video != null`. Mirror it in `ClipListView.swift`'s `PendingMatchAction`.

- [ ] **Step 5: Run everything**

```bash
./gradlew :shared:jvmTest :androidApp:testDebugUnitTest
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
xcodebuild test -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -destination "platform=iOS Simulator,name=iPhone 17 Pro" \
  -derivedDataPath iosApp/build/DerivedData CODE_SIGNING_ALLOWED=NO
```

Expected: all green.

- [ ] **Step 6: Commit**

```bash
git add androidApp/src/ iosApp/Sources/
git commit -m "fix: deleting a bound match's video keeps the match and its points"
```

---

## Task 15: Docs, changelog, and the checks only a device finds

**Files:**
- Modify: `CHANGELOG.md`
- Modify: `docs/plans/2026-08-28-match-video-attach-design.md`

- [ ] **Step 1: Record the two refinements the implementation forced**

Two decisions were made during implementation that the design did not state, and both change behaviour:

1. §4.5's "Add video is offered only on a finished match" is precisely `!log.isPlayable() && log.videoId == null` - because the board's `Done` button leaves a won match at `LIVE` on purpose, so `status != LIVE` is the wrong predicate.
2. Adding a video to a log still marked `LIVE` finishes it first, so `BOUND` is only ever reached from `UNBOUND` and a bound match never advertises "Resume scoring".

Add both to §4.5 of the design doc.

- [ ] **Step 2: Write the changelog entry**

Under `## [Unreleased]` / `### Added`:

```markdown
- A match scored on the phone can now take its video afterwards, on both
  platforms. Finish a match and Shuttl offers to import or record the video of
  it; say "not now" and the offer is waiting on the match itself. Adding the
  video starts the clipping pipeline straight away, the match row says how far
  along it is, and when it finishes the rallies are part of that same match
  rather than a second entry in the list. A match and its video are one row and
  one page from then on, with Points and Rallies side by side.
```

Under `### Fixed`:

```markdown
- A match ended early through "Finish match" no longer describes itself as still
  being scored on its own page and in the match list.
- Deleting the video of a match scored on the phone now keeps the match, its
  points and its tags. Only the clips go.
```

- [ ] **Step 3: Verify on a real Android device**

Do all of these on the emulator, not by inspection:

1. Create a match, score a full game, tap Done, accept the prompt, import a video, mark the court. Watch the row go "Uploading n%" then "Clipping…" then show a thumbnail with its rallies.
2. Kill the app mid-upload (`adb shell am force-stop com.badmintontracker.android`) and relaunch. The row must pick up where it left off rather than sit on a dead spinner.
3. Delete the video of that bound match. The match, its points and its tags must all still be there, and the row must go back to offering "Add video".
4. Confirm the attached video never appears in "On this phone" while it is clipping.

- [ ] **Step 4: Verify on the iOS simulator**

The same four, driven by a UI test with a `xcrun simctl io <udid> screenshot` poll. The simulator has a live session; sign-in cannot be automated, so if it has expired, ask the user rather than working around it.

- [ ] **Step 5: Commit**

```bash
git add CHANGELOG.md docs/plans/2026-08-28-match-video-attach-design.md
git commit -m "docs: changelog and design refinements for attaching a video to a match"
```

---

## Self-review notes

**Spec coverage.** §4.1 is Task 1. §4.2 is Tasks 2 and 3. §4.3 is Task 3. §4.4 is Tasks 4, 6, 7 and 8. §4.5 is Tasks 12 and 13. §4.6 is Tasks 9, 10 and 11. §4.7 is Task 5. §4.8 is Task 14. §4.9 is Tasks 7, 8 and 11. §5 is Task 0. §7 is spread across every task's test step plus Task 15.

**Not covered, and deliberately.** §6's reconcile is out of scope by the spec, and nothing in these tasks has to be undone to build it: `RECONCILED` stays unreachable, and no surface draws a correspondence between a point and a rally.

**Known thin spots for the implementer.** Tasks 9 through 13 describe UI edits against files that must be read first rather than reproduced here in full - the four features that have to survive the match-page merge (sort menu, share sheet, label summary sheet, pull-to-refresh) are named in Task 9 Step 5 and Task 11 Step 6 as the by-hand check. Task 10 Step 1's "shortest event list that finishes a BWF_21 match" must be looked up in `ScoringRulesTest` rather than guessed.
