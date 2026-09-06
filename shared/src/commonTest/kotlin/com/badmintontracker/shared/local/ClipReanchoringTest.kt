package com.badmintontracker.shared.local

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class ClipReanchoringTest {

    /** Annotation timestamps are relative to clip start. */
    private val annotations = listOf("a" to 2.0, "b" to 5.0)

    @Test
    fun a_sub_epsilon_move_is_a_no_op() {
        // Returning null rather than an empty plan matters: the caller opens a
        // transaction on a non-null result, and re-writing every annotation on
        // every re-analysis is how a rounding difference becomes row churn.
        planReanchor(
            oldStart = 10.0, newStart = 10.01, newDuration = 8.0, annotations = annotations,
        ) shouldBe null
    }

    @Test
    fun an_earlier_start_moves_annotations_later() {
        // Clip start 10.0 -> 8.5 means the clip now begins 1.5s sooner, so a
        // moment 2.0s into the old clip is 3.5s into the new one. Getting this
        // sign wrong moves every annotation the wrong way by twice the delta,
        // and nothing about the result looks malformed.
        val plan = planReanchor(10.0, 8.5, 8.0, annotations)!!
        plan.shiftSeconds shouldBe 1.5
        plan.moved.map { it.annotationId to it.newTimestampSeconds } shouldBe
            listOf("a" to 3.5, "b" to 6.5)
        plan.flagged shouldBe emptyList()
    }

    @Test
    fun a_later_start_moves_annotations_earlier() {
        val plan = planReanchor(10.0, 11.5, 8.0, annotations)!!
        plan.shiftSeconds shouldBe -1.5
        plan.moved.map { it.annotationId to it.newTimestampSeconds } shouldBe
            listOf("a" to 0.5, "b" to 3.5)
    }

    @Test
    fun an_annotation_pushed_before_the_new_start_is_flagged_not_clamped() {
        // Clamping to 0.0 silently relocates a coach's note to the start of
        // the clip and looks like a real annotation. Flagging keeps the
        // computed value so a human can decide.
        val plan = planReanchor(10.0, 11.5, 8.0, listOf("early" to 0.5))!!
        plan.moved shouldBe emptyList()
        plan.flagged.single().annotationId shouldBe "early"
        plan.flagged.single().newTimestampSeconds shouldBe -1.0
        plan.flagged.single().outsideNewClip shouldBe true
    }

    @Test
    fun an_annotation_past_the_new_end_is_flagged() {
        val plan = planReanchor(10.0, 8.5, 3.0, listOf("late" to 2.0))!!
        plan.moved shouldBe emptyList()
        plan.flagged.single().annotationId shouldBe "late"
        plan.flagged.single().newTimestampSeconds shouldBe 3.5
    }

    @Test
    fun moved_and_flagged_partition_the_input() {
        // Nothing may be dropped: an annotation that appears in neither list
        // keeps its stale timestamp against footage that has moved.
        val input = listOf("early" to 0.2, "ok" to 2.0, "late" to 7.9)
        val plan = planReanchor(10.0, 11.5, 5.0, input)!!
        (plan.moved.size + plan.flagged.size) shouldBe input.size
        (plan.moved.map { it.annotationId } + plan.flagged.map { it.annotationId })
            .toSet() shouldBe input.map { it.first }.toSet()
    }

    @Test
    fun the_epsilon_separates_a_wobble_from_a_real_move() {
        // Deliberately not asserted at exactly epsilon. The obvious case,
        // planReanchor(10.0, 10.05, ...), looks like a shift of exactly 0.05
        // and is not: 10.0 - 10.05 is -0.05000000000000071 in binary floating
        // point, which is already past the threshold. Writing a test around
        // that equality pins an artifact of two particular decimal literals
        // rather than the behaviour, so the assertion is placed clearly on
        // either side instead.
        planReanchor(10.0, 10.02, 8.0, annotations) shouldBe null
        (planReanchor(10.0, 10.2, 8.0, annotations) != null) shouldBe true
    }
}
