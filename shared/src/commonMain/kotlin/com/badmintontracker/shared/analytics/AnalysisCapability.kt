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
        // No longer conditional. The skeleton is drawn over playback, so it
        // needs the file - and a cloud run now keeps the file, precisely so
        // this row can be unconditional. It read "if the video is here" while
        // the run itself was what took the video away.
        panel = AnalyticsPanel.Skeleton,
        cloud = "Both players",
        device = "The near player",
    ),
)

/**
 * The line above the metric picker.
 *
 * Names the device run, because the picker only drives that one: the cloud
 * button hands `startAnalysis` the court marks and nothing else, and the worker
 * decides its own stages. The old line said "this run", so a coach could tick
 * Skeleton, press the cloud button, and reasonably conclude they had asked for
 * something. They had not - and the one panel they had ticked was the one that
 * then did not appear.
 *
 * Shared for the reason the rest of this file is: a promise about what the app
 * does, worded once.
 */
const val METRIC_PICKER_LINE: String =
    "Pick what a run on this phone should produce. A cloud run measures everything."

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
    // The storage cost, said plainly, because it is the one thing a cloud run
    // asks of the phone. It used to release the video here and say so - but the
    // skeleton it had just computed is drawn OVER that video, so releasing it
    // left an artifact nothing could display. Keeping the file is what makes
    // the Skeleton row above unconditional.
    "Keeps the video on this phone, so the skeleton has something to draw over. Remove the video yourself when you want the space back.",
)

/** The same, for a run on this phone. */
val deviceNotes: List<String> = listOf(
    "Runs here, offline, with no upload.",
    "You pick what it measures, and the estimate above is for this phone.",
    "Needs the video on this phone, and finds the near player only.",
)
