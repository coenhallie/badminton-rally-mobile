package com.badmintontracker.shared.exercises

import com.badmintontracker.analysis.geometry.Court
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.floats.shouldBeBetween
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.math.hypot
import kotlin.math.min
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
    fun all_joints_and_court_points_stay_within_valid_bounds_across_full_cycle() {
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

            // Sample 12 phases across the animated FK cycle to confirm no mid-arc clipping.
            for (step in 0 until 12) {
                val p = step / 12f
                val sampled = samplePose(visual.pose, phase01 = p, mirrorX = false)
                sampled.figure.allBodyPoints().forEach { pt ->
                    pt.x.shouldBeBetween(0f, 1f, 1e-4f)
                    pt.y.shouldBeBetween(0f, 1f, 1e-4f)
                }
                sampled.figure.racketTip?.let { t ->
                    t.x.shouldBeBetween(0f, 1f, 1e-4f)
                    t.y.shouldBeBetween(0f, 1f, 1e-4f)
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
                val courtState = sampleCourtState(path, phase01 = 0.37f, mirrorX = false)
                courtState.playerPosition.x.shouldBeBetween(0f, width, 1e-3f)
                courtState.playerPosition.y.shouldBeBetween(0f, halfLength, 1e-3f)
                courtState.smoothRoute.forEach { rp ->
                    rp.x.shouldBeBetween(0f, width, 1e-3f)
                    rp.y.shouldBeBetween(0f, halfLength, 1e-3f)
                }
            }
        }
    }

    @Test
    fun fk_interpolation_preserves_racket_and_arm_length_mid_swing() {
        val visual = visualFor("clear-to-base-multi-shuttle")
        val load = visual.pose.keyframes[0].figure
        val strike = visual.pose.keyframes[1].figure
        val minRacketLen = min(
            hypot(load.racketTip!!.x - load.wristR.x, load.racketTip.y - load.wristR.y),
            hypot(strike.racketTip!!.x - strike.wristR.x, strike.racketTip.y - strike.wristR.y),
        )
        // Mid-swing between keyframe 0 (0.0) and keyframe 1 (0.333) sits around phase 0.20.
        val midSwing = samplePose(visual.pose, phase01 = 0.20f, mirrorX = false)
        val midRacketLen = hypot(
            midSwing.figure.racketTip!!.x - midSwing.figure.wristR.x,
            midSwing.figure.racketTip.y - midSwing.figure.wristR.y,
        )
        midRacketLen shouldBeGreaterThan (minRacketLen * 0.95f)
        midSwing.racketTrail.isNotEmpty() shouldBe true
    }

    @Test
    fun shuttle_flight_and_airborne_shadow_factor_fire_for_appropriate_drills() {
        val jumpSmash = visualFor("jump-smash-and-follow")
        // Tuck gather (keyframe 0) is airborne.
        val tuck = samplePose(jumpSmash.pose, phase01 = 0.02f, mirrorX = false)
        tuck.airborneFactor shouldBeGreaterThan 0.5f

        // Right after contact (keyframe 1 = 0.333, contactPhase = 0.367) shuttle flashes and rebounds.
        val impact = samplePose(jumpSmash.pose, phase01 = 0.38f, mirrorX = false)
        val shuttle = impact.shuttle.shouldNotBeNull()
        shuttle.alpha shouldBeGreaterThan 0.5f
        shuttle.impactFlash shouldBeGreaterThan 0.2f

        // Off-court exercise has no shuttle and is firmly grounded at keyframe 0.
        val squat = visualFor("split-squat-endurance")
        val squatTop = samplePose(squat.pose, phase01 = 0.02f, mirrorX = false)
        squatTop.shuttle.shouldBeNull()
        squatTop.airborneFactor shouldBe 0f
    }

    @Test
    fun samplePose_interpolates_wraps_and_mirrors_for_left_hand() {
        val visual = visualFor("forecourt-lunge-net-kill")
        val right = samplePose(visual.pose, phase01 = 0.38f, mirrorX = false)
        val left = samplePose(visual.pose, phase01 = 0.38f, mirrorX = true)

        right.label.isNotBlank() shouldBe true
        left.label shouldBe right.label
        left.keyframeIndex shouldBe right.keyframeIndex
        left.keyframeCount shouldBe right.keyframeCount
        left.figure.head.x.shouldBeBetween(1f - right.figure.head.x - 1e-4f, 1f - right.figure.head.x + 1e-4f, 1e-5f)
        left.figure.head.y.shouldBeBetween(right.figure.head.y - 1e-4f, right.figure.head.y + 1e-4f, 1e-5f)
        val rightTip = right.figure.racketTip.shouldNotBeNull()
        val leftTip = left.figure.racketTip.shouldNotBeNull()
        leftTip.x.shouldBeBetween(1f - rightTip.x - 1e-4f, 1f - rightTip.x + 1e-4f, 1e-5f)
        val rightShuttle = right.shuttle.shouldNotBeNull()
        val leftShuttle = left.shuttle.shouldNotBeNull()
        leftShuttle.position.x.shouldBeBetween(
            1f - rightShuttle.position.x - 1e-4f,
            1f - rightShuttle.position.x + 1e-4f,
            1e-5f,
        )

        // Phase outside [0, 1] wraps cleanly without throwing or NaN.
        val wrapped = samplePose(visual.pose, phase01 = 1.38f, mirrorX = false)
        wrapped.figure.head.x.shouldBeBetween(right.figure.head.x - 1e-4f, right.figure.head.x + 1e-4f, 1e-5f)
        val negative = samplePose(visual.pose, phase01 = -0.62f, mirrorX = false)
        negative.figure.head.x.shouldBeBetween(right.figure.head.x - 1e-4f, right.figure.head.x + 1e-4f, 1e-5f)
    }

    @Test
    fun sampleCourtState_traces_curved_waypoints_shuttle_and_mirrors_across_centre_line() {
        val path = visualFor("drop-and-follow-net-attack").courtPath.shouldNotBeNull()
        val rightState = sampleCourtState(path, phase01 = 0.25f, mirrorX = false)
        val leftState = sampleCourtState(path, phase01 = 0.25f, mirrorX = true)

        val width = Court.WIDTH_DOUBLES.toFloat()
        leftState.playerPosition.x.shouldBeBetween(
            width - rightState.playerPosition.x - 1e-3f,
            width - rightState.playerPosition.x + 1e-3f,
            1e-4f,
        )
        leftState.playerPosition.y.shouldBeBetween(
            rightState.playerPosition.y - 1e-3f,
            rightState.playerPosition.y + 1e-3f,
            1e-4f,
        )
        rightState.playerTrail shouldHaveSize 6
        rightState.shuttlePosition.shouldNotBeNull()
        leftState.shuttlePosition.shouldNotBeNull()

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
