# An on-device run produces a match - Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A finished on-device analysis produces a match that looks and behaves exactly like a cloud-analysed one - a row in "My matches", a rally list, a rally page with notes and labels, and a label summary strip - without anything leaving the phone.

**Architecture:** The local side synthesizes the same `RallyClip` and `RallyAnnotation` the cloud path produces, and composite repositories merge cloud and local behind `ClipsRepository`, `AnnotationsRepository` and `MediaRepository`. Every existing screen on both platforms then serves local rallies with no view code changed, because they are the same screens reading the same types. Notes are anchored in video time and re-partitioned against the current clip windows, which makes the pre-analysis migration and re-analysis the same idempotent operation.

**Tech Stack:** Kotlin Multiplatform (shared/androidApp/iosApp), Gradle, Jetpack Compose + Material 3, SwiftUI with `@Observable`/`@MainActor`, SKIE bridging, kotest assertions in Kotlin tests, XCTest on iOS.

**Spec:** `docs/plans/2026-09-11-on-device-rallies-as-matches-design.md`

## Global Constraints

- No em dash (`—`) anywhere written by hand: code comments, commit messages, docs, UI copy. Use a plain dash `-`. Existing file content that is not otherwise being rewritten is left alone.
- No agent attribution on commits: no `Co-Authored-By` trailer, no "Generated with" footer.
- Do not hand-edit `iosApp/iosApp.xcodeproj/project.pbxproj`. Run `xcodegen generate` from `iosApp/` after adding or deleting Swift files. `project.yml` lists `sources: [Sources, Assets.xcassets]` as directories, so new files under `Sources/` need no `project.yml` change.
- Every iOS build/test command must be prefixed with `export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer`, and simulator builds need `CODE_SIGNING_ALLOWED=NO`. Run `xattr -cr iosApp` first if codesign complains about `com.apple.provenance`.
- iOS test names mirror Android test names one for one. That parity is the cross-platform check; if the two lists diverge, the two surfaces have diverged.
- TDD: write the failing test, run it and see it fail for the stated reason, implement, run it and see it pass, commit.
- **Nothing in this plan uploads.** No Supabase write, no storage put, no `rally_clips` row. If a step seems to need one, the design is being misread - see spec §8.
- `rally_index` is 1-based throughout this project. Clip filenames, `LocalClipFile.index` and the synthesized `RallyClip.rallyIndex` all agree on that, and no display adds `+ 1`.
- Android verification builds pin the emulator: `ANDROID_SERIAL=emulator-5554`. A phone is often attached and `installDebug` hits every connected device.

**Test commands:**

```bash
# shared, one class
./gradlew :shared:jvmTest --tests "com.badmintontracker.shared.local.NotePartitionTest"
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

## Task 1: The local clip id namespace

Everything downstream routes on this. A synthesized clip has to be tellable from a cloud one by its id alone, because that is all a repository method gets.

**Files:**
- Create: `shared/src/commonMain/kotlin/com/badmintontracker/shared/local/LocalClips.kt`
- Test: `shared/src/commonTest/kotlin/com/badmintontracker/shared/local/LocalClipsTest.kt`

**Interfaces:**
- Produces: `LocalClipFile(index: Int, startSeconds: Double, endSeconds: Double, durationSeconds: Double, url: String)` with `hasBounds: Boolean`; `LOCAL_CLIP_PREFIX`, `LOCAL_CLIP_OWNER`, `localClipId(entryId, index)`, `isLocalClipId(id)`, `localEntryIdOf(clipId)`, `localClipKeyPrefix(entryId)`.

- [ ] **Step 1: Write the failing test**

Create `LocalClipsTest.kt`:

```kotlin
package com.badmintontracker.shared.local

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class LocalClipsTest {

    @Test
    fun a_clip_id_carries_its_entry_and_its_rally_number() {
        val id = localClipId("e1", 3)
        id shouldBe "local:e1:3"
        isLocalClipId(id) shouldBe true
        localEntryIdOf(id) shouldBe "e1"
    }

    @Test
    fun a_cloud_clip_id_is_not_a_local_one() {
        // Cloud ids are bare UUIDs. Nothing about them may parse as local, or
        // updateTitle would route a server row into the on-disk title store.
        val uuid = "6f1b2c3d-4e5f-6071-8293-a4b5c6d7e8f9"
        isLocalClipId(uuid) shouldBe false
        localEntryIdOf(uuid).shouldBeNull()
    }

    @Test
    fun a_malformed_local_id_yields_no_entry() {
        localEntryIdOf("local:e1").shouldBeNull()
        localEntryIdOf("local:e1:x").shouldBeNull()
        localEntryIdOf("local::2").shouldBeNull()
    }

    @Test
    fun the_key_prefix_matches_every_clip_of_one_entry_and_no_other() {
        val prefix = localClipKeyPrefix("e1")
        localClipId("e1", 1).startsWith(prefix) shouldBe true
        localClipId("e1", 12).startsWith(prefix) shouldBe true
        // "e1" must not match "e12": removeAllFor sweeps on this prefix, and a
        // loose match would delete a different video's notes.
        localClipId("e12", 1).startsWith(prefix) shouldBe false
    }

    @Test
    fun a_clip_recovered_by_filename_reports_no_bounds() {
        // PlayerTrackStore.scanClips returns zero bounds when the sidecar is
        // missing. Callers must be able to ask rather than compare doubles.
        LocalClipFile(1, 0.0, 0.0, 7.5, "file:///x/rally-1.mp4").hasBounds shouldBe false
        LocalClipFile(1, 10.0, 17.5, 7.5, "file:///x/rally-1.mp4").hasBounds shouldBe true
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

```bash
./gradlew :shared:jvmTest --tests "com.badmintontracker.shared.local.LocalClipsTest"
```

Expected: compilation failure, `Unresolved reference: localClipId`.

- [ ] **Step 3: Write it**

Create `LocalClips.kt`:

```kotlin
package com.badmintontracker.shared.local

/**
 * One cut rally as it exists on this phone.
 *
 * The platform's own clip store speaks `File` (Android) or `URL` (iOS), so this
 * is what crosses into shared: the numbers, and a URL as a string.
 *
 * [durationSeconds] is deliberately independent of the bounds. A run recovered
 * by filename has no sidecar and therefore no position within the source video,
 * but its own length is one cheap probe away, and without it every recovered
 * rally would render as "0.0s" in a list the coach is scanning.
 */
data class LocalClipFile(
    val index: Int,
    val startSeconds: Double,
    val endSeconds: Double,
    val durationSeconds: Double,
    val url: String,
) {
    /** Whether this clip's position in the source video is known. */
    val hasBounds: Boolean get() = endSeconds > startSeconds
}

/**
 * What marks a synthesized clip id.
 *
 * Cloud ids are bare UUIDs from postgres, which contain no colon, so the two
 * namespaces cannot collide. Every composite repository routes on this prefix.
 */
const val LOCAL_CLIP_PREFIX = "local:"

/**
 * The owner stamped on a synthesized clip.
 *
 * Never compared to an account id. A clip on this phone belongs to whoever is
 * holding the phone, and routing that through `auth.currentUserId()` - which is
 * null until supabase-kt restores the session - would render a coach's own
 * rallies read-only for the length of that window. See the design, §4.3.
 */
const val LOCAL_CLIP_OWNER = "local"

/** `local:<entryId>:<rallyIndex>`, 1-based like every other rally number here. */
fun localClipId(entryId: String, index: Int): String = "$LOCAL_CLIP_PREFIX$entryId:$index"

fun isLocalClipId(id: String): Boolean = id.startsWith(LOCAL_CLIP_PREFIX)

/**
 * The entry a local clip id belongs to, or null if this is not one.
 *
 * Splits on the LAST colon: the entry id is a UUID today and contains none, but
 * reading from the right costs nothing and cannot be broken by an id format that
 * later does.
 */
fun localEntryIdOf(clipId: String): String? {
    if (!isLocalClipId(clipId)) return null
    val body = clipId.removePrefix(LOCAL_CLIP_PREFIX)
    val cut = body.lastIndexOf(':')
    if (cut <= 0) return null
    if (body.substring(cut + 1).toIntOrNull() == null) return null
    return body.substring(0, cut)
}

/**
 * The prefix every clip id of one entry starts with, and no other entry's does.
 *
 * The trailing colon is load-bearing: `LocalAnnotationsRepository.removeAllFor`
 * sweeps note keys on this, and without it removing "e1" would take "e12" with it.
 */
fun localClipKeyPrefix(entryId: String): String = "$LOCAL_CLIP_PREFIX$entryId:"
```

- [ ] **Step 4: Run it and watch it pass**

```bash
./gradlew :shared:jvmTest --tests "com.badmintontracker.shared.local.LocalClipsTest"
```

Expected: PASS, 5 tests.

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/badmintontracker/shared/local/LocalClips.kt \
        shared/src/commonTest/kotlin/com/badmintontracker/shared/local/LocalClipsTest.kt
git commit -m "feat(shared): an id namespace for clips that never leave the phone"
```

---

## Task 2: A note carries its own video-time anchor

Without this the partition in Task 3 cannot be exact: a re-run overwrites the sidecar, and the clip start a note was relative to is gone with it.

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/badmintontracker/shared/localvideo/LocalAnnotation.kt`
- Test: `shared/src/commonTest/kotlin/com/badmintontracker/shared/localvideo/LocalAnnotationSerializationTest.kt` (create)

**Interfaces:**
- Produces: `LocalAnnotation.videoTimestampSeconds: Float?`, defaulting to `null`, declared **last**.

- [ ] **Step 1: Write the failing test**

Create `LocalAnnotationSerializationTest.kt`:

```kotlin
package com.badmintontracker.shared.localvideo

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlin.test.Test

class LocalAnnotationSerializationTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun a_note_written_before_the_anchor_existed_still_decodes() {
        // LocalAnnotationsRepository.load() swallows a decode failure and returns
        // an empty map, so a non-defaulted field here would silently wipe every
        // note on the phone the first time this build runs.
        val legacy = """
            {"id":"a1","timestampSeconds":12.0,"body":"nice","createdAtEpochMs":0}
        """.trimIndent()
        val note = json.decodeFromString(LocalAnnotation.serializer(), legacy)
        note.videoTimestampSeconds.shouldBeNull()
    }

    @Test
    fun a_note_on_a_rally_remembers_where_it_is_in_the_whole_video() {
        val note = LocalAnnotation(
            id = "a1", timestampSeconds = 2.0f, body = "net kill",
            createdAtEpochMs = 0, videoTimestampSeconds = 132.5f,
        )
        val round = json.decodeFromString(
            LocalAnnotation.serializer(),
            json.encodeToString(LocalAnnotation.serializer(), note),
        )
        round.videoTimestampSeconds shouldBe 132.5f
        round.timestampSeconds shouldBe 2.0f
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

```bash
./gradlew :shared:jvmTest --tests "com.badmintontracker.shared.localvideo.LocalAnnotationSerializationTest"
```

Expected: compilation failure, `No parameter with name 'videoTimestampSeconds' found`.

- [ ] **Step 3: Add the field**

In `LocalAnnotation.kt`, add as the **last** constructor parameter, after `createdAtEpochMs`:

```kotlin
    /**
     * Where this note sits in the whole video, in seconds.
     *
     * [timestampSeconds] is relative to whatever the note is filed under - the
     * video itself, or one cut rally - and that is what every player seeks to.
     * This is the anchor that survives re-analysis: a re-run overwrites the clip
     * sidecar with new windows, so the clip start the timestamp was relative to
     * is gone, and the partition would have nothing exact to normalize through.
     *
     * Null on every note written before this field existed. Those are all filed
     * under the video, where [timestampSeconds] already is video time, so the
     * null case reads correctly rather than needing a migration.
     *
     * Last in the parameter list on purpose: Swift constructs this type with
     * every argument spelled out, so appending is a one-line change there.
     */
    val videoTimestampSeconds: Float? = null,
```

- [ ] **Step 4: Run it and watch it pass**

```bash
./gradlew :shared:jvmTest --tests "com.badmintontracker.shared.localvideo.LocalAnnotationSerializationTest"
```

Expected: PASS, 2 tests.

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/badmintontracker/shared/localvideo/LocalAnnotation.kt \
        shared/src/commonTest/kotlin/com/badmintontracker/shared/localvideo/LocalAnnotationSerializationTest.kt
git commit -m "feat(shared): a local note remembers where it is in the whole video"
```

---

## Task 3: `planNotePartition`

The one rule from spec §5.1, as a pure function. This is the only piece in the feature that can lose a coach's work, so it is tested before anything calls it.

**Files:**
- Create: `shared/src/commonMain/kotlin/com/badmintontracker/shared/local/NotePartition.kt`
- Test: `shared/src/commonTest/kotlin/com/badmintontracker/shared/local/NotePartitionTest.kt`

**Interfaces:**
- Consumes: `LocalClipFile` (Task 1).
- Produces: `AnchoredNote(id: String, key: String, timestampSeconds: Double, videoTimestampSeconds: Double)`, `NoteMove(noteId: String, fromKey: String, toKey: String, timestampSeconds: Double, videoTimestampSeconds: Double)`, `NOTE_PARTITION_EPSILON_SECONDS`, `containingClip(clips, videoTimeSeconds): LocalClipFile?`, `planNotePartition(entryId, notes, clips): List<NoteMove>`.

- [ ] **Step 1: Write the failing test**

Create `NotePartitionTest.kt`:

```kotlin
package com.badmintontracker.shared.local

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class NotePartitionTest {

    private fun clip(index: Int, start: Double, end: Double) =
        LocalClipFile(index, start, end, end - start, "file:///x/rally-$index.mp4")

    /** Two rallies with a gap between them: 10-20 and 30-40. */
    private val clips = listOf(clip(1, 10.0, 20.0), clip(2, 30.0, 40.0))

    private fun videoNote(id: String, at: Double) =
        AnchoredNote(id = id, key = "e1", timestampSeconds = at, videoTimestampSeconds = at)

    private fun clipNote(id: String, index: Int, at: Double, video: Double) =
        AnchoredNote(
            id = id, key = localClipId("e1", index),
            timestampSeconds = at, videoTimestampSeconds = video,
        )

    @Test
    fun a_note_inside_a_rally_moves_onto_it_rebased_to_the_clip() {
        val moves = planNotePartition("e1", listOf(videoNote("a", 14.0)), clips)
        moves.single().let {
            it.noteId shouldBe "a"
            it.fromKey shouldBe "e1"
            it.toKey shouldBe "local:e1:1"
            it.timestampSeconds shouldBe 4.0
            it.videoTimestampSeconds shouldBe 14.0
        }
    }

    @Test
    fun a_note_in_dead_air_stays_on_the_video() {
        // 25.0 is between the two rallies. Coercing it to a clip edge would put a
        // coach's note on footage it was not about, and it would be
        // indistinguishable from one deliberately placed there.
        planNotePartition("e1", listOf(videoNote("a", 25.0)), clips) shouldBe emptyList()
    }

    @Test
    fun a_note_whose_rally_is_gone_falls_back_to_video_time() {
        // The note was on rally 2 at 3.0s in, i.e. 33.0s into the video. A re-run
        // produced only one rally, and nothing contains 33.0 any more.
        val moves = planNotePartition("e1", listOf(clipNote("a", 2, 3.0, 33.0)), listOf(clips[0]))
        moves.single().let {
            it.fromKey shouldBe "local:e1:2"
            it.toKey shouldBe "e1"
            it.timestampSeconds shouldBe 33.0
        }
    }

    @Test
    fun a_note_moves_to_the_rally_that_now_contains_it() {
        // Re-analysis shifted the boundary: what was rally 1 at 14.0s in video
        // time is now inside rally 2. The note follows the footage, not the index.
        val after = listOf(clip(1, 0.0, 8.0), clip(2, 12.0, 22.0))
        val moves = planNotePartition("e1", listOf(clipNote("a", 1, 4.0, 14.0)), after)
        moves.single().let {
            it.toKey shouldBe "local:e1:2"
            it.timestampSeconds shouldBe 2.0
        }
    }

    @Test
    fun running_it_twice_changes_nothing() {
        // The idempotence the design rests on: the second pass is what runs on
        // every app start for the life of the entry.
        val first = planNotePartition("e1", listOf(videoNote("a", 14.0)), clips)
        val settled = AnchoredNote(
            id = "a", key = first.single().toKey,
            timestampSeconds = first.single().timestampSeconds,
            videoTimestampSeconds = first.single().videoTimestampSeconds,
        )
        planNotePartition("e1", listOf(settled), clips) shouldBe emptyList()
    }

    @Test
    fun a_sub_epsilon_difference_is_not_a_move() {
        // A boundary that wobbled by a rounding error must not rewrite the blob,
        // or repeated re-analysis accumulates float noise instead of settling.
        val nudged = listOf(clip(1, 10.02, 20.0), clips[1])
        planNotePartition("e1", listOf(clipNote("a", 1, 4.0, 14.0)), nudged) shouldBe emptyList()
    }

    @Test
    fun overlapping_rallies_take_the_note_it_sits_furthest_inside() {
        // padRallyWindows preserves overlap because refineRallies produces
        // overlapping rallies (ClipWindows.kt:28), so containment is genuinely
        // ambiguous here. 19.0 is 9.0s inside rally 1 and 1.0s inside rally 2.
        val overlapping = listOf(clip(1, 10.0, 20.0), clip(2, 18.0, 28.0))
        val moves = planNotePartition("e1", listOf(videoNote("a", 19.0)), overlapping)
        moves.single().toKey shouldBe "local:e1:1"
    }

    @Test
    fun an_exact_tie_in_an_overlap_goes_to_the_lower_rally() {
        // 19.0 sits 1.0s inside rally 1's tail and 1.0s inside rally 2's head.
        // Anything that depends on list order here moves a note between runs.
        val overlapping = listOf(clip(1, 10.0, 20.0), clip(2, 18.0, 28.0))
        val moves = planNotePartition("e1", listOf(videoNote("a", 19.0)), overlapping.reversed())
        moves.singleOrNull().shouldBeNull()   // 19.0 is not a tie; see the test above
        val tied = listOf(clip(1, 10.0, 20.0), clip(2, 18.0, 20.0))
        planNotePartition("e1", listOf(videoNote("b", 19.0)), tied.reversed())
            .single().toKey shouldBe "local:e1:1"
    }

    @Test
    fun an_entry_whose_clips_have_no_bounds_is_left_alone() {
        // scanClips recovery: the files are there, their place in the video is
        // not. Guessing would relocate notes into rallies they are not about.
        val recovered = listOf(
            LocalClipFile(1, 0.0, 0.0, 7.5, "file:///x/rally-1.mp4"),
            LocalClipFile(2, 0.0, 0.0, 9.0, "file:///x/rally-2.mp4"),
        )
        planNotePartition("e1", listOf(videoNote("a", 14.0)), recovered) shouldBe emptyList()
    }

    @Test
    fun containment_picks_nothing_outside_every_clip() {
        containingClip(clips, 25.0).shouldBeNull()
        containingClip(clips, 14.0)?.index shouldBe 1
        // Boundaries are inclusive at the start and the end: a note made on the
        // very last frame of a rally belongs to that rally.
        containingClip(clips, 10.0)?.index shouldBe 1
        containingClip(clips, 20.0)?.index shouldBe 1
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

```bash
./gradlew :shared:jvmTest --tests "com.badmintontracker.shared.local.NotePartitionTest"
```

Expected: compilation failure, `Unresolved reference: AnchoredNote`.

- [ ] **Step 3: Write it**

Create `NotePartition.kt`:

```kotlin
package com.badmintontracker.shared.local

import kotlin.math.abs
import kotlin.math.min

/**
 * Below this, in seconds, a recomputed position counts as unchanged.
 *
 * Not about row churn - this writes one JSON blob on the phone - but about
 * settling. Re-analysis of the same footage produces boundaries that differ in
 * the last decimal place, and rewriting every note by a rounding error on every
 * run is how an operation that should be a no-op becomes a slow drift.
 *
 * The test is `abs(difference) <= epsilon`. Behaviour exactly at the threshold
 * is not a contract: 0.05 in decimal is not 0.05 in binary, so treat it as a
 * band rather than a line.
 */
const val NOTE_PARTITION_EPSILON_SECONDS: Double = 0.05

/**
 * One note, with both of its positions: where it is filed, and where it is in
 * the video.
 *
 * [key] is the store key - the entry id for a note on the whole video, a local
 * clip id for one on a rally. [timestampSeconds] is relative to whichever of
 * those it is, because that is what a player seeks to.
 */
data class AnchoredNote(
    val id: String,
    val key: String,
    val timestampSeconds: Double,
    val videoTimestampSeconds: Double,
)

/** Where one note should be filed instead, and at what offset. */
data class NoteMove(
    val noteId: String,
    val fromKey: String,
    val toKey: String,
    val timestampSeconds: Double,
    val videoTimestampSeconds: Double,
)

/**
 * The clip a moment in the video belongs to, or null when it belongs to none.
 *
 * Bounds are inclusive at both ends: a note made on a rally's last frame is
 * about that rally.
 *
 * Clips can overlap - `padRallyWindows` preserves the overlap that
 * `refineRallies` produces (`ClipWindows.kt:28`) - so this has to choose. The
 * note goes to the clip it sits furthest inside, measured as the distance to
 * the nearer boundary, because a moment in an overlap is usually the action in
 * one clip and the padding of its neighbour. Exact ties go to the lower rally
 * index, so the answer never depends on the order of the list.
 */
fun containingClip(clips: List<LocalClipFile>, videoTimeSeconds: Double): LocalClipFile? =
    clips
        .filter { it.hasBounds && videoTimeSeconds >= it.startSeconds && videoTimeSeconds <= it.endSeconds }
        .minWithOrNull(
            compareByDescending<LocalClipFile> {
                min(videoTimeSeconds - it.startSeconds, it.endSeconds - videoTimeSeconds)
            }.thenBy { it.index },
        )

/**
 * Where every note of one entry should live, given the rallies that exist now.
 *
 * The whole mechanism from the design's §5: a note lives in video time when it
 * belongs to no rally and in clip time when it belongs to one, so re-filing is
 * a pure function of (notes, windows). That makes three separate-looking
 * problems one operation - notes made before the video was ever analysed, notes
 * made on a rally, and notes whose rally moved or vanished under a re-run - and
 * makes running it twice a no-op.
 *
 * Returns only the notes that actually move. An empty result is the settled
 * state, which is what this returns on every app start after the first.
 *
 * [clips] with no bounds are ignored entirely rather than treated as rallies at
 * zero: a run recovered by filename (`PlayerTrackStore.scanClips`) has real
 * files whose place in the video is unknown, and filing a note against a
 * guessed position puts it on footage it was not about.
 */
fun planNotePartition(
    entryId: String,
    notes: List<AnchoredNote>,
    clips: List<LocalClipFile>,
    epsilonSeconds: Double = NOTE_PARTITION_EPSILON_SECONDS,
): List<NoteMove> {
    val usable = clips.filter { it.hasBounds }
    if (usable.isEmpty()) return emptyList()

    return notes.mapNotNull { note ->
        val video = note.videoTimestampSeconds
        val target = containingClip(usable, video)
        val toKey = if (target == null) entryId else localClipId(entryId, target.index)
        val timestamp = if (target == null) video else video - target.startSeconds

        val settled = toKey == note.key && abs(timestamp - note.timestampSeconds) <= epsilonSeconds
        if (settled) null
        else NoteMove(
            noteId = note.id,
            fromKey = note.key,
            toKey = toKey,
            timestampSeconds = timestamp,
            videoTimestampSeconds = video,
        )
    }
}
```

- [ ] **Step 4: Run it and watch it pass**

```bash
./gradlew :shared:jvmTest --tests "com.badmintontracker.shared.local.NotePartitionTest"
```

Expected: PASS, 10 tests.

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/badmintontracker/shared/local/NotePartition.kt \
        shared/src/commonTest/kotlin/com/badmintontracker/shared/local/NotePartitionTest.kt
git commit -m "feat(shared): file a local note against the rally that contains it"
```

---

## Task 4: The note store learns about rallies

`LocalAnnotationsRepository` currently keys everything by entry id. It gains clip keys, the write that applies a partition, and a removal that does not leave rally notes behind.

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/badmintontracker/shared/localvideo/LocalAnnotationsRepository.kt`
- Test: `shared/src/commonTest/kotlin/com/badmintontracker/shared/localvideo/LocalAnnotationsRepositoryTest.kt`

**Interfaces:**
- Consumes: `AnchoredNote`, `NoteMove`, `localClipKeyPrefix` (Tasks 1 and 3).
- Produces: `add(..., videoTimestampSeconds: Float?)` (new last parameter, defaulting to null), `anchoredNotesFor(entryId): List<AnchoredNote>`, `applyMoves(moves: List<NoteMove>)`, `countsByKey: StateFlow<Map<String, Int>>`.

- [ ] **Step 1: Write the failing tests**

Append to `LocalAnnotationsRepositoryTest.kt`:

```kotlin
    @Test
    fun removing_a_video_takes_its_rally_notes_with_it() {
        val repo = LocalAnnotationsRepository(MapSettings())
        repo.add("e1", 5f, "on the video", null)
        repo.add("local:e1:2", 3f, "on a rally", null, videoTimestampSeconds = 33f)
        repo.add("local:e12:1", 3f, "another video entirely", null)

        repo.removeAllFor("e1")

        repo.annotationsFor("e1") shouldBe emptyList()
        repo.annotationsFor("local:e1:2") shouldBe emptyList()
        // The prefix sweep must not be a plain startsWith("local:e1"): "e12"
        // starts with "e1" and its notes are a different video's.
        repo.annotationsFor("local:e12:1").size shouldBe 1
    }

    @Test
    fun anchored_notes_gather_both_kinds_and_fill_in_a_missing_anchor() {
        val repo = LocalAnnotationsRepository(MapSettings())
        // No anchor: written by a build before the field existed, and filed
        // under the video, where the timestamp already is video time.
        repo.add("e1", 5f, "legacy", null)
        repo.add("local:e1:2", 3f, "on a rally", null, videoTimestampSeconds = 33f)

        val anchored = repo.anchoredNotesFor("e1").sortedBy { it.videoTimestampSeconds }
        anchored.map { it.key } shouldBe listOf("e1", "local:e1:2")
        anchored.map { it.videoTimestampSeconds } shouldBe listOf(5.0, 33.0)
        anchored.map { it.timestampSeconds } shouldBe listOf(5.0, 3.0)
    }

    @Test
    fun a_clip_keyed_note_with_no_anchor_is_not_gathered() {
        // There is no way to say where it is in the video, and inventing one
        // would file it against a rally it may not be in. It stays where it is.
        val repo = LocalAnnotationsRepository(MapSettings())
        repo.add("local:e1:2", 3f, "no anchor", null)
        repo.anchoredNotesFor("e1") shouldBe emptyList()
        repo.annotationsFor("local:e1:2").size shouldBe 1
    }

    @Test
    fun applying_moves_refiles_notes_and_keeps_their_bodies() {
        val repo = LocalAnnotationsRepository(MapSettings())
        val note = repo.add("e1", 14f, "net kill", null)

        repo.applyMoves(
            listOf(
                NoteMove(
                    noteId = note.id, fromKey = "e1", toKey = "local:e1:1",
                    timestampSeconds = 4.0, videoTimestampSeconds = 14.0,
                ),
            ),
        )

        repo.annotationsFor("e1") shouldBe emptyList()
        repo.annotationsFor("local:e1:1").single().let {
            it.id shouldBe note.id
            it.body shouldBe "net kill"
            it.timestampSeconds shouldBe 4f
            it.videoTimestampSeconds shouldBe 14f
        }
    }

    @Test
    fun counts_by_key_tracks_writes() {
        // This is what a rally row's "N notes" reads, so it has to be a flow
        // rather than a value read once: a note added on the rally page must
        // change the count on the list behind it.
        val repo = LocalAnnotationsRepository(MapSettings())
        repo.countsByKey.value["local:e1:1"] shouldBe null
        repo.add("local:e1:1", 3f, "one", null, videoTimestampSeconds = 13f)
        repo.countsByKey.value["local:e1:1"] shouldBe 1
        repo.add("local:e1:1", 5f, "two", null, videoTimestampSeconds = 15f)
        repo.countsByKey.value["local:e1:1"] shouldBe 2
    }
```

Add the imports the file is missing: `com.badmintontracker.shared.local.NoteMove`.

- [ ] **Step 2: Run them and watch them fail**

```bash
./gradlew :shared:jvmTest --tests "com.badmintontracker.shared.localvideo.LocalAnnotationsRepositoryTest"
```

Expected: compilation failure, `No parameter with name 'videoTimestampSeconds' found` and `Unresolved reference: anchoredNotesFor`.

- [ ] **Step 3: Grow the repository**

In `LocalAnnotationsRepository.kt`, change the class doc, add the anchor to `add`, and add the three new members. The file becomes:

```kotlin
package com.badmintontracker.shared.localvideo

import com.badmintontracker.shared.local.AnchoredNote
import com.badmintontracker.shared.local.NoteMove
import com.badmintontracker.shared.local.localClipKeyPrefix
import com.badmintontracker.shared.model.AnnotationLabel
import com.badmintontracker.shared.util.SyncLock
import com.badmintontracker.shared.util.nowEpochMs
import com.badmintontracker.shared.util.randomUuid
import com.badmintontracker.shared.util.withLock
import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * On-phone annotations, persisted as JSON in Settings.
 *
 * Keyed by local video id for a note on the whole video, and by local clip id
 * (`local:<entryId>:<index>`) for one on a cut rally. One map rather than two
 * stores because the two are the same note moving between two ways of being
 * filed - see the design's §5.1 - and a note must never exist in both.
 */
class LocalAnnotationsRepository(private val settings: Settings) {

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer =
        MapSerializer(String.serializer(), ListSerializer(LocalAnnotation.serializer()))

    private val state = MutableStateFlow(load())
    val byVideoId: StateFlow<Map<String, List<LocalAnnotation>>> = state.asStateFlow()

    private val counts = MutableStateFlow(state.value.mapValues { it.value.size })

    /**
     * How many notes each key holds.
     *
     * Separate from [byVideoId] because a rally list redraws on it: synthesizing
     * a clip needs a count, not the notes, and mapping the whole store to sizes
     * on every emission of a flow the list already collects is work per row per
     * frame. Kept in step by [persist], which is the only writer.
     */
    val countsByKey: StateFlow<Map<String, Int>> = counts.asStateFlow()

    // Serializes read-modify-write cycles; callers include app-scoped analyze
    // pipelines running concurrently on Dispatchers.Default.
    private val lock = SyncLock()

    fun annotationsFor(videoId: String): List<LocalAnnotation> =
        state.value[videoId].orEmpty()

    fun hasAnnotations(videoId: String): Boolean =
        state.value[videoId]?.isNotEmpty() == true

    fun add(
        videoId: String,
        timestampSeconds: Float,
        body: String,
        label: AnnotationLabel?,
        videoTimestampSeconds: Float? = null,
    ): LocalAnnotation {
        val annotation = LocalAnnotation(
            id = randomUuid(),
            timestampSeconds = timestampSeconds,
            body = body,
            labelName = label?.name,
            labelColor = label?.colorKey,
            createdAtEpochMs = nowEpochMs(),
            videoTimestampSeconds = videoTimestampSeconds,
        )
        mutate(videoId) { it + annotation }
        return annotation
    }

    fun delete(videoId: String, annotationId: String) =
        mutate(videoId) { list -> list.filterNot { it.id == annotationId } }

    /**
     * Every note of one entry, wherever it is filed, with its video-time anchor.
     *
     * A note on a rally with no anchor is omitted rather than guessed at: it was
     * written by a build that did not record one, and the clip start it was
     * relative to may since have moved. Leaving it where it is keeps it on the
     * rally the coach put it on.
     */
    fun anchoredNotesFor(entryId: String): List<AnchoredNote> {
        val prefix = localClipKeyPrefix(entryId)
        return state.value.entries
            .filter { (key, _) -> key == entryId || key.startsWith(prefix) }
            .flatMap { (key, notes) ->
                notes.mapNotNull { note ->
                    val video = note.videoTimestampSeconds
                        ?: note.timestampSeconds.takeIf { key == entryId }
                        ?: return@mapNotNull null
                    AnchoredNote(
                        id = note.id,
                        key = key,
                        timestampSeconds = note.timestampSeconds.toDouble(),
                        videoTimestampSeconds = video.toDouble(),
                    )
                }
            }
    }

    /**
     * Re-files the notes a partition decided to move, in one write.
     *
     * One write rather than a delete and an add per note: this runs on app start
     * for every analysed entry, and a note that exists in neither key because
     * the process died between the two halves is a note the coach has lost.
     */
    fun applyMoves(moves: List<NoteMove>) {
        if (moves.isEmpty()) return
        lock.withLock {
            val byId = moves.associateBy { it.noteId }
            val next = LinkedHashMap<String, MutableList<LocalAnnotation>>()
            state.value.forEach { (key, notes) -> next[key] = notes.toMutableList() }

            for (move in moves) {
                val from = next[move.fromKey] ?: continue
                val index = from.indexOfFirst { it.id == move.noteId }
                if (index < 0) continue
                val note = from.removeAt(index)
                next.getOrPut(move.toKey) { mutableListOf() } += note.copy(
                    timestampSeconds = move.timestampSeconds.toFloat(),
                    videoTimestampSeconds = move.videoTimestampSeconds.toFloat(),
                )
            }
            check(byId.size == moves.size) { "a note may be moved at most once per plan" }

            persist(
                next
                    .filterValues { it.isNotEmpty() }
                    .mapValues { (_, list) -> list.sortedBy { it.timestampSeconds } },
            )
        }
    }

    /**
     * Everything filed for one entry: the notes on the video and the notes on
     * every rally cut from it.
     *
     * The prefix carries a trailing colon (see [localClipKeyPrefix]), so
     * removing "e1" cannot take "e12" with it.
     */
    fun removeAllFor(videoId: String) = lock.withLock {
        val prefix = localClipKeyPrefix(videoId)
        persist(state.value.filterKeys { it != videoId && !it.startsWith(prefix) })
    }

    private fun mutate(videoId: String, transform: (List<LocalAnnotation>) -> List<LocalAnnotation>) =
        lock.withLock {
            val current = state.value[videoId].orEmpty()
            val updated = transform(current).sortedBy { it.timestampSeconds }
            persist(state.value + (videoId to updated))
        }

    private fun persist(next: Map<String, List<LocalAnnotation>>) {
        settings.putString(KEY, json.encodeToString(serializer, next))
        state.value = next
        counts.value = next.mapValues { it.value.size }
    }

    private fun load(): Map<String, List<LocalAnnotation>> =
        settings.getStringOrNull(KEY)
            ?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }
            ?.mapValues { (_, list) -> list.sortedBy { it.timestampSeconds } }
            ?: emptyMap()

    private companion object { const val KEY = "local_annotations" }
}
```

- [ ] **Step 4: Run the whole shared suite**

```bash
./gradlew :shared:jvmTest
```

Expected: PASS. The existing `LocalAnnotationsRepositoryTest` cases still pass unchanged - `add` gained a defaulted parameter and `removeAllFor` still removes the entry key.

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/badmintontracker/shared/localvideo/LocalAnnotationsRepository.kt \
        shared/src/commonTest/kotlin/com/badmintontracker/shared/localvideo/LocalAnnotationsRepositoryTest.kt
git commit -m "feat(shared): the local note store learns about rallies"
```

---

## Task 5: `LocalClipsRepository`

Where a directory of mp4 files becomes the same `RallyClip` the cloud produces.

**Files:**
- Create: `shared/src/commonMain/kotlin/com/badmintontracker/shared/local/LocalClipsRepository.kt`
- Test: `shared/src/commonTest/kotlin/com/badmintontracker/shared/local/LocalClipsRepositoryTest.kt`

**Interfaces:**
- Consumes: `LocalClipFile`, `localClipId`, `LOCAL_CLIP_OWNER` (Task 1); `planNotePartition` (Task 3); `LocalAnnotationsRepository.anchoredNotesFor/applyMoves/countsByKey` (Task 4); `LocalVideoRepository.entries`.
- Produces: `LocalClipTitles(settings)` with `byClipId: StateFlow<Map<String, String>>`, `put(clipId, title)`, `removeAllFor(entryId)`; `LocalClipsRepository(source, localVideos, localAnnotations, titles, scope)` with `clips: StateFlow<List<RallyClip>>`, `invalidate(entryId)`, `entryIdsWithClips: Set<String>`.

- [ ] **Step 1: Write the failing tests**

Create `LocalClipsRepositoryTest.kt`:

```kotlin
package com.badmintontracker.shared.local

import com.badmintontracker.shared.localvideo.LocalAnnotationsRepository
import com.badmintontracker.shared.localvideo.LocalVideoEntry
import com.badmintontracker.shared.localvideo.LocalVideoRepository
import com.russhwolf.settings.MapSettings
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class LocalClipsRepositoryTest {

    private fun entry(id: String, title: String? = null) = LocalVideoEntry(
        id = id, uri = "file:///v/$id.mp4", displayName = "$id.mp4",
        durationMs = 600_000, sizeBytes = 1, addedAtEpochMs = 1_700_000_000_000,
        title = title,
    )

    private fun file(index: Int, start: Double, end: Double) =
        LocalClipFile(index, start, end, end - start, "file:///c/$index.mp4")

    private fun TestScope.fixture(
        entries: List<LocalVideoEntry>,
        clips: Map<String, List<LocalClipFile>>,
    ): Triple<LocalClipsRepository, LocalAnnotationsRepository, LocalVideoRepository> {
        val settings = MapSettings()
        val videos = LocalVideoRepository(settings) {}
        entries.forEach(videos::add)
        val notes = LocalAnnotationsRepository(settings)
        val titles = LocalClipTitles(settings)
        val repo = LocalClipsRepository(
            source = { id -> clips[id].orEmpty() },
            localVideos = videos,
            localAnnotations = notes,
            titles = titles,
            scope = backgroundScope,
        )
        // The recompute collector is launched in init; let it run before asserting.
        runCurrent()
        return Triple(repo, notes, videos)
    }

    @Test
    fun a_cut_rally_becomes_a_clip_the_cloud_screens_can_draw() = runTest {
        val (repo, _, _) = fixture(
            entries = listOf(entry("e1", title = "Tuesday league")),
            clips = mapOf("e1" to listOf(file(1, 10.0, 20.0))),
        )
        val clip = repo.clips.value.single()
        clip.id shouldBe "local:e1:1"
        clip.videoId shouldBe "e1"
        clip.ownerId shouldBe LOCAL_CLIP_OWNER
        clip.rallyIndex shouldBe 1
        clip.startTimestamp shouldBe 10f
        clip.endTimestamp shouldBe 20f
        clip.durationSeconds shouldBe 10f
        clip.clipStoragePath shouldBe "file:///c/1.mp4"
        // The thumbnail is the clip itself: both platforms decode a frame from
        // the file rather than storing a JPEG beside it. See the design, §3.8.
        clip.thumbnailStoragePath shouldBe "file:///c/1.mp4"
        // The match name is stamped on every clip, the way the web app does it,
        // so matchTitle() and clipRowTitle() work with no special case.
        clip.title shouldBe "Tuesday league"
        clip.annotationCount shouldBe 0
    }

    @Test
    fun the_note_count_follows_the_store() = runTest {
        val (repo, notes, _) = fixture(
            entries = listOf(entry("e1")),
            clips = mapOf("e1" to listOf(file(1, 10.0, 20.0))),
        )
        repo.clips.value.single().annotationCount shouldBe 0
        notes.add("local:e1:1", 3f, "net kill", null, videoTimestampSeconds = 13f)
        runCurrent()
        // A snapshot taken once would pass a test that read it before the add and
        // show a stale "0 notes" in the app forever after.
        repo.clips.value.single().annotationCount shouldBe 1
    }

    @Test
    fun notes_made_before_the_analysis_land_on_their_rallies() = runTest {
        val settings = MapSettings()
        val videos = LocalVideoRepository(settings) {}
        videos.add(entry("e1"))
        val notes = LocalAnnotationsRepository(settings)
        notes.add("e1", 14f, "made while watching the whole video", null)

        LocalClipsRepository(
            source = { listOf(file(1, 10.0, 20.0)) },
            localVideos = videos,
            localAnnotations = notes,
            titles = LocalClipTitles(settings),
            scope = backgroundScope,
        )
        runCurrent()

        notes.annotationsFor("e1") shouldBe emptyList()
        notes.annotationsFor("local:e1:1").single().timestampSeconds shouldBe 4f
    }

    @Test
    fun a_rename_overrides_the_match_name_for_one_rally() = runTest {
        val settings = MapSettings()
        val videos = LocalVideoRepository(settings) {}
        videos.add(entry("e1", title = "Tuesday league"))
        val titles = LocalClipTitles(settings)
        titles.put("local:e1:2", "The long one")
        val repo = LocalClipsRepository(
            source = { listOf(file(1, 10.0, 20.0), file(2, 30.0, 40.0)) },
            localVideos = videos,
            localAnnotations = LocalAnnotationsRepository(settings),
            titles = titles,
            scope = backgroundScope,
        )
        runCurrent()
        repo.clips.value.map { it.title } shouldContainExactly
            listOf("Tuesday league", "The long one")
    }

    @Test
    fun an_entry_with_no_clips_contributes_no_match() = runTest {
        val (repo, _, _) = fixture(entries = listOf(entry("e1")), clips = emptyMap())
        repo.clips.value shouldBe emptyList()
        repo.entryIdsWithClips shouldBe emptySet()
    }

    @Test
    fun invalidate_picks_up_what_a_finished_run_just_cut() = runTest {
        val settings = MapSettings()
        val videos = LocalVideoRepository(settings) {}
        videos.add(entry("e1"))
        var cut = emptyList<LocalClipFile>()
        val repo = LocalClipsRepository(
            source = { cut },
            localVideos = videos,
            localAnnotations = LocalAnnotationsRepository(settings),
            titles = LocalClipTitles(settings),
            scope = backgroundScope,
        )
        runCurrent()
        repo.clips.value shouldBe emptyList()

        cut = listOf(file(1, 10.0, 20.0))
        repo.invalidate("e1")
        runCurrent()

        repo.clips.value.single().id shouldBe "local:e1:1"
    }

    @Test
    fun removing_an_entry_drops_its_clips() = runTest {
        val (repo, _, videos) = fixture(
            entries = listOf(entry("e1")),
            clips = mapOf("e1" to listOf(file(1, 10.0, 20.0))),
        )
        repo.clips.value.size shouldBe 1
        videos.remove("e1")
        runCurrent()
        repo.clips.value shouldBe emptyList()
    }
}
```

- [ ] **Step 2: Run them and watch them fail**

```bash
./gradlew :shared:jvmTest --tests "com.badmintontracker.shared.local.LocalClipsRepositoryTest"
```

Expected: compilation failure, `Unresolved reference: LocalClipsRepository`.

- [ ] **Step 3: Write the title store and the repository**

Create `LocalClipsRepository.kt`:

```kotlin
package com.badmintontracker.shared.local

import com.badmintontracker.shared.localvideo.LocalAnnotationsRepository
import com.badmintontracker.shared.localvideo.LocalVideoEntry
import com.badmintontracker.shared.localvideo.LocalVideoRepository
import com.badmintontracker.shared.model.RallyClip
import com.badmintontracker.shared.util.SyncLock
import com.badmintontracker.shared.util.withLock
import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.Instant
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Per-clip titles for rallies that only exist on the phone.
 *
 * The cloud rally page offers a rename, which writes `rally_clips.title`. A
 * local clip has no row, so the same affordance needs somewhere to put the
 * string, and the alternative is gating the rename off for local matches - a
 * visible parity gap for twenty lines of code.
 */
class LocalClipTitles(private val settings: Settings) {

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), String.serializer())

    private val state = MutableStateFlow(load())
    val byClipId: StateFlow<Map<String, String>> = state.asStateFlow()

    private val lock = SyncLock()

    /** A null or blank title clears the rename, restoring the match name. */
    fun put(clipId: String, title: String?) = lock.withLock {
        val trimmed = title?.trim()?.takeIf { it.isNotEmpty() }
        val next = if (trimmed == null) state.value - clipId else state.value + (clipId to trimmed)
        settings.putString(KEY, json.encodeToString(serializer, next))
        state.value = next
    }

    fun removeAllFor(entryId: String) = lock.withLock {
        val prefix = localClipKeyPrefix(entryId)
        val next = state.value.filterKeys { !it.startsWith(prefix) }
        settings.putString(KEY, json.encodeToString(serializer, next))
        state.value = next
    }

    private fun load(): Map<String, String> =
        settings.getStringOrNull(KEY)
            ?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }
            ?: emptyMap()

    private companion object { const val KEY = "local_clip_titles" }
}

/**
 * The rallies an on-device run cut, as the same [RallyClip] the cloud produces.
 *
 * This is the whole of the design's §2: the screens do not depend on Supabase,
 * they depend on this type, so producing it here is what makes a device run look
 * like a match rather than like a second kind of thing.
 *
 * [source] is the one platform seam - the clip sidecar is read with `File` on
 * Android and `URL` on iOS, neither of which exists here. It is called off the
 * main thread by its implementations and its result is cached per entry; the set
 * only changes when a run finishes, which is what [invalidate] is for.
 */
class LocalClipsRepository(
    private val source: (entryId: String) -> List<LocalClipFile>,
    private val localVideos: LocalVideoRepository,
    private val localAnnotations: LocalAnnotationsRepository,
    private val titles: LocalClipTitles,
    scope: CoroutineScope,
) {
    private val files = MutableStateFlow<Map<String, List<LocalClipFile>>>(emptyMap())
    private val loaded = mutableSetOf<String>()
    private val lock = SyncLock()

    private val derived = MutableStateFlow<List<RallyClip>>(emptyList())

    /**
     * Every local clip on this phone, newest entry first.
     *
     * Recomputed from four sources rather than stored: the files on disk, the
     * entry (its name and date), the note counts and the renames. The last two
     * change while a coach is working, and a value captured once would leave a
     * rally row showing "0 notes" under a note they just wrote.
     */
    val clips: StateFlow<List<RallyClip>> = derived.asStateFlow()

    /** Entries that have at least one cut rally - what the drawer's filter asks. */
    val entryIdsWithClips: Set<String>
        get() = files.value.filterValues { it.isNotEmpty() }.keys

    init {
        // Four reasons for a rally row to redraw, and none of them is this
        // class's own write. `files` is in the list because `invalidate` moves it.
        scope.launch {
            combine(
                localVideos.entries,
                localAnnotations.countsByKey,
                titles.byClipId,
                files,
            ) { entries, counts, renames, byEntry -> recompute(entries, counts, renames, byEntry) }
                .collect { derived.value = it }
        }
        // Not inside the collector: loading reads a directory per entry and
        // writes back through applyMoves, and a load driven by the flow it
        // feeds would provoke the emission that provokes the load.
        localVideos.entries.value.forEach(::load)
    }

    /**
     * Re-read one entry's clips from disk.
     *
     * Called when a run finishes cutting, and when an entry this process has not
     * seen appears. Not a flow: nothing on the phone can tell this class that a
     * directory changed, and stat-ing one per redraw is what the cache avoids.
     */
    fun invalidate(entryId: String) {
        lock.withLock { loaded -= entryId }
        load(entryId)
    }

    private fun load(entryId: String) {
        val alreadyLoaded = lock.withLock { !loaded.add(entryId) }
        if (alreadyLoaded) return
        val clips = source(entryId)
        lock.withLock { files.value = files.value + (entryId to clips) }
        partition(entryId, clips)
    }

    /**
     * Re-file this entry's notes against the rallies that exist now.
     *
     * One of the design's two triggers (§5.3), the other being a finished run.
     * Idempotent by construction, which is what makes it safe to run on every
     * cold start for the life of the entry.
     */
    private fun partition(entryId: String, clips: List<LocalClipFile>) {
        localAnnotations.applyMoves(
            planNotePartition(
                entryId = entryId,
                notes = localAnnotations.anchoredNotesFor(entryId),
                clips = clips,
            ),
        )
    }

    private fun recompute(
        entries: List<LocalVideoEntry>,
        counts: Map<String, Int>,
        renames: Map<String, String>,
        byEntry: Map<String, List<LocalClipFile>>,
    ): List<RallyClip> {
        val byId = entries.associateBy { it.id }
        return byEntry
            .flatMap { (entryId, clips) ->
                val entry = byId[entryId] ?: return@flatMap emptyList()
                clips.map { it.toRallyClip(entry, counts, renames) }
            }
            .sortedWith(compareByDescending<RallyClip> { it.createdAt }.thenBy { it.rallyIndex })
    }

    private fun LocalClipFile.toRallyClip(
        entry: LocalVideoEntry,
        counts: Map<String, Int>,
        renames: Map<String, String>,
    ): RallyClip {
        val id = localClipId(entry.id, index)
        return RallyClip(
            id = id,
            // The entry id IS the future videos.id (LocalVideoEntry.kt:140), so a
            // device match and the cloud match of the same video share an
            // identity rather than colliding under two.
            videoId = entry.id,
            ownerId = LOCAL_CLIP_OWNER,
            rallyIndex = index,
            startTimestamp = startSeconds.toFloat(),
            endTimestamp = endSeconds.toFloat(),
            durationSeconds = durationSeconds.toFloat(),
            clipStoragePath = url,
            // Both platforms decode a frame from the clip itself rather than
            // storing a JPEG beside it. See the design, §3.8.
            thumbnailStoragePath = url,
            title = renames[id] ?: entry.title,
            annotationCount = counts[id] ?: 0,
            createdAt = Instant.fromEpochMilliseconds(entry.addedAtEpochMs),
        )
    }
}
```

`load` is guarded by `loaded.add`, which returns false when the entry is already
there, so the `init` sweep and a later `invalidate` cannot both read the same
directory. Add the imports the file needs: `kotlinx.coroutines.CoroutineScope`,
`kotlinx.coroutines.flow.combine`, `kotlinx.coroutines.flow.collect`,
`kotlinx.coroutines.launch`.

- [ ] **Step 4: Run them and watch them pass**

```bash
./gradlew :shared:jvmTest --tests "com.badmintontracker.shared.local.LocalClipsRepositoryTest"
```

Expected: PASS, 7 tests. `runTest` drives the scope; pass `backgroundScope` from the test.

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/badmintontracker/shared/local/LocalClipsRepository.kt \
        shared/src/commonTest/kotlin/com/badmintontracker/shared/local/LocalClipsRepositoryTest.kt
git commit -m "feat(shared): cut rallies become the clip type the app already draws"
```

---

## Task 6: The three composites

Where local clips join the app. After this task every screen that reads a repository can see them, and nothing in any screen has changed.

**Files:**
- Create: `shared/src/commonMain/kotlin/com/badmintontracker/shared/local/CompositeRepositories.kt`
- Test: `shared/src/commonTest/kotlin/com/badmintontracker/shared/local/CompositeRepositoriesTest.kt`

**Interfaces:**
- Consumes: `LocalClipsRepository` and `LocalClipTitles` (Task 5), `isLocalClipId` (Task 1), `LocalAnnotationsRepository` (Task 4), the `ClipsRepository` / `AnnotationsRepository` / `MediaRepository` interfaces.
- Also produces, in `LocalClipsRepository.kt`: `interface LocalClipsSource { val clips: StateFlow<List<RallyClip>> }`, which `LocalClipsRepository` implements and both composites depend on, so they are testable without a filesystem.
- Produces: `CompositeClipsRepository(cloud, local, titles)`, `CompositeAnnotationsRepository(cloud, localAnnotations, localClips)`, `CompositeMediaRepository(cloud)`.

- [ ] **Step 1: Write the failing tests**

Create `CompositeRepositoriesTest.kt`:

```kotlin
package com.badmintontracker.shared.local

import com.badmintontracker.shared.model.RallyClip
import com.badmintontracker.shared.testing.FakeClipsRepository
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.Test

class CompositeRepositoriesTest {

    private fun cloudClip(id: String, videoId: String, index: Int) = RallyClip(
        id = id, videoId = videoId, ownerId = "owner", rallyIndex = index,
        startTimestamp = 0f, endTimestamp = 5f, durationSeconds = 5f,
        clipStoragePath = "clips/$id.mp4", annotationCount = 0,
        createdAt = Instant.fromEpochMilliseconds(1_700_000_000_000),
    )

    @Test
    fun the_list_holds_both_kinds() = runTest {
        val cloud = FakeClipsRepository()
        cloud.clips.value = listOf(cloudClip("c1", "v1", 1))
        val composite = compositeFor(cloud, local = listOf(localClip("e1", 1)))
        composite.observeClips().first().map { it.id } shouldContainExactly listOf("c1", "local:e1:1")
    }

    @Test
    fun a_refresh_does_not_wipe_the_local_half() = runTest {
        // ClipsRepositoryImpl.refresh() is a whole-list replace. Routed straight
        // through, it empties the rally list under an open match page.
        val cloud = FakeClipsRepository()
        val composite = compositeFor(cloud, local = listOf(localClip("e1", 1)))
        composite.refresh()
        cloud.refreshCalls.size shouldBe 1
        composite.observeClips().first().map { it.id } shouldContainExactly listOf("local:e1:1")
    }

    @Test
    fun cloud_clips_win_when_one_video_has_both() = runTest {
        // A device run and a later cloud run of the same video share a videoId,
        // because the entry id becomes videos.id. Showing both would render one
        // match with every rally twice.
        val cloud = FakeClipsRepository()
        cloud.clips.value = listOf(cloudClip("c1", "e1", 1))
        val composite = compositeFor(cloud, local = listOf(localClip("e1", 1)))
        composite.observeClips().first().map { it.id } shouldContainExactly listOf("c1")
    }

    @Test
    fun a_rename_routes_on_the_id() = runTest {
        val cloud = FakeClipsRepository()
        val titles = LocalClipTitles(com.russhwolf.settings.MapSettings())
        val composite = compositeFor(cloud, local = listOf(localClip("e1", 1)), titles = titles)

        composite.updateTitle("local:e1:1", "The long one").isSuccess shouldBe true
        titles.byClipId.value["local:e1:1"] shouldBe "The long one"

        composite.updateTitle("c1", "Something").isSuccess shouldBe true
    }

    @Test
    fun counting_a_video_server_side_never_sees_a_local_id() = runTest {
        // AnalyzeCoordinator is the only caller and it is on the cloud path.
        val cloud = FakeClipsRepository()
        val composite = compositeFor(cloud, local = listOf(localClip("e1", 1)))
        composite.countClipsForVideo("v1")
        cloud.countCalls shouldContainExactly listOf("v1")
    }

    @Test
    fun a_local_clip_is_played_from_its_file() = runTest {
        val media = CompositeMediaRepository(cloud = ThrowingMedia)
        val clip = localClip("e1", 1)
        media.signedClipUrl(clip) shouldBe clip.clipStoragePath
        media.signedThumbnailUrl(clip) shouldBe clip.clipStoragePath
    }
}
```

Add the helpers at the bottom of the same file:

```kotlin
    private fun localClip(entryId: String, index: Int) = RallyClip(
        id = localClipId(entryId, index), videoId = entryId, ownerId = LOCAL_CLIP_OWNER,
        rallyIndex = index, startTimestamp = 10f, endTimestamp = 20f, durationSeconds = 10f,
        clipStoragePath = "file:///c/$entryId/rally-$index.mp4",
        thumbnailStoragePath = "file:///c/$entryId/rally-$index.mp4",
        annotationCount = 0, createdAt = Instant.fromEpochMilliseconds(1_700_000_000_000),
    )

    /**
     * A composite over a stub local half. The real [LocalClipsRepository] needs a
     * filesystem and a scope; what these tests check is the routing in front of
     * it, so the clips are handed in directly.
     */
    private fun compositeFor(
        cloud: FakeClipsRepository,
        local: List<RallyClip>,
        titles: LocalClipTitles = LocalClipTitles(MapSettings()),
    ): CompositeClipsRepository = CompositeClipsRepository(
        cloud = cloud,
        local = StubLocalClips(local),
        titles = titles,
    )

    private object ThrowingMedia : MediaRepository {
        override suspend fun signedClipUrl(clip: RallyClip): String =
            error("cloud media must not be reached for a local clip")
        override suspend fun signedThumbnailUrl(clip: RallyClip): String? =
            error("cloud media must not be reached for a local clip")
    }
```

`StubLocalClips` is what makes the composite testable without a filesystem, so
extract the one member the composite actually uses into an interface in
`LocalClipsRepository.kt`:

```kotlin
/** What a composite needs from the local half: the current clips, as a flow. */
interface LocalClipsSource {
    val clips: StateFlow<List<RallyClip>>
}
```

`LocalClipsRepository` implements it (its `clips` property already satisfies the
contract), `CompositeClipsRepository` and `CompositeAnnotationsRepository` take
`LocalClipsSource` rather than the concrete class, and the test's stub is:

```kotlin
    private class StubLocalClips(clips: List<RallyClip>) : LocalClipsSource {
        override val clips = MutableStateFlow(clips)
    }
```

- [ ] **Step 2: Run them and watch them fail**

```bash
./gradlew :shared:jvmTest --tests "com.badmintontracker.shared.local.CompositeRepositoriesTest"
```

Expected: compilation failure, `Unresolved reference: CompositeMediaRepository`.

- [ ] **Step 3: Write the composites**

Create `CompositeRepositories.kt`. Full code for the clips composite; the other two follow the same routing rule.

```kotlin
package com.badmintontracker.shared.local

import com.badmintontracker.shared.localvideo.LocalAnnotationsRepository
import com.badmintontracker.shared.model.AnnotationLabel
import com.badmintontracker.shared.model.RallyAnnotation
import com.badmintontracker.shared.model.RallyClip
import com.badmintontracker.shared.repo.AnnotationsRepository
import com.badmintontracker.shared.repo.ClipsRepository
import com.badmintontracker.shared.repo.MediaRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Cloud rallies and on-phone rallies, as one list.
 *
 * Everything that draws a rally reads this: the drawer's match rows, the match
 * page, the rally page, the label summary. None of them knows which half a clip
 * came from, which is the point - see the design's §2.
 */
class CompositeClipsRepository(
    private val cloud: ClipsRepository,
    private val local: LocalClipsRepository,
    private val titles: LocalClipTitles,
) : ClipsRepository {

    override suspend fun listClips(): List<RallyClip> = merge(cloud.listClips(), local.clips.value)

    override fun observeClips(): Flow<List<RallyClip>> =
        combine(cloud.observeClips(), local.clips) { c, l -> merge(c, l) }

    /**
     * Refreshes the cloud half only.
     *
     * Not an oversight and not a shortcut: the local half has nothing to fetch,
     * and [ClipsRepository.refresh] on the cloud implementation is a whole-list
     * replace. Routing this through both would empty the rally list under an
     * open match page every time a pull-to-refresh landed.
     */
    override suspend fun refresh() = cloud.refresh()

    override suspend fun updateTitle(clipId: String, title: String?): Result<Unit> =
        if (isLocalClipId(clipId)) runCatching { titles.put(clipId, title) }
        else cloud.updateTitle(clipId, title)

    /** Cloud only: the single caller is `AnalyzeCoordinator`, on the cloud path. */
    override suspend fun countClipsForVideo(videoId: String): Result<Int> =
        cloud.countClipsForVideo(videoId)

    /** Cloud only: a local video's clips go when its entry does, not from here. */
    override fun pruneVideo(videoId: String) = cloud.pruneVideo(videoId)

    /**
     * Cloud clips win for a video that has both.
     *
     * `LocalVideoEntry.id` becomes `videos.id`, so a video analysed on the phone
     * and later in the cloud produces two sets of rallies under one videoId.
     * Showing both would draw every rally of that match twice. The consequence -
     * notes made on the device rallies stop being visible on that match - is
     * stated in the design's §7.3 and is not solved here.
     */
    private fun merge(cloudClips: List<RallyClip>, localClips: List<RallyClip>): List<RallyClip> {
        if (localClips.isEmpty()) return cloudClips
        val cloudVideoIds = cloudClips.mapTo(mutableSetOf()) { it.videoId }
        return cloudClips + localClips.filterNot { it.videoId in cloudVideoIds }
    }
}

/**
 * Notes on a cloud rally go to postgres; notes on an on-phone rally go to the
 * Settings-backed store, with the video-time anchor the partition needs.
 */
class CompositeAnnotationsRepository(
    private val cloud: AnnotationsRepository,
    private val localAnnotations: LocalAnnotationsRepository,
    private val localClips: LocalClipsRepository,
) : AnnotationsRepository {

    override suspend fun list(clipId: String): List<RallyAnnotation> =
        if (isLocalClipId(clipId)) localAnnotations.annotationsFor(clipId).map { it.toRallyAnnotation(clipId) }
        else cloud.list(clipId)

    override suspend fun listForClips(clipIds: List<String>): Result<List<RallyAnnotation>> {
        val (localIds, cloudIds) = clipIds.partition(::isLocalClipId)
        val fromCloud = if (cloudIds.isEmpty()) emptyList() else
            cloud.listForClips(cloudIds).getOrElse { return Result.failure(it) }
        val fromLocal = localIds.flatMap { id ->
            localAnnotations.annotationsFor(id).map { it.toRallyAnnotation(id) }
        }
        return Result.success(fromCloud + fromLocal)
    }

    override suspend fun add(
        clipId: String,
        timestampSeconds: Float,
        body: String,
        label: AnnotationLabel?,
    ): Result<RallyAnnotation> {
        if (!isLocalClipId(clipId)) return cloud.add(clipId, timestampSeconds, body, label)
        return runCatching {
            // The anchor is what survives a re-analysis. Computed from the clip's
            // own start rather than stored by the caller, so every writer of a
            // local note gets it right by construction.
            val start = localClips.clips.value.firstOrNull { it.id == clipId }?.startTimestamp ?: 0f
            localAnnotations
                .add(clipId, timestampSeconds, body, label, videoTimestampSeconds = start + timestampSeconds)
                .toRallyAnnotation(clipId)
        }
    }

    /**
     * Deletion is by annotation id alone, and the local store is keyed by clip,
     * so a local delete has to find the key first. Cloud ids are UUIDs and local
     * ones are too, so there is no prefix to route on here.
     */
    override suspend fun delete(id: String): Result<Unit> {
        val key = localAnnotations.byVideoId.value.entries
            .firstOrNull { (_, notes) -> notes.any { it.id == id } }
            ?.key
        if (key == null || !isLocalClipId(key)) return cloud.delete(id)
        return runCatching { localAnnotations.delete(key, id) }
    }
}

/** A local clip is already a file:// URL; there is nothing to sign. */
class CompositeMediaRepository(private val cloud: MediaRepository) : MediaRepository {
    override suspend fun signedClipUrl(clip: RallyClip): String =
        if (isLocalClipId(clip.id)) clip.clipStoragePath else cloud.signedClipUrl(clip)

    override suspend fun signedThumbnailUrl(clip: RallyClip): String? =
        if (isLocalClipId(clip.id)) clip.thumbnailStoragePath else cloud.signedThumbnailUrl(clip)
}
```

And the mapper, private in the same file:

```kotlin
/**
 * A local note as the screens' own type.
 *
 * [LocalAnnotation.videoTimestampSeconds] deliberately does not cross: it is the
 * partition's anchor, not something a player seeks to, and a second timestamp on
 * the type every rally page reads is one a screen will eventually use by mistake.
 */
private fun LocalAnnotation.toRallyAnnotation(clipId: String) = RallyAnnotation(
    id = id,
    clipId = clipId,
    timestampSeconds = timestampSeconds,
    body = body,
    labelName = labelName,
    labelColor = labelColor,
    createdAt = Instant.fromEpochMilliseconds(createdAtEpochMs),
)
```

- [ ] **Step 4: Run the whole shared suite**

```bash
./gradlew :shared:jvmTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/badmintontracker/shared/local/CompositeRepositories.kt \
        shared/src/commonTest/kotlin/com/badmintontracker/shared/local/CompositeRepositoriesTest.kt
git commit -m "feat(shared): one rally list, whichever pipeline cut it"
```

---

## Task 7: Wiring, and the two clip sources

The composites reach the screens here. Both platforms build and run after this task, showing local rallies in the match list, with no UI file touched yet.

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/badmintontracker/shared/RallyApp.kt:40-70`
- Modify: `shared/src/iosMain/kotlin/com/badmintontracker/shared/RallyAppIos.kt:17-38`
- Create: `androidApp/src/main/java/com/badmintontracker/android/localanalysis/AndroidLocalClipSource.kt`
- Create: `iosApp/Sources/LocalAnalysis/IosLocalClipSource.swift`
- Modify: `androidApp/src/main/java/com/badmintontracker/android/RallyAndroidApp.kt:50-70`
- Modify: `androidApp/src/main/java/com/badmintontracker/android/localanalysis/LocalAnalysisRunner.kt:251`
- Modify: `iosApp/Sources/RallyIOSApp.swift:11-16`
- Modify: `iosApp/Sources/LocalAnalysis/LocalAnalysisRunner.swift:455`

**Interfaces:**
- Consumes: everything from Tasks 5 and 6.
- Produces: `RallyApp.localClips: LocalClipsRepository`, `RallyApp.localClipTitles: LocalClipTitles`; `RallyApp` constructor parameter `localClipFiles: (String) -> List<LocalClipFile> = { emptyList() }`; `createRallyApp(..., localClipFiles:)`.

- [ ] **Step 1: Give `RallyApp` the local half**

In `RallyApp.kt`, add the constructor parameter after `onLocalVideoRemoved`:

```kotlin
    /**
     * The cut rallies on this phone, for one entry.
     *
     * Supplied by the platform because the clip sidecar is read with `File` on
     * Android and `URL` on iOS. Defaults to nothing, which is what a test graph
     * and a build without the on-device pipeline both want.
     */
    localClipFiles: (entryId: String) -> List<LocalClipFile> = { _ -> emptyList() },
    /** Scope for the local clip list's recomputation. App-scoped by both callers. */
    appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
```

Then replace the three repository properties. `clips`, `annotations` and `media` keep their names and types - every consumer reads them unchanged - and gain the composite in front:

```kotlin
    val localClipTitles: LocalClipTitles = LocalClipTitles(settings)
    val localClips: LocalClipsRepository = LocalClipsRepository(
        source = localClipFiles,
        localVideos = localVideos,
        localAnnotations = localAnnotations,
        titles = localClipTitles,
        scope = appScope,
    )

    val clips: ClipsRepository = CompositeClipsRepository(
        cloud = ClipsRepositoryImpl(client), local = localClips, titles = localClipTitles,
    )
    val annotations: AnnotationsRepository = CompositeAnnotationsRepository(
        cloud = AnnotationsRepositoryImpl(client),
        localAnnotations = localAnnotations,
        localClips = localClips,
    )
    val media: MediaRepository = CompositeMediaRepository(cloud = MediaRepositoryImpl(client))
```

**Ordering matters here.** `localVideos` and `localAnnotations` are declared below `clips` in the current file; move the three composite properties below them, or the constructor runs against uninitialised references and every read returns null through the Swift bridge.

Also extend `onLocalVideoRemoved`'s call site so a removed entry drops its renames: in `LocalVideoRepository`'s removal path the notes already go through `removeAllFor`; add `localClipTitles.removeAllFor(id)` beside it.

- [ ] **Step 2: Build shared and run its suite**

```bash
./gradlew :shared:jvmTest
```

Expected: PASS. Nothing in shared's tests constructs `RallyApp`, so this is a compile check plus the existing suite.

- [ ] **Step 3: Write the Android clip source**

Create `AndroidLocalClipSource.kt`:

```kotlin
package com.badmintontracker.android.localanalysis

import android.media.MediaMetadataRetriever
import com.badmintontracker.shared.local.LocalClipFile
import java.io.File

/**
 * The clip sidecar as shared sees it.
 *
 * Two things happen here that [PlayerTrackStore.loadClips] does not do. The URL
 * becomes a string, because shared cannot hold a `File`. And a clip whose bounds
 * are unknown - a run recovered by filename, which is every run cut before the
 * sidecar existed - gets its own length probed from the file, because otherwise
 * every one of its rallies renders as "0.0s" in a list the coach is scanning.
 *
 * Durations are cached for the life of the process: the probe opens the file,
 * and this is called again on every invalidate.
 */
class AndroidLocalClipSource(private val filesDir: File) {

    private val durations = mutableMapOf<String, Double>()

    fun clips(entryId: String): List<LocalClipFile> =
        PlayerTrackStore(filesDir).loadClips(entryId).map { clip ->
            val known = clip.endSeconds - clip.startSeconds
            LocalClipFile(
                index = clip.index,
                startSeconds = clip.startSeconds,
                endSeconds = clip.endSeconds,
                durationSeconds = if (known > 0.0) known else probe(clip.file),
                url = "file://${clip.file.absolutePath}",
            )
        }

    private fun probe(file: File): Double = durations.getOrPut(file.path) {
        runCatching {
            MediaMetadataRetriever().use { r ->
                r.setDataSource(file.path)
                (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L) / 1000.0
            }
        }.getOrDefault(0.0)
    }
}
```

- [ ] **Step 4: Wire Android and invalidate on a finished cut**

In `RallyAndroidApp.kt`, build the source before `RallyApp` and pass it:

```kotlin
        val clipSource = AndroidLocalClipSource(filesDir)
        rally       = RallyApp(
            config = SupabaseConfig(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY),
            settings = settings,
            onLocalVideoRemoved = { entry -> AnalysisFiles.deleteAll(filesDir, entry.id) },
            localClipFiles = clipSource::clips,
            appScope = appScope,
        )
```

In `LocalAnalysisRunner.kt`, add a constructor parameter and call it after the sidecar is written:

```kotlin
class LocalAnalysisRunner(
    private val context: Context,
    private val scope: CoroutineScope,
    private val throughput: DeviceThroughputRepository,
    private val log: (String) -> Unit = {},
    /**
     * Told that this entry's cut rallies changed. The clip list caches a
     * directory listing per entry, and a finished run is the only thing that can
     * make that cache wrong. See the design's §5.3.
     */
    private val onClipsCut: (String) -> Unit = {},
) {
```

and at line 251, immediately after `tracks.saveClips(entryId, clips)`:

```kotlin
                tracks.saveClips(entryId, clips)
                // Before the state moves to Done: a screen watching for Done reads
                // the clip list, and reading it before the invalidate lands shows
                // the run's own rallies as missing.
                onClipsCut(entryId)
```

Then in `RallyAndroidApp.kt`, pass `onClipsCut = { rally.localClips.invalidate(it) }` into the `LocalAnalysisRunner(...)` construction.

- [ ] **Step 5: Build and install Android**

```bash
./gradlew :androidApp:testDebugUnitTest
ANDROID_SERIAL=emulator-5554 ./gradlew :androidApp:installDebug
```

Expected: both succeed. Open the app: an entry with cut rallies now appears under "My matches" **and** still under "On this phone" - the duplicate is real and Task 8 removes it. Open the match: the rally rows draw, with thumbnails and durations.

- [ ] **Step 6: Write the iOS clip source and wire it**

Create `IosLocalClipSource.swift`, the port of Step 3:

```swift
import AVFoundation
import Foundation
import Shared

/// The clip sidecar as shared sees it. Port of Android's `AndroidLocalClipSource`.
///
/// Two things happen here that `PlayerTrackStore.loadClips` does not do. The URL
/// becomes a string, because shared cannot hold a `URL`. And a clip whose bounds
/// are unknown - a run recovered by filename, which is every run cut before the
/// sidecar existed - gets its own length probed from the file, because otherwise
/// every one of its rallies renders as "0.0s" in a list the coach is scanning.
///
/// `nonisolated` for the same reason `LocalAnalysisRunner.storedClips` is: this
/// is a directory listing and a small parse, called off the main actor.
final class IosLocalClipSource: @unchecked Sendable {
    static let shared = IosLocalClipSource()

    private let lock = NSLock()
    private var durations: [String: Double] = [:]

    nonisolated func clips(entryId: String) -> [LocalClipFile] {
        PlayerTrackStore().loadClips(entryId: entryId).map { clip in
            let known = clip.endSeconds - clip.startSeconds
            return LocalClipFile(
                index: Int32(clip.index),
                startSeconds: clip.startSeconds,
                endSeconds: clip.endSeconds,
                durationSeconds: known > 0 ? known : probe(clip.url),
                url: clip.url.absoluteString
            )
        }
    }

    /// Synchronous on purpose: the caller is already off the main actor, and an
    /// async probe here would make the whole source async and with it every
    /// shared read of the clip list.
    private func probe(_ url: URL) -> Double {
        lock.lock(); defer { lock.unlock() }
        if let cached = durations[url.path] { return cached }
        let seconds = CMTimeGetSeconds(AVURLAsset(url: url).duration)
        let value = seconds.isFinite ? seconds : 0
        durations[url.path] = value
        return value
    }
}
```

`AVURLAsset.duration` is the deprecated synchronous accessor; it is the right one
here for the reason in the comment, and the file carries that comment so the
deprecation warning is not "fixed" by making the source async.

In `RallyAppIos.kt` add the parameter and pass it through:

```kotlin
    /**
     * The cut rallies on this phone, for one entry. Supplied by Swift because
     * the clip sidecar is read with `URL` there. Same reason as
     * [deleteLocalAnalysis].
     */
    localClipFiles: (String) -> List<LocalClipFile>,
```

In `RallyIOSApp.swift`, pass `localClipFiles: { IosLocalClipSource.shared.clips(entryId: $0) }`, and in `LocalAnalysisRunner.swift` after `try tracks.saveClips(...)` at line 455 call the runner's new `onClipsCut(entryId)`, wired from `RootView.swift` where the runner is constructed.

- [ ] **Step 7: Build and run iOS**

```bash
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
cd iosApp && xcodegen generate && cd ..
xcodebuild test -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -destination "platform=iOS Simulator,name=iPhone 17 Pro" \
  -derivedDataPath iosApp/build/DerivedData CODE_SIGNING_ALLOWED=NO
```

Expected: builds, existing tests pass, and the simulator shows the same duplicate rows Android does.

- [ ] **Step 8: Commit**

```bash
git add shared/src/commonMain/kotlin/com/badmintontracker/shared/RallyApp.kt \
        shared/src/iosMain/kotlin/com/badmintontracker/shared/RallyAppIos.kt \
        androidApp/src/main/java/com/badmintontracker/android/localanalysis/AndroidLocalClipSource.kt \
        androidApp/src/main/java/com/badmintontracker/android/RallyAndroidApp.kt \
        androidApp/src/main/java/com/badmintontracker/android/localanalysis/LocalAnalysisRunner.kt \
        iosApp/Sources/LocalAnalysis/IosLocalClipSource.swift \
        iosApp/Sources/RallyIOSApp.swift \
        iosApp/Sources/RootView.swift \
        iosApp/Sources/LocalAnalysis/LocalAnalysisRunner.swift
git commit -m "feat: on-device rallies reach the match list on both platforms"
```

---

## Task 8: One match, one row

Closes the duplicate Task 7 leaves behind. Two rules, both ported to each platform with mirrored tests.

**Files:**
- Modify: `androidApp/src/main/java/com/badmintontracker/android/cliplist/MatchRow.kt:60-77`
- Modify: `androidApp/src/main/java/com/badmintontracker/android/cliplist/ClipListViewModel.kt`
- Modify: `androidApp/src/main/java/com/badmintontracker/android/cliplist/ClipListScreen.kt:116`
- Modify: `iosApp/Sources/ClipList/MatchGrouping.swift:128-152`
- Modify: `iosApp/Sources/ClipList/ClipListModel.swift:190-210`
- Modify: `iosApp/Sources/ClipList/ClipListView.swift` (the "On this phone" filter)
- Test: `androidApp/src/test/java/com/badmintontracker/android/cliplist/MatchRowTest.kt`
- Test: `iosApp/Tests/MatchGroupingTests.swift`

**Interfaces:**
- Produces: `mergeMatchRows(videoMatches, scoreMatches, attachByScoreLogId, scoreLogIdByVideoId)` - one new last parameter on both platforms.

- [ ] **Step 1: Write the failing test**

Append to `MatchRowTest.kt`:

```kotlin
    @Test
    fun a_device_analysed_video_folds_into_its_scored_match() {
        // score_logs.video_id has a foreign key to videos(id) and is null until
        // the cloud pipeline's CREATE_ROW step, which a device run never reaches.
        // The binding lives on the entry instead. Without the second claim
        // source this emits two rows for one match: a video row of its own, and
        // a score row still offering "Add video". Measured on a real device, ten
        // of eleven videos were match-attached, so this is the common case.
        val video = matchSummary(videoId = "e1")
        val card = scoreCard(scoreLogId = "log-1", videoId = null)

        val rows = mergeMatchRows(
            videoMatches = listOf(video),
            scoreMatches = listOf(card),
            scoreLogIdByVideoId = mapOf("e1" to "log-1"),
        )

        rows.size shouldBe 1
        val row = rows.single().shouldBeInstanceOf<MatchRow.Score>()
        row.card.scoreLogId shouldBe "log-1"
        row.video?.videoId shouldBe "e1"
    }

    @Test
    fun a_binding_to_another_match_does_not_claim_the_video() {
        val rows = mergeMatchRows(
            videoMatches = listOf(matchSummary(videoId = "e1")),
            scoreMatches = listOf(scoreCard(scoreLogId = "log-1", videoId = null)),
            scoreLogIdByVideoId = mapOf("e1" to "log-2"),
        )
        rows.size shouldBe 2
    }
```

Use the file's existing `matchSummary` / `scoreCard` helpers; add them if the file has none.

- [ ] **Step 2: Run it and watch it fail**

```bash
./gradlew :androidApp:testDebugUnitTest --tests "com.badmintontracker.android.cliplist.MatchRowTest"
```

Expected: compilation failure, `No parameter with name 'scoreLogIdByVideoId' found`.

- [ ] **Step 3: Add the second claim source**

In `MatchRow.kt`, add the parameter and widen `claimed`:

```kotlin
internal fun mergeMatchRows(
    videoMatches: List<MatchSummary>,
    scoreMatches: List<ScoreMatchCard>,
    attachByScoreLogId: Map<String, AttachStatus> = emptyMap(),
    /**
     * Which match each local entry was filmed for, keyed by video id.
     *
     * The second way a score match claims a video, and the only one that works
     * before the cloud pipeline has run: `score_logs.video_id` has a foreign key
     * to `videos(id)` and stays null until CREATE_ROW, which a device run never
     * reaches, so the binding lives on `LocalVideoEntry.scoreLogId` instead.
     * Without this a device-analysed video filmed for a scored match is two rows.
     */
    scoreLogIdByVideoId: Map<String, String> = emptyMap(),
): List<MatchRow> {
    val videoById = videoMatches.associateBy { it.videoId }
    val logIds = scoreMatches.mapTo(mutableSetOf()) { it.scoreLogId }
    val claimed = scoreMatches.mapNotNullTo(mutableSetOf()) { it.videoId }
        .apply { addAll(videoMatches.map { it.videoId }.filter { scoreLogIdByVideoId[it] in logIds }) }
    val scoreRows = scoreMatches.map { card ->
        MatchRow.Score(
            card = card,
            video = card.videoId?.let(videoById::get)
                ?: videoMatches.firstOrNull { scoreLogIdByVideoId[it.videoId] == card.scoreLogId },
            attach = attachByScoreLogId[card.scoreLogId],
        )
    }
    val videoRows = videoMatches.filterNot { it.videoId in claimed }.map(MatchRow::Video)
    return (videoRows + scoreRows)
        .sortedWith(compareByDescending<MatchRow> { it.sortAtEpochMs }.thenBy { it.key })
}
```

In `ClipListViewModel.kt`, build the map from the entries flow and pass it into the `mergeMatchRows` call:

```kotlin
    // Entries carry the binding the score log cannot hold yet. See mergeMatchRows.
    private val scoreLogIdByVideoId = localVideos.entries.map { entries ->
        entries.mapNotNull { e -> e.scoreLogId?.let { e.id to it } }.toMap()
    }
```

- [ ] **Step 4: Exclude a promoted entry from "On this phone"**

In `ClipListScreen.kt:116`, widen the filter:

```kotlin
    // A video picked for a match is represented by that match's row (MatchRow.Score.video).
    // A video whose rallies this phone cut is represented by its own match row.
    // Listing either again here under "On this phone" would be the same match twice.
    val standaloneRows = localRows.filter {
        it.entry.scoreLogId == null && it.entry.id !in localClipEntryIds
    }
```

`localClipEntryIds` comes in as a new `ClipListScreen` parameter, supplied by `HomeScreen` from `rally.localClips.entryIdsWithClips`.

- [ ] **Step 5: Run the Android tests**

```bash
./gradlew :androidApp:testDebugUnitTest
```

Expected: PASS.

- [ ] **Step 6: Port both rules to iOS**

In `MatchGrouping.swift:128`, the same parameter with the same doc comment:

```swift
func mergeMatchRows(
    videoMatches: [MatchSummary],
    scoreMatches: [ScoreMatchCard],
    attachByScoreLogId: [String: AttachStatus],
    scoreLogIdByVideoId: [String: String] = [:]
) -> [MatchRow] {
    let videoById = Dictionary(videoMatches.map { ($0.videoId, $0) }, uniquingKeysWith: { _, last in last })
    let logIds = Set(scoreMatches.map(\.scoreLogId))
    var claimed = Set(scoreMatches.compactMap(\.videoId))
    for match in videoMatches where logIds.contains(scoreLogIdByVideoId[match.videoId] ?? "") {
        claimed.insert(match.videoId)
    }
    let scoreRows = scoreMatches.map { card in
        MatchRow.score(ScoreRowContent(
            card: card,
            video: card.videoId.flatMap { videoById[$0] }
                ?? videoMatches.first { scoreLogIdByVideoId[$0.videoId] == card.scoreLogId },
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

In `ClipListModel.swift`'s `regroup()`, build the map from the entries it already
holds and pass it:

```swift
        let bindings = Dictionary(
            localEntries.compactMap { entry in entry.scoreLogId.map { (entry.id, $0) } },
            uniquingKeysWith: { _, last in last }
        )
        ownedRows = mergeMatchRows(
            videoMatches: owned,
            scoreMatches: scoreCards,
            attachByScoreLogId: attachMap(),
            scoreLogIdByVideoId: bindings
        )
```

`ClipListView.swift`'s "On this phone" filter gains the same exclusion as
Android's, reading `rally.localClips.entryIdsWithClips`. Append the two mirrored
tests to `iosApp/Tests/MatchGroupingTests.swift`, named exactly as the Kotlin
ones: `a_device_analysed_video_folds_into_its_scored_match` and
`a_binding_to_another_match_does_not_claim_the_video`.

- [ ] **Step 7: Run the iOS tests**

```bash
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
xcodebuild test -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -destination "platform=iOS Simulator,name=iPhone 17 Pro" \
  -derivedDataPath iosApp/build/DerivedData CODE_SIGNING_ALLOWED=NO
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add androidApp/src/main/java/com/badmintontracker/android/cliplist/ \
        androidApp/src/test/java/com/badmintontracker/android/cliplist/MatchRowTest.kt \
        iosApp/Sources/ClipList/ iosApp/Tests/MatchGroupingTests.swift
git commit -m "fix: a device-analysed match is one row, not two"
```

---

## Task 9: The match page for a match that never left the phone

**Files:**
- Modify: `androidApp/src/main/java/com/badmintontracker/android/clipdetail/ClipDetailViewModel.kt:103`
- Modify: `androidApp/src/main/java/com/badmintontracker/android/match/MatchScreen.kt` (share gate, overflow)
- Modify: `androidApp/src/main/java/com/badmintontracker/android/localvideo/LocalVideoSection.kt:214-237` (menu items leave)
- Modify: `iosApp/Sources/ClipDetail/ClipDetailModel.swift`
- Modify: `iosApp/Sources/Match/MatchView.swift`
- Modify: `iosApp/Sources/LocalVideo/LocalVideoSection.swift:112-126`
- Test: `androidApp/src/test/java/com/badmintontracker/android/match/LocalMatchRulesTest.kt` (create)
- Test: `iosApp/Tests/LocalMatchRulesTests.swift` (create)

**Interfaces:**
- Consumes: `isLocalClipId` (Task 1).
- Produces: `fun List<RallyClip>.isLocalMatch(): Boolean` in shared, beside the id helpers.

- [ ] **Step 1: Write the failing test**

Create `LocalMatchRulesTest.kt`:

```kotlin
package com.badmintontracker.android.match

import com.badmintontracker.shared.local.LOCAL_CLIP_OWNER
import com.badmintontracker.shared.local.isLocalMatch
import com.badmintontracker.shared.local.localClipId
import com.badmintontracker.shared.local.isLocalClipId
import com.badmintontracker.shared.model.RallyClip
import io.kotest.matchers.shouldBe
import kotlinx.datetime.Instant
import org.junit.Test

class LocalMatchRulesTest {

    private fun clip(id: String, owner: String) = RallyClip(
        id = id, videoId = "e1", ownerId = owner, rallyIndex = 1,
        startTimestamp = 0f, endTimestamp = 5f, durationSeconds = 5f,
        clipStoragePath = "x", annotationCount = 0,
        createdAt = Instant.fromEpochMilliseconds(0),
    )

    /** The rule ClipDetailViewModel and ClipDetailModel both apply. */
    private fun isOwner(clip: RallyClip, currentUserId: String?) =
        isLocalClipId(clip.id) || clip.ownerId == currentUserId

    @Test
    fun a_match_whose_rallies_are_local_cannot_be_shared() {
        listOf(clip(localClipId("e1", 1), LOCAL_CLIP_OWNER)).isLocalMatch() shouldBe true
        listOf(clip("6f1b2c3d-4e5f-6071-8293-a4b5c6d7e8f9", "owner")).isLocalMatch() shouldBe false
        // Empty is not local: a match page with no rallies yet is a cloud match
        // still being clipped, and calling it local would hide its share button.
        emptyList<RallyClip>().isLocalMatch() shouldBe false
    }

    @Test
    fun a_local_rally_is_always_the_viewers_own() {
        // currentUserId() is null in the window before supabase-kt restores the
        // session. Comparing against it there renders a coach's own rallies
        // read-only: no Add note, no delete, intermittently, after a cold start.
        isOwner(clip(localClipId("e1", 1), LOCAL_CLIP_OWNER), currentUserId = null) shouldBe true
        isOwner(clip(localClipId("e1", 1), LOCAL_CLIP_OWNER), currentUserId = "someone") shouldBe true
    }

    @Test
    fun a_cloud_rally_still_compares_owners() {
        val cloud = clip("6f1b2c3d-4e5f-6071-8293-a4b5c6d7e8f9", "owner")
        isOwner(cloud, currentUserId = "owner") shouldBe true
        isOwner(cloud, currentUserId = "someone else") shouldBe false
        isOwner(cloud, currentUserId = null) shouldBe false
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

```bash
./gradlew :androidApp:testDebugUnitTest --tests "com.badmintontracker.android.match.LocalMatchRulesTest"
```

Expected: compilation failure, `Unresolved reference: isLocalMatch`.

- [ ] **Step 3: Add the rule and apply it**

In `shared/.../local/LocalClips.kt`:

```kotlin
/**
 * Whether these rallies are ones this phone cut.
 *
 * Asked of the clips rather than of the video id, because a local match and the
 * cloud match of the same video share an id - the entry id becomes videos.id.
 * Empty is not local: a match page with no rallies yet is a cloud match that has
 * not finished, and treating it as local would hide its share button.
 */
fun List<RallyClip>.isLocalMatch(): Boolean = isNotEmpty() && all { isLocalClipId(it.id) }
```

In `ClipDetailViewModel.kt:103`:

```kotlin
            // A clip on this phone belongs to whoever is holding the phone. Not
            // routed through auth: currentUserId() is null until supabase-kt
            // restores the session, and comparing against it in that window
            // renders a coach's own rallies read-only - no Add note, no delete.
            val isOwner = isLocalClipId(clip.id) || clip.ownerId == auth.currentUserId()
```

In `MatchScreen.kt`, derive the flag once beside `clipsForMatch` (line 169) and
use it for every cloud-only affordance:

```kotlin
    // Asked of the clips, not of the videoId: a local match and the cloud match
    // of the same video share an id, because the entry id becomes videos.id.
    val isLocal = clipsForMatch.isLocalMatch()
```

- The share action (line 248) and the share sheet (line 398) become
  `if (videoId != null && !isLocal)`. A local match has no server row to grant
  anyone; offering the button would open a sheet whose every outcome is an error.
- The overflow gains the four items the drawer row is losing, each shown only
  when `isLocal`: "Player heatmap" -> `nav.navigate(Route.Heatmap(videoId))`,
  "Edit details" -> the same `MatchDetailsSheet` the drawer opened, gated on the
  existing `canEditLocalVideoDetails(entry.stage)`, "Remove from app" -> the same
  confirm and the same `canRemoveLocalVideo(entry.stage)` gate, and "Analyze"
  (the cloud run) -> `nav.navigate(Route.CourtMarking(videoId))`.
- Behind `BuildConfig.DEBUG` only, "Clips on this phone (raw)" ->
  `nav.navigate(Route.LocalClips(videoId))`, which is what keeps the A/B
  comparison surface reachable now that the drawer no longer offers it.

- [ ] **Step 4: Take the five items off the drawer row**

In `LocalVideoSection.kt`, the menu keeps only what a *non*-analysed entry needs. An entry with cut rallies no longer renders here at all (Task 8), so the items that only applied to an analysed entry - "Clips on this phone", "Player heatmap" - are removed outright, and `localClips` / `onOpenLocalClips` / `onOpenHeatmap` come out of the parameter list. Follow the compiler up through `HomeScreen.kt:94` and `AuthGate.kt:248` and delete the now-unused wiring.

- [ ] **Step 5: Run the Android tests and install**

```bash
./gradlew :androidApp:testDebugUnitTest
ANDROID_SERIAL=emulator-5554 ./gradlew :androidApp:installDebug
```

Expected: PASS, and on the emulator the analysed entry appears exactly once, its match page has the rally list, no share button, and the four moved items in its overflow.

- [ ] **Step 6: Port to iOS**

The same four changes:

- `ClipDetailModel.swift`, where `isOwner` is computed: `LocalClipsKt.isLocalClipId(id: clip.id) || clip.ownerId == rally.auth.currentUserId()`.
- `MatchView.swift`: `let isLocal = LocalClipsKt.isLocalMatch(clips)` beside its own `clipsForMatch`, the share toolbar item and sheet gated on it, and the four moved items plus the debug raw-clips item in the overflow `Menu`.
- `LocalVideoSection.swift:112-126`: the two analysed-entry items and their closures come out, and the compiler leads up through `HomeView.swift` to the wiring to delete.
- `iosApp/Tests/LocalMatchRulesTests.swift` mirrors the three Kotlin test names exactly: `a_match_whose_rallies_are_local_cannot_be_shared`, `a_local_rally_is_always_the_viewers_own`, `a_cloud_rally_still_compares_owners`.

- [ ] **Step 7: Run the iOS tests**

```bash
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
xcodebuild test -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -destination "platform=iOS Simulator,name=iPhone 17 Pro" \
  -derivedDataPath iosApp/build/DerivedData CODE_SIGNING_ALLOWED=NO
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add androidApp/src iosApp/Sources iosApp/Tests shared/src
git commit -m "feat: the match page serves a match that never left the phone"
```

---

## Task 10: Remove what the design replaced, and verify on device

**Files:**
- Delete: `shared/src/commonMain/kotlin/com/badmintontracker/shared/local/ClipReanchoring.kt`
- Delete: `shared/src/commonTest/kotlin/com/badmintontracker/shared/local/ClipReanchoringTest.kt`
- Modify: `CHANGELOG.md`

- [ ] **Step 1: Confirm nothing calls them**

```bash
grep -rn "planReanchor\|ReanchorPlan\|REANCHOR_EPSILON" --include=*.kt --include=*.swift \
  shared/src androidApp/src iosApp/Sources analysis/src
```

Expected: matches only inside the two files being deleted. Anything else is a call site that must be understood before deleting - stop and report it.

- [ ] **Step 2: Delete them**

```bash
git rm shared/src/commonMain/kotlin/com/badmintontracker/shared/local/ClipReanchoring.kt \
       shared/src/commonTest/kotlin/com/badmintontracker/shared/local/ClipReanchoringTest.kt
./gradlew :shared:jvmTest
```

Expected: PASS. The design's §5.2 records why: re-partitioning computes the same number and answers the clip-matching question `planReanchor` needs a separate pass for, so keeping a tested function that describes a mechanism the code deliberately does not use is how the next reader concludes the wiring is missing and adds it back.

- [ ] **Step 3: Full suite, both platforms**

```bash
./gradlew :shared:jvmTest :androidApp:testDebugUnitTest
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
xcodebuild test -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -destination "platform=iOS Simulator,name=iPhone 17 Pro" \
  -derivedDataPath iosApp/build/DerivedData CODE_SIGNING_ALLOWED=NO
```

Expected: PASS everywhere.

- [ ] **Step 4: Verify on device**

Capture a screenshot at each step rather than chaining blind taps, which drift. Android on the emulator, then iOS on the simulator:

1. An existing pre-sidecar run: the match appears once in "My matches", its rallies show a real duration rather than "0.0s", and thumbnails decode.
2. Open a rally, add a note with a label. The summary strip appears over the match with the right count, and the rally row's "N notes" goes up.
3. Kill the app and reopen: the note and the label are still there, on the same rally.
4. Re-run analysis on a **one- to two-minute** video, not a match - this pipeline pays per frame and a full run with pose is half an hour. Notes land on the right rallies and none is lost. The partitioner's real coverage is Task 3's tests; this checks that the trigger fires.
5. Both themes. Light is the one to check: `bgInput` and `bgTertiary` resolve to the same colour there, so a layered surface that reads correctly in dark can flatten into one block.

- [ ] **Step 5: Changelog and commit**

Add to `CHANGELOG.md` under the current version:

```markdown
- Rallies analysed on the phone now appear as an ordinary match: one row in
  "My matches", the rally list, and a rally page where they can be given notes
  and labels like any other. They stay on the phone - a match analysed this way
  is not uploaded and cannot be shared.
```

```bash
git add CHANGELOG.md shared/src
git commit -m "chore: retire ClipReanchoring, which the partition rule replaced"
```
