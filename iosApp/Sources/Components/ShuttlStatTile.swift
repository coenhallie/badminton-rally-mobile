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
    var onTap: (() -> Void)? = nil

    var body: some View {
        let tile = VStack(alignment: .leading, spacing: 0) {
            if labelAbove { caption }
            HStack(alignment: .firstTextBaseline, spacing: 0) {
                Text(value)
                    .shuttlType(ShuttlType.statNumber)
                    .foregroundStyle(Shuttl.textHeading)
                Text(unit)
                    .shuttlType(ShuttlType.bodyMedium)
                    .foregroundStyle(Shuttl.textTertiary)
            }
            .lineLimit(1)
            .padding(.top, labelAbove ? 4 : 0)
            if !labelAbove { caption.padding(.top, 4) }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 20)
        .padding(.vertical, 16)
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
