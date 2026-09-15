package com.badmintontracker.shared.analytics

import com.badmintontracker.analysis.player.RallyWindow
import com.badmintontracker.shared.model.RallyClip
import io.kotest.matchers.shouldBe
import kotlinx.datetime.Instant
import kotlin.test.Test

/**
 * The Base tab for a cloud-analysed match. Its windows come from `rally_clips`,
 * which is already the rally truth for a cloud video; nothing here derives a
 * rally from the pose stream.
 */
class CloudRallyWindowsTest {

    private fun clip(videoId: String, index: Int, start: Float, end: Float) = RallyClip(
        id = "c-$videoId-$index", videoId = videoId, ownerId = "user-self", rallyIndex = index,
        startTimestamp = start, endTimestamp = end, durationSeconds = end - start,
        clipStoragePath = "user-self/$videoId/rally_$index.mp4", annotationCount = 0,
        createdAt = Instant.fromEpochMilliseconds(0),
    )

    @Test
    fun a_cloud_clip_becomes_the_window_its_base_is_measured_over() {
        cloudRallyWindows(listOf(clip("v1", 1, 4f, 21.5f)), "v1") shouldBe
            listOf(RallyWindow(1, 4.0, 21.5))
    }

    @Test
    fun the_rally_number_is_the_clips_own_and_nothing_adds_one() {
        // rally_index is 1-based across this project. A display that adds one
        // renames every rally in the match.
        cloudRallyWindows(listOf(clip("v1", 7, 0f, 1f)), "v1").single().index shouldBe 7
    }

    @Test
    fun only_this_videos_clips_are_measured() {
        // The cache holds every clip the coach has. Measuring one match's
        // medians over another's rallies is the defect this filter prevents,
        // and it belongs here rather than at two call sites that could forget.
        val clips = listOf(clip("v1", 1, 0f, 5f), clip("v2", 1, 9f, 12f), clip("v1", 2, 6f, 9f))

        cloudRallyWindows(clips, "v1").map { it.index } shouldBe listOf(1, 2)
    }

    @Test
    fun the_windows_come_out_in_rally_order() {
        // The cache is ordered by whatever the query returned. The panel prints
        // them down the page as rallies, so out-of-order rungs read as corruption.
        val clips = listOf(clip("v1", 3, 20f, 25f), clip("v1", 1, 0f, 5f), clip("v1", 2, 6f, 9f))

        cloudRallyWindows(clips, "v1").map { it.index } shouldBe listOf(1, 2, 3)
    }

    @Test
    fun a_video_with_no_clips_has_no_windows() {
        cloudRallyWindows(emptyList(), "v1") shouldBe emptyList()
    }

    @Test
    fun a_cloud_track_is_measured_over_cloud_windows() {
        // The ranking that keeps the track and the windows on one run. A phone
        // that also holds a device run's clips must not plot the cloud track's
        // medians over the device detector's boundaries.
        val cloud = listOf(RallyWindow(1, 0.0, 5.0))
        val device = listOf(RallyWindow(1, 1.0, 4.0))

        rallyWindowsFor(preferCloud = true, cloud = cloud, device = device) shouldBe cloud
    }

    @Test
    fun a_device_track_is_measured_over_device_windows() {
        val cloud = listOf(RallyWindow(1, 0.0, 5.0))
        val device = listOf(RallyWindow(1, 1.0, 4.0))

        rallyWindowsFor(preferCloud = false, cloud = cloud, device = device) shouldBe device
    }

    @Test
    fun a_cloud_track_whose_clips_have_not_loaded_falls_back_rather_than_showing_nothing() {
        // The clip cache refreshes asynchronously. An empty Base tab for the
        // couple of seconds before it lands would read as "this run measured
        // nothing", so the other source is used rather than none.
        val device = listOf(RallyWindow(1, 1.0, 4.0))

        rallyWindowsFor(preferCloud = true, cloud = emptyList(), device = device) shouldBe device
    }

    @Test
    fun a_device_track_with_no_kept_bounds_falls_back_to_the_cloud_windows() {
        val cloud = listOf(RallyWindow(1, 0.0, 5.0))

        rallyWindowsFor(preferCloud = false, cloud = cloud, device = emptyList()) shouldBe cloud
    }
}
