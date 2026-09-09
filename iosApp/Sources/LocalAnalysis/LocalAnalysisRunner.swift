import Foundation
import Shared
import SwiftUI

/// Where a local analysis has got to, per video. Port of Android's
/// `LocalAnalysisState`, plus one case Android has no need of - see `.paused`.
enum LocalAnalysisState: Equatable {
    case idle
    case preparing(message: String)
    case analysing(fraction: Float)
    case cutting(done: Int, total: Int)
    case done(Done)
    case failed(message: String)

    /// A run that stopped because the app left the foreground.
    ///
    /// iOS has no foreground service and no wake lock. `beginBackgroundTask`
    /// buys seconds, and `BGProcessingTaskRequest` runs when the system chooses,
    /// typically overnight on charge - neither can carry an analysis that costs
    /// minutes and was started by a button press. The honest options were to run
    /// only in the foreground or to promise background analysis and deliver a
    /// run that dies silently at 4%.
    ///
    /// So the run is suspended and SAYS it is suspended, and whatever was
    /// already written - the track, the skeleton - is kept, which is why the
    /// runner writes those before it cuts clips.
    case paused(fraction: Float)

    struct Done: Equatable {
        let rallies: Int
        let shuttleVisible: Int
        let totalFrames: Int
        let clips: [PlayerTrackStore.Clip]
        let elapsedSeconds: Double
        /// Empty unless a metric that needs pose was asked for.
        let playerTrack: PlayerTrack
        let fps: Double

        static func == (lhs: Done, rhs: Done) -> Bool {
            lhs.rallies == rhs.rallies && lhs.totalFrames == rhs.totalFrames
                && lhs.clips == rhs.clips && lhs.fps == rhs.fps
        }
    }
}

/// Whether an on-device run is currently working on this video.
///
/// Port of Android's `isDeviceRunInFlight`, and it exists for the same reason:
/// a device run never moves `LocalVideoEntry.stage`, so the stage-based rules in
/// shared (`isAnalysisRunning` and friends) cannot see one. Any screen offering
/// an "Analyze" button has to ask this as well, or it leaves a live-looking
/// button over a run already in progress - pressing it walks the coach through
/// marking the court again and then `start` returns immediately, because the
/// entry is already running. Android shipped that: twelve taps, no effect, no
/// explanation.
///
/// An exhaustive switch rather than a set membership test, so adding a state
/// fails to compile here rather than quietly defaulting to "not running".
func isDeviceRunInFlight(_ state: LocalAnalysisState) -> Bool {
    switch state {
    case .preparing, .analysing, .cutting:
        return true
    // Outcomes, not work. `.idle` covers both "never started" and "finished and
    // forgotten after a termination". `.paused` is deliberately NOT in flight:
    // the whole point is that the coach can start it again.
    case .failed, .done, .idle, .paused:
        return false
    }
}

/// This state as the shared code's own model of a run, or nil when nothing is
/// happening.
///
/// The one reduction from the device pipeline's state to `DeviceWork`, so the
/// chrome indicator and a list row cannot disagree about what a run is doing or
/// what it is called - shared `deviceWorkLabel` turns one into words.
///
/// `.cutting` reports no fraction on purpose: its done/total counts clips, not
/// frames, so rendering it as the analysis percentage would show the bar
/// restarting near the end of a run.
func toDeviceWork(entryId: String, state: LocalAnalysisState) -> DeviceWork? {
    switch state {
    case .idle, .done:
        return nil
    case .preparing:
        return DeviceWork(entryId: entryId, phase: .preparing, fraction: nil, failed: false)
    case .analysing(let fraction):
        return DeviceWork(entryId: entryId, phase: .analysing, fraction: KotlinFloat(float: fraction), failed: false)
    case .cutting:
        return DeviceWork(entryId: entryId, phase: .cutting, fraction: nil, failed: false)
    case .failed:
        return DeviceWork(entryId: entryId, phase: .analysing, fraction: nil, failed: true)
    // A paused run is not running, so it must not keep the chrome indicator
    // lit. What it is doing instead is said on its own row.
    case .paused:
        return nil
    }
}

/// Runs the on-device pipeline for one video and keeps its progress.
///
/// Port of Android's `LocalAnalysisRunner`. Held by the app rather than by a
/// view, because an analysis outlives the screen that starts it and navigating
/// away must not cancel it.
///
/// Deliberately not merged into `AnalyzeCoordinator`. That drives the cloud
/// path, and the whole point of running both is that they stay separable enough
/// to compare.
@Observable @MainActor
final class LocalAnalysisRunner {

    private(set) var states: [String: LocalAnalysisState] = [:]

    private let tracks = PlayerTrackStore()
    private let skeletons = SkeletonStore()
    private let log: (String) -> Void

    /// Which analyses are in flight.
    ///
    /// Tracked apart from `states` because the state is what the UI reads and it
    /// moves through preparing, analysing and cutting; "is anything running" is
    /// a different question, and the idle timer's lifetime depends on getting it
    /// right rather than on matching a particular state.
    private var running: Set<String> = []

    /// Set when the app leaves the foreground, read by the running pass at every
    /// progress callback.
    ///
    /// Cooperative cancellation rather than `Task.cancel`, because the pass is a
    /// synchronous loop over a decoder on a dispatch queue - there is no
    /// suspension point for a cancellation to land on, and the loop is exactly
    /// where the check belongs.
    private var suspendRequested = false

    init(log: @escaping (String) -> Void = { _ in }) {
        self.log = log
    }

    func state(for entryId: String) -> LocalAnalysisState { states[entryId] ?? .idle }

    /// Every run the chrome indicator should show.
    var deviceWork: [DeviceWork] {
        states.compactMap { toDeviceWork(entryId: $0.key, state: $0.value) }
    }

    /// Whether anything is running, which is what holds the screen awake.
    var isRunning: Bool { !running.isEmpty }

    // MARK: - Stored results

    /// The track from an earlier run, for a screen opened after this one died.
    func storedTrack(entryId: String) -> PlayerTrackStore.Stored? { tracks.load(entryId: entryId) }

    /// Whether `storedTrack` has anything to return, without loading it. For a
    /// list deciding what each of its rows can do.
    func hasStoredTrack(entryId: String) -> Bool { tracks.has(entryId: entryId) }

    /// The clips from an earlier run, for the same reason.
    func storedClips(entryId: String) -> [PlayerTrackStore.Clip] { tracks.loadClips(entryId: entryId) }

    func storedSkeleton(entryId: String) -> SkeletonStore.Stored? { skeletons.load(entryId: entryId) }

    /// Header-only, for a screen deciding whether it has a second renderer to
    /// offer.
    func hasStoredSkeleton(entryId: String) -> Bool { skeletons.has(entryId: entryId) }

    /// Every entry with a track this app can draw.
    ///
    /// The list asks once for the whole list rather than once per row, matching
    /// Android's `storedTrackIds`: `has` is a small read, and a list of forty
    /// matches is forty of them on every recomposition otherwise.
    func storedTrackIds(among entryIds: [String]) -> Set<String> {
        Set(entryIds.filter { tracks.has(entryId: $0) })
    }

    // MARK: - Running

    func start(
        entryId: String,
        videoPath: String,
        keypoints: CourtKeypoints,
        metrics: Set<AnalysisMetric> = [.rallyClips]
    ) {
        guard !running.contains(entryId) else { return }
        guard ModelCatalog.canAnalyseOnDevice else {
            states[entryId] = .failed(message: IosLocalInferenceEngine.Failure.noModels.localizedDescription)
            return
        }
        running.insert(entryId)
        suspendRequested = false
        // The iOS analogue of Android's wake lock. Without it the screen locks
        // under a running analysis and the pass stops with it, which on a
        // 20-minute run is every run.
        UIApplication.shared.isIdleTimerDisabled = true
        states[entryId] = .preparing(message: "Preparing video")

        let wantsPose = metrics.contains { $0.needsPose }
        Task.detached(priority: .userInitiated) { [weak self] in
            let started = Date()
            do {
                let outcome = try await self?.analyse(
                    entryId: entryId, videoPath: videoPath, keypoints: keypoints,
                    metrics: metrics, wantsPose: wantsPose, started: started
                )
                _ = outcome
            } catch is CancellationError {
                // Not a failure: the coach backgrounded the app, and `analyse`
                // has already recorded how far it got.
            } catch {
                await self?.finish(entryId: entryId, with: .failed(message: error.localizedDescription))
            }
            await self?.released(entryId)
        }
    }

    /// Ends a run's hold on the screen, and drops the idle-timer override once
    /// nothing at all is running.
    ///
    /// Two videos analysed back to back share one hold, and releasing on the
    /// first would let the screen lock under the second.
    private func released(_ entryId: String) {
        running.remove(entryId)
        if running.isEmpty { UIApplication.shared.isIdleTimerDisabled = false }
    }

    private func finish(entryId: String, with state: LocalAnalysisState) {
        states[entryId] = state
    }

    /// Called by the app when the scene stops being active.
    ///
    /// Requests, rather than performs: the pass is inside a decode loop and the
    /// next progress callback is where it can stop cleanly, with whatever it has
    /// written still on disk.
    func suspendForBackground() {
        guard isRunning else { return }
        suspendRequested = true
        log("local analysis: suspending, the app left the foreground")
    }

    private func shouldSuspend() -> Bool { suspendRequested }

    private func analyse(
        entryId: String,
        videoPath: String,
        keypoints: CourtKeypoints,
        metrics: Set<AnalysisMetric>,
        wantsPose: Bool,
        started: Date
    ) async throws {
        let source = URL(fileURLWithPath: videoPath)
        await MainActor.run { states[entryId] = .analysing(fraction: 0) }

        var lastFraction: Float = 0
        let engine = IosLocalInferenceEngine(wantsPose: wantsPose)
        let coordinator = LocalAnalysisCoordinator(engine: engine, log: log)

        // analyzeThrowing, not analyze: Kotlin's Result reaches Objective-C as a
        // bare id, so a failed analysis would cast to nil in Swift and read as
        // "returned nothing" rather than as the decode error it was.
        let result = try await coordinator.analyzeThrowing(
            videoPath: source.path,
            keypoints: keypoints,
            onProgress: { [weak self] fraction in
                let value = fraction.floatValue
                lastFraction = value
                Task { @MainActor [weak self] in
                    guard let self else { return }
                    guard !self.shouldSuspend() else {
                        self.states[entryId] = .paused(fraction: value)
                        return
                    }
                    self.states[entryId] = .analysing(fraction: value)
                }
            }
        )

        if await MainActor.run(body: { shouldSuspend() }) {
            await MainActor.run { states[entryId] = .paused(fraction: lastFraction) }
            throw CancellationError()
        }

        // Written before the clips are cut, which is minutes of work: a track
        // that survived the analysis should not be lost to a failure - or a
        // backgrounding - in the step after it.
        if !result.playerTrack.samples.isEmpty {
            try tracks.save(entryId: entryId, track: result.playerTrack, fps: result.result.fps)
        }

        // Kept only for the latest run that asked for it: a run that did not ask
        // for a skeleton, or asked but found nobody, removes the previous one,
        // so what the detail offers always belongs to the latest run and a coach
        // who declined it stops paying for it. The rule itself is shared, so the
        // two platforms cannot disagree about it.
        switch SkeletonDecisionKt.skeletonAction(metrics: metrics, poses: result.poses) {
        case .save:
            try skeletons.save(
                entryId: entryId, poses: result.poses, fps: result.result.fps,
                videoWidth: Int(result.videoWidth), videoHeight: Int(result.videoHeight),
                marks: keypoints.toAnalysis()
            )
        case .delete:
            try skeletons.delete(entryId: entryId)
        default:
            break
        }

        let windows = metrics.contains(.rallyClips) ? result.clipWindows : []
        await MainActor.run { states[entryId] = .cutting(done: 0, total: windows.count) }
        var clips: [PlayerTrackStore.Clip] = []
        if !windows.isEmpty {
            let directory = try AnalysisFiles.directory("local-clips/\(entryId)")
            clips = try ClipCutter().cut(source: source, windows: windows, into: directory) { done in
                Task { @MainActor [weak self] in
                    self?.states[entryId] = .cutting(done: done, total: windows.count)
                }
            }
            try tracks.saveClips(entryId: entryId, clips: clips)
        }

        let elapsed = Date().timeIntervalSince(started)
        await MainActor.run {
            states[entryId] = .done(LocalAnalysisState.Done(
                rallies: result.result.rallies.count,
                shuttleVisible: result.result.shuttlePositions.values.filter(\.visible).count,
                totalFrames: Int(result.result.totalFrames),
                clips: clips,
                elapsedSeconds: elapsed,
                playerTrack: result.playerTrack,
                fps: result.result.fps
            ))
        }
        log("local analysis done: \(clips.count) clips in \(Int(elapsed))s")
    }

    func clear(entryId: String) { states[entryId] = nil }
}
