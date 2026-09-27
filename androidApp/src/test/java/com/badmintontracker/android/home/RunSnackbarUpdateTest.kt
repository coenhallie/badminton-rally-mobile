package com.badmintontracker.android.home

import com.badmintontracker.analysis.player.PlayerTrack
import com.badmintontracker.android.localanalysis.ClipCutter
import com.badmintontracker.android.localanalysis.LocalAnalysisState
import io.kotest.matchers.shouldBe
import java.io.File
import kotlin.test.Test

/**
 * AuthGate's announcement rule: which finished runs reach the list's
 * snackbar, and how runs landing before it is shown join it. The seen-set rule
 * itself is shared and pinned in RunAnnouncementTest; these pin what Android
 * does around it. The Android twin of iOS's RunToastUpdateTests.
 */
class RunSnackbarUpdateTest {

    @Test
    fun a_device_run_that_found_nothing_is_not_finished() {
        finishedRuns(mapOf("empty" to done(), "rallies" to done(clips = 2)), emptySet()) shouldBe setOf("rallies")
    }

    @Test
    fun a_device_run_with_only_a_skeleton_is_finished() {
        finishedRuns(mapOf("pose" to done(skeletonSaved = true)), emptySet()) shouldBe setOf("pose")
    }

    @Test
    fun a_run_still_working_is_not_finished() {
        finishedRuns(mapOf("busy" to LocalAnalysisState.Analysing(0.5f)), emptySet()) shouldBe emptySet()
    }

    @Test
    fun cloud_runs_are_finished_unfiltered() {
        finishedRuns(emptyMap(), setOf("v1")) shouldBe setOf("v1")
    }

    @Test
    fun a_video_finished_both_ways_is_one_run() {
        finishedRuns(mapOf("e1" to done(clips = 1)), setOf("e1")) shouldBe setOf("e1")
    }

    @Test
    fun a_new_run_is_announced() {
        update(finished = setOf("a")).announced shouldBe setOf("a")
    }

    // Two runs can land on consecutive collections before HomeScreen has read
    // and cleared the first; a plain assignment dropped it.
    @Test
    fun a_run_landing_while_another_waits_to_be_shown_joins_it() {
        update(finished = setOf("a", "b"), seen = setOf("a"), waiting = setOf("a")).announced shouldBe setOf("a", "b")
    }

    // Once HomeScreen has taken the waiting runs, the next snackbar names the
    // new run alone and queues behind the one on screen (design 7.5).
    @Test
    fun once_the_waiting_runs_are_taken_the_next_starts_from_the_new_runs_alone() {
        update(finished = setOf("a", "b"), seen = setOf("a"), waiting = emptySet()).announced shouldBe setOf("b")
    }

    @Test
    fun nothing_new_leaves_the_waiting_runs_as_they_are() {
        val result = update(finished = setOf("a"), seen = setOf("a"), waiting = setOf("a"))
        result.announced shouldBe null
        result.seen shouldBe setOf("a")
    }

    // The seen-set rule, as it reaches the snackbar: a run that lands while the
    // list is not in front (a push, a sheet, the app in the background) is
    // marked seen and does not announce itself when the coach comes back.
    @Test
    fun a_run_landing_out_of_sight_is_marked_seen_and_never_announced() {
        val away = update(finished = setOf("a"), onHome = false)
        away.announced shouldBe null
        away.seen shouldBe setOf("a")
        update(finished = setOf("a"), seen = away.seen, onHome = true).announced shouldBe null
    }

    private fun update(
        finished: Set<String>,
        seen: Set<String> = emptySet(),
        onHome: Boolean = true,
        waiting: Set<String> = emptySet(),
    ) = runSnackbarUpdate(finished = finished, seen = seen, onHome = onHome, waiting = waiting)

    private fun done(clips: Int = 0, skeletonSaved: Boolean = false) = LocalAnalysisState.Done(
        rallies = clips,
        shuttleVisible = 0,
        totalFrames = 100,
        clips = (1..clips).map { ClipCutter.Clip(index = it, startSeconds = 0.0, endSeconds = 1.0, file = File("/tmp/$it.mp4")) },
        elapsedSeconds = 1.0,
        playerTrack = PlayerTrack(emptyList(), 0, emptyMap()),
        fps = 30.0,
        skeletonSaved = skeletonSaved,
    )
}
