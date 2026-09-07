# Skeleton View Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Play a locally analysed video on Android with the near player's pose skeleton drawn over it, frame-synchronised, as the second renderer on the Analytics detail screen.

**Architecture:** The pure `:analysis` layer learns to hand back the chosen person's joints beside the court position it already produces, stamped with the frame's container timestamp, and to look one up by time. The Android decode pass stops discarding the timestamps it is handed. A binary `SkeletonStore` keeps the near player's poses per run, written only when the skeleton metric was requested. A `SkeletonPanel` plays the file the pose ran over, reads the player's position every frame, and draws the pose whose timestamp is within half a frame, or nothing.

**Tech Stack:** Kotlin Multiplatform 2.3.20 for `:analysis` and `:shared` (kotlin.test, kotest assertions), Jetpack Compose with Material 3 and Media3 ExoPlayer in `androidApp`, JUnit 4 for Android unit and instrumented tests, an arm64 emulator (API 36) for on-device verification.

**Spec:** `docs/plans/2026-09-07-skeleton-view-design.md`

## Global Constraints

- Bare `§N` refers to the spec above.
- **Never use the em dash character in prose.** Use a plain dash. Applies to code comments, commit messages and docs.
- **No agent attribution in commits.** No `Co-Authored-By` trailer, no "Generated with" footer.
- Android only. Nothing in `iosApp` changes. Anything placed in `:analysis` or `:shared` stays platform-neutral.
- `PlayerSample` and the v2 `PlayerTrackStore` format do not change.
- Keypoints are in source-video pixels everywhere below the platform layer, COCO-17 order, as `PoseRunner` emits them.
- Run every gradle command from the repo root. JVM suites: `./gradlew :analysis:jvmTest :shared:jvmTest :androidApp:testDebugUnitTest`. Instrumented: `./gradlew :androidApp:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true -Pandroid.testInstrumentationRunnerArguments.class=<fqcn>`; the second property keeps the app installed so its external files can be pulled.
- The emulator needs `adb push tools/models/onnx/posen.960.fp16.onnx /data/local/tmp/` and `adb push ~/shuttl-corpus/743d7fb1-ad6e-44d2-bd35-f9a6c0892a87/source.mp4 /data/local/tmp/corpus-743d7fb1.mp4` before any instrumented test that reads them. Both were pushed on 2026-09-07.
- Branch: `skeleton-view`, from `heatmap-accuracy` (this work reads the ankle-only selector and the v2 track store).

## Preconditions

- [ ] `git checkout -b skeleton-view heatmap-accuracy`
- [ ] `./gradlew :analysis:jvmTest :shared:jvmTest :androidApp:testDebugUnitTest` is green before the first task (141, 449 and 226 tests on 2026-09-07).

## File Structure

| File | Responsibility |
| --- | --- |
| `analysis/src/commonMain/.../player/NearPlayer.kt` | `Result` gains `person`. |
| `analysis/src/commonMain/.../player/PlayerTrack.kt` | `PlayerPose`, `NearPlayerSelection`, `selectNearPlayer`; `buildNearPlayerTrack` delegates. |
| `analysis/src/commonMain/.../player/PoseLookup.kt` | `nearestPose`, `poseToleranceS`. |
| `analysis/src/commonTest/.../player/NearPlayerSelectionTest.kt` | Selection returns poses beside samples. |
| `analysis/src/commonTest/.../player/PoseLookupTest.kt` | The lookup. |
| `analysis/src/commonTest/.../player/DevicePoseDumpTest.kt` | Extended: poses from real device output. |
| `shared/src/commonMain/.../local/LocalAnalysisCoordinator.kt` | Outcome gains `poses`, `videoWidth`, `videoHeight`. |
| `shared/src/commonTest/.../local/LocalAnalysisCoordinatorTest.kt` | Poses and size come through. |
| `androidApp/.../localanalysis/TrackNetRunner.kt` | `onFrame` passes the presentation time. |
| `androidApp/.../localanalysis/AndroidLocalInferenceEngine.kt` | `RawFrame.timestamp` is the container's. |
| `androidApp/.../localanalysis/VideoFrameSource.kt` | Frame-count fallback by sample scan. |
| `androidApp/.../localanalysis/SkeletonStore.kt` | The binary per-run pose file. |
| `androidApp/src/test/.../localanalysis/SkeletonStoreTest.kt` | Round trip, `has`, truncation. |
| `androidApp/.../localanalysis/LocalAnalysisRunner.kt` | Saves the skeleton; exposes it and the analysed source. |
| `androidApp/.../localanalysis/SkeletonPanel.kt` | Resolution, the player, the position loop, the overlay host. |
| `androidApp/.../analytics/AnalyticsDetailScreen.kt` | The two-segment control. |
| `androidApp/.../AuthGate.kt` | Passes the entry and playback prefs to the detail. |
| `androidApp/src/androidTest/.../localanalysis/VideoFrameSourceTest.kt` | Fallback agrees with the retriever. |
| `androidApp/src/androidTest/.../localanalysis/Phase2EndToEndTest.kt` | Timestamps are the container's. |

---

## Task 1: The selection returns the chosen person's joints

**Files:**
- Modify: `analysis/src/commonMain/kotlin/com/badmintontracker/analysis/player/NearPlayer.kt:118-127, 189`
- Modify: `analysis/src/commonMain/kotlin/com/badmintontracker/analysis/player/PlayerTrack.kt:30-73`
- Create: `analysis/src/commonTest/kotlin/com/badmintontracker/analysis/player/NearPlayerSelectionTest.kt`
- Modify: `analysis/src/commonTest/kotlin/com/badmintontracker/analysis/player/DevicePoseDumpTest.kt`

**Interfaces:**
- Consumes: `NearPlayerSelector.select(frame: PoseFrame): Result`, `RawInference`, `RawFrame.timestamp: Double`, `RawPerson.toPosePerson()`.
- Produces:
  - `data class PlayerPose(val frame: Int, val timestamp: Double, val keypoints: List<Point>, val confidence: List<Float>)`
  - `data class NearPlayerSelection(val track: PlayerTrack, val poses: List<PlayerPose>)`
  - `fun selectNearPlayer(raw: RawInference, keypoints: CourtKeypoints): NearPlayerSelection`
  - `NearPlayerSelector.Result(val sample: PlayerSample?, val rejection: RejectionReason?, val person: PosePerson? = null)`

- [ ] **Step 1: Write the failing test**

Create `analysis/src/commonTest/kotlin/com/badmintontracker/analysis/player/NearPlayerSelectionTest.kt`:

```kotlin
package com.badmintontracker.analysis.player

import com.badmintontracker.analysis.geometry.CourtKeypoints
import com.badmintontracker.analysis.geometry.Point
import com.badmintontracker.analysis.raw.RawBox
import com.badmintontracker.analysis.raw.RawFrame
import com.badmintontracker.analysis.raw.RawHeader
import com.badmintontracker.analysis.raw.RawInference
import com.badmintontracker.analysis.raw.RawKeypoint
import com.badmintontracker.analysis.raw.RawPerson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The skeleton and the heatmap are one selection. Whatever the selector picks
 * for the court position is the person whose joints are kept, at the same
 * frame, with the frame's own timestamp.
 */
class NearPlayerSelectionTest {

    // Corpus video 743d7fb1's marks, as the cloud stored them.
    private val keypoints = CourtKeypoints(
        topLeft = Point(649.5, 484.8),
        topRight = Point(1277.3, 481.2),
        bottomRight = Point(1579.4, 998.6),
        bottomLeft = Point(360.0, 1004.0),
        netLeft = Point(550.0, 663.9),
        netRight = Point(1382.2, 665.7),
        serviceLineNearLeft = Point(504.8, 743.5),
        serviceLineNearRight = Point(1422.0, 738.1),
        serviceLineFarLeft = Point(588.0, 595.2),
        serviceLineFarRight = Point(1338.8, 595.2),
        centerNear = Point(966.1, 593.4),
        centerFar = Point(966.1, 736.3),
    )

    private fun header(fps: Double = 25.0) = RawHeader(1, fps, 100, 1920, 1080, "test")

    /** A person standing at ([x],[y]) with a distinctive nose so the joints can be told apart. */
    private fun person(x: Double, y: Double, confidence: Float, noseX: Double): RawPerson {
        val kps = MutableList(Coco.COUNT) { RawKeypoint(x.toFloat(), (y - 100).toFloat(), 0.9f) }
        kps[0] = RawKeypoint(noseX.toFloat(), (y - 180).toFloat(), 0.9f)
        kps[Coco.LEFT_ANKLE] = RawKeypoint((x - 10).toFloat(), y.toFloat(), 0.9f)
        kps[Coco.RIGHT_ANKLE] = RawKeypoint((x + 10).toFloat(), y.toFloat(), 0.9f)
        return RawPerson(RawBox(0, confidence, 0f, 0f, 1f, 1f), kps)
    }

    private fun frame(index: Int, fps: Double, persons: List<RawPerson>, timestamp: Double = index / fps) =
        RawFrame(index, timestamp, null, emptyList(), persons)

    @Test
    fun every_sample_has_a_pose_at_the_same_frame_and_no_others() {
        val raw = RawInference(
            header(),
            listOf(
                frame(0, 25.0, listOf(person(966.0, 900.0, 0.9f, noseX = 111.0))),
                frame(1, 25.0, emptyList()),                                          // pose never ran
                frame(2, 25.0, listOf(person(966.0, 550.0, 0.9f, noseX = 222.0))),   // far side, rejected
                frame(3, 25.0, listOf(person(1200.0, 950.0, 0.8f, noseX = 333.0))),
            ),
        )
        val selection = selectNearPlayer(raw, keypoints)
        assertEquals(listOf(0, 3), selection.track.samples.map { it.frame })
        assertEquals(listOf(0, 3), selection.poses.map { it.frame })
        assertEquals(selection.track, buildNearPlayerTrack(raw, keypoints))
    }

    @Test
    fun the_pose_is_the_chosen_persons_joints() {
        // Two on the near court; the more confident wins the sample, so the
        // pose must be that person's joints, not the first person's.
        val raw = RawInference(
            header(),
            listOf(
                frame(
                    0, 25.0,
                    listOf(
                        person(700.0, 900.0, 0.4f, noseX = 111.0),
                        person(1200.0, 950.0, 0.95f, noseX = 999.0),
                    ),
                ),
            ),
        )
        val pose = selectNearPlayer(raw, keypoints).poses.single()
        assertEquals(999.0, pose.keypoints[0].x)
        assertEquals(Coco.COUNT, pose.keypoints.size)
        assertEquals(Coco.COUNT, pose.confidence.size)
        assertTrue(pose.confidence.all { it == 0.9f })
    }

    @Test
    fun the_pose_carries_the_frames_own_timestamp_not_frame_over_fps() {
        // A variable-frame-rate source: frame 3 is late. The pose must say
        // when the frame was actually shown, which is what playback matches on.
        val raw = RawInference(
            header(fps = 25.0),
            listOf(frame(3, 25.0, listOf(person(966.0, 900.0, 0.9f, noseX = 1.0)), timestamp = 0.2)),
        )
        assertEquals(0.2, selectNearPlayer(raw, keypoints).poses.single().timestamp)
    }

    @Test
    fun a_bad_court_yields_no_poses_either() {
        val raw = RawInference(header(), listOf(frame(0, 25.0, listOf(person(966.0, 900.0, 0.9f, noseX = 1.0)))))
        val scrambled = keypoints.copy(topLeft = keypoints.bottomRight, bottomRight = keypoints.topLeft)
        val selection = selectNearPlayer(raw, scrambled)
        assertTrue(selection.poses.isEmpty())
        assertEquals(1, selection.track.rejections[RejectionReason.BAD_COURT])
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :analysis:jvmTest --tests 'com.badmintontracker.analysis.player.NearPlayerSelectionTest' -q`
Expected: compilation failure, `Unresolved reference 'selectNearPlayer'`.

- [ ] **Step 3: Carry the person out of the selector**

In `NearPlayer.kt`, the loop body that assigns `best` (currently):

```kotlin
            if (person.boxConfidence > bestConfidence) {
                bestConfidence = person.boxConfidence
                best = PlayerSample(frame.frame, court)
            }
        }
        return Result(best, if (best == null) reason else null)
```

becomes:

```kotlin
            if (person.boxConfidence > bestConfidence) {
                bestConfidence = person.boxConfidence
                best = PlayerSample(frame.frame, court)
                bestPerson = person
            }
        }
        return Result(best, if (best == null) reason else null, bestPerson)
```

with `var bestPerson: PosePerson? = null` declared beside `var best`. And the result type:

```kotlin
    /**
     * [person] is the detection [sample] was taken from, so a caller that
     * wants the joints as well as the court position gets the same person
     * the heatmap got, by construction rather than by a second pass.
     */
    data class Result(
        val sample: PlayerSample?,
        val rejection: RejectionReason?,
        val person: PosePerson? = null,
    )
```

- [ ] **Step 4: Return poses beside the track**

In `PlayerTrack.kt`, after `PlayerTrack` and before `RawPerson.toPosePerson()`, add:

```kotlin
/** The near player's joints in one frame, in source-video pixels. */
data class PlayerPose(
    val frame: Int,
    /**
     * The frame's container presentation time in seconds. Playback matches
     * on this, never on frame / fps: on a variable-frame-rate source the two
     * disagree and the skeleton would drift off the body.
     */
    val timestamp: Double,
    /** COCO-17 order. */
    val keypoints: List<Point>,
    val confidence: List<Float>,
)

/**
 * The track and the poses, from one pass over the frames.
 *
 * One entry in [poses] per entry in [PlayerTrack.samples], at the same frame:
 * the heatmap's position and the skeleton's joints are the same person in the
 * same frame, and returning them together is what makes that true.
 */
data class NearPlayerSelection(val track: PlayerTrack, val poses: List<PlayerPose>)
```

Replace `buildNearPlayerTrack` with:

```kotlin
/**
 * Builds the near player's track from raw model output.
 *
 * Pure, so the whole of Phase 2's judgement is testable without a phone: the
 * platform layer's only job was to put keypoints in [RawInference].
 */
fun buildNearPlayerTrack(raw: RawInference, keypoints: CourtKeypoints): PlayerTrack =
    selectNearPlayer(raw, keypoints).track

/** [buildNearPlayerTrack], keeping the chosen person's joints as well. */
fun selectNearPlayer(raw: RawInference, keypoints: CourtKeypoints): NearPlayerSelection {
    val selector = NearPlayerSelector(
        keypoints,
        raw.header.videoWidth.toDouble(),
        raw.header.videoHeight.toDouble(),
    )
    // An unusable court is not a thin track, it is no track. Returning an empty
    // one with the reason counted keeps that distinguishable from a match where
    // the player was simply never found.
    if (!selector.usable) {
        return NearPlayerSelection(
            PlayerTrack(emptyList(), 0, mapOf(RejectionReason.BAD_COURT to raw.frames.size)),
            emptyList(),
        )
    }

    val samples = ArrayList<PlayerSample>()
    val poses = ArrayList<PlayerPose>()
    val rejections = mutableMapOf<RejectionReason, Int>()
    var framesWithPose = 0

    for (frame in raw.frames) {
        // Frames the pose model never ran on are not failures to find a player,
        // so they must not dilute coverage.
        if (frame.persons.isEmpty()) continue
        framesWithPose++
        val result = selector.select(PoseFrame(frame.frame, frame.persons.map { it.toPosePerson() }))
        val sample = result.sample
        val person = result.person
        if (sample != null && person != null) {
            samples.add(sample)
            poses.add(PlayerPose(frame.frame, frame.timestamp, person.keypoints, person.keypointConfidence))
        } else {
            result.rejection?.let { rejections[it] = (rejections[it] ?: 0) + 1 }
        }
    }
    return NearPlayerSelection(PlayerTrack(samples, framesWithPose, rejections), poses)
}
```

- [ ] **Step 5: Extend the device golden**

In `DevicePoseDumpTest.kt`, the test builds `frames: List<PoseFrame>` and selects per frame. Add a second test after the existing one:

```kotlin
    @Test
    fun the_device_output_yields_a_pose_for_every_sample() {
        val frames = frames() ?: return
        val raw = com.badmintontracker.analysis.raw.RawInference(
            header = com.badmintontracker.analysis.raw.RawHeader(1, 25.0, frames.size, 1920, 1080, "device"),
            frames = frames.map { f ->
                com.badmintontracker.analysis.raw.RawFrame(
                    frame = f.frame,
                    timestamp = f.frame / 25.0,
                    shuttle = null,
                    boxes = emptyList(),
                    persons = f.people.map { p ->
                        com.badmintontracker.analysis.raw.RawPerson(
                            box = com.badmintontracker.analysis.raw.RawBox(0, p.boxConfidence, 0f, 0f, 0f, 0f),
                            keypoints = p.keypoints.mapIndexed { k, pt ->
                                com.badmintontracker.analysis.raw.RawKeypoint(
                                    pt.x.toFloat(), pt.y.toFloat(), p.keypointConfidence[k],
                                )
                            },
                        )
                    },
                )
            },
        )
        val selection = selectNearPlayer(raw, keypoints)
        assertTrue(selection.track.samples.isNotEmpty())
        assertTrue(
            selection.poses.map { it.frame } == selection.track.samples.map { it.frame },
            "poses and samples must be at the same frames",
        )
        assertTrue(selection.poses.all { it.keypoints.size == Coco.COUNT && it.confidence.size == Coco.COUNT })
        // The ankles that produced the position are confident in every pose.
        assertTrue(
            selection.poses.all {
                it.confidence[Coco.LEFT_ANKLE] >= NearPlayerSelector.MIN_KEYPOINT_CONFIDENCE &&
                    it.confidence[Coco.RIGHT_ANKLE] >= NearPlayerSelector.MIN_KEYPOINT_CONFIDENCE
            },
        )
    }
```

Move the fully qualified `com.badmintontracker.analysis.raw.*` names into imports at the top of the file.

- [ ] **Step 6: Run the analysis suite**

Run: `./gradlew :analysis:jvmTest -q`
Expected: PASS, 141 + 5 tests.

- [ ] **Step 7: Commit**

```bash
git add analysis/src
git commit -m "feat(analysis): the near-player selection keeps the chosen person's joints"
```

---

## Task 2: Looking a pose up by playback time

**Files:**
- Create: `analysis/src/commonMain/kotlin/com/badmintontracker/analysis/player/PoseLookup.kt`
- Create: `analysis/src/commonTest/kotlin/com/badmintontracker/analysis/player/PoseLookupTest.kt`

**Interfaces:**
- Consumes: `PlayerPose` from Task 1.
- Produces:
  - `fun nearestPose(poses: List<PlayerPose>, seconds: Double, toleranceS: Double): PlayerPose?`
  - `fun poseToleranceS(fps: Double): Double`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.badmintontracker.analysis.player

import com.badmintontracker.analysis.geometry.Point
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PoseLookupTest {

    private fun pose(frame: Int, timestamp: Double) = PlayerPose(
        frame, timestamp, List(Coco.COUNT) { Point(0.0, 0.0) }, List(Coco.COUNT) { 0.9f },
    )

    // 25fps, with frame 2 missing: the player was not found in it.
    private val poses = listOf(pose(0, 0.0), pose(1, 0.04), pose(3, 0.12), pose(4, 0.16))
    private val tolerance = poseToleranceS(25.0)

    @Test
    fun an_exact_hit_returns_that_pose() {
        assertEquals(3, nearestPose(poses, 0.12, tolerance)?.frame)
    }

    @Test
    fun within_tolerance_on_either_side_returns_the_pose() {
        assertEquals(1, nearestPose(poses, 0.04 - 0.015, tolerance)?.frame)
        assertEquals(1, nearestPose(poses, 0.04 + 0.015, tolerance)?.frame)
    }

    @Test
    fun a_missing_frame_draws_nothing_rather_than_a_neighbour() {
        // 0.08 is frame 2's time. Frames 1 and 3 are a whole frame away,
        // outside half a frame, so the overlay must be empty there: holding a
        // neighbour would draw the player where they are not.
        assertNull(nearestPose(poses, 0.08, tolerance))
    }

    @Test
    fun between_two_poses_the_nearer_wins() {
        assertEquals(3, nearestPose(poses, 0.13, tolerance)?.frame)
        assertEquals(4, nearestPose(poses, 0.15, tolerance)?.frame)
    }

    @Test
    fun the_players_millisecond_truncation_still_resolves() {
        // ExoPlayer reports 33ms for a frame shown at 33.333ms. The tolerance
        // covers half a frame plus that millisecond.
        val thirty = listOf(pose(0, 0.0), pose(1, 1.0 / 30.0), pose(2, 2.0 / 30.0))
        assertEquals(1, nearestPose(thirty, 0.033, poseToleranceS(30.0))?.frame)
        assertEquals(2, nearestPose(thirty, 0.066, poseToleranceS(30.0))?.frame)
    }

    @Test
    fun before_the_first_and_after_the_last_are_within_tolerance_only() {
        assertEquals(0, nearestPose(poses, 0.01, tolerance)?.frame)
        assertNull(nearestPose(poses, 0.5, tolerance))
    }

    @Test
    fun an_empty_list_returns_null() {
        assertNull(nearestPose(emptyList(), 0.0, tolerance))
    }

    @Test
    fun the_tolerance_is_half_a_frame_plus_a_millisecond() {
        assertEquals(0.02 + 0.001, poseToleranceS(25.0), 1e-9)
        // A frame rate the container could not report falls back to 30fps
        // rather than to an infinite window.
        assertEquals(1.0 / 60.0 + 0.001, poseToleranceS(0.0), 1e-9)
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :analysis:jvmTest --tests 'com.badmintontracker.analysis.player.PoseLookupTest' -q`
Expected: compilation failure, `Unresolved reference 'nearestPose'`.

- [ ] **Step 3: Implement**

Create `PoseLookup.kt`:

```kotlin
package com.badmintontracker.analysis.player

import kotlin.math.abs

/**
 * How far a playback position may sit from a pose's timestamp and still be
 * that frame: half a frame period, plus one millisecond.
 *
 * Half a frame is the boundary between one frame and the next. The extra
 * millisecond is ExoPlayer's: it reports position in whole milliseconds,
 * rounded down, so a frame shown at 33.333ms reads as 33 and would otherwise
 * sit exactly on the boundary.
 */
fun poseToleranceS(fps: Double): Double {
    val rate = if (fps > 0.0) fps else FALLBACK_FPS
    return 0.5 / rate + 0.001
}

/**
 * The pose nearest [seconds], or null when none is within [toleranceS].
 *
 * Null is a result, not a failure: a frame the player was not found in has no
 * skeleton, and drawing a neighbour's would put the figure where the body is
 * not. [poses] must be sorted by timestamp, which a selection pass produces.
 */
fun nearestPose(poses: List<PlayerPose>, seconds: Double, toleranceS: Double): PlayerPose? {
    if (poses.isEmpty()) return null
    var lo = 0
    var hi = poses.size - 1
    while (lo < hi) {
        val mid = (lo + hi) / 2
        if (poses[mid].timestamp < seconds) lo = mid + 1 else hi = mid
    }
    // lo is the first pose at or after seconds; the one before may be nearer.
    val after = poses[lo]
    val before = if (lo > 0) poses[lo - 1] else null
    val best = when {
        before == null -> after
        abs(before.timestamp - seconds) <= abs(after.timestamp - seconds) -> before
        else -> after
    }
    return if (abs(best.timestamp - seconds) <= toleranceS) best else null
}

private const val FALLBACK_FPS = 30.0
```

- [ ] **Step 4: Run and commit**

Run: `./gradlew :analysis:jvmTest --tests 'com.badmintontracker.analysis.player.PoseLookupTest' -q`
Expected: PASS.

```bash
git add analysis/src/commonMain/kotlin/com/badmintontracker/analysis/player/PoseLookup.kt analysis/src/commonTest/kotlin/com/badmintontracker/analysis/player/PoseLookupTest.kt
git commit -m "feat(analysis): find the pose nearest a playback position, or none"
```

---

## Task 3: The outcome carries poses and the video size

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/badmintontracker/shared/local/LocalAnalysisCoordinator.kt:19-32, 136-160`
- Modify: `shared/src/commonTest/kotlin/com/badmintontracker/shared/local/LocalAnalysisCoordinatorTest.kt`

**Interfaces:**
- Consumes: `selectNearPlayer`, `PlayerPose` (Task 1); `RawHeader.videoWidth`, `videoHeight`.
- Produces: `LocalAnalysisOutcome(result, clipWindows, playerTrack, poses: List<PlayerPose>, videoWidth: Int, videoHeight: Int)`.

- [ ] **Step 1: Write the failing test**

Add to `LocalAnalysisCoordinatorTest.kt`, using the existing `twoExchanges()` and `FakeEngine`. First a helper that puts one near-court person into a frame; the fixture keypoints put the net at y=550 and the near baseline at y=900 with the court between x=200 and x=1700:

```kotlin
    private fun withPerson(raw: RawInference, frame: Int): RawInference {
        val kps = MutableList(17) { RawKeypoint(950f, 700f, 0.9f) }
        kps[15] = RawKeypoint(940f, 800f, 0.9f)
        kps[16] = RawKeypoint(960f, 800f, 0.9f)
        val person = RawPerson(RawBox(0, 0.9f, 0f, 0f, 1f, 1f), kps)
        return raw.copy(frames = raw.frames.map { if (it.frame == frame) it.copy(persons = listOf(person)) else it })
    }

    @Test
    fun poses_and_the_video_size_come_through_the_outcome() = runTest {
        val outcome = coordinator(FakeEngine(withPerson(twoExchanges(), frame = 7)))
            .analyze("/tmp/m.mp4", keypoints) {}
            .getOrThrow()
        assertEquals(listOf(7), outcome.poses.map { it.frame })
        assertEquals(7 / 30.0, outcome.poses.single().timestamp)
        assertEquals(listOf(7), outcome.playerTrack.samples.map { it.frame })
        assertEquals(1920, outcome.videoWidth)
        assertEquals(1080, outcome.videoHeight)
    }

    @Test
    fun a_pose_less_run_has_no_poses() = runTest {
        val outcome = coordinator(FakeEngine(twoExchanges())).analyze("/tmp/m.mp4", keypoints) {}.getOrThrow()
        assertTrue(outcome.poses.isEmpty())
    }
```

Add the imports the file lacks: `com.badmintontracker.analysis.raw.RawBox`, `RawKeypoint`, `RawPerson`, and `kotlin.test.assertTrue`.

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :shared:jvmTest --tests 'com.badmintontracker.shared.local.LocalAnalysisCoordinatorTest' -q`
Expected: compilation failure, `Unresolved reference 'poses'`.

- [ ] **Step 3: Implement**

`LocalAnalysisOutcome` becomes:

```kotlin
data class LocalAnalysisOutcome(
    val result: AnalysisResult,
    val clipWindows: List<ClipWindow>,
    /**
     * The near player's path, or an empty track when pose did not run.
     *
     * Empty rather than null: a Phase 1 run and a Phase 2 run that found
     * nobody are different facts, and [PlayerTrack] already distinguishes them
     * through framesWithPose and its rejection counts. A null would collapse
     * the two into "no data" at exactly the point a coach asks why the heatmap
     * is blank.
     */
    val playerTrack: PlayerTrack,
    /**
     * The same player's joints, one per sample, in source pixels. Always
     * computed: the selection produces them for free. Whether they are KEPT
     * is the runner's decision, from the metrics the coach asked for.
     */
    val poses: List<PlayerPose>,
    /** What [poses] are measured in, so a renderer can fit them to a display box. */
    val videoWidth: Int,
    val videoHeight: Int,
)
```

In `analyse`, replace the `buildNearPlayerTrack` block and the return:

```kotlin
        // Empty unless the engine was given a pose model, since Phase 1 frames
        // carry no persons at all.
        val selection = selectNearPlayer(raw, keypoints.toAnalysis())
        val playerTrack = selection.track
        if (playerTrack.rejections.containsKey(RejectionReason.BAD_COURT)) {
            log("near player: the court marks do not fit a court; no positions taken")
        } else if (playerTrack.framesWithPose > 0) {
            log(
                "near player: ${playerTrack.samples.size} samples over " +
                    "${playerTrack.framesWithPose} pose frames",
            )
        }

        return LocalAnalysisOutcome(
            playerTrack = playerTrack,
            poses = selection.poses,
            videoWidth = header.videoWidth,
            videoHeight = header.videoHeight,
            result = AnalysisResult.fromPhase1(
                output = output,
                fps = fps,
                totalFrames = header.totalFrames,
                durationSeconds = durationSeconds(header.totalFrames, fps),
                filename = videoPath.substringAfterLast('/'),
            ),
            clipWindows = output.clipWindows,
        )
```

Replace the import of `buildNearPlayerTrack` with `com.badmintontracker.analysis.player.selectNearPlayer` and add `com.badmintontracker.analysis.player.PlayerPose`.

- [ ] **Step 4: Run and commit**

Run: `./gradlew :shared:jvmTest :androidApp:testDebugUnitTest -q`
Expected: PASS. (`LocalAnalysisRunner` constructs `Done` from `result.playerTrack`, unchanged, so nothing else breaks.)

```bash
git add shared/src
git commit -m "feat(shared): the local analysis outcome carries the poses and the video size"
```

---

## Task 4: The decode pass carries the container timestamps

**Files:**
- Modify: `androidApp/src/main/java/com/badmintontracker/android/localanalysis/TrackNetRunner.kt:56, 100`
- Modify: `androidApp/src/main/java/com/badmintontracker/android/localanalysis/AndroidLocalInferenceEngine.kt:66, 75-76, 112-118`
- Modify: `androidApp/src/androidTest/java/com/badmintontracker/android/localanalysis/Phase2EndToEndTest.kt`

**Interfaces:**
- Consumes: `VideoFrameSource.forEachFrame(maxFrames) { index, seconds, image -> }` (already passes the presentation time).
- Produces: `TrackNetRunner.track(..., onFrame: (Int, Double, android.media.Image) -> Unit)`; `RawFrame.timestamp` is the container presentation time.

- [ ] **Step 1: Write the failing assertion**

In `Phase2EndToEndTest.a_video_becomes_a_shuttle_track_and_a_player_heatmap`, after `val raw = runBlocking { ... }`, add:

```kotlin
        // The timestamps are the container's, not i / fps. On this constant-rate
        // corpus the two agree to well under a frame, but the container's
        // values carry sub-millisecond precision and i / fps does not: a frame
        // whose timestamp equals its index over fps to the last bit was made
        // up rather than read.
        val fps = raw.header.fps
        assertTrue("first frame at 0s", raw.frames.first().timestamp == 0.0)
        assertTrue(
            "timestamps must be non-decreasing",
            raw.frames.zipWithNext().all { (a, b) -> b.timestamp >= a.timestamp },
        )
        val fabricated = raw.frames.drop(1).count { it.timestamp == it.frame / fps }
        assertTrue("$fabricated of ${raw.frames.size} timestamps are exactly frame / fps", fabricated < raw.frames.size / 2)
        assertTrue(
            "timestamps must be within a frame of frame / fps on a constant-rate source",
            raw.frames.all { kotlin.math.abs(it.timestamp - it.frame / fps) < 1.0 / fps },
        )
```

- [ ] **Step 2: Pass the time through the runner**

In `TrackNetRunner.kt:56`, change the parameter:

```kotlin
        /** Every decoded frame, its container presentation time in seconds, and the image. */
        onFrame: (Int, Double, android.media.Image) -> Unit = { _, _, _ -> },
```

and at `:99-100`:

```kotlin
            source.forEachFrame(maxFrames) { index, seconds, image ->
                onFrame(index, seconds, image)
```

- [ ] **Step 3: Record it in the engine**

In `AndroidLocalInferenceEngine.run`, beside `val people = HashMap<Int, List<RawPerson>>()`:

```kotlin
        // The container's presentation time per frame. RawFrame.timestamp used
        // to be i / fps, which VideoFrameSource's own KDoc says is wrong on a
        // variable-frame-rate source; a skeleton drawn over playback is where
        // that would show.
        val timestamps = HashMap<Int, Double>()
```

Change the callback:

```kotlin
                    onFrame = { index, seconds, image ->
                        timestamps[index] = seconds
                        val found = detector.detect(image)
```

and in the frame assembly:

```kotlin
                timestamp = timestamps[i] ?: if (meta.fps > 0) i / meta.fps else 0.0,
```

- [ ] **Step 4: Compile, then run the end-to-end test on the emulator (after Task 5, which the emulator needs)**

Run now: `./gradlew :androidApp:compileDebugKotlin :androidApp:compileDebugAndroidTestKotlin -q`
Expected: compiles. The instrumented run is Task 5 Step 5, once `metadata()` works on the emulator.

- [ ] **Step 5: Commit**

```bash
git add androidApp/src/main/java/com/badmintontracker/android/localanalysis/TrackNetRunner.kt androidApp/src/main/java/com/badmintontracker/android/localanalysis/AndroidLocalInferenceEngine.kt androidApp/src/androidTest/java/com/badmintontracker/android/localanalysis/Phase2EndToEndTest.kt
git commit -m "fix(android): raw frames carry the container's timestamp, not frame over fps"
```

---

## Task 5: A frame count when the retriever has none

**Files:**
- Modify: `androidApp/src/main/java/com/badmintontracker/android/localanalysis/VideoFrameSource.kt:31-48`
- Modify: `androidApp/src/androidTest/java/com/badmintontracker/android/localanalysis/VideoFrameSourceTest.kt`

**Interfaces:**
- Produces: `VideoFrameSource.metadata()` succeeds without `METADATA_KEY_VIDEO_FRAME_COUNT`; `VideoFrameSource.countSamples(): Int`.

- [ ] **Step 1: Write the failing test**

Add to `VideoFrameSourceTest.kt`:

```kotlin
    @Test
    fun the_sample_scan_counts_what_the_container_reports() {
        // The fallback for a retriever that has no frame count (the emulator's
        // does not; the S23's does). Both must agree on a file where both work,
        // and metadata() must succeed on the emulator through the fallback.
        val src = VideoFrameSource(requireVideo())
        assertEquals(5972, src.countSamples())
        assertEquals(5972, src.metadata().frameCount)
    }
```

- [ ] **Step 2: Run it on the emulator to verify it fails**

Run: `./gradlew :androidApp:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true -Pandroid.testInstrumentationRunnerArguments.class=com.badmintontracker.android.localanalysis.VideoFrameSourceTest`
Expected: compilation failure on `countSamples`; and, once stubbed, `the_container_reports_the_frame_count_the_desktop_run_measured` fails with `no frame count in corpus-743d7fb1.mp4`.

- [ ] **Step 3: Implement**

Replace `metadata()`:

```kotlin
    fun metadata(): Metadata = MediaMetadataRetriever().use { r ->
        r.setDataSource(file.path)
        fun meta(key: Int) = r.extractMetadata(key)
        // Not every retriever fills the frame count in: the arm64 emulator's
        // reports none for a file the S23's counts fine. Scanning the samples
        // reads no pixels and costs a few hundred milliseconds on a
        // 30-minute file, against a pipeline that cannot start without it.
        val frames = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toIntOrNull()
            ?: countSamples()
        val durationMs = meta(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toDouble()
        Metadata(
            frameCount = frames,
            width = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt()
                ?: error("no width in ${file.name}"),
            height = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt()
                ?: error("no height in ${file.name}"),
            // Frames over duration rather than CAPTURE_FRAMERATE, which is
            // absent on most files and reports the recording rate rather than
            // the playback rate when present.
            fps = if (durationMs != null && durationMs > 0) frames * 1000.0 / durationMs else 0.0,
        )
    }

    /**
     * The number of samples on the video track, by walking the container.
     *
     * One sample is one encoded frame for every codec this app decodes, so
     * this is the frame count. It decodes nothing.
     */
    fun countSamples(): Int {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            val track = (0 until extractor.trackCount).first { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            }
            extractor.selectTrack(track)
            var count = 0
            while (extractor.sampleTime >= 0) {
                count++
                if (!extractor.advance()) break
            }
            return count
        } finally {
            extractor.release()
        }
    }
```

- [ ] **Step 4: Run the frame source tests on the emulator**

Run: `./gradlew :androidApp:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true -Pandroid.testInstrumentationRunnerArguments.class=com.badmintontracker.android.localanalysis.VideoFrameSourceTest`
Expected: PASS, all tests including the two that call `metadata()`.

- [ ] **Step 5: Run Task 4's end-to-end assertion on the emulator**

Run: `./gradlew :androidApp:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true -Pandroid.testInstrumentationRunnerArguments.class=com.badmintontracker.android.localanalysis.Phase2EndToEndTest -Pandroid.testInstrumentationRunnerArguments.frames=150`
Expected: PASS. This runs TrackNet, the detector and pose on 150 frames plus the 300-seek background pre-pass; allow ten to twenty minutes on the emulator. Read the `Phase2` logcat line: `adb logcat -d -s Phase2`.

- [ ] **Step 6: Commit**

```bash
git add androidApp/src/main/java/com/badmintontracker/android/localanalysis/VideoFrameSource.kt androidApp/src/androidTest/java/com/badmintontracker/android/localanalysis/VideoFrameSourceTest.kt
git commit -m "fix(android): count samples when the retriever has no frame count"
```

---

## Task 6: `SkeletonStore`

**Files:**
- Create: `androidApp/src/main/java/com/badmintontracker/android/localanalysis/SkeletonStore.kt`
- Create: `androidApp/src/test/java/com/badmintontracker/android/localanalysis/SkeletonStoreTest.kt`

**Interfaces:**
- Consumes: `PlayerPose`, `Point`, `Coco.COUNT`.
- Produces:
  - `class SkeletonStore(root: File)`
  - `fun save(entryId: String, poses: List<PlayerPose>, fps: Double, videoWidth: Int, videoHeight: Int)`
  - `fun has(entryId: String): Boolean`
  - `fun load(entryId: String): Stored?` where `data class Stored(val poses: List<PlayerPose>, val fps: Double, val videoWidth: Int, val videoHeight: Int)`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.badmintontracker.android.localanalysis

import com.badmintontracker.analysis.geometry.Point
import com.badmintontracker.analysis.player.Coco
import com.badmintontracker.analysis.player.PlayerPose
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The per-run pose file: written once after a run, read back on the detail
 * screen, and refused when it is not something this app can draw.
 */
class SkeletonStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun store() = SkeletonStore(temp.root)

    private fun pose(frame: Int) = PlayerPose(
        frame = frame,
        timestamp = frame / 25.0,
        keypoints = List(Coco.COUNT) { Point(frame + it * 1.5, frame * 2.0 + it) },
        confidence = List(Coco.COUNT) { 0.5f + it / 100f },
    )

    @Test
    fun a_saved_skeleton_reads_back_exactly() {
        val s = store()
        val poses = listOf(pose(0), pose(1), pose(3))
        s.save("e1", poses, fps = 25.0, videoWidth = 1920, videoHeight = 1080)
        val stored = s.load("e1")!!
        stored.fps shouldBe 25.0
        stored.videoWidth shouldBe 1920
        stored.videoHeight shouldBe 1080
        stored.poses.size shouldBe 3
        stored.poses.map { it.frame } shouldBe listOf(0, 1, 3)
        stored.poses[2].timestamp shouldBe 3 / 25.0
        // Floats survive as floats: the file stores what the model emitted.
        stored.poses[1].keypoints[5] shouldBe Point(1 + 5 * 1.5, 2.0 + 5)
        stored.poses[1].confidence[16] shouldBe 0.66f
    }

    @Test
    fun a_video_never_analysed_for_skeletons_has_none() {
        store().has("e1") shouldBe false
        store().load("e1") shouldBe null
    }

    @Test
    fun has_reads_only_the_header_and_agrees_with_load_on_a_healthy_file() {
        val s = store()
        s.save("e1", listOf(pose(0)), 30.0, 1280, 720)
        s.has("e1") shouldBe true
        s.has("e2") shouldBe false
        s.has("e1") shouldBe (s.load("e1") != null)
    }

    @Test
    fun another_format_version_is_refused() {
        val s = store()
        s.save("e1", listOf(pose(0)), 30.0, 1280, 720)
        val onDisk = temp.root.walkTopDown().first { it.isFile && it.name.startsWith("e1") }
        val bytes = onDisk.readBytes()
        bytes[4] = 9 // version, little-endian low byte
        onDisk.writeBytes(bytes)
        s.has("e1") shouldBe false
        s.load("e1") shouldBe null
    }

    @Test
    fun a_file_truncated_mid_pose_loads_as_nothing() {
        val s = store()
        s.save("e1", listOf(pose(0), pose(1)), 30.0, 1280, 720)
        val onDisk = temp.root.walkTopDown().first { it.isFile && it.name.startsWith("e1") }
        val bytes = onDisk.readBytes()
        onDisk.writeBytes(bytes.copyOf(bytes.size - 10))
        // A skeleton with a pose missing its last joints is not a skeleton.
        s.load("e1") shouldBe null
    }

    @Test
    fun saving_nothing_writes_nothing() {
        val s = store()
        s.save("e1", emptyList(), 30.0, 1280, 720)
        s.has("e1") shouldBe false
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :androidApp:testDebugUnitTest --tests 'com.badmintontracker.android.localanalysis.SkeletonStoreTest' -q`
Expected: compilation failure, `Unresolved reference 'SkeletonStore'`.

- [ ] **Step 3: Implement**

```kotlin
package com.badmintontracker.android.localanalysis

import com.badmintontracker.analysis.geometry.Point
import com.badmintontracker.analysis.player.Coco
import com.badmintontracker.analysis.player.PlayerPose
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The near player's joints for one analysed video, kept on disk so the
 * skeleton view outlives the run that made it.
 *
 * Binary, because a person does not read a joint list and a 30-minute match is
 * 11MB of them: 216 bytes a pose against the track store's three numbers. The
 * layout is fixed width and little-endian, so [has] can answer from the header
 * and [load] can check the length before parsing a byte.
 *
 *   magic "SKEL", version i32, fps f64, videoWidth i32, videoHeight i32,
 *   poseCount i32, then per pose: frame i32, timestamp f64, 17 x (x f32, y f32, c f32)
 *
 * The timestamp is the frame's container presentation time, which is what
 * playback matches on; the video size is what the joints are measured in.
 * Both travel with the poses because a renderer that took either from
 * somewhere else would be pairing them by coincidence.
 */
class SkeletonStore(private val root: File) {

    data class Stored(
        val poses: List<PlayerPose>,
        val fps: Double,
        val videoWidth: Int,
        val videoHeight: Int,
    )

    fun save(entryId: String, poses: List<PlayerPose>, fps: Double, videoWidth: Int, videoHeight: Int) {
        // Nothing to draw is nothing to offer: no file, so has() stays false.
        if (poses.isEmpty()) return
        val buffer = ByteBuffer.allocate(HEADER_BYTES + poses.size * POSE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(MAGIC).putInt(VERSION).putDouble(fps).putInt(videoWidth).putInt(videoHeight).putInt(poses.size)
        poses.forEach { pose ->
            require(pose.keypoints.size == Coco.COUNT && pose.confidence.size == Coco.COUNT) {
                "pose at frame ${pose.frame} has ${pose.keypoints.size} joints"
            }
            buffer.putInt(pose.frame).putDouble(pose.timestamp)
            for (k in 0 until Coco.COUNT) {
                buffer.putFloat(pose.keypoints[k].x.toFloat())
                    .putFloat(pose.keypoints[k].y.toFloat())
                    .putFloat(pose.confidence[k])
            }
        }
        val file = fileFor(entryId).apply { parentFile?.mkdirs() }
        file.writeBytes(buffer.array())
    }

    /** Whether a skeleton this version can draw exists for [entryId], from the header alone. */
    fun has(entryId: String): Boolean {
        val file = fileFor(entryId)
        if (!file.isFile || file.length() < HEADER_BYTES) return false
        return runCatching {
            val head = ByteArray(8)
            file.inputStream().use { it.read(head) }
            val buffer = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(4).also { buffer.get(it) }
            magic.contentEquals(MAGIC) && buffer.getInt() == VERSION
        }.getOrDefault(false)
    }

    /** Null when there is nothing stored, or when what is stored cannot be read whole. */
    fun load(entryId: String): Stored? {
        val file = fileFor(entryId)
        if (!file.isFile) return null
        return runCatching {
            val buffer = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(4).also { buffer.get(it) }
            if (!magic.contentEquals(MAGIC)) return null
            if (buffer.getInt() != VERSION) return null
            val fps = buffer.getDouble()
            val width = buffer.getInt()
            val height = buffer.getInt()
            val count = buffer.getInt()
            // Length checked before parsing: a file killed mid-write ends in
            // a partial pose, and a skeleton missing its last joints is not one.
            if (buffer.remaining() != count * POSE_BYTES) return null
            val poses = ArrayList<PlayerPose>(count)
            repeat(count) {
                val frame = buffer.getInt()
                val timestamp = buffer.getDouble()
                val keypoints = ArrayList<Point>(Coco.COUNT)
                val confidence = ArrayList<Float>(Coco.COUNT)
                repeat(Coco.COUNT) {
                    keypoints.add(Point(buffer.getFloat().toDouble(), buffer.getFloat().toDouble()))
                    confidence.add(buffer.getFloat())
                }
                poses.add(PlayerPose(frame, timestamp, keypoints, confidence))
            }
            Stored(poses, fps, width, height)
        }.getOrNull()
    }

    private fun fileFor(entryId: String) = File(root, "skeletons/$entryId.skel")

    private companion object {
        val MAGIC = "SKEL".toByteArray(Charsets.US_ASCII)
        const val VERSION = 1
        const val HEADER_BYTES = 4 + 4 + 8 + 4 + 4 + 4
        const val POSE_BYTES = 4 + 8 + Coco.COUNT * 12
    }
}
```

- [ ] **Step 4: Run and commit**

Run: `./gradlew :androidApp:testDebugUnitTest --tests 'com.badmintontracker.android.localanalysis.SkeletonStoreTest' -q`
Expected: PASS, 6 tests.

```bash
git add androidApp/src/main/java/com/badmintontracker/android/localanalysis/SkeletonStore.kt androidApp/src/test/java/com/badmintontracker/android/localanalysis/SkeletonStoreTest.kt
git commit -m "feat(android): a binary store for the near player's poses"
```

---

## Task 7: The runner keeps the skeleton when it was asked for

**Files:**
- Modify: `androidApp/src/main/java/com/badmintontracker/android/localanalysis/LocalAnalysisRunner.kt:89-101, 172-175, 224-234`

**Interfaces:**
- Consumes: `SkeletonStore` (Task 6), `LocalAnalysisOutcome.poses/videoWidth/videoHeight` (Task 3), `AnalysisMetric.SKELETON_PLAYBACK`.
- Produces:
  - `fun hasStoredSkeleton(entryId: String): Boolean`
  - `fun storedSkeleton(entryId: String): SkeletonStore.Stored?`
  - `fun analysedSource(entryId: String, videoUri: String): File?`

- [ ] **Step 1: Add the store beside the track store**

Below `private val tracks = PlayerTrackStore(context.filesDir)`:

```kotlin
    private val skeletons = SkeletonStore(context.filesDir)

    /** A skeleton from an earlier run, for the same reason as [storedTrack]. */
    fun storedSkeleton(entryId: String): SkeletonStore.Stored? = skeletons.load(entryId)

    /** Header-only, for a screen deciding whether it has a second renderer to offer. */
    fun hasStoredSkeleton(entryId: String): Boolean = skeletons.has(entryId)

    /**
     * The file the analysis actually decoded, if it is still there.
     *
     * A skeleton is indexed by the frames of THIS file. The entry's own URI may
     * be a content grant that has since been revoked, and a copy made by
     * another app may be re-encoded; neither shares the analysed frames. Null
     * when the copy was never made or has been cleaned up, in which case the
     * skeleton has no video to sit on and the panel says so.
     */
    fun analysedSource(entryId: String, videoUri: String): File? {
        val direct = File(videoUri.removePrefix("file://"))
        if (direct.isFile && direct.canRead()) return direct
        return File(context.filesDir, "local-sources/$entryId.mp4").takeIf { it.isFile && it.length() > 0 }
    }
```

- [ ] **Step 2: Save it after the track**

After the `tracks.save(...)` block at `:172-175`:

```kotlin
                // Kept only when asked for: the metric selector prices the
                // skeleton as storage, and a coach who declined it should not
                // pay it. Written here, before the clips, for the same reason
                // the track is.
                if (AnalysisMetric.SKELETON_PLAYBACK in metrics && result.poses.isNotEmpty()) {
                    skeletons.save(entryId, result.poses, result.result.fps, result.videoWidth, result.videoHeight)
                }
```

- [ ] **Step 3: Compile and run the unit suite**

Run: `./gradlew :androidApp:testDebugUnitTest -q`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add androidApp/src/main/java/com/badmintontracker/android/localanalysis/LocalAnalysisRunner.kt
git commit -m "feat(android): a run that asked for skeleton playback keeps its poses"
```

---

## Task 8: The panel, the player, and the second renderer on the detail

**Files:**
- Create: `androidApp/src/main/java/com/badmintontracker/android/localanalysis/SkeletonPanel.kt`
- Modify: `androidApp/src/main/java/com/badmintontracker/android/analytics/AnalyticsDetailScreen.kt:22-73`
- Modify: `androidApp/src/main/java/com/badmintontracker/android/AuthGate.kt:608-614`

**Interfaces:**
- Consumes: `LocalAnalysisRunner.hasStoredSkeleton/storedSkeleton/analysedSource` (Task 7), `nearestPose`/`poseToleranceS` (Task 2), `SkeletonOverlay`, `PlaybackControlBar(player, prefs)`, `FrameStepBar(player)`, `LocalVideoRepository.get(id)`, `PlaybackPreferenceRepository`.
- Produces:
  - `@Composable fun SkeletonPanel(entryId: String, videoUri: String?, runner: LocalAnalysisRunner, prefs: PlaybackPreferenceRepository, modifier: Modifier = Modifier)`
  - `AnalyticsDetailScreen(entryId, localAnalysis, localVideos, playbackPrefs, onBack)`
  - `internal enum class AnalyticsPanel { Heatmap, Skeleton }`

- [ ] **Step 1: The panel**

Create `SkeletonPanel.kt`:

```kotlin
package com.badmintontracker.android.localanalysis

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.ui.PlayerView
import com.badmintontracker.analysis.player.nearestPose
import com.badmintontracker.analysis.player.poseToleranceS
import com.badmintontracker.android.clipdetail.FrameStepBar
import com.badmintontracker.android.clipdetail.PlaybackControlBar
import com.badmintontracker.shared.prefs.PlaybackPreferenceRepository
import java.io.File

/**
 * The near player's skeleton over the video it was measured on.
 *
 * Two things have to exist: the poses a run kept (only when the skeleton
 * metric was ticked) and the file that run decoded. Either missing is said in
 * one line rather than drawn around, because a skeleton over the wrong frames
 * looks like tracking that is broken, and a coach cannot tell that from
 * tracking that is bad.
 */
@Composable
fun SkeletonPanel(
    entryId: String,
    videoUri: String?,
    runner: LocalAnalysisRunner,
    prefs: PlaybackPreferenceRepository,
    modifier: Modifier = Modifier,
) {
    val stored = remember(entryId) { runner.storedSkeleton(entryId) }
    val source = remember(entryId, videoUri) { videoUri?.let { runner.analysedSource(entryId, it) } }

    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        when {
            stored == null -> Text(
                "No skeleton was kept for this video. Run the analysis again with " +
                    "\"Skeleton playback\" ticked.",
                modifier = Modifier.padding(16.dp),
            )
            source == null -> Text(
                "The video this skeleton was measured on is no longer on this phone.",
                modifier = Modifier.padding(16.dp),
            )
            else -> SkeletonPlayer(source, stored, prefs)
        }
    }
}

/**
 * The player, the overlay, and the loop that keeps them on the same frame.
 *
 * The box takes the video's own aspect ratio, as the court-marking screen
 * does, so the player fills it edge to edge and the overlay's letterbox
 * arithmetic reduces to the identity. The position is read once per
 * display frame with `withFrameNanos`, because Media3 has no per-frame
 * position callback and a coach stepping frame by frame needs the joints to
 * move with the frame, not a few hundred milliseconds after it.
 */
@Composable
private fun SkeletonPlayer(
    source: File,
    stored: SkeletonStore.Stored,
    prefs: PlaybackPreferenceRepository,
) {
    val context = LocalContext.current
    val player = remember {
        ExoPlayer.Builder(context).build().apply { setSeekParameters(SeekParameters.EXACT) }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    LaunchedEffect(source) {
        player.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(source)))
        player.prepare()
        player.playWhenReady = false
    }

    var positionMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(player) {
        while (true) {
            withFrameNanos { }
            positionMs = player.currentPosition
        }
    }
    val tolerance = remember(stored.fps) { poseToleranceS(stored.fps) }
    val pose = remember(positionMs, stored) { nearestPose(stored.poses, positionMs / 1000.0, tolerance) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(stored.videoWidth.toFloat() / stored.videoHeight.toFloat())
            .background(Color.Black),
    ) {
        AndroidView(
            factory = { c ->
                PlayerView(c).apply {
                    this.player = player
                    // The bars below own transport; a controller over the
                    // skeleton would sit exactly where the joints are.
                    useController = false
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (pose != null) {
            SkeletonOverlay(
                keypoints = pose.keypoints,
                confidence = pose.confidence,
                videoWidth = stored.videoWidth,
                videoHeight = stored.videoHeight,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
    PlaybackControlBar(player = player, prefs = prefs)
    FrameStepBar(player = player)
    Text(
        "Skeleton in ${stored.poses.size} frames" +
            (pose?.let { " · frame ${it.frame}" } ?: " · no skeleton at this frame"),
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}
```

`PlayerView` inflated in code needs the texture surface the XML gives the other players only if the overlay fails to draw above it; if the skeleton is not visible on the emulator, replace the `PlayerView(c)` construction with the same `LayoutInflater.from(c).inflate(R.layout.clip_player_view, null) as PlayerView` the players use (`LocalPlayerScreen.kt:152-154`).

- [ ] **Step 2: The detail screen**

Replace the body of `AnalyticsDetailScreen` and its signature:

```kotlin
/** Which renderer the detail is showing. Only offered when both exist. */
internal enum class AnalyticsPanel { Heatmap, Skeleton }

/**
 * What one analysed match has to show, reached from a READY row on the
 * Analytics list.
 *
 * A two-segment control between the heatmap and the skeleton, drawn only when
 * a skeleton is stored for this video. The earlier version of this screen had
 * no tab row, deliberately: with one renderer a single pill read as a primary
 * button that did nothing, and a control that cannot be actuated is worse
 * than a plain heading. Two segments with two things behind them is the case
 * that version was waiting for. Built as the match page builds its facet
 * selector, and gated the same way: both sides must have content.
 *
 * The heatmap itself, and the resolution of which track to draw, are
 * [HeatmapPanel]'s - shared with the standalone route the analysis banner
 * opens, so the two cannot drift. The skeleton is [SkeletonPanel]'s.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalyticsDetailScreen(
    entryId: String,
    localAnalysis: LocalAnalysisRunner,
    localVideos: LocalVideoRepository,
    playbackPrefs: PlaybackPreferenceRepository,
    onBack: () -> Unit,
) {
    val hasSkeleton = remember(entryId) { localAnalysis.hasStoredSkeleton(entryId) }
    var panel by rememberSaveable { mutableStateOf(AnalyticsPanel.Heatmap) }
    val videoUri = remember(entryId) { localVideos.get(entryId)?.uri }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("ANALYSIS") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = { BackgroundWorkAction() },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (hasSkeleton) {
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    listOf(AnalyticsPanel.Heatmap to "Heatmap", AnalyticsPanel.Skeleton to "Skeleton")
                        .forEachIndexed { index, (candidate, label) ->
                            SegmentedButton(
                                selected = panel == candidate,
                                onClick = { panel = candidate },
                                shape = SegmentedButtonDefaults.itemShape(index, 2),
                            ) { Text(label) }
                        }
                }
            } else {
                // Styled as the list's SectionHeader, not as a title: a coach arrives
                // here in one tap from that list, and two treatments of the same thing
                // across those two screens reads as two different kinds of heading.
                Text(
                    "HEATMAP",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
            when (if (hasSkeleton) panel else AnalyticsPanel.Heatmap) {
                AnalyticsPanel.Heatmap -> HeatmapPanel(entryId = entryId, runner = localAnalysis)
                AnalyticsPanel.Skeleton -> SkeletonPanel(
                    entryId = entryId,
                    videoUri = videoUri,
                    runner = localAnalysis,
                    prefs = playbackPrefs,
                )
            }
        }
    }
}
```

Add the imports: `androidx.compose.foundation.layout.fillMaxWidth`, `androidx.compose.material3.SegmentedButton`, `SegmentedButtonDefaults`, `SingleChoiceSegmentedButtonRow`, `androidx.compose.runtime.getValue`, `setValue`, `mutableStateOf`, `remember`, `androidx.compose.runtime.saveable.rememberSaveable`, `com.badmintontracker.android.localanalysis.SkeletonPanel`, `com.badmintontracker.shared.localvideo.LocalVideoRepository`, `com.badmintontracker.shared.prefs.PlaybackPreferenceRepository`.

- [ ] **Step 3: Wire the route**

In `AuthGate.kt:608-614`:

```kotlin
                composable<Route.AnalyticsDetail> { entry ->
                    val args = entry.toRoute<Route.AnalyticsDetail>()
                    AnalyticsDetailScreen(
                        entryId = args.entryId,
                        localAnalysis = localAnalysis,
                        localVideos = localVideos,
                        playbackPrefs = rally.playbackPrefs,
                        onBack = { nav.popBackStack() },
                    )
                }
```

- [ ] **Step 4: Compile and run the unit suite**

Run: `./gradlew :androidApp:compileDebugKotlin :androidApp:testDebugUnitTest -q`
Expected: compiles; PASS.

- [ ] **Step 5: Commit**

```bash
git add androidApp/src/main/java/com/badmintontracker/android/localanalysis/SkeletonPanel.kt androidApp/src/main/java/com/badmintontracker/android/analytics/AnalyticsDetailScreen.kt androidApp/src/main/java/com/badmintontracker/android/AuthGate.kt
git commit -m "feat(android): the skeleton view, the Analytics detail's second renderer"
```

---

## Task 9: On the emulator, with screenshots

**Files:**
- Create: `docs/screenshots/android-analytics-skeleton-*.png`
- Modify: `CHANGELOG.md` (Unreleased, Added)
- Modify: `docs/plans/2026-09-07-skeleton-view-design.md` (status line)
- Modify: `shared/src/commonMain/kotlin/com/badmintontracker/shared/local/AnalysisPlan.kt:20-22` (the metric's KDoc says what it keeps now)

- [ ] **Step 1: Install and run an analysis with the skeleton metric**

`./gradlew :androidApp:installDebug`, then on the emulator: sign in, import `corpus-743d7fb1.mp4` from `/data/local/tmp` (push it to `/sdcard/Download/` first if the picker cannot see `/data/local/tmp`), mark the court with the twelve points, tick **Skeleton playback**, start the on-device analysis and wait for it. Use `tools/adb-tap.sh` for the taps, as the earlier visual sweeps did. Expect the run to take tens of minutes on the emulator; the background indicator shows progress from any screen.

- [ ] **Step 2: Open the detail and check every state**

- Analytics list, the READY row, the detail: the segmented control shows "Heatmap" and "Skeleton".
- Skeleton: the video with the near player's joints on the body on a standing frame. Step with "Next frame" to a frame of contact and confirm the wrist follows the racket arm; step back and forward and confirm the skeleton moves with each frame and never lags.
- Play, then pause: no drift between the joints and the body.
- A frame where the player was not found (step through a lunge or a moment they leave the near court): nothing drawn, and the line under the bars says "no skeleton at this frame".
- Rotate the emulator: the chosen panel survives.
- A video analysed WITHOUT the metric: no segmented control, the "HEATMAP" heading as before.
- Delete `local-sources/<id>.mp4` with `adb shell run-as com.badmintontracker.android rm files/local-sources/<id>.mp4` and reopen: the panel says the video is no longer on this phone.

- [ ] **Step 3: Screenshots**

`adb exec-out screencap -p > docs/screenshots/android-analytics-skeleton-light.png` for: the segmented control with the skeleton on a standing frame, the skeleton at contact, and the "no skeleton at this frame" line; repeat in dark theme with `-dark` suffixes. Six files. `adb exec-out` writes fresh each time, unlike `simctl`.

- [ ] **Step 4: Docs**

`CHANGELOG.md`, under `## [Unreleased]` / `### Added`, at the top of the list:

```markdown
- The Analytics detail can now show the near player's skeleton over the video
  it was measured on (Android, on-device analysis). Run an analysis with
  "Skeleton playback" ticked, open the match from Analytics, and switch between
  Heatmap and Skeleton. Step frame by frame to the moment of contact; the
  joints follow the frame exactly, and a frame where the player was not found
  shows no skeleton rather than a guess.
```

`AnalysisPlan.kt:20-22`, the `SKELETON_PLAYBACK` KDoc, becomes:

```kotlin
    /** Keep every joint of the near player for overlay playback. Needs pose, and about 11MB per 30 minutes of video. */
```

The design doc's status line: `**Status:** Implemented 2026-09-<dd> on Android; see the Android screenshots in docs/screenshots/android-analytics-skeleton-*.png`.

- [ ] **Step 5: Full suites and commit**

Run: `./gradlew :analysis:jvmTest :shared:jvmTest :androidApp:testDebugUnitTest :androidApp:compileDebugAndroidTestKotlin -q`
Expected: PASS.

```bash
git add docs/screenshots CHANGELOG.md docs/plans/2026-09-07-skeleton-view-design.md shared/src/commonMain/kotlin/com/badmintontracker/shared/local/AnalysisPlan.kt
git commit -m "docs: the skeleton view on the emulator, and the metric says what it keeps"
```

---

## Done criteria

- `./gradlew :analysis:jvmTest :shared:jvmTest :androidApp:testDebugUnitTest` passes with the new tests: selection parity, the lookup, the coordinator's poses, the store.
- `VideoFrameSourceTest` and `Phase2EndToEndTest` pass on the arm64 emulator, the latter asserting container timestamps.
- A run with "Skeleton playback" ticked writes `skeletons/<id>.skel`; one without does not.
- The Analytics detail offers Heatmap and Skeleton only when a skeleton is stored, and the skeleton stays on the body through play, pause and frame stepping.
- Six screenshots in `docs/screenshots/`, and the changelog entry.

## What comes next, and why it is not in this plan

- **Skeletons over rally clips**, which need the clip's start offset applied to the stored timestamps (`ClipCutter.kt:129-130` rebases them).
- **Marking-time court validation**, owed from the heatmap accuracy work: refuse marks whose fit residual exceeds the selector's gate before an hour of inference is spent on them.
- **iOS**, downstream of the on-device pipeline's Stage 2.
