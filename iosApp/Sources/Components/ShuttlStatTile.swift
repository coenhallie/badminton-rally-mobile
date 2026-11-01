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
/// `compact` is a tile three to a row (a player's profile), laid out by
/// `ShuttlStatTileRow`. On a 375pt phone with the page gutters such a tile is
/// about 104pt wide, which leaves about 80pt for the value in compact's 12pt of
/// padding. At the stat size (26pt) "None yet" is wider than that, so the value
/// is set at titleLarge (16pt), where the profile's values ("Oct 28",
/// "Oct '25", "None yet") keep to one line. At a large text size a value wraps
/// between words rather than being cut, and the caption sits at the tile's
/// foot, so a row of compact tiles given one height keeps its captions level.
/// `compactInset` is the row's to tighten, see `ShuttlStatTileRow`.
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
    /// A compact tile's horizontal padding.
    var compactInset: CGFloat = ShuttlStatTileRow.inset
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
        .padding(.horizontal, compact ? compactInset : 20)
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

/// Compact stat tiles in one row, at one width and one height, so the columns
/// line up and the captions stay level.
///
/// Every value keeps to one line at the default text size, down to a 320pt
/// phone. There a column is about 85pt, and compact's 12pt inset leaves 61pt,
/// while "None yet" needs about 64. So the row keeps the 12pt inset while the
/// widest tile fits its column, and otherwise sets every tile to 8pt together:
/// one inset for the whole row, never one tile tighter than its neighbours.
/// The choice is made from the tiles' own widths, not from the screen's. At a
/// large text size neither fits; the row takes the tighter inset and a value
/// wraps between words.
struct ShuttlStatTileRow: View {
    struct Tile {
        let value: String
        let label: String
    }

    let tiles: [Tile]

    /// The compact tile's inset where the row has room for it.
    static let inset: CGFloat = 12
    /// Where it does not.
    static let tightInset: CGFloat = 8
    static let spacing: CGFloat = 8

    var body: some View {
        ViewThatFits(in: .horizontal) {
            row(inset: Self.inset)
            row(inset: Self.tightInset)
        }
    }

    private func row(inset: CGFloat) -> some View {
        EqualColumns(spacing: Self.spacing) {
            ForEach(tiles.indices, id: \.self) { index in
                ShuttlStatTile(
                    value: tiles[index].value, unit: "", label: tiles[index].label,
                    compact: true, compactInset: inset
                )
                .accessibilityElement(children: .combine)
            }
        }
    }
}

/// Its subviews side by side in equal columns, all as tall as the tallest.
/// Its ideal width is what the widest subview needs in every column, which is
/// what lets `ViewThatFits` tell whether a row of equal tiles fits: an `HStack`
/// would report the sum of the tiles' own widths, and a row whose short values
/// leave room would pass while its widest tile still wrapped.
private struct EqualColumns: Layout {
    let spacing: CGFloat

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        guard !subviews.isEmpty else { return .zero }
        let gaps = spacing * CGFloat(subviews.count - 1)
        let width = proposal.width
            ?? subviews.map { $0.sizeThatFits(.unspecified).width }.max()! * CGFloat(subviews.count) + gaps
        let column = max(0, (width - gaps) / CGFloat(subviews.count))
        let height = subviews
            .map { $0.sizeThatFits(ProposedViewSize(width: column, height: nil)).height }
            .max()!
        return CGSize(width: width, height: height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        guard !subviews.isEmpty else { return }
        let gaps = spacing * CGFloat(subviews.count - 1)
        let column = max(0, (bounds.width - gaps) / CGFloat(subviews.count))
        for (index, subview) in subviews.enumerated() {
            subview.place(
                at: CGPoint(x: bounds.minX + CGFloat(index) * (column + spacing), y: bounds.minY),
                anchor: .topLeading,
                proposal: ProposedViewSize(width: column, height: bounds.height)
            )
        }
    }
}
