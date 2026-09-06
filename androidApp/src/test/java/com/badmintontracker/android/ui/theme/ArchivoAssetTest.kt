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
 * Filename legality (lowercase, digits, underscores only) is not checked
 * here: AAPT already enforces that rule earlier in the build, at
 * packageDebugResources, which runs before this test task and hard-fails the
 * build on an illegal name. A JVM test for that condition can only be
 * exercised by moving the offending file out of res/font altogether, which
 * means it can never fail from the state it claims to guard against - so it
 * was removed rather than kept as a check that always reports green.
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
}
