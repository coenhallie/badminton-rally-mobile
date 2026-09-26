package com.badmintontracker.android.scoring

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.badmintontracker.shared.scoring.GamePoints
import com.badmintontracker.shared.scoring.MatchSets
import com.badmintontracker.shared.scoring.MatchSetup
import com.badmintontracker.shared.scoring.ScoreLogsRepository
import com.badmintontracker.shared.scoring.ScoringRules
import com.badmintontracker.shared.scoring.Side
import com.badmintontracker.shared.scoring.createMatchProblem
import com.badmintontracker.shared.scoring.defaultMatchTitle
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlin.time.Clock

data class NewMatchState(
    /** Prefilled, so the form can be submitted the moment it opens. */
    val title: String,
    val points: GamePoints = GamePoints.DEFAULT,
    val sets: MatchSets = MatchSets.DEFAULT,
    /** Null while the name is one the form wrote itself, so nothing is red before anyone has typed. */
    val problem: String? = null,
    val canCreate: Boolean = true,
)

class NewMatchViewModel(
    private val scoreLogs: ScoreLogsRepository,
    private val places: PlaceNames,
    now: () -> Instant = Clock.System::now,
    private val zone: TimeZone = TimeZone.currentSystemDefault(),
) : ViewModel() {

    /** Read once: the name says when the form was opened, not when the place came back. */
    private val openedAt = now()

    private val internal = MutableStateFlow(NewMatchState(title = defaultMatchTitle(openedAt, zone, place = null)))
    val state: StateFlow<NewMatchState> = internal.asStateFlow()

    /** Once the user has typed a name it is theirs, and a place arriving late must not overwrite it. */
    private var titleEdited = false

    private var lookup: Job? = null

    fun setTitle(title: String) {
        titleEdited = true
        val problem = createMatchProblem(title)
        internal.value = internal.value.copy(title = title, problem = problem, canCreate = problem == null)
    }

    fun setPoints(points: GamePoints) { internal.value = internal.value.copy(points = points) }
    fun setSets(sets: MatchSets) { internal.value = internal.value.copy(sets = sets) }

    /**
     * Adds the place to the default name, if the phone can name one. Called once the
     * screen knows whether location is allowed; asking is the screen's job, because
     * a permission prompt needs an Activity.
     */
    fun addPlaceToTitle() {
        if (lookup != null) return
        lookup = viewModelScope.launch {
            val place = places.current() ?: return@launch
            if (titleEdited) return@launch
            internal.value = internal.value.copy(title = defaultMatchTitle(openedAt, zone, place))
        }
    }

    /** The new match's id, or null when the form is not complete. */
    fun create(): String? {
        val s = internal.value
        if (!s.canCreate) return null
        return scoreLogs.create(
            title = s.title,
            rules = ScoringRules.of(s.points, s.sets),
            // Home, because the form no longer asks: the board lets it be switched
            // until the first point is scored.
            setup = MatchSetup(firstServer = Side.HOME),
        ).id
    }
}
