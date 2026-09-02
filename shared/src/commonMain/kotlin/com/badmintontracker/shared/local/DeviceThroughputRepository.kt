package com.badmintontracker.shared.local

import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What this particular phone manages, learned from its own runs.
 *
 * The seeded defaults are real numbers from a Galaxy S23, which is honest for a
 * first estimate and wrong for most other phones: the spread across Android
 * hardware is far wider than any single default covers. So every completed run
 * reports what it actually achieved and the estimate converges on the truth for
 * the device in hand.
 *
 * Smoothed rather than replaced outright. One run on a hot phone, or one on a
 * phone that happened to be idle, should move the estimate rather than become
 * it, or the number a user sees would swing between runs for no reason they can
 * see.
 */
class DeviceThroughputRepository(private val settings: Settings) {

    private val state = MutableStateFlow(load())
    val throughput: StateFlow<DeviceThroughput> = state.asStateFlow()

    /**
     * Record what a finished run achieved.
     *
     * @param poseMsPerFrame null when pose did not run, which must not be
     *   recorded as pose being free.
     */
    fun record(baseMsPerFrame: Double, poseMsPerFrame: Double?, cutMsPerClipSecond: Double?) {
        val current = state.value
        val next = current.copy(
            baseMsPerFrame = blend(current.baseMsPerFrame, baseMsPerFrame, current.measured),
            poseMsPerFrame = poseMsPerFrame
                ?.let { blend(current.poseMsPerFrame, it, current.measured) }
                ?: current.poseMsPerFrame,
            cutMsPerClipSecond = cutMsPerClipSecond
                ?.let { blend(current.cutMsPerClipSecond, it, current.measured) }
                ?: current.cutMsPerClipSecond,
            measured = true,
        )
        state.value = next
        settings.putDouble(KEY_BASE, next.baseMsPerFrame)
        settings.putDouble(KEY_POSE, next.poseMsPerFrame)
        settings.putDouble(KEY_CUT, next.cutMsPerClipSecond)
        settings.putBoolean(KEY_MEASURED, true)
    }

    /**
     * The first real measurement replaces the seed outright rather than being
     * averaged with it; blending a Galaxy S23's numbers into a slower phone's
     * would keep the estimate optimistic for several runs.
     */
    private fun blend(old: Double, new: Double, haveMeasured: Boolean): Double =
        if (!haveMeasured) new else old * (1 - WEIGHT) + new * WEIGHT

    private fun load() = DeviceThroughput(
        baseMsPerFrame = settings.getDouble(KEY_BASE, DeviceThroughput.SEED_BASE_MS),
        poseMsPerFrame = settings.getDouble(KEY_POSE, DeviceThroughput.SEED_POSE_MS),
        cutMsPerClipSecond = settings.getDouble(KEY_CUT, DeviceThroughput.SEED_CUT_MS),
        measured = settings.getBoolean(KEY_MEASURED, false),
    )

    private companion object {
        const val KEY_BASE = "throughput.base.ms"
        const val KEY_POSE = "throughput.pose.ms"
        const val KEY_CUT = "throughput.cut.ms"
        const val KEY_MEASURED = "throughput.measured"

        /** New runs move the estimate without dominating it. */
        const val WEIGHT = 0.4
    }
}
