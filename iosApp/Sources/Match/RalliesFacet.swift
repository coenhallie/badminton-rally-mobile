import SwiftUI
import Shared

/// The rally half of a match: the label summary strip, the match description and
/// one row per clip. Lifted from `MatchClipsView` unchanged - same summary strip,
/// same clip rows, same "No rallies" message. `MatchView` owns everything else
/// that view had (the sort picker, the summary sheet, the refresh loop), the same
/// way Android's `MatchScreen` owns them around its own `ralliesFacet`.
struct RalliesFacet: View {
    let rally: RallyApp
    let clips: [RallyClip]
    let summary: MatchLabelSummary?
    let matchTitle: String?
    let description: String?
    let onSummaryClick: () -> Void

    var body: some View {
        if let summary, !summary.isEmpty, !clips.isEmpty {
            Button { onSummaryClick() } label: {
                MatchLabelStripView(summary: summary)
            }
            .buttonStyle(.plain)
        }
        if let description {
            Text(description)
                .shuttlType(ShuttlType.titleMedium)
                .foregroundStyle(Shuttl.textSecondary)
        }
        if clips.isEmpty {
            Text("No rallies in this match.")
                .foregroundStyle(Shuttl.textSecondary)
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
