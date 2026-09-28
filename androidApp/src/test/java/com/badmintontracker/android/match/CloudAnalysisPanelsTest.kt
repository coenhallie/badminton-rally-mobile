package com.badmintontracker.android.match

import com.badmintontracker.analysis.geometry.Point
import com.badmintontracker.analysis.player.CourtSide
import com.badmintontracker.analysis.player.PlayerSample
import com.badmintontracker.analysis.player.PlayerSelection
import com.badmintontracker.analysis.player.PlayerTrack
import com.badmintontracker.analysis.player.RallyWindow
import com.badmintontracker.android.localanalysis.PlayerTrackStore
import com.badmintontracker.android.localanalysis.TrackSource
import com.badmintontracker.shared.analytics.AnalyticsPanel
import com.badmintontracker.shared.analytics.availablePanels
import com.badmintontracker.shared.analytics.cloudRallyWindows
import com.badmintontracker.shared.analytics.rallyWindowsFor
import com.badmintontracker.shared.model.RallyClip
import io.kotest.matchers.shouldBe
import kotlinx.datetime.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * What AnalysisSection composes, driven through the same pieces in the
 * same order.
 *
 * The Base tab's absence for a cloud analysis was a WIRING defect, not a logic
 * one: every part worked and the screen fed `hasBoundedClips` from the device
 * clip index, which a cloud run never writes. So the parts are assembled here
 * rather than asserted one at a time, over a real store on a real directory.
 *
 * Two assumptions this pins that nothing else does. That `PlayerTrackStore.load`
 * stamps the source it read from, without which the screen cannot tell a cloud
 * track from a local one and pairs the wrong windows to it. And that a clip's
 * `videoId` is the entry id the stores are keyed by - true because
 * `AnalyzeCoordinator` creates the videos row with `entry.id`, and the whole
 * class of bug this branch fixed was an id that used to be one thing being
 * quietly asked of another.
 */
class CloudAnalysisPanelsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val entryId = "v1"

    private fun track(samples: Int) = PlayerTrack(
        samples = (0 until samples).map { PlayerSample(it, Point(it * 0.1, 3.05)) },
        framesWithPose = samples,
        rejections = emptyMap(),
    )

    private fun clip(index: Int, start: Float, end: Float) = RallyClip(
        id = "c-$index", videoId = entryId, ownerId = "user-self", rallyIndex = index,
        startTimestamp = start, endTimestamp = end, durationSeconds = end - start,
        clipStoragePath = "user-self/$entryId/rally_$index.mp4", annotationCount = 0,
        createdAt = Instant.fromEpochMilliseconds(0),
    )

    /** Exactly what `measureMovement` -> `saveCloudAnalysis` leaves on disk. */
    private fun installCloudAnalysis(): PlayerTrackStore {
        val store = PlayerTrackStore(temp.root)
        store.saveAll(
            entryId = entryId,
            source = TrackSource.CLOUD,
            selections = listOf(
                PlayerSelection(CourtSide.NEAR, track(4), emptyList()),
                PlayerSelection(CourtSide.FAR, track(3), emptyList()),
            ),
            fps = 30.0,
        )
        return store
    }

    /** The panel set AnalysisSection builds, from the inputs it builds them from. */
    private fun panelsFor(
        store: PlayerTrackStore,
        cloudClips: List<RallyClip>,
        hasSkeleton: Boolean = false,
        hasVideo: Boolean = false,
    ): List<AnalyticsPanel> {
        val stored = store.load(entryId, TrackSource.CLOUD) ?: store.load(entryId, TrackSource.LOCAL)
        val windows = rallyWindowsFor(
            preferCloud = stored?.source == TrackSource.CLOUD,
            cloud = cloudRallyWindows(cloudClips, entryId),
            device = store.loadClips(entryId).map { RallyWindow(it.index, it.startSeconds, it.endSeconds) },
        )
        return availablePanels(
            hasTrack = stored != null,
            hasBoundedClips = windows.any { it.isBounded },
            hasSkeleton = hasSkeleton,
            hasVideo = hasVideo,
        )
    }

    @Test
    fun a_cloud_analysis_offers_the_heatmap_and_the_base_position() {
        // The headline: a coach who chose "analyze in the cloud" gets the same
        // two panels a device run gives, on a phone that cut no clips itself.
        val store = installCloudAnalysis()

        panelsFor(store, cloudClips = listOf(clip(1, 4f, 21.5f), clip(2, 30f, 44f))) shouldBe
            listOf(AnalyticsPanel.Heatmap, AnalyticsPanel.Base)
    }

    @Test
    fun the_cloud_track_is_read_back_as_a_cloud_track() {
        // Everything above hangs off this. If load() did not stamp the source,
        // the screen would pair the device's windows to the cloud's track and
        // the Base tab would go missing again - silently, since both are lists
        // of rallies.
        val store = installCloudAnalysis()

        store.load(entryId, TrackSource.CLOUD)?.source shouldBe TrackSource.CLOUD
        store.load(entryId, TrackSource.CLOUD)?.tracks?.map { it.side } shouldBe
            listOf(CourtSide.NEAR, CourtSide.FAR)
    }

    @Test
    fun the_base_tab_waits_for_the_clip_cache_rather_than_borrowing() {
        // The beat between the screen opening and rally_clips landing. The tab
        // is not offered yet; it must not appear measured over some other run's
        // boundaries.
        val store = installCloudAnalysis()

        panelsFor(store, cloudClips = emptyList()) shouldBe listOf(AnalyticsPanel.Heatmap)
    }

    @Test
    fun another_videos_clips_do_not_furnish_this_ones_base_tab() {
        // The filter in cloudRallyWindows, reached the way the screen reaches
        // it: the cache holds every clip the coach has.
        val store = installCloudAnalysis()
        val elsewhere = clip(1, 0f, 9f).copy(videoId = "some-other-video")

        panelsFor(store, cloudClips = listOf(elsewhere)) shouldBe listOf(AnalyticsPanel.Heatmap)
    }

    @Test
    fun the_skeleton_joins_them_only_while_the_video_is_still_here() {
        // The one place parity stops, and it is honest: a cloud run releases
        // the footage, and the skeleton overlays playback. An annotated video
        // is kept as ANALYZED, and then all three are offered.
        val store = installCloudAnalysis()
        val clips = listOf(clip(1, 4f, 21.5f))

        panelsFor(store, clips, hasSkeleton = true, hasVideo = false) shouldBe
            listOf(AnalyticsPanel.Heatmap, AnalyticsPanel.Base)
        panelsFor(store, clips, hasSkeleton = true, hasVideo = true) shouldBe
            listOf(AnalyticsPanel.Heatmap, AnalyticsPanel.Base, AnalyticsPanel.Skeleton)
    }
}
