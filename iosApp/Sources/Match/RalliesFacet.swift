import SwiftUI
import Shared

/// The cloud half of the match page's Rallies section: the label summary strip,
/// who played, the match description and one row per clip. Lifted from
/// `MatchClipsView` - same summary strip, same "No rallies" message. `MatchView`
/// owns everything else that view had (the sort control, the summary sheet, the
/// refresh loop, the push to a rally), the same way Android's `MatchScreen` owns
/// them around its own `ralliesFacet`.
///
/// Every row sits in the page's gutter with a rally row's inset
/// (`.rallyListRow()`), and the clips are drawn by `RallyRowLabel`, the row the
/// device rallies draw: a match's rallies look the same wherever they were cut.
struct RalliesFacet: View {
    let clips: [RallyClip]
    let summary: MatchLabelSummary?
    let matchTitle: String?
    /// "Coen vs Marco · Doubles" from `matchDetailsLine`, or nil. The Points
    /// section has its own legend for this; here it is what a match that was
    /// only ever filmed has to say about itself once its details are filled in.
    let details: String?
    let description: String?
    let onSummaryClick: () -> Void
    /// A `Button` rather than a `NavigationLink`: a link in a plain list draws a
    /// disclosure chevron the device rows do not have, and the page already
    /// pushes a rally itself for the summary sheet's top rally.
    let onClipClick: (RallyClip) -> Void

    var body: some View {
        if let summary, !summary.isEmpty, !clips.isEmpty {
            Button { onSummaryClick() } label: {
                MatchLabelStripView(summary: summary)
            }
            .buttonStyle(.plain)
            .rallyListRow()
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
            .frame(maxWidth: .infinity, alignment: .leading)
            .rallyListRow()
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
            Button { onClipClick(clip) } label: {
                RallyRowLabel(
                    title: clipRowTitle(ClipInfo(clip), matchTitle: matchTitle),
                    meta: clipRowMeta(clip)
                )
            }
            .rallyListRow()
        }
    }
}

/// "25.2s · 3 notes" (the row sets it in capitals): the length through the
/// shared formatter the device rows use, left out rather than printed as "0.0s"
/// when the clip has none. The stored float printed as it came read "25.1727S".
/// Port of the meta line androidApp's `ClipRow` draws.
func clipRowMeta(_ clip: RallyClip) -> String {
    [RallyLengthKt.rallyLengthText(seconds: Double(clip.durationSeconds)), "\(clip.annotationCount) notes"]
        .compactMap { $0 }
        .joined(separator: " · ")
}
