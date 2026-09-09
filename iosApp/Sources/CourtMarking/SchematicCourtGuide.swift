import Shared
import SwiftUI

/// Mini-court showing which landmark is next — mirror of Android's SchematicCourtGuide.
/// Court meters from homography.ts: 6.1 wide × 13.4 long, service line 1.98 from net.
struct SchematicCourtGuide: View {
    let placedCount: Int

    private static let courtW: CGFloat = 6.1
    private static let courtL: CGFloat = 13.4
    private static let serviceLine: CGFloat = 1.98

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
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .top, spacing: 12) {
                canvas
                    .frame(width: 160 * Self.courtW / Self.courtL, height: 160)
                VStack(alignment: .leading, spacing: 4) {
                    Text("Tap each court landmark in the order shown.")
                        .font(.footnote)
                        .foregroundStyle(Shuttl.text)
                    Text("12 points give precise homography for player tracking, speeds and zones. Pinch to zoom for accuracy.")
                        .font(.footnote)
                        .foregroundStyle(Shuttl.textSecondary)
                }
            }
        }
        .padding(.horizontal, 16)
    }

    private var canvas: some View {
        Canvas { context, size in
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
