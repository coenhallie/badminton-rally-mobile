package com.badmintontracker.shared.local

/**
 * What the file's court marks came to.
 *
 * [NONE] and [BAD] both leave the two court-plane measurements off the strip,
 * but they are different problems with different fixes - a re-run for marks
 * that were never stored, a re-marking for marks that do not fit - so the
 * copy tells them apart rather than sending a coach to re-run a court it will
 * reject again.
 */
enum class CourtFit { NONE, BAD, OK }

/**
 * The one line always on screen under the graph: which frame this is. A frame
 * without a skeleton says so, because the dashes on the chips alone could be
 * read as a frame the model was unsure of rather than one it never had.
 */
fun skeletonFooter(frame: Int?): String =
    if (frame == null) "No skeleton at this frame" else "Frame $frame"

/**
 * Why the court-plane measurements are missing, or null when they are not.
 * Shown in the footer as well as the detail, because a coach looking for the
 * Stance chip should not have to open the grid to learn why it is not there.
 */
fun courtWarning(courtFit: CourtFit): String? = when (courtFit) {
    CourtFit.OK -> null
    CourtFit.NONE -> "No court marks in this file: stance and position need a re-run."
    CourtFit.BAD -> "The court marks do not fit: mark the court again for stance and position."
}

/** The file's facts, for the expanded grid: how many frames carry a skeleton and how the court came out. */
fun skeletonDetail(frames: Int, courtFit: CourtFit): String =
    "Skeleton in $frames frames · " + when (courtFit) {
        CourtFit.OK -> "court marks in file"
        CourtFit.NONE -> "no court marks"
        CourtFit.BAD -> "court marks do not fit"
    }
