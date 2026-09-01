package com.badmintontracker.android.localanalysis

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The foreground service reaches the foreground.
 *
 * Not a formality. `startForeground` throws when the manifest's
 * `foregroundServiceType` does not match the type passed in code, when the
 * matching permission is missing, or when the notification channel does not
 * exist - and on Android 14+ the process is killed rather than merely failing.
 * All three are manifest and configuration mistakes that compile perfectly.
 *
 * What this cannot check is the behaviour the service exists for: that work
 * survives the screen going off. That needs a real run with the phone asleep.
 */
@RunWith(AndroidJUnit4::class)
class LocalAnalysisServiceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun the_service_starts_and_stops_without_throwing() {
        LocalAnalysisService.start(context, "instrumented test")
        // Long enough for startForeground to have run and thrown if it were
        // going to: the failure is asynchronous, inside the service.
        Thread.sleep(3000)
        LocalAnalysisService.stop(context)
        Thread.sleep(500)
        // Reaching here means the process was not killed for a foreground
        // service violation, which is how Android 14+ reports the mismatch.
        assertTrue(true)
    }

    @Test
    fun starting_twice_is_safe() {
        // The runner re-starts the service to update its notification text as
        // the analysis moves from preparing to analysing to cutting, so this
        // is the normal path rather than an edge case.
        LocalAnalysisService.start(context, "first")
        Thread.sleep(1000)
        LocalAnalysisService.start(context, "second")
        Thread.sleep(1000)
        LocalAnalysisService.stop(context)
        Thread.sleep(500)
        assertTrue(true)
    }
}
