import Shared
import SwiftUI

struct CourtMarkingRoute: Hashable {
    let entryId: String
}

/// Which of the two setup steps is on screen.
///
/// Android's `SetupStep`, and the same two: the court is marked, then what the
/// run should produce is chosen. Split rather than stacked because the mapping
/// step wants the whole screen for a video frame and the options step wants a
/// scrolling column.
private enum SetupStep {
    case mapping
    case options

    var title: String {
        switch self {
        case .mapping: return "Court mapping"
        case .options: return "What to analyze"
        }
    }
}

struct CourtMarkingView: View {
    let rally: RallyApp
    let analyze: AnalyzeCoordinator
    /// The on-device pipeline, or nil where only the cloud is offered.
    ///
    /// Optional rather than always present so a build with no ONNX graphs
    /// staged - CI's, and any checkout that has not run the export scripts -
    /// shows one button rather than a second that fails on the tap.
    let localAnalysis: LocalAnalysisRunner?
    let entryId: String
    @Environment(\.dismiss) private var dismiss
    @State private var step: SetupStep = .mapping
    @State private var courtFrame: CourtFrame? = nil
    /// Clips only by default, matching Android: it is the one metric every user
    /// came for, and pose roughly doubles the wait, so it is opted into rather
    /// than out of.
    @State private var metrics: Set<AnalysisMetric> = [.rallyClips]
    @State private var throughput: DeviceThroughput = DeviceThroughput(
        baseMsPerFrame: DeviceThroughput.companion.SEED_BASE_MS,
        poseMsPerFrame: DeviceThroughput.companion.SEED_POSE_MS,
        cutMsPerClipSecond: DeviceThroughput.companion.SEED_CUT_MS,
        measured: false
    )
    @State private var frame: UIImage? = nil
    @State private var marking: CourtMarkingState? = nil
    @State private var error: String? = nil
    @State private var scale: CGFloat = 1
    @State private var offset: CGSize = .zero
    @State private var lastPanTranslation: CGSize = .zero
    @GestureState private var magnifyFrom: CGFloat = 1

    var body: some View {
        VStack(spacing: 0) {
            if let error {
                ErrorBanner(message: error)
                Spacer()
            } else if step == .options, let courtFrame, let marking, marking.isComplete {
                optionsStep(courtFrame: courtFrame, marking: marking)
            } else if let frame, let marking {
                content(frame: frame, marking: marking)
            } else {
                Spacer()
                ProgressView()
                Spacer()
            }
        }
        .navigationTitle(step.title)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            // Back out of the options step to the mapping step rather than off
            // the screen, which is what Android's BackHandler does. Without it
            // the only way to change a mark after reaching the options is to
            // leave and start again.
            if step == .options {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Back") { step = .mapping }
                }
            }
        }
        .task {
            guard marking == nil, error == nil else { return }
            guard let entry = rally.localVideos.get(id: entryId) else {
                dismiss()
                return
            }
            do {
                let loaded = try await CourtFrameLoader.loadFirstFrame(relativePath: entry.uri)
                courtFrame = loaded
                frame = loaded.image
                marking = CourtMarkingState(
                    videoWidth: Int32(loaded.image.size.width * loaded.image.scale),
                    videoHeight: Int32(loaded.image.size.height * loaded.image.scale),
                    points: []
                )
                // What this phone has learned about itself, if anything. Read
                // here rather than in the selector so the selector stays a view
                // over values it is given.
                throughput = rally.deviceThroughput.throughput.value
            } catch {
                self.error = error.localizedDescription   // "Couldn't extract video frame"
            }
        }
    }

    @ViewBuilder
    private func content(frame: UIImage, marking: CourtMarkingState) -> some View {
        GeometryReader { geo in
            let aspect = CGFloat(truncating: marking.videoWidth as NSNumber)
                / CGFloat(truncating: marking.videoHeight as NSNumber)
            let displayW = min(geo.size.width, geo.size.height * aspect)
            let displayH = displayW / aspect
            ZStack(alignment: .topLeading) {
                frameCanvas(frame: frame, marking: marking, displaySize: CGSize(width: displayW, height: displayH))
                    .frame(width: displayW, height: displayH)
                    .scaleEffect(scale, anchor: .topLeading)
                    .offset(offset)
            }
            .frame(width: displayW, height: displayH)
            .clipped()
            .contentShape(Rectangle())
            .position(x: geo.size.width / 2, y: geo.size.height / 2)
            .gesture(tapGesture(displaySize: CGSize(width: displayW, height: displayH)))
            .simultaneousGesture(magnifyGesture())
            .simultaneousGesture(panGesture)
        }

        instructionRow(marking: marking)
        SchematicCourtGuide(placedCount: Int(marking.points.count))
            .padding(.vertical, 8)

        HStack(spacing: 12) {
            Button("Undo") { self.marking = marking.undo() }
                .disabled(marking.points.isEmpty)
            Button("Clear") { self.marking = marking.clear() }
                .disabled(marking.points.isEmpty)
        }
        .padding(.horizontal, 16)

        if marking.isComplete {
            Button("Continue") { step = .options }
                .buttonStyle(PrimaryButtonStyle())
                .padding(16)
        }
    }

    // MARK: - Step two: what the run should produce

    @ViewBuilder
    private func optionsStep(courtFrame: CourtFrame, marking: CourtMarkingState) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                Text("What to analyze")
                    .shuttlType(ShuttlType.headlineLarge)
                    .foregroundStyle(Shuttl.textHeading)
                    .padding(.top, 16)
                Text("Pick what this run should produce.")
                    .shuttlType(ShuttlType.bodySmall)
                    .foregroundStyle(Shuttl.textTertiary)
                    .padding(.top, 8)
                MetricSelector(
                    frames: courtFrame.frameCount,
                    fps: courtFrame.fps,
                    selected: metrics,
                    throughput: throughput,
                    onToggle: { metric in
                        if metrics.contains(metric) { metrics.remove(metric) } else { metrics.insert(metric) }
                    }
                )
                .padding(.top, 22)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16)
            .padding(.bottom, 16)
        }

        // Two buttons rather than one with a toggle: the point is to run the
        // same video through both pipelines back to back and compare, and a
        // toggle adds a step to every comparison.
        VStack(spacing: 10) {
            Button("Analyze in cloud") { start(onDevice: false, marking: marking) }
                .buttonStyle(PrimaryButtonStyle())
            if localAnalysis != nil {
                Button("Analyze on device") { start(onDevice: true, marking: marking) }
                    .buttonStyle(SecondaryPillButtonStyle())
                    .disabled(metrics.isEmpty)
            }
        }
        .padding(.horizontal, 16)
        .padding(.bottom, 16)
    }

    private func start(onDevice: Bool, marking: CourtMarkingState) {
        let keypoints = marking.toCourtKeypoints()
        if onDevice, let localAnalysis, let entry = rally.localVideos.get(id: entryId) {
            localAnalysis.start(
                entryId: entryId,
                videoPath: LocalVideoFiles.resolve(relativePath: entry.uri).path,
                keypoints: keypoints,
                metrics: metrics
            )
        } else {
            analyze.startAnalysis(entryId: entryId, keypoints: keypoints)
        }
        dismiss()
    }

    private func tapGesture(displaySize: CGSize) -> some Gesture {
        SpatialTapGesture().onEnded { value in
            guard let marking, !marking.isComplete else { return }
            let p = CourtTapMath.inverse(
                tapX: value.location.x, tapY: value.location.y,
                offsetX: offset.width, offsetY: offset.height, scale: scale
            )
            guard p.x >= 0, p.y >= 0, p.x <= displaySize.width, p.y <= displaySize.height else { return }
            self.marking = marking.place(
                displayX: Float(p.x), displayY: Float(p.y),
                displayWidth: Float(displaySize.width), displayHeight: Float(displaySize.height)
            )
        }
    }

    private func magnifyGesture() -> some Gesture {
        MagnifyGesture()
            .onChanged { value in
                let zoom = value.magnification / magnifyFrom
                let newScale = min(max(scale * zoom, 1), 6)
                let effectiveZoom = newScale / scale
                let centroid = value.startLocation
                offset = CGSize(
                    width: centroid.x - (centroid.x - offset.width) * effectiveZoom,
                    height: centroid.y - (centroid.y - offset.height) * effectiveZoom
                )
                scale = newScale
                if scale == 1 { offset = .zero }
            }
            .updating($magnifyFrom) { value, state, _ in state = value.magnification }
    }

    private var panGesture: some Gesture {
        DragGesture(minimumDistance: 8)
            .onChanged { value in
                guard scale > 1 else { return }
                let delta = CGSize(
                    width: value.translation.width - lastPanTranslation.width,
                    height: value.translation.height - lastPanTranslation.height
                )
                lastPanTranslation = value.translation
                offset = CGSize(width: offset.width + delta.width, height: offset.height + delta.height)
            }
            .onEnded { _ in
                lastPanTranslation = .zero
            }
    }

    private func instructionRow(marking: CourtMarkingState) -> some View {
        HStack {
            if marking.isComplete {
                Text("All points placed")
                    .font(.body.weight(.medium))
                    .foregroundStyle(Shuttl.accent)
            } else {
                Text("Tap: \(CourtMarkingSpec.shared.fullLabels[Int(marking.nextIndex)])")
                    .font(.body.weight(.medium))
                    .foregroundStyle(specColor(Int(marking.nextIndex)))
            }
            Spacer()
            Text("\(marking.points.count) / \(CourtMarkingSpec.shared.TOTAL) points")
                .font(.footnote)
                .foregroundStyle(Shuttl.textSecondary)
        }
        .padding(.horizontal, 16)
        .padding(.top, 8)
    }

    @ViewBuilder
    private func frameCanvas(frame: UIImage, marking: CourtMarkingState, displaySize: CGSize) -> some View {
        ZStack(alignment: .topLeading) {
            Image(uiImage: frame)
                .resizable()
                .frame(width: displaySize.width, height: displaySize.height)
            Canvas { context, size in
                drawCourtGuide(context: &context, size: size)
                drawCornerRectangle(context: &context, size: size, marking: marking)
                drawPlacedPoints(context: &context, size: size, marking: marking)
            }
        }
    }

    private func drawCourtGuide(context: inout GraphicsContext, size: CGSize) {
        let guideGreen = Shuttl.accent.opacity(0.2)
        let connectGreen = Shuttl.accent.opacity(0.6)
        let margin: CGFloat = 0.15
        let x1 = size.width * margin, x2 = size.width * (1 - margin)
        let y1 = size.height * margin, y2 = size.height * (1 - margin)
        let dash = StrokeStyle(lineWidth: 1, dash: [4, 4])
        var rect = Path(); rect.addRect(CGRect(x: x1, y: y1, width: x2 - x1, height: y2 - y1))
        context.stroke(rect, with: .color(guideGreen), style: dash)
        let netY = size.height / 2
        var net = Path(); net.move(to: CGPoint(x: x1, y: netY)); net.addLine(to: CGPoint(x: x2, y: netY))
        context.stroke(net, with: .color(connectGreen), style: dash)
        let serviceY1 = y1 + (netY - y1) * 0.6
        let serviceY2 = y2 - (y2 - netY) * 0.6
        for sy in [serviceY1, serviceY2] {
            var line = Path(); line.move(to: CGPoint(x: x1, y: sy)); line.addLine(to: CGPoint(x: x2, y: sy))
            context.stroke(line, with: .color(guideGreen), style: dash)
        }
        var center = Path()
        center.move(to: CGPoint(x: size.width / 2, y: serviceY1))
        center.addLine(to: CGPoint(x: size.width / 2, y: serviceY2))
        context.stroke(center, with: .color(guideGreen), style: dash)
    }

    private func drawCornerRectangle(context: inout GraphicsContext, size: CGSize, marking: CourtMarkingState) {
        guard marking.points.count >= 4 else { return }
        let fx = size.width / CGFloat(truncating: marking.videoWidth as NSNumber)
        let fy = size.height / CGFloat(truncating: marking.videoHeight as NSNumber)
        var path = Path()
        for i in 0..<4 {
            let p = marking.points[i]
            let dp = CGPoint(x: CGFloat(p.x) * fx, y: CGFloat(p.y) * fy)
            if i == 0 { path.move(to: dp) } else { path.addLine(to: dp) }
        }
        path.closeSubpath()
        context.stroke(path, with: .color(Shuttl.accent.opacity(0.6)), style: StrokeStyle(lineWidth: 2, dash: [8, 4]))
    }

    private func drawPlacedPoints(context: inout GraphicsContext, size: CGSize, marking: CourtMarkingState) {
        let fx = size.width / CGFloat(truncating: marking.videoWidth as NSNumber)
        let fy = size.height / CGFloat(truncating: marking.videoHeight as NSNumber)
        let radius = 10 / scale
        for (i, p) in marking.points.enumerated() {
            let dp = CGPoint(x: CGFloat(p.x) * fx, y: CGFloat(p.y) * fy)
            let circle = Path(ellipseIn: CGRect(x: dp.x - radius, y: dp.y - radius, width: radius * 2, height: radius * 2))
            context.fill(circle, with: .color(specColor(i)))
            context.stroke(circle, with: .color(.black), lineWidth: 2 / scale)
            context.draw(
                Text(CourtMarkingSpec.shared.shortLabels[i])
                    .font(.system(size: 9 / scale, weight: .bold))
                    .foregroundColor(.black),
                at: dp
            )
        }
    }
}

/// Spec colors are 0xAARRGGBB Longs from shared CourtMarkingSpec.
func specColor(_ index: Int) -> Color {
    let raw = CourtMarkingSpec.shared.colors[index]
    let rgb = UInt32(truncating: raw) & 0x00FFFFFF
    return Color(rgb: rgb)
}
