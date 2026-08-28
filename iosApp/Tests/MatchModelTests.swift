import XCTest
import Shared
@testable import iosApp

/// The parity check. These are `MatchViewModelTest`'s cases, name for name: if
/// the two lists diverge, the two surfaces have diverged.
///
/// The first four need no dispatcher: `MatchModel` reads the store with
/// `ScoreLogsRepository.get(id:)`, a direct cache lookup rather than a flow, so a
/// mutation made before construction is visible the moment `init` runs. The fifth
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

    func testAMatchStillBeingScoredCannotTakeAVideoYet() {
        // Its action is Score/Resume. Offering both would put "add the video"
        // beside a match that has not been played, and would need .live and
        // .bound to mean something together.
        let store = store()
        let log = newMatch(store)
        let model = MatchModel(scoreLogs: store, scoreLogId: log.id)
        XCTAssertFalse(model.canAddVideo)
    }

    func testAMatchTheRulesHaveEndedCanTakeAVideoEvenWhileStillMarkedLive() {
        let store = store()
        let log = newMatch(store)
        store.replaceEvents(id: log.id, events: Array(repeating: ScoreEventPointTo(side: .home), count: 42))
        let model = MatchModel(scoreLogs: store, scoreLogId: log.id)
        XCTAssertTrue(model.canAddVideo)
    }

    func testAMatchEndedByHandCanTakeAVideo() {
        let store = store()
        let log = newMatch(store)
        store.finish(id: log.id)
        let model = MatchModel(scoreLogs: store, scoreLogId: log.id)
        XCTAssertTrue(model.canAddVideo)
    }

    func testAMatchThatAlreadyHasAVideoIsNotOfferedAnotherOne() {
        // Replacing means removing the first, which is the delete gesture, not a
        // second picker on the same page.
        let store = store()
        let log = newMatch(store)
        store.finish(id: log.id)
        store.attachVideo(id: log.id, videoId: "vid-1")
        let model = MatchModel(scoreLogs: store, scoreLogId: log.id)
        XCTAssertFalse(model.canAddVideo)
    }

    func testADeletedMatchLeavesThePageInertRatherThanCrashing() async throws {
        let store = store()
        let log = newMatch(store)
        let model = MatchModel(scoreLogs: store, scoreLogId: log.id)

        // Drives the mutation through the same `for await` loop `MatchView`'s
        // `.task` runs, not `reload()` - this is the path that must actually
        // notice a match removed elsewhere.
        let collecting = Task { await model.start() }
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
        while model.log != nil && Date() < deadline {
            try await Task.sleep(nanoseconds: 20_000_000)
        }

        XCTAssertNil(model.log)
        XCTAssertFalse(model.canAddVideo)
    }
}
