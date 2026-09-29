package com.badmintontracker.analysis.player

import com.badmintontracker.analysis.geometry.Point
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * The per-side path (design `docs/plans/2026-09-29-two-player-tracking-design.md`
 * §5): which of each frame's gated people is this side's player, decided over
 * the whole run rather than frame by frame.
 */
class SidePathTest {

    private val fps = 30.0

    private fun at(frame: Int, x: Double, y: Double, confidence: Float = 0.8f, outsideM: Double = 0.0) =
        SideCandidate(frame, Point(x, y), confidence, outsideM)

    /** The chosen candidate's position per frame, null where none was chosen. */
    private fun chosen(frames: List<List<SideCandidate>>): List<Point?> =
        chooseSidePath(frames, fps).mapIndexed { i, j -> if (j < 0) null else frames[i][j].court }

    @Test
    fun one_player_moving_steadily_is_kept_in_every_frame() {
        val frames = (1..90).map { f -> listOf(at(f, 2.0 + 0.02 * f, 3.0)) }
        chosen(frames).all { it != null } shouldBe true
    }

    @Test
    fun one_candidate_per_frame_is_kept_even_well_outside_the_lines() {
        // Today's selection keeps anyone the gates pass, and so must the path
        // when nobody competes: the gates are the judge of "on the court".
        val frames = (1..30).map { f -> listOf(at(f, -1.5, 3.0 + 0.01 * f, confidence = 0.3f, outsideM = 1.5)) }
        chosen(frames).all { it != null } shouldBe true
    }

    @Test
    fun a_one_frame_jump_to_someone_five_metres_away_is_not_taken() {
        val frames = (1..60).map { f ->
            if (f == 30) listOf(at(f, 5.5, 5.5, confidence = 0.99f)) else listOf(at(f, 2.0, 3.0))
        }
        val path = chosen(frames)
        path[29] shouldBe null
        path.filterIndexed { i, _ -> i != 29 }.all { it == Point(2.0, 3.0) } shouldBe true
    }

    @Test
    fun a_still_official_off_the_court_loses_to_a_moving_player_inside_it() {
        // The umpire, more confident and detected in every frame, 1.2 m
        // outside the sideline; the player inside, missed every fifth frame.
        val frames = (1..90).map { f ->
            val umpire = at(f, 7.3, 3.0, confidence = 0.95f, outsideM = 1.2)
            if (f % 5 == 0) listOf(umpire) else listOf(umpire, at(f, 2.0 + 0.02 * f, 3.0, confidence = 0.6f))
        }
        val path = chosen(frames)
        path.withIndex().filter { (i, _) -> (i + 1) % 5 != 0 }.all { (_, p) -> p != null && p.x < 6.1 } shouldBe true
    }

    @Test
    fun the_other_player_reaching_across_the_net_does_not_take_the_side() {
        // IMG_5892 frames 1375-1378: the near player walks to the net and her
        // ankles cross it, more confidently detected than the far player.
        val frames = (1..60).map { f ->
            val far = at(f, 3.0 + 0.01 * f, 2.0, confidence = 0.6f)
            if (f in 30..34) listOf(at(f, 4.5, 6.5, confidence = 0.95f), far) else listOf(far)
        }
        chosen(frames).all { it != null && it.y < 3.0 } shouldBe true
    }

    @Test
    fun far_side_ankle_noise_does_not_break_the_path() {
        // At the far baseline a lifted foot moves the ground point by a metre
        // between frames; the path must ride through it.
        val frames = (1..60).map { f -> listOf(at(f, 3.0, if (f % 2 == 0) 1.0 else 1.9)) }
        chosen(frames).all { it != null } shouldBe true
    }

    @Test
    fun two_stretches_apart_in_time_are_both_kept() {
        val frames = ((1..30) + (120..150)).map { f -> listOf(at(f, if (f < 100) 2.0 else 4.5, 3.0)) }
        chosen(frames).all { it != null } shouldBe true
    }

    @Test
    fun a_lone_frame_far_in_time_from_any_other_is_kept_as_the_per_frame_pick_kept_it() {
        // Review finding: a 1-2 frame stretch more than MAX_SKIP_S from its
        // neighbours never paid back a restart, so the path dropped it where
        // the per-frame pick kept it. Nothing within reach can compete there.
        val frames = ((1..30) + listOf(61) + (92..120)).map { f -> listOf(at(f, 2.0, 3.0)) }
        chosen(frames).all { it != null } shouldBe true
        val two = ((1..30) + listOf(61, 62) + (92..120)).map { f -> listOf(at(f, 4.0, 2.0)) }
        chosen(two).all { it != null } shouldBe true
    }

    @Test
    fun frames_without_candidates_choose_nothing() {
        val frames = listOf(listOf(at(1, 2.0, 3.0)), emptyList(), listOf(at(3, 2.0, 3.0)))
        chooseSidePath(frames, fps).toList() shouldBe listOf(0, -1, 0)
    }

    @Test
    fun no_frames_give_no_path() {
        chooseSidePath(emptyList(), fps).size shouldBe 0
    }

    private fun sample(frame: Int, x: Double, y: Double) = PlayerSample(frame, Point(x, y))

    @Test
    fun a_short_stretch_that_starts_where_the_other_player_just_was_is_theirs() {
        // IMG_5892 frames 1376-1380: the near player walks to the net and her
        // ankles cross it while the far player is hidden behind her, so she is
        // the only person on the far side. Her far "stretch" starts where the
        // near track just left her.
        val near = (1..40).map { sample(it, 1.0, 7.4 - 0.02 * it) } // walking up to the net
        val far = (60..64).map { sample(it, 0.95, 6.55) } + (100..160).map { sample(it, 0.9, 2.8) }
        val nearBefore = near + listOf(sample(58, 0.95, 6.8))
        crossedFrom(far, nearBefore, fps) shouldBe (60..64).toSet()
    }

    @Test
    fun a_short_stretch_that_ends_where_the_other_player_reappears_is_theirs() {
        val far = (10..14).map { sample(it, 0.95, 6.55) }
        val near = listOf(sample(17, 1.0, 6.9)) + (18..40).map { sample(it, 1.0, 7.0 + 0.02 * it) }
        crossedFrom(far, near, fps) shouldBe (10..14).toSet()
    }

    @Test
    fun a_short_stretch_while_the_other_player_is_seen_on_their_own_side_is_kept() {
        // Both players at the net and both seen: the short far stretch cannot
        // be the near player, who is on her own side in the same frames.
        val near = (50..70).map { sample(it, 1.2, 7.1) }
        val far = (60..64).map { sample(it, 1.0, 6.4) }
        crossedFrom(far, near, fps) shouldBe emptySet()
    }

    @Test
    fun a_long_stretch_that_starts_near_the_other_player_is_kept() {
        // Both players at the net: the far player re-found there and followed
        // for two seconds is the far player.
        val near = listOf(sample(58, 1.2, 7.3))
        val far = (60..120).map { sample(it, 1.0, 6.2 - 0.01 * (it - 60)) }
        crossedFrom(far, near, fps) shouldBe emptySet()
    }

    @Test
    fun a_short_stretch_nowhere_near_the_other_player_is_kept() {
        val near = (1..60).map { sample(it, 3.0, 11.0) }
        val far = (30..34).map { sample(it, 3.0, 2.0) }
        crossedFrom(far, near, fps) shouldBe emptySet()
    }

    @Test
    fun outside_the_lines_is_measured_per_half() {
        outsideLinesM(Point(3.0, 2.0), CourtSide.FAR) shouldBe 0.0
        outsideLinesM(Point(3.0, -0.5), CourtSide.FAR) shouldBe 0.5
        outsideLinesM(Point(7.1, 3.0), CourtSide.FAR) shouldBe (1.0 plusOrMinus 1e-9)
        outsideLinesM(Point(3.0, 14.0), CourtSide.NEAR) shouldBe (0.6 plusOrMinus 1e-9)
        outsideLinesM(Point(3.0, 8.0), CourtSide.NEAR) shouldBe 0.0
    }
}
