import Shared
import SwiftUI

/// Mini-court showing which landmark is next, mirror of Android's SchematicCourtGuide.
/// Court meters from homography.ts: 6.1 wide x 13.4 long, service line 1.98 from net.
struct SchematicCourtGuide: View {
    let placedCount: Int

    private static let courtW: CGFloat = 6.1
    private static let courtL: CGFloat = 13.4
    private static let serviceLine: CGFloat = 1.98
    /// The court's height, androidApp's SCHEMATIC_HEIGHT: the guide shares the
    /// scrolling area under the frame with the instruction and the buttons.
    private static let courtHeight: CGFloat = 128
    /// Room around the court for the next point's 6 pt marker, which sits on
    /// the court's edge at the corners and was clipped to a sliver there by
    /// the canvas bounds.
    private static let inset: CGFloat = 7

    /// Landmark positions in court meters (x across width, y along length),
    /// index-aligned with CourtMarkingSpec order.
    private static let positions: [CGPoint] = [
        CGPoint(x: 0, y: 0),                      // TL
        CGPoint(x: courtW, y: 0),                 // TR
        CGPoint(x: courtW, y: courtL),            // BR
        CGPoint(x: 0, y: courtL),                 // BL
        CGPoint(x: 0, y: courtL / 2),             // NL
        CGPoint(x: courtW, y: courtL / 2),        // NR
        CGPoint(x: 0, y: courtL / 2 - serviceLine),        // SNL
        CGPoint(x: courtW, y: courtL / 2 - serviceLine),   // SNR
        CGPoint(x: 0, y: courtL / 2 + serviceLine),        // SFL
        CGPoint(x: courtW, y: courtL / 2 + serviceLine),   // SFR
        CGPoint(x: courtW / 2, y: courtL / 2 - serviceLine), // CTN
        CGPoint(x: courtW / 2, y: courtL / 2 + serviceLine), // CTF
    ]

    var body: some View {
        HStack(alignment: .center, spacing: 16 - Self.inset) {
            canvas
                .frame(
                    width: Self.courtHeight * Self.courtW / Self.courtL + Self.inset * 2,
                    height: Self.courtHeight + Self.inset * 2
                )
            // The order sentence only while points remain: beside a complete
            // marking (a found court, restored marks) there is no order left.
            VStack(alignment: .leading, spacing: 4) {
                ForEach(CourtMarkingSpec.shared.guideLines(placedCount: Int32(placedCount)), id: \.self) { line in
                    Text(line)
                        .shuttlType(ShuttlType.bodySmall)
                        .foregroundStyle(Shuttl.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        // The inset is the canvas's own margin, so only the leading gutter
        // gives it back; the text keeps the page's 16 on the trailing side.
        .padding(.leading, 16 - Self.inset)
        .padding(.trailing, 16)
    }

    private var canvas: some View {
        Canvas { context, canvasSize in
            context.translateBy(x: Self.inset, y: Self.inset)
            let size = CGSize(width: canvasSize.width - Self.inset * 2, height: canvasSize.height - Self.inset * 2)
            let sx = size.width / Self.courtW
            let sy = size.height / Self.courtL
            let line = Shuttl.accent
            var outline = Path(); outline.addRect(CGRect(origin: .zero, size: size))
            context.stroke(outline, with: .color(line), lineWidth: 1)
            for y in [Self.courtL / 2, Self.courtL / 2 - Self.serviceLine, Self.courtL / 2 + Self.serviceLine] {
                var p = Path()
                p.move(to: CGPoint(x: 0, y: y * sy)); p.addLine(to: CGPoint(x: size.width, y: y * sy))
                context.stroke(p, with: .color(line.opacity(y == Self.courtL / 2 ? 1 : 0.5)), lineWidth: 1)
            }
            var center = Path()
            center.move(to: CGPoint(x: size.width / 2, y: (Self.courtL / 2 - Self.serviceLine) * sy))
            center.addLine(to: CGPoint(x: size.width / 2, y: (Self.courtL / 2 + Self.serviceLine) * sy))
            context.stroke(center, with: .color(line.opacity(0.5)), lineWidth: 1)

            for (i, pos) in Self.positions.enumerated() {
                let dp = CGPoint(x: pos.x * sx, y: pos.y * sy)
                let isNext = i == placedCount
                let isPlaced = i < placedCount
                let radius: CGFloat = isNext ? 6 : 3.5
                let color = specColor(i).opacity(isPlaced || isNext ? 1 : 0.35)
                let circle = Path(ellipseIn: CGRect(x: dp.x - radius, y: dp.y - radius, width: radius * 2, height: radius * 2))
                context.fill(circle, with: .color(color))
                // Every marker is ringed, and in a grey that reads on both
                // pages rather than black. Two of the twelve colours come from
                // desktop as white and light grey, and Center-Near - the white
                // one - was invisible on the light theme's white page; a black
                // ring would have been as invisible on the dark one. The next
                // point is still told apart by its size. Android's
                // SchematicCourtGuide rings its markers the same way.
                context.stroke(
                    circle,
                    with: .color(Shuttl.textSecondary.opacity(isPlaced || isNext ? 1 : 0.35)),
                    lineWidth: 1
                )
            }
        }
    }
}
