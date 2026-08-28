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
    private var uploadPercentByEntryId: [String: Int] = [:]

    init(rally: RallyApp, analyze: AnalyzeCoordinator) {
        self.rally = rally
        self.analyze = analyze
    }

    func start() async {
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
                let progress = (map as? [String: AnalyzeProgress]) ?? [:]
                // Truncates rather than rounds, matching Kotlin's `(it * 100).toInt()`.
                uploadPercentByEntryId = progress.compactMapValues { entry in
                    (entry.uploadProgress?.floatValue).map { Int($0 * 100) }
                }
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
    func deleteScoreMatch(scoreLogId: String) async {
        if let message = try? await rally.scoreLogs.deleteScoreMatchOrMessage(id: scoreLogId) {
            error = message
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

    /// What each scored match's video is doing, keyed by score log id. Mirrors
    /// Android's `ClipListViewModel.attachStatuses`: needs the score logs
    /// themselves (for `videoId`), each match's local entry, the coordinator's
    /// transient upload progress, and how many clips the match already has.
    private func attachMap() -> [String: AttachStatus] {
        var result: [String: AttachStatus] = [:]
        for log in scoreLogs {
            let entry = localEntries.first { $0.scoreLogId == log.id }
            let percent = entry.flatMap { uploadPercentByEntryId[$0.id] }
            let clipCount = clips.filter { $0.videoId == log.videoId }.count
            if let status = AttachStatusKt.attachStatus(
                hasVideo: log.videoId != nil,
                entry: entry,
                uploadPercent: percent.map { KotlinInt(int: Int32($0)) },
                clipCount: Int32(clipCount)
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
