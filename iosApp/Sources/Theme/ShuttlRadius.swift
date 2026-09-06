import CoreGraphics

/// The shape scale, as raw radii.
///
/// Mirrors androidApp's ShuttlRadius.kt number for number. Use with
/// `.clipShape(RoundedRectangle(cornerRadius: ShuttlRadius.large))`, or
/// `Capsule()` where Android would use `pill`.
enum ShuttlRadius {
    static let extraSmall: CGFloat = 8
    static let small: CGFloat = 12
    static let medium: CGFloat = 16
    static let large: CGFloat = 20
    static let extraLarge: CGFloat = 28

    /// Buttons, chips, badges, tab pills. `Capsule()` is equivalent and
    /// preferred in SwiftUI; this exists so the two platforms' scales can be
    /// compared token for token.
    static let pill: CGFloat = 999
}
