package com.badmintontracker.shared.matchlist

import com.badmintontracker.shared.localvideo.AnalyzeStage
import com.badmintontracker.shared.localvideo.LocalVideoEntry
import com.badmintontracker.shared.localvideo.canEditLocalVideoDetails
import com.badmintontracker.shared.localvideo.canRemoveLocalVideo
import com.badmintontracker.shared.localvideo.cloudAnalysisStatus
import com.badmintontracker.shared.localvideo.deviceWorkLabel
import com.badmintontracker.shared.localvideo.isAnalysisRunning
import com.badmintontracker.shared.model.RallyClip
import com.badmintontracker.shared.model.playersLine
import com.badmintontracker.shared.scoring.AttachKind
import com.badmintontracker.shared.scoring.ScoreLog
import com.badmintontracker.shared.scoring.buildScoreMatchCard
import com.badmintontracker.shared.scoring.scoreLogAttachStatus
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * The match name the web app stamps onto every clip of a video at cut time: the
 * most common non-null title, so retitling one clip does not relabel the match.
 */
fun List<RallyClip>.matchTitle(): String? =
    mapNotNull { it.title }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key

/** One row per match, from everything the phone knows about its matches. */
fun buildMatchList(input: MatchListInput): MatchList = buildMatchList(input, TimeZone.currentSystemDefault())

internal fun buildMatchList(input: MatchListInput, timeZone: TimeZone): MatchList {
    val clipsByVideo = input.clips.groupBy { it.videoId }
    fun ownedVideo(videoId: String?): List<RallyClip> =
        videoId?.let(clipsByVideo::get).orEmpty().takeIf { it.firstOrNull()?.isOwnedBy(input.currentUserId) == true }.orEmpty()

    val claimed = mutableSetOf<String>()
    val mine = mutableListOf<MatchListItem>()

    // Every log id in this input, so the shared-id fallback below can tell "no
    // entry names this log yet" apart from "some entry names a *different* log
    // in this same input" - the latter must not be borrowed just because its id
    // happens to equal this log's video id, or it would be listed under both.
    val logIds = input.scoreLogs.map { it.id }.toSet()

    // Scored matches first: a video filmed for a match belongs to that match's row.
    for (log in input.scoreLogs) {
        // scoreLogId is the primary link; the shared id is a fallback for a
        // video-first import later bound to a log by video_id alone, where the
        // entry itself was never told which log claimed it. Preferring
        // scoreLogId when both could match keeps an entry that does carry the
        // right scoreLogId from ever being shadowed by a coincidental id match,
        // and excluding an entry already spoken for by another log in this same
        // input (logIds) is what keeps that entry from being listed twice.
        val entry = input.localEntries.firstOrNull { it.scoreLogId == log.id }
            ?: input.localEntries.firstOrNull { it.id == log.videoId && (it.scoreLogId == null || it.scoreLogId !in logIds) }
        val videoId = log.videoId ?: entry?.id
        val clips = ownedVideo(videoId)
        listOfNotNull(entry?.id, log.videoId).forEach { claimed += it }
        mine += scoreItem(log, entry, clips, input, timeZone)
    }

    // Videos filmed on their own. An entry naming a match is never one of these,
    // even when that match is missing from the list: the log cache starts empty on
    // a cold start, and falling back here would flash every attached video in and
    // out of the list (see orphanedLocalVideoIds for the real orphans).
    for (entry in input.localEntries) {
        if (entry.scoreLogId != null) {
            // Claimed here even when the log this entry names is not (yet) in the
            // list: the same cold-start window review focus 1 covers for the case
            // with no clips - the score-log cache starts empty on a cold start, and
            // an entry naming a log not in the list yet is that window, not an
            // orphan. Without also claiming entry.id here, the entry's own clips
            // would surface through the cloud-clips loop below as an orphaned
            // CLOUD row that knows nothing about the entry, then jump to a
            // different key once the log loads. orphanedLocalVideoIds is what
            // releases a *true* orphan - a log deleted or no longer visible to
            // this account - once an authoritative sync confirms it; this fold
            // never has that authority, so it only hides the row, it never drops it.
            claimed += entry.id
            continue
        }
        if (entry.id in claimed) continue
        claimed += entry.id
        mine += videoItem(entry, ownedVideo(entry.id), input, timeZone)
    }

    val shared = mutableListOf<MatchListItem>()
    for ((videoId, clips) in clipsByVideo) {
        if (videoId in claimed) continue
        claimed += videoId
        if (clips.first().isOwnedBy(input.currentUserId)) mine += videoItem(null, clips, input, timeZone)
        else shared += videoItem(null, clips, input, timeZone, sharer = input.sharerByVideoId[videoId])
    }

    val order = compareByDescending<MatchListItem> { it.sortAtEpochMs }.thenBy { it.key }
    return MatchList(mine = mine.sortedWith(order), shared = shared.sortedWith(order))
}

private fun RallyClip.isOwnedBy(userId: String?) = userId != null && ownerId == userId

private data class Asks(val action: MatchAction, val text: String?, val isError: Boolean)

/** What the file's own pipelines are asking for, device run first: it is the one this row's button starts. */
private fun entryAsks(entry: LocalVideoEntry, input: MatchListInput, hasRunResults: Boolean): Asks {
    val run = input.deviceRuns[entry.id]
    return when {
        run?.kind == DeviceRunKind.FAILED -> Asks(MatchAction.RETRY, "Analysis failed: ${run.message ?: "Unknown error"}", true)
        run?.kind == DeviceRunKind.PAUSED -> Asks(MatchAction.PAUSED, run.message ?: "Paused", false)
        run?.kind == DeviceRunKind.RUNNING -> Asks(MatchAction.RUNNING, deviceWorkLabel(run.phase, null) + "…", false)
        isAnalysisRunning(entry.stage) ->
            Asks(MatchAction.RUNNING, cloudAnalysisStatus(entry.stage, input.cloudProgress[entry.id]) ?: "Analyzing…", false)
        entry.stage == AnalyzeStage.FAILED -> Asks(MatchAction.RETRY, entry.failureMessage ?: "Analysis failed", true)
        hasRunResults -> Asks(MatchAction.NONE, null, false)
        // Finished in the cloud, but this phone has not seen the clips: offline,
        // or deleted elsewhere. Asking to analyze it again would be a button that
        // does nothing (canAnalyze is false for ANALYZED).
        entry.stage == AnalyzeStage.ANALYZED -> Asks(MatchAction.NONE, MatchListCopy.ANALYZED, false)
        else -> Asks(MatchAction.ANALYZE, MatchListCopy.NOT_ANALYZED, false)
    }
}

private fun deviceRunHoldsFile(entry: LocalVideoEntry, input: MatchListInput): Boolean =
    input.deviceRuns[entry.id]?.kind.let { it == DeviceRunKind.RUNNING || it == DeviceRunKind.PAUSED }

private fun analysesFor(entry: LocalVideoEntry?, videoId: String?, cloudClips: Int, input: MatchListInput): Pair<List<MatchAnalysis>, String?> {
    val ids = listOfNotNull(entry?.id, videoId).distinct()
    val deviceClips = entry?.let { input.deviceClipCounts[it.id] } ?: 0
    val heatmapId = ids.firstOrNull { it in input.storedTrackIds }
    val list = buildList {
        if (cloudClips > 0 || deviceClips > 0) add(MatchAnalysis.RALLIES)
        if (heatmapId != null) add(MatchAnalysis.HEATMAP)
        // Drawn over playback, so only while the file is here.
        if (entry != null && ids.any { it in input.storedSkeletonIds }) add(MatchAnalysis.SKELETON)
    }
    return list to heatmapId
}

private fun whereOf(entry: LocalVideoEntry?, cloudClips: Int): MatchWhere? = when {
    entry != null && cloudClips > 0 -> MatchWhere.BOTH
    entry != null -> MatchWhere.PHONE
    cloudClips > 0 -> MatchWhere.CLOUD
    else -> null
}

private fun videoItem(
    entry: LocalVideoEntry?,
    clips: List<RallyClip>,
    input: MatchListInput,
    timeZone: TimeZone,
    sharer: String? = null,
): MatchListItem {
    val id = entry?.id ?: clips.first().videoId
    val videoId = id.takeIf { clips.isNotEmpty() }
    val meta = input.metadataByVideoId[id]
    val deviceClips = entry?.let { input.deviceClipCounts[it.id] } ?: 0
    val (analyses, heatmapId) = analysesFor(entry, videoId, clips.size, input)
    val asks = if (entry != null) entryAsks(entry, input, analyses.isNotEmpty()) else Asks(MatchAction.NONE, null, false)
    val sortAt = entry?.addedAtEpochMs ?: clips.minOf { it.createdAt }.toEpochMilliseconds()
    val rallies = if (clips.isNotEmpty()) clips.size else deviceClips
    val detail = listOfNotNull(
        ralliesText(rallies),
        entry?.takeIf { rallies == 0 }?.let { formatClock(it.durationMs) },
        formatMatchDate(sortAt, timeZone),
    ).joinToString(" · ")
    val where = whereOf(entry, clips.size)
    val owned = sharer == null && (entry != null || clips.first().isOwnedBy(input.currentUserId))
    val removable = entry != null && canRemoveLocalVideo(entry.stage) && !deviceRunHoldsFile(entry, input)
    return MatchListItem(
        key = "video-$id",
        sortAtEpochMs = sortAt,
        title = meta?.title ?: entry?.title ?: clips.matchTitle() ?: entry?.displayName
            ?: "Match · ${formatMatchDate(sortAt, timeZone)}",
        detail = detail,
        playersLine = meta?.playersLine(),
        sharedBy = sharer?.let { "Shared by $it" },
        where = where,
        analyses = analyses,
        statusText = asks.text,
        statusIsError = asks.isError,
        action = asks.action,
        entryId = entry?.id,
        videoId = videoId,
        scoreLogId = null,
        localUri = entry?.uri,
        coverClip = clips.minByOrNull { it.rallyIndex },
        scoreText = null,
        isOwned = owned,
        canShare = owned && videoId != null,
        canEditDetails = entry != null && canEditLocalVideoDetails(entry.stage),
        canAnalyzeAgain = entry != null && entry.stage == AnalyzeStage.LOCAL &&
            asks.action == MatchAction.NONE && analyses.isNotEmpty(),
        canRemoveFromPhone = removable,
        delete = when {
            !owned -> MatchDelete.LEAVE_SHARE
            videoId == null -> MatchDelete.NONE
            // Deleting the cloud match also discards the file, so it waits for
            // the same thing removing the file waits for.
            entry != null && !removable -> MatchDelete.NONE
            else -> MatchDelete.VIDEO_MATCH
        },
        opensLocalPlayer = entry != null && videoId == null,
        deviceClipCount = deviceClips,
        heatmapId = heatmapId,
        accessibilityStatus = statusDescription(where, analyses, asks.text),
    )
}

private fun scoreItem(
    log: ScoreLog,
    entry: LocalVideoEntry?,
    clips: List<RallyClip>,
    input: MatchListInput,
    timeZone: TimeZone,
): MatchListItem {
    val card = buildScoreMatchCard(log)
    val state = log.state()
    val started = state.points.isNotEmpty() || state.completedGames.isNotEmpty()
    val videoId = clips.firstOrNull()?.videoId
    val (runAnalyses, heatmapId) = analysesFor(entry, videoId, clips.size, input)
    val analyses = if (started) runAnalyses + MatchAnalysis.SCORE else runAnalyses
    val attach = scoreLogAttachStatus(log, input.localEntries, input.cloudProgress, clips.size)
    val asks = when (attach?.kind) {
        AttachKind.COURT_NOT_MARKED -> Asks(MatchAction.MARK_COURT, attach.text, false)
        AttachKind.FAILED -> Asks(MatchAction.RETRY, attach.text, true)
        AttachKind.UPLOADING, AttachKind.CLIPPING, AttachKind.FINISHING_UP -> Asks(MatchAction.RUNNING, attach.text, false)
        null -> entry?.let { e ->
            // Only the device run can still be asking here: the cloud stages are
            // what the attach status above already speaks for.
            entryAsks(e, input, hasRunResults = true).takeIf { input.deviceRuns[e.id] != null }
        } ?: Asks(MatchAction.NONE, null, false)
    }
    val rallies = clips.size.takeIf { it > 0 } ?: (entry?.let { input.deviceClipCounts[it.id] } ?: 0)
    val where = whereOf(entry, clips.size)
    val removable = entry != null && canRemoveLocalVideo(entry.stage) && !deviceRunHoldsFile(entry, input)
    return MatchListItem(
        key = "score-${log.id}",
        sortAtEpochMs = card.createdAtEpochMs,
        title = card.title,
        detail = listOfNotNull(card.scoreLine, ralliesText(rallies), formatMatchDate(card.createdAtEpochMs, timeZone))
            .joinToString(" · "),
        playersLine = card.playersLine,
        sharedBy = null,
        where = where,
        analyses = analyses,
        statusText = asks.text,
        statusIsError = asks.isError,
        action = asks.action,
        entryId = entry?.id,
        videoId = videoId,
        scoreLogId = log.id,
        localUri = entry?.uri,
        coverClip = clips.minByOrNull { it.rallyIndex },
        // The last game, which is the one a coach remembers: "21-18, 5-3" does not fit a 64dp tile.
        scoreText = card.scoreLine.takeIf { started }?.substringAfterLast(", "),
        isOwned = true,
        canShare = videoId != null,
        canEditDetails = false,
        canAnalyzeAgain = false,
        canRemoveFromPhone = removable,
        delete = when {
            entry != null && !removable -> MatchDelete.NONE
            videoId != null -> MatchDelete.BOUND_MATCH
            else -> MatchDelete.SCORE_MATCH
        },
        opensLocalPlayer = false,
        deviceClipCount = entry?.let { input.deviceClipCounts[it.id] } ?: 0,
        heatmapId = heatmapId,
        accessibilityStatus = statusDescription(where, analyses, asks.text),
    )
}

private fun ralliesText(count: Int): String? =
    count.takeIf { it > 0 }?.let { "$it ${if (it == 1) "rally" else "rallies"}" }

private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/** "Sep 8, 2026": the date every match list in the app prints. */
internal fun formatMatchDate(epochMs: Long, timeZone: TimeZone): String {
    val d = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(timeZone)
    return "${MONTHS[d.monthNumber - 1]} ${d.dayOfMonth}, ${d.year}"
}

/** "0:48". */
internal fun formatClock(ms: Long): String {
    val s = ms / 1000
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}
