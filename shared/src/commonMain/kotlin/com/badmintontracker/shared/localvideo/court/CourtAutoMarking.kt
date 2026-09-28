package com.badmintontracker.shared.localvideo.court

import com.badmintontracker.analysis.court.CourtFinding
import com.badmintontracker.analysis.geometry.pixels

/**
 * Where the court finder stands on the marking screen, and what the screen
 * says about it. Shared so Android and iOS say the same thing at the same
 * moment.
 *
 * [busyLine] replaces the instruction line while the finder works on the
 * frame; [note] sits under it once the finder has answered.
 */
enum class CourtFinderStatus(val note: String?, val busyLine: String?) {
    /** The finder has not run, or its answer is no longer news: say nothing. */
    IDLE(note = null, busyLine = null),

    /** Looking for the court on the frame on screen. */
    FINDING(note = null, busyLine = CourtMarkingSpec.FINDING),

    /** The court was found and all twelve points placed. */
    FOUND(note = CourtMarkingSpec.FOUND_NOTE, busyLine = null),

    /** Nothing found: the coach taps the four corners. */
    NOT_FOUND(note = CourtMarkingSpec.NOT_FOUND_NOTE, busyLine = null),

    /** Completing the court from the four tapped corners. */
    ALIGNING(note = null, busyLine = CourtMarkingSpec.ALIGNING),

    /** The four corners were completed to twelve points. */
    ALIGNED(note = CourtMarkingSpec.ALIGNED_NOTE, busyLine = null),

    /** The four corners could not be completed: the coach taps on. */
    ALIGN_FAILED(note = CourtMarkingSpec.ALIGN_FAILED_NOTE, busyLine = null),

    /**
     * Find court found nothing over a marking that has points: they stay, and
     * [NOT_FOUND]'s "tap the four corners" would be wrong about what comes
     * next. An empty marking gets [NOT_FOUND].
     */
    FIND_FAILED(note = CourtMarkingSpec.FIND_FAILED_NOTE, busyLine = null),
    ;

    /**
     * Whether the finder is working on the frame: Find court waits, and the
     * instruction line says so. A member, not an extension, so iOS reads it
     * off the Swift enum SKIE makes of this one.
     */
    val isBusy: Boolean get() = busyLine != null

    /**
     * The note under the instruction line, where the saved-marks note also
     * sits. The finder's note wins: the two meet only when Find court over
     * saved marks finds nothing ([FIND_FAILED]), and a silent failure there
     * would leave the coach waiting for a court that is not coming.
     */
    fun screenNote(showsSavedMarks: Boolean): String? =
        note ?: CourtMarkingSpec.SAVED_MARKS_NOTE.takeIf { showsSavedMarks }
}

/**
 * The twelve points of a found court as marking points, in the table order
 * the marking screen places them in (`COURT_KEYPOINT_POSITIONS`, which is
 * also [CourtMarkingSpec.shortLabels]' order), or null when nothing was found.
 */
fun CourtFinding.toCourtPoints(): List<CourtPoint>? =
    keypoints?.pixels()?.map { CourtPoint(it.x.toFloat(), it.y.toFloat()) }

/**
 * The marking with the court the finder found on opening, or null to leave
 * the marking as it is: nothing was found, or the coach has already placed a
 * point, which means the finder answered after he started by hand. His hand
 * wins; the detection is discarded rather than replacing his taps.
 *
 * Find court on demand is not this: it replaces whatever is there, with
 * [CourtMarkingState.withDetected] directly.
 */
fun applyFinding(state: CourtMarkingState, finding: CourtFinding): CourtMarkingState? {
    if (state.points.isNotEmpty()) return null
    val points = finding.toCourtPoints() ?: return null
    return state.withDetected(points)
}
