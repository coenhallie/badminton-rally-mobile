import Shared
import SwiftUI

/// The chips above the list, and the legend's way in. With fewer matches than
/// `showsFilters` asks for, only the legend button is left, at the end of a
/// slim row: the glyphs still need naming on a short list. Mirrors androidApp's
/// MatchFilterRow.kt.
struct MatchFilterRow: View {
    let showChips: Bool
    let selected: MatchListFilter
    let onSelect: (MatchListFilter) -> Void
    let onLegend: () -> Void

    var body: some View {
        HStack(spacing: 0) {
            if showChips {
                // One line at the drawer's width; at a large text size it
                // scrolls rather than wrapping into a second row of chips.
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 6) {
                        pill(MatchListCopy.shared.FILTER_ALL, .all)
                        pill(MatchListCopy.shared.FILTER_ON_THIS_PHONE, .onThisPhone)
                        pill(MatchListCopy.shared.FILTER_NOT_ANALYZED, .notAnalyzed)
                    }
                }
                .scrollBounceBehavior(.basedOnSize, axes: .horizontal)
            } else {
                Spacer(minLength: 0)
            }
            Button(action: onLegend) {
                Image(systemName: "info.circle")
                    .foregroundStyle(Shuttl.textSecondary)
                    // Centred in its 44pt box, with the row's trailing inset
                    // backing the box's own 12pt out (see `trailingInset`), so
                    // the glyph ends where the row menus' glyphs below it end.
                    .frame(width: 44, height: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.borderless)
            .accessibilityLabel(MatchListCopy.shared.LEGEND_BUTTON)
        }
    }

    /// The row's trailing inset in the drawer: a card's edge plus its padding,
    /// less the 12pt the info glyph sits inside its centred 44pt box.
    static let trailingInset = DrawerList.sideMargin + DrawerList.rowPaddingH - 12

    /// One filter, as a pill: three radio choices on the panel itself, outlined
    /// until chosen. The drawn pill is about 30pt tall; the hit area around it
    /// is the row's full 44pt, taken by a clear frame outside the drawn shape.
    ///
    /// bodySmall with 10pt sides rather than the labelMedium and 12pt Android
    /// uses: measured in Archivo, the three pills are then 229pt with their
    /// gaps, and the drawer leaves them 234pt beside the info button. In
    /// labelMedium they came to 257pt and "Not analyzed" sat half under the
    /// scroll edge at rest.
    private func pill(_ text: String, _ filter: MatchListFilter) -> some View {
        let isSelected = selected == filter
        return Button { onSelect(filter) } label: {
            Text(text)
                .shuttlType(ShuttlType.bodySmall)
                .foregroundStyle(isSelected ? Shuttl.bg : Shuttl.textSecondary)
                .lineLimit(1)
                .padding(.horizontal, 10)
                .padding(.vertical, 7)
                .background {
                    if isSelected {
                        Capsule().fill(Shuttl.text)
                    } else {
                        Capsule().strokeBorder(Shuttl.borderSecondary, lineWidth: 1)
                    }
                }
                .frame(minHeight: 44)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(isSelected ? [.isSelected] : [])
    }
}

/// Names the status line's glyphs. The match page says the same things in
/// words, so the glyphs are a scan aid, not the only place this is said.
struct MatchLegendSheet: View {
    @State private var contentHeight: CGFloat = 420

    /// The glyph beside each `MatchListCopy.LEGEND` row, by position: the two
    /// where-glyphs first, then the analyses in the order the status line
    /// draws them.
    private var glyphs: [(image: Image, isWhere: Bool)] {
        [(MatchGlyph.phone, true), (MatchGlyph.cloud, true)]
            + MatchAnalysis.allCases.map { (MatchGlyph.analysis($0), false) }
    }

    var body: some View {
        let legend = MatchListCopy.shared.LEGEND
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text(MatchListCopy.shared.LEGEND_TITLE)
                    .shuttlType(ShuttlType.titleLarge)
                    .foregroundStyle(Shuttl.text)
                ForEach(Array(zip(legend, glyphs).enumerated()), id: \.offset) { _, row in
                    let (copy, glyph) = row
                    HStack(alignment: .top, spacing: DrawerList.gap) {
                        // One 18pt slot for both kinds, so the labels line up
                        // down the sheet whether the glyph is bare or chipped.
                        Group {
                            if glyph.isWhere { WhereGlyph(image: glyph.image) } else { AnalysisChip(image: glyph.image) }
                        }
                        .frame(width: MatchGlyph.chipSize, height: MatchGlyph.chipSize)
                        .padding(.top, 2)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(copy.first as String? ?? "")
                                .shuttlType(ShuttlType.bodyLarge)
                                .foregroundStyle(Shuttl.text)
                            Text(copy.second as String? ?? "")
                                .shuttlType(ShuttlType.bodySmall)
                                .foregroundStyle(Shuttl.textSecondary)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                    }
                    .accessibilityElement(children: .combine)
                }
            }
            .padding(.horizontal, 24)
            .padding(.top, 24)
            .padding(.bottom, 16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background {
                GeometryReader { proxy in
                    Color.clear.onChange(of: proxy.size.height, initial: true) { _, height in
                        contentHeight = height
                    }
                }
            }
        }
        .scrollBounceBehavior(.basedOnSize)
        // As tall as its content, the way PlaybackSettingsSheet sizes itself:
        // a half-sheet would clip the last rows, a full one would be mostly empty.
        .presentationDetents([.height(contentHeight)])
        .presentationDragIndicator(.visible)
        .presentationBackground(Shuttl.bgSecondary)
    }
}
