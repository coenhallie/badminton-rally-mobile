package com.badmintontracker.analysis.compare

import com.badmintontracker.analysis.geometry.CourtKeypoints
import com.badmintontracker.analysis.geometry.Point
import com.badmintontracker.analysis.raw.RawBox
import com.badmintontracker.analysis.raw.RawFrame
import com.badmintontracker.analysis.raw.RawHeader
import com.badmintontracker.analysis.raw.RawInference
import com.badmintontracker.analysis.raw.RawKeypoint
import com.badmintontracker.analysis.raw.RawPerson
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class CorpusRunExportTest {

    // 743d7fb1's marks, as NearPlayerSelectorTest uses them.
    private val marks = CourtKeypoints(
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

    /** Ankles at ([x] -/+ 10, [y]); the rest of the body 90 to 100 px above them. */
    private fun person(x: Float, y: Float): RawPerson {
        val kps = MutableList(17) { RawKeypoint(x, y - 100f, 0.9f) }
        kps[11] = RawKeypoint(x - 10f, y - 60f, 0.9f)
        kps[12] = RawKeypoint(x + 10f, y - 60f, 0.9f)
        kps[15] = RawKeypoint(x - 10f, y, 0.9f)
        kps[16] = RawKeypoint(x + 10f, y, 0.9f)
        return RawPerson(RawBox(0, 0.9f, x - 20f, y - 110f, x + 20f, y + 5f), kps)
    }

    private fun raw() = RawInference(
        header = RawHeader(2, 30.0, 2, 1920, 1080, "test"),
        frames = listOf(
            RawFrame(1, 0.0, null, emptyList(), listOf(person(966f, 900f), person(966f, 560f))),
            RawFrame(2, 0.0333, null, emptyList(), emptyList()),
        ),
    )

    @Test
    fun every_frame_is_listed_with_its_timestamp_and_no_camera_when_none_was_followed() {
        val frames = exportRun(raw(), marks).getValue("frames").jsonArray
        assertEquals(2, frames.size)
        assertEquals(1, frames[0].jsonObject.getValue("k").jsonPrimitive.int)
        assertEquals(0.0333, frames[1].jsonObject.getValue("timestamp").jsonPrimitive.double)
        assertEquals(JsonNull, frames[0].jsonObject.getValue("camera"))
    }

    @Test
    fun each_side_lists_the_phone_s_pick_with_its_ankle_midpoint_in_pixels() {
        val sides = exportRun(raw(), marks).getValue("sides").jsonObject
        val near = sides.getValue("NEAR").jsonObject.getValue("samples").jsonArray
        val far = sides.getValue("FAR").jsonObject.getValue("samples").jsonArray
        assertEquals(1, near.size)
        assertEquals(1, far.size)
        val nearPick = near[0].jsonObject
        assertEquals(1, nearPick.getValue("frame").jsonPrimitive.int)
        val ankle = nearPick.getValue("ankle").jsonArray.map { it.jsonPrimitive.double }
        assertEquals(listOf(966.0, 900.0), ankle)
        val farAnkle = far[0].jsonObject.getValue("ankle").jsonArray.map { it.jsonPrimitive.double }
        assertEquals(listOf(966.0, 560.0), farAnkle)
    }

    @Test
    fun each_side_carries_its_pose_frame_count() {
        val sides = exportRun(raw(), marks).getValue("sides").jsonObject
        assertEquals(1, sides.getValue("NEAR").jsonObject.getValue("framesWithPose").jsonPrimitive.int)
    }
}
