package com.badmintontracker.android.ui.theme

import io.kotest.matchers.shouldBe
import java.io.File
import kotlin.test.Test

/**
 * Guards the font ASSETS, not the wiring.
 *
 * This source set has no Robolectric, so R.font cannot be resolved here and a
 * real "does the family load" test is not available. What can be checked cheaply
 * is that the four files exist under the exact resource names ShuttlType.kt
 * references, which is the failure that would otherwise surface as every screen
 * silently rendering in the system font. The wiring itself is covered by the
 * visual sweep in Task 9.
 *
 * Gradle runs unit tests with the module directory as the working directory.
 */
class ArchivoAssetTest {
    private val fontDir = File("src/main/res/font")

    @Test
    fun every_weight_referenced_by_shuttl_type_is_present() {
        val required = listOf(
            "archivo_regular.ttf",
            "archivo_medium.ttf",
            "archivo_semibold.ttf",
            "archivo_bold.ttf",
        )
        for (name in required) {
            File(fontDir, name).exists() shouldBe true
        }
    }

    @Test
    fun font_resource_names_are_valid_android_resource_names() {
        // A capital letter or a dash in res/font is a build failure with a
        // message that does not mention the file, so catch it here instead.
        val offenders = fontDir.listFiles().orEmpty()
            .map { it.name }
            .filter { !it.matches(Regex("[a-z0-9_]+\\.ttf")) }
        offenders shouldBe emptyList()
    }
}
