import SwiftUI

/// A menu's button drawn as a form field: `ShuttlTextField`'s box (the same
/// fill, stroke, radius, inset and line height, the text in bodyLarge) holding
/// the value, with a chevron at its right. `leading` draws before the value
/// (the player picker's avatar) and is handed the field's line height, so it
/// can keep the box a text field's height. `dimmed` letters the value as a
/// placeholder ("Choose a player").
///
/// One line ending in an ellipsis, so the box sits level with a text field
/// beside it. At an accessibility text size the value wraps to up to three
/// lines instead and the box grows with it: one line held only a few letters
/// ("Someone e…", "Coe…").
///
/// The caller wraps it in its `Menu` and names it for VoiceOver there. Taps
/// land at least 44pt tall, reaching just past the box rather than making it
/// taller than the text field beside it.
struct ShuttlMenuField<Leading: View>: View {
    let text: String
    var dimmed: Bool = false
    @ViewBuilder var leading: (_ lineHeight: CGFloat) -> Leading

    @Environment(\.displayScale) private var displayScale
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    /// `ShuttlTextField`'s line, so the two boxes are one height.
    @ScaledMetric(relativeTo: .body) private var lineHeight: CGFloat =
        ShuttlType.bodyLarge.size * ShuttlType.bodyLarge.lineHeightMultiple

    var body: some View {
        let line = (lineHeight * displayScale).rounded(.up) / displayScale
        let wraps = dynamicTypeSize.isAccessibilitySize
        HStack(spacing: 8) {
            leading(line)
            Text(text)
                .shuttlType(ShuttlType.bodyLarge)
                .foregroundStyle(dimmed ? Shuttl.textSecondary : Shuttl.text)
                .lineLimit(wraps ? 3 : 1)
                .truncationMode(.tail)
                // A menu's label centres its lines otherwise.
                .multilineTextAlignment(.leading)
                .fixedSize(horizontal: false, vertical: wraps)
                .frame(maxWidth: .infinity, alignment: .leading)
            Image(systemName: "chevron.down")
                .imageScale(.small)
                .foregroundStyle(Shuttl.textSecondary)
                .accessibilityHidden(true)
        }
        .frame(height: wraps ? nil : line)
        .frame(minHeight: wraps ? line : nil)
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .background(RoundedRectangle(cornerRadius: ShuttlRadius.small).fill(Shuttl.bgInput))
        .overlay(
            RoundedRectangle(cornerRadius: ShuttlRadius.small)
                .strokeBorder(Shuttl.textTertiary, lineWidth: 1)
        )
        .contentShape(MinimumTargetShape(axes: .vertical))
    }
}

extension ShuttlMenuField where Leading == EmptyView {
    init(text: String, dimmed: Bool = false) {
        self.init(text: text, dimmed: dimmed) { _ in EmptyView() }
    }
}
