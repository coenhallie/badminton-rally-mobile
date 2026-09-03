import SwiftUI

extension UIColor {
    convenience init(rgb: UInt32) {
        self.init(
            red: CGFloat((rgb >> 16) & 0xFF) / 255,
            green: CGFloat((rgb >> 8) & 0xFF) / 255,
            blue: CGFloat(rgb & 0xFF) / 255,
            alpha: 1
        )
    }
}

extension Color {
    init(rgb: UInt32) { self.init(UIColor(rgb: rgb)) }
    init(light: UInt32, dark: UInt32) {
        self.init(UIColor { trait in
            trait.userInterfaceStyle == .dark ? UIColor(rgb: dark) : UIColor(rgb: light)
        })
    }
}

/// The design tokens, resolved for the current interface style.
///
/// Values live in `ShuttlPalette`; this is the SwiftUI-facing view of them.
/// Ported from androidApp's ui/theme/ShuttlColors.kt, which mirrors this file
/// token for token. Radii live in `ShuttlRadius`, type in `ShuttlType`.
enum Shuttl {
    private static func token(_ pair: ShuttlPalette.Pair) -> Color {
        Color(light: pair.light, dark: pair.dark)
    }

    static let bg              = token(ShuttlPalette.bg)
    static let bgSecondary     = token(ShuttlPalette.bgSecondary)
    static let bgTertiary      = token(ShuttlPalette.bgTertiary)
    static let bgInput         = token(ShuttlPalette.bgInput)
    static let border          = token(ShuttlPalette.border)
    static let borderSecondary = token(ShuttlPalette.borderSecondary)
    static let textHeading     = token(ShuttlPalette.textHeading)
    static let text            = token(ShuttlPalette.text)
    static let textSecondary   = token(ShuttlPalette.textSecondary)
    static let textTertiary    = token(ShuttlPalette.textTertiary)
    static let textMuted       = token(ShuttlPalette.textMuted)
    static let accent          = token(ShuttlPalette.accent)
    static let onAccent        = token(ShuttlPalette.onAccent)
    static let accentDark      = token(ShuttlPalette.accentDark)
    static let error           = token(ShuttlPalette.error)
    static let warning         = token(ShuttlPalette.warning)
    static let info            = token(ShuttlPalette.info)

    // The two sides of the scoreboard. Deliberately not the accent green and the
    // info blue: those are interface colours sized for a chip, and these are
    // full-bleed halves carrying white numerals, so they are picked for contrast
    // against white first and family resemblance second. Deeper in dark, where a
    // lit-up half at arm's length in a dim hall is the thing to avoid. Mirrors
    // androidApp's ShuttlExtendedColors.sideHome / sideAway.
    static let sideHome        = token(ShuttlPalette.sideHome)
    static let sideAway        = token(ShuttlPalette.sideAway)

    /// Tiny uppercase tracked label - matches Android labelSmall.
    static func sectionLabel(_ text: String) -> some View {
        Text(text.uppercased())
            .shuttlType(ShuttlType.labelSmall)
            .foregroundStyle(Shuttl.textSecondary)
    }
}
