package com.badmintontracker.shared.localvideo

import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * When a finished run is announced on the match list.
 *
 * The rule is small and its consequences are not: every case here is a way the
 * app could take the screen out from under someone who was using it.
 */
class RunAnnouncementTest {

    @Test
    fun a_run_that_lands_while_home_is_showing_announces() {
        val result = announceFinishedRuns(finished = setOf("v1"), seen = emptySet(), onHome = true)

        result.announce shouldBe setOf("v1")
        result.seen shouldBe setOf("v1")
    }

    @Test
    fun the_same_finished_run_does_not_announce_twice() {
        // The list is left and come back to on every trip out and back.
        // Without this every arrival back on it would announce the same run
        // again.
        val first = announceFinishedRuns(setOf("v1"), emptySet(), onHome = true)
        val second = announceFinishedRuns(setOf("v1"), first.seen, onHome = true)

        second.announce shouldBe emptySet()
    }

    @Test
    fun a_run_that_lands_while_the_coach_is_elsewhere_never_announces() {
        // The case the seen-set exists for. The run finishes while the coach is
        // deep in another screen; the app bar indicator reports it. Walking back
        // onto the list ten minutes later must not then behave as though
        // it had just that moment landed.
        val away = announceFinishedRuns(setOf("v1"), emptySet(), onHome = false)
        away.announce shouldBe emptySet()

        val backOnHome = announceFinishedRuns(setOf("v1"), away.seen, onHome = true)
        backOnHome.announce shouldBe emptySet()
    }

    @Test
    fun re_analysing_the_same_video_announces_again() {
        // The reason seen is replaced rather than accumulated. The second run is
        // one the coach asked for just as explicitly as the first.
        val first = announceFinishedRuns(setOf("v1"), emptySet(), onHome = true)
        // Mid-run the entry is no longer finished, so it leaves the set.
        val running = announceFinishedRuns(emptySet(), first.seen, onHome = true)
        running.announce shouldBe emptySet()

        val second = announceFinishedRuns(setOf("v1"), running.seen, onHome = true)
        second.announce shouldBe setOf("v1")
    }

    @Test
    fun a_second_video_landing_announces_even_though_the_first_is_already_seen() {
        val first = announceFinishedRuns(setOf("v1"), emptySet(), onHome = true)
        val both = announceFinishedRuns(setOf("v1", "v2"), first.seen, onHome = true)

        both.announce shouldBe setOf("v2")
        both.seen shouldBe setOf("v1", "v2")
    }

    @Test
    fun nothing_finished_announces_nothing() {
        announceFinishedRuns(emptySet(), emptySet(), onHome = true).announce shouldBe emptySet()
    }

    @Test
    fun only_the_runs_that_just_landed_are_announced() {
        val first = announceFinishedRuns(setOf("v1"), emptySet(), onHome = true)
        val both = announceFinishedRuns(setOf("v1", "v2"), first.seen, onHome = true)

        both.announce shouldBe setOf("v2")
    }

    @Test
    fun nothing_is_announced_while_something_is_above_the_list() {
        announceFinishedRuns(setOf("v1"), emptySet(), onHome = false).announce shouldBe emptySet()
    }
}
