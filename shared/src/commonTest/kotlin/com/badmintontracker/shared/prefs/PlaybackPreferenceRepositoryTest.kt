package com.badmintontracker.shared.prefs

import app.cash.turbine.test
import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class PlaybackPreferenceRepositoryTest {

    @Test
    fun defaults_to_ten_second_skip_at_normal_speed() {
        val repo = PlaybackPreferenceRepository(MapSettings())
        assertEquals(10, repo.skipSeconds.value)
        assertEquals(1.0f, repo.speed.value)
    }

    @Test
    fun settings_persist_across_instances() {
        val settings = MapSettings()
        PlaybackPreferenceRepository(settings).apply {
            setSkipSeconds(30)
            setSpeed(0.25f)
        }
        val reloaded = PlaybackPreferenceRepository(settings)
        assertEquals(30, reloaded.skipSeconds.value)
        assertEquals(0.25f, reloaded.speed.value)
    }

    @Test
    fun the_metrics_grid_starts_collapsed_and_stays_open_once_opened() {
        val settings = MapSettings()
        val repo = PlaybackPreferenceRepository(settings)
        assertEquals(false, repo.metricsExpanded.value)
        repo.setMetricsExpanded(true)
        assertEquals(true, repo.metricsExpanded.value)
        assertEquals(true, PlaybackPreferenceRepository(settings).metricsExpanded.value)
        repo.setMetricsExpanded(false)
        assertEquals(false, PlaybackPreferenceRepository(settings).metricsExpanded.value)
    }

    @Test
    fun unsupported_skip_snaps_to_the_nearest_supported_value() {
        val repo = PlaybackPreferenceRepository(MapSettings())
        repo.setSkipSeconds(7)
        assertEquals(5, repo.skipSeconds.value)
        repo.setSkipSeconds(13)
        assertEquals(15, repo.skipSeconds.value)
    }

    @Test
    fun the_short_frame_hunting_intervals_round_trip_untouched() {
        // 1s and 2s were added after the bar shipped: a build that only knew
        // 5/10/15/30 snapped them away, so lock the new rungs down as exact.
        val settings = MapSettings()
        PlaybackPreferenceRepository(settings).setSkipSeconds(1)
        assertEquals(1, PlaybackPreferenceRepository(settings).skipSeconds.value)
        PlaybackPreferenceRepository(settings).setSkipSeconds(2)
        assertEquals(2, PlaybackPreferenceRepository(settings).skipSeconds.value)
    }

    @Test
    fun unsupported_speed_snaps_to_the_nearest_supported_value() {
        val repo = PlaybackPreferenceRepository(MapSettings())
        repo.setSpeed(0.8f)
        assertEquals(1.0f, repo.speed.value)
        repo.setSpeed(0.3f)
        assertEquals(0.25f, repo.speed.value)
    }

    @Test
    fun a_speed_exactly_between_two_options_snaps_to_the_slower_one() {
        val repo = PlaybackPreferenceRepository(MapSettings())
        repo.setSpeed(0.75f)
        assertEquals(0.5f, repo.speed.value)
    }

    @Test
    fun out_of_range_values_clamp_to_the_ends() {
        val repo = PlaybackPreferenceRepository(MapSettings())
        repo.setSkipSeconds(-4)
        assertEquals(1, repo.skipSeconds.value)
        repo.setSkipSeconds(600)
        assertEquals(30, repo.skipSeconds.value)
        repo.setSpeed(0f)
        assertEquals(0.25f, repo.speed.value)
        repo.setSpeed(99f)
        assertEquals(2.0f, repo.speed.value)
    }

    @Test
    fun stored_values_from_another_build_snap_instead_of_resetting() {
        val settings = MapSettings().apply {
            putString("playback_skip_seconds", "20")
            putString("playback_speed", "1.9")
        }
        val repo = PlaybackPreferenceRepository(settings)
        assertEquals(15, repo.skipSeconds.value)
        assertEquals(2.0f, repo.speed.value)
    }

    @Test
    fun garbage_stored_values_fall_back_to_defaults() {
        val settings = MapSettings().apply {
            putString("playback_skip_seconds", "soon")
            putString("playback_speed", "brisk")
        }
        val repo = PlaybackPreferenceRepository(settings)
        assertEquals(10, repo.skipSeconds.value)
        assertEquals(1.0f, repo.speed.value)
    }

    @Test
    fun speed_changes_are_observable() = runTest {
        val repo = PlaybackPreferenceRepository(MapSettings())
        repo.speed.test {
            assertEquals(1.0f, awaitItem())
            repo.setSpeed(0.5f)
            assertEquals(0.5f, awaitItem())
        }
    }

    @Test
    fun skip_changes_are_observable() = runTest {
        val repo = PlaybackPreferenceRepository(MapSettings())
        repo.skipSeconds.test {
            assertEquals(10, awaitItem())
            repo.setSkipSeconds(30)
            assertEquals(30, awaitItem())
        }
    }

    @Test
    fun speed_labels_drop_a_trailing_zero_so_normal_speed_reads_as_one() {
        assertEquals("0.25\u00d7", PlaybackOptions.formatSpeed(0.25f))
        assertEquals("0.5\u00d7", PlaybackOptions.formatSpeed(0.5f))
        assertEquals("1\u00d7", PlaybackOptions.formatSpeed(1.0f))
        assertEquals("1.5\u00d7", PlaybackOptions.formatSpeed(1.5f))
        assertEquals("2\u00d7", PlaybackOptions.formatSpeed(2.0f))
    }
}
