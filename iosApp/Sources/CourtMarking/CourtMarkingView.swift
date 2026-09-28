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
    /// Whether the marks on screen are the ones the last run used, untouched.
    ///
    /// Set when the screen opens on them and cleared by the first edit: Undo,
    /// Clear, moving a point or Find court. Picking a point up to move it is
    /// not an edit yet. androidApp keeps the same flag in `CourtMarkingUiState`.
    @State private var showsSavedMarks = false
    /// Where the court finder stands, which the instruction line and the note
    /// say. androidApp keeps the same in `CourtMarkingUiState.status`, and the
    /// functions below mirror its `CourtMarkingViewModel` one for one.
    @State private var finderStatus: CourtFinderStatus = .idle
    /// The finder call in flight, if any. An edit by the coach cancels it: his
    /// hand wins over an answer about a marking he has since changed, and the
    /// busy line would otherwise stand in for the instruction he needs while
    /// he taps. The search itself cannot be stopped, so every answer checks
    /// for the cancel before it touches the screen.
    @State private var finderTask: Task<Void, Never>? = nil
    @State private var error: String? = nil
    /// Whether the "what each run produces" sheet is up.
    @State private var showCapabilities = false
    @State private var scale: CGFloat = 1
    @State private var offset: CGSize = .zero
    @State private var lastPanTranslation: CGSize = .zero
    @GestureState private var magnifyFrom: CGFloat = 1

    var body: some View {
        VStack(spacing: 0) {
            if let error {
                // At the page gutter, as androidApp's CourtMarkingScreen puts it.
                ErrorBanner(message: error)
                    .padding(ShuttlGutter.page)
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
        // Without this the bar is transparent and whatever is at the top of the
        // VStack runs up behind the title and the status bar. Seen on the
        // simulator with the error banner: a video whose file has gone paints
        // the whole top of the screen red, "Court mapping" included.
        .shuttlNavigationBarBackground()
        // The system back button REPLACED on the options step, not joined by a
        // second one. Adding a leading item leaves the navigation chevron in
        // place beside it, and the two then do different things - one returns to
        // the marks, the other leaves court marking entirely - which is two back
        // buttons on one screen doing two things.
        .navigationBarBackButtonHidden(step == .options)
        .toolbar {
            // Back out of the options step to the mapping step rather than off
            // the screen, which is what Android's BackHandler does. Without it
            // the only way to change a mark after reaching the options is to
            // leave and start again.
            if step == .options {
                ToolbarItem(placement: .topBarLeading) {
                    Button {
                        step = .mapping
                    } label: {
                        Image(systemName: "chevron.left")
                    }
                    .accessibilityLabel("Back to court mapping")
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
                // Opened on the marks this entry was analysed with last time,
                // when it has any. They are persisted before either pipeline
                // starts and were read back by nothing, so a second run over a
                // video - cloud after device, a different metric set, a retry
                // that falls through to here - meant placing all twelve again.
                let restored = loaded.marking(restoring: entry.keypoints)
                marking = restored
                showsSavedMarks = restored.isComplete
                // What this phone has learned about itself, if anything. Read
                // here rather than in the selector so the selector stays a view
                // over values it is given.
                throughput = rally.deviceThroughput.throughput.value
                // Saved marks are what the coach used last time: opening on
                // them is a review, and the finder stays out of it unless he
                // asks (Find court).
                if restored.points.isEmpty { findOnOpening() }
            } catch {
                self.error = error.localizedDescription   // "Couldn't extract video frame"
            }
        }
        .onDisappear { cancelFinder() }
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
            // Every gesture goes on the FRAMED view, above `.position`, and the
            // order is the whole point. `.position` returns a view that fills
            // its parent and draws the child at a point inside it, so a gesture
            // attached after it reports locations in the GEOMETRYREADER's space
            // while `place` reads them as the frame's. The court is 16:9 in a
            // tall column, so that space is about twice as tall as the picture:
            // every mark landed the letterbox inset too low, and a tap below
            // about 40% of the frame mapped past its bottom edge and was
            // silently dropped by `tapGesture`'s own bounds guard. Which is to
            // say the near half of the court - the half this pipeline tracks a
            // player on - could not be marked at all.
            .gesture(tapGesture(displaySize: CGSize(width: displayW, height: displayH)))
            .simultaneousGesture(magnifyGesture())
            .simultaneousGesture(panGesture)
            .position(x: geo.size.width / 2, y: geo.size.height / 2)
        }

        instructionRow(marking: marking)
        // Above the guide, not below it: the guide is asking him to tap
        // landmarks that are already down, and this is the line that says why
        // they are. androidApp says the same, from the same constant. The
        // finder's note sits in the same place and wins over it: the two only
        // meet when Find court over saved marks finds nothing, which would
        // otherwise go unsaid.
        if let note = finderStatus.screenNote(showsSavedMarks: showsSavedMarks) {
            Text(note)
                .shuttlType(ShuttlType.bodySmall)
                .foregroundStyle(Shuttl.textTertiary)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 16)
                .padding(.top, 10)
        }
        SchematicCourtGuide(placedCount: Int(marking.points.count))
            .padding(.vertical, 8)

        // Find court on a row of its own under Undo and Clear, as androidApp
        // lays it out: a third of a narrow phone is too little for its label.
        VStack(spacing: 8) {
            HStack(spacing: 12) {
                Button("Undo") { onUndo() }
                    .disabled(marking.points.isEmpty)
                Button("Clear") { onClear() }
                    .disabled(marking.points.isEmpty)
            }
            Button { onFindCourt() } label: {
                Text(CourtMarkingSpec.shared.FIND_COURT)
                    .frame(maxWidth: .infinity)
            }
            .disabled(finderStatus.isBusy)
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
                HStack(alignment: .firstTextBaseline) {
                    Text("What to analyze")
                        .shuttlType(ShuttlType.headlineLarge)
                        .foregroundStyle(Shuttl.textHeading)
                    Spacer(minLength: 8)
                    // On the header rather than on either button: the question
                    // it answers is "what is the difference between these two",
                    // which is about the pair.
                    Button { showCapabilities = true } label: {
                        Text("?")
                            .shuttlType(ShuttlType.labelMedium)
                            .foregroundStyle(Shuttl.textTertiary)
                            .frame(width: 26, height: 26)
                            .overlay(Circle().stroke(Shuttl.textTertiary, lineWidth: 1))
                            // 44pt is the minimum touch target; the ring stays 26.
                            .frame(width: 44, height: 44)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("What each run produces")
                }
                .padding(.top, 16)
                Text(AnalysisCapabilityKt.METRIC_PICKER_LINE)
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
        .sheet(isPresented: $showCapabilities) { CapabilitySheet() }
    }

    private func start(onDevice: Bool, marking: CourtMarkingState) {
        let keypoints = marking.toCourtKeypoints()
        if onDevice, let localAnalysis, let entry = rally.localVideos.get(id: entryId) {
            // Saved first, as Android does and as the cloud run does inside
            // `startAnalysis`: a device run never moves the stage off LOCAL, so
            // the saved court is what tells the match list this court is
            // marked. It also prefills the marking screen for the next run.
            rally.localVideos.setKeypoints(id: entryId, keypoints: keypoints)
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
            let p = CourtTapMath.inverse(
                tapX: value.location.x, tapY: value.location.y,
                offsetX: offset.width, offsetY: offset.height, scale: scale
            )
            guard p.x >= 0, p.y >= 0, p.x <= displaySize.width, p.y <= displaySize.height else { return }
            onTap(p, displaySize: displaySize)
        }
    }

    // MARK: - Marking and the court finder

    /// How far from a placed point a tap still picks it up, on screen.
    private static let hitRadius: CGFloat = 22

    /// A tap at `p`, in the un-zoomed display units of `displaySize`, which
    /// is what `place` maps from: places the next point, or on a complete
    /// marking picks a point up or moves it (`CourtMarkingState.tap`).
    private func onTap(_ p: CGPoint, displaySize: CGSize) {
        guard let before = marking else { return }
        // A point's reach is 22 pt on screen at any zoom, so in the un-zoomed
        // units `p` is in, it shrinks as the frame is zoomed in.
        let after = before.tap(
            displayX: Float(p.x), displayY: Float(p.y),
            displayWidth: Float(displaySize.width), displayHeight: Float(displaySize.height),
            hitRadiusDisplay: Float(Self.hitRadius / scale)
        )
        if after == before { return }
        let edited = after.points != before.points
        if edited && finderStatus.isBusy { cancelFinder() }
        marking = after
        if edited { showsSavedMarks = false }
        if after.justReachedFourCorners(previous: before) { completeFromCorners(after.points) }
    }

    private func onUndo() {
        cancelFinder()
        marking = marking?.undo()
        showsSavedMarks = false
        finderStatus = .idle
    }

    private func onClear() {
        cancelFinder()
        marking = marking?.clear()
        showsSavedMarks = false
        finderStatus = .idle
    }

    /// Find court: the finder on demand, replacing whatever is marked (saved
    /// marks included) as one step Undo reverses. Nothing found leaves the
    /// marking alone, and says so: `.findFailed` over marks, `.notFound` (tap
    /// the corners) over none. Ignored while the finder is already working.
    private func onFindCourt() {
        guard marking != nil, !finderStatus.isBusy else { return }
        runFinder(.finding) { image in
            let finding = await CourtFinderBridge.find(image, call: "find on demand")
            guard !Task.isCancelled, let marking else { return }
            apply(CourtAutoMarkingKt.afterFindCourt(marking: marking, finding: finding))
        }
    }

    /// The finder on an empty marking, as the screen opens. A tap before it
    /// answers wins: a court found after the coach started by hand is
    /// discarded (`afterOpeningFind`).
    private func findOnOpening() {
        runFinder(.finding) { image in
            let finding = await CourtFinderBridge.find(image, call: "find")
            guard !Task.isCancelled, let marking else { return }
            apply(CourtAutoMarkingKt.afterOpeningFind(marking: marking, finding: finding))
        }
    }

    /// The fourth corner was just tapped: ask the finder to complete the court
    /// from `corners`. Applied only onto those same four taps; anything the
    /// coach did in between wins. Undo afterwards gives the four taps back.
    private func completeFromCorners(_ corners: [CourtPoint]) {
        runFinder(.aligning) { image in
            let finding = await CourtFinderBridge.fromCorners(image, corners: corners)
            guard !Task.isCancelled else { return }
            guard let marking else {
                finderStatus = .idle
                return
            }
            apply(CourtAutoMarkingKt.afterCorners(marking: marking, corners: corners, finding: finding))
        }
    }

    /// The screen after a finder answer, by the shared rules androidApp
    /// applies too. An applied answer is an edit, which ends the saved-marks
    /// note.
    private func apply(_ outcome: CourtFinderOutcome) {
        marking = outcome.marking
        finderStatus = outcome.status
        if outcome.edited { showsSavedMarks = false }
    }

    /// Starts `work` on the frame on screen with the status `busy`, in place
    /// of any call already in flight.
    ///
    /// `work` must check `Task.isCancelled` after the finder answers: the
    /// search runs on a detached task that a cancel does not stop, so without
    /// the check a cancelled answer would land over whatever came after it.
    private func runFinder(_ busy: CourtFinderStatus, _ work: @escaping @MainActor (CGImage) async -> Void) {
        cancelFinder()
        guard let image = courtFrame?.cgImage else { return }
        finderStatus = busy
        finderTask = Task { await work(image) }
    }

    private func cancelFinder() {
        finderTask?.cancel()
        finderTask = nil
        if finderStatus.isBusy { finderStatus = .idle }
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
            Group {
                if let busyLine = finderStatus.busyLine {
                    // The finder is looking at the frame. It stands in for the
                    // instruction only while it works: a tap that places or
                    // moves a point wins over its answer and brings the
                    // instruction back.
                    HStack(spacing: 10) {
                        ProgressView()
                            .controlSize(.small)
                        Text(busyLine)
                            .foregroundStyle(Shuttl.textHeading)
                    }
                } else if let selected = marking.selected?.intValue {
                    // In the point's own colour, like the placing instruction:
                    // the ring on the frame and this line name the same point.
                    Text(CourtMarkingSpec.shared.moveInstruction(index: Int32(selected)))
                        .foregroundStyle(specColor(selected))
                } else if marking.isComplete {
                    Text("All points placed")
                        .foregroundStyle(Shuttl.accent)
                } else {
                    Text("Tap: \(CourtMarkingSpec.shared.fullLabels[Int(marking.nextIndex)])")
                        .foregroundStyle(specColor(Int(marking.nextIndex)))
                }
            }
            .shuttlType(ShuttlType.titleLarge)
            Spacer()
            Text("\(marking.points.count) / \(CourtMarkingSpec.shared.TOTAL) points")
                .shuttlType(ShuttlType.bodySmall)
                .foregroundStyle(Shuttl.textSecondary)
        }
        .padding(.horizontal, 16)
        .padding(.top, 8)
    }

    @ViewBuilder
    private func frameCanvas(frame: UIImage, marking: CourtMarkingState, displaySize: CGSize) -> some View {
        // The court drawn through the twelve marks, once there are twelve: a
        // mark off its paint shows as a line off the court's line, which is
        // easier to see than a dot slightly off.
        let courtLines = marking.courtLines()
        ZStack(alignment: .topLeading) {
            Image(uiImage: frame)
                .resizable()
                .frame(width: displaySize.width, height: displaySize.height)
            Canvas { context, size in
                drawCourtGuide(context: &context, size: size)
                // The corner rectangle says less than the court's lines and
                // would sit on top of them.
                if courtLines.isEmpty {
                    drawCornerRectangle(context: &context, size: size, marking: marking)
                } else {
                    drawCourtLines(context: &context, size: size, marking: marking, lines: courtLines)
                }
                drawPlacedPoints(context: &context, size: size, marking: marking)
                drawSelection(context: &context, size: size, marking: marking)
            }
        }
    }

    /// The court's lines through the marks, `lines` in source pixels. 2 pt on
    /// screen at any zoom, like the markers: a stroke that grew with the zoom
    /// would cover the paint it is there to be compared with.
    private func drawCourtLines(
        context: inout GraphicsContext,
        size: CGSize,
        marking: CourtMarkingState,
        lines: [KotlinPair<CourtPoint, CourtPoint>]
    ) {
        let fx = size.width / CGFloat(truncating: marking.videoWidth as NSNumber)
        let fy = size.height / CGFloat(truncating: marking.videoHeight as NSNumber)
        var path = Path()
        for line in lines {
            guard let a = line.first, let b = line.second else { continue }
            path.move(to: CGPoint(x: CGFloat(a.x) * fx, y: CGFloat(a.y) * fy))
            path.addLine(to: CGPoint(x: CGFloat(b.x) * fx, y: CGFloat(b.y) * fy))
        }
        context.stroke(path, with: .color(Shuttl.accent), lineWidth: 2 / scale)
    }

    /// The ring on the point a tap picked up to move, clear of its 10 pt
    /// marker at any zoom.
    private func drawSelection(context: inout GraphicsContext, size: CGSize, marking: CourtMarkingState) {
        guard let i = marking.selected?.intValue, i < marking.points.count else { return }
        let p = marking.points[i]
        let fx = size.width / CGFloat(truncating: marking.videoWidth as NSNumber)
        let fy = size.height / CGFloat(truncating: marking.videoHeight as NSNumber)
        let centre = CGPoint(x: CGFloat(p.x) * fx, y: CGFloat(p.y) * fy)
        let radius = 16 / scale
        let ring = Path(ellipseIn: CGRect(x: centre.x - radius, y: centre.y - radius, width: radius * 2, height: radius * 2))
        context.stroke(ring, with: .color(Shuttl.accent), lineWidth: 3 / scale)
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
