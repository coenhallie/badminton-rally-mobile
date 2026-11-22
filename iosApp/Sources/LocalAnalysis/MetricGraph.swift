import Shared
import SwiftUI

/// Pure-Swift mirror of Kotlin's `MetricSample` so `MetricGraph` slices the
/// visible time window and builds polylines at 30 Hz without bridging up to
/// ~6,000 Kotlin objects across Objective-C on every frame.
struct CachedMetricSample: Equatable {
    let timestamp: Double
    let stanceM: Double?
    let behindServiceLineM: Double?
    let elbowLeftDeg: Double?
    let elbowRightDeg: Double?
    let armLeftDeg: Double?
    let armRightDeg: Double?
    let kneeLeftDeg: Double?
    let kneeRightDeg: Double?
    let leanDeg: Double?
    let shouldersDeg: Double?
    let hipsDeg: Double?

    init(timestamp: Double, metrics: PoseMetrics) {
        self.timestamp = timestamp
        self.stanceM = metrics.stanceM?.doubleValue
        self.behindServiceLineM = metrics.behindServiceLineM?.doubleValue
        self.elbowLeftDeg = metrics.elbowLeftDeg?.doubleValue
        self.elbowRightDeg = metrics.elbowRightDeg?.doubleValue
        self.armLeftDeg = metrics.armLeftDeg?.doubleValue
        self.armRightDeg = metrics.armRightDeg?.doubleValue
        self.kneeLeftDeg = metrics.kneeLeftDeg?.doubleValue
        self.kneeRightDeg = metrics.kneeRightDeg?.doubleValue
        self.leanDeg = metrics.trunkLeanDeg?.doubleValue
        self.shouldersDeg = metrics.shoulderTiltDeg?.doubleValue
        self.hipsDeg = metrics.hipTiltDeg?.doubleValue
    }

    init(
        timestamp: Double,
        stanceM: Double? = nil,
        behindServiceLineM: Double? = nil,
        elbowLeftDeg: Double? = nil,
        elbowRightDeg: Double? = nil,
        armLeftDeg: Double? = nil,
        armRightDeg: Double? = nil,
        kneeLeftDeg: Double? = nil,
        kneeRightDeg: Double? = nil,
        leanDeg: Double? = nil,
        shouldersDeg: Double? = nil,
        hipsDeg: Double? = nil
    ) {
        self.timestamp = timestamp
        self.stanceM = stanceM
        self.behindServiceLineM = behindServiceLineM
        self.elbowLeftDeg = elbowLeftDeg
        self.elbowRightDeg = elbowRightDeg
        self.armLeftDeg = armLeftDeg
        self.armRightDeg = armRightDeg
        self.kneeLeftDeg = kneeLeftDeg
        self.kneeRightDeg = kneeRightDeg
        self.leanDeg = leanDeg
        self.shouldersDeg = shouldersDeg
        self.hipsDeg = hipsDeg
    }

    func value(for kind: MetricKind) -> Double? {
        switch kind {
        case .stance: return stanceM
        case .behindLine: return behindServiceLineM
        case .elbowLeft: return elbowLeftDeg
        case .elbowRight: return elbowRightDeg
        case .armLeft: return armLeftDeg
        case .armRight: return armRightDeg
        case .kneeLeft: return kneeLeftDeg
        case .kneeRight: return kneeRightDeg
        case .lean: return leanDeg
        case .shoulders: return shouldersDeg
        case .hips: return hipsDeg
        }
    }
}

struct SwiftGraphPoint: Equatable {
    let timestamp: Double
    let value: Double
}

struct SwiftGraphSegments: Equatable {
    let polylines: [[SwiftGraphPoint]]
    let points: [SwiftGraphPoint]

    static let empty = SwiftGraphSegments(polylines: [], points: [])
}

/// Pure-Swift port of `MetricsFormatKt.graphSegments`, matching its exact
/// binary-search windowing, boundary-straddling and gap-splitting rules with
/// zero Kotlin/ObjC bridge overhead per draw.
func swiftGraphSegments(
    series: [CachedMetricSample],
    kind: MetricKind,
    startS: Double,
    endS: Double,
    gapS: Double
) -> SwiftGraphSegments {
    guard !series.isEmpty else { return .empty }

    var lo = 0
    var hi = series.count
    while lo < hi {
        let mid = (lo + hi) >> 1
        if series[mid].timestamp < startS {
            lo = mid + 1
        } else {
            hi = mid
        }
    }

    var polylines: [[SwiftGraphPoint]] = []
    var points: [SwiftGraphPoint] = []
    var run: [SwiftGraphPoint] = []

    func flush() {
        if run.count >= 2 {
            polylines.append(run)
        } else if run.count == 1 {
            let point = run[0]
            if point.timestamp >= startS && point.timestamp <= endS {
                points.append(point)
            }
        }
        run.removeAll(keepingCapacity: true)
    }

    var i = max(0, lo - 1)
    var prev: CachedMetricSample? = nil
    while i < series.count {
        let s = series[i]
        let value = s.value(for: kind)
        let prevValue = prev.flatMap { $0.value(for: kind) }
        let joinsPrev = value != nil && prevValue != nil && (s.timestamp - prev!.timestamp) <= gapS
        if let value {
            if !joinsPrev { flush() }
            run.append(SwiftGraphPoint(timestamp: s.timestamp, value: value))
        } else {
            flush()
        }
        prev = s
        if s.timestamp > endS { break }
        i += 1
    }
    flush()

    return SwiftGraphSegments(polylines: polylines, points: points)
}

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
/// `durationS` is the video's length in seconds, or `.infinity` while the player
/// does not know it yet. A drag clamps to it, so dragging past an end does not
/// bank travel that has to be given back before the playhead moves again.
///
/// Port of androidApp's `MetricGraph`.
struct MetricGraph: View {
    private let cachedSeries: [CachedMetricSample]
    private let playhead: PlaybackPlayhead?
    private let staticPositionS: Double
    private let staticDurationS: Double
    let kind: MetricKind
    let label: String
    let fps: Double
    let onSeek: (Double) -> Void
    var windowS: Double = 2.0

    init(
        cachedSeries: [CachedMetricSample],
        kind: MetricKind,
        label: String,
        playhead: PlaybackPlayhead,
        fps: Double,
        onSeek: @escaping (Double) -> Void,
        windowS: Double = 2.0
    ) {
        self.cachedSeries = cachedSeries
        self.playhead = playhead
        self.staticPositionS = 0
        self.staticDurationS = .infinity
        self.kind = kind
        self.label = label
        self.fps = fps
        self.onSeek = onSeek
        self.windowS = windowS
    }

    init(
        series: [MetricSample],
        kind: MetricKind,
        label: String,
        positionS: Double,
        fps: Double,
        durationS: Double,
        onSeek: @escaping (Double) -> Void,
        windowS: Double = 2.0
    ) {
        self.cachedSeries = series.map { CachedMetricSample(timestamp: $0.timestamp, metrics: $0.metrics) }
        self.playhead = nil
        self.staticPositionS = positionS
        self.staticDurationS = durationS
        self.kind = kind
        self.label = label
        self.fps = fps
        self.onSeek = onSeek
        self.windowS = windowS
    }

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
            MetricPlotView(
                cachedSeries: cachedSeries,
                kind: kind,
                rangeStart: kind.rangeStart,
                rangeEnd: kind.rangeEnd,
                playhead: playhead,
                staticPositionS: staticPositionS,
                staticDurationS: staticDurationS,
                fps: fps,
                windowS: windowS,
                onSeek: onSeek
            )
            .frame(height: MetricGraphMetrics.plotHeight)
            .padding(.top, 12)
        }
        .padding(.horizontal, 20)
        .padding(.top, 18)
        .padding(.bottom, 16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Shuttl.bgSecondary, in: RoundedRectangle(cornerRadius: ShuttlRadius.large))
        .padding(.horizontal, ShuttlGutter.page)
    }
}

/// Leaf view that observes `playhead.position` for the Canvas and scrub
/// gestures alone so the surrounding card header never re-evaluates at 30 Hz.
private struct MetricPlotView: View {
    let cachedSeries: [CachedMetricSample]
    let kind: MetricKind
    let rangeStart: Double
    let rangeEnd: Double
    let playhead: PlaybackPlayhead?
    let staticPositionS: Double
    let staticDurationS: Double
    let fps: Double
    let windowS: Double
    let onSeek: (Double) -> Void

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
    /// True for exactly as long as a drag is on the card.
    ///
    /// `@GestureState` and not `@State` because it is the one flag that resets
    /// when a gesture is CANCELLED as well as when it ends, and cancellation is
    /// the normal outcome here: a drag that starts on this card and is taken
    /// over by the ScrollView above it never delivers `onEnded`.
    @GestureState private var dragging = false

    private var gapS: Double { fps > 0 ? 2.5 / fps : 0.1 }

    private var currentPositionS: Double {
        if let playhead {
            return Double(playhead.position.positionMs) / 1000
        }
        return staticPositionS
    }

    private var currentDurationS: Double {
        if let playhead {
            let ms = playhead.position.durationMs
            return ms > 0 ? Double(ms) / 1000 : .infinity
        }
        return staticDurationS
    }

    var body: some View {
        let positionS = currentPositionS
        let durationS = currentDurationS
        GeometryReader { proxy in
            Canvas { context, size in draw(&context, size: size, positionS: positionS) }
                .contentShape(Rectangle())
                // A tap and a scrub as two gestures, not one drag of zero
                // minimum distance. A zero-distance drag claims the touch on
                // touch-down, so a finger put on this card to scroll the page
                // never reaches the scroller: the page stays still. A tap
                // gesture does not claim it, and a drag with a real minimum
                // competes with the scroll on the usual terms.
                .simultaneousGesture(
                    SpatialTapGesture(coordinateSpace: .local).onEnded { value in
                        guard proxy.size.width > 0 else { return }
                        onSeek(
                            currentPositionS
                                + (Double(value.location.x / proxy.size.width) - 0.5) * 2 * windowS
                        )
                    }
                )
                .simultaneousGesture(scrubGesture(width: proxy.size.width, durationS: durationS))
                // The two `@State` halves of a scrub, cleared on the way out of
                // every gesture including a cancelled one. `onEnded` clears
                // them too; this is what covers the case it never sees.
                .onChange(of: dragging) { _, stillDragging in
                    if !stillDragging {
                        anchorS = nil
                        isScrub = nil
                    }
                }
        }
    }

    private func draw(_ context: inout GraphicsContext, size: CGSize, positionS: Double) {
        let startS = positionS - windowS
        let span = rangeEnd - rangeStart
        func x(_ t: Double) -> CGFloat { CGFloat((t - startS) / (2 * windowS)) * size.width }
        func y(_ value: Double) -> CGFloat {
            guard span != 0 else { return size.height }
            return size.height - CGFloat((value - rangeStart) / span) * size.height
        }

        let segments = swiftGraphSegments(
            series: cachedSeries, kind: kind, startS: startS, endS: positionS + windowS, gapS: gapS
        )
        let stroke = MetricGraphMetrics.curve

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

        var playheadPath = Path()
        playheadPath.move(to: CGPoint(x: size.width / 2, y: 0))
        playheadPath.addLine(to: CGPoint(x: size.width / 2, y: size.height))
        context.stroke(playheadPath, with: .color(Shuttl.text), lineWidth: 1)
    }

    private func scrubGesture(width: CGFloat, durationS: Double) -> some Gesture {
        DragGesture(minimumDistance: MetricGraphMetrics.slop)
            .updating($dragging) { _, dragging, _ in dragging = true }
            .onChanged { value in
                guard width > 0 else { return }
                if isScrub == nil {
                    isScrub = abs(value.translation.width) > abs(value.translation.height)
                    anchorS = currentPositionS
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
}

private enum MetricGraphMetrics {
    static let plotHeight: CGFloat = 72
    static let curve: CGFloat = 1.6
    /// How far a finger travels before it counts as a drag at all, and so
    /// before its direction is read. UIKit's own pan recognisers settle at
    /// about ten points.
    static let slop: CGFloat = 10
}

private extension Double {
    func clamped(to range: ClosedRange<Double>) -> Double {
        Swift.min(Swift.max(self, range.lowerBound), range.upperBound)
    }
}
