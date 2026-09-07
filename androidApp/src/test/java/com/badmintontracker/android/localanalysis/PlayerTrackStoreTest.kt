package com.badmintontracker.android.localanalysis

import com.badmintontracker.analysis.geometry.Point
import com.badmintontracker.analysis.player.PlayerSample
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
}
