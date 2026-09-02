import Foundation
import Shared

@Observable @MainActor
final class ClipListModel {
    let rally: RallyApp
    let analyze: AnalyzeCoordinator
    private(set) var clips: [RallyClip] = []
    private(set) var owned: [MatchSummary] = []
    private(set) var shared: [MatchSummary] = []
    /// What the owned section renders: video-backed and courtside-scored matches
    /// interleaved by date. `owned` stays as it is so nothing that reads it changes.
    private(set) var ownedRows: [MatchRow] = []
    /// The single source of truth for every video on this phone. `ClipListView`
    /// reads this rather than keeping its own copy, so the "On this phone" list and
    /// the attach status this model derives from the same entries can never disagree.
    private(set) var localEntries: [LocalVideoEntry] = []
    private(set) var thumbnailUrls: [String: URL] = [:]   // clipId -> signed URL
    var isRefreshing = false
    var error: String? = nil
    private var sharerByVideoId: [String: String] = [:]
    private var metadataByVideoId: [String: MatchMetadata] = [:]
    private var scoreCards: [ScoreMatchCard] = []
    /// Kept alongside `scoreCards` because `attachStatus` needs each log's `videoId`,
    /// which the card does carry, but also needs to be matched back to its own log id.
    private var scoreLogs: [ScoreLog] = []
    private var progressByEntryId: [String: AnalyzeProgress] = [:]
    // ClipListView's `.task` calls start() on every reappearance of the list
    // (returning from the match page, from court marking, etc.) while `model`
    // itself is created once and kept for the life of the view, so without this
    // guard each return spawned four more never-returning `for await` loops on
    // the same model, each calling regroup() on every emission.
    private var started = false

    init(rally: RallyApp, analyze: AnalyzeCoordinator) {
        self.rally = rally
        self.analyze = analyze
    }

    func start() async {
        guard !started else { return }
        started = true
        Task { await refresh() }
        // Each its own task: the clips loop at the bottom never returns, so
        // anything sequenced after it would never run.
        Task {
            for await logs in rally.scoreLogs.logs {
                scoreLogs = logs
                scoreCards = logs.map { ScoreMatchCardKt.buildScoreMatchCard(log: $0) }
                regroup()
            }
        }
        Task {
            for await entries in rally.localVideos.entries {
                localEntries = entries
                regroup()
            }
        }
        Task {
            for await map in analyze.progress {
                progressByEntryId = map
                regroup()
            }
        }
        for await latest in rally.clips.observeClips() {
            clips = latest
            regroup()
        }
    }

    func refresh() async {
        isRefreshing = true
        error = nil
        do {
            try await rally.clips.refresh()
        } catch {
            self.error = "Couldn't refresh matches. Pull to try again."
        }
        // Soft failure: leave sharerByVideoId untouched, no user-facing error (matches Android).
        if let received = try? await rally.shares.listReceived() {
            sharerByVideoId = Dictionary(
                uniqueKeysWithValues: received.compactMap { r in
                    r.sharerEmail.map { (r.videoId, $0) }
                }
            )
        }
        // Same soft-failure contract as the shares lookup: a nil result leaves the
        // previous map alone rather than blanking every name the user can see.
        if let rows = try? await SwiftInteropKt.listMatchMetadataOrNull(rally.videos) {
            metadataByVideoId = Dictionary(uniqueKeysWithValues: rows.map { ($0.videoId, $0) })
        }
        // Soft failure, same contract as the shares and metadata reads: the matches
        // are all still on this phone, so there is nothing to say and nothing for
        // the user to do about it.
        _ = try? await rally.scoreLogs.syncScoreLogsOrMessage()
        regroup()
        isRefreshing = false
    }

    func signOut() async {
        _ = try? await SwiftInteropKt.signOutOrMessage(rally.auth)
    }

    func deleteMatch(videoId: String) async {
        if let message = try? await SwiftInteropKt.deleteMatchOrMessage(rally.videos, videoId: videoId) {
            error = message
            return
        }
        rally.clips.pruneVideo(videoId: videoId)
        // The database does this too, through ON DELETE SET NULL and the unbind
        // trigger, but the phone would not learn it until the next sync and
        // would go on advertising clips for a deleted video. Idempotent against
        // the trigger, which has already done it.
        //
        // This path deletes the match outright; "remove the video, keep the
        // match" is the match page's own gesture and runs the shared
        // MatchVideoRemovalKt.removeMatchVideoOrMessage instead. The call below
        // is here to mirror the trigger above, so that a bound match deleted
        // from the list does not leave a stale binding behind on this phone
        // between the delete and the next sync.
        for log in rally.scoreLogs.logs.value where log.videoId == videoId {
            rally.scoreLogs.detachVideo(id: log.id)
        }
        await refresh()
    }

    func leaveShare(videoId: String) async {
        if let message = try? await SwiftInteropKt.leaveShareOrMessage(rally.shares, videoId: videoId) {
            error = message
            return
        }
        rally.clips.pruneVideo(videoId: videoId)
        await refresh()
    }

    /// No refresh afterwards: the repository has already dropped it locally and its
    /// flow has pushed the shorter list through `regroup()`.
    ///
    /// Returns whether the server accepted the delete, not whether the row left
    /// this phone - the repository removes it locally either way, win or lose.
    /// A caller chaining another mutation after this one (see `ClipListView`'s
    /// `.deleteBoundMatch`) needs that server outcome to decide whether it is
    /// safe to go on.
    @discardableResult
    func deleteScoreMatch(scoreLogId: String, hasVideo: Bool = false) async -> Bool {
        // The log is gone from this device either way - ScoreLogsRepository.delete
        // removes it locally unconditionally, win or lose on the server - so an
        // entry pointed at it is orphaned regardless of the outcome below, and
        // this must not be gated on that outcome. Only when nothing is in flight
        // for it: canRemoveLocalVideo already exists to protect an active
        // upload/pipeline, and cancelling one mid-flight is deliberately out of
        // scope here. Without this a settled entry - most reachably one
        // AnalyzeCoordinator's zero-rally detection left FAILED - would be
        // invisible ("On this phone" filters on scoreLogId == nil) and
        // unremovable. Mirrors Android's ClipListViewModel.deleteScoreLog.
        // Reads the repository directly rather than the cached `localEntries` -
        // that cache is only as fresh as `start()`'s loop has delivered so far,
        // and this must see the current entry even if called before that loop's
        // first emission lands. Matches Android's `localVideos.entries.value`.
        if let entry = rally.localVideos.entries.value.first(where: { $0.scoreLogId == scoreLogId }),
           LocalVideoEntryKt.canRemoveLocalVideo(stage: entry.stage) {
            rally.localVideos.remove(id: entry.id)
            rally.localAnnotations.removeAllFor(videoId: entry.id)
        }
        // do/catch, not `try?`: `try?` flattens a thrown error and a nil (success)
        // into the same case, so a throw would return true here and let the caller
        // go on to the irreversible video delete plus a resurrecting refresh().
        // Mirrors Android's `Result.isSuccess` in ClipListViewModel.deleteScoreLog.
        do {
            if let message = try await rally.scoreLogs.deleteScoreMatchOrMessage(id: scoreLogId, hasVideo: hasVideo) {
                error = message
                return false
            }
            return true
        } catch {
            self.error = SwiftInteropKt.scoreLogDeleteFailedMessage(hasVideo: hasVideo)
            return false
        }
    }

    func thumbnail(forCoverOf match: MatchSummary) async {
        guard thumbnailUrls[match.coverClipId] == nil,
              let cover = clips.first(where: { $0.id == match.coverClipId }) else { return }
        if let signed = try? await rally.media.signedThumbnailUrl(clip: cover),
           let url = URL(string: signed) {
            thumbnailUrls[match.coverClipId] = url
        }
    }

    /// What each scored match's video is doing, keyed by score log id. Calls the
    /// same shared derivation the match page's own status uses
    /// (`AttachStatusKt.scoreLogAttachStatus`, mirrored by Android's
    /// `ClipListViewModel.attachStatuses`), so the list row and the match page
    /// cannot quietly disagree about the same match.
    private func attachMap() -> [String: AttachStatus] {
        var result: [String: AttachStatus] = [:]
        for log in scoreLogs {
            let clipCount = clips.filter { $0.videoId == log.videoId }.count
            if let status = AttachStatusKt.scoreLogAttachStatus(
                log: log, entries: localEntries, progress: progressByEntryId, clipCount: Int32(clipCount)
            ) {
                result[log.id] = status
            }
        }
        return result
    }

    private func regroup() {
        let infos = clips.map(ClipInfo.init)
        let result = MatchGrouping.matches(
            from: infos,
            currentUserId: rally.auth.currentUserId(),
            sharerByVideoId: sharerByVideoId,
            metadataByVideoId: metadataByVideoId
        )
        owned = result.owned
        shared = result.shared
        ownedRows = mergeMatchRows(
            videoMatches: owned,
            scoreMatches: scoreCards,
            attachByScoreLogId: attachMap()
        )
    }
}
