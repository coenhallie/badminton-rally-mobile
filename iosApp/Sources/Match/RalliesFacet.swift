import SwiftUI
import Shared

/// The rally half of a match: the label summary strip, who played, the match
/// description and one row per clip. Lifted from `MatchClipsView` - same summary
/// strip, same clip rows, same "No rallies" message. `MatchView` owns everything
/// else that view had (the sort picker, the summary sheet, the refresh loop), the
/// same way Android's `MatchScreen` owns them around its own `ralliesFacet`.
struct RalliesFacet: View {
    let rally: RallyApp
    let clips: [RallyClip]
    let summary: MatchLabelSummary?
    let matchTitle: String?
    /// "Coen vs Marco · Doubles" from `matchDetailsLine`, or nil. The points facet
    /// has its own legend for this; here it is what a match that was only ever
    /// filmed has to say about itself once its details are filled in.
    let details: String?
    let description: String?
    let onSummaryClick: () -> Void

    var body: some View {
        if let summary, !summary.isEmpty, !clips.isEmpty {
            Button { onSummaryClick() } label: {
                MatchLabelStripView(summary: summary)
            }
            .buttonStyle(.plain)
        }
        // One row for both lines, so a match with a players line and a
        // description gets one separator under them rather than one each.
        if details != nil || description != nil {
            VStack(alignment: .leading, spacing: 4) {
                if let details {
                    Text(details)
                        .shuttlType(ShuttlType.titleMedium)
                        .foregroundStyle(Shuttl.text)
                }
                if let description {
                    Text(description)
                        .shuttlType(ShuttlType.titleMedium)
                        .foregroundStyle(Shuttl.textSecondary)
                }
            }
        }
        if clips.isEmpty {
            ShuttlEmptyState(
                systemImage: "video",
                title: "No rallies yet",
                message: "Rallies show up here once this match's video has been clipped."
            ) { EmptyView() }
            .listRowInsets(EdgeInsets())
            .listRowSeparator(.hidden)
            .listRowBackground(Color.clear)
        }
        ForEach(clips, id: \.id) { clip in
            NavigationLink {
                ClipDetailView(rally: rally, clipId: clip.id)
            } label: {
                VStack(alignment: .leading, spacing: 4) {
                    Text(clipRowTitle(ClipInfo(clip), matchTitle: matchTitle))
                        .shuttlType(ShuttlType.titleMedium)
                        .foregroundStyle(Shuttl.text)
                    Text("\(clip.durationSeconds)S · \(clip.annotationCount) NOTES")
                        .shuttlType(ShuttlType.labelSmall)
                        .foregroundStyle(Shuttl.textSecondary)
                }
            }
        }
    }
}
