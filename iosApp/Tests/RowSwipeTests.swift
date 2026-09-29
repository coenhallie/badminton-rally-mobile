import Shared
import XCTest
@testable import iosApp

/// `RowSwipe`'s three outcomes: a two-way choice, a single confirmation, and
/// no swipe at all; and `DeleteEverywhere`, what a row's delete everywhere
/// calls. Built through `buildMatchList`, the same fixtures
/// `MatchListBridgingTests` uses, so a case here cannot assert a combination
/// the real join could never produce. Mirrors Android's RowGesturesTest.
final class RowSwipeTests: XCTestCase {

    private func entry(id: String, stage: AnalyzeStage = .local, scoreLogId: String? = nil) -> LocalVideoEntry {
        LocalVideoEntry(
            id: id, uri: "LocalVideos/\(id).mp4", displayName: "m.mp4",
            durationMs: 48_000, sizeBytes: 2, addedAtEpochMs: 1_787_940_000_000,
            title: nil, description: nil, keypoints: nil,
            stage: stage, failedStep: nil, failureMessage: nil, resultSeen: false,
            scoreLogId: scoreLogId
        )
    }

    private func log(id: String, videoId: String? = nil) -> ScoreLog {
        let at = KotlinInstant.companion.fromEpochMilliseconds(epochMilliseconds: 1_787_940_000_000)
        return ScoreLog(
            id: id, videoId: videoId, title: "Thu League",
            homePlayers: [], awayPlayers: [], format: nil,
            rules: ScoringRules.companion.BWF_21, setup: MatchSetup(firstServer: .home),
            events: [], status: videoId != nil ? .bound : .unbound,
            createdAt: at, updatedAt: at
        )
    }

    private func clip(_ index: Int32, videoId: String, owner: String) -> RallyClip {
        RallyClip(
            id: "\(videoId)-c\(index)", videoId: videoId, ownerId: owner,
            rallyIndex: index, startTimestamp: 0, endTimestamp: 5, durationSeconds: 5,
            clipStoragePath: "\(owner)/\(videoId)/rally_\(index).mp4",
            thumbnailStoragePath: nil, title: nil, annotationCount: 0,
            createdAt: KotlinInstant.companion.fromEpochMilliseconds(epochMilliseconds: 1_787_950_000_000)
        )
    }

    private func item(
        clips: [RallyClip] = [], entries: [LocalVideoEntry] = [], logs: [ScoreLog] = [],
        currentUserId: String = "user-self"
    ) -> MatchListItem {
        let input = MatchListInput(
            clips: clips, currentUserId: currentUserId,
            sharerByVideoId: [:], metadataByVideoId: [:], scoreLogs: logs,
            localEntries: entries, cloudProgress: [:], deviceRuns: [:],
            storedTrackIds: [], storedSkeletonIds: [],
            deviceClipCounts: [:],
            localNoteCounts: [:]
        )
        let list = MatchListBuilderKt.buildMatchList(input: input)
        return (list.mine + list.shared)[0]
    }

    func testAMatchOnThisPhoneAndInTheCloudOffersTheTwoWayChoice() {
        // Both a file to keep and a match to delete: the coach picks.
        let match = item(
            clips: [clip(1, videoId: "v1", owner: "user-self")],
            entries: [entry(id: "v1", stage: .analyzed)]
        )
        XCTAssertEqual(match.delete, .videoMatch)
        XCTAssertTrue(match.canRemoveFromPhone)
        XCTAssertEqual(RowSwipe(match), .choose)
    }

    func testAMatchOnlyInTheCloudGoesStraightToTheSingleDelete() {
        // No local file to offer keeping, so there is nothing to choose between.
        let match = item(clips: [clip(1, videoId: "v1", owner: "user-self")])
        XCTAssertEqual(match.delete, .videoMatch)
        XCTAssertFalse(match.canRemoveFromPhone)
        XCTAssertEqual(RowSwipe(match), .delete)
    }

    func testASharedMatchLeavesRatherThanDeletes() {
        let match = item(clips: [clip(1, videoId: "v1", owner: "someone-else")])
        XCTAssertEqual(match.delete, .leaveShare)
        XCTAssertEqual(RowSwipe(match), .leave)
        XCTAssertEqual(RowSwipe(match)?.label, MatchListCopy.shared.SWIPE_REMOVE)
    }

    func testAVideoOnThisPhoneWithNothingInTheCloudOffersRemoveOnly() {
        let match = item(entries: [entry(id: "e1", stage: .local)])
        XCTAssertEqual(match.delete, .none)
        XCTAssertTrue(match.canRemoveFromPhone)
        XCTAssertEqual(RowSwipe(match), .remove)
        XCTAssertEqual(RowSwipe(match)?.label, MatchListCopy.shared.SWIPE_REMOVE)
    }

    func testARunInFlightOffersNoSwipeAtAll() {
        // The file is held by a running device run, and the match has nothing
        // else to delete: nothing here may be swiped away.
        let run = LocalVideoStatus.deviceRun(for: .analysing(fraction: 0.3))!
        let input = MatchListInput(
            clips: [], currentUserId: "user-self",
            sharerByVideoId: [:], metadataByVideoId: [:], scoreLogs: [],
            localEntries: [entry(id: "e1", stage: .local)], cloudProgress: [:],
            deviceRuns: ["e1": run], storedTrackIds: [], storedSkeletonIds: [],
            deviceClipCounts: [:],
            localNoteCounts: [:]
        )
        let match = MatchListBuilderKt.buildMatchList(input: input).mine[0]
        XCTAssertEqual(match.delete, .none)
        XCTAssertFalse(match.canRemoveFromPhone)
        XCTAssertNil(RowSwipe(match))
    }

    // MARK: - Delete everywhere hands over the row's own entry

    func testAVideoMatchOnThisPhoneDeletesWithItsEntry() {
        let match = item(
            clips: [clip(1, videoId: "v1", owner: "user-self")],
            entries: [entry(id: "v1", stage: .analyzed)]
        )
        XCTAssertEqual(DeleteEverywhere(match), .videoMatch(videoId: "v1", entryId: "v1"))
    }

    func testAScoredMatchDeletesWithItsEntry() {
        let match = item(entries: [entry(id: "e1", scoreLogId: "l1")], logs: [log(id: "l1")])
        XCTAssertEqual(DeleteEverywhere(match), .scoreMatch(scoreLogId: "l1", entryId: "e1"))
    }

    func testABoundMatchDeletesWithTheEntryTheListJoinedByVideoId() {
        // A lookup by scoreLogId would miss this entry, which names no log,
        // and leave the file behind as a phone-only row of a deleted match.
        let match = item(
            clips: [clip(1, videoId: "v1", owner: "user-self")],
            entries: [entry(id: "v1", stage: .analyzed)],
            logs: [log(id: "l1", videoId: "v1")]
        )
        XCTAssertEqual(DeleteEverywhere(match), .boundMatch(videoId: "v1", scoreLogId: "l1", entryId: "v1"))
    }

    func testASharedMatchIsLeftAndAMatchWithNothingToDeleteDoesNothing() {
        XCTAssertEqual(
            DeleteEverywhere(item(clips: [clip(1, videoId: "v1", owner: "someone-else")])),
            .leaveShare(videoId: "v1")
        )
        XCTAssertNil(DeleteEverywhere(item(entries: [entry(id: "e1")])))
    }
}
