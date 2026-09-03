import SwiftUI
import UIKit

/// The type scale, in Archivo.
///
/// Roles carry their raw numbers so the scale can be asserted from a unit test,
/// which a built `Font` cannot be. Mirrors androidApp's ShuttlScale number for
/// number; the two are checked against each other by ShuttlTypeTests and
/// ShuttlTypeTest.
///
/// Tracking is stored in em, the unit the design is expressed in, and converted
/// to points on the way out. Storing points would mean re-deriving the
/// conversion by hand every time a size changes.
enum ShuttlType {
    enum Weight: String, Hashable {
        case regular  = "Archivo-Regular"
        case medium   = "Archivo-Medium"
        case semibold = "Archivo-SemiBold"
        case bold     = "Archivo-Bold"
    }

    struct Role {
        let name: String
        let size: CGFloat
        let weight: Weight
        let trackingEm: CGFloat
        let lineHeightMultiple: CGFloat

        /// Tracking in points, which is what `.kerning` takes.
        var kerning: CGFloat { size * trackingEm }

        /// Extra leading in points, which is what `.lineSpacing` takes.
        ///
        /// Clamped at zero: `.lineSpacing` adds to the font's natural line
        /// height and cannot subtract from it, so a target tighter than Archivo's
        /// own leading is unreachable this way. See `View.shuttlType(_:)`.
        var lineSpacing: CGFloat {
            let natural = UIFont(name: weight.rawValue, size: size)?.lineHeight
                ?? UIFont.systemFont(ofSize: size).lineHeight
            return max(0, size * lineHeightMultiple - natural)
        }

        /// The SwiftUI font. Falls back to the system font at the same size if
        /// Archivo is somehow missing, so a bundling mistake degrades rather
        /// than crashes. ArchivoFontTests is what catches it properly.
        var font: Font {
            .custom(weight.rawValue, size: size)
        }
    }

    static let display          = Role(name: "display", size: 40, weight: .medium, trackingEm: -0.035, lineHeightMultiple: 1.08)
    static let headlineLarge    = Role(name: "headlineLarge", size: 28, weight: .medium, trackingEm: -0.030, lineHeightMultiple: 1.15)
    static let headlineMedium   = Role(name: "headlineMedium", size: 22, weight: .medium, trackingEm: -0.020, lineHeightMultiple: 1.20)
    static let statNumber       = Role(name: "statNumber", size: 26, weight: .medium, trackingEm: -0.030, lineHeightMultiple: 1.15)
    static let titleLarge       = Role(name: "titleLarge", size: 16, weight: .semibold, trackingEm: -0.010, lineHeightMultiple: 1.30)
    static let titleMedium      = Role(name: "titleMedium", size: 15, weight: .semibold, trackingEm: -0.010, lineHeightMultiple: 1.30)
    static let bodyLarge        = Role(name: "bodyLarge", size: 16, weight: .regular, trackingEm: 0, lineHeightMultiple: 1.45)
    static let bodyMedium       = Role(name: "bodyMedium", size: 14, weight: .regular, trackingEm: 0, lineHeightMultiple: 1.45)
    static let bodySmall        = Role(name: "bodySmall", size: 12, weight: .regular, trackingEm: 0, lineHeightMultiple: 1.40)
    static let labelSmall       = Role(name: "labelSmall", size: 11, weight: .medium, trackingEm: 0.050, lineHeightMultiple: 1.30)

    static let allRoles: [Role] = [
        display, headlineLarge, headlineMedium, statNumber,
        titleLarge, titleMedium, bodyLarge, bodyMedium, bodySmall, labelSmall,
    ]
}

extension View {
    /// Applies a role's font, tracking and leading together, which is the only
    /// correct way to use one: kerning and leading are both derived from the
    /// size, so setting the font alone silently ships the wrong metrics.
    ///
    /// Leading is approximate in one direction. SwiftUI's `.lineSpacing` is
    /// ADDITIVE - it adds to the font's natural line height and cannot subtract
    /// from it - so a role whose target sits below Archivo's natural leading
    /// gets that natural leading instead, and reads slightly looser than the
    /// design. Only the display role is affected. The hero is the one place
    /// where that difference is visible, and phase 2 draws it as two separate
    /// `Text` views in a `VStack` with explicit spacing, which sidesteps
    /// leading entirely and hits 1.08 exactly.
    func shuttlType(_ role: ShuttlType.Role) -> some View {
        self.font(role.font)
            .kerning(role.kerning)
            .lineSpacing(role.lineSpacing)
    }

    /// Same as `shuttlType(_:)`, with monospaced digits - for timers and counts
    /// that must not shift width as their digits change.
    func shuttlType(_ role: ShuttlType.Role, monospacedDigit: Bool) -> some View {
        self.font(monospacedDigit ? role.font.monospacedDigit() : role.font)
            .kerning(role.kerning)
            .lineSpacing(role.lineSpacing)
    }
}
