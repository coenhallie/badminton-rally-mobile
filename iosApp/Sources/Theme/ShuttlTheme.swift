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

/// Ports of androidApp ui/theme/ShuttlColors.kt (web tokens). Sharp corners everywhere
/// (ShuttlShapes.kt is all 0dp) except pill badges/chips.
enum Shuttl {
    static let bg              = Color(light: 0xFFFFFF, dark: 0x0D0D0D)
    static let bgSecondary     = Color(light: 0xF8F9FA, dark: 0x141414)
    static let bgTertiary      = Color(light: 0xF0F1F3, dark: 0x1A1A1A)
    static let bgInput         = Color(light: 0xF0F1F3, dark: 0x111111)
    static let border          = Color(light: 0xE0E0E0, dark: 0x222222)
    static let borderSecondary = Color(light: 0xD0D0D0, dark: 0x333333)
    static let textHeading     = Color(light: 0x0D0D0D, dark: 0xFFFFFF)
    static let text            = Color(light: 0x1A1A2E, dark: 0xE2E8F0)
    static let textSecondary   = Color(light: 0x555555, dark: 0x888888)
    static let textTertiary    = Color(light: 0x777777, dark: 0x666666)
    static let accent          = Color(light: 0x16A34A, dark: 0x22C55E)
    static let accentDark      = Color(light: 0x166534, dark: 0x16A34A)
    static let error           = Color(rgb: 0xEF4444)
    static let warning         = Color(rgb: 0xF59E0B)
    static let info            = Color(rgb: 0x3B82F6)

    /// Tiny uppercase tracked label — matches Android labelSmall (11sp, medium, 0.05em).
    static func sectionLabel(_ text: String) -> some View {
        Text(text.uppercased())
            .font(.system(size: 11, weight: .medium))
            .kerning(0.55)
            .foregroundStyle(Shuttl.textSecondary)
    }
}
