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

    /// A run that is not progressing, because the app is in the background.
    ///
    /// iOS has no foreground service and no wake lock. `beginBackgroundTask`
    /// buys seconds, and `BGProcessingTaskRequest` runs when the system chooses,
    /// typically overnight on charge - neither can carry an analysis that costs
    /// minutes and was started by a button press. The honest options were to run
    /// only in the foreground or to promise background analysis and deliver a
    /// run that dies silently at 4%.
    ///
    /// So the run SAYS it has stopped, and it is NOT cancelled. The system
    /// freezes the process seconds after it goes to the background and thaws it
    /// on return, so the pass resumes on the frame it was on; cancelling would
    /// throw away minutes of work the OS was about to hand back intact. This
    /// state is what a row shows in the meantime, and `resumeFromBackground`
    /// clears it.
    ///
    /// What is genuinely lost is a run the system kills while suspended, which
    /// takes with it everything not yet on disk. The track and the skeleton are
    /// written before clip cutting - minutes of work - so a kill during THAT
    /// step keeps them; a kill during the analysis itself keeps nothing,
    /// because at that point nothing has been written.
    ///
    /// Carries the state it interrupted rather than a bare fraction, because a
    /// run can be frozen while preparing or while cutting clips too, and
    /// neither of those has a fraction to carry. `resumeFromBackground` puts
    /// exactly that state back.
    indirect case paused(LocalAnalysisState)

    /// How far a run had got when it was paused, if it was paused during the
    /// analysis. Nil while preparing and while cutting, neither of which
    /// reports one.
    var pausedFraction: Float? {
        if case .paused(.analysing(let fraction)) = self { return fraction }
        return nil
    }

    /// Whether the run has stopped, whichever way it stopped.
    ///
    /// Both outcomes, not `.done` alone: the track is written before the clips
    /// are cut, so a run that failed in the cut still left one behind and the
    /// screens that read it can still open it.
    ///
    /// A paused run has not stopped. `.paused` carries the state it interrupted
    /// and none of the states it can interrupt is an outcome.
    var hasSettled: Bool {
        switch self {
        case .done, .failed: return true
        case .idle, .preparing, .analysing, .cutting, .paused: return false
        }
    }

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
    case .preparing, .analysing, .cutting, .paused:
        // `.paused` counts, because nothing was cancelled. A row that treated it
        // as finished would offer a live button over a pass that is still there,
        // `start` would refuse it on its own guard, and the coach would get
        // exactly the Android defect this function exists to prevent.
        return true
    // Outcomes, not work. `.idle` covers both "never started" and "finished and
    // forgotten after a termination".
    case .failed, .done, .idle:
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
    // Nothing to light. The indicator is only on screen while the app is
    // active, and `.paused` is cleared on the way back to active, so all this
    // could do is contradict the row - which says the run has stopped - for the
    // one frame before it goes.
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

    /// Which runs have stopped. Observe this, not `states`, to learn that one
    /// has.
    ///
    /// `@Observable` tracks a property, not a key: reading `states` at all -
    /// `state(for:)` included - subscribes the reader to every write to the
    /// dictionary, and a run in flight writes one per analysed frame. A screen
    /// that only wants to re-read the disk when a run finishes was redrawing
    /// itself, and its panels with it, at the rate of the progress bar. This set
    /// changes twice per run.
    private(set) var settledRuns: Set<String> = []

    /// The one place `states` changes.
    ///
    /// Every write goes through here so `settledRuns` is derived rather than
    /// kept by hand: a dozen assignment sites and a second collection to
    /// remember at each of them is a drift waiting to happen.
    private func set(_ state: LocalAnalysisState?, for entryId: String) {
        states[entryId] = state
        if state?.hasSettled == true {
            settledRuns.insert(entryId)
        } else {
            settledRuns.remove(entryId)
        }
    }

    // `nonisolated`, because `analyse` is: both stores are stateless value
    // types over the file system, and the run reaches them from the queue it
    // works on rather than from the main one.
    private nonisolated let tracks = PlayerTrackStore()
    private nonisolated let skeletons = SkeletonStore()
    private nonisolated let log: @Sendable (String) -> Void

    /// Which analyses are in flight.
    ///
    /// Tracked apart from `states` because the state is what the UI reads and it
    /// moves through preparing, analysing and cutting; "is anything running" is
    /// a different question, and the idle timer's lifetime depends on getting it
    /// right rather than on matching a particular state.
    private var running: Set<String> = []

    /// Whether the app is in the background, and so whether a run in flight is
    /// getting any CPU.
    ///
    /// Presentational only. There is nothing here to cancel: the pass is a
    /// synchronous loop over a decoder on a dispatch queue, and the system
    /// freezes and thaws it with the rest of the process. This flag decides
    /// which of two words a row uses for the same live run.
    private var isBackgrounded = false

    init(log: @escaping @Sendable (String) -> Void = { _ in }) {
        self.log = log
    }

    func state(for entryId: String) -> LocalAnalysisState { states[entryId] ?? .idle }

    /// Every run the chrome indicator should show.
    var deviceWork: [DeviceWork] {
        states.compactMap { toDeviceWork(entryId: $0.key, state: $0.value) }
    }

    /// Whether anything is running.
    ///
    /// Read by `RootView`, which owns `isIdleTimerDisabled` for the whole app.
    /// Not set here: an upload already drives that same global flag, and two
    /// writers means whichever finishes first turns the screen lock back on
    /// under the other one - which for a 20-minute analysis is the run dying.
    var isRunning: Bool { !running.isEmpty }

    // MARK: - Stored results

    // All `nonisolated`: every one of these reads a file through a stateless
    // store and touches nothing this actor owns, and the skeleton panel measures
    // 6,000 poses off the main thread before it can draw anything. Isolating
    // them would put that parse and that pass back on the main actor, which is
    // the defect `analyse` was already fixed for once.

    /// The track from an earlier run, for a screen opened after this one died.
    nonisolated func storedTrack(entryId: String) -> PlayerTrackStore.Stored? { tracks.load(entryId: entryId) }

    /// Whether `storedTrack` has anything to return, without loading it. For a
    /// list deciding what each of its rows can do.
    nonisolated func hasStoredTrack(entryId: String) -> Bool { tracks.has(entryId: entryId) }

    /// The clips from an earlier run, for the same reason.
    nonisolated func storedClips(entryId: String) -> [PlayerTrackStore.Clip] { tracks.loadClips(entryId: entryId) }

    nonisolated func storedSkeleton(entryId: String) -> SkeletonStore.Stored? { skeletons.load(entryId: entryId) }

    /// Header-only, for a screen deciding whether it has a second renderer to
    /// offer.
    nonisolated func hasStoredSkeleton(entryId: String) -> Bool { skeletons.has(entryId: entryId) }

    /// Every entry with a track this app can draw.
    ///
    /// The list asks once for the whole list rather than once per row, matching
    /// Android's `storedTrackIds`: `has` is a small read, and a list of forty
    /// matches is forty of them on every recomposition otherwise.
    nonisolated func storedTrackIds(among entryIds: [String]) -> Set<String> {
        Set(entryIds.filter { tracks.has(entryId: $0) })
    }

    /// How many clips each entry has, for the rows that offer to play them.
    ///
    /// Asked for the whole list at once, for the same reason `storedTrackIds`
    /// is: androidApp asks `storedClips(id).size` per row, which parses an
    /// index - or lists a directory - once per row on every recomposition.
    /// Entries with none are absent rather than zero, so a caller reads
    /// "nothing to offer" as nil.
    nonisolated func storedClipCounts(among entryIds: [String]) -> [String: Int] {
        var counts: [String: Int] = [:]
        for id in entryIds {
            let count = tracks.loadClips(entryId: id).count
            if count > 0 { counts[id] = count }
        }
        return counts
    }

    /// The file the analysis actually decoded, if it is still there.
    ///
    /// A skeleton is indexed by the frames of THIS file, so a renderer that took
    /// the video from anywhere else would be drawing joints over frames they
    /// were not measured on. On iOS that reduces to the entry's own copy:
    /// importing moves the file into this app's container (`LocalVideoFiles`),
    /// so there is no second copy and no revocable grant - which is what
    /// androidApp's version of this has to reckon with and this one does not.
    /// Nil once the file is gone, in which case the skeleton has no video to sit
    /// on and the panel says so.
    nonisolated func analysedSource(relativePath: String) -> URL? {
        let url = LocalVideoFiles.resolve(relativePath: relativePath)
        return FileManager.default.isReadableFile(atPath: url.path) ? url : nil
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
            set(.failed(message: IosLocalInferenceEngine.Failure.noModels.localizedDescription), for: entryId)
            return
        }
        running.insert(entryId)
        set(.preparing(message: "Preparing video"), for: entryId)

        let wantsPose = metrics.contains { $0.needsPose }
        Task.detached(priority: .userInitiated) { [weak self] in
            let started = Date()
            do {
                let outcome = try await self?.analyse(
                    entryId: entryId, videoPath: videoPath, keypoints: keypoints,
                    metrics: metrics, wantsPose: wantsPose, started: started
                )
                _ = outcome
            } catch {
                await self?.finish(entryId: entryId, with: .failed(message: error.localizedDescription))
            }
            await self?.released(entryId)
        }
    }

    private func released(_ entryId: String) {
        running.remove(entryId)
    }

    private func finish(entryId: String, with state: LocalAnalysisState) {
        set(state, for: entryId)
    }

    /// Called by the app when the scene goes to the BACKGROUND.
    ///
    /// Not `.inactive`: an app switcher glance, a control centre pull and a
    /// notification banner all make a scene inactive while it keeps running at
    /// full speed, and telling a coach his analysis stopped because he swiped
    /// down would be false.
    ///
    /// Repaints the rows itself rather than waiting for the next progress
    /// callback, which will not arrive at all once the process is frozen. The
    /// app switcher's card is a real audience for that: iOS snapshots the scene
    /// on the way out, so a stale "Analyzing on device 42%" would sit there for
    /// as long as the coach is away.
    ///
    /// All three working states, not `.analysing` alone. The words a frozen
    /// `.cutting` row shows would still be true - "Cutting clips 3 of 12" is a
    /// position, not a rate - but its spinner would not: a ring turning on the
    /// switcher's card over a run getting no CPU says the opposite of the line
    /// beside it.
    func suspendForBackground() {
        isBackgrounded = true
        guard isRunning else { return }
        log("local analysis: the app left the foreground, the run stops until it is back")
        for (entryId, state) in states {
            switch state {
            case .preparing, .analysing, .cutting:
                set(.paused(state), for: entryId)
            // Outcomes, and a pause already recorded. Nothing here is work that
            // could have stopped.
            case .idle, .done, .failed, .paused:
                break
            }
        }
    }

    /// Called by the app when the scene becomes active again.
    ///
    /// Repaints for the same reason, in the other direction: the pass thaws
    /// mid-frame and its next callback can be a second away, which on a row
    /// still reading "Paused" looks like a run that did not come back.
    func resumeFromBackground() {
        isBackgrounded = false
        for (entryId, state) in states {
            if case .paused(let interrupted) = state { set(interrupted, for: entryId) }
        }
    }

    /// `nonisolated`, and deliberately so.
    ///
    /// Every state write in here already hops to the main actor explicitly. What
    /// does not, and must not, is the work between them: `tracks.save`, the
    /// skeleton write and above all `ClipCutter.cut`, which re-encodes every
    /// rally and blocks its thread for as long as that takes. Isolated to the
    /// main actor - which is what a method of a `@MainActor` class is unless it
    /// says otherwise - those froze the UI for the length of the cut.
    private nonisolated func analyse(
        entryId: String,
        videoPath: String,
        keypoints: CourtKeypoints,
        metrics: Set<AnalysisMetric>,
        wantsPose: Bool,
        started: Date
    ) async throws {
        let source = URL(fileURLWithPath: videoPath)
        await MainActor.run { set(.analysing(fraction: 0), for: entryId) }

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
                Task { @MainActor [weak self] in
                    guard let self else { return }
                    // One assignment, two words for it: a run in the background
                    // is still moving through frames whenever the system lets
                    // it, and it is the app's own state - not the run's - that
                    // decides which of them a row shows.
                    self.set(
                        self.isBackgrounded
                            ? .paused(.analysing(fraction: value))
                            : .analysing(fraction: value),
                        for: entryId
                    )
                }
            }
        )

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
        await MainActor.run { set(.cutting(done: 0, total: windows.count), for: entryId) }
        var clips: [PlayerTrackStore.Clip] = []
        if !windows.isEmpty {
            let directory = try AnalysisFiles.directory("local-clips/\(entryId)")
            clips = try ClipCutter().cut(source: source, windows: windows, into: directory) { done in
                Task { @MainActor [weak self] in
                    self?.set(.cutting(done: done, total: windows.count), for: entryId)
                }
            }
            try tracks.saveClips(entryId: entryId, clips: clips)
        }

        let elapsed = Date().timeIntervalSince(started)
        await MainActor.run {
            set(.done(LocalAnalysisState.Done(
                rallies: result.result.rallies.count,
                shuttleVisible: result.result.shuttlePositions.values.filter(\.visible).count,
                totalFrames: Int(result.result.totalFrames),
                clips: clips,
                elapsedSeconds: elapsed,
                playerTrack: result.playerTrack,
                fps: result.result.fps
            )), for: entryId)
        }
        log("local analysis done: \(clips.count) clips in \(Int(elapsed))s")
    }

    func clear(entryId: String) { set(nil, for: entryId) }
}
