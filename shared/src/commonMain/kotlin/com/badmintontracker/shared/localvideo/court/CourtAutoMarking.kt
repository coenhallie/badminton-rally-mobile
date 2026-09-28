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
     * Whether Continue may take the marking to the options step: not while
     * the finder works on the frame. Its answer replaces the marks, and one
     * that landed under the options step would send the analysis marks the
     * coach never saw. Both screens enable Continue on this and a complete
     * marking together.
     */
    val allowsContinue: Boolean get() = !isBusy

    /**
     * The status once the coach has changed the points to [marking] (a
     * placed or moved point), so a note about how things stood before the
     * edit does not stay on screen after it stops being true.
     *
     * [FIND_FAILED]'s "Your marks are unchanged" ends with any edit.
     * [ALIGN_FAILED]'s "Tap the rest" ends once there is no rest to tap.
     * The others stay: [FOUND] and [ALIGNED] ask him to check the points and
     * move them, which an edit is; [NOT_FOUND] tells him to tap the corners,
     * which he is doing. A busy status is not changed here: the platforms
     * cancel the finder on any tap first, which leaves [IDLE].
     *
     * Undo and Clear do not use this: they end every note.
     */
    fun afterEdit(marking: CourtMarkingState): CourtFinderStatus = when (this) {
        FIND_FAILED -> IDLE
        ALIGN_FAILED -> if (marking.isComplete) IDLE else this
        else -> this
    }

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

/**
 * What one finder answer does to the marking screen: the [marking] to show
 * and the [status] to say. [edited] is true when the answer placed a court,
 * which ends the saved-marks note like any other edit. It is one Undo step,
 * except where the court found is the marking already there
 * ([afterFindCourt]).
 *
 * The three functions below produce it, one per kind of finder call, so
 * Android and iOS apply an answer by the same rules. What stays on each
 * platform is the plumbing around them: running the call off the main
 * thread, and dropping an answer the coach cancelled by editing first.
 *
 * Each takes `finding = null` for a call that threw, which reads exactly as
 * nothing found: a finder that fails must leave the coach able to mark by
 * hand. Out of memory is included where it can be caught: on the JVM and
 * Android it is an `OutOfMemoryError` like any other throwable, but on
 * Kotlin/Native (iOS) running out of memory ends the process, and no caller
 * sees it.
 */
data class CourtFinderOutcome(
    val marking: CourtMarkingState,
    val status: CourtFinderStatus,
    val edited: Boolean,
)

/**
 * The finder's answer on opening, over an empty marking: the court placed
 * ([CourtFinderStatus.FOUND]); discarded because the coach has since tapped
 * ([applyFinding]), which says nothing ([CourtFinderStatus.IDLE]); or nothing
 * found, so tap the corners ([CourtFinderStatus.NOT_FOUND]).
 */
fun afterOpeningFind(marking: CourtMarkingState, finding: CourtFinding?): CourtFinderOutcome {
    if (finding?.found != true) return CourtFinderOutcome(marking, CourtFinderStatus.NOT_FOUND, edited = false)
    val applied = applyFinding(marking, finding)
        ?: return CourtFinderOutcome(marking, CourtFinderStatus.IDLE, edited = false)
    return CourtFinderOutcome(applied, CourtFinderStatus.FOUND, edited = true)
}

/**
 * Find court's answer: the court replaces whatever is marked, saved marks
 * included, as one step Undo reverses. Nothing found leaves the marking
 * alone and says so: [CourtFinderStatus.FIND_FAILED] over marks, and
 * [CourtFinderStatus.NOT_FOUND] (tap the corners) over none.
 *
 * A court found exactly where the marks already are, on the frame on screen
 * (Find court pressed twice, say), is the same answer without the step: an
 * Undo that changes nothing on screen would read as a broken Undo. It is
 * still an answer, so it says [CourtFinderStatus.FOUND] and puts down a
 * selection like any other.
 */
fun afterFindCourt(marking: CourtMarkingState, finding: CourtFinding?): CourtFinderOutcome {
    val found = finding?.toCourtPoints()
    if (found == null) {
        val status = if (marking.points.isEmpty()) CourtFinderStatus.NOT_FOUND else CourtFinderStatus.FIND_FAILED
        return CourtFinderOutcome(marking, status, edited = false)
    }
    val unchanged = found == marking.points && marking.markedAtSeconds == marking.frameSeconds
    val placed = if (unchanged) marking.copy(selected = null) else marking.withDetected(found)
    return CourtFinderOutcome(placed, CourtFinderStatus.FOUND, edited = true)
}

/**
 * The completion of the four tapped [corners]: applied only onto those same
 * four taps ([CourtFinderStatus.ALIGNED]); anything the coach did in between
 * wins and the answer is dropped quietly ([CourtFinderStatus.IDLE]). Nothing
 * found keeps the taps, and the coach taps on
 * ([CourtFinderStatus.ALIGN_FAILED]). Undo after a completion gives the four
 * taps back.
 */
fun afterCorners(marking: CourtMarkingState, corners: List<CourtPoint>, finding: CourtFinding?): CourtFinderOutcome {
    if (marking.points != corners) return CourtFinderOutcome(marking, CourtFinderStatus.IDLE, edited = false)
    val found = finding?.toCourtPoints()
        ?: return CourtFinderOutcome(marking, CourtFinderStatus.ALIGN_FAILED, edited = false)
    return CourtFinderOutcome(marking.withDetected(found), CourtFinderStatus.ALIGNED, edited = true)
}
