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

/** Which record a who's-playing save writes to. */
enum class PlayersTarget { SCORE_LOG, LOCAL_ENTRY, VIDEO_ROW, UPLOADING }

/**
 * Where a who's-playing save goes for a match, or null when there is nothing to
 * write to. In order: the score log when there is one (it speaks for the match);
 * the local entry while no videos row exists yet (stage LOCAL, or a failure at
 * UPLOAD or CREATE_ROW, which both come before the INSERT); nothing during the
 * upload, because the entry's values are being copied onto the INSERT and a write
 * now could land on neither; and the videos row once [videoId] is known.
 */
fun playersTarget(scoreLogId: String?, entry: LocalVideoEntry?, videoId: String?): PlayersTarget? {
    if (scoreLogId != null) return PlayersTarget.SCORE_LOG
    if (entry != null) {
        val noRowYet = entry.stage == AnalyzeStage.LOCAL ||
            (entry.stage == AnalyzeStage.FAILED &&
                (entry.failedStep == AnalyzeStep.UPLOAD || entry.failedStep == AnalyzeStep.CREATE_ROW))
        if (noRowYet) return PlayersTarget.LOCAL_ENTRY
        if (entry.stage == AnalyzeStage.UPLOADING) return PlayersTarget.UPLOADING
    }
    return if (videoId != null) PlayersTarget.VIDEO_ROW else null
}

/**
 * The one save behind every who's-playing surface. Returns null on success or the
 * sentence to show; a problem with [players] under [format] is returned before
 * anything is written.
 *
 * A score log's assignment stands for the match, so a log is saved first and the
 * video, if one is attached, only learns [nearSide] (with nobody on it: the log
 * speaks for the players), routed by the video's own target exactly as for a match
 * that was only ever filmed. A videos row is written before the entry that
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
    matchPlayersProblem(players, format)?.let { return it }
    val clean = players.copy(
        homePlayers = submittedPlayers(players.homePlayers, format),
        awayPlayers = submittedPlayers(players.awayPlayers, format),
    )
    return when (target) {
        PlayersTarget.UPLOADING -> PlayersCopy.UPLOAD_IN_PROGRESS
        PlayersTarget.LOCAL_ENTRY -> {
            if (entryId == null) return PlayersCopy.WHOS_PLAYING_SAVE_FAILED
            localVideos.setMatchPlayers(entryId, clean, nearSide)
            null
        }
        PlayersTarget.VIDEO_ROW -> {
            if (videoId == null) return PlayersCopy.WHOS_PLAYING_SAVE_FAILED
            writeVideoRow(videos, localVideos, videoId, entryId, clean, nearSide)
        }
        PlayersTarget.SCORE_LOG -> {
            if (scoreLogId == null) return PlayersCopy.WHOS_PLAYING_SAVE_FAILED
            scoreLogs.assignPlayers(scoreLogId, clean)
            saveEndOfAttachedVideo(videos, localVideos, videoId, entryId, nearSide)
        }
    }
}

/** Which end the home side was at, for the video a score log is attached to. */
private suspend fun saveEndOfAttachedVideo(
    videos: VideosRepository,
    localVideos: LocalVideoRepository,
    videoId: String?,
    entryId: String?,
    nearSide: Side?,
): String? {
    val entry = entryId?.let { localVideos.get(it) }
    return when (playersTarget(null, entry, videoId)) {
        PlayersTarget.LOCAL_ENTRY -> {
            localVideos.setMatchPlayers(entry!!.id, MatchPlayers.NONE, nearSide)
            null
        }
        PlayersTarget.VIDEO_ROW -> writeVideoRow(videos, localVideos, videoId!!, entryId, MatchPlayers.NONE, nearSide)
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
