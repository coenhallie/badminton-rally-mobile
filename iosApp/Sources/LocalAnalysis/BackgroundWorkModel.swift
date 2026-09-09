import Shared
import SwiftUI

/// What the app is working on, for the chrome indicator.
/// Port of Android's `BackgroundWorkMonitor`.
///
/// App-scoped rather than owned by a screen: a run outlives every screen that
/// can show it, and the indicator appears on most of them.
///
/// It only assembles inputs. What they mean is `backgroundWork` in `shared`,
/// where androidApp reads the same rules and where they are tested without a
/// device.
///
/// The device half is not held here at all - it is read straight off the runner
/// in `work`, so the ring follows a run without this class subscribing to
/// anything. The cloud half has to be held, because a failure is a TRANSITION
/// between two readings of the entry list rather than a state on it; see
/// `failureTransitions`.
@Observable @MainActor
final class BackgroundWorkModel {

    /// The two streams and the runner, rather than `RallyApp` and the
    /// coordinator wholesale: this needs nothing else from either, and depending
    /// on the app object would drag a Supabase client into a class whose entire
    /// job is a fold over three sources. androidApp's monitor takes flows for the
    /// same reason.
    private let localVideos: LocalVideoRepository
    private let analyze: AnalyzeCoordinator
    private let runner: LocalAnalysisRunner?

    private(set) var entries: [LocalVideoEntry] = []
    private(set) var progress: [String: AnalyzeProgress] = [:]
    /// Cloud failures seen while THIS process has been running.
    ///
    /// A persisted `stage == FAILED` deliberately does not count - it survives
    /// restarts and is cleared only by a retry, so badging it would light the
    /// chrome forever for a video that failed last week. `backgroundWork`'s own
    /// note is the long form.
    private(set) var cloudFailures: Set<String> = []

    /// The two loops, held for the model's own lifetime.
    ///
    /// Started in `init` rather than from a view's `.task`, and that is not a
    /// style choice. The only view that could own them is the scope, which lives
    /// inside `RootView`'s authenticated branch: signing out takes that branch
    /// away, cancels the task and unwinds both loops, and signing back in
    /// re-creates a scope around the SAME model - whose entries and progress
    /// would then sit frozen at the moment of sign-out for the rest of the
    /// session. The device half would go on working, because `work` reads the
    /// runner live, so the symptom would be a ring that had quietly stopped
    /// noticing cloud runs. androidApp's monitor launches into an
    /// application-scoped `CoroutineScope` in its own `init` for the same reason.
    ///
    /// Not cancelled anywhere, and not leaked either: exactly one of these is
    /// made, `RootView` holds it for the life of the process, and `deinit` on a
    /// `@MainActor` class cannot reach an isolated property to cancel it.
    private var loops: Task<Void, Never>?

    init(
        localVideos: LocalVideoRepository,
        analyze: AnalyzeCoordinator,
        runner: LocalAnalysisRunner?
    ) {
        self.localVideos = localVideos
        self.analyze = analyze
        self.runner = runner
        // Seeded rather than left empty until the first emission: a cloud run
        // already in flight when the app launches must light the ring on the
        // first frame, not on its next progress callback.
        self.entries = localVideos.entries.value
        loops = Task { [weak self] in await self?.observe() }
    }


    /// What the indicator renders, or nil when nothing is running and nothing
    /// has failed.
    ///
    /// Computed rather than stored, and that is what makes the ring live: reading
    /// it inside a view body tracks `runner.states` through `deviceWork`, so a
    /// progress callback moves the arc without this class observing the runner.
    ///
    /// Device failures are read rather than accumulated, unlike the cloud's: the
    /// runner replaces a `.failed` state the moment a retry starts, so the set
    /// clears itself.
    var work: BackgroundWork? {
        let device = runner?.deviceWork ?? []
        let deviceFailures = Set(device.filter(\.failed).map(\.entryId))
        return BackgroundWorkKt.backgroundWork(
            entries: entries,
            progress: progress,
            device: device,
            // Kept apart until here so neither source can clear the other's
            // badge: the same video can have failed in the cloud and be running
            // on the device.
            sessionFailures: cloudFailures.union(deviceFailures)
        )
    }

    private func observe() async {
        await withTaskGroup(of: Void.self) { group in
            group.addTask { @MainActor [weak self] in
                guard let self else { return }
                // Only the previous map is held here. The diff is
                // `failureTransitions` in shared, because holding a map across
                // emissions is observation machinery and the diff is not.
                var seen: [String: AnalyzeStage] = [:]
                for await current in self.localVideos.entries {
                    self.entries = current
                    var stages: [String: AnalyzeStage] = [:]
                    for entry in current { stages[entry.id] = entry.stage }
                    let transitions = BackgroundWorkKt.failureTransitions(seen: seen, now: stages)
                    seen = stages
                    if !transitions.failed.isEmpty || !transitions.resolved.isEmpty {
                        self.cloudFailures = self.cloudFailures
                            .union(transitions.failed)
                            .subtracting(transitions.resolved)
                    }
                }
            }
            group.addTask { @MainActor [weak self] in
                guard let self else { return }
                for await map in self.analyze.progress { self.progress = map }
            }
        }
    }
}

private struct BackgroundWorkKey: EnvironmentKey {
    static let defaultValue: BackgroundWork? = nil
}

private struct BackgroundWorkClickKey: EnvironmentKey {
    static let defaultValue: () -> Void = {}
}

extension EnvironmentValues {
    /// Ambient background-work state, provided once around the navigation stack.
    /// Port of Android's `LocalBackgroundWork`.
    ///
    /// The environment rather than a parameter on eight screen signatures. This
    /// is app chrome that any screen may show and none of them own, and
    /// threading it through would put a progress concern into the signature of
    /// every screen that happens to have a bar.
    var backgroundWork: BackgroundWork? {
        get { self[BackgroundWorkKey.self] }
        set { self[BackgroundWorkKey.self] = newValue }
    }

    /// Where the indicator sends you: the one list that already shows runs in
    /// detail. Port of Android's `LocalBackgroundWorkClick`.
    var backgroundWorkClick: () -> Void {
        get { self[BackgroundWorkClickKey.self] }
        set { self[BackgroundWorkClickKey.self] = newValue }
    }
}

/// Puts `work` into the environment for everything inside it.
///
/// Its own view, and a small one, on purpose: reading `model.work` establishes
/// the observation that redraws on every progress callback, and whatever reads
/// it re-evaluates thirty times a second on a fast video. Here that is this
/// wrapper's body and nothing else - the content was already built by the caller,
/// so only the views that actually READ the environment key follow.
struct BackgroundWorkScope<Content: View>: View {
    let model: BackgroundWorkModel
    @ViewBuilder let content: Content

    var body: some View {
        content.environment(\.backgroundWork, model.work)
    }
}
