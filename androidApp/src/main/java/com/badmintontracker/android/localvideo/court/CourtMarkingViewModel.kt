package com.badmintontracker.android.localvideo.court

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.badmintontracker.analysis.court.CourtFinding
import com.badmintontracker.shared.localvideo.court.CourtFinderStatus
import com.badmintontracker.shared.localvideo.court.CourtMarkingState
import com.badmintontracker.shared.localvideo.court.CourtPoint
import com.badmintontracker.shared.localvideo.court.CourtFinderOutcome
import com.badmintontracker.shared.localvideo.court.afterCorners
import com.badmintontracker.shared.localvideo.court.afterFindCourt
import com.badmintontracker.shared.localvideo.court.afterOpeningFind
import com.badmintontracker.shared.model.CourtKeypoints
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Frame shown for marking. `frame` is null in unit tests (dimensions still real). */
data class CourtFrame(
    val frame: Bitmap?,
    val width: Int,
    val height: Int,
    /**
     * Read from the container rather than assumed, because the analysis
     * estimate is priced in frames: a six-minute 50fps video has more frames,
     * and costs more, than an eight-minute 25fps one. Assuming 30 would
     * understate exactly the videos that take longest.
     */
    val fps: Double = 0.0,
    val frameCount: Int = 0,
    /**
     * When in the video [frame] was taken, seconds, which the marks carry as
     * `marked_at_s` for the camera tracker. 0.1 rather than read back:
     * `OPTION_CLOSEST` does not report which frame it returned, and the frame
     * nearest 0.1 s is within one frame of it.
     */
    val markedAtSeconds: Double = 0.1,
)

data class CourtMarkingUiState(
    val frame: Bitmap? = null,
    val marking: CourtMarkingState? = null,
    val error: String? = null,
    /** Carried through for the analysis time estimate, which is priced in frames. */
    val fps: Double = 0.0,
    val frameCount: Int = 0,
    /**
     * Whether the marks on screen are the ones the last run used, untouched.
     *
     * True from the moment the screen opens on them until the coach edits them:
     * Undo, Clear, moving a point or Find court. Picking a point up to move it
     * is not an edit yet. What it drives is one line saying where the dots
     * came from.
     */
    val showsSavedMarks: Boolean = false,
    /** Where the court finder stands, which the instruction line and the note say. */
    val status: CourtFinderStatus = CourtFinderStatus.IDLE,
)

class CourtMarkingViewModel(
    val entryId: String,
    /**
     * What this entry was marked with last time, if it was. The screen opens on
     * those twelve points rather than an empty frame, so a second run over the
     * same video is a review of the marks and not a re-marking of them.
     */
    private val savedKeypoints: CourtKeypoints? = null,
    private val loadFrame: suspend () -> CourtFrame,
    private val finder: CourtFinderPort = DefaultCourtFinderPort,
    private val log: CourtFinderLog = LogcatCourtFinderLog,
) : ViewModel() {

    val state = MutableStateFlow(CourtMarkingUiState())

    /** The frame on screen, which every finder call looks at. */
    private var loaded: CourtFrame? = null

    /**
     * The finder call in flight, if any. An edit by the coach cancels it: his
     * hand wins over an answer to a question about a marking he has since
     * changed, and the busy line would otherwise stand in for the instruction
     * he needs while he taps. The arrival checks below guard the same thing
     * for an answer that is already on its way back.
     */
    private var finderJob: Job? = null

    init {
        viewModelScope.launch {
            runCatching { loadFrame() }
                .onSuccess { f ->
                    loaded = f
                    val restored = CourtMarkingState.restored(
                        f.width, f.height, savedKeypoints, frameSeconds = f.markedAtSeconds,
                    )
                    state.update {
                        it.copy(
                            frame = f.frame,
                            marking = restored,
                            fps = f.fps,
                            frameCount = f.frameCount,
                            showsSavedMarks = restored.isComplete,
                        )
                    }
                    // Saved marks are what the coach used last time: opening
                    // on them is a review, and the finder stays out of it
                    // unless he asks (Find court).
                    if (restored.points.isEmpty()) findOnOpening()
                }
                .onFailure { e ->
                    state.update { it.copy(error = e.message ?: "Couldn't load frame") }
                }
        }
    }

    /**
     * A tap on the frame, in display coordinates, with [hitRadius] the reach of
     * a placed point in the same units (see [CourtMarkingState.tap]).
     */
    fun onTap(
        displayX: Float,
        displayY: Float,
        displayWidth: Float,
        displayHeight: Float,
        hitRadius: Float = 0f,
    ) {
        val before = state.value.marking ?: return
        val after = before.tap(displayX, displayY, displayWidth, displayHeight, hitRadius)
        if (after == before) return
        val edited = after.points != before.points
        if (edited && state.value.status.isBusy) cancelFinder()
        state.update {
            it.copy(marking = after, showsSavedMarks = it.showsSavedMarks && !edited)
        }
        if (after.justReachedFourCorners(before)) completeFromCorners(after.points)
    }

    fun onUndo() {
        cancelFinder()
        state.update { it.copy(marking = it.marking?.undo(), showsSavedMarks = false, status = CourtFinderStatus.IDLE) }
    }

    fun onClear() {
        cancelFinder()
        state.update { it.copy(marking = it.marking?.clear(), showsSavedMarks = false, status = CourtFinderStatus.IDLE) }
    }

    /**
     * Find court: the finder on demand, replacing whatever is marked (saved
     * marks included) as one step Undo reverses. Nothing found leaves the
     * marking alone, and says so: FIND_FAILED over marks, NOT_FOUND (tap the
     * corners) over none. Ignored while the finder is already working.
     */
    fun onFindCourt() {
        if (state.value.marking == null || state.value.status.isBusy) return
        runFinder(CourtFinderStatus.FINDING) {
            val finding = ask("find on demand") { finder.find(it) }
            state.update { s -> s.marking?.let { s.with(afterFindCourt(it, finding)) } ?: s }
        }
    }

    /** The finder on an empty marking, as the screen opens. A tap before it answers wins. */
    private fun findOnOpening() = runFinder(CourtFinderStatus.FINDING) {
        val finding = ask("find") { finder.find(it) }
        state.update { s -> s.marking?.let { s.with(afterOpeningFind(it, finding)) } ?: s }
    }

    /**
     * The fourth corner was just tapped: ask the finder to complete the court
     * from [corners]. Applied only onto those same four taps; anything the
     * coach did in between wins. Undo afterwards gives the four taps back.
     */
    private fun completeFromCorners(corners: List<CourtPoint>) =
        runFinder(CourtFinderStatus.ALIGNING) {
            val finding = ask("fromCorners") { finder.fromCorners(it, corners) }
            state.update { s ->
                s.marking?.let { s.with(afterCorners(it, corners, finding)) } ?: s.copy(status = CourtFinderStatus.IDLE)
            }
        }

    /** The screen after a finder answer; an applied one is an edit, which ends the saved-marks note. */
    private fun CourtMarkingUiState.with(outcome: CourtFinderOutcome) = copy(
        marking = outcome.marking,
        status = outcome.status,
        showsSavedMarks = showsSavedMarks && !outcome.edited,
    )

    private fun runFinder(busy: CourtFinderStatus, block: suspend () -> Unit) {
        cancelFinder()
        state.update { it.copy(status = busy) }
        finderJob = viewModelScope.launch { block() }
    }

    private fun cancelFinder() {
        finderJob?.cancel()
        finderJob = null
        state.update { if (it.status.isBusy) it.copy(status = CourtFinderStatus.IDLE) else it }
    }

    /**
     * One finder call on the frame on screen, logged. Null when it threw, out
     * of memory included, which the screen treats as nothing found: a finder
     * that fails must leave the coach able to mark by hand.
     */
    private suspend fun ask(call: String, work: suspend (CourtFrame) -> CourtFinding): CourtFinding? {
        val frame = loaded ?: return null
        return try {
            work(frame).also { log.answered(call, it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            log.failed(call, e)
            null
        }
    }
}
