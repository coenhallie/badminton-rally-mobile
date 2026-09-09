import Shared
import SwiftUI

/// What the overlay emphasises for the selected tile: the arc of an angle, the
/// line between the ankles, or a tilt line with the horizontal it is measured
/// from.
enum PoseHighlight: Equatable {
    case angle(a: Int, vertex: Int, c: Int)
    case stance
    case tilt(left: Int, right: Int)
}

/// The highlight for one measurement, or none for a measurement with no
/// geometry to point at.
///
/// Reads `MetricKind`'s own joint lists rather than restating them, so a kind
/// whose arc moves moves on both platforms at once. Port of androidApp's
/// `MetricKind.highlight()`.
func poseHighlight(for kind: MetricKind) -> PoseHighlight? {
    if let joints = kind.angleJoints,
       let a = (joints.first as? KotlinInt)?.intValue,
       let vertex = (joints.second as? KotlinInt)?.intValue,
       let c = (joints.third as? KotlinInt)?.intValue {
        return .angle(a: a, vertex: vertex, c: c)
    }
    if let line = kind.lineJoints,
       let left = (line.first as? KotlinInt)?.intValue,
       let right = (line.second as? KotlinInt)?.intValue {
        return .tilt(left: left, right: right)
    }
    return kind == .stance ? .stance : nil
}

/// One player's pose, drawn over the video frame it came from.
///
/// Keypoints arrive in SOURCE-VIDEO pixels, as everything downstream of the
/// platform layer does, so this maps them into whatever box the video is being
/// displayed in. Taking the video's stored size as a parameter rather than
/// assuming the canvas matches it is what keeps the drawing correct when the
/// player is letterboxed inside its container, which it usually is.
///
/// Low-confidence joints are omitted rather than drawn faintly. A limb drawn to
/// a keypoint the model is unsure of puts an arm through the figure's chest,
/// which a viewer reads as broken tracking rather than as uncertainty.
///
/// `highlight` puts the number and the geometry it came from on the same pixels.
/// It is drawn last so it sits over the limbs, and it is omitted when any joint
/// it needs is below `minConfidence`, the same rule the limbs follow.
///
/// Port of androidApp's `SkeletonOverlay`.
struct SkeletonOverlay: View {
    let keypoints: [Point]
    /// Already unwrapped from the pose's `[KotlinFloat]` by the caller: this
    /// view redraws with the playhead, and unwrapping seventeen boxed floats per
    /// redraw is work the pose only has to have done to it once.
    let confidence: [Float]
    let videoWidth: Int
    let videoHeight: Int
    var minConfidence: Float = NearPlayerSelector.companion.MIN_KEYPOINT_CONFIDENCE
    var highlight: PoseHighlight? = nil

    var body: some View {
        if keypoints.count >= Joints.count, videoWidth > 0, videoHeight > 0 {
            Canvas { context, size in draw(&context, size: size) }
                .allowsHitTesting(false)
        }
    }

    private func draw(_ context: inout GraphicsContext, size: CGSize) {
        // The video is fitted inside this box, so the same letterboxing the
        // player applies has to be applied here or the skeleton drifts off the
        // body at any aspect ratio but the exact one.
        let scale = min(size.width / CGFloat(videoWidth), size.height / CGFloat(videoHeight))
        let offsetX = (size.width - CGFloat(videoWidth) * scale) / 2
        let offsetY = (size.height - CGFloat(videoHeight) * scale) / 2
        func at(_ index: Int) -> CGPoint {
            CGPoint(
                x: offsetX + CGFloat(keypoints[index].x) * scale,
                y: offsetY + CGFloat(keypoints[index].y) * scale
            )
        }
        func confident(_ index: Int) -> Bool {
            index < confidence.count && confidence[index] >= minConfidence
        }
        func stroke(_ from: CGPoint, _ to: CGPoint, _ colour: Color, width: CGFloat, dash: [CGFloat] = []) {
            var path = Path()
            path.move(to: from)
            path.addLine(to: to)
            context.stroke(path, with: .color(colour), style: StrokeStyle(lineWidth: width, dash: dash))
        }

        // `edgeVisible` rather than a local "both ends confident": the rule is
        // in `:analysis` so the two platforms cannot draw different limbs, and
        // the boxed confidences it needs are built once per redraw rather than
        // once per edge.
        let boxed = confidence.map { KotlinFloat(float: $0) }
        for edge in Joints.edges
        where Skeleton.shared.edgeVisible(confidence: boxed, edge: edge.pair, minConfidence: minConfidence) {
            stroke(at(edge.a), at(edge.b), edge.arm ? Palette.arm : Palette.limb, width: Metrics.limb)
        }

        for k in 0..<Joints.count where confident(k) {
            // The ankles are marked apart because they are the measurement: the
            // heatmap is built from their midpoint, so seeing them land on the
            // feet is how the projection is checked by eye.
            let ankle = k == Joints.leftAnkle || k == Joints.rightAnkle
            let radius = ankle ? Metrics.ankleRadius : Metrics.jointRadius
            let centre = at(k)
            context.fill(
                Path(ellipseIn: CGRect(
                    x: centre.x - radius, y: centre.y - radius,
                    width: radius * 2, height: radius * 2
                )),
                with: .color(ankle ? Palette.ankle : Palette.joint)
            )
        }

        switch highlight {
        case .angle(let a, let vertex, let c):
            guard confident(a), confident(vertex), confident(c) else { return }
            let centre = at(vertex)
            let toA = CGPoint(x: at(a).x - centre.x, y: at(a).y - centre.y)
            let toC = CGPoint(x: at(c).x - centre.x, y: at(c).y - centre.y)
            guard hypot(toA.x, toA.y) > 0, hypot(toC.x, toC.y) > 0 else { return }
            let start = atan2(toA.y, toA.x)
            var sweep = atan2(toC.y, toC.x) - start
            // The short way round: the interior angle, never its reflex.
            while sweep > .pi { sweep -= 2 * .pi }
            while sweep < -.pi { sweep += 2 * .pi }
            var arc = Path()
            arc.addArc(
                center: centre, radius: Metrics.arcRadius,
                startAngle: .radians(start), endAngle: .radians(start + sweep),
                // `clockwise` is read in SwiftUI's own y-down space, where the
                // negative sweep is the counter-clockwise one.
                clockwise: sweep < 0
            )
            context.stroke(arc, with: .color(Palette.highlight), lineWidth: Metrics.highlight)
        case .stance:
            guard confident(Joints.leftAnkle), confident(Joints.rightAnkle) else { return }
            stroke(at(Joints.leftAnkle), at(Joints.rightAnkle), Palette.highlight, width: Metrics.highlight)
        case .tilt(let left, let right):
            guard confident(left), confident(right) else { return }
            let a = at(left)
            let b = at(right)
            // The horizontal the tilt is measured from, dashed and as long as
            // the line, so the angle between the two is the number on the tile.
            let half = hypot(b.x - a.x, b.y - a.y) / 2
            let mid = CGPoint(x: (a.x + b.x) / 2, y: (a.y + b.y) / 2)
            stroke(
                CGPoint(x: mid.x - half, y: mid.y),
                CGPoint(x: mid.x + half, y: mid.y),
                Palette.highlight.opacity(0.6),
                width: Metrics.highlight * 0.6,
                dash: [8, 6]
            )
            stroke(a, b, Palette.highlight, width: Metrics.highlight)
        case nil:
            break
        }
    }

    /// androidApp's numbers converted from raw canvas pixels to points.
    ///
    /// Compose's `drawLine` takes pixels, so its `4f` limb is 1.33dp on the 3x
    /// phone the mock was drawn for and thinner still on a denser one - a
    /// density dependence that is a defect over there rather than a look to
    /// reproduce. These are the same weights at 3x, stated in points, so they
    /// hold on every iPhone.
    private enum Metrics {
        static let limb: CGFloat = 1.5
        static let jointRadius: CGFloat = 1.5
        static let ankleRadius: CGFloat = 2.5
        static let arcRadius: CGFloat = 7.5
        static let highlight: CGFloat = 1
    }

    /// The edge list and the two ankle indices, crossed from Kotlin once for the
    /// process rather than on every redraw.
    ///
    /// `Skeleton.EDGES` and `Coco` are the shared source of both - a limb
    /// attached to the wrong joint on one platform only is exactly what putting
    /// them in `:analysis` prevents - but each read is an Objective-C call
    /// through a boxed `KotlinPair`, and this view redraws with the playhead.
    private enum Joints {
        static let count = Int(Coco.shared.COUNT)
        static let leftAnkle = Int(Coco.shared.LEFT_ANKLE)
        static let rightAnkle = Int(Coco.shared.RIGHT_ANKLE)

        /// The pair travels with the unwrapped indices so `edgeVisible` can
        /// still be asked without rebuilding it.
        static let edges: [(a: Int, b: Int, arm: Bool, pair: KotlinPair<KotlinInt, KotlinInt>)] = {
            let arms = Skeleton.shared.ARM_EDGES
            return Skeleton.shared.EDGES.compactMap { edge in
                guard let a = (edge.first as? KotlinInt)?.intValue,
                      let b = (edge.second as? KotlinInt)?.intValue
                else { return nil }
                return (a: a, b: b, arm: arms.contains(edge), pair: edge)
            }
        }()
    }

    /// The same five colours androidApp draws, stated as literals for the reason
    /// `ShuttlVideoOverlay`'s are: these sit ON the video, which is dark
    /// whatever the app's theme is, so they must not flip with the palette.
    private enum Palette {
        static let limb = Color(red: 0, green: 0.898, blue: 1)
        static let arm = Color(red: 1, green: 0.839, blue: 0)
        static let joint = Color.white
        static let ankle = Color(red: 1, green: 0.239, blue: 0)
        static let highlight = Color(red: 0.878, green: 0.251, blue: 0.984)
    }
}
