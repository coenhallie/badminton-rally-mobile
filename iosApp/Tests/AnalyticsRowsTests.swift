import Shared
import XCTest
@testable import iosApp

/// The Analytics list's decision logic, which is everything except the layout:
/// which state each match lands in, which control its row offers, where that
/// control goes and which line explains the list. Android's equivalent shipped
/// untested inside a Compose file and a Critical was hiding in it.
final class AnalyticsRowsTests: XCTestCase {

    // MARK: - Fixtures

    private func entry(
        id: String,
        scoreLogId: String? = nil,
        stage: AnalyzeStage = .local,
        keypoints: CourtKeypoints? = nil,
        failureMessage: String? = nil,
        title: String? = nil
    ) -> LocalVideoEntry {
        LocalVideoEntry(
            id: id,
            uri: "LocalVideos/\(id).mp4",
            displayName: "\(id).mp4",
            durationMs: 65_000,
            sizeBytes: 2,
            addedAtEpochMs: 1_784_980_800_000,
            title: title,
            description: nil,
            keypoints: keypoints,
            stage: stage,
            failedStep: nil,
            failureMessage: failureMessage,
            resultSeen: false,
            scoreLogId: scoreLogId
        )
    }

    /// A saved court. The values do not matter to any rule under test; only
    /// whether the field is nil does.
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

    private func videoMatch(_ videoId: String) -> MatchSummary {
        MatchSummary(
            videoId: videoId, rallyCount: 3, latestCreatedAtMillis: 1_784_980_800_000,
            coverClipId: "c-\(videoId)", isOwned: true, sharerEmail: nil,
            title: "Thu League", description: nil
        )
    }

    private func scoreRow(scoreLogId: String, videoId: String? = nil) -> MatchRow {
        .score(ScoreRowContent(
            card: ScoreMatchCard(
                scoreLogId: scoreLogId,
                videoId: videoId,
                title: "Thu League",
                createdAtEpochMs: 1_784_980_800_000,
                playersLine: "Coen vs Marco",
                scoreLine: "11-9",
                statusLine: "Scoring",
                isLive: false,
                hasVideo: videoId != nil
            ),
            video: nil,
            attach: nil
        ))
    }

    private func rows(
        localEntries: [LocalVideoEntry] = [],
        ownedRows: [MatchRow] = [],
        sharedMatches: [MatchSummary] = [],
        progress: [String: AnalyzeProgress] = [:]
    ) -> [AnalyticsRow] {
        buildAnalyticsRows(
            localEntries: localEntries,
            ownedRows: ownedRows,
            sharedMatches: sharedMatches,
            progressByEntryId: progress
        )
    }

    /// Rows built by hand, for the one state `buildAnalyticsRows` cannot produce
    /// on this platform yet.
    private func row(_ state: AnalyticsRowState, key: String = "k") -> AnalyticsRow {
        AnalyticsRow(
            key: key, entryId: state == .notOnDevice ? nil : "e-\(key)",
            group: .ownedMatches, title: "Thu League", subtitle: "3 RALLIES",
            state: state, affordance: .ready
        )
    }

    // MARK: - buildAnalyticsRows

    func testAVideoOnThisPhoneWithNoTrackIsAnalysable() {
        let built = rows(localEntries: [entry(id: "e1")])
        XCTAssertEqual(built.count, 1)
        XCTAssertEqual(built[0].group, .localVideos)
        XCTAssertEqual(built[0].entryId, "e1")
        // Never READY: iOS has no track store, so `hasStoredTrack` is false for
        // every row and the classifier can only reach ANALYSABLE from here.
        XCTAssertEqual(built[0].state, .analysable)
        XCTAssertEqual(built[0].affordance, .ready)
    }

    func testAMatchWithNoVideoOnThisPhoneIsInertAndHasNoEntryToActOn() {
        let built = rows(ownedRows: [.video(videoMatch("v9"))])
        XCTAssertEqual(built.count, 1)
        XCTAssertEqual(built[0].state, .notOnDevice)
        XCTAssertNil(built[0].entryId)
        XCTAssertEqual(built[0].group, .ownedMatches)
    }

    func testASharedMatchIsInertAndLandsInItsOwnSection() {
        let built = rows(sharedMatches: [videoMatch("v9")])
        XCTAssertEqual(built.map(\.group), [.shared])
        XCTAssertEqual(built.map(\.state), [.notOnDevice])
    }

    /// The join Android shipped as `card.videoId` and had to fix as a Critical: a
    /// match scored courtside and filmed on this phone read "Not on this phone"
    /// and was inert forever. `card.videoId` is written only by the cloud
    /// pipeline, so this match has none.
    func testAScoredMatchFindsItsVideoByScoreLogIdNotVideoId() {
        let built = rows(
            localEntries: [entry(id: "e1", scoreLogId: "s1")],
            ownedRows: [scoreRow(scoreLogId: "s1")]
        )
        XCTAssertEqual(built.count, 1)
        XCTAssertEqual(built[0].entryId, "e1")
        XCTAssertEqual(built[0].state, .analysable)
    }

    /// A video picked for a match is that match's row, not a second standalone
    /// one, exactly as the drawer's own "On this phone" section filters it.
    func testAVideoClaimedByAMatchIsNotAlsoAStandaloneRow() {
        let built = rows(
            localEntries: [entry(id: "e1", scoreLogId: "s1")],
            ownedRows: [scoreRow(scoreLogId: "s1")]
        )
        XCTAssertEqual(built.map(\.group), [.ownedMatches])
    }

    /// One video, one row - and the local row is the one that survives, because
    /// it is alive from import until the file leaves the phone whereas the owned
    /// row only appears once a cloud run has clipped.
    func testAVideoTheCloudHasClippedStillShowsOnceUnderOnThisPhone() {
        let built = rows(
            localEntries: [entry(id: "v1")],
            ownedRows: [.video(videoMatch("v1"))]
        )
        XCTAssertEqual(built.count, 1)
        XCTAssertEqual(built[0].group, .localVideos)
        XCTAssertEqual(built[0].key, "local-v1")
    }

    /// Every inert row has a nil entry id, so the dedupe key must fall back to
    /// the row's own key or two unrelated absent matches would collapse into one.
    func testTwoMatchesThatAreBothAbsentDoNotCollapseIntoOne() {
        let built = rows(sharedMatches: [videoMatch("v1"), videoMatch("v2")])
        XCTAssertEqual(built.count, 2)
    }

    func testSectionsKeepTheDrawersOrderLocalThenOwnedThenShared() {
        let built = rows(
            localEntries: [entry(id: "e1")],
            ownedRows: [.video(videoMatch("v9"))],
            sharedMatches: [videoMatch("v8")]
        )
        XCTAssertEqual(built.map(\.group), [.localVideos, .ownedMatches, .shared])
    }

    func testARunInFlightReachesTheRowItBelongsTo() {
        let built = rows(
            localEntries: [entry(id: "e1", stage: .uploading)],
            progress: ["e1": AnalyzeProgress(entryId: "e1", uploadProgress: 0.42, pipelineProgress: nil)]
        )
        XCTAssertEqual(built[0].affordance, .inProgress(phase: "Uploading 42%…"))
    }

    // MARK: - analyseAffordance

    func testAnUploadingRunSpeaksInTheDrawersOwnWording() {
        XCTAssertEqual(
            analyseAffordance(
                for: entry(id: "e1", stage: .uploading),
                progress: AnalyzeProgress(entryId: "e1", uploadProgress: 0.42, pipelineProgress: nil)
            ),
            .inProgress(phase: "Uploading 42%…")
        )
    }

    func testAProcessingRunSpeaksInTheDrawersOwnWording() {
        XCTAssertEqual(
            analyseAffordance(
                for: entry(id: "e1", stage: .processing),
                progress: AnalyzeProgress(entryId: "e1", uploadProgress: nil, pipelineProgress: 0.8)
            ),
            .inProgress(phase: "Analyzing 80%…")
        )
    }

    func testAFailedRunCarriesThePipelinesOwnMessage() {
        XCTAssertEqual(
            analyseAffordance(for: entry(id: "e1", stage: .failed, failureMessage: "Upload failed"), progress: nil),
            .failed(reason: "Upload failed")
        )
        XCTAssertEqual(
            analyseAffordance(for: entry(id: "e1", stage: .failed), progress: nil),
            .failed(reason: "Unknown error")
        )
    }

    func testASettledRunLeavesTheButtonLive() {
        XCTAssertEqual(analyseAffordance(for: entry(id: "e1", stage: .local), progress: nil), .ready)
        XCTAssertEqual(analyseAffordance(for: entry(id: "e1", stage: .analyzed), progress: nil), .ready)
    }

    // MARK: - analyseAction

    func testAFailedRunWithItsCourtAlreadySavedResumesFromTheFailedStep() {
        XCTAssertEqual(
            analyseAction(for: entry(id: "e1", stage: .failed, keypoints: court())),
            .resume(entryId: "e1")
        )
    }

    func testAFailedRunWithNoCourtMustBeMarkedAgain() {
        XCTAssertEqual(
            analyseAction(for: entry(id: "e1", stage: .failed)),
            .markCourt(entryId: "e1")
        )
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

    /// The case iOS actually shows today: videos on this phone with live buttons
    /// and no dot anywhere. The all-inert line would be false here, and so would
    /// a legend explaining a dot that cannot render.
    func testALiveButtonAndNoDotGetsTheLineAboutTheButton() {
        let legend = analyticsLegend(for: [row(.analysable, key: "a"), row(.notOnDevice, key: "b")])
        XCTAssertEqual(legend, .analyseButton)
        XCTAssertEqual(legend.text, "Analyze sends a video to the cloud and cuts it into rallies.")
    }

    /// Unreachable until a track store lands on iOS, and deliberately kept: the
    /// day one does, this is the line that appears with no other UI change.
    func testAStoredTrackGetsTheDotLineAndTheDotPromisesNoTap() {
        let legend = analyticsLegend(for: [row(.ready, key: "a"), row(.analysable, key: "b")])
        XCTAssertEqual(legend, .dot)
        XCTAssertEqual(legend.text, "Analysed on this phone.")
    }
}
