package com.badmintontracker.shared.prefs

import com.russhwolf.settings.Settings
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The values the playback controls offer, shared so Android, iOS and the
 * repository below all agree on one set. Both lists must stay ascending:
 * [nearest] resolves an exact tie to the first match, which is the slower
 * speed / shorter skip only while the list is sorted.
 */
object PlaybackOptions {
    val skipSecondsOptions: List<Int> = listOf(1, 2, 5, 10, 15, 30)
    val speedOptions: List<Float> = listOf(0.25f, 0.5f, 1.0f, 1.5f, 2.0f)

    /**
     * Unchanged since the bar shipped: Android's old asymmetric 5-back/15-forward
     * pair collapsed here, and the short 1s/2s rungs are a deliberate opt-in for
     * picking apart a rally rather than a sensible starting interval.
     */
    const val DEFAULT_SKIP_SECONDS: Int = 10
    const val DEFAULT_SPEED: Float = 1.0f

    /**
     * Button label for a speed: "1\u00d7", "0.5\u00d7", "0.25\u00d7". Built from the
     * hundredths rather than Float.toString() so Android and iOS render the same
     * text - Kotlin/Native and Kotlin/JVM do not agree on float formatting.
     */
    fun formatSpeed(speed: Float): String {
        val hundredths = (speed * 100).roundToInt()
        val whole = hundredths / 100
        val fraction = hundredths % 100
        val number = when {
            fraction == 0 -> whole.toString()
            fraction % 10 == 0 -> "$whole.${fraction / 10}"
            else -> "$whole.${fraction.toString().padStart(2, '0')}"
        }
        return number + "\u00d7"
    }
}

/**
 * Skip interval and playback speed, remembered app-wide and applied by every
 * player surface. Both are stored as strings for the same reason the other
 * preference repositories are: a value written by another build (or another
 * platform) can never blow up on a type mismatch.
 */
class PlaybackPreferenceRepository(private val settings: Settings) {

    private val skipState = MutableStateFlow(loadSkipSeconds())
    val skipSeconds: StateFlow<Int> = skipState.asStateFlow()

    private val speedState = MutableStateFlow(loadSpeed())
    val speed: StateFlow<Float> = speedState.asStateFlow()

    /**
     * Whether the skeleton view shows every measurement as a grid, or the
     * compact row it opens with. A coach who works from the grid should not
     * have to open it again on every video, so the choice is kept here with
     * the other choices about how a player surface is laid out.
     */
    private val metricsExpandedState = MutableStateFlow(settings.getStringOrNull(KEY_METRICS_EXPANDED) == "true")
    val metricsExpanded: StateFlow<Boolean> = metricsExpandedState.asStateFlow()

    fun setMetricsExpanded(expanded: Boolean) {
        settings.putString(KEY_METRICS_EXPANDED, expanded.toString())
        metricsExpandedState.value = expanded
    }

    fun setSkipSeconds(seconds: Int) {
        val next = nearest(seconds.toFloat(), PlaybackOptions.skipSecondsOptions.map { it.toFloat() })
            .toInt()
        settings.putString(KEY_SKIP, next.toString())
        skipState.value = next
    }

    fun setSpeed(speed: Float) {
        val next = nearest(speed, PlaybackOptions.speedOptions)
        settings.putString(KEY_SPEED, next.toString())
        speedState.value = next
    }

    private fun loadSkipSeconds(): Int =
        settings.getStringOrNull(KEY_SKIP)?.toIntOrNull()
            ?.let { nearest(it.toFloat(), PlaybackOptions.skipSecondsOptions.map { o -> o.toFloat() }).toInt() }
            ?: PlaybackOptions.DEFAULT_SKIP_SECONDS

    private fun loadSpeed(): Float =
        settings.getStringOrNull(KEY_SPEED)?.toFloatOrNull()
            ?.let { nearest(it, PlaybackOptions.speedOptions) }
            ?: PlaybackOptions.DEFAULT_SPEED

    /**
     * Snap to the closest offered value rather than rejecting to the default, so
     * a preference written by a build with a different option set degrades to the
     * neighbouring value instead of silently resetting the user's choice.
     */
    private fun nearest(value: Float, options: List<Float>): Float =
        options.minByOrNull { abs(it - value) } ?: value

    private companion object {
        const val KEY_SKIP = "playback_skip_seconds"
        const val KEY_SPEED = "playback_speed"
        const val KEY_METRICS_EXPANDED = "skeleton_metrics_expanded"
    }
}
