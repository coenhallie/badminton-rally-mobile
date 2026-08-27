import Foundation
import Shared

@Observable @MainActor
final class ClipListModel {
    let rally: RallyApp
    private(set) var clips: [RallyClip] = []
    private(set) var owned: [MatchSummary] = []
    private(set) var shared: [MatchSummary] = []
    /// What the owned section renders: video-backed and courtside-scored matches
    /// interleaved by date. `owned` stays as it is so nothing that reads it changes.
    private(set) var ownedRows: [MatchRow] = []
    private(set) var thumbnailUrls: [String: URL] = [:]   // clipId -> signed URL
    var isRefreshing = false
    var error: String? = nil
    private var sharerByVideoId: [String: String] = [:]
    private var metadataByVideoId: [String: MatchMetadata] = [:]
    private var scoreCards: [ScoreMatchCard] = []

    init(rally: RallyApp) { self.rally = rally }

    func start() async {
        Task { await refresh() }
        // Its own task: the clips loop below never returns, so anything after it
        // would never run.
        Task {
            for await logs in rally.scoreLogs.logs {
                scoreCards = logs.map { ScoreMatchCardKt.buildScoreMatchCard(log: $0) }
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
        ownedRows = mergeMatchRows(videoMatches: owned, scoreMatches: scoreCards)
    }
}
