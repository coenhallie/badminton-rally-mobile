package com.badmintontracker.android.data

import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode { LIGHT, DARK }

class ThemePreferenceRepository(private val settings: Settings) {

    private val state = MutableStateFlow(load())
    val mode: StateFlow<ThemeMode> = state.asStateFlow()

    fun set(mode: ThemeMode) {
        settings.putString(KEY, mode.name)
        state.value = mode
    }

    private fun load(): ThemeMode =
        settings.getStringOrNull(KEY)
            ?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
            ?: ThemeMode.LIGHT

    private companion object { const val KEY = "theme_mode" }
}
