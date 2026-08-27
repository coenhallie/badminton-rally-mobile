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
    /// Match name, from videos.title (typed on the phone or in the web app).
    let title: String?
    /// Match description, from videos.description. Phone-only; the web app has no field.
    let description: String?
}

/// The match name the web app stamps onto every clip of a video at cut time.
/// Takes the most common non-nil title rather than the cover clip's, so
/// retitling a single clip in this app doesn't relabel the whole match.
/// Port of Android's `List<RallyClip>.matchTitle()`.
func matchTitle(of clips: [ClipInfo]) -> String? {
    var counts: [String: Int] = [:]
    var order: [String] = []
    for case let title? in clips.map(\.title) {
        if counts[title] == nil { order.append(title) }
        counts[title, default: 0] += 1
    }
    return order.max { (counts[$0] ?? 0) < (counts[$1] ?? 0) }
}

enum MatchGrouping {
    /// Port of ClipListViewModel.toMatches: group by videoId, cover = min rallyIndex,
    /// sort by latest createdAt desc, partition into owned/shared.
    static func matches(
        from clips: [ClipInfo],
        currentUserId: String?,
        sharerByVideoId: [String: String],
        metadataByVideoId: [String: MatchMetadata]
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
                    sharerEmail: owned ? nil : sharerByVideoId[videoId],
                    // videos.title is authoritative; the clip-stamped copy is the
                    // fallback that keeps names on screen when the RPC is unreachable.
                    title: metadataByVideoId[videoId]?.title ?? matchTitle(of: list),
                    description: metadataByVideoId[videoId]?.description_
                )
            }
            .sorted { $0.latestCreatedAtMillis > $1.latestCreatedAtMillis }
        return (all.filter(\.isOwned), all.filter { !$0.isOwned })
    }
}

/// One row of the match list, which since live scoring holds two kinds of thing: a
/// match cut from a video, and a match scored courtside that may never have one.
/// Port of Android's `MatchRow`.
enum MatchRow: Identifiable {
    case video(MatchSummary)
    case score(ScoreMatchCard)

    /// Prefixed: both ids are UUIDs from the same generator and would otherwise collide.
    var id: String {
        switch self {
        case .video(let match): return "video-\(match.videoId)"
        case .score(let card):  return "score-\(card.scoreLogId)"
        }
    }

    var sortAtEpochMs: Int64 {
        switch self {
        case .video(let match): return match.latestCreatedAtMillis
        case .score(let card):  return card.createdAtEpochMs
        }
    }
}

/// Interleaves the two kinds into one newest-first list. Score logs are owner-only
/// by RLS, so this only ever builds the owned section.
///
/// The tie-break on `id` is not decoration, and it must match Android's exactly:
/// the same account on two phones has to produce the same order.
/// Port of Android's `mergeMatchRows`.
func mergeMatchRows(videoMatches: [MatchSummary], scoreMatches: [ScoreMatchCard]) -> [MatchRow] {
    let rows = videoMatches.map(MatchRow.video) + scoreMatches.map(MatchRow.score)
    return rows.sorted {
        $0.sortAtEpochMs != $1.sortAtEpochMs
            ? $0.sortAtEpochMs > $1.sortAtEpochMs
            : $0.id < $1.id
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

/// Label for a single rally row. Every clip of a match carries the match name,
/// so showing it again per row would make the rallies indistinguishable — fall
/// back to the rally number unless the user gave this clip its own title.
/// Port of Android's `clipRowTitle`.
func clipRowTitle(_ clip: ClipInfo, matchTitle: String?) -> String {
    if let title = clip.title, title != matchTitle { return title }
    return "Rally #\(clip.rallyIndex)"
}

/// Headline for a match row. A named match leads with its name; an unnamed one
/// keeps the original date headline. Port of Android's `matchRowPrimary`.
func matchRowPrimary(_ match: MatchSummary) -> String {
    match.title ?? "Match · \(formatMatchDate(millis: match.latestCreatedAtMillis))"
}

/// Sub-line for a match row. When the name takes the headline the date moves
/// down here, so it is never lost from the list. Port of Android's
/// `matchRowSecondary`.
func matchRowSecondary(_ match: MatchSummary) -> String {
    let rallies = "\(match.rallyCount) \(match.rallyCount == 1 ? "RALLY" : "RALLIES")"
    guard match.title != nil else { return rallies }
    return "\(rallies) · \(formatMatchDate(millis: match.latestCreatedAtMillis).uppercased())"
}

/// Name for the most-labelled rally in the summary sheet. Goes through the same
/// `clipRowTitle` the list row uses, so the sheet and the row can never name one
/// clip two ways. Falls back to the rally number when the clip has left the
/// list, which a prune racing an in-flight fetch can produce.
/// Port of Android's `topRallyName`.
func topRallyName(clipId: String, rallyIndex: Int32, clips: [ClipInfo], matchTitle: String?) -> String {
    guard let clip = clips.first(where: { $0.id == clipId }) else { return "Rally #\(rallyIndex)" }
    return clipRowTitle(clip, matchTitle: matchTitle)
}
