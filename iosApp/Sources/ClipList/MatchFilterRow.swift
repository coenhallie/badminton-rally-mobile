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
                    // One named group, not three unlabelled buttons: without
                    // this VoiceOver reads "All, button. On this phone,
                    // button. Not analyzed, button." with nothing to say the
                    // three of them are one control choosing one filter.
                    .accessibilityElement(children: .contain)
                    .accessibilityLabel("Filter")
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

/// Which glyph explains one row of `MatchListCopy.LEGEND`. Not an `Image`
/// directly: a case is what a test can compare, and `image` is the one place
/// that turns it into the glyph the row and its legend both draw.
enum LegendGlyphKind: Equatable {
    case wherePhone
    case whereCloud
    case analysis(MatchAnalysis)

    var isWhere: Bool {
        switch self {
        case .wherePhone, .whereCloud: return true
        case .analysis: return false
        }
    }

    var image: Image {
        switch self {
        case .wherePhone: return MatchGlyph.phone
        case .whereCloud: return MatchGlyph.cloud
        case .analysis(let analysis): return MatchGlyph.analysis(analysis)
        }
    }
}

/// Names the status line's glyphs. The match page says the same things in
/// words, so the glyphs are a scan aid, not the only place this is said.
struct MatchLegendSheet: View {
    @State private var contentHeight: CGFloat = 420

    /// Which glyph a legend row's own label names, keyed on the label rather
    /// than on the row's position in `MatchListCopy.LEGEND`: pairing by index
    /// (a `zip` against a hand-written glyph list) silently mispairs the
    /// moment a row is added, removed or reordered in the shared copy without
    /// a matching change here, and nothing would fail to compile or to run.
    static func glyphKind(forLegendLabel label: String) -> LegendGlyphKind? {
        switch label {
        case MatchListCopy.shared.WHERE_PHONE: return .wherePhone
        case MatchListCopy.shared.WHERE_CLOUD: return .whereCloud
        case MatchListCopy.shared.ANALYSIS_RALLIES: return .analysis(.rallies)
        case MatchListCopy.shared.ANALYSIS_HEATMAP: return .analysis(.heatmap)
        case MatchListCopy.shared.ANALYSIS_SKELETON: return .analysis(.skeleton)
        case MatchListCopy.shared.ANALYSIS_SCORE: return .analysis(.score)
        default: return nil
        }
    }

    var body: some View {
        let legend = MatchListCopy.shared.LEGEND
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text(MatchListCopy.shared.LEGEND_TITLE)
                    .shuttlType(ShuttlType.titleLarge)
                    .foregroundStyle(Shuttl.text)
                ForEach(Array(legend.enumerated()), id: \.offset) { _, pair in
                    let label = pair.first as? String ?? ""
                    let detail = pair.second as? String ?? ""
                    let kind = Self.glyphKind(forLegendLabel: label)
                    // The row always draws, glyph or not: the coach came here
                    // to read what an icon means, and a row whose icon this
                    // file has not been taught yet must not vanish along with
                    // it - that hides the very explanation he opened this
                    // sheet for. `kind == nil` is a bug to fix in this file
                    // (`MatchLegendTests` pins that every LEGEND label maps to
                    // one); it is not a reason to say nothing.
                    HStack(alignment: .top, spacing: DrawerList.gap) {
                        // One 18pt slot for both kinds, so the labels line up
                        // down the sheet whether the glyph is bare, chipped or
                        // (a bug elsewhere notwithstanding) empty. Hidden from
                        // VoiceOver: the label and its line already say what
                        // the glyph means, and reading "iPhone" or "flame"
                        // over that would add noise rather than information.
                        Group {
                            if let kind {
                                if kind.isWhere { WhereGlyph(image: kind.image) } else { AnalysisChip(image: kind.image) }
                            } else {
                                Color.clear
                            }
                        }
                        .frame(width: MatchGlyph.chipSize, height: MatchGlyph.chipSize)
                        .padding(.top, 2)
                        .accessibilityHidden(true)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(label)
                                .shuttlType(ShuttlType.bodyLarge)
                                .foregroundStyle(Shuttl.text)
                            Text(detail)
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
        // One step above the raised cards it explains: this sheet is a
        // surface the app draws itself, unlike the row `Menu` above, which is
        // a system material SwiftUI gives no background to override.
        .presentationBackground(Shuttl.bgRaised)
    }
}
