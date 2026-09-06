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
 * [PlayerTrackStore.has] answers "is there a track for this video" without
 * reading one, which is what a list can afford to ask once per row.
 */
class PlayerTrackStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun store() = PlayerTrackStore(temp.root)

    private val track = PlayerTrack(
        samples = listOf(PlayerSample(frame = 0, courtPosition = Point(1.0, 2.0), onAnkles = true)),
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
    fun a_track_too_damaged_to_read_still_reports_present() {
        // The one behaviour has() and load() disagree on, pinned deliberately so
        // that the divergence its KDoc argues for is a tested claim rather than
        // a comment. A row built on has() keeps offering a heatmap that cannot
        // be drawn; the KDoc says what the user sees and why that was accepted.
        val s = store()
        s.save("e1", track, fps = 30.0)
        // Found by walking rather than by rebuilding the store's path, so the
        // test does not hardcode a layout it cannot see (the companion is private).
        val onDisk = temp.root.walkTopDown().first { it.isFile && it.name.startsWith("e1") }
        onDisk.writeText("this is not a track\n")

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
