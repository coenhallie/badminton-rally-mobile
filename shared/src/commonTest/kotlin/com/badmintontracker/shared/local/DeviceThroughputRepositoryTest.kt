package com.badmintontracker.shared.local

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeviceThroughputRepositoryTest {

    @Test
    fun it_starts_from_the_seed_and_says_so() {
        val repo = DeviceThroughputRepository(MapSettings())
        assertEquals(DeviceThroughput.SEED_BASE_MS, repo.throughput.value.baseMsPerFrame)
        assertFalse(repo.throughput.value.measured, "a seed must not claim to be measured")
    }

    @Test
    fun the_first_real_run_replaces_the_seed_outright() {
        // A slower phone must not be told an S23's number for several runs.
        // Blending here would keep the estimate optimistic exactly when it
        // matters most, on the device that needs the warning.
        val repo = DeviceThroughputRepository(MapSettings())
        repo.record(baseMsPerFrame = 900.0, poseMsPerFrame = 800.0, cutMsPerClipSecond = 400.0)
        assertEquals(900.0, repo.throughput.value.baseMsPerFrame)
        assertTrue(repo.throughput.value.measured)
    }

    @Test
    fun later_runs_move_the_estimate_without_becoming_it() {
        val repo = DeviceThroughputRepository(MapSettings())
        repo.record(1000.0, null, null)
        repo.record(500.0, null, null)
        // Between the two, nearer the older: one hot or idle run should not
        // make the number swing for a reason the user cannot see.
        val base = repo.throughput.value.baseMsPerFrame
        assertTrue(base in 700.0..900.0, "expected a smoothed value, got $base")
    }

    @Test
    fun a_phase_one_run_does_not_record_pose_as_free() {
        val repo = DeviceThroughputRepository(MapSettings())
        val before = repo.throughput.value.poseMsPerFrame
        repo.record(300.0, poseMsPerFrame = null, cutMsPerClipSecond = null)
        assertEquals(before, repo.throughput.value.poseMsPerFrame)
    }

    @Test
    fun it_survives_a_restart() {
        val settings = MapSettings()
        DeviceThroughputRepository(settings).record(777.0, 666.0, 55.0)
        val reopened = DeviceThroughputRepository(settings)
        assertEquals(777.0, reopened.throughput.value.baseMsPerFrame)
        assertEquals(666.0, reopened.throughput.value.poseMsPerFrame)
        assertTrue(reopened.throughput.value.measured)
    }
}
