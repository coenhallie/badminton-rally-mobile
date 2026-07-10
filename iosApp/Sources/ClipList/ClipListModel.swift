import Foundation
import Shared

@Observable @MainActor
final class ClipListModel {
    let rally: RallyApp
    private(set) var clips: [RallyClip] = []
    private(set) var owned: [MatchSummary] = []
    private(set) var shared: [MatchSummary] = []
    private(set) var thumbnailUrls: [String: URL] = [:]   // clipId -> signed URL
    var isRefreshing = false
    var error: String? = nil
    private var sharerByVideoId: [String: String] = [:]

    init(rally: RallyApp) { self.rally = rally }

    func start() async {
        Task { await refresh() }
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
        regroup()
        isRefreshing = false
    }

    func signOut() async {
        _ = try? await SwiftInteropKt.signOutOrMessage(rally.auth)
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
            sharerByVideoId: sharerByVideoId
        )
        owned = result.owned
        shared = result.shared
    }
}
