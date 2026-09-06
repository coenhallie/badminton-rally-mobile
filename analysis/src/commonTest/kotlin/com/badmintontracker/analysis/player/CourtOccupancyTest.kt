package com.badmintontracker.analysis.player

import com.badmintontracker.analysis.geometry.Point
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CourtOccupancyTest {

    @Test
    fun it_accumulates_seconds_rather_than_visits() {
        val occupancy = CourtOccupancy()
        occupancy.add(Point(3.0, 10.0), 0.5)
        occupancy.add(Point(3.0, 10.0), 1.5)
        assertEquals(2.0, occupancy.totalSeconds, 1e-9)
        assertEquals(2.0, occupancy.grid().flatten().max(), 1e-9)
    }

    @Test
    fun the_same_path_gives_the_same_map_at_any_sampling_rate() {
        // The reason occupancy is time-weighted rather than counted. A player
        // standing still for two seconds must read the same whether the pose
        // model ran every frame or every sixth, otherwise the map changes
        // meaning when the sampling does.
        val dense = CourtOccupancy()
        dense.addAll((0 until 60).map { PlayerSample(it, Point(3.0, 10.0), true) }, fps = 30.0)

        val sparse = CourtOccupancy()
        sparse.addAll((0 until 60 step 6).map { PlayerSample(it, Point(3.0, 10.0), true) }, fps = 30.0)

        // Both cover frames 0..59; the last sample is worth one frame in each.
        assertTrue(
            abs(dense.totalSeconds - sparse.totalSeconds) < 0.2,
            "dense ${dense.totalSeconds}s vs sparse ${sparse.totalSeconds}s",
        )
    }

    @Test
    fun a_gap_in_the_track_is_not_credited_to_the_last_known_position() {
        // A sample covers the gap to the NEXT sample, so a player who was lost
        // for a second does not bank a second of standing still. This one is
        // asymmetric on purpose: crediting the gap would put a bright spot
        // exactly where tracking failed.
        val occupancy = CourtOccupancy()
        occupancy.addAll(
            listOf(
                PlayerSample(0, Point(1.0, 3.0), true),
                PlayerSample(90, Point(5.0, 11.0), true),
            ),
            fps = 30.0,
        )
        val grid = occupancy.grid()
        val first = grid.flatten().filter { it > 0.0 }.sortedDescending()
        assertEquals(2, first.size, "two samples, two cells")
        // 3 seconds to the first, 1 frame to the last.
        assertEquals(3.0, first[0], 1e-9)
    }

    @Test
    fun positions_off_the_grid_are_dropped_rather_than_clamped() {
        // Clamping would pile every bad projection onto the court edge and read
        // as a player who loved the tramlines.
        val occupancy = CourtOccupancy()
        occupancy.add(Point(-50.0, 10.0), 1.0)
        occupancy.add(Point(3.0, 500.0), 1.0)
        assertEquals(0.0, occupancy.totalSeconds, 1e-9)
    }

    @Test
    fun smoothing_spreads_time_without_inventing_or_losing_it() {
        val occupancy = CourtOccupancy()
        occupancy.add(Point(3.0, 6.7), 10.0)
        val raw = occupancy.grid().flatten()
        val smooth = occupancy.smoothed().flatten()

        assertEquals(raw.sum(), smooth.sum(), 0.2, "a blur must conserve total time")
        assertTrue(smooth.max() < raw.max(), "the peak must spread")
        assertTrue(smooth.count { it > 1e-6 } > raw.count { it > 1e-6 }, "more cells must be touched")
    }

    @Test
    fun the_grid_covers_the_court_plus_its_margin() {
        val occupancy = CourtOccupancy(cellSizeM = 0.25)
        // 6.1 + 4.0 wide, 13.4 + 4.0 long, at quarter-metre cells.
        assertEquals(40, occupancy.columns)
        assertEquals(70, occupancy.rows)
    }
}
