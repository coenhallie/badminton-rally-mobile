import Shared
import XCTest
@testable import iosApp

/// The local player's loops, held to what `LocalPlayerView`'s `.task` does to
/// them.
///
/// The defect these guard against lived in the view, where no unit test can
/// reach it: the loops sat behind `guard model == nil`, so the first
/// cancellation - pushing court marking - was also the last time they ran. They
/// live on the model now, which is what makes them testable at all.
///
/// A real `RallyApp`, for the reason `ClipListModelTests` gives. Only
/// `observe()` is driven, so nothing reaches the network, and the video file
/// does not exist - nothing here plays it.
@MainActor
final class LocalPlayerModelTests: XCTestCase {

    private var rally: RallyApp!
    private var entryId: String!

    override func setUp() {
        rally = RallyAppIosKt.createRallyApp(
            url: "https://test.supabase.co",
            anonKey: "test-anon-key",
            deleteLocalVideoFile: { _ in },
            deleteLocalAnalysis: { _, _ in }
        )
        // Unique, and cleared again below: the notes store is the simulator's
        // real NSUserDefaults, shared with every other test that builds a RallyApp.
        entryId = "local-player-\(UUID().uuidString)"
    }

    override func tearDown() {
        rally.localAnnotations.removeAllFor(videoId: entryId)
    }

    private func model() -> LocalPlayerModel {
        LocalPlayerModel(rally: rally, entry: LocalVideoEntry(
            id: entryId, uri: "LocalVideos/\(entryId!).mp4", displayName: "m.mp4",
            durationMs: 1000, sizeBytes: 2, addedAtEpochMs: 1,
            title: nil, description: nil, keypoints: nil,
            stage: .local, failedStep: nil, failureMessage: nil, resultSeen: false,
            scoreLogId: nil
        ))
    }

    func testComingBackFromCourtMarkingFollowsTheNotesAgain() async {
        let m = model()
        let returned = await ObservationLifecycle.runAndCancel { await m.observe() }
        XCTAssertTrue(returned, "observe() did not return after its task was cancelled")

        let again = Task { await m.observe() }
        defer { again.cancel() }
        // Straight to the repository rather than through `m.add`, so only the
        // loop can have carried it across.
        _ = rally.localAnnotations.add(videoId: entryId, timestampSeconds: 3, body: "late to the net", label: nil)

        let noticed = await ObservationLifecycle.wait { m.annotations.map(\.body) == ["late to the net"] }
        XCTAssertTrue(noticed, "the second observe() is not following the notes flow")
    }

    func testLeavingThePlayerStopsEveryLoopItStarted() async {
        // See `MatchModelTests` for why a released model is the probe.
        var m: LocalPlayerModel? = model()
        weak var released = m

        let returned = await ObservationLifecycle.runAndCancel { [m] in await m?.observe() }
        XCTAssertTrue(returned, "observe() did not return after its task was cancelled")

        m = nil
        let gone = await ObservationLifecycle.wait { released == nil }
        XCTAssertTrue(gone, "a loop observe() spawned is still running and still holds the model")
    }
}
