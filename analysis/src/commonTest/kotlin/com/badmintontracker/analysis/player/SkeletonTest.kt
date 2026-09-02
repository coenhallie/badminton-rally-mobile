package com.badmintontracker.analysis.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SkeletonTest {

    @Test
    fun every_edge_refers_to_a_real_keypoint() {
        Skeleton.EDGES.forEach { (a, b) ->
            assertTrue(a in 0 until Coco.COUNT, "edge start $a is not a COCO keypoint")
            assertTrue(b in 0 until Coco.COUNT, "edge end $b is not a COCO keypoint")
        }
    }

    @Test
    fun the_limbs_that_matter_are_connected_to_the_right_joints() {
        // Spot-checks against the COCO layout rather than the whole list: these
        // are the joints the ankle midpoint and the racket arm depend on, and
        // an off-by-one in the edge table would draw a plausible but wrong
        // figure.
        assertTrue(13 to 15 in Skeleton.EDGES, "left knee must join the left ankle")
        assertTrue(14 to 16 in Skeleton.EDGES, "right knee must join the right ankle")
        assertTrue(11 to 12 in Skeleton.EDGES, "the hips must be joined to each other")
        assertTrue(7 to 9 in Skeleton.EDGES, "left elbow must join the left wrist")
    }

    @Test
    fun no_edge_is_listed_twice_in_either_direction() {
        val seen = Skeleton.EDGES.map { (a, b) -> minOf(a, b) to maxOf(a, b) }
        assertEquals(seen.size, seen.toSet().size, "a duplicated limb draws twice and reads as thicker")
    }

    @Test
    fun a_limb_is_drawn_only_when_both_ends_are_confident() {
        val confident = List(Coco.COUNT) { 0.9f }
        assertTrue(Skeleton.edgeVisible(confident, 13 to 15))

        // One uncertain end is enough to hide the limb: drawing to it produces
        // an arm through the chest, which reads as a tracking failure.
        val oneWeak = confident.toMutableList().also { it[15] = 0.1f }
        assertFalse(Skeleton.edgeVisible(oneWeak, 13 to 15))
    }

    @Test
    fun a_short_confidence_list_hides_the_limb_rather_than_throwing() {
        // A truncated model output must not crash a view.
        assertFalse(Skeleton.edgeVisible(List(5) { 0.9f }, 13 to 15))
    }
}
