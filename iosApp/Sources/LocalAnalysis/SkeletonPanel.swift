import AVFoundation
import Shared
import SwiftUI

/// Everything the panel reads that does not change with the playhead, plus the
/// playhead itself.
///
/// One observable object rather than a screenful of `@State`, because the
/// position moves thirty times a second: held in the view, every tick would
/// re-evaluate the body that owns the loaded skeleton and the series, and
/// neither is cheap to hand back to SwiftUI. Held here, a tick invalidates only
/// the views that actually read `position`.
///
/// The series stays the Kotlin list `poseMetrics` produced. Mapping it into
/// Swift values would be an O(poses) bridge crossing, and `graphSegments` - the
/// only thing that reads it - takes the Kotlin list anyway.
@Observable @MainActor
final class SkeletonPanelModel {
    enum Load {
        case loading
        case loaded(Loaded)
    }

    struct Loaded {
        let stored: SkeletonStore.Stored?
        /// The resolved court fit from the file's marks; non-nil only when
        /// `courtFit` is `.ok`.
        let homography: [[KotlinDouble]]?
        let courtFit: CourtFit
        /// One entry per pose, in pose order, for the graph.
        let series: [MetricSample]
    }

    private(set) var load: Load = .loading
    private(set) var position = PlaybackPosition()
    /// The pose the playhead is on, and what it measures.
    ///
    /// Resolved when the position moves rather than read from the view, and only
    /// when the FRAME changes: the playhead ticks thirty times a second, a frame
    /// lasts one thirtieth, and `poseMetrics` is a full pass over seventeen
    /// joints while `nearestPose` hands the whole pose list back across the
    /// Objective-C bridge. androidApp gets the same for free from
    /// `remember(positionMs, stored)` and `remember(pose, homography)`.
    private(set) var pose: PlayerPose?
    private(set) var metrics: PoseMetrics?
    private var resolvedFrame: Int32? = nil
    var racketArm: RacketArm?
    /// The coach's pick, kept even while the racket-arm control hides its tile -
    /// see `selected`.
    var chosen: MetricKind = .elbowRight
    var expanded = false
    var speed = PlaybackOptions.shared.DEFAULT_SPEED
    var playbackError: String? = nil
    private(set) var player: AVPlayer?
    /// The video's own frame rate, for whole-frame stepping. Read from the
    /// asset rather than from the skeleton file: the stored fps is the analysis
    /// pass's, which is the same number, but the transport is stepping THIS
    /// player's media.
    private var fps: Float = 0
    private var failureObservation: NSKeyValueObservation?

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
            let series = (stored?.poses ?? []).map { pose in
                MetricSample(
                    timestamp: pose.timestamp,
                    metrics: PoseMetricsKt.poseMetrics(
                        keypoints: pose.keypoints,
                        confidence: pose.confidence,
                        homography: homography,
                        minConfidence: NearPlayerSelector.companion.MIN_KEYPOINT_CONFIDENCE
                    )
                )
            }
            return Loaded(stored: stored, homography: homography, courtFit: courtFit, series: series)
        }.value

        load = .loaded(loaded)
        chosen = loaded.courtFit == .ok ? .stance : .elbowRight
        // The first tick is a thirtieth of a second away and the panel draws
        // before it: without this the video's opening frame has no skeleton on
        // it and every tile reads as a dash.
        resolvedFrame = nil
        resolvePose()
        guard loaded.stored != nil,
              let videoRelativePath,
              let url = runner.analysedSource(relativePath: videoRelativePath)
        else { return }
        preparePlayer(url: url)
        await loadFrameRate(url: url)
    }

    private func preparePlayer(url: URL) {
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

    /// Moves the playhead, and with it the pose and the measurements - but only
    /// when the frame under it actually changed. See `pose`.
    func setPosition(_ next: PlaybackPosition) {
        position = next
        resolvePose()
    }

    private func resolvePose() {
        guard case .loaded(let loaded) = load, let stored = loaded.stored else { return }
        let found = PoseLookupKt.nearestPose(
            poses: stored.poses,
            seconds: Double(position.positionMs) / 1000,
            toleranceS: PoseLookupKt.poseToleranceS(fps: stored.fps)
        )
        // Nil is a frame with no pose and is a state of its own, so "no change"
        // has to compare the two absences too, not just two frame numbers.
        guard found?.frame != resolvedFrame || (found == nil) != (pose == nil) else { return }
        resolvedFrame = found?.frame
        pose = found
        metrics = found.map { found in
            PoseMetricsKt.poseMetrics(
                keypoints: found.keypoints,
                confidence: found.confidence,
                homography: loaded.homography,
                minConfidence: NearPlayerSelector.companion.MIN_KEYPOINT_CONFIDENCE
            )
        }
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
        let visible = MetricsFormatKt.visibleKinds(hasCourt: hasCourt, racketArm: racketArm)
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

    @State private var model: SkeletonPanelModel

    init(
        entryId: String,
        videoRelativePath: String?,
        runner: LocalAnalysisRunner,
        prefs: PlaybackPreferenceRepository,
        racketArmPrefs: RacketArmPreferenceRepository
    ) {
        self.entryId = entryId
        self.videoRelativePath = videoRelativePath
        self.runner = runner
        self.prefs = prefs
        self.racketArmPrefs = racketArmPrefs
        _model = State(initialValue: SkeletonPanelModel(
            entryId: entryId, runner: runner, racketArmPrefs: racketArmPrefs
        ))
    }

    var body: some View {
        content
            .task { await model.loadSkeleton(videoRelativePath: videoRelativePath) }
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
        let pose = model.pose
        return VStack(alignment: .leading, spacing: 0) {
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
                    if let pose {
                        SkeletonOverlay(
                            keypoints: pose.keypoints,
                            confidence: pose.confidence.map(\.floatValue),
                            videoWidth: Int(stored.videoWidth),
                            videoHeight: Int(stored.videoHeight),
                            highlight: poseHighlight(for: model.selected)
                        )
                    }
                },
                errorContent: {
                    if let error = model.playbackError {
                        ErrorBanner(message: error).padding(16)
                    }
                }
            )
            // The frame is the one fact read per frame; the speed sits opposite
            // it, as the mock lays the line out.
            HStack(alignment: .firstTextBaseline) {
                Text(SkeletonSummaryKt.skeletonFooter(frame: pose.map { KotlinInt(int: $0.frame) }))
                    .shuttlType(ShuttlType.bodySmall)
                    .foregroundStyle(Shuttl.textTertiary)
                Spacer(minLength: 8)
                Text("\(PlaybackOptions.shared.formatSpeed(speed: model.speed)) speed")
                    .shuttlType(ShuttlType.bodySmall)
                    .foregroundStyle(Shuttl.textSecondary)
            }
            .padding(.horizontal, ShuttlGutter.page)
            .padding(.top, 22)
            MetricsStrip(
                metrics: model.metrics,
                hasCourt: model.hasCourt,
                racketArm: model.racketArm,
                onRacketArm: { model.setRacketArm($0) },
                selected: model.selected,
                onSelect: { model.chosen = $0 },
                expanded: model.expanded,
                onExpanded: { prefs.setMetricsExpanded(expanded: $0) },
                detail: SkeletonSummaryKt.skeletonDetail(
                    frames: Int32(stored.poses.count), courtFit: loaded.courtFit
                )
            )
            .padding(.top, 10)
            MetricGraph(
                series: loaded.series,
                kind: model.selected,
                label: MetricsFormatKt.metricLabel(kind: model.selected, racketArm: model.racketArm),
                positionS: Double(model.position.positionMs) / 1000,
                fps: stored.fps,
                durationS: model.position.durationMs > 0
                    ? Double(model.position.durationMs) / 1000
                    : .infinity,
                onSeek: { model.seek(toSeconds: $0) }
            )
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
        .task {
            for await value in prefs.speed {
                model.speed = value.floatValue
            }
        }
    }
}
