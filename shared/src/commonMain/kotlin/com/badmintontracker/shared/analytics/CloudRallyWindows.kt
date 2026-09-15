package com.badmintontracker.shared.analytics

import com.badmintontracker.analysis.player.RallyWindow
import com.badmintontracker.shared.model.RallyClip

/**
 * The rallies of a CLOUD-analysed video, as the spans a base position is
 * measured over.
 *
 * The device path measures over the clips it cut itself, whose bounds sit in a
 * sidecar next to the files. A cloud run cuts nothing on this phone, so that
 * sidecar is empty and the Base tab was never offered for a cloud analysis at
 * all - the one row of the "?" sheet that promised something the app could not
 * do.
 *
 * `rally_clips` is the right source and not merely an available one: it is
 * already the rally truth for a cloud video (the 2026-09-12 design, section 3),
 * it is what the coach's own clip list shows, and it is what their annotations
 * are anchored to. Deriving a rally on the phone from the cloud pose stream
 * would be the fourth rally list that design exists to prevent.
 *
 * Shared rather than mapped per platform for the reason the rest of this
 * package is shared: the two phones measuring one match over different windows
 * is the coach seeing two different answers.
 *
 * @param clips every clip the cache holds; [videoId] selects this video's.
 *   Filtering here rather than at the two call sites so neither can forget it
 *   and measure one match's medians over another's rallies.
 */
fun cloudRallyWindows(clips: List<RallyClip>, videoId: String): List<RallyWindow> =
    clips.asSequence()
        .filter { it.videoId == videoId }
        // rally_index is 1-based in this project and RallyWindow.index is what
        // the panel prints. No +1 anywhere.
        .map { RallyWindow(it.rallyIndex, it.startTimestamp.toDouble(), it.endTimestamp.toDouble()) }
        .sortedBy { it.index }
        .toList()

/**
 * The windows to measure against, given both sources.
 *
 * The companion to `heatmapSource`, and it follows the same ranking for the
 * same reason: a median taken over one run's rally boundaries and plotted from
 * another run's track is two answers on one court. Cloud windows go with a
 * cloud track; device windows go with a device track.
 *
 * @param preferCloud whether the track being drawn came from the cloud. Passed
 *   in rather than re-derived, so the caller cannot resolve the track one way
 *   and the windows another.
 */
fun rallyWindowsFor(
    preferCloud: Boolean,
    cloud: List<RallyWindow>,
    device: List<RallyWindow>,
): List<RallyWindow> = when {
    preferCloud && cloud.isNotEmpty() -> cloud
    device.isNotEmpty() -> device
    // A cloud track whose clips the cache has not loaded yet, or a device track
    // whose sidecar predates the index: fall back rather than show an empty
    // Base tab, since an unbounded window is skipped downstream anyway.
    else -> cloud
}
