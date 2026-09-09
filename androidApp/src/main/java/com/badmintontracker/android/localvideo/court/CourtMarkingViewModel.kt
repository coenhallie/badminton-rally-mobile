package com.badmintontracker.android.localvideo.court

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.badmintontracker.shared.localvideo.court.CourtMarkingState
import com.badmintontracker.shared.model.CourtKeypoints
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
     * True from the moment the screen opens on them until the coach edits them,
     * which from a complete marking means Undo or Clear: a tap on a marking that
     * already has its twelve points is ignored. What it drives is one line
     * saying where the dots came from.
     */
    val showsSavedMarks: Boolean = false,
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
) : ViewModel() {

    val state = MutableStateFlow(CourtMarkingUiState())

    init {
        viewModelScope.launch {
            runCatching { loadFrame() }
                .onSuccess { f ->
                    val restored = CourtMarkingState.restored(f.width, f.height, savedKeypoints)
                    state.update {
                        it.copy(
                            frame = f.frame,
                            marking = restored,
                            fps = f.fps,
                            frameCount = f.frameCount,
                            showsSavedMarks = restored.isComplete,
                        )
                    }
                }
                .onFailure { e ->
                    state.update { it.copy(error = e.message ?: "Couldn't load frame") }
                }
        }
    }

    fun onTap(displayX: Float, displayY: Float, displayWidth: Float, displayHeight: Float) {
        state.update { s ->
            s.copy(marking = s.marking?.place(displayX, displayY, displayWidth, displayHeight))
        }
    }

    fun onUndo() = state.update { it.copy(marking = it.marking?.undo(), showsSavedMarks = false) }
    fun onClear() = state.update { it.copy(marking = it.marking?.clear(), showsSavedMarks = false) }
}
