import Shared
import XCTest
@testable import iosApp

/// The shared match list as Swift reaches it: the builder's input built from
/// Swift values, the item's fields read back through SKIE's spelling of them
/// (`where` is a Swift keyword and needs backticks), and this
/// platform's device-run state reduced to the list's.
///
/// The builder's own rules are pinned in shared's MatchListBuilderTest; this
/// only pins the shape the match list compiles against.
final class MatchListBridgingTests: XCTestCase {

    private func entry(id: String, stage: AnalyzeStage) -> LocalVideoEntry {
        LocalVideoEntry(
            id: id, uri: "LocalVideos/\(id).mp4", displayName: "m.mp4",
            durationMs: 48_000, sizeBytes: 2, addedAtEpochMs: 1_787_940_000_000,
            title: nil, description: nil, keypoints: nil,
            stage: stage, failedStep: nil, failureMessage: nil, resultSeen: false,
            scoreLogId: nil
        )
    }

    private func clip(_ index: Int32, videoId: String, owner: String, notes: Int32 = 0) -> RallyClip {
        RallyClip(
            id: "\(videoId)-c\(index)", videoId: videoId, ownerId: owner,
            rallyIndex: index, startTimestamp: 0, endTimestamp: 5, durationSeconds: 5,
            clipStoragePath: "\(owner)/\(videoId)/rally_\(index).mp4",
            thumbnailStoragePath: nil, title: nil, annotationCount: notes,
            createdAt: KotlinInstant.companion.fromEpochMilliseconds(epochMilliseconds: 1_787_950_000_000)
        )
    }

    private func input(
        clips: [RallyClip] = [],
        entries: [LocalVideoEntry] = [],
        deviceRuns: [String: DeviceRun] = [:],
        tracks: Set<String> = [],
        skeletons: Set<String> = [],
        clipCounts: [String: KotlinInt] = [:],
        localNotes: [String: KotlinInt] = [:]
    ) -> MatchListInput {
        MatchListInput(
            clips: clips, currentUserId: "user-self",
            sharerByVideoId: [:], metadataByVideoId: [:], scoreLogs: [],
            localEntries: entries, cloudProgress: [:], deviceRuns: deviceRuns,
            storedTrackIds: tracks, storedSkeletonIds: skeletons,
            deviceClipCounts: clipCounts,
            localNoteCounts: localNotes
        )
    }

    func testAnAnalyzedVideoAndItsCloudClipsAreOneRowOnBothSides() {
        // The duplicate this list exists to end: the entry's id became the
        // cloud video's id, and the two used to be two rows in two sections.
        let list = MatchListBuilderKt.buildMatchList(input: input(
            clips: [clip(1, videoId: "v1", owner: "user-self"), clip(2, videoId: "v1", owner: "user-self")],
            entries: [entry(id: "v1", stage: .analyzed)],
            tracks: ["v1"], skeletons: ["v1"]
        ))

        XCTAssertEqual(list.mine.count, 1)
        XCTAssertTrue(list.shared.isEmpty)
        let item = list.mine[0]
        XCTAssertEqual(item.`where`, .both)
        XCTAssertEqual(item.analyses, [.rallies, .heatmap, .skeleton])
        XCTAssertEqual(item.entryId, "v1")
        XCTAssertEqual(item.videoId, "v1")
        XCTAssertEqual(item.delete, .videoMatch)
        XCTAssertTrue(item.canRemoveFromPhone)
        XCTAssertEqual(item.action, .none)
        XCTAssertEqual(item.accessibilityStatus, "On this phone and in the cloud. Rallies, heatmap, skeleton.")
    }

    func testFiltersAndCountsCrossTheBridge() {
        let list = MatchListBuilderKt.buildMatchList(input: input(
            entries: [entry(id: "e1", stage: .local)],
            clipCounts: ["e1": KotlinInt(int: 3)]
        ))
        let item = list.mine[0]
        XCTAssertEqual(item.deviceClipCount, 3)
        XCTAssertTrue(item.passes(filter: .onThisPhone))
        XCTAssertFalse(MatchListFilterKt.showsFilters(mineCount: 5))
        XCTAssertEqual(MatchListFilterKt.effectiveFilter(chosen: .notAnalyzed, mineCount: 5), .all)
        XCTAssertEqual(MatchListFilterKt.effectiveFilter(chosen: .notAnalyzed, mineCount: 6), .notAnalyzed)
    }

    func testTheSortAndItsCountsCrossTheBridge() {
        // The order rules are pinned in shared's MatchListSortTest; this is
        // the shape Swift calls them in.
        let list = MatchListBuilderKt.buildMatchList(input: input(
            clips: [
                clip(1, videoId: "v1", owner: "user-self", notes: 4),
                clip(1, videoId: "v2", owner: "user-self"), clip(2, videoId: "v2", owner: "user-self"),
            ],
            entries: [entry(id: "e3", stage: .local)],
            clipCounts: ["e3": KotlinInt(int: 5)]
        ))
        let byKey = Dictionary(uniqueKeysWithValues: list.mine.map { ($0.key, $0) })
        XCTAssertEqual(byKey["video-v1"]?.noteCount, 4)
        XCTAssertEqual(byKey["video-v2"]?.rallyCount, 2)
        XCTAssertEqual(byKey["video-e3"]?.rallyCount, 5)

        let keys = { (sort: MatchListSort) in MatchListSortKt.sortMatches(items: list.mine, sort: sort).map(\.key) }
        XCTAssertEqual(keys(.mostRallies), ["video-e3", "video-v2", "video-v1"])
        XCTAssertEqual(keys(.mostNotes).first, "video-v1")
        XCTAssertEqual(MatchListSortKt.effectiveSort(chosen: .mostNotes, mineCount: 5), .newest)
        XCTAssertEqual(MatchListSortKt.effectiveSort(chosen: .mostNotes, mineCount: 6), .mostNotes)
        XCTAssertEqual(MatchListSort.allCases.map { MatchListCopy.shared.sortLabel(sort: $0) },
                       ["Newest", "Oldest", "Most rallies", "Most notes"])
    }

    func testARunningDeviceRunRaisesTheRowAndHoldsItsFile() {
        let run = LocalVideoStatus.deviceRun(for: .analysing(fraction: 0.4))!
        let list = MatchListBuilderKt.buildMatchList(input: input(
            entries: [entry(id: "e1", stage: .local)],
            deviceRuns: ["e1": run]
        ))
        let item = list.mine[0]
        XCTAssertEqual(item.action, .running)
        XCTAssertTrue(item.isRaised)
        XCTAssertFalse(item.canRemoveFromPhone)
    }

    // MARK: - This platform's run state, reduced

    func testIdleAndDoneAreNoRunAtAll() {
        XCTAssertNil(LocalVideoStatus.deviceRun(for: .idle))
        let done = LocalAnalysisState.Done(
            rallies: 1, shuttleVisible: 1, totalFrames: 1, clips: [], elapsedSeconds: 1,
            playerTrack: PlayerTrack(samples: [], framesWithPose: 0, rejections: [:]), fps: 30,
            skeletonSaved: false
        )
        XCTAssertNil(LocalVideoStatus.deviceRun(for: .done(done)))
    }

    func testEachWorkingStateIsARunningRunInItsPhase() {
        XCTAssertEqual(LocalVideoStatus.deviceRun(for: .preparing(message: "x")),
                       DeviceRun(kind: .running, phase: .preparing, message: nil))
        XCTAssertEqual(LocalVideoStatus.deviceRun(for: .analysing(fraction: 0.5)),
                       DeviceRun(kind: .running, phase: .analysing, message: nil))
        XCTAssertEqual(LocalVideoStatus.deviceRun(for: .cutting(done: 1, total: 4)),
                       DeviceRun(kind: .running, phase: .cutting, message: nil))
    }

    func testAPausedRunSaysWhatTheOldRowSaid() {
        let run = LocalVideoStatus.deviceRun(for: .paused(.cutting(done: 1, total: 4)))
        XCTAssertEqual(run, DeviceRun(kind: .paused, phase: .cutting, message: "Paused - keep Shuttl open"))
        // One wording for every list, not a second copy of it: the shared
        // builder's own status line reads this same constant.
        XCTAssertEqual(run?.message, LocalVideoStatus.pausedText)
    }

    func testAFailedRunCarriesItsReason() {
        XCTAssertEqual(LocalVideoStatus.deviceRun(for: .failed(message: "decoder gave up")),
                       DeviceRun(kind: .failed, phase: .analysing, message: "decoder gave up"))
        let list = MatchListBuilderKt.buildMatchList(input: input(
            entries: [entry(id: "e1", stage: .local)],
            deviceRuns: ["e1": LocalVideoStatus.deviceRun(for: .failed(message: "decoder gave up"))!]
        ))
        XCTAssertEqual(list.mine[0].statusText, "Analysis failed: decoder gave up")
        XCTAssertEqual(list.mine[0].action, .retry)
    }
}
