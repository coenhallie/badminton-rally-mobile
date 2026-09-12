package com.badmintontracker.android.localanalysis

import com.badmintontracker.analysis.geometry.Point
import com.badmintontracker.analysis.player.CourtSide
import com.badmintontracker.analysis.player.PlayerSample
import com.badmintontracker.analysis.player.PlayerSelection
import com.badmintontracker.analysis.player.PlayerTrack
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [PlayerTrackStore.has] answers "is there a track for this video that this app
 * can draw" from the header line alone, which is what a list can afford to ask
 * once per row.
 */
class PlayerTrackStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun store() = PlayerTrackStore(temp.root)

    private val track = PlayerTrack(
        samples = listOf(PlayerSample(frame = 0, courtPosition = Point(1.0, 2.0))),
        framesWithPose = 1,
        rejections = emptyMap(),
    )

    @Test
    fun a_saved_track_is_reported_present() {
        val s = store()
        s.save("e1", track, fps = 30.0)
        s.has("e1") shouldBe true
    }

    @Test
    fun a_video_that_was_never_analysed_has_no_track() {
        store().has("e1") shouldBe false
    }

    @Test
    fun each_video_has_its_own_track() {
        // The store is keyed per entry, so one analysed video must not make
        // every other row on the list claim a heatmap it cannot open.
        val s = store()
        s.save("e1", track, fps = 30.0)
        s.has("e2") shouldBe false
    }

    @Test
    fun a_track_from_an_older_format_is_not_offered() {
        // v1 tracks were built on a homography that could be metres off and on
        // hip positions two to three metres off. A row offering one would offer
        // a heatmap that is wrong rather than merely unloadable, so has() reads
        // the header and refuses, and load() agrees.
        val s = store()
        s.save("e1", track, fps = 30.0)
        // Found by walking rather than by rebuilding the store's path, so the
        // test does not hardcode a layout it cannot see (the companion is private).
        val onDisk = temp.root.walkTopDown().first { it.isFile && it.name.startsWith("e1") }
        onDisk.writeText("v1 30.0 1\n0,1.0,2.0,1\n")

        s.has("e1") shouldBe false
        s.load("e1") shouldBe null
    }

    @Test
    fun a_track_truncated_after_its_header_still_reports_present() {
        // The one remaining case has() and load() disagree on, pinned so the
        // KDoc's "accepted" is a tested claim: a file killed mid-write keeps
        // its header and is offered, and the heatmap screen says it could not
        // be loaded.
        val s = store()
        s.save("e1", track, fps = 30.0)
        val onDisk = temp.root.walkTopDown().first { it.isFile && it.name.startsWith("e1") }
        onDisk.writeText("v2 30.0 1\n0,1.0\n")

        s.has("e1") shouldBe true
        s.load("e1") shouldBe null
    }

    @Test
    fun the_cheap_check_and_the_full_read_agree_on_a_healthy_track() {
        // has() is allowed to be weaker than load() for a corrupted file, but
        // for one this store wrote itself the two must not disagree.
        val s = store()
        s.save("e1", track, fps = 30.0)
        s.has("e1") shouldBe (s.load("e1") != null)
    }

    @Test
    fun `a v2 file written by the previous build still loads`() {
        // The regression this whole task is shaped around. has() and load()
        // compared the header against ONE version string for exact equality,
        // so a naive bump would have refused every track already on every
        // phone, and the store's own KDoc names the recovery: a re-run that
        // costs half an hour.
        //
        // Written as bytes rather than through save(), deliberately. A fixture
        // produced by the new writer would pass this test even if the new
        // writer had stopped writing v2 entirely, which is exactly the bug.
        val store = PlayerTrackStore(temp.root)
        File(temp.root, "player-tracks").mkdirs()
        File(temp.root, "player-tracks/e1.track").writeText(
            "v2 29.97 120\n" +
                "0,1.5,2.5\n" +
                "1,1.6,2.6\n",
        )

        store.has("e1") shouldBe true
        val stored = store.load("e1")!!
        stored.fps shouldBe 29.97
        stored.tracks.size shouldBe 1
        stored.tracks[0].side shouldBe CourtSide.NEAR
        stored.tracks[0].track.framesWithPose shouldBe 120
        stored.tracks[0].track.samples.size shouldBe 2
        stored.near!!.samples[1].courtPosition shouldBe Point(1.6, 2.6)
    }

    @Test
    fun `a v1 file is still refused`() {
        // Unchanged and deliberate. v1 tracks were built on a homography that
        // could be metres off, so they are wrong rather than merely old, and
        // the reason the refusal exists does not reach v2.
        val store = PlayerTrackStore(temp.root)
        File(temp.root, "player-tracks").mkdirs()
        File(temp.root, "player-tracks/e1.track").writeText("v1 30.0 10\n0,1.0,2.0,1\n")

        store.has("e1") shouldBe false
        store.load("e1") shouldBe null
    }

    @Test
    fun `two tracks round trip through v3`() {
        val store = PlayerTrackStore(temp.root)
        val near = PlayerTrack(listOf(PlayerSample(0, Point(1.0, 2.0))), 10, emptyMap())
        val far = PlayerTrack(listOf(PlayerSample(0, Point(5.0, 11.0))), 10, emptyMap())

        store.saveAll(
            "e1", TrackSource.CLOUD,
            listOf(
                PlayerSelection(CourtSide.NEAR, near, emptyList()),
                PlayerSelection(CourtSide.FAR, far, emptyList()),
            ),
            fps = 30.0,
        )

        val stored = store.load("e1", TrackSource.CLOUD)!!
        stored.tracks.map { it.side } shouldBe listOf(CourtSide.NEAR, CourtSide.FAR)
        stored.tracks[0].track shouldBe near
        stored.tracks[1].track shouldBe far
        stored.fps shouldBe 30.0
    }

    @Test
    fun `the cloud and local subtrees do not see each other`() {
        // Separate directories rather than a flag in one file, so
        // skeletonAction's "a completed run is the new truth for its entry"
        // cannot reach a cloud artifact. A device run that asked for rallies
        // only must not be able to delete a cloud heatmap.
        val store = PlayerTrackStore(temp.root)
        store.save("e1", PlayerTrack(listOf(PlayerSample(0, Point(1.0, 2.0))), 5, emptyMap()), 30.0)

        store.has("e1", TrackSource.LOCAL) shouldBe true
        store.has("e1", TrackSource.CLOUD) shouldBe false
    }

    @Test
    fun `a side with no samples still round trips as an empty track`() {
        // A match played on one half of the court is a real thing, and an
        // empty far track says so. Dropping it would make "nobody was there"
        // indistinguishable from "this file predates the far player".
        val store = PlayerTrackStore(temp.root)
        store.saveAll(
            "e1", TrackSource.CLOUD,
            listOf(
                PlayerSelection(CourtSide.NEAR, PlayerTrack(listOf(PlayerSample(0, Point(1.0, 2.0))), 10, emptyMap()), emptyList()),
                PlayerSelection(CourtSide.FAR, PlayerTrack(emptyList(), 10, emptyMap()), emptyList()),
            ),
            fps = 30.0,
        )

        val stored = store.load("e1", TrackSource.CLOUD)!!
        stored.tracks.size shouldBe 2
        stored.tracks[1].track.samples shouldBe emptyList()
        stored.tracks[1].track.framesWithPose shouldBe 10
    }
}
