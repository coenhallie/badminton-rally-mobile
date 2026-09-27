import SwiftUI

/// The least a control takes taps across: the platform's 44pt touch target.
///
/// Held here so a control drawn smaller than that (a compact pill, a pill tab,
/// a one-word text button) reaches it with `MinimumTargetShape` rather than
/// with an inset worked out by hand from its current height, which goes stale
/// the moment the type or the padding changes.
enum ShuttlTarget {
    static let minimum: CGFloat = 44
}

/// A hit shape that is the view's own rect grown to `ShuttlTarget.minimum`
/// where the view is smaller, centred on it, without changing what is drawn or
/// how much room the view takes. `axes` limits the growth: a pill beside
/// another 4pt away grows only vertically, or it would take its neighbour's
/// taps.
///
/// A hit shape reaches past its view's frame, but not past a scroll view's
/// bounds: a control at a scroll view's top edge still loses what sits above
/// it.
struct MinimumTargetShape: Shape {
    var axes: Axis.Set = [.horizontal, .vertical]

    func path(in rect: CGRect) -> Path {
        let dx = axes.contains(.horizontal) ? max(0, (ShuttlTarget.minimum - rect.width) / 2) : 0
        let dy = axes.contains(.vertical) ? max(0, (ShuttlTarget.minimum - rect.height) / 2) : 0
        return Path(rect.insetBy(dx: -dx, dy: -dy))
    }
}
