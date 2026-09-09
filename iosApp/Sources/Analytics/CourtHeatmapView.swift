import Shared
import SwiftUI

/// Where the player spent the match, drawn on the court rather than on the
/// video. Port of Android's `CourtHeatmapView`.
///
/// The court is the frame of reference on purpose. The web app bins into
/// video-normalised pixels, which ties the picture to where the camera stood:
/// two matches cannot be compared, and because perspective makes a pixel near
/// the camera cover less court than one at the far baseline, a fixed pixel blur
/// is a spatially varying blur in the real world. Drawn in metres, one cell is
/// one cell everywhere.
///
/// Aspect ratio comes from the real court, so the drawing cannot silently
/// stretch and misrepresent how far the player actually ranged.
struct CourtHeatmapView: View {
    let track: PlayerTrack
    let fps: Double
    var cellSizeM: Double = CourtOccupancy.companion.DEFAULT_CELL_SIZE_M

    /// The court plus the margin the selector accepts, so a lunge past the
    /// baseline is drawn where it was rather than clamped onto the line.
    static let drawMarginM: Double = 2.0
    /// The page gutter the mock lays every card in.
    static let gutter: CGFloat = 24
    /// The mock's court panel is 232pt in a 345pt column. Wider here because
    /// the canvas carries the two-metre margin on every side, which the mock's
    /// panel does not, so at the mock's width the court itself would be a third
    /// narrower.
    private static let cardMaxWidth: CGFloat = 300

    var body: some View {
        if track.samples.isEmpty {
            PanelMessage(text: whyEmpty(track))
        } else {
            let occupancy = CourtOccupancy(cellSizeM: cellSizeM, marginM: Self.drawMarginM)
            let _ = occupancy.addAll(samples: track.samples, fps: fps)
            let grid = occupancy.smoothed(sigmaM: CourtOccupancy.companion.DEFAULT_SIGMA_M)
                .map { $0.map { $0.doubleValue } }
            let peak = grid.flatMap { $0 }.max() ?? 0

            VStack(spacing: 0) {
                courtCard(grid: grid, peak: peak)
                scale
                summary(occupancy: occupancy)
                Spacer(minLength: Self.gutter)
            }
        }
    }

    // MARK: - The court

    @ViewBuilder
    private func courtCard(grid: [[Double]], peak: Double) -> some View {
        let totalWidth = Court.shared.WIDTH_DOUBLES + 2 * Self.drawMarginM
        let totalHeight = Court.shared.LENGTH + 2 * Self.drawMarginM
        ZStack {
            // One pixel per cell, scaled up by the renderer rather than drawn as
            // rectangles.
            //
            // Rectangles produced two artefacts on Android that made a smooth
            // field look like data it is not: each cell drawn a pixel oversized
            // to avoid hairline gaps, so neighbours double-blended into a bright
            // grid; and every cell with any value floored to 15% opacity, which
            // turned the Gaussian tail into a solid plateau with a hard
            // rectangular edge. Interpolating an image has no seams to cover and
            // lets the tail reach zero.
            if let image = Self.heatImage(grid: grid, peak: peak) {
                Image(decorative: image, scale: 1)
                    .resizable()
                    .interpolation(.high)
            }
            Canvas { context, size in
                Self.drawCourt(context: &context, size: size, marginM: Self.drawMarginM)
            }
        }
        .aspectRatio(totalWidth / totalHeight, contentMode: .fit)
        .frame(maxWidth: Self.cardMaxWidth)
        .background(Shuttl.bgSecondary)
        .clipShape(RoundedRectangle(cornerRadius: ShuttlRadius.medium))
        .overlay(
            RoundedRectangle(cornerRadius: ShuttlRadius.medium)
                .stroke(Shuttl.border, lineWidth: 1)
        )
        .frame(maxWidth: .infinity)
        .padding(.horizontal, Self.gutter)
    }

    /// The scale, said once under the court as the mock says it: the ramp itself
    /// from nothing to the hottest cell, and the words for its ends.
    private var scale: some View {
        HStack(spacing: 12) {
            LinearGradient(
                colors: [Self.cool.opacity(0), Self.cool, Self.hot],
                startPoint: .leading, endPoint: .trailing
            )
            .frame(height: 3)
            .clipShape(Capsule())
            Text("Low to high")
                .shuttlType(ShuttlType.bodySmall)
                .foregroundStyle(Shuttl.textTertiary)
        }
        .padding(.horizontal, Self.gutter)
        .padding(.vertical, 18)
    }

    /// How much of the map to trust, as the mock's pair of stat tiles.
    ///
    /// Coverage is the whole quality story: every sample stands on the ankles,
    /// so a frame without confident ankles contributes nothing rather than a
    /// guess, and the share of frames that produced a position is what the
    /// picture rests on.
    private func summary(occupancy: CourtOccupancy) -> some View {
        HStack(spacing: 8) {
            // Two lines for the caption: both of these run longer than half a
            // phone, and the shared tile's one-line default would ellipsize
            // "Frames with the player" to "Frames with the…".
            ShuttlStatTile(
                value: String(format: "%.1f", occupancy.totalSeconds / 60),
                unit: " min", label: "Tracked", labelLineLimit: 2
            )
            ShuttlStatTile(
                value: "\(Int(track.coverage * 100))",
                unit: "%", label: "Frames with the player", labelLineLimit: 2
            )
        }
        .padding(.horizontal, Self.gutter)
    }

    // MARK: - Drawing

    /// The occupancy field as one small image, one pixel per cell.
    ///
    /// Alpha rises with occupancy and reaches zero where there is none, so the
    /// edge of the data is where the player stopped going rather than where the
    /// grid happens to end.
    static func heatImage(grid: [[Double]], peak: Double) -> CGImage? {
        guard peak > 0, let first = grid.first, !first.isEmpty else { return nil }
        let rows = grid.count
        let columns = first.count
        var pixels = [UInt8](repeating: 0, count: rows * columns * 4)
        for r in 0..<rows {
            for c in 0..<columns {
                // Square root rather than linear: occupancy is heavily peaked
                // around a base position, and a linear ramp renders everywhere
                // else as empty when it is not.
                let t = min(max((grid[r][c] / peak).squareRoot(), 0), 1)
                let colour = Self.ramp(t)
                let alpha = t
                // Premultiplied, because that is what the bitmap context below
                // declares. Storing straight alpha there tints the cool end
                // toward white as it fades.
                let at = (r * columns + c) * 4
                pixels[at] = UInt8(colour.0 * alpha * 255)
                pixels[at + 1] = UInt8(colour.1 * alpha * 255)
                pixels[at + 2] = UInt8(colour.2 * alpha * 255)
                pixels[at + 3] = UInt8(alpha * 255)
            }
        }
        guard let provider = CGDataProvider(data: Data(pixels) as CFData) else { return nil }
        return CGImage(
            width: columns, height: rows, bitsPerComponent: 8, bitsPerPixel: 32,
            bytesPerRow: columns * 4,
            space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.premultipliedLast.rawValue),
            provider: provider, decode: nil, shouldInterpolate: true,
            intent: .defaultIntent
        )
    }

    /// Court lines, in the same metre space as the grid underneath.
    ///
    /// Drawn from `Court`'s dimensions rather than hand-placed fractions, so the
    /// lines and the data cannot drift apart.
    static func drawCourt(context: inout GraphicsContext, size: CGSize, marginM: Double) {
        let court = Court.shared
        let totalW = court.WIDTH_DOUBLES + 2 * marginM
        let totalH = court.LENGTH + 2 * marginM
        func x(_ m: Double) -> CGFloat { CGFloat((m + marginM) / totalW) * size.width }
        func y(_ m: Double) -> CGFloat { CGFloat((m + marginM) / totalH) * size.height }
        // 0.75 alpha is measured, not chosen by eye. Against the real
        // backgrounds the court lines come out at 3.8:1 light and 3.9:1 dark,
        // which clears WCAG 1.4.11's 3:1 for a non-text graphical object - and
        // this view's whole argument is that the court IS the frame of
        // reference, so the lines are not decoration.
        let colour = GraphicsContext.Shading.color(Shuttl.textSecondary.opacity(0.75))

        func line(_ x1: Double, _ y1: Double, _ x2: Double, _ y2: Double, width: CGFloat = 1.5) {
            var path = Path()
            path.move(to: CGPoint(x: x(x1), y: y(y1)))
            path.addLine(to: CGPoint(x: x(x2), y: y(y2)))
            context.stroke(path, with: colour, lineWidth: width)
        }

        // Outer doubles court.
        line(0, 0, court.WIDTH_DOUBLES, 0)
        line(0, court.LENGTH, court.WIDTH_DOUBLES, court.LENGTH)
        line(0, 0, 0, court.LENGTH)
        line(court.WIDTH_DOUBLES, 0, court.WIDTH_DOUBLES, court.LENGTH)
        // Net, heavier because it is the one line that orients the whole
        // picture.
        line(0, court.LENGTH / 2, court.WIDTH_DOUBLES, court.LENGTH / 2, width: 3)
        // Singles sidelines.
        let inset = (court.WIDTH_DOUBLES - court.WIDTH_SINGLES) / 2
        line(inset, 0, inset, court.LENGTH)
        line(court.WIDTH_DOUBLES - inset, 0, court.WIDTH_DOUBLES - inset, court.LENGTH)
        // Short service lines, one either side of the net.
        line(0, court.LENGTH / 2 - court.SERVICE_LINE, court.WIDTH_DOUBLES, court.LENGTH / 2 - court.SERVICE_LINE)
        line(0, court.LENGTH / 2 + court.SERVICE_LINE, court.WIDTH_DOUBLES, court.LENGTH / 2 + court.SERVICE_LINE)
        // Centre line, on the near half only: this pipeline tracks one player.
        line(court.WIDTH_DOUBLES / 2, court.LENGTH / 2 + court.SERVICE_LINE,
             court.WIDTH_DOUBLES / 2, court.LENGTH)
    }

    /// DELIBERATELY raw hex, outside `ShuttlPalette`, and not a defect to fix.
    ///
    /// These two are data, not chrome. They are the endpoints of a scale that
    /// encodes occupancy, so what has to stay readable is the GRADIENT BETWEEN
    /// them - a viewer reads "here more than there" - not either endpoint's
    /// contrast against the surface behind it. A theme-swapped ramp would change
    /// what a colour MEANS between light and dark, which is the one thing a
    /// scale may not do; the court lines over it are themed precisely because
    /// they are chrome and carry no value.
    ///
    /// Blue-to-orange rather than the more common green-to-red: it survives the
    /// two most common colour-vision deficiencies, and green is already the
    /// accent, which would read as an interface colour on top of a court.
    private static let cool = Color(red: 0x29 / 255, green: 0x62 / 255, blue: 0xFF / 255)
    private static let hot = Color(red: 0xFF / 255, green: 0x6D / 255, blue: 0x00 / 255)

    private static func ramp(_ t: Double) -> (Double, Double, Double) {
        (
            (0x29 + (0xFF - 0x29) * t) / 255,
            (0x62 + (0x6D - 0x62) * t) / 255,
            (0xFF + (0x00 - 0xFF) * t) / 255
        )
    }
}

/// Why the heatmap is empty, said in the terms the coach can act on.
func whyEmpty(_ track: PlayerTrack) -> String {
    if track.rejections[RejectionReason.badCourt] != nil {
        return "The court marks do not fit a badminton court, so positions cannot be trusted. "
            + "Mark the court again and re-run the analysis."
    }
    if track.framesWithPose == 0 {
        return "No pose data for this video. It was analyzed for rallies only."
    }
    return "The player was not found on the near court in any of \(track.framesWithPose) frames."
}

/// One line of explanation where a panel would otherwise draw nothing.
///
/// A blank court reads as a player who never moved, which is a different claim
/// from "there is no data", and this app must not make the first one by
/// accident.
struct PanelMessage: View {
    let text: String

    var body: some View {
        Text(text)
            .shuttlType(ShuttlType.bodySmall)
            .foregroundStyle(Shuttl.textTertiary)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, CourtHeatmapView.gutter)
            .padding(.vertical, 16)
    }
}
