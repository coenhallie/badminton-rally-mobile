package com.badmintontracker.shared.players

import com.badmintontracker.shared.localvideo.AnalyzeStage
import com.badmintontracker.shared.localvideo.AnalyzeStep
import com.badmintontracker.shared.localvideo.LocalVideoEntry
import com.badmintontracker.shared.localvideo.LocalVideoRepository
import com.badmintontracker.shared.localvideo.setMatchPlayers
import com.badmintontracker.shared.repo.VideosRepository
import com.badmintontracker.shared.scoring.MatchFormat
import com.badmintontracker.shared.scoring.ScoreLogsRepository
import com.badmintontracker.shared.scoring.Side
import com.badmintontracker.shared.scoring.submittedPlayers
import kotlinx.coroutines.flow.first

/** Which record a who's-playing save writes to. */
enum class PlayersTarget { SCORE_LOG, LOCAL_ENTRY, VIDEO_ROW, UPLOADING }

/**
 * Where a who's-playing save goes for a match, or null when there is nothing to
 * write to. In order: the score log when there is one (it speaks for the match);
 * the local entry while no videos row exists yet (stage LOCAL, or a failure at
 * UPLOAD or CREATE_ROW, which both come before the INSERT); nothing during the
 * upload, because the entry's values are being copied onto the INSERT and a write
 * now could land on neither; and the videos row once it exists. An entry past the
 * INSERT has a row whether or not [videoId] is known yet (its clips may not have
 * synced): the upload made the entry's id the row's id, and the save writes there.
 */
fun playersTarget(scoreLogId: String?, entry: LocalVideoEntry?, videoId: String?): PlayersTarget? {
    if (scoreLogId != null) return PlayersTarget.SCORE_LOG
    if (entry != null) {
        val noRowYet = entry.stage == AnalyzeStage.LOCAL ||
            (entry.stage == AnalyzeStage.FAILED &&
                (entry.failedStep == AnalyzeStep.UPLOAD || entry.failedStep == AnalyzeStep.CREATE_ROW))
        return when {
            noRowYet -> PlayersTarget.LOCAL_ENTRY
            entry.stage == AnalyzeStage.UPLOADING -> PlayersTarget.UPLOADING
            else -> PlayersTarget.VIDEO_ROW
        }
    }
    return if (videoId != null) PlayersTarget.VIDEO_ROW else null
}

/**
 * The one save behind every who's-playing surface. Returns null on success or the
 * sentence to show; a problem with [players] is returned before anything is
 * written. A score log is judged under its own format, not [format]: the log is
 * what gets written, and a profile on a doubles log would be refused by the
 * database and fail the whole sync batch. Every other target is judged under
 * [format], the video's.
 *
 * A score log's assignment stands for the match, so a log is saved first and the
 * video, if one is attached, only learns [nearSide] (with nobody on it: the log
 * speaks for the players), routed by the video's own target exactly as for a match
 * that was only ever filmed. That second write is skipped when the video already
 * stores [nearSide], so saving the players of a match whose end did not change
 * never fails on the video. A videos row is written before the entry that
 * uploaded it, and the entry follows only once the server accepted: the row is
 * the record.
 */
suspend fun saveMatchPlayersOrMessage(
    target: PlayersTarget,
    scoreLogs: ScoreLogsRepository,
    videos: VideosRepository,
    localVideos: LocalVideoRepository,
    scoreLogId: String?,
    videoId: String?,
    entryId: String?,
    players: MatchPlayers,
    nearSide: Side?,
    format: MatchFormat?,
): String? {
    val log = if (target == PlayersTarget.SCORE_LOG) {
        scoreLogId?.let(scoreLogs::get) ?: return PlayersCopy.WHOS_PLAYING_SAVE_FAILED
    } else {
        null
    }
    val judgedFormat = if (log != null) log.format else format
    matchPlayersProblem(players, judgedFormat)?.let { return it }
    val clean = players.copy(
        homePlayers = submittedPlayers(players.homePlayers, judgedFormat),
        awayPlayers = submittedPlayers(players.awayPlayers, judgedFormat),
    )
    return when (target) {
        PlayersTarget.UPLOADING -> PlayersCopy.UPLOAD_IN_PROGRESS
        PlayersTarget.LOCAL_ENTRY -> {
            if (entryId == null) return PlayersCopy.WHOS_PLAYING_SAVE_FAILED
            localVideos.setMatchPlayers(entryId, clean, nearSide)
            null
        }
        PlayersTarget.VIDEO_ROW -> {
            val entry = entryId?.let(localVideos::get)
            val rowId = rowId(entry, videoId) ?: return PlayersCopy.WHOS_PLAYING_SAVE_FAILED
            writeVideoRow(videos, localVideos, rowId, entryId, clean, nearSide)
        }
        PlayersTarget.SCORE_LOG -> {
            if (log == null) return PlayersCopy.WHOS_PLAYING_SAVE_FAILED
            scoreLogs.assignPlayers(log.id, clean)
            saveEndOfAttachedVideo(videos, localVideos, videoId, entryId, nearSide)
        }
    }
}

/**
 * The videos row a save for [entry] or [videoId] writes: [videoId] when the caller
 * knows it, else the id of an entry past the INSERT, which the upload made the
 * row's id. Null when neither names a row.
 */
private fun rowId(entry: LocalVideoEntry?, videoId: String?): String? =
    videoId ?: entry?.takeIf { playersTarget(null, it, null) == PlayersTarget.VIDEO_ROW }?.id

/** Which end the home side was at, for the video a score log is attached to. */
private suspend fun saveEndOfAttachedVideo(
    videos: VideosRepository,
    localVideos: LocalVideoRepository,
    videoId: String?,
    entryId: String?,
    nearSide: Side?,
): String? {
    val entry = entryId?.let(localVideos::get)
    val rowId = rowId(entry, videoId)
    // The entry's end when the phone holds one, else the cached row's. No cached
    // row means the end is unknown, so it is written.
    val unchanged = if (entry != null) {
        entry.nearSide == nearSide
    } else {
        rowId?.let { videos.observeMatchMetadata().first()[it] }?.let { it.nearSide == nearSide } == true
    }
    if (unchanged) return null
    return when (playersTarget(null, entry, rowId)) {
        PlayersTarget.LOCAL_ENTRY -> {
            entry?.let { localVideos.setMatchPlayers(it.id, MatchPlayers.NONE, nearSide) }
            null
        }
        PlayersTarget.VIDEO_ROW ->
            rowId?.let { writeVideoRow(videos, localVideos, it, entryId, MatchPlayers.NONE, nearSide) }
        PlayersTarget.UPLOADING -> PlayersCopy.UPLOAD_IN_PROGRESS
        PlayersTarget.SCORE_LOG, null -> null
    }
}

/** Server first; the entry that uploaded this video, if still on the phone, follows only on success. */
private suspend fun writeVideoRow(
    videos: VideosRepository,
    localVideos: LocalVideoRepository,
    videoId: String,
    entryId: String?,
    players: MatchPlayers,
    nearSide: Side?,
): String? {
    val saved = videos.updateMatchPlayers(videoId, players, nearSide)
    if (saved.isFailure) return PlayersCopy.WHOS_PLAYING_SAVE_FAILED
    if (entryId != null && localVideos.get(entryId) != null) {
        localVideos.setMatchPlayers(entryId, players, nearSide)
    }
    return null
}
