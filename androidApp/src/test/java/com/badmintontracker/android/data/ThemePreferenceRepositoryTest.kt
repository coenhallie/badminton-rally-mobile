package com.badmintontracker.android.data

import app.cash.turbine.test
import com.russhwolf.settings.MapSettings
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ThemePreferenceRepositoryTest {

    @Test
    fun `defaults to LIGHT when no value stored`() = runTest {
        val repo = ThemePreferenceRepository(MapSettings())
        repo.mode.value shouldBe ThemeMode.LIGHT
    }

    @Test
    fun `set DARK is observable and persisted`() = runTest {
        val settings = MapSettings()
        val repo     = ThemePreferenceRepository(settings)

        repo.mode.test {
            awaitItem() shouldBe ThemeMode.LIGHT
            repo.set(ThemeMode.DARK)
            awaitItem() shouldBe ThemeMode.DARK
        }

        // New repo over the same settings rehydrates DARK.
        ThemePreferenceRepository(settings).mode.value shouldBe ThemeMode.DARK
    }
}
