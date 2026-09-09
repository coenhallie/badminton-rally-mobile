package com.badmintontracker.shared.localvideo

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The four cases the chrome badge has to get right, all of them about a set that
 * must neither start full nor only grow. See [failureTransitions].
 */
class FailureTransitionsTest {

    private fun stages(vararg pairs: Pair<String, AnalyzeStage>) = pairs.toMap()

    @Test
    fun an_entry_already_failed_when_collection_starts_is_not_badged() {
        // It failed in an earlier run of the app. Its row carries the failure and
        // the Retry that clears it; a badge here would never go out.
        val t = failureTransitions(emptyMap(), stages("a" to AnalyzeStage.FAILED))
        assertEquals(emptySet(), t.failed)
        assertEquals(emptySet(), t.resolved)
    }

    @Test
    fun an_entry_that_fails_while_watching_is_badged() {
        val t = failureTransitions(
            stages("a" to AnalyzeStage.UPLOADING),
            stages("a" to AnalyzeStage.FAILED),
        )
        assertEquals(setOf("a"), t.failed)
        assertEquals(emptySet(), t.resolved)
    }

    @Test
    fun a_retry_out_of_failed_resolves_the_badge() {
        val t = failureTransitions(
            stages("a" to AnalyzeStage.FAILED),
            stages("a" to AnalyzeStage.PROCESSING),
        )
        assertEquals(emptySet(), t.failed)
        assertEquals(setOf("a"), t.resolved)
    }

    @Test
    fun an_entry_removed_from_the_library_resolves_the_badge() {
        // A badge pointing at a video that is no longer in the app is a red dot
        // with nothing behind it.
        val t = failureTransitions(stages("a" to AnalyzeStage.FAILED), emptyMap())
        assertEquals(emptySet(), t.failed)
        assertEquals(setOf("a"), t.resolved)
    }

    @Test
    fun an_entry_that_stays_failed_neither_re_badges_nor_resolves() {
        // The list re-emits on every progress tick of every OTHER entry, and a
        // failure re-reported each time would be a badge that cannot be cleared.
        val t = failureTransitions(
            stages("a" to AnalyzeStage.FAILED),
            stages("a" to AnalyzeStage.FAILED),
        )
        assertEquals(emptySet(), t.failed)
        assertEquals(emptySet(), t.resolved)
    }

    @Test
    fun an_entry_that_is_removed_without_ever_failing_resolves_nothing_that_was_set() {
        // Resolved carries it either way; the caller subtracts, and subtracting
        // an id that was never in the set is a no-op. Pinned because the
        // alternative reading - only report removals of FAILED entries - would
        // make the two branches of `resolved` differ for no reason.
        val t = failureTransitions(stages("a" to AnalyzeStage.LOCAL), emptyMap())
        assertEquals(setOf("a"), t.resolved)
    }
}
