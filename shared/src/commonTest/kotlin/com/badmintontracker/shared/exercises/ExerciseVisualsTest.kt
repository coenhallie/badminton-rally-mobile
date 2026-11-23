package com.badmintontracker.shared.exercises

import com.badmintontracker.analysis.geometry.Court
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.floats.shouldBeBetween
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.math.hypot
import kotlin.test.Test

class ExerciseVisualsTest {

    @Test
    fun every_catalog_exercise_has_a_matching_visual() {
        ExerciseCatalog.all.forEach { exercise ->
            val visual = visualFor(exercise.id)
            visual.exerciseId shouldBe exercise.id
            visual.pose.keyframes.size shouldBeGreaterThanOrEqual 2
            if (exercise.category == ExerciseCategory.ON_COURT) {
                val path = visual.courtPath.shouldNotBeNull()
                path.waypoints.size shouldBeGreaterThanOrEqual 3
            } else {
                visual.courtPath.shouldBeNull()
            }
        }
    }

    @Test
    fun all_joints_and_court_points_stay_within_valid_bounds() {
        val halfLength = (Court.LENGTH / 2.0).toFloat()
        val width = Court.WIDTH_DOUBLES.toFloat()

        ExerciseCatalog.all.forEach { exercise ->
            val visual = visualFor(exercise.id)
            visual.pose.keyframes.forEach { kf ->
                kf.label.isNotBlank() shouldBe true
                kf.figure.allBodyPoints().forEach { pt ->
                    pt.x.shouldBeBetween(0f, 1f, 1e-4f)
                    pt.y.shouldBeBetween(0f, 1f, 1e-4f)
                }
                val tip = kf.figure.racketTip
                if (exercise.category == ExerciseCategory.OFF_COURT) {
                    tip.shouldBeNull()
                } else {
                    val t = tip.shouldNotBeNull()
                    t.x.shouldBeBetween(0f, 1f, 1e-4f)
                    t.y.shouldBeBetween(0f, 1f, 1e-4f)
                    val len = hypot(t.x - kf.figure.wristR.x, t.y - kf.figure.wristR.y)
                    len.shouldBeBetween(0.12f, 0.28f, 1e-3f)
                }
            }
            visual.courtPath?.let { path ->
                path.baseMarker.x.shouldBeBetween(0f, width, 1e-3f)
                path.baseMarker.y.shouldBeBetween(0f, halfLength, 1e-3f)
                path.waypoints.forEach { pt ->
                    pt.x.shouldBeBetween(0f, width, 1e-3f)
                    pt.y.shouldBeBetween(0f, halfLength, 1e-3f)
                }
                path.shuttleStart?.let { s ->
                    s.x.shouldBeBetween(0f, width, 1e-3f)
                    s.y.shouldBeBetween(0f, halfLength, 1e-3f)
                }
                path.shuttleEnd?.let { e ->
                    e.x.shouldBeBetween(0f, width, 1e-3f)
                    e.y.shouldBeBetween(0f, halfLength, 1e-3f)
                }
            }
        }
    }

    @Test
    fun samplePose_interpolates_wraps_and_mirrors_for_left_hand() {
        val visual = visualFor("forecourt-lunge-net-kill")
        val right = samplePose(visual.pose, phase01 = 0.25f, mirrorX = false)
        val left = samplePose(visual.pose, phase01 = 0.25f, mirrorX = true)

        right.label.isNotBlank() shouldBe true
        left.label shouldBe right.label
        left.figure.head.x.shouldBeBetween(1f - right.figure.head.x - 1e-4f, 1f - right.figure.head.x + 1e-4f, 1e-5f)
        left.figure.head.y.shouldBeBetween(right.figure.head.y - 1e-4f, right.figure.head.y + 1e-4f, 1e-5f)
        val rightTip = right.figure.racketTip.shouldNotBeNull()
        val leftTip = left.figure.racketTip.shouldNotBeNull()
        leftTip.x.shouldBeBetween(1f - rightTip.x - 1e-4f, 1f - rightTip.x + 1e-4f, 1e-5f)

        // Phase outside [0, 1] wraps cleanly without throwing or NaN.
        val wrapped = samplePose(visual.pose, phase01 = 1.25f, mirrorX = false)
        wrapped.figure.head.x.shouldBeBetween(right.figure.head.x - 1e-4f, right.figure.head.x + 1e-4f, 1e-5f)
        val negative = samplePose(visual.pose, phase01 = -0.75f, mirrorX = false)
        negative.figure.head.x.shouldBeBetween(right.figure.head.x - 1e-4f, right.figure.head.x + 1e-4f, 1e-5f)
    }

    @Test
    fun sampleCourtPoint_traces_waypoints_and_mirrors_across_centre_line() {
        val path = visualFor("drop-and-follow-net-attack").courtPath.shouldNotBeNull()
        val rightStart = sampleCourtPoint(path, phase01 = 0f, mirrorX = false)
        val leftStart = sampleCourtPoint(path, phase01 = 0f, mirrorX = true)
        val rightMid = sampleCourtPoint(path, phase01 = 0.35f, mirrorX = false)
        val leftMid = sampleCourtPoint(path, phase01 = 0.35f, mirrorX = true)

        val width = Court.WIDTH_DOUBLES.toFloat()
        leftStart.x.shouldBeBetween(width - rightStart.x - 1e-3f, width - rightStart.x + 1e-3f, 1e-4f)
        leftMid.x.shouldBeBetween(width - rightMid.x - 1e-3f, width - rightMid.x + 1e-3f, 1e-4f)
        leftMid.y.shouldBeBetween(rightMid.y - 1e-3f, rightMid.y + 1e-3f, 1e-4f)

        val mirroredPath = mirroredCourtPoints(path.waypoints, mirrorX = true)
        mirroredPath shouldHaveSize path.waypoints.size
    }

    @Test
    fun visual_labels_have_no_em_dashes_or_player_pronouns() {
        val pronounRegex = Regex("""\b(he|she|his|her|they|their)\b""", RegexOption.IGNORE_CASE)
        ExerciseCatalog.all.forEach { exercise ->
            visualFor(exercise.id).pose.keyframes.forEach { kf ->
                kf.label.contains('\u2014') shouldBe false
                pronounRegex.containsMatchIn(kf.label) shouldBe false
            }
        }
    }
}
