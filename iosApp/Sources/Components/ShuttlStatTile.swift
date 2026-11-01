import SwiftUI

/// One number with its unit and a caption, on a card: the mock's stat tile,
/// shared by the heatmap's summary and the skeleton view's measurements.
///
/// The unit is set smaller and quieter than the number, in the same line, so
/// "1.76 m" reads as one value with the number carrying the weight. The caption
/// sits above the number when the tile is one of a row that a coach scans by
/// name first (the measurements), and below it when the number is the thing
/// (the heatmap's summary); `labelAbove` picks.
///
/// A selectable tile draws a one-point accent border when chosen and a clear one
/// otherwise, so choosing a tile never moves its neighbours.
///
/// `compact` is a tile three to a row (a player's profile). On a 375pt phone
/// with the page gutters such a tile is about 104pt wide, which leaves about
/// 80pt for the value in compact's 12pt of padding. At the stat size (26pt)
/// "None yet" is wider than that, so the value is set at titleLarge (16pt),
/// where the profile's values ("Oct 28", "Oct '25", "None yet") keep to one
/// line. At a large text size a value wraps between words rather than being
/// cut, and the caption sits at the tile's foot, so a row of compact tiles
/// given one height (`.fixedSize(horizontal: false, vertical: true)` on the
/// row) keeps its captions level.
///
/// Port of androidApp's `ShuttlStatTile`.
struct ShuttlStatTile: View {
    let value: String
    let unit: String
    let label: String
    var labelAbove: Bool = false
    var selected: Bool = false
    /// One line, as Android sets it. The heatmap's own two captions are longer
    /// than half a phone and were laid out on two before this tile was shared,
    /// so that screen passes 2 rather than starting to ellipsize.
    var labelLineLimit: Int = 1
    var compact: Bool = false
    var onTap: (() -> Void)? = nil

    var body: some View {
        let tile = VStack(alignment: .leading, spacing: 0) {
            if labelAbove { caption }
            HStack(alignment: .firstTextBaseline, spacing: 0) {
                Text(value)
                    .shuttlType(compact ? ShuttlType.titleLarge : ShuttlType.statNumber)
                    .foregroundStyle(Shuttl.textHeading)
                Text(unit)
                    .shuttlType(ShuttlType.bodyMedium)
                    .foregroundStyle(Shuttl.textTertiary)
            }
            .lineLimit(compact ? nil : 1)
            .fixedSize(horizontal: false, vertical: compact)
            .padding(.top, labelAbove ? 4 : 0)
            // The caption at the foot of a tile its row has made taller.
            if compact && !labelAbove { Spacer(minLength: 0) }
            if !labelAbove { caption.padding(.top, 4) }
        }
        .frame(maxWidth: .infinity, maxHeight: compact ? .infinity : nil, alignment: .topLeading)
        .padding(.horizontal, compact ? 12 : 20)
        .padding(.vertical, compact ? 12 : 16)
        .background(Shuttl.bgSecondary, in: RoundedRectangle(cornerRadius: ShuttlRadius.large))
        .overlay(
            RoundedRectangle(cornerRadius: ShuttlRadius.large)
                .stroke(selected ? Shuttl.accent : .clear, lineWidth: 1)
        )

        if let onTap {
            Button(action: onTap) { tile }
                .buttonStyle(.plain)
                .accessibilityLabel("\(label), \(value)\(unit)")
                .accessibilityAddTraits(selected ? [.isButton, .isSelected] : .isButton)
        } else {
            tile
        }
    }

    private var caption: some View {
        Text(label)
            .shuttlType(ShuttlType.bodySmall)
            .foregroundStyle(Shuttl.textTertiary)
            .lineLimit(labelLineLimit)
            .frame(maxWidth: .infinity, alignment: .leading)
    }
}
