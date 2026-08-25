import SwiftUI
import Shared

/// Two chips plus an overflow count is what fits beside the chevron on the
/// narrowest supported screen. Mirrors Android's STRIP_CHIP_LIMIT.
private let stripChipLimit = 2

/// The match's top labels, above the rally list. The whole row is one target:
/// a chip that looks tappable but does not filter would promise something this
/// screen does not do.
struct MatchLabelStripView: View {
    let summary: MatchLabelSummary

    var body: some View {
        let shown = Array(summary.labels.prefix(stripChipLimit))
        let overflow = summary.labels.count - shown.count
        HStack(spacing: 8) {
            ForEach(Array(shown.enumerated()), id: \.offset) { _, label in
                LabelCountChip(label: label)
            }
            if overflow > 0 {
                Text("+\(overflow)")
                    .font(.system(size: 11, weight: .medium))
                    .foregroundStyle(Shuttl.textSecondary)
            }
            Spacer()
            Image(systemName: "chevron.right")
                .font(.footnote)
                .foregroundStyle(Shuttl.textSecondary)
        }
        .contentShape(Rectangle())
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Match summary, \(labelledNotes(summary.labelledNoteCount))")
    }
}

private struct LabelCountChip: View {
    let label: LabelCount

    var body: some View {
        HStack(spacing: 4) {
            LabelBadge(
                name: MatchLabelSummaryKt.stripLabelName(name: label.name),
                colorKey: label.colorKey
            )
            Text("\(label.count)")
                .font(.system(size: 11, weight: .medium).monospacedDigit())
                .foregroundStyle(Shuttl.textSecondary)
        }
    }
}

/// The full breakdown. `topRallyName` is resolved by the caller, which holds the
/// clips, so this stays a pure rendering of the summary.
struct MatchSummarySheet: View {
    let summary: MatchLabelSummary
    let topRallyName: String?
    let onTopRally: () -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                ForEach(Array(summary.labels.enumerated()), id: \.offset) { _, label in
                    LabelShareRow(label: label)
                }
                if let name = topRallyName, let top = summary.topRally {
                    Button {
                        onTopRally()
                        dismiss()
                    } label: {
                        HStack {
                            Text("Most labelled · \(name) · \(labelledNotes(top.labelCount))")
                                .font(.subheadline)
                                .foregroundStyle(Shuttl.text)
                            Spacer()
                            Image(systemName: "chevron.right")
                                .font(.footnote)
                                .foregroundStyle(Shuttl.textSecondary)
                        }
                    }
                }
            }
            .listStyle(.plain)
            .navigationTitle(labelledNotes(summary.labelledNoteCount))
            .navigationBarTitleDisplayMode(.inline)
        }
    }
}

private struct LabelShareRow: View {
    let label: LabelCount

    private var swatch: LabelColor? { LabelColor.companion.from(key: label.colorKey) }
    private var barColor: Color {
        swatch.map { Color(rgb: UInt32($0.background & 0xFFFFFF)) } ?? Shuttl.accent
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                LabelBadge(name: label.name, colorKey: label.colorKey)
                Spacer()
                Text("\(label.count)   \(label.sharePercent)%")
                    .font(.system(size: 12, weight: .medium).monospacedDigit())
                    .foregroundStyle(Shuttl.textSecondary)
            }
            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    Capsule().fill(Shuttl.bgTertiary)
                    // A label rounding to 0% still gets a visible sliver: the
                    // percentage stays honest, the bar stays present.
                    Capsule().fill(barColor)
                        .frame(width: max(geo.size.width * CGFloat(label.sharePercent) / 100, 2))
                }
            }
            .frame(height: 6)
        }
        .padding(.vertical, 4)
    }
}

/// "1 labelled note" / "12 labelled notes". Never "notes": the rally rows'
/// note count includes notes with no label. Mirrors Android's `labelledNotes`.
func labelledNotes(_ count: Int32) -> String {
    "\(count) labelled \(count == 1 ? "note" : "notes")"
}
