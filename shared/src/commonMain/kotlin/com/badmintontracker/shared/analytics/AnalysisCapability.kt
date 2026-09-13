package com.badmintontracker.shared.analytics

/**
 * What a cloud run and a device run each produce, as the "?" sheet on the
 * court-marking screen shows it.
 *
 * Shared rather than written out per platform for the reason [availablePanels]
 * is: this is a promise about what the app does, and a promise that reads
 * differently on the two phones is the coach seeing two different apps. It is
 * also the copy most likely to go stale, because it describes a capability
 * rather than a screen - so it lives next to `availablePanels`, which is the
 * rule it describes.
 *
 * The two paths converge on the same three panels, and [PanelCapability] says
 * so plainly. What still differs is WHO is measured, WHERE the footage has to
 * be, and WHEN the answer arrives, which is what [cloudNotes] and [deviceNotes]
 * carry.
 */
data class PanelCapability(
    val panel: AnalyticsPanel,
    /** What a cloud analysis gives you for this panel. */
    val cloud: String,
    /** What a run on this phone gives you. */
    val device: String,
)

/**
 * One row per panel, in the order the detail screen offers them.
 *
 * Pinned to [AnalyticsPanel.entries] by its test: a panel added later that
 * silently missed this sheet would leave the coach with a tab nothing
 * explains.
 */
val panelCapabilities: List<PanelCapability> = listOf(
    PanelCapability(
        panel = AnalyticsPanel.Heatmap,
        cloud = "Both players",
        device = "The near player",
    ),
    PanelCapability(
        panel = AnalyticsPanel.Base,
        cloud = "Both players",
        device = "The near player",
    ),
    PanelCapability(
        // The one row where the two are not symmetric, and the asymmetry is
        // real: the skeleton is drawn over playback, so it needs the file.
        panel = AnalyticsPanel.Skeleton,
        cloud = "Both players, if the video is here",
        device = "The near player",
    ),
)

/** The line above the table: what both paths produce whatever you pick. */
const val CAPABILITY_SHARED_LINE: String =
    "Both cut the rallies into clips you can watch and annotate."

/**
 * Why a coach might pick the cloud, said as facts rather than as a
 * recommendation. "Takes as long as it takes" is the honest version: the
 * worker decides its own stages and the estimator on this screen prices the
 * device run only.
 */
val cloudNotes: List<String> = listOf(
    "Runs on a GPU, so a long match is not limited by this phone.",
    "Measures movement automatically once the clips are ready. That is a second pass, and it takes as long as it takes.",
    "The heatmap and base position work even on a phone that never held the video.",
)

/** The same, for a run on this phone. */
val deviceNotes: List<String> = listOf(
    "Runs here, offline, with no upload.",
    "You pick what it measures, and the estimate above is for this phone.",
    "Needs the video on this phone, and finds the near player only.",
)
