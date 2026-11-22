import AVFoundation
import Shared
import SwiftUI

/// Everything the panel reads that does not change with the playhead, plus
/// frame-resolved pose state and an isolated playhead object.
///
/// One observable object rather than a screenful of `@State`, with the 30 Hz
/// playhead isolated inside `PlaybackPlayhead` and poses/series pre-converted
/// into pure-Swift arrays off the main actor so playback never bridges Kotlin
/// lists or invalidates `SkeletonPanel`'s outer layout at 30 Hz.
@Observable @MainActor
final class SkeletonPanelModel {
    enum Load {
        case loading
        case loaded(Loaded)
    }

    /// Pure-Swift cached pose built once off the main actor during load so
    /// binary search, overlay drawing, and tile updates never cross Objective-C
    /// or re-compute `poseMetrics` on every frame.
    struct CachedPose {
        let frame: Int32
        let timestamp: Double
        let keypoints: [CGPoint]
        let confidence: [Float]
        let source: PlayerPose
        let metrics: PoseMetrics
    }

    struct Loaded {
        let stored: SkeletonStore.Stored?
        /// The resolved court fit from the file's marks; non-nil only when
        /// `courtFit` is `.ok`. It fits the marking frame: a pose is measured
        /// through `poseCourtFit(h:pose:)`, which carries it through that
        /// pose's camera.
        let homography: [[KotlinDouble]]?
        let courtFit: CourtFit
        /// One entry per pose, in pose order, for the graph - per side, because
        /// a cloud analysis carries both players and the graph belongs to
        /// whichever one the toggle is showing.
        let seriesBySide: [CourtSide: [MetricSample]]
        /// Pure-Swift cached poses per side for O(log N) lookup and zero-bridge
        /// skeleton rendering.
        let cachedPosesBySide: [CourtSide: [CachedPose]]
        /// Pure-Swift cached metric series per side for zero-bridge graph drawing.
        let cachedSeriesBySide: [CourtSide: [CachedMetricSample]]
        /// Precomputed pose match tolerance matching `PoseLookupKt.poseToleranceS`.
        let poseToleranceS: Double
    }

    /// The side on screen. Held by side rather than by index so a reload that
    /// reordered or dropped one cannot silently swap which player is drawn.
    private(set) var side: CourtSide = .near

    /// The sides this file has joints for, in file order.
    var drawableSides: [CourtSide] {
        guard case .loaded(let l) = load, let stored = l.stored else { return [] }
        return stored.tracks.filter { !$0.poses.isEmpty }.map(\.side)
    }

    /// The shown side's joints. Not `stored.poses`, which is always near.
    var shownPoses: [PlayerPose] {
        guard case .loaded(let l) = load, let stored = l.stored else { return [] }
        let drawable = stored.tracks.filter { !$0.poses.isEmpty }
        return (drawable.first { $0.side == side } ?? drawable.first)?.poses ?? []
    }

    var shownCachedPoses: [CachedPose] {
        guard case .loaded(let l) = load else { return [] }
        return l.cachedPosesBySide[shownSide] ?? []
    }

    var shownSeries: [MetricSample] {
        guard case .loaded(let l) = load else { return [] }
        return l.seriesBySide[shownSide] ?? []
    }

    var shownCachedSeries: [CachedMetricSample] {
        guard case .loaded(let l) = load else { return [] }
        return l.cachedSeriesBySide[shownSide] ?? []
    }

    var shownSide: CourtSide { drawableSides.contains(side) ? side : (drawableSides.first ?? .near) }

    func setSide(_ next: CourtSide) {
        guard next != side else { return }
        side = next
        resolvedFrame = nil
        resolvedSide = nil
        resolvePose()
    }

    private(set) var load: Load = .loading
    /// Isolated 30 Hz playhead read only by the graph plot leaf view.
    let playhead = PlaybackPlayhead()
    var position: PlaybackPosition { playhead.position }
    /// The pose the playhead is on, and what it measures.
    ///
    /// Resolved via pure-Swift binary search and only updated when the frame
    /// (or active side) actually changes.
    private(set) var cachedPose: CachedPose?
    private(set) var pose: PlayerPose?
    private(set) var metrics: PoseMetrics?
    private var resolvedFrame: Int32? = nil
    private var resolvedSide: CourtSide? = nil
    /// The per-video choice, as stored. What the metrics use is `shownArm`.
    var racketArm: RacketArm?
    /// Who stands at each end (`courtSideNames`), for a profile's playing hand.
    /// Set by the panel as the match says it, not at init: this model outlives
    /// the panel's own redraws.
    var sideNames: CourtSideNames?

    /// The playing hand of the profile on the side shown, when it has one set.
    var profileArm: RacketArm? { CourtSideNamesKt.profileArm(side: shownSide, names: sideNames) }

    /// The arm the metrics are read for. The shown player's profile hand wins
    /// over the per-video choice: it is the one answer for that person on every
    /// video, and the pills that would contradict it are not drawn. The
    /// per-video choice is kept untouched, so a side with no profile hand (or a
    /// match with no profile) still reads it. Mirrors androidApp's SkeletonPanel.
    var shownArm: RacketArm? { profileArm ?? racketArm }
    /// The coach's pick, kept even while the racket-arm control hides its tile -
    /// see `selected`.
    var chosen: MetricKind = .elbowRight
    var expanded = false
    var playbackError: String? = nil
    private(set) var player: AVPlayer?
    /// The video's own frame rate, for whole-frame stepping. Read from the
    /// asset rather than from the skeleton file: the stored fps is the analysis
    /// pass's, which is the same number, but the transport is stepping THIS
    /// player's media.
    private var fps: Float = 0
    private var failureObservation: NSKeyValueObservation?
    private var loadedKey: LoadKey?

    private struct LoadKey: Equatable {
        let entryId: String
        let videoRelativePath: String?
    }

    let entryId: String
    private let runner: LocalAnalysisRunner
    private let racketArmPrefs: RacketArmPreferenceRepository

    init(entryId: String, runner: LocalAnalysisRunner, racketArmPrefs: RacketArmPreferenceRepository) {
        self.entryId = entryId
        self.runner = runner
        self.racketArmPrefs = racketArmPrefs
        self.racketArm = racketArmPrefs.racketArm(entryId: entryId)
    }

    /// Reads the file and measures every pose in it, off the main actor.
    ///
    /// `SkeletonStore.load` parses the whole file, and its own note prices a
    /// 30-minute match at 11MB of poses; measuring them is another pass over the
    /// same 6,000 frames. Doing either while the screen opens would jank it, so
    /// both happen here with a one-line placeholder up in the meantime.
    ///
    /// A court fit whose marks miss by more than the near-player selector's own
    /// gate is dropped rather than turned into a stance in metres that is metres
    /// wrong.
    func loadSkeleton(videoRelativePath: String?) async {
        let key = LoadKey(entryId: entryId, videoRelativePath: videoRelativePath)
        if case .loaded = load, loadedKey == key { return }
        let entryId = self.entryId
        let runner = self.runner
        let loaded = await Task.detached(priority: .userInitiated) { () -> Loaded in
            let stored = runner.storedSkeleton(entryId: entryId)
            let marks = stored?.marks
            let homography = marks?.homography().flatMap { candidate -> [[KotlinDouble]]? in
                guard let residual = marks?.maxResidualM(h: candidate)?.doubleValue,
                      residual <= NearPlayerSelector.companion.MAX_COURT_RESIDUAL_M
                else { return nil }
                return candidate
            }
            let courtFit: CourtFit = marks == nil ? .none : (homography == nil ? .bad : .ok)
            // Per side, and here rather than on selection: the metrics for a
            // 30-minute match are the expensive part of this read, and moving
            // them to the toggle would put them on the main actor. Each pose
            // through its own frame's fit: the gate above judged the marks on
            // the frame they were placed on, and a pose from a frame the camera
            // had moved in reaches the court through its camera.
            var seriesBySide: [CourtSide: [MetricSample]] = [:]
            var cachedPosesBySide: [CourtSide: [CachedPose]] = [:]
            var cachedSeriesBySide: [CourtSide: [CachedMetricSample]] = [:]
            for track in stored?.tracks ?? [] {
                var samples: [MetricSample] = []
                var cachedPoses: [CachedPose] = []
                var cachedSamples: [CachedMetricSample] = []
                samples.reserveCapacity(track.poses.count)
                cachedPoses.reserveCapacity(track.poses.count)
                cachedSamples.reserveCapacity(track.poses.count)
                for pose in track.poses {
                    let measured = PoseMetricsKt.poseMetrics(
                        keypoints: pose.keypoints,
                        confidence: pose.confidence,
                        homography: PoseMetricsKt.poseCourtFit(h: homography, pose: pose),
                        minConfidence: NearPlayerSelector.companion.MIN_KEYPOINT_CONFIDENCE
                    )
                    samples.append(MetricSample(timestamp: pose.timestamp, metrics: measured))
                    cachedPoses.append(CachedPose(
                        frame: pose.frame,
                        timestamp: pose.timestamp,
                        keypoints: pose.keypoints.map { CGPoint(x: $0.x, y: $0.y) },
                        confidence: pose.confidence.map(\.floatValue),
                        source: pose,
                        metrics: measured
                    ))
                    cachedSamples.append(CachedMetricSample(timestamp: pose.timestamp, metrics: measured))
                }
                seriesBySide[track.side] = samples
                cachedPosesBySide[track.side] = cachedPoses
                cachedSeriesBySide[track.side] = cachedSamples
            }
            let tolerance = stored.map { PoseLookupKt.poseToleranceS(fps: $0.fps) } ?? 0
            return Loaded(
                stored: stored,
                homography: homography,
                courtFit: courtFit,
                seriesBySide: seriesBySide,
                cachedPosesBySide: cachedPosesBySide,
                cachedSeriesBySide: cachedSeriesBySide,
                poseToleranceS: tolerance
            )
        }.value
        guard !Task.isCancelled else { return }

        load = .loaded(loaded)
        loadedKey = key
        chosen = loaded.courtFit == .ok ? .stance : .elbowRight
        // The first tick is a thirtieth of a second away and the panel draws
        // before it: without this the video's opening frame has no skeleton on
        // it and every tile reads as a dash.
        resolvedFrame = nil
        resolvedSide = nil
        resolvePose()
        guard loaded.stored != nil,
              let videoRelativePath,
              let url = runner.analysedSource(relativePath: videoRelativePath)
        else {
            failureObservation?.invalidate()
            failureObservation = nil
            player?.pause()
            player = nil
            return
        }
        preparePlayer(url: url)
        await loadFrameRate(url: url)
    }

    private func preparePlayer(url: URL) {
        failureObservation?.invalidate()
        failureObservation = nil
        player?.pause()
        playbackError = nil
        let created = AVPlayer(url: url)
        // Mirrors LocalPlayerModel: the analysed file can be moved or deleted
        // behind us, and without this the player just freezes silently instead
        // of saying so.
        failureObservation = created.observe(\.currentItem?.status) { [weak self] player, _ in
            guard player.currentItem?.status == .failed else { return }
            Task { @MainActor [weak self] in
                self?.playbackError =
                    "Couldn't play the analyzed video. Run the analysis again to rebuild it."
            }
        }
        player = created
    }

    private func loadFrameRate(url: URL) async {
        let asset = AVURLAsset(url: url)
        if let track = try? await asset.loadTracks(withMediaType: .video).first,
           let rate = try? await track.load(.nominalFrameRate) {
            fps = rate
        }
    }

    /// Pure-Swift port of `PoseLookupKt.nearestPose`: binary search over
    /// timestamp-sorted cached poses with zero Objective-C bridging.
    nonisolated static func nearestPose(
        in poses: [CachedPose],
        seconds: Double,
        toleranceS: Double
    ) -> CachedPose? {
        guard !poses.isEmpty else { return nil }
        var lo = 0
        var hi = poses.count - 1
        while lo < hi {
            let mid = (lo + hi) / 2
            if poses[mid].timestamp < seconds {
                lo = mid + 1
            } else {
                hi = mid
            }
        }
        let after = poses[lo]
        let best: CachedPose
        if lo > 0 {
            let before = poses[lo - 1]
            best = abs(before.timestamp - seconds) <= abs(after.timestamp - seconds) ? before : after
        } else {
            best = after
        }
        return abs(best.timestamp - seconds) <= toleranceS ? best : nil
    }

    /// Moves the playhead, and with it the pose and the measurements - but only
    /// when the frame under it actually changed. See `pose`.
    func setPosition(_ next: PlaybackPosition) {
        if playhead.position != next {
            playhead.position = next
        }
        resolvePose()
    }

    private func resolvePose() {
        guard case .loaded(let loaded) = load, loaded.stored != nil else { return }
        let activeSide = shownSide
        let found = Self.nearestPose(
            in: loaded.cachedPosesBySide[activeSide] ?? [],
            seconds: Double(playhead.position.positionMs) / 1000,
            toleranceS: loaded.poseToleranceS
        )
        // Nil is a frame with no pose and is a state of its own, so "no change"
        // has to compare the two absences too, not just two frame numbers.
        guard found?.frame != resolvedFrame
            || (found == nil) != (cachedPose == nil)
            || resolvedSide != activeSide
        else { return }
        resolvedFrame = found?.frame
        resolvedSide = activeSide
        cachedPose = found
        pose = found?.source
        metrics = found?.metrics
    }

    var hasCourt: Bool {
        guard case .loaded(let loaded) = load else { return false }
        return loaded.homography != nil
    }

    /// The coach's choice held to the tiles on screen: the racket-arm control
    /// can hide the chosen kind, and the graph and the arc must never show a
    /// kind with no tile. Derived rather than written back, so the choice itself
    /// is kept and putting the arm back brings the tile back selected.
    var selected: MetricKind {
        let visible = MetricsFormatKt.visibleKinds(hasCourt: hasCourt, racketArm: shownArm)
        return visible.contains(chosen) ? chosen : (visible.first ?? .elbowRight)
    }

    func setRacketArm(_ arm: RacketArm?) {
        racketArm = arm
        racketArmPrefs.setRacketArm(entryId: entryId, arm: arm)
    }

    func stepFrames(_ delta: Int64) {
        guard let player else { return }
        let duration = player.currentItem?.duration
        player.seek(
            to: CMTime(
                seconds: FrameStepMath.targetSeconds(
                    currentSeconds: player.currentTime().isNumeric
                        ? CMTimeGetSeconds(player.currentTime()) : 0,
                    fps: fps,
                    delta: delta,
                    durationSeconds: (duration?.isNumeric == true) ? CMTimeGetSeconds(duration!) : 0
                ),
                preferredTimescale: 600
            ),
            toleranceBefore: .zero,
            toleranceAfter: .zero
        )
    }

    /// Seeks from the graph, which asks in absolute seconds rather than in a
    /// fraction. Pauses first: scrubbing a curve while the video runs away under
    /// the finger is not scrubbing.
    func seek(toSeconds seconds: Double) {
        guard let player else { return }
        if player.rate != 0 { player.pause() }
        let duration = player.currentItem?.duration
        let limit = (duration?.isNumeric == true) ? CMTimeGetSeconds(duration!) : .greatestFiniteMagnitude
        player.seek(
            to: CMTime(seconds: min(max(seconds, 0), limit), preferredTimescale: 600),
            toleranceBefore: .zero,
            toleranceAfter: .zero
        )
    }
}

/// The near player's skeleton over the video it was measured on.
///
/// Two things have to exist: the poses a run kept (only when the skeleton metric
/// was ticked) and the file that run decoded. Either missing is said in one line
/// rather than drawn around, because a skeleton over the wrong frames looks like
/// tracking that is broken, and a coach cannot tell that from tracking that is
/// bad.
///
/// Under the video sit the transport, the strip and the graph: one tile per
/// measurement of the frame on screen, and the selected tile's kind drawn on the
/// skeleton as an arc and plotted over the two seconds either side of the
/// playhead. Nothing here is stored - every number is computed from the poses in
/// the file at view time, so a metric added later needs no re-run, and the two
/// court-plane tiles appear only when the file carries marks that fit.
///
/// Port of androidApp's `SkeletonPanel`.
struct SkeletonPanel: View {
    let entryId: String
    /// The entry's own path, or nil when the video it was measured on is gone.
    let videoRelativePath: String?
    let runner: LocalAnalysisRunner
    let prefs: PlaybackPreferenceRepository
    let racketArmPrefs: RacketArmPreferenceRepository
    /// Who stands at each end: the toggle's names and a profile's hand.
    let sideNames: CourtSideNames?

    @State private var model: SkeletonPanelModel

    init(
        entryId: String,
        videoRelativePath: String?,
        runner: LocalAnalysisRunner,
        prefs: PlaybackPreferenceRepository,
        racketArmPrefs: RacketArmPreferenceRepository,
        sideNames: CourtSideNames?
    ) {
        self.entryId = entryId
        self.videoRelativePath = videoRelativePath
        self.runner = runner
        self.prefs = prefs
        self.racketArmPrefs = racketArmPrefs
        self.sideNames = sideNames
        let model = SkeletonPanelModel(entryId: entryId, runner: runner, racketArmPrefs: racketArmPrefs)
        model.sideNames = sideNames
        _model = State(initialValue: model)
    }

    var body: some View {
        content
            .onChange(of: sideNames) { _, names in model.sideNames = names }
            .onDisappear { model.player?.pause() }
            .task(id: videoRelativePath) { await model.loadSkeleton(videoRelativePath: videoRelativePath) }
            .task {
                for await value in prefs.metricsExpanded {
                    model.expanded = value.boolValue
                }
            }
    }

    @ViewBuilder
    private var content: some View {
        switch model.load {
        case .loading:
            PanelMessage(text: "Loading skeleton")
        case .loaded(let loaded):
            if let stored = loaded.stored {
                if stored.videoWidth <= 0 || stored.videoHeight <= 0 {
                    // Guards the aspect ratio below, which a non-positive size
                    // would make meaningless. A stored size this broken is
                    // another "this skeleton cannot be shown" case.
                    PanelMessage(
                        text: "This skeleton's stored video size is invalid. "
                            + "Run the analysis again to rebuild it."
                    )
                } else if model.player == nil {
                    PanelMessage(
                        text: "The video this skeleton was measured on is no longer on this phone."
                    )
                } else {
                    player(stored: stored, loaded: loaded)
                }
            } else {
                PanelMessage(
                    text: "No skeleton was kept for this video. "
                        + "Run the analysis again with \"Skeleton playback\" ticked."
                )
            }
        }
    }

    private func player(stored: SkeletonStore.Stored, loaded: SkeletonPanelModel.Loaded) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            ShuttlPlayer(
                player: model.player,
                prefs: prefs,
                step: { model.stepFrames($0) },
                // The video's own stored ratio, so the player fills the card
                // edge to edge and the overlay's letterbox arithmetic reduces to
                // the identity.
                aspectRatio: Double(stored.videoWidth) / Double(stored.videoHeight),
                onPosition: { model.setPosition($0) },
                overlay: {
                    SkeletonOverlayHost(
                        model: model,
                        videoWidth: Int(stored.videoWidth),
                        videoHeight: Int(stored.videoHeight)
                    )
                },
                errorContent: {
                    if let error = model.playbackError {
                        ErrorBanner(message: error).padding(16)
                    }
                }
            )
            // The frame is the one fact read per frame. Isolated in a leaf view
            // so `ShuttlPlayer`, `ShuttlTransportBar`, `SideToggle`, and
            // `MetricsStrip` do not re-evaluate on every frame tick.
            SkeletonFooterRow(model: model)
                .padding(.horizontal, ShuttlGutter.page)
                .padding(.top, 22)
            // With the strip rather than above it, so one row of controls does
            // not become two. Absent on a device run, which only has the near
            // player.
            if model.drawableSides.count > 1 {
                SideToggle(
                    sides: model.drawableSides,
                    selected: model.shownSide,
                    onSelect: { model.setSide($0) },
                    names: model.sideNames
                )
                .padding(.horizontal, ShuttlGutter.page)
                .padding(.vertical, 8)
            }
            SkeletonMetricsHost(model: model, loaded: loaded, prefs: prefs)
                .padding(.top, 10)
            SkeletonGraphHost(model: model, fps: stored.fps)
                .padding(.top, 8)
            // A court warning is shown here, not only in the expanded detail: a
            // coach looking for the Stance tile should not have to open the grid
            // to learn why it is not there.
            if let warning = SkeletonSummaryKt.courtWarning(courtFit: loaded.courtFit) {
                Text(warning)
                    .shuttlType(ShuttlType.bodySmall)
                    .foregroundStyle(Shuttl.textTertiary)
                    .padding(.horizontal, ShuttlGutter.page)
                    .padding(.vertical, 12)
            }
            Spacer(minLength: ShuttlGutter.page)
        }
    }
}

/// Leaf view that observes `model.cachedPose` and `model.selected` for the
/// skeleton overlay without invalidating `ShuttlPlayer` or `ShuttlTransportBar`.
private struct SkeletonOverlayHost: View {
    let model: SkeletonPanelModel
    let videoWidth: Int
    let videoHeight: Int

    var body: some View {
        if let pose = model.cachedPose {
            SkeletonOverlay(
                keypoints: pose.keypoints,
                confidence: pose.confidence,
                videoWidth: videoWidth,
                videoHeight: videoHeight,
                highlight: poseHighlight(for: model.selected)
            )
        }
    }
}

/// Leaf view for the per-frame footer line.
private struct SkeletonFooterRow: View {
    let model: SkeletonPanelModel

    var body: some View {
        HStack(alignment: .firstTextBaseline) {
            Text(SkeletonSummaryKt.skeletonFooter(frame: model.cachedPose.map { KotlinInt(int: $0.frame) }))
                .shuttlType(ShuttlType.bodySmall)
                .foregroundStyle(Shuttl.textTertiary)
            Spacer(minLength: 8)
        }
    }
}

/// Leaf view that observes `model.metrics` for `MetricsStrip` alone.
private struct SkeletonMetricsHost: View {
    let model: SkeletonPanelModel
    let loaded: SkeletonPanelModel.Loaded
    let prefs: PlaybackPreferenceRepository

    var body: some View {
        MetricsStrip(
            metrics: model.metrics,
            hasCourt: model.hasCourt,
            racketArm: model.shownArm,
            onRacketArm: { model.setRacketArm($0) },
            racketArmFromProfile: model.profileArm != nil,
            selected: model.selected,
            onSelect: { model.chosen = $0 },
            expanded: model.expanded,
            onExpanded: { prefs.setMetricsExpanded(expanded: $0) },
            detail: SkeletonSummaryKt.skeletonDetail(
                frames: Int32(model.shownCachedPoses.count), courtFit: loaded.courtFit
            )
        )
    }
}

/// Leaf view that connects `MetricGraph` to `model.playhead` and the active
/// side's pre-converted pure-Swift series.
private struct SkeletonGraphHost: View {
    let model: SkeletonPanelModel
    let fps: Double

    var body: some View {
        MetricGraph(
            cachedSeries: model.shownCachedSeries,
            kind: model.selected,
            label: MetricsFormatKt.metricLabel(kind: model.selected, racketArm: model.shownArm),
            playhead: model.playhead,
            fps: fps,
            onSeek: { model.seek(toSeconds: $0) }
        )
    }
}
