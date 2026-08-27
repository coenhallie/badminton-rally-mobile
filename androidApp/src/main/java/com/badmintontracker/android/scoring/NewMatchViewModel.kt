package com.badmintontracker.android.scoring

import androidx.lifecycle.ViewModel
import com.badmintontracker.shared.scoring.MatchSetup
import com.badmintontracker.shared.scoring.ScoreLogsRepository
import com.badmintontracker.shared.scoring.ScoringRules
import com.badmintontracker.shared.scoring.Side
import com.badmintontracker.shared.scoring.newMatchProblem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class NewMatchState(
    val title: String = "",
    val doubles: Boolean = false,
    /**
     * Always two entries per side. The second is ignored in singles rather than
     * cleared, so toggling the format twice does not destroy what was typed.
     */
    val homePlayers: List<String> = listOf("", ""),
    val awayPlayers: List<String> = listOf("", ""),
    val rules: ScoringRules = ScoringRules.BWF_21,
    val firstServer: Side = Side.HOME,
    /** Null while the form is untouched, so nothing is red before anyone has typed. */
    val problem: String? = null,
    val canCreate: Boolean = false,
)

class NewMatchViewModel(private val scoreLogs: ScoreLogsRepository) : ViewModel() {

    private val internal = MutableStateFlow(NewMatchState())
    val state: StateFlow<NewMatchState> = internal.asStateFlow()

    private var touched = false

    fun setTitle(title: String) = edit { it.copy(title = title) }
    fun setDoubles(doubles: Boolean) = edit { it.copy(doubles = doubles) }
    fun setRules(rules: ScoringRules) = edit { it.copy(rules = rules) }
    fun setFirstServer(side: Side) = edit { it.copy(firstServer = side) }

    fun setPlayer(side: Side, index: Int, name: String) = edit { current ->
        val replace = { list: List<String> -> list.mapIndexed { i, v -> if (i == index) name else v } }
        if (side == Side.HOME) current.copy(homePlayers = replace(current.homePlayers))
        else current.copy(awayPlayers = replace(current.awayPlayers))
    }

    /** The new match's id, or null when the form is not complete. */
    fun create(): String? {
        val s = internal.value
        if (!s.canCreate) return null
        return scoreLogs.create(
            title = s.title,
            homePlayers = submitted(s.homePlayers, s.doubles),
            awayPlayers = submitted(s.awayPlayers, s.doubles),
            rules = s.rules,
            setup = MatchSetup(doubles = s.doubles, firstServer = s.firstServer),
        ).id
    }

    private fun edit(transform: (NewMatchState) -> NewMatchState) {
        touched = true
        val next = transform(internal.value)
        val problem = newMatchProblem(
            title = next.title,
            homePlayers = submitted(next.homePlayers, next.doubles),
            awayPlayers = submitted(next.awayPlayers, next.doubles),
            doubles = next.doubles,
        )
        internal.value = next.copy(
            problem = if (touched) problem else null,
            canCreate = problem == null,
        )
    }

    private fun submitted(players: List<String>, doubles: Boolean): List<String> =
        players.take(if (doubles) 2 else 1)
}
