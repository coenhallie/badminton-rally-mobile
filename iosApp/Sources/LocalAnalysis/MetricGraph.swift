import Shared
import SwiftUI

/// The selected measurement over the seconds around the playhead, on a card.
///
/// A serve is a curve, not a frame: the elbow angle through a serve is what a
/// coach compares between two serves, and a tile cannot show a curve. Raw
/// values, no smoothing, a gap wherever the joint was absent for more than two
/// frames, a fixed vertical range per kind so the shape does not rescale under
/// the eye. The card's header names the measurement and says the range once; the
/// plot itself is the mock's bare line with the playhead at its centre, no frame
/// and no axis labels. Tapping or dragging seeks.
///
/// What to draw is `graphSegments`, in `shared`, so the two platforms cannot
/// disagree about where a curve breaks.
///
/// `durationS` is the video's length in seconds, or `.infinity` while the player
/// does not know it yet. A drag clamps to it, so dragging past an end does not
/// bank travel that has to be given back before the playhead moves again.
///
/// Port of androidApp's `MetricGraph`.
struct MetricGraph: View {
    let series: [MetricSample]
    let kind: MetricKind
    let label: String
    let positionS: Double
    let fps: Double
    let durationS: Double
    let onSeek: (Double) -> Void
    var windowS: Double = 2.0

    /// Where the finger started, in seconds, once a drag has been decided to be
    /// a scrub. Clamped as it moves, not only when it seeks: an unclamped anchor
    /// would keep accumulating past the end and the drag back would spend its
    /// first centimetres undoing that instead of moving the playhead.
    @State private var anchorS: Double? = nil
    /// Nil until the drag's direction is known, then whether it is a scrub.
    ///
    /// androidApp uses `detectHorizontalDragGestures`, which yields a vertical
    /// drag to the scroller above it. A plain `DragGesture` does not: it claims
    /// the touch on touch-down, so a finger put on this card to scroll the page
    /// scrubs the video instead and the page does not move. The decision is made
    /// once per gesture and held, so a scrub that drifts vertically keeps
    /// scrubbing and a scroll that drifts sideways keeps scrolling.
    @State private var isScrub: Bool? = nil

    private var gapS: Double { fps > 0 ? 2.5 / fps : 0.1 }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .lastTextBaseline) {
                Text("\(label) around this frame")
                    .shuttlType(ShuttlType.labelMedium)
                    .foregroundStyle(Shuttl.text)
                Spacer(minLength: 8)
                Text(MetricsFormatKt.metricRangeLabel(kind: kind))
                    .shuttlType(ShuttlType.bodySmall)
                    .foregroundStyle(Shuttl.textTertiary)
            }
            plot
                .frame(height: Metrics.plotHeight)
                .padding(.top, 12)
        }
        .padding(.horizontal, 20)
        .padding(.top, 18)
        .padding(.bottom, 16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Shuttl.bgSecondary, in: RoundedRectangle(cornerRadius: ShuttlRadius.large))
        .padding(.horizontal, ShuttlGutter.page)
    }

    private var plot: some View {
        GeometryReader { proxy in
            Canvas { context, size in draw(&context, size: size) }
                .contentShape(Rectangle())
                // A tap and a scrub as two gestures, not one drag of zero
                // minimum distance. A zero-distance drag claims the touch on
                // touch-down, so a finger put on this card to scroll the page
                // never reaches the scroller: the page stays still. A tap
                // gesture does not claim it, and a drag with a real minimum
                // competes with the scroll on the usual terms.
                // Both simultaneous, and both simultaneous with the scroll: a
                // plain `.onTapGesture` beside a drag is swallowed by it, and a
                // drag that claims the touch on touch-down is what stopped the
                // page scrolling in the first place.
                .simultaneousGesture(
                    SpatialTapGesture(coordinateSpace: .local).onEnded { value in
                        guard proxy.size.width > 0 else { return }
                        onSeek(
                            positionS
                                + (Double(value.location.x / proxy.size.width) - 0.5) * 2 * windowS
                        )
                    }
                )
                .simultaneousGesture(scrubGesture(width: proxy.size.width))
        }
    }

    private func draw(_ context: inout GraphicsContext, size: CGSize) {
        let startS = positionS - windowS
        func x(_ t: Double) -> CGFloat { CGFloat((t - startS) / (2 * windowS)) * size.width }
        func y(_ value: Double) -> CGFloat {
            let span = kind.rangeEnd - kind.rangeStart
            guard span != 0 else { return size.height }
            return size.height - CGFloat((value - kind.rangeStart) / span) * size.height
        }

        let segments = MetricsFormatKt.graphSegments(
            series: series, kind: kind, startS: startS, endS: positionS + windowS, gapS: gapS
        )
        let stroke = Metrics.curve

        // Values outside the kind's range are real (a lunge behind the service
        // line reads under -1 m), so the curve is cut at the box edges rather
        // than drawn over the header.
        context.drawLayer { layer in
            layer.clip(to: Path(CGRect(origin: .zero, size: size)))
            for polyline in segments.polylines {
                var path = Path()
                for (index, point) in polyline.enumerated() {
                    let at = CGPoint(x: x(point.timestamp), y: y(point.value))
                    if index == 0 { path.move(to: at) } else { path.addLine(to: at) }
                }
                layer.stroke(
                    path, with: .color(Shuttl.accent),
                    style: StrokeStyle(lineWidth: stroke, lineCap: .round, lineJoin: .round)
                )
            }
            for point in segments.points {
                let at = CGPoint(x: x(point.timestamp), y: y(point.value))
                layer.fill(
                    Path(ellipseIn: CGRect(
                        x: at.x - stroke, y: at.y - stroke, width: stroke * 2, height: stroke * 2
                    )),
                    with: .color(Shuttl.accent)
                )
            }
        }

        var playhead = Path()
        playhead.move(to: CGPoint(x: size.width / 2, y: 0))
        playhead.addLine(to: CGPoint(x: size.width / 2, y: size.height))
        context.stroke(playhead, with: .color(Shuttl.text), lineWidth: 1)
    }

    /// Scrubbing: a drag along the curve moves the playhead with the finger.
    ///
    /// A drag that is mostly vertical is left alone, because it is the page
    /// being scrolled. androidApp gets that from `detectHorizontalDragGestures`,
    /// which yields to the scroller above it; here it is one comparison, made
    /// once per gesture and then held, so a scrub that drifts vertically keeps
    /// scrubbing and a scroll that drifts sideways keeps scrolling.
    private func scrubGesture(width: CGFloat) -> some Gesture {
        DragGesture(minimumDistance: Metrics.slop)
            .onChanged { value in
                guard width > 0 else { return }
                if isScrub == nil {
                    isScrub = abs(value.translation.width) > abs(value.translation.height)
                    anchorS = positionS
                }
                guard isScrub == true, let from = anchorS else { return }
                let moved = Double(value.translation.width / width) * 2 * windowS
                onSeek((from - moved).clamped(to: 0...max(0, durationS)))
            }
            .onEnded { _ in
                anchorS = nil
                isScrub = nil
            }
    }

    private enum Metrics {
        static let plotHeight: CGFloat = 72
        static let curve: CGFloat = 1.6
        /// How far a finger travels before it counts as a drag at all, and so
        /// before its direction is read. UIKit's own pan recognisers settle at
        /// about ten points.
        static let slop: CGFloat = 10
    }
}

private extension Double {
    func clamped(to range: ClosedRange<Double>) -> Double {
        Swift.min(Swift.max(self, range.lowerBound), range.upperBound)
    }
}
