package com.badmintontracker.android.localanalysis

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream

/**
 * The wake lock outlives a single acquisition.
 *
 * The service originally took one `acquire(timeout)` sized to the longest video
 * anyone expected. That is a trap: analysis runs at roughly seven times
 * realtime, so any fixed bound covers a video far shorter than it looks, and
 * past it the lock lapses in silence. Nothing crashes and nothing logs. The
 * service stays up and the notification keeps showing a percentage while the
 * CPU is free to sleep, so with the screen off the run simply stalls until the
 * screen comes back on.
 *
 * The lock is read back out of `dumpsys power` rather than from the service,
 * because what matters is whether the platform still believes the lock is held,
 * which is the thing a stale in-process field would misreport.
 */
@RunWith(AndroidJUnit4::class)
class LocalAnalysisWakeLockTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    /**
     * Start from an observed clean slate rather than an assumed one.
     *
     * The service is process-wide and other tests in this package start it, so
     * "sleep a bit after stop and hope" makes this test's result depend on what
     * ran before it. Waiting for the lock to actually be gone is the difference
     * between a regression test and a flaky one.
     */
    @Before
    fun releaseAnyLeftoverLock() {
        LocalAnalysisService.stop(context)
        assertTrue(
            "a previous test left the wake lock held; this test cannot trust its own reading",
            waitForLock(held = false, timeoutMillis = 5_000),
        )
    }

    @After
    fun stopService() {
        LocalAnalysisService.stop(context)
        waitForLock(held = false, timeoutMillis = 5_000)
    }

    /** Polls until the lock reaches [held], returning whether it still holds that state. */
    private fun waitForLock(held: Boolean, timeoutMillis: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (lockIsHeld() == held) return true
            Thread.sleep(100)
        }
        return lockIsHeld() == held
    }

    private fun lockIsHeld(): Boolean =
        FileInputStream(
            instrumentation.uiAutomation
                .executeShellCommand("dumpsys power")
                .fileDescriptor,
        ).bufferedReader().use { it.readText() }
            .lineSequence()
            .any { "shuttl:local-analysis" in it && "PARTIAL_WAKE_LOCK" in it }

    @Test
    fun the_lock_is_held_past_a_single_acquisition_and_released_on_stop() {
        // A deadline the test can outlast, refreshed many times inside it.
        // Against the original single `acquire(TIMEOUT)` the lock is gone by
        // the time this asserts; the heartbeat is what keeps it.
        //
        // The margin between refresh and deadline is wide on purpose. The
        // heartbeat posts to the main looper, so a main-thread stall longer
        // than the deadline lapses the lock, and this package also runs the
        // ONNX and clip-cutting tests that load the phone enough to cause one.
        // Production runs 5 minutes against 15; a tight margin here would only
        // measure the test device's scheduling.
        LocalAnalysisService.start(context, "wake lock test", 4_000L, 300L)
        assertTrue(
            "expected the lock to be held while analysing",
            waitForLock(held = true, timeoutMillis = 5_000),
        )

        Thread.sleep(8_000) // twice the deadline, so an un-refreshed lock has lapsed
        assertTrue("the lock lapsed: it is not being refreshed", lockIsHeld())

        LocalAnalysisService.stop(context)
        assertTrue(
            "the lock outlived the service",
            waitForLock(held = false, timeoutMillis = 5_000),
        )
    }
}
