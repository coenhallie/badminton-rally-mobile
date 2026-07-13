import CoreGraphics

/// Inverse of the zoom/pan transform (transform origin top-left), matching
/// Android's tap handler in CourtMarkingScreen.
enum CourtTapMath {
    static func inverse(tapX: CGFloat, tapY: CGFloat, offsetX: CGFloat, offsetY: CGFloat, scale: CGFloat) -> CGPoint {
        CGPoint(x: (tapX - offsetX) / scale, y: (tapY - offsetY) / scale)
    }
}
