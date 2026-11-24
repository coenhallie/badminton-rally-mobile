package com.badmintontracker.android.match

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.badmintontracker.shared.invites.LinkedPlayerProfile
import com.badmintontracker.shared.invites.PlayerAccessRepository
import com.badmintontracker.shared.invites.toPlayer
import com.badmintontracker.shared.localvideo.LocalAnnotationsRepository
import com.badmintontracker.shared.localvideo.LocalVideoEntry
import com.badmintontracker.shared.localvideo.LocalVideoRepository
import com.badmintontracker.shared.players.MatchPlayers
import com.badmintontracker.shared.players.Player
import com.badmintontracker.shared.players.PlayersRepository
import com.badmintontracker.shared.players.matchPlayersProblem
import com.badmintontracker.shared.players.playersTarget
import com.badmintontracker.shared.players.saveEntryMatchDetailsOrMessage
import com.badmintontracker.shared.players.saveMatchPlayersOrMessage
import com.badmintontracker.shared.repo.ClipsRepository
import com.badmintontracker.shared.repo.VideosRepository
import com.badmintontracker.shared.scoring.MatchFormat
import com.badmintontracker.shared.scoring.MatchState
import com.badmintontracker.shared.scoring.ScoreLog
import com.badmintontracker.shared.scoring.ScoreLogsRepository
import com.badmintontracker.shared.scoring.ScoreMatchCard
import com.badmintontracker.shared.scoring.ScoreTagSummary
import com.badmintontracker.shared.scoring.Side
import com.badmintontracker.shared.scoring.buildScoreMatchCard
import com.badmintontracker.shared.scoring.buildScoreTagSummary
import com.badmintontracker.shared.scoring.isPlayable
import com.badmintontracker.shared.scoring.removeMatchVideoOrMessage
import com.badmintontracker.shared.scoring.scoreLogCanRemoveVideo
import com.badmintontracker.shared.scoring.updateVideoMatchDetailsOrMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * One match, folded. Everything is derived from the store's flow rather than
 * fetched once, so a point scored elsewhere or a video finishing its pipeline
 * redraws this page without a refresh.
 */
data class MatchPageState(
    /** Null for a video-first or shared match, and for one deleted while open. */
    val log: ScoreLog? = null,
    /** True only when [log] is owned by the signed-in account (never true for coached linked logs). */
    val isOwnedLog: Boolean = false,
    val match: MatchState? = null,
    /** The coach's players (plus any linked player profiles): a side a profile holds is named from these. */
    val players: List<Player> = emptyList(),
    val card: ScoreMatchCard? = null,
    val tally: ScoreTagSummary = ScoreTagSummary.EMPTY,
    /** A finished owned match with no video, and no video already picked for it. */
    val canAddVideo: Boolean = false,
    /** This owned match has a video and nothing is in flight for it. */
    val canRemoveVideo: Boolean = false,
    /** Whether a videos row exists, which decides what the confirm says is about
     *  to be lost - an entry that never reached CREATE_ROW has no rallies and no
     *  notes to lose. */
    val hasServerVideo: Boolean = false,
    /** A failed removal, for the page's snackbar. Nothing else on this page can
     *  fail: everything else it shows is derived from stores it only reads. */
    val error: String? = null,
)

/**
 * A new "Home was at the" answer for the video of a scored match: the side nearest
 * the camera (null: not set) and the video's ids, as the page knows them.
 */
data class VideoEndChange(val nearSide: Side?, val videoId: String?, val entryId: String?)

class MatchViewModel(
    private val scoreLogs: ScoreLogsRepository,
    private val localVideos: LocalVideoRepository,
    private val clips: ClipsRepository,
    private val videos: VideosRepository,
    private val localAnnotations: LocalAnnotationsRepository,
    players: PlayersRepository,
    private val scoreLogId: String?,
    playerAccess: PlayerAccessRepository? = null,
) : ViewModel() {

    private val errors = MutableStateFlow<String?>(null)
    private val linkedProfilesFlow = playerAccess?.linkedProfiles ?: MutableStateFlow(emptyList())

    private val allPlayers = combine(players.players, linkedProfilesFlow) { roster, linked ->
        val seen = roster.mapTo(mutableSetOf()) { it.id }
        roster + linked.filter { it.playerId !in seen }.map { it.toPlayer() }
    }

    // What the match's video is doing is not read here: the page's status
    // banner draws the match list's own row (see MatchScreen's StatusBanner),
    // the one place that also sees an on-device run.
    val state = combine(
        scoreLogs.logs,
        scoreLogs.linkedLogs,
        localVideos.entries,
        allPlayers,
        errors,
    ) { logs, linkedLogs, entries, roster, error ->
        build(logs, linkedLogs, entries, roster, error)
    }
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            build(
                logs = scoreLogs.logs.value,
                linkedLogs = scoreLogs.linkedLogs.value,
                entries = localVideos.entries.value,
                roster = mergePlayers(players.players.value, linkedProfilesFlow.value),
                error = errors.value,
            ),
        )

    private fun mergePlayers(roster: List<Player>, linked: List<LinkedPlayerProfile>): List<Player> {
        val seen = roster.mapTo(mutableSetOf()) { it.id }
        return roster + linked.filter { it.playerId !in seen }.map { it.toPlayer() }
    }

    fun removeVideo(onRemoved: () -> Unit = {}) {
        val id = scoreLogId ?: return
        if (scoreLogs.get(id) == null) return
        viewModelScope.launch {
            val message = removeMatchVideoOrMessage(
                scoreLogId = id,
                scoreLogs = scoreLogs,
                videos = videos,
                clips = clips,
                localVideos = localVideos,
                localAnnotations = localAnnotations,
            )
            if (message == null) onRemoved() else errors.value = message
        }
    }

    /**
     * The details editor's save for a scored match: the name, the format and who
     * played, in the one write to the log (local-first like every other edit to a
     * match; sync carries it). A combination the database would refuse, a profile
     * on a doubles log, is said instead and nothing is written.
     *
     * [end] is passed only when the editor's "Home was at the" row changed. The log
     * speaks for the players, so the attached video learns the end alone, with
     * nobody on it, wherever its own target sends it (the entry before upload, the
     * row after). See [saveMatchPlayersOrMessage].
     */
    fun updateDetails(title: String, format: MatchFormat?, players: MatchPlayers, end: VideoEndChange? = null) {
        val id = scoreLogId ?: return
        if (scoreLogs.get(id) == null) return
        matchPlayersProblem(players, format)?.let { errors.value = it; return }
        scoreLogs.updateDetails(id, title, format, players)
        end ?: return
        val entry = end.entryId?.let(localVideos::get)
        val target = playersTarget(null, entry, end.videoId) ?: return
        viewModelScope.launch {
            saveMatchPlayersOrMessage(
                target, scoreLogs, videos, localVideos,
                scoreLogId = null, videoId = end.videoId, entryId = end.entryId,
                players = MatchPlayers.NONE, nearSide = end.nearSide, format = null,
            )?.let { errors.value = it }
        }
    }

    /**
     * The details editor's save for a match that was only ever filmed, whose
     * details live on its videos row rather than on a score log: name,
     * description, format, who played and which end Home was at, in one write.
     * Server first, then the local entry that uploaded it: the ordering, and the
     * message on failure, are [updateVideoMatchDetailsOrMessage]'s. The page reads
     * the result back through the repository's metadata cache, so nothing here
     * has to hand it over.
     */
    fun updateVideoDetails(
        videoId: String,
        title: String,
        description: String,
        format: MatchFormat?,
        players: MatchPlayers,
        nearSide: Side?,
    ) {
        viewModelScope.launch {
            val message = updateVideoMatchDetailsOrMessage(
                videos = videos,
                localVideos = localVideos,
                videoId = videoId,
                title = title,
                description = description,
                format = format,
                players = players,
                nearSide = nearSide,
            )
            if (message != null) errors.value = message
        }
    }

    /**
     * The "Match details" sheet's save, for a match only its local entry speaks
     * for (no score log, no clips yet): who's playing wherever the entry's stage
     * sends it, then the name and description while the entry is still LOCAL. See
     * [saveEntryMatchDetailsOrMessage]; its sentence goes on the page's snackbar.
     */
    fun saveEntryDetails(entryId: String, title: String, description: String, players: MatchPlayers, nearSide: Side?) {
        viewModelScope.launch {
            saveEntryMatchDetailsOrMessage(
                scoreLogs, videos, localVideos, entryId, title, description, players, nearSide,
            )?.let { errors.value = it }
        }
    }

    fun dismissError() { errors.value = null }

    private fun build(
        logs: List<ScoreLog>,
        linkedLogs: List<ScoreLog>,
        entries: List<LocalVideoEntry>,
        roster: List<Player>,
        error: String?,
    ): MatchPageState {
        val ownedLog = logs.firstOrNull { it.id == scoreLogId }
        val log = ownedLog ?: linkedLogs.firstOrNull { it.id == scoreLogId }
            ?: return MatchPageState(players = roster, error = error)
        val isOwnedLog = ownedLog != null
        val match = log.state()
        val entry = if (isOwnedLog) entries.firstOrNull { it.scoreLogId == scoreLogId } else null
        return MatchPageState(
            log = log,
            isOwnedLog = isOwnedLog,
            match = match,
            players = roster,
            card = buildScoreMatchCard(log, roster),
            tally = buildScoreTagSummary(match),
            canAddVideo = isOwnedLog && !log.isPlayable() && log.videoId == null && entry == null,
            canRemoveVideo = isOwnedLog && scoreLogCanRemoveVideo(log, entries),
            hasServerVideo = log.videoId != null,
            error = error,
        )
    }
}
