import Shared
import SwiftUI

private let doublesBackServiceInsetM: CGFloat = 0.76

/// Stylized animated drill visual rendered inside an expanded `ExerciseCard`.
///
/// Port of androidApp's `ExerciseVisualView`: forward-kinematics pose sampling,
/// tapered vector athlete silhouettes, live shuttle flight, racket swing swooshes,
/// curved half-court movement with synced shuttle and arrival pulses, plus
/// slow-motion (0.5x) and phase-step controls.
struct ExerciseVisualView: View {
    let exerciseId: String
    let racketArm: RacketArm?

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var paused = false
    @State private var slowMo = false
    @State private var stepIndex = 1
    @State private var anchorDate = Date()
    @State private var anchorElapsed: TimeInterval = 0

    var body: some View {
        let visual = ExerciseVisualsKt.visualFor(exerciseId: exerciseId)
        let mirrorX = racketArm == .left
        let frameCount = max(1, visual.pose.keyframes.count)
        let poseCycle = max(0.4, visual.pose.cycleSeconds)

        TimelineView(.animation(paused: reduceMotion || paused)) { timeline in
            let speed = slowMo ? 0.5 : 1.0
            let elapsed = paused
                ? anchorElapsed
                : anchorElapsed + max(0, timeline.date.timeIntervalSince(anchorDate)) * speed
            let posePhase: Float = reduceMotion
                ? (Float(stepIndex % frameCount) + 0.08) / Float(frameCount)
                : phase01(elapsed: elapsed, cycleSeconds: poseCycle)
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
            let courtState = visual.courtPath.map {
                ExerciseVisualsKt.sampleCourtState(path: $0, phase01: courtPhase, mirrorX: mirrorX)
            }
            let summary = visual.courtPath != nil
                ? "\(ExercisesCopy.shared.COURT_VISUAL_CAPTION) and \(ExercisesCopy.shared.poseCaption(phaseLabel: sampledPose.label))"
                : ExercisesCopy.shared.poseCaption(phaseLabel: sampledPose.label)

            VStack(alignment: .leading, spacing: 8) {
                Button {
                    togglePlayback(frameCount: frameCount)
                } label: {
                    if let path = visual.courtPath, let courtState {
                        HStack(spacing: 8) {
                            VisualTile(
                                caption: ExercisesCopy.shared.COURT_VISUAL_CAPTION,
                                captionColor: Shuttl.textSecondary,
                                tileHeight: 152
                            ) {
                                HalfCourtCanvas(path: path, state: courtState, mirrorX: mirrorX)
                            }
                            VisualTile(
                                caption: ExercisesCopy.shared.poseCaption(phaseLabel: sampledPose.label),
                                captionColor: Shuttl.accentDark,
                                tileHeight: 152
                            ) {
                                PoseFigureCanvas(sampled: sampledPose)
                            }
                        }
                    } else {
                        VisualTile(
                            caption: ExercisesCopy.shared.poseCaption(phaseLabel: sampledPose.label),
                            captionColor: Shuttl.accentDark,
                            tileHeight: 164
                        ) {
                            PoseFigureCanvas(sampled: sampledPose)
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

                // Compact phase-scrubber and slow-motion playback strip.
                HStack(alignment: .center) {
                    HStack(spacing: 6) {
                        ForEach(0..<Int(sampledPose.keyframeCount), id: \.self) { idx in
                            let active = idx == Int(sampledPose.keyframeIndex)
                            Button {
                                if reduceMotion {
                                    stepIndex = idx
                                } else {
                                    anchorElapsed = (Double(idx) + 0.08) / Double(frameCount) * poseCycle
                                    anchorDate = Date()
                                    paused = true
                                }
                            } label: {
                                Capsule()
                                    .fill(active ? Shuttl.accent : Shuttl.borderSecondary)
                                    .frame(width: active ? 18 : 7, height: 6)
                            }
                            .buttonStyle(.plain)
                            .accessibilityLabel("Pose \(idx + 1) of \(sampledPose.keyframeCount)")
                        }
                    }

                    Spacer(minLength: 8)

                    HStack(spacing: 6) {
                        if !reduceMotion {
                            ControlPill(
                                label: slowMo ? ExercisesCopy.shared.SPEED_SLOW : ExercisesCopy.shared.SPEED_NORMAL,
                                highlighted: slowMo
                            ) {
                                let now = Date()
                                if !paused {
                                    anchorElapsed += max(0, now.timeIntervalSince(anchorDate)) * (slowMo ? 0.5 : 1.0)
                                    anchorDate = now
                                }
                                slowMo.toggle()
                            }
                        }
                        ControlPill(
                            label: reduceMotion ? "Next" : (paused ? "Play" : "Pause"),
                            highlighted: false
                        ) {
                            togglePlayback(frameCount: frameCount)
                        }
                    }
                }
            }
        }
    }

    private func togglePlayback(frameCount: Int) {
        if reduceMotion {
            stepIndex = (stepIndex + 1) % frameCount
        } else if paused {
            anchorDate = Date()
            paused = false
        } else {
            let now = Date()
            anchorElapsed += max(0, now.timeIntervalSince(anchorDate)) * (slowMo ? 0.5 : 1.0)
            anchorDate = now
            paused = true
        }
    }

    private func phase01(elapsed: TimeInterval, cycleSeconds: Double) -> Float {
        let cycle = max(0.4, cycleSeconds)
        return Float((elapsed / cycle).truncatingRemainder(dividingBy: 1.0))
    }
}

private struct ControlPill: View {
    let label: String
    let highlighted: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(label)
                .shuttlType(ShuttlType.labelSmall)
                .foregroundStyle(highlighted ? Shuttl.onAccent : Shuttl.textSecondary)
                .padding(.horizontal, 9)
                .padding(.vertical, 3)
                .background(highlighted ? Shuttl.accent : Shuttl.bg, in: Capsule())
                .overlay(
                    Capsule()
                        .stroke(highlighted ? Shuttl.accent : Shuttl.border, lineWidth: 1)
                )
        }
        .buttonStyle(.plain)
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
    let state: SampledCourtState
    let mirrorX: Bool

    var body: some View {
        let waypoints = ExerciseVisualsKt.mirroredCourtPoints(points: path.waypoints, mirrorX: mirrorX)
        let baseMarker = ExerciseVisualsKt.mirroredCourtPoint(point: path.baseMarker, mirrorX: mirrorX)

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

            // Subtle court floor tint with slightly stronger singles floor band.
            let courtRect = CGRect(x: left, y: top, width: drawW, height: drawH)
            context.fill(Path(courtRect), with: .color(Shuttl.accent.opacity(0.05)))
            let singlesRect = CGRect(
                x: left + singlesInset * scale,
                y: top,
                width: (courtW - singlesInset * 2) * scale,
                height: drawH
            )
            context.fill(Path(singlesRect), with: .color(Shuttl.accent.opacity(0.04)))

            let lineShading = GraphicsContext.Shading.color(Shuttl.textSecondary.opacity(0.42))
            context.stroke(Path(courtRect), with: lineShading, lineWidth: 1)

            func strokeLine(
                _ a: CGPoint,
                _ b: CGPoint,
                shading: GraphicsContext.Shading = lineShading,
                width: CGFloat = 1
            ) {
                var p = Path()
                p.move(to: a)
                p.addLine(to: b)
                context.stroke(p, with: shading, style: StrokeStyle(lineWidth: width, lineCap: .round))
            }

            strokeLine(toCanvas(singlesInset, 0), toCanvas(singlesInset, courtH))
            strokeLine(toCanvas(courtW - singlesInset, 0), toCanvas(courtW - singlesInset, courtH))
            strokeLine(toCanvas(0, serviceY), toCanvas(courtW, serviceY))
            strokeLine(toCanvas(0, doublesBackY), toCanvas(courtW, doublesBackY))
            strokeLine(toCanvas(centreX, serviceY), toCanvas(centreX, courtH))

            // Net tape across top edge with small post overhangs.
            strokeLine(
                toCanvas(-0.22, 0),
                toCanvas(courtW + 0.22, 0),
                shading: .color(Shuttl.textHeading.opacity(0.72)),
                width: 2.2
            )

            // Curved dashed movement pattern + directional chevrons.
            let smooth = state.smoothRoute
            if smooth.count >= 2 {
                var route = Path()
                route.move(to: toCanvas(smooth[0]))
                for pt in smooth.dropFirst() {
                    route.addLine(to: toCanvas(pt))
                }
                context.stroke(
                    route,
                    with: .color(Shuttl.accent.opacity(0.38)),
                    style: StrokeStyle(lineWidth: 1.4, lineCap: .round, lineJoin: .round, dash: [4, 3])
                )
                var idx = 3
                while idx < smooth.count - 1 {
                    let prev = toCanvas(smooth[idx - 1])
                    let cur = toCanvas(smooth[idx])
                    drawChevron(
                        context: &context,
                        tip: cur,
                        angleRad: atan2(cur.y - prev.y, cur.x - prev.x),
                        size: 3.4,
                        color: Shuttl.accent.opacity(0.45)
                    )
                    idx += 6
                }
            }

            // Corner target dots away from base marker.
            for wp in waypoints {
                if hypot(CGFloat(wp.x - baseMarker.x), CGFloat(wp.y - baseMarker.y)) > 0.35 {
                    context.fill(
                        circlePath(at: toCanvas(wp), radius: 2.8),
                        with: .color(Shuttl.accent.opacity(0.30))
                    )
                }
            }

            // Expanding corner arrival pulse ring.
            if let cp = state.cornerPulsePoint, state.cornerPulse01 > 0.02 {
                let expand = CGFloat(1.0 - state.cornerPulse01)
                context.stroke(
                    circlePath(at: toCanvas(cp), radius: 3.5 + expand * 7.0),
                    with: .color(Shuttl.accent.opacity(0.42 * Double(state.cornerPulse01))),
                    lineWidth: 1.4
                )
            }

            // Base marker ring + expanding arrival pulse.
            let baseCenter = toCanvas(baseMarker)
            if state.basePulse01 > 0.02 {
                let expand = CGFloat(1.0 - state.basePulse01)
                context.stroke(
                    circlePath(at: baseCenter, radius: 6.0 + expand * 7.0),
                    with: .color(Shuttl.accent.opacity(0.38 * Double(state.basePulse01))),
                    lineWidth: 1.4
                )
            }
            context.stroke(
                circlePath(at: baseCenter, radius: 5.8),
                with: .color(Shuttl.accent),
                lineWidth: 1.75
            )

            // Fading comet trail behind moving player dot.
            let trailCount = state.playerTrail.count
            for (idx, tp) in state.playerTrail.enumerated() {
                let frac = CGFloat(idx + 1) / CGFloat(trailCount + 1)
                context.fill(
                    circlePath(at: toCanvas(tp), radius: 1.6 + frac * 2.2),
                    with: .color(Shuttl.accent.opacity(0.06 + Double(frac) * 0.28))
                )
            }

            // Synchronized live shuttle marker flying on the half-court.
            if let sp = state.shuttlePosition {
                let sc = toCanvas(sp)
                context.fill(circlePath(at: sc, radius: 4.6), with: .color(Shuttl.textHeading.opacity(0.20)))
                context.fill(circlePath(at: sc, radius: 2.4), with: .color(Shuttl.textHeading))
            }

            // Animated player marker with soft halo and crisp core.
            let dotCenter = toCanvas(state.playerPosition)
            context.fill(circlePath(at: dotCenter, radius: 7.8), with: .color(Shuttl.accent.opacity(0.22)))
            context.fill(circlePath(at: dotCenter, radius: 4.4), with: .color(Shuttl.accent))
            context.fill(circlePath(at: dotCenter, radius: 1.7), with: .color(Shuttl.bg))
        }
    }
}

private struct PoseFigureCanvas: View {
    let sampled: SampledPose

    var body: some View {
        let figure = sampled.figure

        Canvas { context, size in
            let pad: CGFloat = 8
            let side = max(1, min(size.width - pad * 2, size.height - pad * 2))
            let originX = (size.width - side) / 2.0
            let originY = (size.height - side) / 2.0

            func pt(_ p: VisualPoint) -> CGPoint {
                CGPoint(x: originX + CGFloat(p.x) * side, y: originY + CGFloat(p.y) * side)
            }

            let headingColor = Shuttl.textHeading
            let frontLimbColor = headingColor.opacity(0.90)
            let backLimbColor = headingColor.opacity(0.42)
            let shortsColor = headingColor.opacity(0.86)
            let jerseyColor = Shuttl.accentDark.opacity(0.90)
            let jerseyHighlight = Shuttl.accent.opacity(0.35)
            let facingSign = CGFloat(sampled.facingSign)

            // Floor line + dynamic height-responsive contact shadow.
            let groundY = originY + CGFloat(ExerciseVisualsKt.POSE_GROUND_Y) * side
            var floorLine = Path()
            floorLine.move(to: CGPoint(x: originX + 0.06 * side, y: groundY))
            floorLine.addLine(to: CGPoint(x: originX + 0.94 * side, y: groundY))
            context.stroke(floorLine, with: .color(Shuttl.border), style: StrokeStyle(lineWidth: 1.25, lineCap: .round))

            let ankleL = pt(figure.ankleL)
            let ankleR = pt(figure.ankleR)
            let air = CGFloat(sampled.airborneFactor)
            let shadowScale = 1.0 - air * 0.45
            let shadowAlpha = max(0.04, 0.18 * (1.0 - Double(air) * 0.65))
            let rawLeft = min(ankleL.x, ankleR.x) - side * 0.07
            let rawRight = max(ankleL.x, ankleR.x) + side * 0.07
            let shadowCenter = (rawLeft + rawRight) * 0.5
            let shadowW = max(side * 0.20, rawRight - rawLeft) * shadowScale
            let shadowH = side * 0.032 * shadowScale
            let shadowRect = CGRect(
                x: shadowCenter - shadowW * 0.5,
                y: groundY - shadowH * 0.5,
                width: shadowW,
                height: shadowH
            )
            context.fill(Path(ellipseIn: shadowRect), with: .color(Shuttl.textSecondary.opacity(shadowAlpha)))

            // Expanding ground ripple on split-step or lunge footplant.
            if let ripple = sampled.floorRipple {
                let p = CGFloat(ripple.progress01)
                let rx = side * (0.04 + p * 0.14)
                let ry = side * (0.012 + p * 0.022)
                let cx = originX + CGFloat(ripple.centerX) * side
                let rect = CGRect(x: cx - rx, y: groundY - ry, width: rx * 2, height: ry * 2)
                context.stroke(
                    Path(ellipseIn: rect),
                    with: .color(Shuttl.accent.opacity(0.45 * Double(1.0 - p))),
                    lineWidth: 1.4
                )
            }

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

            let thighTop = side * 0.030
            let kneeRad = side * 0.021
            let ankleRad = side * 0.013
            let upperArmTop = side * 0.021
            let elbowRad = side * 0.016
            let wristRad = side * 0.012

            // 1. Back/non-racket leg and arm behind torso.
            drawTaperedLimb(&context, hipL, kneeL, thighTop, kneeRad, backLimbColor)
            drawTaperedLimb(&context, kneeL, ankleL, kneeRad * 0.96, ankleRad, backLimbColor)
            drawShoe(&context, ankle: ankleL, knee: kneeL, facingSign: facingSign, side: side, color: backLimbColor, accentSole: nil)
            drawTaperedLimb(&context, shoulderL, elbowL, upperArmTop, elbowRad, backLimbColor)
            drawTaperedLimb(&context, elbowL, wristL, elbowRad * 0.94, wristRad, backLimbColor)

            // 2. Athletic shorts pelvis band.
            let dirL = unitToward(hipL, kneeL)
            let dirR = unitToward(hipR, kneeR)
            let shortsL = CGPoint(x: hipL.x + dirL.x * side * 0.065, y: hipL.y + dirL.y * side * 0.065)
            let shortsR = CGPoint(x: hipR.x + dirR.x * side * 0.065, y: hipR.y + dirR.y * side * 0.065)
            var shorts = Path()
            shorts.move(to: hipL)
            shorts.addLine(to: hipR)
            shorts.addLine(to: shortsR)
            shorts.addLine(to: shortsL)
            shorts.closeSubpath()
            context.fill(shorts, with: .color(shortsColor))
            drawTaperedLimb(&context, hipL, hipR, side * 0.026, side * 0.026, shortsColor)

            // 3. Tailored athletic jersey torso.
            let shDx = shoulderR.x - shoulderL.x
            let shDy = shoulderR.y - shoulderL.y
            let shLen = max(1, hypot(shDx, shDy))
            let shExpand = side * 0.018
            let shOuterL = CGPoint(x: shoulderL.x - shDx / shLen * shExpand, y: shoulderL.y - shDy / shLen * shExpand)
            let shOuterR = CGPoint(x: shoulderR.x + shDx / shLen * shExpand, y: shoulderR.y + shDy / shLen * shExpand)
            let midL = CGPoint(x: (shOuterL.x + hipL.x) * 0.5, y: (shOuterL.y + hipL.y) * 0.5)
            let midR = CGPoint(x: (shOuterR.x + hipR.x) * 0.5, y: (shOuterR.y + hipR.y) * 0.5)
            var jersey = Path()
            jersey.move(to: shOuterL)
            jersey.addQuadCurve(to: shOuterR, control: CGPoint(x: neck.x, y: neck.y - side * 0.012))
            jersey.addQuadCurve(to: hipR, control: midR)
            jersey.addLine(to: hipL)
            jersey.addQuadCurve(to: shOuterL, control: midL)
            jersey.closeSubpath()
            context.fill(jersey, with: .color(jerseyColor))
            context.stroke(jersey, with: .color(jerseyHighlight), lineWidth: 1)

            // 4. Neck and athletic head silhouette with subtle facing contour.
            drawTaperedLimb(&context, neck, head, side * 0.018, side * 0.016, frontLimbColor)
            let headRad = side * 0.056
            context.fill(circlePath(at: head, radius: headRad), with: .color(headingColor))
            context.fill(
                circlePath(
                    at: CGPoint(x: head.x + facingSign * headRad * 0.58, y: head.y - headRad * 0.08),
                    radius: headRad * 0.22
                ),
                with: .color(Shuttl.accent.opacity(0.65))
            )

            // 5. Foreground/racket-side leg, shoe, and arm.
            drawTaperedLimb(&context, hipR, kneeR, thighTop, kneeRad, frontLimbColor)
            drawTaperedLimb(&context, kneeR, ankleR, kneeRad * 0.96, ankleRad, frontLimbColor)
            drawShoe(&context, ankle: ankleR, knee: kneeR, facingSign: facingSign, side: side, color: headingColor, accentSole: Shuttl.accent)
            drawTaperedLimb(&context, shoulderR, elbowR, upperArmTop, elbowRad, frontLimbColor)
            drawTaperedLimb(&context, elbowR, wristR, elbowRad * 0.94, wristRad, frontLimbColor)

            // 6. Motion-trail swoosh ribbon behind fast racket swings.
            let trail = sampled.racketTrail
            if trail.count >= 2 {
                for i in 1..<trail.count {
                    let frac = CGFloat(i) / CGFloat(trail.count - 1)
                    var seg = Path()
                    seg.move(to: pt(trail[i - 1]))
                    seg.addLine(to: pt(trail[i]))
                    context.stroke(
                        seg,
                        with: .color(Shuttl.accent.opacity(0.04 + Double(frac) * 0.34)),
                        style: StrokeStyle(lineWidth: 1.2 + frac * 4.5, lineCap: .round)
                    )
                }
            }

            // 7. Detailed badminton racket (grip, shaft, oval frame + string grid).
            if let tipPoint = figure.racketTip {
                let tip = pt(tipPoint)
                let dx = tip.x - wristR.x
                let dy = tip.y - wristR.y
                let len = hypot(dx, dy)
                if len > 1 {
                    let shaftW = max(1.4, side * 0.015)
                    let gripEnd = CGPoint(x: wristR.x + dx * 0.16, y: wristR.y + dy * 0.16)
                    let throatBase = CGPoint(x: wristR.x + dx * 0.52, y: wristR.y + dy * 0.52)

                    var grip = Path()
                    grip.move(to: wristR)
                    grip.addLine(to: gripEnd)
                    context.stroke(grip, with: .color(headingColor), style: StrokeStyle(lineWidth: shaftW * 1.65, lineCap: .round))

                    var shaft = Path()
                    shaft.move(to: gripEnd)
                    shaft.addLine(to: throatBase)
                    context.stroke(shaft, with: .color(Shuttl.accent), style: StrokeStyle(lineWidth: shaftW, lineCap: .round))

                    let headCenter = CGPoint(x: wristR.x + dx * 0.77, y: wristR.y + dy * 0.77)
                    let rx = len * 0.24
                    let ry = len * 0.145
                    context.drawLayer { layer in
                        layer.translateBy(x: headCenter.x, y: headCenter.y)
                        layer.rotate(by: Angle(radians: atan2(dy, dx)))
                        let oval = Path(ellipseIn: CGRect(x: -rx, y: -ry, width: rx * 2, height: ry * 2))
                        layer.fill(oval, with: .color(Shuttl.accent.opacity(0.14)))

                        // Subtle string grid inside the isometric racket head.
                        let stringShading = GraphicsContext.Shading.color(Shuttl.accent.opacity(0.34))
                        var strings = Path()
                        strings.move(to: CGPoint(x: -rx * 0.82, y: -ry * 0.36))
                        strings.addLine(to: CGPoint(x: rx * 0.82, y: -ry * 0.36))
                        strings.move(to: CGPoint(x: -rx * 0.82, y: ry * 0.36))
                        strings.addLine(to: CGPoint(x: rx * 0.82, y: ry * 0.36))
                        for step in -1...1 {
                            let sx = rx * 0.42 * CGFloat(step)
                            let sy = ry * (step == 0 ? 0.90 : 0.78)
                            strings.move(to: CGPoint(x: sx, y: -sy))
                            strings.addLine(to: CGPoint(x: sx, y: sy))
                        }
                        layer.stroke(strings, with: stringShading, lineWidth: 0.75)
                        layer.stroke(oval, with: .color(Shuttl.accent), lineWidth: shaftW * 1.08)
                    }
                }
            }

            // 8. Live animated shuttlecock + contact flash ring.
            if let shuttle = sampled.shuttle {
                drawPoseShuttle(
                    context: &context,
                    shuttle: shuttle,
                    center: pt(shuttle.position),
                    side: side,
                    headingColor: headingColor
                )
            }
        }
    }
}

private func drawTaperedLimb(
    _ context: inout GraphicsContext,
    _ start: CGPoint,
    _ end: CGPoint,
    _ startRadius: CGFloat,
    _ endRadius: CGFloat,
    _ color: Color
) {
    context.fill(circlePath(at: start, radius: startRadius), with: .color(color))
    context.fill(circlePath(at: end, radius: endRadius), with: .color(color))
    let dx = end.x - start.x
    let dy = end.y - start.y
    let len = hypot(dx, dy)
    guard len >= 0.5 else { return }
    let nx = -dy / len
    let ny = dx / len
    var path = Path()
    path.move(to: CGPoint(x: start.x + nx * startRadius, y: start.y + ny * startRadius))
    path.addLine(to: CGPoint(x: end.x + nx * endRadius, y: end.y + ny * endRadius))
    path.addLine(to: CGPoint(x: end.x - nx * endRadius, y: end.y - ny * endRadius))
    path.addLine(to: CGPoint(x: start.x - nx * startRadius, y: start.y - ny * startRadius))
    path.closeSubpath()
    context.fill(path, with: .color(color))
}

private func drawShoe(
    _ context: inout GraphicsContext,
    ankle: CGPoint,
    knee: CGPoint,
    facingSign: CGFloat,
    side: CGFloat,
    color: Color,
    accentSole: Color?
) {
    let dir: CGFloat = {
        if ankle.x - knee.x > side * 0.05 { return 1 }
        if ankle.x - knee.x < -side * 0.05 { return -1 }
        return facingSign
    }()
    let shoeW = side * 0.068
    let shoeH = side * 0.026
    let left = dir >= 0 ? ankle.x - shoeW * 0.25 : ankle.x - shoeW * 0.75
    let top = ankle.y - shoeH * 0.55
    let rect = CGRect(x: left, y: top, width: shoeW, height: shoeH)
    context.fill(Path(roundedRect: rect, cornerRadius: shoeH * 0.5), with: .color(color))
    if let accentSole {
        var sole = Path()
        sole.move(to: CGPoint(x: left + shoeW * 0.14, y: top + shoeH * 0.82))
        sole.addLine(to: CGPoint(x: left + shoeW * 0.86, y: top + shoeH * 0.82))
        context.stroke(sole, with: .color(accentSole), style: StrokeStyle(lineWidth: 1.4, lineCap: .round))
    }
}

private func drawPoseShuttle(
    context: inout GraphicsContext,
    shuttle: PoseShuttle,
    center: CGPoint,
    side: CGFloat,
    headingColor: Color
) {
    if shuttle.impactFlash > 0.02 {
        let flash = CGFloat(shuttle.impactFlash)
        let ringR = side * (0.028 + (1.0 - flash) * 0.055)
        context.stroke(
            circlePath(at: center, radius: ringR),
            with: .color(Shuttl.accent.opacity(0.52 * Double(flash))),
            lineWidth: 1.6
        )
    }
    let alpha = Double(shuttle.alpha)
    let corkR = max(2.2, side * 0.017)
    let skirtLen = corkR * 3.1
    let skirtHalfW = corkR * 1.45
    context.drawLayer { layer in
        layer.translateBy(x: center.x, y: center.y)
        layer.rotate(by: Angle(radians: Double(shuttle.velocityAngleRad)))
        var skirt = Path()
        skirt.move(to: .zero)
        skirt.addLine(to: CGPoint(x: -skirtLen, y: -skirtHalfW))
        skirt.addLine(to: CGPoint(x: -skirtLen * 0.86, y: 0))
        skirt.addLine(to: CGPoint(x: -skirtLen, y: skirtHalfW))
        skirt.closeSubpath()
        layer.fill(skirt, with: .color(Shuttl.accent.opacity(0.28 * alpha)))
        layer.stroke(
            skirt,
            with: .color(headingColor.opacity(0.78 * alpha)),
            style: StrokeStyle(lineWidth: 1, lineJoin: .round)
        )
        layer.fill(
            circlePath(at: CGPoint(x: corkR * 0.25, y: 0), radius: corkR),
            with: .color(headingColor.opacity(alpha))
        )
    }
}

private func drawChevron(
    context: inout GraphicsContext,
    tip: CGPoint,
    angleRad: CGFloat,
    size: CGFloat,
    color: Color
) {
    let spread: CGFloat = 0.58
    var p = Path()
    p.move(to: CGPoint(x: tip.x - cos(angleRad - spread) * size, y: tip.y - sin(angleRad - spread) * size))
    p.addLine(to: tip)
    p.addLine(to: CGPoint(x: tip.x - cos(angleRad + spread) * size, y: tip.y - sin(angleRad + spread) * size))
    context.stroke(p, with: .color(color), style: StrokeStyle(lineWidth: 1.25, lineCap: .round, lineJoin: .round))
}

private func unitToward(_ from: CGPoint, _ to: CGPoint) -> CGPoint {
    let dx = to.x - from.x
    let dy = to.y - from.y
    let len = hypot(dx, dy)
    return len < 0.001 ? CGPoint(x: 0, y: 1) : CGPoint(x: dx / len, y: dy / len)
}

private func circlePath(at center: CGPoint, radius: CGFloat) -> Path {
    Path(ellipseIn: CGRect(
        x: center.x - radius,
        y: center.y - radius,
        width: radius * 2,
        height: radius * 2
    ))
}
