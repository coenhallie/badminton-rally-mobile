package com.badmintontracker.analysis.player

import com.badmintontracker.analysis.geometry.Point
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MovementProfileTest {

    private fun track(vararg samples: PlayerSample) = PlayerTrack(samples.toList(), samples.size, emptyMap())

    private fun standing(at: Point, fromFrame: Int, seconds: Double, fps: Double = 30.0): List<PlayerSample> =
        (0 until (seconds * fps).toInt()).map { PlayerSample(fromFrame + it, at) }

    @Test
    fun near_and_far_sides_mirror_depth_and_lateral_coordinates() {
        // Near: (3.55, 10.68) -> 2.00 m behind near service line (8.68), +0.50 m player right of centre (3.05).
        // Far:  (2.55,  2.72) -> 2.00 m behind far service line (4.72),  +0.50 m player right facing net.
        val nearSamples = standing(Point(3.55, 10.68), fromFrame = 0, seconds = 2.0) +
            standing(Point(3.55, 10.68), fromFrame = 90, seconds = 2.0)
        val farSamples = standing(Point(2.55, 2.72), fromFrame = 0, seconds = 2.0) +
            standing(Point(2.55, 2.72), fromFrame = 90, seconds = 2.0)
        val windows = listOf(RallyWindow(1, 0.0, 2.0), RallyWindow(2, 3.0, 5.0))

        val near = movementProfile(track(*nearSamples.toTypedArray()), 30.0, windows, CourtSide.NEAR)
        val far = movementProfile(track(*farSamples.toTypedArray()), 30.0, windows, CourtSide.FAR)

        for (profile in listOf(near, far)) {
            assertEquals(2, profile.countedRallies)
            assertEquals(2.0, assertNotNull(profile.overallBehindServiceLineM), 1e-6)
            assertEquals(0.5, assertNotNull(profile.overallLateralFromCentreM), 1e-6)
            assertEquals(0.0, profile.frontShare, 1e-6)
            assertEquals(1.0, profile.midShare, 1e-6)
            assertEquals(0.0, profile.rearShare, 1e-6)
            assertEquals(0.0, profile.leftShare, 1e-6)
            assertEquals(1.0, profile.rightShare, 1e-6)
        }
    }

    @Test
    fun zone_shares_split_front_mid_rear_and_left_right_with_gap_capping() {
        // 10 fps, 1 rally spanning 0..3 s (frames 0..30).
        // 1 s front-left (depth 1.3 < 2.2, lat -1.05), 1 s mid-right (depth 3.3, lat +0.95), 1 s rear-right (depth 5.3 > 4.5, lat +0.95).
        val samples = standing(Point(2.0, 8.0), fromFrame = 0, seconds = 1.0, fps = 10.0) +
            standing(Point(4.0, 10.0), fromFrame = 10, seconds = 1.0, fps = 10.0) +
            standing(Point(4.0, 12.0), fromFrame = 20, seconds = 1.0, fps = 10.0)
        val profile = movementProfile(
            track = track(*samples.toTypedArray()),
            fps = 10.0,
            windows = listOf(RallyWindow(1, 0.0, 3.0)),
            side = CourtSide.NEAR,
        )

        assertEquals(1.0, profile.frontShare + profile.midShare + profile.rearShare, 1e-6)
        assertEquals(1.0, profile.leftShare + profile.rightShare, 1e-6)
        assertEquals(1.0 / 3.0, profile.frontShare, 0.05)
        assertEquals(1.0 / 3.0, profile.midShare, 0.05)
        assertEquals(1.0 / 3.0, profile.rearShare, 0.05)
        assertEquals(1.0 / 3.0, profile.leftShare, 0.05)
        assertEquals(2.0 / 3.0, profile.rightShare, 0.05)

        // Tracking dropout of 2.0 s inside a rally must cap the last seen sample's dwell at 0.5 s.
        val gapped = standing(Point(2.0, 8.0), fromFrame = 0, seconds = 1.0, fps = 10.0) +
            standing(Point(4.0, 12.0), fromFrame = 30, seconds = 1.0, fps = 10.0)
        val gappedProfile = movementProfile(
            track = track(*gapped.toTypedArray()),
            fps = 10.0,
            windows = listOf(RallyWindow(1, 0.0, 4.0)),
            side = CourtSide.NEAR,
        )
        // Front block: 9 frames of 0.1 s + 1 capped gap of 0.5 s = 1.4 s.
        // Rear block: 9 frames of 0.1 s + last sample 0.1 s = 1.0 s. Total = 2.4 s (NOT 3.9 s).
        assertEquals(2.4, gappedProfile.totalRallySeconds, 1e-6)
    }

    @Test
    fun base_spread_and_depth_drift_require_minimum_rallies() {
        val fiveDepths = listOf(1.0, 1.2, 1.4, 1.6, 1.8)
        val fiveSamples = fiveDepths.flatMapIndexed { i, behind ->
            standing(Point(3.05, 8.68 + behind), fromFrame = i * 90, seconds = 2.0)
        }
        val fiveWindows = fiveDepths.indices.map { i -> RallyWindow(i + 1, i * 3.0, i * 3.0 + 2.0) }
        val five = movementProfile(track(*fiveSamples.toTypedArray()), 30.0, fiveWindows, CourtSide.NEAR)

        assertEquals(5, five.countedRallies)
        assertEquals(0.2, assertNotNull(five.baseSpreadM), 1e-6)
        assertNull(five.baseDepthDriftM)

        val sixDepths = listOf(1.0, 1.0, 1.5, 1.5, 2.0, 2.0)
        val sixSamples = sixDepths.flatMapIndexed { i, behind ->
            standing(Point(3.05, 8.68 + behind), fromFrame = i * 90, seconds = 2.0)
        }
        val sixWindows = sixDepths.indices.map { i -> RallyWindow(i + 1, i * 3.0, i * 3.0 + 2.0) }
        val six = movementProfile(track(*sixSamples.toTypedArray()), 30.0, sixWindows, CourtSide.NEAR)

        assertEquals(6, six.countedRallies)
        assertEquals(1.0, assertNotNull(six.baseDepthDriftM), 1e-6)
    }

    @Test
    fun path_length_and_peak_speed_and_late_speed_drop() {
        // 6 rallies of 2 s at 20 fps: move back and forth cleanly at known speeds (4, 4, 3, 3, 2, 2 m/s).
        val fps = 20.0
        val speeds = listOf(4.0, 4.0, 3.0, 3.0, 2.0, 2.0)
        val samples = ArrayList<PlayerSample>()
        val windows = ArrayList<RallyWindow>()
        speeds.forEachIndexed { r, speed ->
            val startFrame = r * 60
            // 40 frames (0..39 -> 1.95 s). Move straight along y in [7.0, 13.0] for first 1.0 s, then reverse for 1.0 s.
            for (f in 0 until 40) {
                val t = f / fps
                val dist = if (t <= 1.0) speed * t else speed * (2.0 - t)
                val y = 8.0 + dist
                samples += PlayerSample(startFrame + f, Point(3.05, y))
            }
            windows += RallyWindow(r + 1, startFrame / fps, (startFrame + 39) / fps)
        }

        val profile = movementProfile(track(*samples.toTypedArray()), fps, windows, CourtSide.NEAR)

        assertEquals(6, profile.countedRallies)
        assertEquals(4.0, assertNotNull(profile.earlyPeakSpeedMps), 0.15)
        assertEquals(2.0, assertNotNull(profile.latePeakSpeedMps), 0.15)
        assertEquals(0.5, assertNotNull(profile.speedDropFraction), 0.05)
        assertEquals(3.0, assertNotNull(profile.medianPeakSpeedMps), 0.15)
        // Median rally moves at 3.0 m/s for ~1.95 s (~5.85 m resampled at 0.25 s).
        val path = assertNotNull(profile.medianRallyPathM)
        assertTrue(path in 5.0..6.2, "Expected median rally path near 5.7 m, got $path")
    }

    @Test
    fun dropout_and_teleport_do_not_fabricate_path_or_speed() {
        val fps = 20.0
        // Stand at (3.0, 9.0) for 1 s (frames 0..19), disappear for 1.0 s (frames 20..39), stand at (3.0, 12.5) for 1 s (frames 40..59).
        val gapped = standing(Point(3.0, 9.0), fromFrame = 0, seconds = 1.0, fps = fps) +
            standing(Point(3.0, 12.5), fromFrame = 40, seconds = 1.0, fps = fps)
        val profile = movementProfile(
            track = track(*gapped.toTypedArray()),
            fps = fps,
            windows = listOf(RallyWindow(1, 0.0, 3.0)),
            side = CourtSide.NEAR,
        )

        assertEquals(0.0, assertNotNull(profile.medianRallyPathM), 1e-6)
        assertEquals(0.0, assertNotNull(profile.medianPeakSpeedMps), 1e-6)

        // Implausible 6.0 m jump over 0.5 s (12 m/s > MAX_PLAUSIBLE_SPEED_MPS = 8.0) must be rejected.
        val glitch = (0 until 40).map { f ->
            // Stationary except instant teleport at frame 20 so every 0.5 s window crossing frame 20 reads 12 m/s.
            val y = if (f < 20) 7.0 else 13.0
            PlayerSample(f, Point(3.05, y))
        }
        val glitchProfile = movementProfile(
            track = track(*glitch.toTypedArray()),
            fps = fps,
            windows = listOf(RallyWindow(1, 0.0, 2.0)),
            side = CourtSide.NEAR,
        )
        assertEquals(0.0, assertNotNull(glitchProfile.medianPeakSpeedMps), 1e-6)
    }

    @Test
    fun unsorted_and_duplicate_vfr_frames_do_not_divide_by_zero() {
        val fps = 20.0
        val base = (0 until 40).flatMap { f ->
            val p = Point(3.05, 9.5 + (f % 10) * 0.05)
            // Duplicate every 4th frame (simulating VFR duplicates) and shuffle.
            if (f % 4 == 0) listOf(PlayerSample(f, p), PlayerSample(f, p)) else listOf(PlayerSample(f, p))
        }.reversed()

        val profile = movementProfile(
            track = track(*base.toTypedArray()),
            fps = fps,
            windows = listOf(RallyWindow(1, 0.0, 2.0)),
            side = CourtSide.NEAR,
        )

        assertEquals(1, profile.countedRallies)
        assertTrue(profile.rallyCoverage.isFinite() && profile.rallyCoverage > 0.0)
        assertTrue(assertNotNull(profile.medianRallyPathM).isFinite())
        assertTrue(assertNotNull(profile.medianPeakSpeedMps).isFinite())
        assertEquals(1.0, profile.frontShare + profile.midShare + profile.rearShare, 1e-6)
    }

    @Test
    fun degenerate_inputs_return_empty_zeroed_profile() {
        val samples = standing(Point(3.05, 10.0), fromFrame = 0, seconds = 2.0)
        val cases = listOf(
            movementProfile(track(*samples.toTypedArray()), 0.0, listOf(RallyWindow(1, 0.0, 2.0)), CourtSide.NEAR),
            movementProfile(track(), 30.0, listOf(RallyWindow(1, 0.0, 2.0)), CourtSide.NEAR),
            movementProfile(track(*samples.toTypedArray()), 30.0, listOf(RallyWindow(1, 0.0, 0.0)), CourtSide.NEAR),
        )

        for (p in cases) {
            assertEquals(0, p.countedRallies)
            assertEquals(0.0, p.rallyCoverage, 1e-9)
            assertEquals(0.0, p.totalRallySeconds, 1e-9)
            assertNull(p.overallBehindServiceLineM)
            assertNull(p.overallLateralFromCentreM)
            assertNull(p.baseSpreadM)
            assertNull(p.baseDepthDriftM)
            assertNull(p.medianRallyPathM)
            assertNull(p.medianPeakSpeedMps)
            assertNull(p.earlyPeakSpeedMps)
            assertNull(p.latePeakSpeedMps)
            assertNull(p.speedDropFraction)
            assertEquals(0.0, p.frontShare, 1e-9)
            assertEquals(0.0, p.midShare, 1e-9)
            assertEquals(0.0, p.rearShare, 1e-9)
            assertEquals(0.0, p.leftShare, 1e-9)
            assertEquals(0.0, p.rightShare, 1e-9)
        }
    }
}
