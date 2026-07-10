import Foundation
import Shared

/// Swift value mirror of RallyClip so grouping is unit-testable without Kotlin construction.
struct ClipInfo: Equatable {
    let id: String
    let videoId: String
    let ownerId: String
    let rallyIndex: Int32
    let createdAtMillis: Int64
    let title: String?
    let durationSeconds: Float
    let annotationCount: Int32
}

extension ClipInfo {
    init(_ clip: RallyClip) {
        self.init(
            id: clip.id, videoId: clip.videoId, ownerId: clip.ownerId,
            rallyIndex: clip.rallyIndex, createdAtMillis: clip.createdAt.toEpochMilliseconds(),
            title: clip.title, durationSeconds: clip.durationSeconds,
            annotationCount: clip.annotationCount
        )
    }
}

struct MatchSummary: Equatable {
    let videoId: String
    let rallyCount: Int
    let latestCreatedAtMillis: Int64
    let coverClipId: String
    let isOwned: Bool
    let sharerEmail: String?
}

enum MatchGrouping {
    /// Port of ClipListViewModel.toMatches: group by videoId, cover = min rallyIndex,
    /// sort by latest createdAt desc, partition into owned/shared.
    static func matches(
        from clips: [ClipInfo],
        currentUserId: String?,
        sharerByVideoId: [String: String]
    ) -> (owned: [MatchSummary], shared: [MatchSummary]) {
        let all = Dictionary(grouping: clips, by: \.videoId)
            .map { videoId, list -> MatchSummary in
                let cover = list.min { $0.rallyIndex < $1.rallyIndex } ?? list[0]
                let owned = currentUserId != nil && cover.ownerId == currentUserId
                return MatchSummary(
                    videoId: videoId,
                    rallyCount: list.count,
                    latestCreatedAtMillis: list.map(\.createdAtMillis).max() ?? 0,
                    coverClipId: cover.id,
                    isOwned: owned,
                    sharerEmail: owned ? nil : sharerByVideoId[videoId]
                )
            }
            .sorted { $0.latestCreatedAtMillis > $1.latestCreatedAtMillis }
        return (all.filter(\.isOwned), all.filter { !$0.isOwned })
    }
}

private let matchDateFormatter: DateFormatter = {
    let f = DateFormatter()
    f.locale = Locale(identifier: "en_US_POSIX")
    f.dateFormat = "MMM d, yyyy"   // "Jan 5, 2026" — matches Android formatDate
    return f
}()

func formatMatchDate(millis: Int64) -> String {
    matchDateFormatter.string(from: Date(timeIntervalSince1970: Double(millis) / 1000))
}
