import Shared
import XCTest
@testable import iosApp

/// The match list's loops, held to what `ClipListView`'s `.task` does to them.
///
/// Built on a real `RallyApp`, because `ClipListModel` takes one and shared has
/// no doubles for the auth and shares repositories it reads. Nothing here
/// reaches the network: the model is only ever asked to observe, and every
/// flow it observes is a local cache.
@MainActor
final class ClipListModelTests: XCTestCase {

    private var rally: RallyApp!
    private var entryId: String!

    override func setUp() {
        rally = RallyAppIosKt.createRallyApp(
            url: "https://test.supabase.co",
            anonKey: "test-anon-key",
            deleteLocalVideoFile: { _ in },
            deleteLocalAnalysis: { _, _ in }
        )
        // Unique, and removed again below: this registry is the simulator's real
        // NSUserDefaults, shared with every other test that builds a RallyApp.
        entryId = "clip-list-\(UUID().uuidString)"
    }

    override func tearDown() {
        rally.localVideos.remove(id: entryId)
    }

    private func model() -> ClipListModel {
        // The coordinator double, wired to this app's own stores: nothing here
        // runs the pipeline, and the real factory wants an upload channel.
        ClipListModel(
            rally: rally,
            analyze: IosTestDoublesKt.testAnalyzeCoordinator(localVideos: rally.localVideos, clips: rally.clips)
        )
    }

    private func entry() -> LocalVideoEntry {
        LocalVideoEntry(
            id: entryId, uri: "LocalVideos/\(entryId!).mp4", displayName: "m.mp4",
            durationMs: 1000, sizeBytes: 2, addedAtEpochMs: 1,
            title: nil, description: nil, keypoints: nil,
            stage: .local, failedStep: nil, failureMessage: nil, resultSeen: false,
            scoreLogId: nil
        )
    }

    func testComingBackToTheListStartsObservingAgain() async {
        // The reported defect. Opening a match cancels the list's `.task`, which
        // ended the one loop start() ran inline - the clips one. Coming back ran
        // start() again, and a `started` latch made that a no-op, so the list
        // never followed the clips flow again for the life of the view.
        let m = model()
        let returned = await ObservationLifecycle.runAndCancel { await m.observe() }
        XCTAssertTrue(returned, "observe() did not return after its task was cancelled")

        let stillRunning = ObservationLifecycle.Flag()
        let again = Task { @MainActor in
            await m.observe()
            stillRunning.set()
        }
        defer { again.cancel() }
        try? await Task.sleep(nanoseconds: 300_000_000)
        XCTAssertFalse(stillRunning.isSet, "the second observe() returned at once instead of observing")

        rally.localVideos.add(entry: entry())
        let noticed = await ObservationLifecycle.wait { m.localEntries.contains { $0.id == self.entryId } }
        XCTAssertTrue(noticed, "the second observe() is not following the entries flow")
    }

    func testLeavingTheListStopsEveryLoopItStarted() async {
        // Three of the four loops were unstructured `Task {}`s that nothing ever
        // cancelled. See `MatchModelTests` for why a released model is the probe.
        var m: ClipListModel? = model()
        weak var released = m

        let returned = await ObservationLifecycle.runAndCancel { [m] in await m?.observe() }
        XCTAssertTrue(returned, "observe() did not return after its task was cancelled")

        m = nil
        let gone = await ObservationLifecycle.wait { released == nil }
        XCTAssertTrue(gone, "a loop observe() spawned is still running and still holds the model")
    }
}
