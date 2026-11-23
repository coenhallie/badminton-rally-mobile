import Shared
import SwiftUI

private let doublesBackServiceInsetM: CGFloat = 0.76

/// Compact animated drill visual rendered inside an expanded `ExerciseCard`.
///
/// Port of androidApp's `ExerciseVisualView`: on-court drills show a top-down
/// half-court movement pattern beside an articulated badminton pose figure;
/// off-court exercises show a wider single pose stage. Mirrors automatically
/// when `racketArm` is `.left` and honours Reduce Motion with tap-to-step.
struct ExerciseVisualView: View {
    let exerciseId: String
    let racketArm: RacketArm?

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var paused = false
    @State private var stepIndex = 1
    @State private var baseDate = Date()
    @State private var frozenElapsed: TimeInterval = 0

    var body: some View {
        let visual = ExerciseVisualsKt.visualFor(exerciseId: exerciseId)
        let mirrorX = racketArm == .left
        let frameCount = max(1, visual.pose.keyframes.count)

        TimelineView(.animation(paused: reduceMotion || paused)) { timeline in
            let elapsed = paused ? frozenElapsed : max(0, timeline.date.timeIntervalSince(baseDate))
            let posePhase: Float = reduceMotion
                ? Float(stepIndex % frameCount) / Float(frameCount)
                : phase01(elapsed: elapsed, cycleSeconds: visual.pose.cycleSeconds)
            let courtPhase: Float = {
                guard let path = visual.courtPath else { return 0 }
                if reduceMotion {
                    let segments = max(1, path.waypoints.count - 1)
                    return Float(stepIndex % segments) / Float(segments)
                }
                return phase01(elapsed: elapsed, cycleSeconds: path.cycleSeconds)
            }()
            let sampledPose = ExerciseVisualsKt.samplePose(
                sequence: visual.pose,
                phase01: posePhase,
                mirrorX: mirrorX
            )
            let summary = visual.courtPath != nil
                ? "\(ExercisesCopy.shared.COURT_VISUAL_CAPTION) and \(ExercisesCopy.shared.poseCaption(phaseLabel: sampledPose.label))"
                : ExercisesCopy.shared.poseCaption(phaseLabel: sampledPose.label)

            Button {
                if reduceMotion {
                    stepIndex = (stepIndex + 1) % frameCount
                } else if paused {
                    baseDate = Date().addingTimeInterval(-frozenElapsed)
                    paused = false
                } else {
                    frozenElapsed = max(0, Date().timeIntervalSince(baseDate))
                    paused = true
                }
            } label: {
                if let path = visual.courtPath {
                    HStack(spacing: 8) {
                        VisualTile(
                            caption: ExercisesCopy.shared.COURT_VISUAL_CAPTION,
                            captionColor: Shuttl.textSecondary,
                            tileHeight: 136
                        ) {
                            HalfCourtCanvas(path: path, phase01: courtPhase, mirrorX: mirrorX)
                        }
                        VisualTile(
                            caption: ExercisesCopy.shared.poseCaption(phaseLabel: sampledPose.label),
                            captionColor: Shuttl.accentDark,
                            tileHeight: 136
                        ) {
                            PoseFigureCanvas(figure: sampledPose.figure)
                        }
                    }
                } else {
                    VisualTile(
                        caption: ExercisesCopy.shared.poseCaption(phaseLabel: sampledPose.label),
                        captionColor: Shuttl.accentDark,
                        tileHeight: 148
                    ) {
                        PoseFigureCanvas(figure: sampledPose.figure)
                    }
                }
            }
            .buttonStyle(.plain)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(summary)
            .accessibilityHint(
                reduceMotion
                    ? "Double tap to step through poses"
                    : (paused ? "Double tap to play animation" : "Double tap to pause animation")
            )
        }
    }

    private func phase01(elapsed: TimeInterval, cycleSeconds: Double) -> Float {
        let cycle = max(0.4, cycleSeconds)
        return Float((elapsed / cycle).truncatingRemainder(dividingBy: 1.0))
    }
}

private struct VisualTile<Content: View>: View {
    let caption: String
    let captionColor: Color
    let tileHeight: CGFloat
    @ViewBuilder let content: () -> Content

    var body: some View {
        VStack(spacing: 4) {
            content()
                .frame(maxWidth: .infinity)
                .frame(height: tileHeight)
                .background(Shuttl.bg, in: RoundedRectangle(cornerRadius: ShuttlRadius.small))
                .overlay(
                    RoundedRectangle(cornerRadius: ShuttlRadius.small)
                        .stroke(Shuttl.border, lineWidth: 1)
                )
            Text(caption)
                .shuttlType(ShuttlType.labelSmall)
                .foregroundStyle(captionColor)
                .lineLimit(1)
                .truncationMode(.tail)
        }
        .frame(maxWidth: .infinity)
    }
}

private struct HalfCourtCanvas: View {
    let path: CourtDrillPath
    let phase01: Float
    let mirrorX: Bool

    var body: some View {
        let waypoints = ExerciseVisualsKt.mirroredCourtPoints(points: path.waypoints, mirrorX: mirrorX)
        let baseMarker = ExerciseVisualsKt.mirroredCourtPoint(point: path.baseMarker, mirrorX: mirrorX)
        let shuttleStart = path.shuttleStart.map {
            ExerciseVisualsKt.mirroredCourtPoint(point: $0, mirrorX: mirrorX)
        }
        let shuttleEnd = path.shuttleEnd.map {
            ExerciseVisualsKt.mirroredCourtPoint(point: $0, mirrorX: mirrorX)
        }
        let playerPos = ExerciseVisualsKt.sampleCourtPoint(path: path, phase01: phase01, mirrorX: mirrorX)

        Canvas { context, size in
            let courtW = CGFloat(Court.shared.WIDTH_DOUBLES)
            let courtH = CGFloat(Court.shared.LENGTH / 2.0)
            let singlesInset = CGFloat((Court.shared.WIDTH_DOUBLES - Court.shared.WIDTH_SINGLES) / 2.0)
            let serviceY = CGFloat(Court.shared.SERVICE_LINE)
            let doublesBackY = courtH - doublesBackServiceInsetM
            let centreX = courtW / 2.0

            let pad: CGFloat = 10
            let availW = max(1, size.width - pad * 2)
            let availH = max(1, size.height - pad * 2)
            let scale = min(availW / courtW, availH / courtH)
            let drawW = courtW * scale
            let drawH = courtH * scale
            let left = (size.width - drawW) / 2.0
            let top = (size.height - drawH) / 2.0

            func toCanvas(_ p: VisualPoint) -> CGPoint {
                CGPoint(x: left + CGFloat(p.x) * scale, y: top + CGFloat(p.y) * scale)
            }
            func toCanvas(_ x: CGFloat, _ y: CGFloat) -> CGPoint {
                CGPoint(x: left + x * scale, y: top + y * scale)
            }

            let courtRect = CGRect(x: left, y: top, width: drawW, height: drawH)
            context.fill(Path(courtRect), with: .color(Shuttl.accent.opacity(0.06)))

            let lineShading = GraphicsContext.Shading.color(Shuttl.textSecondary.opacity(0.45))
            context.stroke(Path(courtRect), with: lineShading, lineWidth: 1)

            func strokeLine(_ a: CGPoint, _ b: CGPoint, shading: GraphicsContext.Shading = lineShading, width: CGFloat = 1) {
                var p = Path()
                p.move(to: a)
                p.addLine(to: b)
                context.stroke(p, with: shading, style: StrokeStyle(lineWidth: width, lineCap: .round))
            }

            // Singles sidelines, short service line, doubles back service line, centre line.
            strokeLine(toCanvas(singlesInset, 0), toCanvas(singlesInset, courtH))
            strokeLine(toCanvas(courtW - singlesInset, 0), toCanvas(courtW - singlesInset, courtH))
            strokeLine(toCanvas(0, serviceY), toCanvas(courtW, serviceY))
            strokeLine(toCanvas(0, doublesBackY), toCanvas(courtW, doublesBackY))
            strokeLine(toCanvas(centreX, serviceY), toCanvas(centreX, courtH))

            // Net across top edge with small post overhangs.
            strokeLine(
                toCanvas(-0.20, 0),
                toCanvas(courtW + 0.20, 0),
                shading: .color(Shuttl.textHeading.opacity(0.68)),
                width: 2
            )

            // Optional shuttle feed trajectory.
            if let shuttleStart, let shuttleEnd {
                let shuttleShading = GraphicsContext.Shading.color(Shuttl.textSecondary.opacity(0.55))
                var feed = Path()
                feed.move(to: toCanvas(shuttleStart))
                feed.addLine(to: toCanvas(shuttleEnd))
                context.stroke(
                    feed,
                    with: shuttleShading,
                    style: StrokeStyle(lineWidth: 1.25, lineCap: .round, dash: [2.5, 3])
                )
                context.fill(circlePath(at: toCanvas(shuttleEnd), radius: 2.2), with: shuttleShading)
            }

            // Dashed movement pattern connecting drill waypoints.
            if waypoints.count >= 2 {
                var route = Path()
                route.move(to: toCanvas(waypoints[0]))
                for pt in waypoints.dropFirst() {
                    route.addLine(to: toCanvas(pt))
                }
                context.stroke(
                    route,
                    with: .color(Shuttl.accent.opacity(0.45)),
                    style: StrokeStyle(lineWidth: 1.5, lineCap: .round, lineJoin: .round, dash: [4, 3])
                )
            }

            // Subtle corner target dots away from the base marker.
            for wp in waypoints {
                let dx = CGFloat(wp.x - baseMarker.x)
                let dy = CGFloat(wp.y - baseMarker.y)
                if hypot(dx, dy) > 0.35 {
                    context.fill(
                        circlePath(at: toCanvas(wp), radius: 2.4),
                        with: .color(Shuttl.accent.opacity(0.32))
                    )
                }
            }

            // Target base / cone ring.
            context.stroke(
                circlePath(at: toCanvas(baseMarker), radius: 6),
                with: .color(Shuttl.accent),
                lineWidth: 1.75
            )

            // Animated player marker with soft halo and crisp core.
            let dotCenter = toCanvas(playerPos)
            context.fill(circlePath(at: dotCenter, radius: 7.5), with: .color(Shuttl.accent.opacity(0.22)))
            context.fill(circlePath(at: dotCenter, radius: 4.2), with: .color(Shuttl.accent))
            context.fill(circlePath(at: dotCenter, radius: 1.6), with: .color(Shuttl.bg))
        }
    }
}

private struct PoseFigureCanvas: View {
    let figure: PoseFigure

    var body: some View {
        Canvas { context, size in
            let pad: CGFloat = 8
            let side = max(1, min(size.width - pad * 2, size.height - pad * 2))
            let originX = (size.width - side) / 2.0
            let originY = (size.height - side) / 2.0

            func pt(_ p: VisualPoint) -> CGPoint {
                CGPoint(x: originX + CGFloat(p.x) * side, y: originY + CGFloat(p.y) * side)
            }

            func drawLimb(_ a: CGPoint, _ b: CGPoint, width: CGFloat, color: Color) {
                var p = Path()
                p.move(to: a)
                p.addLine(to: b)
                context.stroke(p, with: .color(color), style: StrokeStyle(lineWidth: width, lineCap: .round))
            }

            // Ground baseline and subtle contact shadow under the stance.
            let groundY = originY + CGFloat(ExerciseVisualsKt.POSE_GROUND_Y) * side
            drawLimb(
                CGPoint(x: originX + 0.08 * side, y: groundY),
                CGPoint(x: originX + 0.92 * side, y: groundY),
                width: 1.25,
                color: Shuttl.border
            )

            let ankleL = pt(figure.ankleL)
            let ankleR = pt(figure.ankleR)
            let shadowLeft = min(ankleL.x, ankleR.x) - side * 0.06
            let shadowRight = max(ankleL.x, ankleR.x) + side * 0.06
            let shadowH = side * 0.028
            let shadowRect = CGRect(
                x: shadowLeft,
                y: groundY - shadowH / 2.0,
                width: max(side * 0.18, shadowRight - shadowLeft),
                height: shadowH
            )
            context.fill(Path(ellipseIn: shadowRect), with: .color(Shuttl.textSecondary.opacity(0.14)))

            let shoulderL = pt(figure.shoulderL)
            let shoulderR = pt(figure.shoulderR)
            let elbowL = pt(figure.elbowL)
            let elbowR = pt(figure.elbowR)
            let wristL = pt(figure.wristL)
            let wristR = pt(figure.wristR)
            let hipL = pt(figure.hipL)
            let hipR = pt(figure.hipR)
            let kneeL = pt(figure.kneeL)
            let kneeR = pt(figure.kneeR)
            let neck = pt(figure.neck)
            let head = pt(figure.head)

            let bodyColor = Shuttl.textHeading
            let backLimbColor = Shuttl.textHeading.opacity(0.72)
            let thighW = side * 0.038
            let shinW = side * 0.031
            let upperArmW = side * 0.032
            let forearmW = side * 0.026
            let torsoW = side * 0.028

            // Non-racket leg and arm behind torso.
            drawLimb(hipL, kneeL, width: thighW, color: backLimbColor)
            drawLimb(kneeL, ankleL, width: shinW, color: backLimbColor)
            drawLimb(shoulderL, elbowL, width: upperArmW, color: backLimbColor)
            drawLimb(elbowL, wristL, width: forearmW, color: backLimbColor)

            // Softly shaded torso polygon + shoulder/hip/spine frame.
            var torso = Path()
            torso.move(to: shoulderL)
            torso.addLine(to: shoulderR)
            torso.addLine(to: hipR)
            torso.addLine(to: hipL)
            torso.closeSubpath()
            context.fill(torso, with: .color(bodyColor.opacity(0.13)))
            drawLimb(shoulderL, shoulderR, width: torsoW, color: bodyColor)
            drawLimb(hipL, hipR, width: torsoW, color: bodyColor)
            let midHip = CGPoint(x: (hipL.x + hipR.x) / 2.0, y: (hipL.y + hipR.y) / 2.0)
            drawLimb(neck, midHip, width: torsoW, color: bodyColor)

            // Racket-side leg and arm in foreground.
            drawLimb(hipR, kneeR, width: thighW, color: bodyColor)
            drawLimb(kneeR, ankleR, width: shinW, color: bodyColor)
            drawLimb(shoulderR, elbowR, width: upperArmW, color: bodyColor)
            drawLimb(elbowR, wristR, width: forearmW, color: bodyColor)

            // Grounded foot ticks extending outward from stance midpoint.
            let midFootX = (ankleL.x + ankleR.x) / 2.0
            let footLen = side * 0.038
            drawLimb(
                ankleL,
                CGPoint(x: ankleL.x + (ankleL.x <= midFootX ? -footLen : footLen), y: ankleL.y),
                width: shinW * 0.85,
                color: backLimbColor
            )
            drawLimb(
                ankleR,
                CGPoint(x: ankleR.x + (ankleR.x >= midFootX ? footLen : -footLen), y: ankleR.y),
                width: shinW * 0.85,
                color: bodyColor
            )

            // Solid athletic head silhouette.
            context.fill(circlePath(at: head, radius: side * 0.058), with: .color(bodyColor))

            // Badminton racket shaft + oriented isometric oval head for on-court drills.
            if let tipPoint = figure.racketTip {
                let tip = pt(tipPoint)
                let dx = tip.x - wristR.x
                let dy = tip.y - wristR.y
                let len = hypot(dx, dy)
                if len > 1 {
                    let shaftW = max(1.5, side * 0.018)
                    let shaftEnd = CGPoint(x: wristR.x + dx * 0.54, y: wristR.y + dy * 0.54)
                    drawLimb(wristR, shaftEnd, width: shaftW, color: Shuttl.accent)

                    let headCenter = CGPoint(x: wristR.x + dx * 0.77, y: wristR.y + dy * 0.77)
                    let rx = len * 0.24
                    let ry = len * 0.145
                    context.drawLayer { layer in
                        layer.translateBy(x: headCenter.x, y: headCenter.y)
                        layer.rotate(by: Angle(radians: atan2(dy, dx)))
                        let oval = Path(ellipseIn: CGRect(x: -rx, y: -ry, width: rx * 2, height: ry * 2))
                        layer.fill(oval, with: .color(Shuttl.accent.opacity(0.16)))
                        layer.stroke(oval, with: .color(Shuttl.accent), lineWidth: shaftW)
                    }
                }
            }
        }
    }
}

private func circlePath(at center: CGPoint, radius: CGFloat) -> Path {
    Path(ellipseIn: CGRect(
        x: center.x - radius,
        y: center.y - radius,
        width: radius * 2,
        height: radius * 2
    ))
}
