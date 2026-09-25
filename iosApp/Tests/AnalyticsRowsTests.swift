import Shared
import XCTest
@testable import iosApp

/// The Analytics list's own decisions, which neither `analyticsRowState` nor the
/// screen's layout makes: which of the shared `MatchListItem`s the screen
/// shows, and what a row's control says while one of the two pipelines is busy
/// with it.
///
/// Rows come from `buildMatchList` here the same way they do in the app
/// (`ClipListModel.regroup`), rather than hand-built `MatchListItem`s, so a
/// test cannot assert something the real join could never produce. Mirrors
/// Android's `AnalyticsRowsTest`.
final class AnalyticsRowsTests: XCTestCase {

    private let me = "user-self"
    private let other = "user-other"
    private let sep8 = KotlinInstant.companion.fromEpochMilliseconds(epochMilliseconds: 1_787_940_000_000)

    // MARK: - Fixtures

    private func entry(
        id: String,
        scoreLogId: String? = nil,
        stage: AnalyzeStage = .local,
        keypoints: CourtKeypoints? = nil,
        failureMessage: String? = nil
    ) -> LocalVideoEntry {
        LocalVideoEntry(
            id: id, uri: "LocalVideos/\(id).mp4", displayName: "\(id).mp4",
            durationMs: 60_000, sizeBytes: 1_000_000, addedAtEpochMs: 1_787_940_000_000,
            title: nil, description: nil, keypoints: keypoints,
            stage: stage, failedStep: nil, failureMessage: failureMessage,
            resultSeen: false, scoreLogId: scoreLogId
        )
    }

    private func clip(_ videoId: String, index: Int32 = 1, owner: String? = nil) -> RallyClip {
        RallyClip(
            id: "\(videoId)-\(index)", videoId: videoId, ownerId: owner ?? me,
            rallyIndex: index, startTimestamp: 0, endTimestamp: 1, durationSeconds: 1,
            clipStoragePath: "clips/\(videoId)/\(index).mp4", thumbnailStoragePath: nil,
            title: nil, annotationCount: 0, createdAt: sep8
        )
    }

    private func log(
        id: String, videoId: String? = nil, points: Int = 0,
        homePlayers: [String] = [], awayPlayers: [String] = []
    ) -> ScoreLog {
        ScoreLog(
            id: id, videoId: videoId, title: "Thu League",
            homePlayers: homePlayers, awayPlayers: awayPlayers, format: nil,
            rules: ScoringRules.companion.BWF_21, setup: MatchSetup(firstServer: .home),
            events: (0..<points).map { _ -> ScoreEvent in ScoreEventPointTo(side: .home) },
            status: videoId != nil ? .bound : .unbound,
            createdAt: sep8, updatedAt: sep8
        )
    }

    private func matchList(
        clips: [RallyClip] = [], logs: [ScoreLog] = [], entries: [LocalVideoEntry] = [],
        deviceRuns: [String: DeviceRun] = [:], tracks: Set<String> = [],
        progress: [String: AnalyzeProgress] = [:]
    ) -> MatchList {
        MatchListBuilderKt.buildMatchList(input: MatchListInput(
            clips: clips, currentUserId: me,
            sharerByVideoId: [:], metadataByVideoId: [:], scoreLogs: logs,
            localEntries: entries, cloudProgress: progress, deviceRuns: deviceRuns,
            storedTrackIds: tracks, storedSkeletonIds: [], deviceClipCounts: [:]
        ))
    }

    private func rows(
        clips: [RallyClip] = [], logs: [ScoreLog] = [], entries: [LocalVideoEntry] = [],
        deviceStates: [String: LocalAnalysisState] = [:], tracks: Set<String> = [],
        progress: [String: AnalyzeProgress] = [:]
    ) -> [AnalyticsRow] {
        let deviceRuns = Dictionary(uniqueKeysWithValues: deviceStates.compactMap { id, state in
            LocalVideoStatus.deviceRun(for: state).map { (id, $0) }
        })
        return buildAnalyticsRows(
            matchList: matchList(
                clips: clips, logs: logs, entries: entries,
                deviceRuns: deviceRuns, tracks: tracks, progress: progress
            ),
            localEntries: entries,
            progressByEntryId: progress,
            deviceStates: deviceStates
        )
    }

    /// A trivial, valid `MatchListItem` for the legend tests below, which only
    /// ever read `.state` off a row: hand-writing every one of its fields for
    /// each case would pin nothing the shared builder's own tests do not
    /// already.
    private lazy var dummyItem: MatchListItem = matchList(logs: [log(id: "dummy")]).mine[0]

    private func row(_ state: AnalyticsRowState, key: String = "k") -> AnalyticsRow {
        AnalyticsRow(
            key: key, entryId: state == .notOnDevice ? nil : "e-\(key)",
            group: .mine, title: "Thu League", subtitle: "3 rallies",
            state: state, affordance: .ready, item: dummyItem
        )
    }

    // MARK: - buildAnalyticsRows

    func testAScoredMatchFindsItsVideoThroughTheEntryThatClaimsTheLog() {
        // The defect this guards: the score card's own `videoId` is written
        // only when the CLOUD pipeline creates the videos row, so a match
        // scored courtside and filmed on this phone has none. Reading it would
        // call a video sitting on the phone "Not on this phone" forever, unless
        // the coach uploads.
        let e = entry(id: "e1", scoreLogId: "log1")
        let built = rows(logs: [log(id: "log1")], entries: [e])
        XCTAssertEqual(built.count, 1)
        XCTAssertEqual(built[0].state, .analysable)
        XCTAssertEqual(built[0].entryId, "e1")
    }

    func testAScoredMatchWithAStoredTrackIsReady() {
        let e = entry(id: "e1", scoreLogId: "log1")
        let built = rows(logs: [log(id: "log1")], entries: [e], tracks: ["e1"])
        XCTAssertEqual(built[0].state, .ready)
    }

    func testAScoredMatchWhoseVideoNeverTouchedThisPhoneStaysInert() {
        // The entry's scoreLogId names a log this input never loaded
        // ("other-log" is not "log1"), so the builder hides the entry rather
        // than showing it as a standalone row; the score row itself comes out
        // with nothing to point at.
        let built = rows(logs: [log(id: "log1")], entries: [entry(id: "e1", scoreLogId: "other-log")])
        XCTAssertEqual(built.count, 1)
        XCTAssertEqual(built[0].state, .notOnDevice)
        XCTAssertNil(built[0].entryId)
    }

    func testAScoredMatchRowReportsItsOwnEntrysLiveness() {
        let e = entry(id: "e1", scoreLogId: "log1")
        let built = rows(
            logs: [log(id: "log1")], entries: [e],
            deviceStates: ["e1": .analysing(fraction: 0.2)]
        )
        XCTAssertEqual(built[0].affordance, .inProgress(phase: "Analyzing on device 20%"))
    }

    func testAScoredMatchsPlayersLineIsCarriedOnItsItemOnceSomeoneIsNamed() {
        // Subtitle is `item.detail` for every row - score, rallies, date - the
        // same text for a named and an unnamed match alike. The players line
        // is its own field on the item (`playersLine`, drawn under the
        // subtitle the same way the drawer draws it) rather than replacing the
        // score/date line.
        let unnamed = rows(logs: [log(id: "log1", points: 3)])
        XCTAssertNil(unnamed[0].item.playersLine)
        XCTAssertEqual(unnamed[0].subtitle, unnamed[0].item.detail)

        let named = rows(logs: [log(id: "log1", points: 3, homePlayers: ["Coen"], awayPlayers: ["Marco"])])
        XCTAssertEqual(named[0].item.playersLine, "Coen vs Marco")
        XCTAssertEqual(named[0].subtitle, named[0].item.detail)
    }

    func testTitleAndSubtitleAreTheItemsOwnTextUntouched() {
        // Title and subtitle are a straight read of `MatchListItem.title`/
        // `.detail` as the shared builder wrote them: `row.subtitle ==
        // row.item.detail` catches a call-site transform reappearing at
        // `buildAnalyticsRows`'s one use of `item.detail`, which is the only
        // place left one could hide.
        let out = rows(clips: [clip("v9", owner: other)], entries: [entry(id: "e1")])
        for row in out {
            XCTAssertEqual(row.title, row.item.title)
            XCTAssertEqual(row.subtitle, row.item.detail)
        }
    }

    func testAVideoRemovedFromThisPhoneAfterAnalysisStillOpens() {
        // A track outlives the entry it came from - a device run's own track
        // stays on disk after the file (and its entry) are gone, and the same
        // is true of a cloud run's track once the video has left the phone by
        // some other path. Either way the row must open the heatmap the coach
        // already has - it used to come out inert, with "Not on this phone"
        // under a match that had just been analysed.
        let built = rows(clips: [clip("v1")], tracks: ["v1"])
        XCTAssertEqual(built.count, 1)
        XCTAssertEqual(built[0].state, .readyNoVideo)
        // Nil here is what made the row unopenable even once the state was
        // right: `AnalyticsListView` navigates on the id.
        XCTAssertEqual(built[0].entryId, "v1")
    }

    func testASharedMatchWithATrackOpensToo() {
        // Same story for a match another coach shared: no local entry here
        // either, and the panels read only the stored track.
        // NOTE: synthetic. Nothing fetches a pose artifact except the phone
        // that uploaded, so a shared match cannot actually have a track here
        // yet. This pins the row logic for when one can.
        let built = rows(clips: [clip("v1", owner: other)], tracks: ["v1"])
        XCTAssertEqual(built[0].state, .readyNoVideo)
        XCTAssertEqual(built[0].entryId, "v1")
        XCTAssertEqual(built[0].group, .shared)
    }

    func testAMatchWithNeitherAVideoNorATrackStaysInertAndIdless() {
        // The guard on the line above: only a row with something to show earns
        // an id. Two matches that are merely both absent must not collapse
        // into each other in the shared builder's own join.
        let built = rows(clips: [clip("v1")])
        XCTAssertEqual(built[0].state, .notOnDevice)
        XCTAssertNil(built[0].entryId)
    }

    func testASharedMatchIsNeverOnThisPhone() {
        let built = rows(clips: [clip("v1", owner: other)])
        XCTAssertEqual(built[0].group, .shared)
        XCTAssertEqual(built[0].state, .notOnDevice)
    }

    func testMineRowsComeBeforeSharedRows() {
        let built = rows(clips: [clip("v9", owner: other)], entries: [entry(id: "e1")])
        XCTAssertEqual(built.map(\.group), [.mine, .shared])
    }

    func testOneVideoProducesOneRowEvenOnceTheCloudHasClippedIt() {
        // A video-first import that finished uploading is both on this phone
        // and in the cloud. The shared builder folds the two into a single
        // `MatchListItem`, so there is nothing left here to deduplicate.
        let e = entry(id: "v1", stage: .analyzed)
        let out = rows(clips: [clip("v1")], entries: [e])
        XCTAssertEqual(out.map(\.key), ["video-v1"])
        XCTAssertEqual(out[0].group, .mine)
    }

    func testTwoMatchesThatAreBothOffThisPhoneKeepTheirOwnRows() {
        let out = rows(clips: [clip("v1", owner: other), clip("v2", owner: other)])
        XCTAssertEqual(out.count, 2)
        XCTAssertEqual(out.map(\.group), [.shared, .shared])
    }

    func testACloudRunsProgressReachesTheRowItBelongsTo() {
        let e = entry(id: "e1", stage: .uploading)
        let built = rows(
            entries: [e],
            progress: ["e1": AnalyzeProgress(entryId: "e1", uploadProgress: 0.25, pipelineProgress: nil)]
        )
        XCTAssertEqual(built[0].affordance, .inProgress(phase: "Uploading 25%…"))
    }

    func testAReadyRowKeepsItsDotWhileASecondRunIsInFlight() {
        // Deliberate: a READY row's first track still opens, so the row keeps
        // the affordance that works rather than swapping it for a spinner. The
        // run itself still reaches the screen through `MatchStatusLine`, which
        // this test does not exercise (it is a SwiftUI concern, not this
        // function's).
        let e = entry(id: "e1")
        let built = rows(entries: [e], deviceStates: ["e1": .analysing(fraction: 0.5)], tracks: ["e1"])
        XCTAssertEqual(built[0].state, .ready)
        XCTAssertEqual(built[0].affordance, .ready)
    }

    // MARK: - analyseAffordance (independent of buildAnalyticsRows)

    func testAnUploadingRunSpeaksInTheDrawersOwnWording() {
        XCTAssertEqual(
            analyseAffordance(
                for: entry(id: "e1", stage: .uploading),
                progress: AnalyzeProgress(entryId: "e1", uploadProgress: 0.42, pipelineProgress: nil),
                device: .idle
            ),
            .inProgress(phase: "Uploading 42%…")
        )
    }

    func testAProcessingRunSpeaksInTheDrawersOwnWording() {
        XCTAssertEqual(
            analyseAffordance(
                for: entry(id: "e1", stage: .processing),
                progress: AnalyzeProgress(entryId: "e1", uploadProgress: nil, pipelineProgress: 0.8),
                device: .idle
            ),
            .inProgress(phase: "Analyzing 80%…")
        )
    }

    func testAFailedRunCarriesThePipelinesOwnMessage() {
        XCTAssertEqual(
            analyseAffordance(
                for: entry(id: "e1", stage: .failed, failureMessage: "Upload failed"),
                progress: nil, device: .idle
            ),
            .failed(reason: "Upload failed")
        )
        XCTAssertEqual(
            analyseAffordance(for: entry(id: "e1", stage: .failed), progress: nil, device: .idle),
            .failed(reason: "Unknown error")
        )
    }

    func testASettledRunLeavesTheButtonLive() {
        XCTAssertEqual(analyseAffordance(for: entry(id: "e1", stage: .local), progress: nil, device: .idle), .ready)
        XCTAssertEqual(analyseAffordance(for: entry(id: "e1", stage: .analyzed), progress: nil, device: .idle), .ready)
    }

    func testADeviceRunSpeaksOverASettledCloudStage() {
        XCTAssertEqual(
            analyseAffordance(
                for: entry(id: "e1", stage: .local), progress: nil,
                device: .analysing(fraction: 0.4)
            ),
            .inProgress(phase: "Analyzing on device 40%")
        )
        XCTAssertEqual(
            analyseAffordance(
                for: entry(id: "e1", stage: .local), progress: nil,
                device: .cutting(done: 2, total: 9)
            ),
            .inProgress(phase: "Cutting clips")
        )
    }

    func testADeviceRunSpeaksOverAStaleCloudFailure() {
        XCTAssertEqual(
            analyseAffordance(
                for: entry(id: "e1", stage: .failed, failureMessage: "Upload failed"),
                progress: nil, device: .preparing(message: "Preparing video")
            ),
            .inProgress(phase: "Preparing video")
        )
    }

    func testASuspendedRunIsNotAFailure() {
        XCTAssertEqual(
            analyseAffordance(
                for: entry(id: "e1", stage: .local), progress: nil,
                device: .paused(.analysing(fraction: 0.37))
            ),
            .paused(reason: "Paused at 37% - keep Shuttl open to finish")
        )
    }

    func testAPauseWithNoFractionDoesNotInventAZero() {
        XCTAssertEqual(
            analyseAffordance(
                for: entry(id: "e1", stage: .local), progress: nil,
                device: .paused(.cutting(done: 6, total: 9))
            ),
            .paused(reason: "Paused - keep Shuttl open to finish")
        )
    }

    func testAFinishedDeviceRunFallsThroughToTheCloudStage() {
        XCTAssertEqual(
            analyseAffordance(for: entry(id: "e1", stage: .local), progress: nil, device: .idle),
            .ready
        )
    }

    // MARK: - analyseAction

    private func court() -> CourtKeypoints {
        let p = [KotlinFloat(float: 0), KotlinFloat(float: 0)]
        return CourtKeypoints(
            topLeft: p, topRight: p, bottomRight: p, bottomLeft: p,
            netLeft: p, netRight: p,
            serviceLineNearLeft: p, serviceLineNearRight: p,
            serviceLineFarLeft: p, serviceLineFarRight: p,
            centerNear: p, centerFar: p
        )
    }

    func testAFailedRunWithItsCourtAlreadySavedResumesFromTheFailedStep() {
        XCTAssertEqual(
            analyseAction(for: entry(id: "e1", stage: .failed, keypoints: court())),
            .resume(entryId: "e1")
        )
    }

    func testAFailedRunWithNoCourtMustBeMarkedAgain() {
        XCTAssertEqual(analyseAction(for: entry(id: "e1", stage: .failed)), .markCourt(entryId: "e1"))
    }

    func testAFreshVideoStartsAtCourtMarking() {
        XCTAssertEqual(analyseAction(for: entry(id: "e1")), .markCourt(entryId: "e1"))
    }

    // MARK: - analyticsLegend

    func testAnEmptyListExplainsNothing() {
        XCTAssertEqual(analyticsLegend(for: []), .silent)
        XCTAssertNil(AnalyticsLegend.silent.text)
    }

    func testEveryRowInertGetsTheOneAllInertLine() {
        let legend = analyticsLegend(for: [row(.notOnDevice, key: "a"), row(.notOnDevice, key: "b")])
        XCTAssertEqual(legend, .nothingOnThisPhone)
        XCTAssertEqual(legend.text, "None of these matches are on this phone yet.")
    }

    func testALiveButtonAndNoDotGetsTheLineAboutTheButton() {
        let legend = analyticsLegend(for: [row(.analysable, key: "a"), row(.notOnDevice, key: "b")])
        XCTAssertEqual(legend, .analyseButton)
        XCTAssertEqual(legend.text, "Analyze cuts a video into rallies, on this phone or in the cloud.")
    }

    func testAStoredTrackGetsTheDotLineAndTheDotPromisesATap() {
        let legend = analyticsLegend(for: [row(.ready, key: "a"), row(.analysable, key: "b")])
        XCTAssertEqual(legend, .dot)
        XCTAssertEqual(legend.text, "Analyzed on this phone - tap to view")
    }
}
