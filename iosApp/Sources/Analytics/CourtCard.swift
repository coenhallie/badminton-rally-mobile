import Shared
import SwiftUI

/// The court on a bordered card, centred and no wider than the mock draws it.
/// Port of Android's `CourtCard`.
///
/// The court is 1.7 times taller than it is wide, so a court the full width of
/// a phone is taller than the screen and takes whatever sits under it off the
/// bottom. Capped at the mock's width instead, and centred. The card covers the
/// court plus the margin the selector accepts, so a player lunging past the
/// baseline is drawn where they were rather than clamped onto the line.
///
/// One card for both panels that draw a court, so the heatmap and the base
/// positions cannot end up on differently proportioned courts and be read
/// against each other.
struct CourtCard<Content: View>: View {
    @ViewBuilder let content: Content

    var body: some View {
        ZStack { content }
            .aspectRatio(CourtLayout.aspectRatio, contentMode: .fit)
            // The cap goes outside the fill: a fill applied first would take the
            // whole gutter and leave the cap nothing to cap.
            .frame(maxWidth: CourtLayout.maxWidth)
            .background(Shuttl.bgSecondary)
            .clipShape(RoundedRectangle(cornerRadius: ShuttlRadius.medium))
            .overlay(
                RoundedRectangle(cornerRadius: ShuttlRadius.medium)
                    .stroke(Shuttl.border, lineWidth: 1)
            )
            .frame(maxWidth: .infinity)
            .padding(.horizontal, ShuttlGutter.page)
    }
}

/// The metre space a `CourtCard` draws in, and where a position lands in it.
///
/// Its own type rather than statics on `CourtCard`, which is generic in its
/// content and so cannot be named without one.
enum CourtLayout {
    /// The court plus the margin the selector accepts, so a lunge past the
    /// baseline is drawn where it was rather than clamped onto the line.
    static let marginM: Double = 2.0

    /// The mock's court panel is 232pt in a 345pt column. Wider here because
    /// the canvas carries the two-metre margin on every side, which the mock's
    /// panel does not, so at the mock's width the court itself would be a third
    /// narrower.
    static let maxWidth: CGFloat = 300

    /// From the real court, so the drawing cannot silently stretch and
    /// misrepresent how far the player actually ranged.
    static var aspectRatio: CGFloat {
        CGFloat((Court.shared.WIDTH_DOUBLES + 2 * marginM) / (Court.shared.LENGTH + 2 * marginM))
    }

    /// Where a court metre lands on a card of this size.
    ///
    /// The same mapping `drawCourt` uses for the lines, offered to the panels
    /// that put marks on top of them so a dot and the line it sits behind
    /// cannot be computed two different ways.
    static func point(_ p: Point, in size: CGSize) -> CGPoint {
        let totalW = Court.shared.WIDTH_DOUBLES + 2 * marginM
        let totalH = Court.shared.LENGTH + 2 * marginM
        return CGPoint(
            x: CGFloat((p.x + marginM) / totalW) * size.width,
            y: CGFloat((p.y + marginM) / totalH) * size.height
        )
    }
}
