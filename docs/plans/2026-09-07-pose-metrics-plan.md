# Pose Metrics Strip Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Under the skeleton video on the Analytics detail, show per-frame body measurements (stance width and position on the court in metres; elbow, arm, knee and trunk-lean angles as seen by the camera), an arc on the overlay for the selected angle, and a graph of the selected measurement around the playhead.

**Architecture:** The pure `:analysis` layer gains `poseMetrics()`, which turns one frame's COCO-17 joints plus the court homography into a `PoseMetrics` value, and a `MetricKind` enumeration the UI selects from. `SkeletonStore` moves to a v2 file that carries the twelve court marks, so the strip depends on nothing but the file (v1 files still open, without the court-plane tiles). A small `:shared` preference remembers which arm holds the racket per video. On Android, `MetricsStrip` shows the tiles, `MetricGraph` draws the selected metric over ±2 s, and `SkeletonOverlay` draws the selected angle's arc. The near-player selector also gains a scale gate so a broadcast close-up cannot pass as an on-court player, which the research measured polluting the heatmap.

**Tech Stack:** Kotlin Multiplatform 2.3.20 for `:analysis` and `:shared` (kotlin.test, kotest assertions, multiplatform-settings), Jetpack Compose with Material 3 and Media3 ExoPlayer in `androidApp`, JUnit 4 for Android unit tests, an arm64 emulator (API 36, 4 GB) for on-device verification.

**Spec:** `docs/plans/2026-09-07-pose-metrics-research.md` (§7 is the UI, §3 to §5 the measurements, §8 the gate).

## Global Constraints

- Bare `§N` refers to the spec above.
- **Never use the em dash character in prose.** Use a plain dash. Applies to code comments, commit messages and docs. The en dash "–" is allowed as the "absent value" glyph in the strip only.
- **No agent attribution in commits.** No `Co-Authored-By` trailer, no "Generated with" footer.
- Android only for UI. Nothing in `iosApp` changes. Anything placed in `:analysis` or `:shared` stays platform-neutral; a Swift caller is not required to use the new preference.
- Keypoints are in source-video pixels everywhere below the platform layer, COCO-17 order, as `PoseRunner` emits them. `NearPlayerSelector.MIN_KEYPOINT_CONFIDENCE` (0.5) is the one confidence threshold; a measurement built on a joint below it is absent, never estimated.
- Court coordinates: x across the court 0 to 6.1 m, y along it 0 to 13.4 m with the camera side at large y (`bottomLeft` maps to `(0, 13.4)`); the net at y 6.7; service lines at 6.7 ± 1.98. The near player is on the camera half, y > 6.7.
- Angles are the image-plane angle at the vertex joint in degrees: 180 is a straight limb. Trunk lean is signed from vertical, positive toward the frame's right. Copy in the UI says "as seen by the camera" once; no tile claims a 3D angle.
- Displayed values are raw per frame. No temporal smoothing anywhere (§4).
- Run every gradle command from the repo root. JVM suites: `./gradlew :analysis:jvmTest :shared:jvmTest :androidApp:testDebugUnitTest`. The instrumented suite must still compile: `./gradlew :androidApp:compileDebugAndroidTestKotlin`.
- The emulator `emulator-5554` (Pixel_9a, API 36, 4 GB) is running and signed in; `tools/adb-tap.sh` for taps; never tap "Sign out". The corpus video 743d7fb1 is already imported and marked on it.
- Branch: `pose-metrics`, from `main` (4894d0a).

## Preconditions

- [ ] `git checkout -b pose-metrics main`
- [ ] `./gradlew :analysis:jvmTest :shared:jvmTest :androidApp:testDebugUnitTest` is green before the first task (154, 451 and 241 tests on 2026-09-07).

## File Structure

| File | Responsibility |
| --- | --- |
| `analysis/src/commonMain/.../player/NearPlayer.kt` | `Coco` gains the upper-body and knee indices; `RejectionReason.WRONG_SCALE`; the torso scale gate. |
| `analysis/src/commonMain/.../player/PoseMetrics.kt` | `Side`, `PoseMetrics`, `MetricKind`, `jointAngleDeg`, `poseMetrics`. |
| `analysis/src/commonMain/.../geometry/Homography.kt` | `Matrix3x3.metresPerPixelAt`. |
| `analysis/src/commonTest/.../player/PoseMetricsTest.kt` | Angles, court-plane metrics on corpus marks, absence rules, `MetricKind.of`. |
| `analysis/src/commonTest/.../player/NearPlayerSelectorTest.kt` | The scale gate. |
| `analysis/src/commonTest/.../geometry/HomographyTest.kt` | `metresPerPixelAt` against the corpus marks. |
| `shared/src/commonMain/.../prefs/RacketArmPreferenceRepository.kt` | `RacketArm`, per-video racket arm. |
| `shared/src/commonTest/.../prefs/RacketArmPreferenceRepositoryTest.kt` | Default, persistence, clearing, garbage. |
| `shared/src/commonMain/.../RallyApp.kt` | Constructs the repository. |
| `androidApp/.../localanalysis/SkeletonStore.kt` | v2 header with marks; v1 still loads. |
| `androidApp/src/test/.../localanalysis/SkeletonStoreTest.kt` | v2 round trip, no-marks, v1 compatibility. |
| `androidApp/.../localanalysis/LocalAnalysisRunner.kt` | Passes the marks to the store. |
| `androidApp/.../localanalysis/MetricsFormat.kt` | `formatMetric`, `metricLabel`, `visibleKinds`. |
| `androidApp/src/test/.../localanalysis/MetricsFormatTest.kt` | Formatting and visibility rules. |
| `androidApp/.../localanalysis/MetricsStrip.kt` | The tiles, the caption, the racket-arm control. |
| `androidApp/.../localanalysis/MetricGraph.kt` | The ±2 s graph with seek on tap and drag. |
| `androidApp/.../localanalysis/SkeletonOverlay.kt` | `PoseHighlight`: the arc, the ankle line. |
| `androidApp/.../localanalysis/SkeletonPanel.kt` | Series precompute, the strip, the graph, the selection, the racket arm. |
| `androidApp/.../analytics/AnalyticsDetailScreen.kt`, `androidApp/.../AuthGate.kt` | Pass the racket-arm repository through. |
| `CHANGELOG.md` | Added entry. |
| `docs/screenshots/android-analytics-metrics-{light,dark}.png` | On-emulator proof. |

---

## Task 1: `poseMetrics` in `:analysis`

**Files:**
- Modify: `analysis/src/commonMain/kotlin/com/badmintontracker/analysis/player/NearPlayer.kt:12-19` (the `Coco` object only)
- Create: `analysis/src/commonMain/kotlin/com/badmintontracker/analysis/player/PoseMetrics.kt`
- Test: `analysis/src/commonTest/kotlin/com/badmintontracker/analysis/player/PoseMetricsTest.kt`

**Interfaces:**
- Consumes: `Point`, `Matrix3x3`, `Matrix3x3.apply`, `Court`, `CourtKeypoints.homography()` (all existing in `:analysis`).
- Produces: `Side`, `PoseMetrics` (ten nullable fields plus `NONE`), `MetricKind` (nine kinds with `side`, `range`, `isAngle`, `angleJoints`, `of(metrics)`), `jointAngleDeg(a, vertex, c): Double?`, `poseMetrics(keypoints, confidence, homography, minConfidence): PoseMetrics`. Later tasks use these names exactly.

- [ ] **Step 1: Extend `Coco`**

Replace the `Coco` object with:

```kotlin
/** COCO-17 indices, the layout every YOLO pose model emits. */
object Coco {
    const val NOSE = 0
    const val LEFT_SHOULDER = 5
    const val RIGHT_SHOULDER = 6
    const val LEFT_ELBOW = 7
    const val RIGHT_ELBOW = 8
    const val LEFT_WRIST = 9
    const val RIGHT_WRIST = 10
    const val LEFT_HIP = 11
    const val RIGHT_HIP = 12
    const val LEFT_KNEE = 13
    const val RIGHT_KNEE = 14
    const val LEFT_ANKLE = 15
    const val RIGHT_ANKLE = 16
    const val COUNT = 17
}
```

- [ ] **Step 2: Write the failing tests**

```kotlin
package com.badmintontracker.analysis.player

import com.badmintontracker.analysis.geometry.CourtKeypoints
import com.badmintontracker.analysis.geometry.Point
import com.badmintontracker.analysis.geometry.homography
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PoseMetricsTest {

    // 743d7fb1's marks, as NearPlayerSelectorTest uses them.
    private val marks = CourtKeypoints(
        topLeft = Point(649.5, 484.8), topRight = Point(1277.3, 481.2),
        bottomRight = Point(1579.4, 998.6), bottomLeft = Point(360.0, 1004.0),
        netLeft = Point(550.0, 663.9), netRight = Point(1382.2, 665.7),
        serviceLineNearLeft = Point(504.8, 743.5), serviceLineNearRight = Point(1422.0, 738.1),
        serviceLineFarLeft = Point(588.0, 595.2), serviceLineFarRight = Point(1338.8, 595.2),
        centerNear = Point(966.1, 593.4), centerFar = Point(966.1, 736.3),
    )
    private val h = marks.homography()!!

    private fun near(expected: Double, actual: Double?, tolerance: Double, what: String) {
        assertNotNull(actual, "$what is absent")
        assertTrue(abs(expected - actual) <= tolerance, "$what: expected $expected, got $actual")
    }

    /** All joints confident at [where]; callers overwrite the ones under test. */
    private fun figure(where: Point = Point(900.0, 800.0)): Pair<MutableList<Point>, MutableList<Float>> =
        MutableList(Coco.COUNT) { where } to MutableList(Coco.COUNT) { 0.9f }

    @Test
    fun a_right_angle_is_ninety_degrees_whichever_way_it_opens() {
        near(90.0, jointAngleDeg(Point(0.0, 0.0), Point(0.0, 1.0), Point(1.0, 1.0)), 1e-9, "angle")
        near(90.0, jointAngleDeg(Point(1.0, 1.0), Point(0.0, 1.0), Point(0.0, 0.0)), 1e-9, "angle")
    }

    @Test
    fun a_straight_limb_is_one_hundred_eighty_and_a_folded_one_is_zero() {
        near(180.0, jointAngleDeg(Point(0.0, 0.0), Point(1.0, 0.0), Point(2.0, 0.0)), 1e-9, "straight")
        near(0.0, jointAngleDeg(Point(0.0, 0.0), Point(1.0, 0.0), Point(0.0, 0.0)), 1e-9, "folded")
    }

    @Test
    fun a_joint_on_top_of_its_neighbour_has_no_angle() {
        assertNull(jointAngleDeg(Point(1.0, 1.0), Point(1.0, 1.0), Point(2.0, 2.0)))
    }

    @Test
    fun an_unconfident_joint_removes_only_the_angles_that_use_it() {
        val (kps, conf) = figure()
        kps[Coco.LEFT_SHOULDER] = Point(0.0, 0.0)
        kps[Coco.LEFT_ELBOW] = Point(0.0, 10.0)
        kps[Coco.LEFT_WRIST] = Point(10.0, 10.0)
        kps[Coco.RIGHT_SHOULDER] = Point(100.0, 0.0)
        kps[Coco.RIGHT_ELBOW] = Point(100.0, 10.0)
        kps[Coco.RIGHT_WRIST] = Point(100.0, 20.0)
        conf[Coco.LEFT_WRIST] = 0.3f
        val m = poseMetrics(kps, conf, homography = null)
        assertNull(m.elbowLeftDeg, "left elbow needs the left wrist")
        near(180.0, m.elbowRightDeg, 1e-9, "right elbow")
    }

    @Test
    fun without_a_homography_the_court_plane_metrics_are_absent_and_the_angles_are_not() {
        val (kps, conf) = figure()
        kps[Coco.LEFT_HIP] = Point(0.0, 0.0)
        kps[Coco.LEFT_KNEE] = Point(0.0, 10.0)
        kps[Coco.LEFT_ANKLE] = Point(0.0, 20.0)
        val m = poseMetrics(kps, conf, homography = null)
        assertNull(m.stanceM)
        assertNull(m.behindServiceLineM)
        assertNull(m.fromCentreLineM)
        near(180.0, m.kneeLeftDeg, 1e-9, "knee")
    }

    @Test
    fun ankles_on_the_near_corners_stand_a_court_width_apart_a_court_length_behind_the_net() {
        val (kps, conf) = figure()
        kps[Coco.LEFT_ANKLE] = marks.bottomLeft
        kps[Coco.RIGHT_ANKLE] = marks.bottomRight
        val m = poseMetrics(kps, conf, h)
        // The corners fit to well under the 0.37m worst-point residual.
        near(6.1, m.stanceM, 0.2, "stance")
        near(13.4 - 8.68, m.behindServiceLineM, 0.2, "behind the service line")
        near(0.0, m.fromCentreLineM, 0.2, "from the centre line")
    }

    @Test
    fun ankles_on_the_far_half_have_a_stance_but_no_service_line_distance() {
        val (kps, conf) = figure()
        kps[Coco.LEFT_ANKLE] = marks.topLeft
        kps[Coco.RIGHT_ANKLE] = marks.topRight
        val m = poseMetrics(kps, conf, h)
        near(6.1, m.stanceM, 0.2, "stance")
        assertNull(m.behindServiceLineM, "the service-line distance is the near player's")
        assertNull(m.fromCentreLineM)
    }

    @Test
    fun an_unconfident_ankle_removes_the_court_plane_metrics() {
        val (kps, conf) = figure()
        kps[Coco.LEFT_ANKLE] = marks.bottomLeft
        kps[Coco.RIGHT_ANKLE] = marks.bottomRight
        conf[Coco.RIGHT_ANKLE] = 0.1f
        val m = poseMetrics(kps, conf, h)
        assertNull(m.stanceM)
        assertNull(m.behindServiceLineM)
    }

    @Test
    fun trunk_lean_is_zero_upright_and_positive_toward_the_frames_right() {
        val (kps, conf) = figure()
        kps[Coco.LEFT_HIP] = Point(90.0, 100.0)
        kps[Coco.RIGHT_HIP] = Point(110.0, 100.0)
        kps[Coco.LEFT_SHOULDER] = Point(90.0, 0.0)
        kps[Coco.RIGHT_SHOULDER] = Point(110.0, 0.0)
        near(0.0, poseMetrics(kps, conf, null).trunkLeanDeg, 1e-9, "upright")
        kps[Coco.LEFT_SHOULDER] = Point(190.0, 0.0)
        kps[Coco.RIGHT_SHOULDER] = Point(210.0, 0.0)
        near(45.0, poseMetrics(kps, conf, null).trunkLeanDeg, 1e-9, "leaning right")
    }

    @Test
    fun arm_angle_is_zero_hanging_and_one_eighty_straight_up() {
        val (kps, conf) = figure()
        kps[Coco.LEFT_HIP] = Point(0.0, 100.0)
        kps[Coco.LEFT_SHOULDER] = Point(0.0, 0.0)
        kps[Coco.LEFT_ELBOW] = Point(0.0, 50.0)
        near(0.0, poseMetrics(kps, conf, null).armLeftDeg, 1e-9, "hanging")
        kps[Coco.LEFT_ELBOW] = Point(0.0, -50.0)
        near(180.0, poseMetrics(kps, conf, null).armLeftDeg, 1e-9, "raised")
    }

    @Test
    fun a_short_keypoint_list_measures_nothing() {
        val m = poseMetrics(List(5) { Point(0.0, 0.0) }, List(5) { 0.9f }, h)
        assertEquals(PoseMetrics.NONE, m)
    }

    @Test
    fun every_kind_reads_its_own_field() {
        val m = PoseMetrics(
            stanceM = 1.0, behindServiceLineM = 2.0, fromCentreLineM = 3.0,
            elbowLeftDeg = 4.0, elbowRightDeg = 5.0, armLeftDeg = 6.0, armRightDeg = 7.0,
            kneeLeftDeg = 8.0, kneeRightDeg = 9.0, trunkLeanDeg = 10.0,
        )
        val seen = MetricKind.entries.map { it.of(m) }
        assertEquals(seen.size, seen.toSet().size, "two kinds read the same field: $seen")
        assertEquals(1.0, MetricKind.STANCE.of(m))
        assertEquals(2.0, MetricKind.BEHIND_LINE.of(m))
        assertEquals(5.0, MetricKind.ELBOW_RIGHT.of(m))
        assertEquals(10.0, MetricKind.LEAN.of(m))
    }

    @Test
    fun angle_kinds_name_the_vertex_between_the_right_neighbours() {
        assertEquals(Triple(Coco.LEFT_SHOULDER, Coco.LEFT_ELBOW, Coco.LEFT_WRIST), MetricKind.ELBOW_LEFT.angleJoints)
        assertEquals(Triple(Coco.RIGHT_ELBOW, Coco.RIGHT_SHOULDER, Coco.RIGHT_HIP), MetricKind.ARM_RIGHT.angleJoints)
        assertEquals(Triple(Coco.RIGHT_HIP, Coco.RIGHT_KNEE, Coco.RIGHT_ANKLE), MetricKind.KNEE_RIGHT.angleJoints)
        assertNull(MetricKind.STANCE.angleJoints)
        assertNull(MetricKind.LEAN.angleJoints)
        assertTrue(MetricKind.entries.filter { it.isAngle }.all { it.range == 0.0..180.0 || it == MetricKind.LEAN })
    }
}
```

- [ ] **Step 3: Run the tests to see them fail**

Run: `./gradlew :analysis:jvmTest --tests '*PoseMetricsTest*'`
Expected: compilation failure, `PoseMetrics` unresolved.

- [ ] **Step 4: Implement `PoseMetrics.kt`**

```kotlin
package com.badmintontracker.analysis.player

import com.badmintontracker.analysis.geometry.Court
import com.badmintontracker.analysis.geometry.Matrix3x3
import com.badmintontracker.analysis.geometry.Point
import com.badmintontracker.analysis.geometry.apply
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.hypot

enum class Side { LEFT, RIGHT }

/**
 * What one frame of the near player's joints measures, or does not.
 *
 * Every field is nullable and null means "not measurable in this frame": a
 * joint under the confidence threshold, no court homography, ankles that do
 * not project. A number is never estimated from a joint the model was unsure
 * of, because a coach reading "174°" cannot tell a guess from a measurement.
 *
 * The court-plane fields are in metres and rest on the same footing as the
 * heatmap (ankles on the floor, the homography maps the floor). The angles are
 * image-plane angles: the true angle projected onto the camera, exact when
 * the joint's plane faces the camera and increasingly wrong as it turns
 * edge-on. See the 2026-09-07 research, §3 and §4.
 */
data class PoseMetrics(
    /** Distance between the two ankles on the court, metres. */
    val stanceM: Double?,
    /**
     * Ankle midpoint's distance behind the near service line, metres, positive
     * toward the baseline. Null on the far half: it is the near player's number.
     */
    val behindServiceLineM: Double?,
    /** Ankle midpoint's signed distance from the centre line, metres, positive to the frame's right. */
    val fromCentreLineM: Double?,
    /** Angle at the elbow between shoulder and wrist; 180 is a straight arm. */
    val elbowLeftDeg: Double?,
    val elbowRightDeg: Double?,
    /** Angle at the shoulder between elbow and hip; 0 is an arm hanging along the torso. */
    val armLeftDeg: Double?,
    val armRightDeg: Double?,
    /** Angle at the knee between hip and ankle; 180 is a straight leg. */
    val kneeLeftDeg: Double?,
    val kneeRightDeg: Double?,
    /** Hip midpoint to shoulder midpoint, degrees from vertical, positive toward the frame's right. */
    val trunkLeanDeg: Double?,
) {
    companion object {
        val NONE = PoseMetrics(null, null, null, null, null, null, null, null, null, null)
    }
}

/**
 * The measurements a coach can pick, one per tile.
 *
 * [range] is the graph's fixed vertical extent, fixed so a curve keeps its
 * shape from frame to frame instead of rescaling under the eye. [angleJoints]
 * is (a, vertex, c) for the overlay's arc, null for the kinds with no arc.
 */
enum class MetricKind(
    val side: Side?,
    val range: ClosedFloatingPointRange<Double>,
    val angleJoints: Triple<Int, Int, Int>?,
) {
    STANCE(null, 0.0..2.0, null),
    BEHIND_LINE(null, -1.0..6.0, null),
    ELBOW_LEFT(Side.LEFT, 0.0..180.0, Triple(Coco.LEFT_SHOULDER, Coco.LEFT_ELBOW, Coco.LEFT_WRIST)),
    ELBOW_RIGHT(Side.RIGHT, 0.0..180.0, Triple(Coco.RIGHT_SHOULDER, Coco.RIGHT_ELBOW, Coco.RIGHT_WRIST)),
    ARM_LEFT(Side.LEFT, 0.0..180.0, Triple(Coco.LEFT_ELBOW, Coco.LEFT_SHOULDER, Coco.LEFT_HIP)),
    ARM_RIGHT(Side.RIGHT, 0.0..180.0, Triple(Coco.RIGHT_ELBOW, Coco.RIGHT_SHOULDER, Coco.RIGHT_HIP)),
    KNEE_LEFT(Side.LEFT, 0.0..180.0, Triple(Coco.LEFT_HIP, Coco.LEFT_KNEE, Coco.LEFT_ANKLE)),
    KNEE_RIGHT(Side.RIGHT, 0.0..180.0, Triple(Coco.RIGHT_HIP, Coco.RIGHT_KNEE, Coco.RIGHT_ANKLE)),
    LEAN(null, -45.0..45.0, null),
    ;

    /** Angles, including the lean; the rest are metres. */
    val isAngle: Boolean get() = this != STANCE && this != BEHIND_LINE

    /** Whether this kind needs the court homography. */
    val needsCourt: Boolean get() = this == STANCE || this == BEHIND_LINE

    fun of(m: PoseMetrics): Double? = when (this) {
        STANCE -> m.stanceM
        BEHIND_LINE -> m.behindServiceLineM
        ELBOW_LEFT -> m.elbowLeftDeg
        ELBOW_RIGHT -> m.elbowRightDeg
        ARM_LEFT -> m.armLeftDeg
        ARM_RIGHT -> m.armRightDeg
        KNEE_LEFT -> m.kneeLeftDeg
        KNEE_RIGHT -> m.kneeRightDeg
        LEAN -> m.trunkLeanDeg
    }
}

/** The image-plane angle at [vertex] between [a] and [c], degrees in 0..180; null when a limb has no length. */
fun jointAngleDeg(a: Point, vertex: Point, c: Point): Double? {
    val ux = a.x - vertex.x
    val uy = a.y - vertex.y
    val vx = c.x - vertex.x
    val vy = c.y - vertex.y
    val lu = hypot(ux, uy)
    val lv = hypot(vx, vy)
    if (lu == 0.0 || lv == 0.0) return null
    val cos = ((ux * vx + uy * vy) / (lu * lv)).coerceIn(-1.0, 1.0)
    return acos(cos) * 180.0 / PI
}

/**
 * Measures one frame. [homography] is the resolved court fit
 * (`CourtKeypoints.homography()`), or null when the court is not known, in
 * which case only the angles are produced.
 */
fun poseMetrics(
    keypoints: List<Point>,
    confidence: List<Float>,
    homography: Matrix3x3?,
    minConfidence: Float = NearPlayerSelector.MIN_KEYPOINT_CONFIDENCE,
): PoseMetrics {
    if (keypoints.size < Coco.COUNT || confidence.size < Coco.COUNT) return PoseMetrics.NONE
    fun ok(vararg idx: Int) = idx.all { confidence[it] >= minConfidence }
    fun angle(a: Int, vertex: Int, c: Int): Double? =
        if (ok(a, vertex, c)) jointAngleDeg(keypoints[a], keypoints[vertex], keypoints[c]) else null

    var stance: Double? = null
    var behind: Double? = null
    var fromCentre: Double? = null
    if (homography != null && ok(Coco.LEFT_ANKLE, Coco.RIGHT_ANKLE)) {
        val l = keypoints[Coco.LEFT_ANKLE].let { homography.apply(it.x, it.y) }
        val r = keypoints[Coco.RIGHT_ANKLE].let { homography.apply(it.x, it.y) }
        if (l != null && r != null) {
            stance = hypot(l.x - r.x, l.y - r.y)
            val midX = (l.x + r.x) / 2.0
            val midY = (l.y + r.y) / 2.0
            if (midY > Court.LENGTH / 2.0) {
                behind = midY - (Court.LENGTH / 2.0 + Court.SERVICE_LINE)
                fromCentre = midX - Court.WIDTH_DOUBLES / 2.0
            }
        }
    }

    var lean: Double? = null
    if (ok(Coco.LEFT_SHOULDER, Coco.RIGHT_SHOULDER, Coco.LEFT_HIP, Coco.RIGHT_HIP)) {
        val sx = (keypoints[Coco.LEFT_SHOULDER].x + keypoints[Coco.RIGHT_SHOULDER].x) / 2.0
        val sy = (keypoints[Coco.LEFT_SHOULDER].y + keypoints[Coco.RIGHT_SHOULDER].y) / 2.0
        val hx = (keypoints[Coco.LEFT_HIP].x + keypoints[Coco.RIGHT_HIP].x) / 2.0
        val hy = (keypoints[Coco.LEFT_HIP].y + keypoints[Coco.RIGHT_HIP].y) / 2.0
        // Image y grows downward, so "up" is hy - sy.
        if (sx != hx || sy != hy) lean = atan2(sx - hx, hy - sy) * 180.0 / PI
    }

    return PoseMetrics(
        stanceM = stance,
        behindServiceLineM = behind,
        fromCentreLineM = fromCentre,
        elbowLeftDeg = angle(Coco.LEFT_SHOULDER, Coco.LEFT_ELBOW, Coco.LEFT_WRIST),
        elbowRightDeg = angle(Coco.RIGHT_SHOULDER, Coco.RIGHT_ELBOW, Coco.RIGHT_WRIST),
        armLeftDeg = angle(Coco.LEFT_ELBOW, Coco.LEFT_SHOULDER, Coco.LEFT_HIP),
        armRightDeg = angle(Coco.RIGHT_ELBOW, Coco.RIGHT_SHOULDER, Coco.RIGHT_HIP),
        kneeLeftDeg = angle(Coco.LEFT_HIP, Coco.LEFT_KNEE, Coco.LEFT_ANKLE),
        kneeRightDeg = angle(Coco.RIGHT_HIP, Coco.RIGHT_KNEE, Coco.RIGHT_ANKLE),
        trunkLeanDeg = lean,
    )
}
```

- [ ] **Step 5: Run the tests**

Run: `./gradlew :analysis:jvmTest`
Expected: all green, including the pre-existing 154.

- [ ] **Step 6: Commit**

```bash
git add analysis/src/commonMain/kotlin/com/badmintontracker/analysis/player/NearPlayer.kt analysis/src/commonMain/kotlin/com/badmintontracker/analysis/player/PoseMetrics.kt analysis/src/commonTest/kotlin/com/badmintontracker/analysis/player/PoseMetricsTest.kt
git commit -m "feat(analysis): measure stance, court position and image-plane joint angles from one pose"
```

---

## Task 2: The scale gate in `NearPlayerSelector`

**Files:**
- Modify: `analysis/src/commonMain/kotlin/com/badmintontracker/analysis/geometry/Homography.kt` (add one function after `apply`)
- Modify: `analysis/src/commonMain/kotlin/com/badmintontracker/analysis/player/NearPlayer.kt` (`RejectionReason`, `select`, a new private `plausibleScale`, a new companion constant)
- Test: `analysis/src/commonTest/kotlin/com/badmintontracker/analysis/geometry/HomographyTest.kt`, `analysis/src/commonTest/kotlin/com/badmintontracker/analysis/player/NearPlayerSelectorTest.kt`

**Interfaces:**
- Consumes: `Coco` from Task 1.
- Produces: `Matrix3x3.metresPerPixelAt(p: Point, stepPx: Double = 4.0): Double?`; `RejectionReason.WRONG_SCALE`; `NearPlayerSelector.MAX_TORSO_M = 0.9`.

Why (§3, §8): the selector accepts any detection whose ankle midpoint lands on the court, at any pixel scale. Broadcast close-ups from another camera pass, and their torsos measure 0.8 to 1.4 m on the court scale, against a never-exceeded 0.8 m for every real frame on both corpus videos (upright median 0.54 m on 743d7fb1, 0.30 m on the steeply filmed 2eabfc01). A torso is only ever foreshortened by lean or tilt, never lengthened, so an upper bound is the whole gate. What it does not catch is a figure at a plausible scale that is not a player (2eabfc01's animated intro reads 0.8 to 0.95 m); that stays a known hole.

- [ ] **Step 1: Write the failing tests**

Add to `HomographyTest`:

```kotlin
    @Test
    fun metres_per_pixel_shrinks_with_distance_from_the_camera() {
        val h = corpus743d7fb1.homography()!!
        // Near baseline centre and far baseline centre, in pixels.
        val near = h.metresPerPixelAt(Point(970.0, 1000.0))!!
        val far = h.metresPerPixelAt(Point(963.0, 483.0))!!
        // 6.1m across 1219px at the near baseline, across 628px at the far one.
        if (near < 0.0045 || near > 0.0055) throw AssertionError("near scale $near m/px")
        if (far < 0.0090 || far > 0.0105) throw AssertionError("far scale $far m/px")
    }
```

Add to `NearPlayerSelectorTest` (the fixture's `person()` puts shoulders 100 px and hips 90 px above the ankles, a 10 px torso, so the existing tests are unaffected):

```kotlin
    private fun withTorsoPx(base: PosePerson, torsoPx: Double, shoulderConfidence: Float = 0.9f): PosePerson {
        val kps = base.keypoints.toMutableList()
        val conf = base.keypointConfidence.toMutableList()
        val hipY = kps[Coco.LEFT_HIP].y
        kps[Coco.LEFT_SHOULDER] = Point(kps[Coco.LEFT_HIP].x, hipY - torsoPx)
        kps[Coco.RIGHT_SHOULDER] = Point(kps[Coco.RIGHT_HIP].x, hipY - torsoPx)
        conf[Coco.LEFT_SHOULDER] = shoulderConfidence
        conf[Coco.RIGHT_SHOULDER] = shoulderConfidence
        return PosePerson(base.boxConfidence, kps, conf)
    }

    @Test
    fun a_torso_of_ordinary_length_passes_the_scale_gate() {
        // 0.5m at the near baseline, where 6.1m is 1219px: about 100px.
        val result = selector().select(PoseFrame(0, listOf(withTorsoPx(person(970.0, 990.0), 100.0))))
        assertNotNull(result.sample)
        assertNull(result.rejection)
    }

    @Test
    fun a_torso_that_measures_over_a_metre_is_a_close_up_not_a_player() {
        // 250px at the near baseline is 1.25m: a broadcast close-up from another camera.
        val result = selector().select(PoseFrame(0, listOf(withTorsoPx(person(970.0, 990.0), 250.0))))
        assertNull(result.sample)
        assertEquals(RejectionReason.WRONG_SCALE, result.rejection)
    }

    @Test
    fun unconfident_shoulders_do_not_trigger_the_scale_gate() {
        val result = selector().select(
            PoseFrame(0, listOf(withTorsoPx(person(970.0, 990.0), 250.0, shoulderConfidence = 0.2f))),
        )
        assertNotNull(result.sample, "a torso that cannot be measured is not a reason to reject")
    }
```

Check the file's existing `PoseFrame` construction and match it (the frame index argument and the `people` list); if the fixture names differ, use the fixture's.

- [ ] **Step 2: Run the tests to see them fail**

Run: `./gradlew :analysis:jvmTest --tests '*HomographyTest*' --tests '*NearPlayerSelectorTest*'`
Expected: compilation failure on `metresPerPixelAt` and `WRONG_SCALE`.

- [ ] **Step 3: Implement**

In `Homography.kt`, after `apply`:

```kotlin
/**
 * Court metres per source pixel along the image x axis at [p], by finite
 * difference; null when either side of [p] projects to infinity.
 *
 * The horizontal scale on the floor at a point. It is the scale of anything
 * at the same depth that lies across the image, which a shoulder line and,
 * up to foreshortening, a torso do. It is not a vertical scale: a camera
 * tilted down sees vertical lengths shorter by the cosine of the tilt.
 */
fun Matrix3x3.metresPerPixelAt(p: Point, stepPx: Double = 4.0): Double? {
    val a = apply(p.x - stepPx, p.y) ?: return null
    val b = apply(p.x + stepPx, p.y) ?: return null
    return sqrt((b.x - a.x) * (b.x - a.x) + (b.y - a.y) * (b.y - a.y)) / (2 * stepPx)
}
```

In `NearPlayer.kt`, add `WRONG_SCALE` to `RejectionReason` between `OFF_COURT` and `BAD_COURT`, with a KDoc line: "On the court, but at a pixel scale no player at that depth can have: a close-up from another camera. See [NearPlayerSelector.MAX_TORSO_M]." (`worse()` ranks by ordinal, so the position matters: it is the gate after `OFF_COURT`.)

In `select`, after the `onCourt` check and before the confidence comparison:

```kotlin
            if (!plausibleScale(person, ground)) {
                reason = worse(reason, RejectionReason.WRONG_SCALE)
                continue
            }
```

Add the private function and the constant:

```kotlin
    /**
     * Whether the person is the size a person at their ground point would be.
     *
     * The torso (shoulder midpoint to hip midpoint) in pixels, times the
     * court's metres per pixel at the ankles. Measured on the two corpus
     * videos (2026-09-07): no real frame exceeded 0.8m; broadcast close-ups
     * from another camera, which the marks do not describe, read 0.8 to
     * 1.4m and were being drawn onto the heatmap as on-court positions. A
     * torso is only ever foreshortened by lean or camera tilt, so a single
     * upper bound is the whole test. Shoulders or hips below the confidence
     * threshold, or a scale the homography cannot give, pass: an unmeasurable
     * torso is not evidence.
     */
    private fun plausibleScale(person: PosePerson, ground: Point): Boolean {
        val c = person.keypointConfidence
        val needed = listOf(Coco.LEFT_SHOULDER, Coco.RIGHT_SHOULDER, Coco.LEFT_HIP, Coco.RIGHT_HIP)
        if (needed.any { c[it] < minKeypointConfidence }) return true
        val h = homography ?: return true
        val metresPerPixel = h.metresPerPixelAt(ground) ?: return true
        val k = person.keypoints
        val sx = (k[Coco.LEFT_SHOULDER].x + k[Coco.RIGHT_SHOULDER].x) / 2.0
        val sy = (k[Coco.LEFT_SHOULDER].y + k[Coco.RIGHT_SHOULDER].y) / 2.0
        val hx = (k[Coco.LEFT_HIP].x + k[Coco.RIGHT_HIP].x) / 2.0
        val hy = (k[Coco.LEFT_HIP].y + k[Coco.RIGHT_HIP].y) / 2.0
        val torsoM = sqrt((sx - hx) * (sx - hx) + (sy - hy) * (sy - hy)) * metresPerPixel
        return torsoM <= MAX_TORSO_M
    }
```

and in the companion:

```kotlin
        /** The longest a torso can measure on the court scale and still be a player at that depth, metres. */
        const val MAX_TORSO_M = 0.9
```

Add the imports (`metresPerPixelAt`, `kotlin.math.sqrt`).

- [ ] **Step 4: Run the whole analysis suite**

Run: `./gradlew :analysis:jvmTest`
Expected: green. `DevicePoseDumpTest` runs real device output through the selector; if it now rejects a frame it previously kept, inspect that frame's torso in court metres before touching the bound, and report it in the task report either way.

- [ ] **Step 5: Commit**

```bash
git add analysis/src/commonMain/kotlin/com/badmintontracker/analysis/geometry/Homography.kt analysis/src/commonMain/kotlin/com/badmintontracker/analysis/player/NearPlayer.kt analysis/src/commonTest/kotlin/com/badmintontracker/analysis/geometry/HomographyTest.kt analysis/src/commonTest/kotlin/com/badmintontracker/analysis/player/NearPlayerSelectorTest.kt
git commit -m "fix(analysis): refuse a near player whose torso is longer than a metre on the court scale"
```

---

## Task 3: `SkeletonStore` v2 carries the court marks

**Files:**
- Modify: `androidApp/src/main/java/com/badmintontracker/android/localanalysis/SkeletonStore.kt`
- Modify: `androidApp/src/main/java/com/badmintontracker/android/localanalysis/LocalAnalysisRunner.kt:210-212`
- Test: `androidApp/src/test/java/com/badmintontracker/android/localanalysis/SkeletonStoreTest.kt`

**Interfaces:**
- Consumes: `com.badmintontracker.analysis.geometry.CourtKeypoints` (analysis type), `CourtKeypoints.pixels()`, `com.badmintontracker.shared.model.toAnalysis()` (shared to analysis bridge).
- Produces: `Stored.marks: CourtKeypoints?`; `save(entryId, poses, fps, videoWidth, videoHeight, marks: CourtKeypoints?)`.

File layout v2, little-endian:

```
magic "SKEL", version i32 = 2, fps f64, videoWidth i32, videoHeight i32,
hasMarks i32 (1 or 0), 12 x (x f64, y f64) in CourtKeypoints.pixels() order (zeros when absent),
poseCount i32, then poses as in v1.
```

Header bytes v2: 4 + 4 + 8 + 4 + 4 + 4 + 192 + 4 = 224. v1 header stays 28 and is still read; `has()` answers true for version 1 or 2. Save always writes v2.

- [ ] **Step 1: Write the failing tests**

Add to `SkeletonStoreTest` (keep the existing tests; update their `save` calls to pass `marks = null` where a parameter is now required, or give the parameter a default of null and leave them):

```kotlin
    private val marks = com.badmintontracker.analysis.geometry.CourtKeypoints(
        topLeft = Point(649.5, 484.8), topRight = Point(1277.3, 481.2),
        bottomRight = Point(1579.4, 998.6), bottomLeft = Point(360.0, 1004.0),
        netLeft = Point(550.0, 663.9), netRight = Point(1382.2, 665.7),
        serviceLineNearLeft = Point(504.8, 743.5), serviceLineNearRight = Point(1422.0, 738.1),
        serviceLineFarLeft = Point(588.0, 595.2), serviceLineFarRight = Point(1338.8, 595.2),
        centerNear = Point(966.1, 593.4), centerFar = Point(966.1, 736.3),
    )

    @Test
    fun the_court_marks_travel_with_the_poses() {
        val s = store()
        s.save("e1", listOf(pose(0), pose(1)), 30.0, 1920, 1080, marks)
        val stored = s.load("e1")!!
        stored.marks shouldBe marks
        stored.poses.size shouldBe 2
    }

    @Test
    fun a_skeleton_saved_without_marks_loads_without_them() {
        val s = store()
        s.save("e1", listOf(pose(0)), 30.0, 1920, 1080, null)
        s.load("e1")!!.marks shouldBe null
    }

    @Test
    fun a_version_one_file_still_loads_with_no_marks() {
        // Written by hand in the v1 layout: a file from before the marks existed.
        val p = pose(7)
        val buffer = java.nio.ByteBuffer.allocate(28 + 216).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buffer.put("SKEL".toByteArray(Charsets.US_ASCII)).putInt(1).putDouble(25.0).putInt(1280).putInt(720).putInt(1)
        buffer.putInt(p.frame).putDouble(p.timestamp)
        for (k in 0 until Coco.COUNT) {
            buffer.putFloat(p.keypoints[k].x.toFloat()).putFloat(p.keypoints[k].y.toFloat()).putFloat(p.confidence[k])
        }
        val file = java.io.File(temp.root, "skeletons/e1.skel").apply { parentFile!!.mkdirs() }
        file.writeBytes(buffer.array())
        val s = store()
        s.has("e1") shouldBe true
        val stored = s.load("e1")!!
        stored.marks shouldBe null
        stored.fps shouldBe 25.0
        stored.poses.single().frame shouldBe 7
    }

    @Test
    fun a_version_two_file_cut_inside_the_marks_is_refused() {
        val s = store()
        s.save("e1", listOf(pose(0)), 30.0, 1920, 1080, marks)
        val file = java.io.File(temp.root, "skeletons/e1.skel")
        file.writeBytes(file.readBytes().copyOf(100))
        s.load("e1") shouldBe null
    }
```

Read the existing truncation test in the file and keep it passing: it truncates a saved file inside a pose; with the v2 header the byte offsets it uses may need to move. Update that test's arithmetic to the v2 header size, not the assertion.

- [ ] **Step 2: Run the tests to see them fail**

Run: `./gradlew :androidApp:testDebugUnitTest --tests '*SkeletonStoreTest*'`
Expected: compilation failure on the `marks` parameter.

- [ ] **Step 3: Implement**

In `SkeletonStore`:

- `Stored` gains `val marks: CourtKeypoints?` (analysis type) as the last field.
- `save(entryId, poses, fps, videoWidth, videoHeight, marks: CourtKeypoints?)`: allocate `HEADER_BYTES_V2 + poses.size * POSE_BYTES`; write magic, `VERSION` (now 2), fps, width, height, `if (marks != null) 1 else 0`, then 24 doubles from `marks?.pixels()` (each `Point` as x then y; zeros when null), then the count and the poses.
- `has`: the version read from the header must be 1 or 2.
- `load`: after the magic, read the version; if 1 read fps, width, height, count and `marks = null`; if 2 read fps, width, height, the flag, the 24 doubles (build a `CourtKeypoints` from the twelve points in `pixels()` order when the flag is 1; the constructor order is `topLeft, topRight, bottomRight, bottomLeft, netLeft, netRight, serviceLineNearLeft, serviceLineNearRight, serviceLineFarLeft, serviceLineFarRight, centerNear, centerFar`), then the count; any other version returns null. Reading the 24 doubles must be guarded by `buffer.remaining() >= 192 + 4` before the reads, so a file cut inside the marks returns null rather than throwing out of `runCatching` with a misleading result; the existing count guard and exact-remaining check stay after it.
- Update the class KDoc's layout line to the v2 layout and one sentence on why the marks are in the file: the strip's court-plane tiles need the homography, the local entry that holds the marks can be deleted while this file lives on, and a file that carries what it needs cannot be paired with the wrong marks.
- Constants: `VERSION = 2`, `HEADER_BYTES_V1 = 28`, `HEADER_BYTES_V2 = 224`, `MARKS_BYTES = 192`.

In `LocalAnalysisRunner`, the save call becomes:

```kotlin
                        skeletons.save(
                            entryId, result.poses, result.result.fps,
                            result.videoWidth, result.videoHeight, keypoints.toAnalysis(),
                        )
```

with `import com.badmintontracker.shared.model.toAnalysis`.

- [ ] **Step 4: Run the Android unit suite and compile the instrumented tests**

Run: `./gradlew :androidApp:testDebugUnitTest :androidApp:compileDebugAndroidTestKotlin`
Expected: green; any instrumented test that calls `SkeletonStore.save` compiles with the new parameter.

- [ ] **Step 5: Commit**

```bash
git add androidApp/src/main/java/com/badmintontracker/android/localanalysis/SkeletonStore.kt androidApp/src/main/java/com/badmintontracker/android/localanalysis/LocalAnalysisRunner.kt androidApp/src/test/java/com/badmintontracker/android/localanalysis/SkeletonStoreTest.kt
git commit -m "feat(android): the skeleton file carries the court marks it was measured against"
```

---

## Task 4: The racket-arm preference in `:shared`

**Files:**
- Create: `shared/src/commonMain/kotlin/com/badmintontracker/shared/prefs/RacketArmPreferenceRepository.kt`
- Modify: `shared/src/commonMain/kotlin/com/badmintontracker/shared/RallyApp.kt:69` (one line after `playbackPrefs`)
- Test: `shared/src/commonTest/kotlin/com/badmintontracker/shared/prefs/RacketArmPreferenceRepositoryTest.kt`

**Interfaces:**
- Produces: `enum class RacketArm { LEFT, RIGHT }`; `RacketArmPreferenceRepository(settings)` with `racketArm(entryId): RacketArm?` and `setRacketArm(entryId, arm: RacketArm?)`; `RallyApp.racketArmPrefs`.

Why a preference and not a field on `LocalVideoEntry`: the entry is constructed argument by argument from Swift, and a device-only display choice does not belong in the registry that the sync pipeline reads. Same storage as `PlaybackPreferenceRepository`, keyed by video.

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.badmintontracker.shared.prefs

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RacketArmPreferenceRepositoryTest {

    @Test
    fun nothing_is_assumed_about_which_arm_holds_the_racket() {
        assertNull(RacketArmPreferenceRepository(MapSettings()).racketArm("v1"))
    }

    @Test
    fun the_choice_is_per_video_and_survives_a_new_instance() {
        val settings = MapSettings()
        RacketArmPreferenceRepository(settings).setRacketArm("v1", RacketArm.LEFT)
        val reloaded = RacketArmPreferenceRepository(settings)
        assertEquals(RacketArm.LEFT, reloaded.racketArm("v1"))
        assertNull(reloaded.racketArm("v2"))
    }

    @Test
    fun clearing_returns_to_both_arms() {
        val repo = RacketArmPreferenceRepository(MapSettings())
        repo.setRacketArm("v1", RacketArm.RIGHT)
        repo.setRacketArm("v1", null)
        assertNull(repo.racketArm("v1"))
    }

    @Test
    fun a_value_this_build_does_not_know_reads_as_unset() {
        val settings = MapSettings()
        settings.putString("racket_arm:v1", "AMBIDEXTROUS")
        assertNull(RacketArmPreferenceRepository(settings).racketArm("v1"))
    }
}
```

- [ ] **Step 2: Run the tests to see them fail**

Run: `./gradlew :shared:jvmTest --tests '*RacketArmPreferenceRepositoryTest*'`
Expected: compilation failure.

- [ ] **Step 3: Implement**

```kotlin
package com.badmintontracker.shared.prefs

import com.russhwolf.settings.Settings

/** Which arm holds the racket, as the coach said; unset means show both. */
enum class RacketArm { LEFT, RIGHT }

/**
 * The racket arm per analysed video, remembered on this phone.
 *
 * The pipeline cannot tell handedness (see `Skeleton.ARM_EDGES`), so the
 * skeleton view shows both arms until a coach picks one, and remembers the
 * pick per video: a left-hander's match stays a left-hander's match. Stored
 * as a string like the other preference repositories, so a value written by
 * another build can never fail on a type mismatch; an unknown string reads
 * as unset.
 */
class RacketArmPreferenceRepository(private val settings: Settings) {

    fun racketArm(entryId: String): RacketArm? =
        settings.getStringOrNull(key(entryId))?.let { stored -> RacketArm.entries.firstOrNull { it.name == stored } }

    fun setRacketArm(entryId: String, arm: RacketArm?) {
        if (arm == null) settings.remove(key(entryId)) else settings.putString(key(entryId), arm.name)
    }

    private fun key(entryId: String) = "$KEY_PREFIX$entryId"

    private companion object {
        const val KEY_PREFIX = "racket_arm:"
    }
}
```

In `RallyApp.kt`, after the `playbackPrefs` line:

```kotlin
    val racketArmPrefs:   RacketArmPreferenceRepository = RacketArmPreferenceRepository(settings)
```

- [ ] **Step 4: Run the shared suite**

Run: `./gradlew :shared:jvmTest`
Expected: green (451 + 4).

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/badmintontracker/shared/prefs/RacketArmPreferenceRepository.kt shared/src/commonMain/kotlin/com/badmintontracker/shared/RallyApp.kt shared/src/commonTest/kotlin/com/badmintontracker/shared/prefs/RacketArmPreferenceRepositoryTest.kt
git commit -m "feat(shared): remember which arm holds the racket, per video"
```

---

## Task 5: Formatting rules, the tiles, and the graph

**Files:**
- Create: `androidApp/src/main/java/com/badmintontracker/android/localanalysis/MetricsFormat.kt`
- Create: `androidApp/src/main/java/com/badmintontracker/android/localanalysis/MetricsStrip.kt`
- Create: `androidApp/src/main/java/com/badmintontracker/android/localanalysis/MetricGraph.kt`
- Test: `androidApp/src/test/java/com/badmintontracker/android/localanalysis/MetricsFormatTest.kt`

**Interfaces:**
- Consumes: `PoseMetrics`, `MetricKind`, `Side` (Task 1); `RacketArm` (Task 4).
- Produces: `formatMetric(kind, value): String`, `metricLabel(kind, racketArm): String`, `visibleKinds(hasCourt, racketArm): List<MetricKind>`, `data class MetricSample(timestamp: Double, metrics: PoseMetrics)`, `@Composable MetricsStrip(...)`, `@Composable MetricGraph(...)`. Task 7 uses these names.

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.badmintontracker.android.localanalysis

import com.badmintontracker.analysis.player.MetricKind
import com.badmintontracker.shared.prefs.RacketArm
import io.kotest.matchers.shouldBe
import org.junit.Test

class MetricsFormatTest {

    @Test
    fun metres_show_two_decimals_and_angles_whole_degrees() {
        formatMetric(MetricKind.STANCE, 0.236) shouldBe "0.24 m"
        formatMetric(MetricKind.BEHIND_LINE, -0.5) shouldBe "-0.50 m"
        formatMetric(MetricKind.ELBOW_RIGHT, 173.6) shouldBe "174°"
        formatMetric(MetricKind.LEAN, -7.6) shouldBe "-8°"
        formatMetric(MetricKind.LEAN, 0.2) shouldBe "0°"
    }

    @Test
    fun an_absent_value_is_a_dash_not_a_stale_number() {
        formatMetric(MetricKind.STANCE, null) shouldBe "–"
        formatMetric(MetricKind.KNEE_LEFT, null) shouldBe "–"
    }

    @Test
    fun labels_name_the_side_until_the_racket_arm_is_chosen() {
        metricLabel(MetricKind.ELBOW_LEFT, null) shouldBe "Elbow L"
        metricLabel(MetricKind.ARM_RIGHT, null) shouldBe "Arm R"
        metricLabel(MetricKind.ELBOW_RIGHT, RacketArm.RIGHT) shouldBe "Elbow"
        metricLabel(MetricKind.ARM_LEFT, RacketArm.LEFT) shouldBe "Arm"
        // Knees keep their side: both legs matter in a lunge whichever arm serves.
        metricLabel(MetricKind.KNEE_LEFT, RacketArm.RIGHT) shouldBe "Knee L"
        metricLabel(MetricKind.STANCE, null) shouldBe "Stance"
        metricLabel(MetricKind.BEHIND_LINE, null) shouldBe "Behind line"
        metricLabel(MetricKind.LEAN, null) shouldBe "Lean"
    }

    @Test
    fun court_tiles_need_the_court_and_the_other_arm_hides_once_an_arm_is_chosen() {
        visibleKinds(hasCourt = true, racketArm = null) shouldBe listOf(
            MetricKind.STANCE, MetricKind.BEHIND_LINE,
            MetricKind.ELBOW_LEFT, MetricKind.ELBOW_RIGHT, MetricKind.ARM_LEFT, MetricKind.ARM_RIGHT,
            MetricKind.KNEE_LEFT, MetricKind.KNEE_RIGHT, MetricKind.LEAN,
        )
        visibleKinds(hasCourt = false, racketArm = RacketArm.RIGHT) shouldBe listOf(
            MetricKind.ELBOW_RIGHT, MetricKind.ARM_RIGHT, MetricKind.KNEE_LEFT, MetricKind.KNEE_RIGHT, MetricKind.LEAN,
        )
        visibleKinds(hasCourt = true, racketArm = RacketArm.LEFT) shouldBe listOf(
            MetricKind.STANCE, MetricKind.BEHIND_LINE, MetricKind.ELBOW_LEFT, MetricKind.ARM_LEFT,
            MetricKind.KNEE_LEFT, MetricKind.KNEE_RIGHT, MetricKind.LEAN,
        )
    }
}
```

- [ ] **Step 2: Run the tests to see them fail**

Run: `./gradlew :androidApp:testDebugUnitTest --tests '*MetricsFormatTest*'`
Expected: compilation failure.

- [ ] **Step 3: Implement `MetricsFormat.kt`**

```kotlin
package com.badmintontracker.android.localanalysis

import com.badmintontracker.analysis.player.MetricKind
import com.badmintontracker.analysis.player.PoseMetrics
import com.badmintontracker.analysis.player.Side
import com.badmintontracker.shared.prefs.RacketArm
import java.util.Locale
import kotlin.math.roundToInt

/** One frame's measurements, stamped with the pose's container timestamp. */
data class MetricSample(val timestamp: Double, val metrics: PoseMetrics)

/** The glyph for a measurement this frame does not have. An en dash, not a hyphen: it must not read as a minus. */
const val ABSENT_METRIC = "–"

/**
 * Metres to two decimals, degrees whole. Locale-fixed: a decimal comma on a
 * German phone would make "0,24 m" and "0.24 m" different numbers to the
 * test that pins them.
 */
fun formatMetric(kind: MetricKind, value: Double?): String {
    if (value == null) return ABSENT_METRIC
    return if (kind.isAngle) "${value.roundToInt()}°" else String.format(Locale.US, "%.2f m", value)
}

/**
 * The tile's label. The side is dropped from the arm kinds once the racket
 * arm is chosen, because then there is only one "Elbow" on screen; the knees
 * keep theirs, both legs matter in a lunge whichever arm serves.
 */
fun metricLabel(kind: MetricKind, racketArm: RacketArm?): String {
    val armChosen = racketArm != null
    fun sided(base: String, side: Side?, dropWhenChosen: Boolean): String = when {
        side == null -> base
        dropWhenChosen && armChosen -> base
        side == Side.LEFT -> "$base L"
        else -> "$base R"
    }
    return when (kind) {
        MetricKind.STANCE -> "Stance"
        MetricKind.BEHIND_LINE -> "Behind line"
        MetricKind.ELBOW_LEFT, MetricKind.ELBOW_RIGHT -> sided("Elbow", kind.side, dropWhenChosen = true)
        MetricKind.ARM_LEFT, MetricKind.ARM_RIGHT -> sided("Arm", kind.side, dropWhenChosen = true)
        MetricKind.KNEE_LEFT, MetricKind.KNEE_RIGHT -> sided("Knee", kind.side, dropWhenChosen = false)
        MetricKind.LEAN -> "Lean"
    }
}

/** Which tiles to show, in order: court-plane first, then the racket arm's kinds, both knees, the lean. */
fun visibleKinds(hasCourt: Boolean, racketArm: RacketArm?): List<MetricKind> =
    MetricKind.entries.filter { kind ->
        when {
            kind.needsCourt -> hasCourt
            kind == MetricKind.KNEE_LEFT || kind == MetricKind.KNEE_RIGHT || kind.side == null -> true
            racketArm == null -> true
            else -> (kind.side == Side.LEFT) == (racketArm == RacketArm.LEFT)
        }
    }
```

- [ ] **Step 4: Run the format tests**

Run: `./gradlew :androidApp:testDebugUnitTest --tests '*MetricsFormatTest*'`
Expected: green.

- [ ] **Step 5: Implement `MetricsStrip.kt`**

```kotlin
package com.badmintontracker.android.localanalysis

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.badmintontracker.analysis.player.MetricKind
import com.badmintontracker.analysis.player.PoseMetrics
import com.badmintontracker.shared.prefs.RacketArm

/**
 * The per-frame measurements under the skeleton video.
 *
 * One tile per visible kind, label over value, absent shown as a dash so the
 * layout never jumps and a stale number is never left on screen. Tapping a
 * tile selects it for the graph and the overlay's arc. The caption says once
 * what every angle tile means; the racket-arm control is the one thing the
 * pipeline cannot know and the coach can.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MetricsStrip(
    metrics: PoseMetrics?,
    hasCourt: Boolean,
    racketArm: RacketArm?,
    onRacketArm: (RacketArm?) -> Unit,
    selected: MetricKind,
    onSelect: (MetricKind) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            visibleKinds(hasCourt, racketArm).forEach { kind ->
                MetricTile(
                    label = metricLabel(kind, racketArm),
                    value = formatMetric(kind, metrics?.let { kind.of(it) }),
                    selected = kind == selected,
                    onClick = { onSelect(kind) },
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "Angles as seen by the camera",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SingleChoiceSegmentedButtonRow {
                listOf(RacketArm.LEFT to "Left arm", RacketArm.RIGHT to "Right arm", null to "Both")
                    .forEachIndexed { index, (arm, label) ->
                        SegmentedButton(
                            selected = racketArm == arm,
                            onClick = { onRacketArm(arm) },
                            shape = SegmentedButtonDefaults.itemShape(index, 3),
                        ) { Text(label, style = MaterialTheme.typography.labelSmall) }
                    }
            }
        }
    }
}

@Composable
private fun MetricTile(label: String, value: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier.widthIn(min = 76.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}
```

If the caption and the three-segment control do not fit on one row on a 412 dp-wide phone (they may not: three segments of labelSmall text plus a caption), put the control on its own row under the caption instead of fighting the width. Decide by rendering on the emulator in Task 8, not by guessing.

- [ ] **Step 6: Implement `MetricGraph.kt`**

```kotlin
package com.badmintontracker.android.localanalysis

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.badmintontracker.analysis.player.MetricKind

/**
 * The selected measurement over the seconds around the playhead.
 *
 * A serve is a curve, not a frame: the elbow angle through a serve is what a
 * coach compares between two serves, and a tile cannot show a curve. Raw
 * values, no smoothing, a gap wherever the joint was absent for more than two
 * frames, a fixed vertical range per kind so the shape does not rescale
 * under the eye. The playhead sits at the centre; tapping or dragging seeks.
 */
@Composable
fun MetricGraph(
    series: List<MetricSample>,
    kind: MetricKind,
    positionS: Double,
    fps: Double,
    onSeek: (Double) -> Unit,
    modifier: Modifier = Modifier,
    windowS: Double = 2.0,
) {
    val line = MaterialTheme.colorScheme.primary
    val playhead = MaterialTheme.colorScheme.onSurface
    val frame = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val labelStyle = MaterialTheme.typography.labelSmall
    val measurer = rememberTextMeasurer()
    val range = kind.range
    val gapS = if (fps > 0) 2.5 / fps else 0.1

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(96.dp)
            .padding(horizontal = 12.dp)
            .pointerInput(positionS, windowS) {
                detectTapGestures { tap -> onSeek(positionS + (tap.x / size.width - 0.5) * 2 * windowS) }
            }
            .pointerInput(windowS) {
                var anchorS = positionS
                detectHorizontalDragGestures(
                    onDragStart = { anchorS = positionS },
                    onHorizontalDrag = { change, _ ->
                        anchorS += -(change.positionChange().x / size.width) * 2 * windowS
                        onSeek(anchorS)
                    },
                )
            },
    ) {
        val w = size.width
        val h = size.height
        val startS = positionS - windowS
        fun x(t: Double) = (((t - startS) / (2 * windowS)) * w).toFloat()
        fun y(v: Double) = (h - ((v - range.start) / (range.endInclusive - range.start)) * h).toFloat()

        drawRect(color = frame, style = Stroke(width = 1f))

        // Binary search for the first sample in the window; walk to the last.
        var lo = 0
        var hi = series.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (series[mid].timestamp < startS) lo = mid + 1 else hi = mid
        }
        var prev: MetricSample? = null
        var i = lo
        while (i < series.size && series[i].timestamp <= positionS + windowS) {
            val s = series[i]
            val v = kind.of(s.metrics)
            val pv = prev?.let { kind.of(it.metrics) }
            if (v != null && pv != null && prev != null && s.timestamp - prev.timestamp <= gapS) {
                drawLine(line, Offset(x(prev.timestamp), y(pv)), Offset(x(s.timestamp), y(v)), strokeWidth = 3f)
            } else if (v != null) {
                drawCircle(line, radius = 2f, center = Offset(x(s.timestamp), y(v)))
            }
            prev = s
            i++
        }

        drawLine(playhead, Offset(w / 2, 0f), Offset(w / 2, h), strokeWidth = 2f)

        val style = labelStyle.copy(color = labelColor)
        drawText(measurer, formatMetric(kind, range.endInclusive), topLeft = Offset(4f, 2f), style = style)
        val bottom = measurer.measure(formatMetric(kind, range.start), style)
        drawText(measurer, formatMetric(kind, range.start), topLeft = Offset(4f, h - bottom.size.height - 2f), style = style)
    }
}
```

`drawText` with a measurer is `androidx.compose.ui.text.drawText` on `DrawScope`; it is in the Compose BOM the app uses (2025.01.00). If a name resolves differently in that version, use what resolves; the behaviour is the contract.

- [ ] **Step 7: Build**

Run: `./gradlew :androidApp:compileDebugKotlin :androidApp:testDebugUnitTest`
Expected: compiles; unit suite green.

- [ ] **Step 8: Commit**

```bash
git add androidApp/src/main/java/com/badmintontracker/android/localanalysis/MetricsFormat.kt androidApp/src/main/java/com/badmintontracker/android/localanalysis/MetricsStrip.kt androidApp/src/main/java/com/badmintontracker/android/localanalysis/MetricGraph.kt androidApp/src/test/java/com/badmintontracker/android/localanalysis/MetricsFormatTest.kt
git commit -m "feat(android): the metrics tiles and the metric graph"
```

---

## Task 6: The overlay draws the selected angle

**Files:**
- Modify: `androidApp/src/main/java/com/badmintontracker/android/localanalysis/SkeletonOverlay.kt`

**Interfaces:**
- Consumes: `MetricKind.angleJoints` (Task 1), `Coco`.
- Produces: `sealed interface PoseHighlight { data class Angle(a, vertex, c); data object Stance }`, `fun MetricKind.highlight(): PoseHighlight?`, `SkeletonOverlay(..., highlight: PoseHighlight? = null)`.

- [ ] **Step 1: Add the highlight**

At the top of the file:

```kotlin
/** What the overlay emphasises for the selected tile: the arc of an angle, or the line between the ankles. */
sealed interface PoseHighlight {
    data class Angle(val a: Int, val vertex: Int, val c: Int) : PoseHighlight
    data object Stance : PoseHighlight
}

fun MetricKind.highlight(): PoseHighlight? = when {
    angleJoints != null -> PoseHighlight.Angle(angleJoints.first, angleJoints.second, angleJoints.third)
    this == MetricKind.STANCE -> PoseHighlight.Stance
    else -> null
}
```

Add the parameter `highlight: PoseHighlight? = null` to `SkeletonOverlay` after `minConfidence`. Inside the `Canvas`, after the joints loop:

```kotlin
        when (highlight) {
            is PoseHighlight.Angle -> {
                val (a, v, c) = highlight
                val visible = listOf(a, v, c).all { confidence.getOrElse(it) { 0f } >= minConfidence }
                if (visible) {
                    val centre = at(v)
                    val ua = at(a) - centre
                    val uc = at(c) - centre
                    if (ua.getDistance() > 0f && uc.getDistance() > 0f) {
                        val startDeg = Math.toDegrees(atan2(ua.y.toDouble(), ua.x.toDouble())).toFloat()
                        val endDeg = Math.toDegrees(atan2(uc.y.toDouble(), uc.x.toDouble())).toFloat()
                        // The short way round: the interior angle, never its reflex.
                        var sweep = endDeg - startDeg
                        while (sweep > 180f) sweep -= 360f
                        while (sweep < -180f) sweep += 360f
                        drawArc(
                            color = HIGHLIGHT,
                            startAngle = startDeg,
                            sweepAngle = sweep,
                            useCenter = false,
                            topLeft = Offset(centre.x - ARC_RADIUS, centre.y - ARC_RADIUS),
                            size = Size(ARC_RADIUS * 2, ARC_RADIUS * 2),
                            style = Stroke(width = HIGHLIGHT_WIDTH),
                        )
                    }
                }
            }
            PoseHighlight.Stance -> {
                if (confidence.getOrElse(Coco.LEFT_ANKLE) { 0f } >= minConfidence &&
                    confidence.getOrElse(Coco.RIGHT_ANKLE) { 0f } >= minConfidence
                ) {
                    drawLine(HIGHLIGHT, at(Coco.LEFT_ANKLE), at(Coco.RIGHT_ANKLE), strokeWidth = HIGHLIGHT_WIDTH)
                }
            }
            null -> Unit
        }
```

with the constants `private const val ARC_RADIUS = 22f`, `private const val HIGHLIGHT_WIDTH = 3f`, `private val HIGHLIGHT = Color(0xFFE040FB)`, and the imports (`androidx.compose.ui.geometry.Size`, `androidx.compose.ui.graphics.drawscope.Stroke`, `kotlin.math.atan2`, `com.badmintontracker.analysis.player.MetricKind`). Compose's `drawArc` measures angles clockwise from 3 o'clock in the same y-down frame as `atan2` on screen offsets, so no sign flip.

Add a KDoc paragraph to `SkeletonOverlay`: the highlight puts the number and the geometry it came from on the same pixels; it is drawn last so it sits over the limbs; it is omitted when any joint it needs is below the threshold, the same rule the limbs follow.

- [ ] **Step 2: Build**

Run: `./gradlew :androidApp:compileDebugKotlin`
Expected: compiles.

- [ ] **Step 3: Commit**

```bash
git add androidApp/src/main/java/com/badmintontracker/android/localanalysis/SkeletonOverlay.kt
git commit -m "feat(android): the skeleton overlay draws the selected angle's arc"
```

---

## Task 7: The panel puts it together

**Files:**
- Modify: `androidApp/src/main/java/com/badmintontracker/android/localanalysis/SkeletonPanel.kt`
- Modify: `androidApp/src/main/java/com/badmintontracker/android/analytics/AnalyticsDetailScreen.kt`
- Modify: `androidApp/src/main/java/com/badmintontracker/android/AuthGate.kt:610-616`
- Modify: `CHANGELOG.md` (Unreleased, Added)

**Interfaces:**
- Consumes: everything above. `SkeletonPanel` gains `racketArmPrefs: RacketArmPreferenceRepository`; `AnalyticsDetailScreen` gains the same and passes it; `AuthGate` passes `rally.racketArmPrefs`.

- [ ] **Step 1: The load precomputes the series**

`SkeletonLoad.Loaded` becomes:

```kotlin
    data class Loaded(
        val stored: SkeletonStore.Stored?,
        /** The resolved court fit from the file's marks; null for a v1 file or marks that do not fit. */
        val homography: Matrix3x3?,
        /** One entry per pose, in pose order, for the graph. */
        val series: List<MetricSample>,
    ) : SkeletonLoad
```

and the `produceState` block computes, still on `Dispatchers.IO`:

```kotlin
            val stored = runner.storedSkeleton(entryId)
            val homography = stored?.marks?.homography()
            val series = stored?.poses?.map { MetricSample(it.timestamp, poseMetrics(it.keypoints, it.confidence, homography)) }
                ?: emptyList()
            value = SkeletonLoad.Loaded(stored, homography, series)
```

Use `homography()` from `com.badmintontracker.analysis.geometry.homography`. A homography whose `maxResidualM` exceeds `NearPlayerSelector.MAX_COURT_RESIDUAL_M` is treated as null (the selector's own gate; the heatmap already refuses such a court, and a stance in metres from it would be metres wrong).

- [ ] **Step 2: `SkeletonPlayer` gains the strip, the graph and the selection**

`SkeletonPlayer(source, stored, homography, series, prefs, racketArmPrefs, entryId)`. Inside, after `pose`:

```kotlin
    val hasCourt = homography != null
    var racketArm by remember(entryId) { mutableStateOf(racketArmPrefs.racketArm(entryId)) }
    var selected by rememberSaveable(entryId) { mutableStateOf(if (hasCourt) MetricKind.STANCE else MetricKind.ELBOW_RIGHT) }
    val visible = visibleKinds(hasCourt, racketArm)
    // A choice that the racket-arm control just hid falls back to the first tile,
    // so the graph and the arc never show a kind with no tile on screen.
    if (selected !in visible) selected = visible.first()
    val metrics = remember(pose, homography) { pose?.let { poseMetrics(it.keypoints, it.confidence, homography) } }
```

The video box passes `highlight = selected.highlight()` to `SkeletonOverlay`. Directly under the box (still inside the `else` branch, so it is not drawn beside a playback error):

```kotlin
        MetricsStrip(
            metrics = metrics,
            hasCourt = hasCourt,
            racketArm = racketArm,
            onRacketArm = { arm ->
                racketArm = arm
                racketArmPrefs.setRacketArm(entryId, arm)
            },
            selected = selected,
            onSelect = { selected = it },
        )
```

After `FrameStepBar`, before the summary text (again only when `error == null`):

```kotlin
        MetricGraph(
            series = series,
            kind = selected,
            positionS = positionMs / 1000.0,
            fps = stored.fps,
            onSeek = { seconds ->
                if (player.isPlaying) player.pause()
                player.seekTo((seconds * 1000).toLong().coerceIn(0L, player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE))
            },
        )
```

`rememberSaveable` with an enum needs no custom saver. Note the seek: `SeekParameters.EXACT` is already set on the player, so the graph lands on the frame it names.

- [ ] **Step 3: The summary line names the court**

The summary text becomes `"Skeleton in N frames · frame X"` as today, followed by `" · court marks in file"` when `hasCourt`, or `" · no court marks: stance and position need a re-run"` when the file has none. A coach whose file predates this change learns in one line why two tiles are missing.

- [ ] **Step 4: Wire the repository through**

`AnalyticsDetailScreen(entryId, localAnalysis, localVideos, playbackPrefs, racketArmPrefs, onBack)` passes `racketArmPrefs` to `SkeletonPanel`. In `AuthGate.kt` the call at line 610 gains `racketArmPrefs = rally.racketArmPrefs,`. Update the KDoc of `SkeletonPanel` with one paragraph: what the strip and graph are, and that everything they show is computed from the file at view time.

- [ ] **Step 5: CHANGELOG**

Under `## [Unreleased]`, `### Added`, first bullet:

```markdown
- The skeleton view now measures the near player frame by frame: stance width
  and distance behind the service line in metres (from the court marks), and
  elbow, arm, knee and trunk-lean angles as seen by the camera. Tap a tile to
  draw that angle on the skeleton and graph it over the two seconds around the
  playhead; tap the graph to seek. Tell it which arm holds the racket and the
  other arm's tiles step aside. A value the model is not sure of shows as a
  dash rather than a guess. Skeletons analysed before this version show the
  angles but need a re-run for the metres.
```

- [ ] **Step 6: Build and test**

Run: `./gradlew :androidApp:compileDebugKotlin :androidApp:testDebugUnitTest :androidApp:compileDebugAndroidTestKotlin`
Expected: green.

- [ ] **Step 7: Commit**

```bash
git add androidApp/src/main/java/com/badmintontracker/android/localanalysis/SkeletonPanel.kt androidApp/src/main/java/com/badmintontracker/android/analytics/AnalyticsDetailScreen.kt androidApp/src/main/java/com/badmintontracker/android/AuthGate.kt CHANGELOG.md
git commit -m "feat(android): per-frame measurements, an arc and a graph under the skeleton video"
```

---

## Task 8: On the emulator

**Files:**
- Create: `docs/screenshots/android-analytics-metrics-light.png`, `docs/screenshots/android-analytics-metrics-dark.png`, `docs/screenshots/android-analytics-metrics-v1-light.png`
- Modify: `docs/plans/2026-09-07-pose-metrics-research.md` (the status line)

The emulator holds a v1 skeleton for the corpus video from the previous session's 21-minute run, and the app data survives an install of the new build.

- [ ] **Step 1: Install and check the v1 path first**

`./gradlew :androidApp:installDebug`, open the app, Analytics, the corpus match, Skeleton. Expect: tiles without Stance and Behind line, the summary saying the file has no court marks, the arc on the selected joint, the graph drawing. Screenshot to `android-analytics-metrics-v1-light.png` (`adb exec-out screencap -p > file`; the file must not exist beforehand, simctl-style refusal does not apply to adb but check the timestamp anyway).

- [ ] **Step 2: Re-run the analysis with both metrics**

From the match's court-marking screen (the marks are stored; do not re-mark), tick "Player movement and heatmap" and "Skeleton playback", run on device. About 21 minutes on this emulator at 4 GB. Wait with a bounded `until` loop on `adb logcat` or the notification, not a blind sleep.

- [ ] **Step 3: Verify at the serve**

Open Skeleton. Seek to 2:02 (the serve at 122.1 s in the corpus: skip buttons then frame steps). Check, and write the observed values into the task report:

- Stance reads between 0.15 and 0.45 m while the feet are together at the service line, then about 0.8 to 1.0 m in the first step after contact (research §3).
- The right elbow reads about 170 to 177° with the racket low, then falls through the stroke.
- The arc sits on the elbow when Elbow R is selected, on the knee for Knee L, and the ankle line for Stance.
- Selecting "Left arm" hides Elbow R and Arm R, renames Elbow L to "Elbow", and the graph follows.
- Tapping the graph one second right of the playhead seeks about one second forward.
- A frame with no skeleton shows dashes in every tile and a gap in the graph.
- The strip and the segmented control fit the width without clipping or wrapping mid-label, in both themes. If the caption and the control do not fit on one row, move the control under the caption (Task 5's note) and re-check.

Screenshot light and dark at the serve stance with Elbow R selected, to `android-analytics-metrics-{light,dark}.png`. Pixel-level sloppiness is a defect: check tile alignment, that the arc is on the joint, and that the graph's labels do not overlap the curve.

- [ ] **Step 4: Heatmap sanity**

Open Heatmap for the same re-run. The summary's "player found in N% of frames" should be within a few points of the previous run's value (the scale gate removes close-up frames, which were a small share of a broadcast and none of a fixed phone). Record both numbers in the report.

- [ ] **Step 5: Docs and commit**

Change the research doc's status line to `Status: implemented 2026-09-07 on Android; see docs/screenshots/android-analytics-metrics-*.png. Tier C heights (§5) are not built.`

```bash
git add docs/screenshots/android-analytics-metrics-light.png docs/screenshots/android-analytics-metrics-dark.png docs/screenshots/android-analytics-metrics-v1-light.png docs/plans/2026-09-07-pose-metrics-research.md
git commit -m "docs: the metrics strip on the emulator"
```
