import XCTest
import Shared
@testable import iosApp

/// The parity check. These are `MatchViewModelTest`'s cases, name for name: if
/// the two lists diverge, the two surfaces have diverged.
///
/// The first six need no dispatcher: `MatchModel` reads the store with
/// `ScoreLogsRepository.get(id:)`, a direct cache lookup rather than a flow, so a
/// mutation made before construction is visible the moment `init` runs. The last
/// drives its mutation through `start()`'s `for await` loop instead - the path
/// `MatchView`'s `.task` actually runs - because that loop, not a direct read, is
/// what the app relies on to notice a match removed elsewhere.
@MainActor
final class MatchModelTests: XCTestCase {

    // 2026-08-28T18:00:00Z. Fixed, because the store stamps created_at from it.
    private let t0 = KotlinInstant.companion.fromEpochMilliseconds(epochMilliseconds: 1_787_940_000_000)

    private func store() -> ScoreLogsRepository {
        SwiftInteropKt.testScoreLogsRepository(now: t0, ownerId: "owner-1")
    }

    private func newMatch(_ store: ScoreLogsRepository) -> ScoreLog {
        store.create(
            title: "Thu League",
            homePlayers: ["Coen"],
            awayPlayers: ["Marco"],
            rules: ScoringRules.companion.BWF_21,
            setup: MatchSetup(doubles: false, firstServer: .home, homeStartsRight: .first, awayStartsRight: .first)
        )
    }

    /// Nothing under test here exercises the attach pipeline itself, so a fresh
    /// `testLocalVideoRepository()` (empty, or carrying one hand-added entry) and
    /// a coordinator wired to test doubles is enough to satisfy the initializer.
    private func model(
        scoreLogs: ScoreLogsRepository,
        scoreLogId: String?,
        localVideos: LocalVideoRepository = IosTestDoublesKt.testLocalVideoRepository()
    ) -> MatchModel {
        let clips = IosTestDoublesKt.testClipsRepository()
        let analyze = IosTestDoublesKt.testAnalyzeCoordinator(localVideos: localVideos, clips: clips)
        return MatchModel(scoreLogs: scoreLogs, localVideos: localVideos, analyze: analyze, clips: clips, scoreLogId: scoreLogId)
    }

    func testAMatchStillBeingScoredCannotTakeAVideoYet() {
        // Its action is Score/Resume. Offering both would put "add the video"
        // beside a match that has not been played, and would need .live and
        // .bound to mean something together.
        let store = store()
        let log = newMatch(store)
        let m = model(scoreLogs: store, scoreLogId: log.id)
        XCTAssertFalse(m.canAddVideo)
    }

    func testAMatchTheRulesHaveEndedCanTakeAVideoEvenWhileStillMarkedLive() {
        let store = store()
        let log = newMatch(store)
        store.replaceEvents(id: log.id, events: Array(repeating: ScoreEventPointTo(side: .home), count: 42))
        let m = model(scoreLogs: store, scoreLogId: log.id)
        XCTAssertTrue(m.canAddVideo)
    }

    func testAMatchEndedByHandCanTakeAVideo() {
        let store = store()
        let log = newMatch(store)
        store.finish(id: log.id)
        let m = model(scoreLogs: store, scoreLogId: log.id)
        XCTAssertTrue(m.canAddVideo)
    }

    func testAMatchThatAlreadyHasAVideoIsNotOfferedAnotherOne() {
        // Replacing means removing the first, which is the delete gesture, not a
        // second picker on the same page.
        let store = store()
        let log = newMatch(store)
        store.finish(id: log.id)
        store.attachVideo(id: log.id, videoId: "vid-1")
        let m = model(scoreLogs: store, scoreLogId: log.id)
        XCTAssertFalse(m.canAddVideo)
    }

    func testAMatchWhoseVideoIsStillBeingPickedUpIsNotOfferedAnotherOne() {
        // Wired through localVideos: an entry can exist for a while before the
        // pipeline gives the match a videoId (LOCAL, UPLOADING, PROCESSING).
        // Gating canAddVideo on videoId alone would let the picker reopen while
        // an attach for this same match is already in flight.
        let store = store()
        let log = newMatch(store)
        store.finish(id: log.id)
        let videos = IosTestDoublesKt.testLocalVideoRepository()
        videos.add(entry: LocalVideoEntry(
            id: "e1", uri: "content://x/e1", displayName: "m.mp4",
            durationMs: 1000, sizeBytes: 10, addedAtEpochMs: 0,
            title: nil, description: nil, keypoints: nil,
            stage: .local, failedStep: nil, failureMessage: nil, resultSeen: false,
            scoreLogId: log.id
        ))
        let m = model(scoreLogs: store, scoreLogId: log.id, localVideos: videos)
        XCTAssertFalse(m.canAddVideo)
    }

    func testTheMatchPageShowsTheSameStatusTheListRowWould() {
        let store = store()
        let log = newMatch(store)
        store.finish(id: log.id)
        let videos = IosTestDoublesKt.testLocalVideoRepository()
        videos.add(entry: LocalVideoEntry(
            id: "e1", uri: "content://x/e1", displayName: "m.mp4",
            durationMs: 1000, sizeBytes: 10, addedAtEpochMs: 0,
            title: nil, description: nil, keypoints: nil,
            stage: .local, failedStep: nil, failureMessage: nil, resultSeen: false,
            scoreLogId: log.id
        ))
        let m = model(scoreLogs: store, scoreLogId: log.id, localVideos: videos)
        XCTAssertEqual(m.attach?.kind, .courtNotMarked)
    }

    func testADeletedMatchLeavesThePageInertRatherThanCrashing() async throws {
        let store = store()
        let log = newMatch(store)
        let m = model(scoreLogs: store, scoreLogId: log.id)

        // Drives the mutation through the same `for await` loop `MatchView`'s
        // `.task` runs, not `reload()` - this is the path that must actually
        // notice a match removed elsewhere.
        let collecting = Task { await m.start() }
        defer { collecting.cancel() }

        store.removeLocally(id: log.id)

        // `logs` is a StateFlow: it always conflates to its latest value, so this
        // is not racing the mutation against the loop's subscription - whichever
        // happens first, the loop's next delivered value already reflects the
        // removal. What it is waiting on is the Kotlin coroutine that bridges that
        // StateFlow into this Swift `for await` actually getting scheduled, which
        // this test does not control directly, hence the poll instead of a bare
        // assertion right after `removeLocally`.
        let deadline = Date().addingTimeInterval(5)
        while m.log != nil && Date() < deadline {
            try await Task.sleep(nanoseconds: 20_000_000)
        }

        XCTAssertNil(m.log)
        XCTAssertFalse(m.canAddVideo)
    }
}
