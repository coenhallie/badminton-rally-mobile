package com.badmintontracker.shared.prefs

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals

class ThemePreferenceRepositoryTest {
    @Test
    fun defaults_to_light() {
        assertEquals(ThemeMode.LIGHT, ThemePreferenceRepository(MapSettings()).mode.value)
    }

    @Test
    fun toggle_flips_and_persists_across_instances() {
        val settings = MapSettings()
        val repo = ThemePreferenceRepository(settings)
        repo.toggle()
        assertEquals(ThemeMode.DARK, repo.mode.value)
        assertEquals(ThemeMode.DARK, ThemePreferenceRepository(settings).mode.value)
    }

    @Test
    fun corrupt_stored_value_defaults_to_light() {
        val settings = MapSettings().apply { putString("theme_mode", "PLAID") }
        assertEquals(ThemeMode.LIGHT, ThemePreferenceRepository(settings).mode.value)
    }
}
