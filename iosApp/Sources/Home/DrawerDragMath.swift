import CoreGraphics

/// The matches drawer's arithmetic.
///
/// SwiftUI has no drawer primitive, so the drawer is a hand built overlay and
/// this is the part of it that can be wrong invisibly: a threshold that makes
/// the gesture feel dead, a clamp that lets the panel slide past its own edge,
/// an opacity ramp that leaves an invisible scrim swallowing taps. Pure, so all
/// of it is covered by DrawerDragMathTests without driving a gesture.
enum DrawerDragMath {
    /// How far in from the left edge a drag may start. Matches the mock's 26px.
    static let edgeZoneWidth: CGFloat = 26

    /// Travel that commits an open on its own.
    static let openThreshold: CGFloat = 70

    /// Speed that commits an open regardless of travel, in points per second.
    /// Without this a quick flick, which is how most people open a drawer, would
    /// be rejected for not having travelled far enough.
    static let velocityThreshold: CGFloat = 300

    /// Capped rather than purely proportional: on an iPad an 86% drawer would be
    /// a full page with a sliver of scrim, which reads as a broken screen rather
    /// than as a panel over the one behind it.
    static func width(forScreenWidth screenWidth: CGFloat) -> CGFloat {
        min(330, screenWidth * 0.86)
    }

    static func shouldOpen(translation: CGFloat, velocity: CGFloat) -> Bool {
        guard translation > 0 else { return false }
        return translation >= openThreshold || velocity >= velocityThreshold
    }

    /// Where the panel sits, measured from fully hidden (-width) to open (0).
    static func offset(translation: CGFloat, width: CGFloat, isOpen: Bool) -> CGFloat {
        let base: CGFloat = isOpen ? 0 : -width
        return min(0, max(-width, base + translation))
    }

    /// Clamped at zero, and that lower clamp is load bearing: an offset
    /// arriving from stale state must never leave a fully opaque scrim
    /// sitting over a closed drawer, because that would swallow every tap on
    /// the screen underneath with nothing visible on screen to explain it.
    /// A degenerate width (zero or negative, as a layout can briefly report)
    /// returns 0 rather than dividing, which is what the guard below is for.
    static func scrimOpacity(offset: CGFloat, width: CGFloat) -> Double {
        guard width > 0 else { return 0 }
        return Double(max(0, 1 - abs(offset) / width))
    }
}
