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
import com.badmintontracker.shared.scoring.attachStatusFor
import com.badmintontracker.shared.scoring.buildScoreMatchCard
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
    val logIds = input.scoreLogs.map { it.id }.toSet()

    // (a) Every log with a video id claims it, first log in input order wins a
    // collision - a data anomaly (two logs bound to the same video), but the
    // fold must still pick one row deterministically rather than let both draw
    // the same clips (the bug this whole join exists to rule out). log.videoId
    // is written by the cloud CREATE_ROW step once a match is bound there, and
    // is the authority over a cached local scoreLogId that might point a
    // different entry at the same video (see (b)): the server's binding wins
    // over whatever the phone remembers on its own.
    val videoClaimedBy = mutableMapOf<String, String>() // video id -> the log.id that claimed it
    val logVideoId = mutableMapOf<String, String?>() // log.id -> the video id it kept, if any
    for (log in input.scoreLogs) {
        val videoId = log.videoId
        logVideoId[log.id] = if (videoId != null && videoId !in videoClaimedBy) {
            videoClaimedBy[videoId] = log.id
            videoId
        } else {
            null
        }
    }

    // (b)+(c) Each entry is assigned to at most one log: to the log that
    // claimed its id in (a) above - the server's binding wins over the entry's
    // own scoreLogId when the two disagree; else to the log its scoreLogId
    // names, if that log is actually in this input; else it is hidden
    // (scoreLogId names a log not yet loaded - the same cold-start window
    // review focus 1 covers) if it does carry a scoreLogId, or standalone if
    // it does not. A log keeps only the first entry assigned to it in input
    // order; a later one falls through to standalone if it carries no
    // scoreLogId of its own, or is hidden alongside the other orphaned kind
    // otherwise - it must not become a second row for a match another entry
    // already claimed.
    //
    // Two passes, so that "first in input order" never decides between the two
    // kinds of claim: every entry a log's video id names is placed first, then
    // the rest by scoreLogId. In one pass an entry naming l1 by scoreLogId,
    // listed before the entry l1's video id names, took l1's row, and the
    // video's own entry - its id already claimed by l1 below - had no row at
    // all.
    val logEntry = mutableMapOf<String, LocalVideoEntry>()
    val standaloneEntries = mutableListOf<LocalVideoEntry>()
    val (byVideoId, byScoreLogId) = input.localEntries.partition { it.id in videoClaimedBy }
    for (entry in byVideoId + byScoreLogId) {
        val claimerLogId = videoClaimedBy[entry.id]
        val targetLogId = claimerLogId ?: entry.scoreLogId?.takeIf { it in logIds }
        when {
            targetLogId != null && targetLogId !in logEntry -> logEntry[targetLogId] = entry
            targetLogId != null -> if (entry.scoreLogId == null) standaloneEntries += entry else claimed += entry.id
            // scoreLogId names a log not (yet) in the list: hidden rather than
            // shown as an orphaned row, per review focus 1. Claiming the id
            // here (without a row) is what stops its clips, if any have
            // already synced, from surfacing through the cloud-clips loop
            // below as a row that knows nothing about this entry, then
            // jumping to a different key once the log loads.
            // orphanedLocalVideoIds is what releases a *true* orphan - a log
            // deleted or no longer visible to this account - once an
            // authoritative sync confirms it; this fold never has that
            // authority, so it only hides the row, it never drops it.
            entry.scoreLogId != null -> claimed += entry.id
            else -> standaloneEntries += entry
        }
    }

    // Scored matches first: a video filmed for a match belongs to that match's row.
    for (log in input.scoreLogs) {
        val entry = logEntry[log.id]
        // (d) The log's own claimed video id wins; failing that, its entry's
        // id stands in, as long as nothing else already claimed it (a log that
        // lost its own video id to an earlier collision in (a) must not then
        // also take an id some other row already owns).
        val videoId = logVideoId[log.id] ?: entry?.id?.takeIf { it !in claimed }
        val clips = ownedVideo(videoId)
        listOfNotNull(entry?.id, videoId).forEach { claimed += it }
        mine += scoreItem(log, entry, clips, input, timeZone)
    }

    // Videos filmed on their own. (e) Skip anything a score row already claimed.
    for (entry in standaloneEntries) {
        if (entry.id in claimed) continue
        claimed += entry.id
        mine += videoItem(entry, ownedVideo(entry.id), input, timeZone)
    }

    val shared = mutableListOf<MatchListItem>()
    for ((videoId, clips) in clipsByVideo) {
        // (e) Skip anything a score row already claimed.
        if (videoId in claimed) continue
        claimed += videoId
        if (clips.first().isOwnedBy(input.currentUserId)) mine += videoItem(null, clips, input, timeZone)
        else shared += videoItem(null, clips, input, timeZone, sharer = input.sharerByVideoId[videoId])
    }

    return MatchList(mine = mine.sortedWith(NEWEST_FIRST), shared = shared.sortedWith(NEWEST_FIRST))
}

private fun RallyClip.isOwnedBy(userId: String?) = userId != null && ownerId == userId

private data class Asks(val action: MatchAction, val text: String?, val isError: Boolean)

/** What the file's own pipelines are asking for, device run first: it is the one this row's button starts. */
private fun entryAsks(entry: LocalVideoEntry, input: MatchListInput, hasRunResults: Boolean): Asks {
    val run = input.deviceRuns[entry.id]
    return when {
        run?.kind == DeviceRunKind.FAILED -> Asks(MatchAction.RETRY, MatchListCopy.analysisFailed(run.message), true)
        run?.kind == DeviceRunKind.PAUSED -> Asks(MatchAction.PAUSED, run.message ?: MatchListCopy.PAUSED, false)
        run?.kind == DeviceRunKind.RUNNING -> Asks(MatchAction.RUNNING, deviceWorkLabel(run.phase, null) + "…", false)
        isAnalysisRunning(entry.stage) ->
            Asks(MatchAction.RUNNING, cloudAnalysisStatus(entry.stage, input.cloudProgress[entry.id]) ?: "Analyzing…", false)
        entry.stage == AnalyzeStage.FAILED -> Asks(MatchAction.RETRY, entry.failureMessage ?: MatchListCopy.ANALYSIS_FAILED, true)
        hasRunResults -> Asks(MatchAction.NONE, null, false)
        // Finished in the cloud, but this phone has not seen the clips: offline,
        // or deleted elsewhere. Asking to analyze it again would be a button that
        // does nothing.
        entry.stage == AnalyzeStage.ANALYZED -> Asks(MatchAction.NONE, MatchListCopy.ANALYZED, false)
        else -> Asks(MatchAction.ANALYZE, MatchListCopy.NOT_ANALYZED, false)
    }
}

/**
 * The cloud clips' notes plus the notes made on this phone's copy. Two stores
 * that never meet: a phone note lives only in the phone's settings and nothing
 * uploads it, and `annotation_count` counts only rally_annotations rows, which
 * only a clip's own page writes. So adding them counts no note twice.
 */
private fun noteCount(entry: LocalVideoEntry?, clips: List<RallyClip>, input: MatchListInput): Int =
    clips.sumOf { it.annotationCount } + (entry?.let { input.localNoteCounts[it.id] } ?: 0)

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
        sharedBy = sharer?.let(MatchListCopy::sharedBy),
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
        deviceClipCount = deviceClips,
        rallyCount = rallies,
        noteCount = noteCount(entry, clips, input),
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
    // attachStatusFor with this row's own [entry], not an independent lookup
    // by score log: the row's status must come from the entry the join above
    // actually assigned it, or the two could disagree about which entry a
    // log's video is.
    val attach = attachStatusFor(log, entry, input.cloudProgress, clips.size)
        // A device run in flight, or what one left, proves the court was
        // marked, even on an entry that has no saved points: iOS did not save
        // them for a device run before 2026-09-27, and those entries keep
        // their results.
        ?.takeUnless {
            it.kind == AttachKind.COURT_NOT_MARKED && entry != null &&
                (runAnalyses.isNotEmpty() || input.deviceRuns[entry.id] != null)
        }
    val asks = when (attach?.kind) {
        AttachKind.COURT_NOT_MARKED -> Asks(MatchAction.MARK_COURT, attach.text, false)
        AttachKind.FAILED -> Asks(MatchAction.RETRY, attach.text, true)
        AttachKind.UPLOADING, AttachKind.CLIPPING, AttachKind.FINISHING_UP -> Asks(MatchAction.RUNNING, attach.text, false)
        null -> entry?.let { e ->
            // The device run, or for a LOCAL entry what the phone has done with
            // it: a device run never leaves LOCAL, so its running line, its
            // retry, its results or "Not analyzed" are all this row has to say.
            // Any other stage is the cloud's, which the attach status above
            // already speaks for.
            entryAsks(e, input, hasRunResults = runAnalyses.isNotEmpty())
                .takeIf { input.deviceRuns[e.id] != null || e.stage == AnalyzeStage.LOCAL }
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
        deviceClipCount = entry?.let { input.deviceClipCounts[it.id] } ?: 0,
        rallyCount = rallies,
        noteCount = noteCount(entry, clips, input),
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
