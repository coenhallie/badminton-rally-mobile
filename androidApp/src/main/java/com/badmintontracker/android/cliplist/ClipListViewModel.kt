package com.badmintontracker.android.cliplist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.badmintontracker.shared.localvideo.AnalyzeCoordinator
import com.badmintontracker.shared.localvideo.LocalAnnotationsRepository
import com.badmintontracker.shared.localvideo.LocalVideoRepository
import com.badmintontracker.shared.localvideo.canRemoveLocalVideo
import com.badmintontracker.shared.model.MatchMetadata
import com.badmintontracker.shared.model.RallyClip
import com.badmintontracker.shared.repo.AuthRepository
import com.badmintontracker.shared.repo.ClipsRepository
import com.badmintontracker.shared.repo.SharesRepository
import com.badmintontracker.shared.repo.VideosRepository
import com.badmintontracker.shared.scoring.ScoreLogsRepository
import com.badmintontracker.shared.scoring.buildScoreMatchCard
import com.badmintontracker.shared.scoring.scoreLogAttachStatus
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant

data class MatchSummary(
    val videoId: String,
    val rallyCount: Int,
    val latestCreatedAt: Instant,
    val coverClip: RallyClip,
    val isOwned: Boolean,
    val sharerEmail: String? = null,
    /** Match name, from videos.title (typed on the phone or in the web app). */
    val title: String? = null,
    /** Match description, from videos.description. Phone-only; the web app has no field. */
    val description: String? = null,
)

data class ClipListState(
    val clips: List<RallyClip> = emptyList(),
    val ownedMatches: List<MatchSummary> = emptyList(),
    /**
     * What the owned section actually renders: video-backed matches and
     * courtside-scored ones interleaved by date. [ownedMatches] stays as it is so
     * nothing that already reads it has to change.
     */
    val ownedRows: List<MatchRow> = emptyList(),
    val sharedMatches: List<MatchSummary> = emptyList(),
    val isRefreshing: Boolean = false,
    val error: String? = null,
)

/**
 * The match name the web app stamps onto every clip of a video at cut time.
 * Takes the most common non-null title rather than the cover clip's, so
 * retitling a single clip in this app doesn't relabel the whole match.
 */
internal fun List<RallyClip>.matchTitle(): String? =
    mapNotNull { it.title }
        .groupingBy { it }
        .eachCount()
        .maxByOrNull { it.value }
        ?.key

/**
 * Label for a single rally row. Every clip of a match carries the match name,
 * so showing it again per row would make the rallies indistinguishable — fall
 * back to the rally number unless the user gave this clip its own title.
 */
internal fun clipRowTitle(clip: RallyClip, matchTitle: String?): String =
    clip.title?.takeIf { it != matchTitle } ?: "Rally #${clip.rallyIndex}"

internal fun List<RallyClip>.toMatches(
    currentUserId: String?,
    sharerByVideoId: Map<String, String>,
    metadataByVideoId: Map<String, MatchMetadata>,
): List<MatchSummary> =
    groupBy { it.videoId }
        .map { (videoId, list) ->
            val cover = list.minByOrNull { it.rallyIndex } ?: list.first()
            val owned = currentUserId != null && cover.ownerId == currentUserId
            MatchSummary(
                videoId = videoId,
                rallyCount = list.size,
                latestCreatedAt = list.maxOf { it.createdAt },
                coverClip = cover,
                isOwned = owned,
                sharerEmail = if (owned) null else sharerByVideoId[videoId],
                // videos.title is authoritative; the clip-stamped copy is the
                // fallback that keeps names on screen when the RPC is unreachable.
                title = metadataByVideoId[videoId]?.title ?: list.matchTitle(),
                description = metadataByVideoId[videoId]?.description,
            )
        }
        .sortedByDescending { it.latestCreatedAt }

class ClipListViewModel(
    private val clips: ClipsRepository,
    private val auth: AuthRepository,
    private val shares: SharesRepository,
    private val videos: VideosRepository,
    private val scoreLogs: ScoreLogsRepository,
    private val localVideos: LocalVideoRepository,
    private val coordinator: AnalyzeCoordinator,
    private val localAnnotations: LocalAnnotationsRepository,
) : ViewModel() {
    private val refreshing      = MutableStateFlow(true)
    private val errors          = MutableStateFlow<String?>(null)
    private val sharerByVideoId = MutableStateFlow<Map<String, String>>(emptyMap())
    private val metadataByVideoId = MutableStateFlow<Map<String, MatchMetadata>>(emptyMap())

    private val scoreCards = scoreLogs.logs.map { logs -> logs.map(::buildScoreMatchCard) }

    /**
     * What each scored match's video is doing, keyed by score log id. Built here
     * because it needs four sources at once: the score logs themselves, each
     * match's local entry, the coordinator's transient progress, and how many
     * clips the match already has.
     */
    private val attachStatuses = combine(
        localVideos.entries,
        coordinator.progress,
        scoreLogs.logs,
        clips.observeClips(),
    ) { entries, progress, logs, allClips ->
        logs.mapNotNull { log ->
            scoreLogAttachStatus(
                log = log,
                entries = entries,
                progress = progress,
                clipCount = allClips.count { it.videoId == log.videoId },
            )?.let { log.id to it }
        }.toMap()
    }

    val state = combine(
        clips.observeClips(),
        sharerByVideoId,
        metadataByVideoId,
        refreshing,
        errors,
    ) { list, sharerMap, metadataMap, r, e ->
        val matches = list.toMatches(auth.currentUserId(), sharerMap, metadataMap)
        val (owned, shared) = matches.partition { it.isOwned }
        ClipListState(
            clips = list,
            ownedMatches = owned,
            sharedMatches = shared,
            isRefreshing = r,
            error = e,
        )
    }.combine(scoreCards) { base, cards -> base to cards }
        .combine(attachStatuses) { (base, cards), attach ->
            base.copy(ownedRows = mergeMatchRows(base.ownedMatches, cards, attach))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ClipListState())

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            refreshing.value = true
            coroutineScope {
                val clipsJob = async {
                    runCatching { clips.refresh() }
                        .onFailure { errors.value = "Couldn't refresh matches. Pull to try again." }
                }
                val sharesJob = async {
                    runCatching { shares.listReceived() }
                        .onSuccess { received ->
                            sharerByVideoId.value =
                                received.mapNotNull { r -> r.sharerEmail?.let { r.videoId to it } }.toMap()
                        }
                        // Soft failure: leave sharerByVideoId untouched, no user-facing error.
                }
                val metadataJob = async {
                    videos.listMatchMetadata()
                        .onSuccess { rows -> metadataByVideoId.value = rows.associateBy { it.videoId } }
                    // Soft failure, same contract as the shares lookup: leave the
                    // previous map alone and say nothing. A missing title degrades
                    // to "Match · <date>", which is not worth interrupting for —
                    // and emptying the map would visibly wipe every name on a blip.
                }
                val scoreLogsJob = async {
                    // Soft failure, same contract as the shares and metadata reads:
                    // the matches are all still on this phone, so there is nothing
                    // for the user to do about a failed sync and nothing to say.
                    scoreLogs.sync()
                }
                clipsJob.await()
                sharesJob.await()
                metadataJob.await()
                scoreLogsJob.await()
            }
            refreshing.value = false
        }
    }

    fun signOut() = viewModelScope.launch { auth.signOut() }
    fun dismissError() { errors.value = null }

    private suspend fun deleteMatchVideo(videoId: String) {
        videos.deleteMatch(videoId)
            .onSuccess {
                clips.pruneVideo(videoId)
                // The database does this too, through ON DELETE SET NULL and the
                // unbind trigger, but the phone would not learn it until the next
                // sync and would go on advertising clips for a deleted video.
                // Idempotent against the trigger, which has already done it.
                //
                // This path deletes the match outright; "remove the video, keep the
                // match" is the match page's own gesture and runs the shared
                // removeMatchVideoOrMessage instead. The call below is here to
                // mirror the trigger above, so that a bound match deleted from
                // the list does not leave a stale binding behind on this phone
                // between the delete and the next sync.
                scoreLogs.logs.value
                    .filter { it.videoId == videoId }
                    .forEach { scoreLogs.detachVideo(it.id) }
                refresh()
            }
            .onFailure { errors.value = "Couldn't delete the match. Please try again." }
    }

    fun deleteMatch(videoId: String) {
        viewModelScope.launch { deleteMatchVideo(videoId) }
    }

    /**
     * Returns whether the server accepted the delete, not whether the row left
     * this device: [ScoreLogsRepository.delete] removes it locally either way,
     * win or lose. Callers that chain another mutation after this one - see
     * [deleteBoundMatch] - need that server outcome to decide whether it is
     * safe to go on.
     *
     * [hasVideo] picks the failure wording: a bound match's delete is two things
     * at once (this call, then [deleteMatchVideo]), and a failure here stops
     * before the second half ever runs, so the video and its clips are left
     * completely untouched, not merely undeleted on the server. Saying only
     * "it's gone from this phone" is true of the match and silent about that,
     * which reads as the whole gesture having landed. With the score_logs
     * migration currently unapplied on the server this is not a rare failure:
     * it is the only outcome a bound match's delete has today.
     */
    private suspend fun deleteScoreLog(scoreLogId: String, hasVideo: Boolean = false): Boolean {
        val result = scoreLogs.delete(scoreLogId)
        // The log is gone from this device either way - delete() removes it
        // locally unconditionally, win or lose on the server - so an entry
        // pointed at it is orphaned regardless of the server outcome below, and
        // this must not be gated on result.isSuccess. Only when nothing is in
        // flight for it: canRemoveLocalVideo already exists to protect an active
        // upload/pipeline, and cancelling one mid-flight is deliberately out of
        // scope here. Without this a settled entry - most reachably one
        // AnalyzeCoordinator's zero-rally detection left FAILED - would be
        // invisible ("On this phone" filters on scoreLogId == null) and
        // unremovable.
        localVideos.entries.value
            .firstOrNull { it.scoreLogId == scoreLogId }
            ?.takeIf { canRemoveLocalVideo(it.stage) }
            ?.let { entry ->
                localVideos.remove(entry.id)
                localAnnotations.removeAllFor(entry.id)
            }
        result.onFailure {
            errors.value = if (hasVideo) {
                "Couldn't delete the match everywhere. The match is gone from this phone, but its video and clips are still there."
            } else {
                "Couldn't delete the match everywhere. It's gone from this phone."
            }
        }
        return result.isSuccess
    }

    /**
     * There is no leaveShare counterpart: match_shares is keyed on video_id, so a
     * score-only match cannot be shared and therefore cannot be left.
     */
    fun deleteScoreMatch(scoreLogId: String) {
        viewModelScope.launch { deleteScoreLog(scoreLogId) }
    }

    /**
     * A bound row with clips deletes two things at once. Sequenced in one
     * coroutine, score log first: [deleteMatchVideo]'s success path calls
     * [refresh], which syncs score logs, and if that sync landed while the score
     * log's own delete was still on the wire, it would pull the row back from
     * the server and resurrect the match the user just deleted.
     *
     * If the score log's server delete fails, [deleteMatchVideo] does not run
     * at all, rather than running without the refresh that would otherwise
     * resurrect it: the row is already gone from this device (the failure
     * message says so), and the video and its clips are left untouched rather
     * than deleted on a best-effort basis while the coach cannot see whether
     * the rest of the delete actually landed.
     */
    fun deleteBoundMatch(videoId: String, scoreLogId: String) {
        viewModelScope.launch {
            if (deleteScoreLog(scoreLogId, hasVideo = true)) {
                deleteMatchVideo(videoId)
            }
        }
    }

    fun leaveShare(videoId: String) {
        viewModelScope.launch {
            shares.leaveShare(videoId)
                .onSuccess { clips.pruneVideo(videoId); refresh() }
                .onFailure { errors.value = "Couldn't remove the shared match. Please try again." }
        }
    }
}
