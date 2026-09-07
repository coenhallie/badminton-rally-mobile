package com.badmintontracker.analysis.player

import com.badmintontracker.analysis.corpus.readResourceBytesOrNull
import com.badmintontracker.analysis.geometry.CourtKeypoints
import com.badmintontracker.analysis.geometry.Point
import com.badmintontracker.analysis.raw.RawBox
import com.badmintontracker.analysis.raw.RawFrame
import com.badmintontracker.analysis.raw.RawHeader
import com.badmintontracker.analysis.raw.RawInference
import com.badmintontracker.analysis.raw.RawKeypoint
import com.badmintontracker.analysis.raw.RawPerson
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Real device pose output through the selector, with the court as the cloud
 * stored it.
 *
 * The resource is what `PoseParityDumpTest` wrote on an arm64 emulator for the
 * first 40 frames of corpus video 743d7fb1: every person the deployed graph
 * found, in source pixels, matched to the host run of the same graph to a
 * median of 0.00px (`tools/models/reports/heatmap-accuracy-2026-09-07.md` §4).
 * The unit tests above build their people by hand; this is the one place the
 * selector meets keypoints a model actually emitted, and it is what a change
 * to a gate or a threshold has to keep passing.
 */
class DevicePoseDumpTest {

    private val keypoints = CourtKeypoints(
        topLeft = Point(649.5, 484.8),
        topRight = Point(1277.3, 481.2),
        bottomRight = Point(1579.4, 998.6),
        bottomLeft = Point(360.0, 1004.0),
        netLeft = Point(550.0, 663.9),
        netRight = Point(1382.2, 665.7),
        serviceLineNearLeft = Point(504.8, 743.5),
        serviceLineNearRight = Point(1422.0, 738.1),
        serviceLineFarLeft = Point(588.0, 595.2),
        serviceLineFarRight = Point(1338.8, 595.2),
        centerNear = Point(966.1, 593.4),
        centerFar = Point(966.1, 736.3),
    )

    private fun frames(): List<PoseFrame>? {
        val text = readResourceBytesOrNull("/pose/743d7fb1-device-first40.csv")
            ?.decodeToString() ?: return null
        val byFrame = LinkedHashMap<Int, MutableList<PosePerson>>()
        text.lineSequence().drop(1).filter { it.isNotBlank() }.forEach { line ->
            val f = line.split(',')
            val frame = f[0].toInt()
            val keypoints = (0 until Coco.COUNT).map { k ->
                Point(f[3 + k * 3].toDouble(), f[4 + k * 3].toDouble())
            }
            val confidence = (0 until Coco.COUNT).map { k -> f[5 + k * 3].toFloat() }
            byFrame.getOrPut(frame) { ArrayList() }.add(PosePerson(f[2].toFloat(), keypoints, confidence))
        }
        return byFrame.map { (frame, people) -> PoseFrame(frame, people) }
    }

    @Test
    fun the_device_output_becomes_a_near_player_track_on_the_near_court() {
        // Native targets cannot read the resource; the JVM run is the check.
        val frames = frames() ?: return
        assertTrue(frames.size == 40, "expected 40 frames, got ${frames.size}")

        val selector = NearPlayerSelector(keypoints, 1920.0, 1080.0)
        assertTrue(selector.usable, "the real marks must give a usable court, residual ${selector.courtFitResidualM}")
        assertTrue(selector.courtFitResidualM!! < 0.4, "residual ${selector.courtFitResidualM} m")

        val results = frames.map { selector.select(it) }
        val samples = results.mapNotNull { it.sample }

        // Two players on court in every one of these frames, and the near one
        // is standing rather than diving, so ankles-only must still find them
        // in most frames. Well under the 93% measured on the host, so a real
        // regression fails hard while a marginal frame or two does not.
        assertTrue(samples.size > frames.size / 2, "near player found in ${samples.size} of ${frames.size} frames")

        // The near half is the larger y in court space under this camera. If
        // the net-line side test or the pair resolution were inverted, this is
        // what would catch it.
        val nearHalf = samples.count { it.courtPosition.y > 6.7 }
        assertTrue(nearHalf == samples.size, "$nearHalf of ${samples.size} samples on the near half")

        // Inside the court plus its margin, by construction; and actually on
        // the court for most of them, which the margin alone would not prove.
        val onCourt = samples.count { it.courtPosition.x in 0.0..6.1 && it.courtPosition.y in 6.7..13.4 }
        assertTrue(onCourt > samples.size / 2, "$onCourt of ${samples.size} samples inside the near court")

        val occupancy = CourtOccupancy()
        occupancy.addAll(samples, fps = 25.0)
        assertTrue(occupancy.totalSeconds > 0.0)
    }

    @Test
    fun the_device_output_yields_a_pose_for_every_sample() {
        val frames = frames() ?: return
        val raw = RawInference(
            header = RawHeader(1, 25.0, frames.size, 1920, 1080, "device"),
            frames = frames.map { f ->
                RawFrame(
                    frame = f.frame,
                    timestamp = f.frame / 25.0,
                    shuttle = null,
                    boxes = emptyList(),
                    persons = f.people.map { p ->
                        RawPerson(
                            box = RawBox(0, p.boxConfidence, 0f, 0f, 0f, 0f),
                            keypoints = p.keypoints.mapIndexed { k, pt ->
                                RawKeypoint(
                                    pt.x.toFloat(), pt.y.toFloat(), p.keypointConfidence[k],
                                )
                            },
                        )
                    },
                )
            },
        )
        val selection = selectNearPlayer(raw, keypoints)
        assertTrue(selection.track.samples.isNotEmpty())
        assertTrue(
            selection.poses.map { it.frame } == selection.track.samples.map { it.frame },
            "poses and samples must be at the same frames",
        )
        assertTrue(selection.poses.all { it.keypoints.size == Coco.COUNT && it.confidence.size == Coco.COUNT })
        // The ankles that produced the position are confident in every pose.
        assertTrue(
            selection.poses.all {
                it.confidence[Coco.LEFT_ANKLE] >= NearPlayerSelector.MIN_KEYPOINT_CONFIDENCE &&
                    it.confidence[Coco.RIGHT_ANKLE] >= NearPlayerSelector.MIN_KEYPOINT_CONFIDENCE
            },
        )
    }
}
