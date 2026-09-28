import Shared
import SwiftUI

/// Where a match's video is, then what it has. Read as one sentence by
/// VoiceOver (`MatchListItem.accessibilityStatus`); the glyphs are for scanning.
///
/// Sized to fit the row's text column in the former 330pt drawer, measured
/// there at 130pt on the iPhone 17 Pro (see `MatchListRowView`); the
/// full-width list's column is wider. Two bare 14pt where-glyphs 2pt
/// apart, a divider with 6pt either side, and up to four 18pt analysis chips
/// 4pt apart is 127pt. Mirrors androidApp's MatchStatusLine.kt number for
/// number.
struct MatchStatusLine: View {
    let item: MatchListItem

    var body: some View {
        let whereGlyphs = MatchGlyph.whereGlyphs(item.`where`)
        let running = item.action == .running
        // A failure has its own line under the row: two lines of reason do not
        // fit here.
        let inlineText = item.statusIsError ? nil : item.statusText
        let afterChip = !item.analyses.isEmpty
        HStack(spacing: 0) {
            ForEach(Array(whereGlyphs.enumerated()), id: \.offset) { i, glyph in
                WhereGlyph(image: glyph)
                    .padding(.leading, i > 0 ? 2 : 0)
            }
            if !whereGlyphs.isEmpty && (afterChip || running || inlineText != nil) {
                Rectangle()
                    .fill(Shuttl.borderSecondary)
                    .frame(width: 1, height: 12)
                    .padding(.horizontal, 6)
            }
            ForEach(Array(item.analyses.enumerated()), id: \.offset) { i, analysis in
                AnalysisChip(image: MatchGlyph.analysis(analysis))
                    .padding(.leading, i > 0 ? 4 : 0)
            }
            // The divider brings its own 6pt; after a chip the gap is spelled
            // out. With nothing before them (a scored match with no video) the
            // spinner and the words start flush with the text above.
            if running {
                ProgressView()
                    .controlSize(.mini)
                    .frame(width: 12, height: 12)
                    .padding(.leading, afterChip ? 6 : 0)
            }
            if let inlineText {
                Text(inlineText)
                    .shuttlType(ShuttlType.bodySmall)
                    .foregroundStyle(Shuttl.textSecondary)
                    .lineLimit(1)
                    .truncationMode(.tail)
                    .padding(.leading, afterChip || running ? 6 : 0)
            }
        }
        // One height for every status line, chips or not: a line of bare glyphs
        // and words would otherwise sit a few points shorter than its
        // neighbour's, and rows of a list that differ by 2pt read as a bug.
        .frame(minHeight: MatchGlyph.chipSize)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(item.accessibilityStatus)
    }
}

extension MatchListItem {
    /// Whether `MatchStatusLine` would draw anything. A scored match nobody has
    /// started, with no video, has nothing to say, and an empty line would
    /// still cost its row the height and the gap above it.
    var hasStatusLine: Bool {
        self.`where` != nil || !analyses.isEmpty || action == .running
            || (statusText != nil && !statusIsError)
    }
}

/// The status line's symbols, in one place so the rows and the legend cannot
/// draw a glyph two ways. Each name is written as a `systemName:` literal so
/// `SystemImageTests` checks it resolves.
enum MatchGlyph {
    static let whereSize: CGFloat = 14
    static let chipSize: CGFloat = 18
    static let chipGlyphSize: CGFloat = 11

    static let phone = Image(systemName: "iphone")
    static let cloud = Image(systemName: "cloud")

    static func whereGlyphs(_ place: MatchWhere?) -> [Image] {
        switch place {
        case .phone: return [phone]
        case .cloud: return [cloud]
        case .both: return [phone, cloud]
        case nil: return []
        }
    }

    static func analysis(_ analysis: MatchAnalysis) -> Image {
        switch analysis {
        case .rallies: return Image(systemName: "film")
        case .heatmap: return Image(systemName: "flame")
        case .skeleton: return Image(systemName: "figure.stand")
        case .score: return Image(systemName: "number")
        }
    }
}

/// A where-glyph, bare: where the video is reads as context, not as an
/// achievement, so it takes the secondary text colour rather than a chip.
struct WhereGlyph: View {
    let image: Image

    var body: some View {
        image
            .resizable()
            .scaledToFit()
            .foregroundStyle(Shuttl.textSecondary)
            .frame(width: MatchGlyph.whereSize, height: MatchGlyph.whereSize)
    }
}

/// An analysis the match has, in its tinted chip. The glyph is its own token,
/// not the accent: see `ShuttlPalette.analysisGlyph`.
struct AnalysisChip: View {
    let image: Image

    var body: some View {
        image
            .resizable()
            .scaledToFit()
            .fontWeight(.semibold)
            .foregroundStyle(Shuttl.analysisGlyph)
            .frame(width: MatchGlyph.chipGlyphSize, height: MatchGlyph.chipGlyphSize)
            .frame(width: MatchGlyph.chipSize, height: MatchGlyph.chipSize)
            .background(Shuttl.analysisChip, in: RoundedRectangle(cornerRadius: 6))
    }
}
