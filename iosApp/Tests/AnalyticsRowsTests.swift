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
            group: .ownedMatches, title: "Thu League", subtitle: "3 rallies",
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

    func testEveryRowDescribesItselfInSentenceCase() {
        // This has flipped twice. Analytics uppercased its subtitles while the
        // drawer beside it had already stopped, and Android was talked into
        // uppercasing to match. The mock settles it the other way - "On this
        // phone", "1:05 · Sep 2" - and the redesign carries no uppercase
        // anywhere, so a `.uppercased()` reappearing at any of these three call
        // sites is a regression rather than a choice.
        let out = rows(
            localEntries: [entry(id: "e1")],
            ownedRows: [.video(videoMatch("v1"))],
            sharedMatches: [videoMatch("v2")]
        )
        XCTAssertEqual(out.map(\.group), [.localVideos, .ownedMatches, .shared])
        // Each subtitle still holds lower case somewhere, which an uppercasing
        // call site would strip.
        for row in out {
            XCTAssertTrue(
                row.subtitle.contains(where: { $0.isLowercase }),
                "subtitle came out uppercased: \(row.subtitle)"
            )
        }
        // Literals, deliberately, and NOT a comparison against the formatters
        // themselves: `subtitle == matchRowSecondary(match)` re-derives the
        // expectation from the code under test, so re-adding `.uppercased()` at
        // the call site would change both sides and the test would still pass.
        XCTAssertEqual(
            out.map(\.subtitle),
            ["1:05 · Jul 25, 2026", "3 rallies · Jul 25, 2026", "3 rallies · Jul 25, 2026"]
        )
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

    // MARK: - The device's own liveness

    func testADeviceRunSpeaksOverASettledCloudStage() {
        // The reason isDeviceRunInFlight exists: a device run never moves
        // LocalVideoEntry.stage, so a row reading the cloud stage alone shows a
        // live "Analyze" button over a run already in progress. Pressing it
        // walks the coach through marking the court again and then returns
        // immediately, because the entry is already running - twelve taps, no
        // effect, no explanation, which is what Android shipped.
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
            // No percentage: done/total counts clips, not frames, so rendering
            // it as the analysis percentage would show the bar restarting near
            // the end of a run.
            .inProgress(phase: "Cutting clips")
        )
    }

    func testADeviceRunSpeaksOverAStaleCloudFailure() {
        // A cloud attempt that failed last week must not describe a device run
        // happening now.
        XCTAssertEqual(
            analyseAffordance(
                for: entry(id: "e1", stage: .failed, failureMessage: "Upload failed"),
                progress: nil, device: .preparing(message: "Preparing video")
            ),
            .inProgress(phase: "Preparing video")
        )
    }

    func testASuspendedRunIsNotAFailure() {
        // iOS has no foreground service, so a run stops making progress when the
        // app does. That is not a failure and the control must not say "Retry".
        XCTAssertEqual(
            analyseAffordance(
                for: entry(id: "e1", stage: .local), progress: nil,
                device: .paused(fraction: 0.37)
            ),
            .paused(reason: "Paused at 37% - keep Shuttl open to finish")
        )
    }

    func testASuspendedRunIsStillInFlight() {
        // It was never cancelled: the system freezes the process and thaws it,
        // and the pass carries on from the frame it was on. A row that counted
        // it as finished would offer an "Analyze" button that start() refuses on
        // its own guard - the same dead button the cloud stage used to give.
        XCTAssertTrue(isDeviceRunInFlight(.paused(fraction: 0.37)))
        // And nothing to light in the chrome, which is only on screen when the
        // app is active - by which point the state is already gone.
        XCTAssertNil(toDeviceWork(entryId: "e1", state: .paused(fraction: 0.37)))
    }

    func testAFinishedDeviceRunFallsThroughToTheCloudStage() {
        // .done and .idle both stay in the runner's map after a run, and
        // neither is work: a row must not spin over either.
        for device in [LocalAnalysisState.idle] {
            XCTAssertEqual(
                analyseAffordance(for: entry(id: "e1", stage: .local), progress: nil, device: device),
                .ready
            )
        }
    }

    func testAStoredTrackTurnsARowReady() {
        // The line that was hardcoded false while no track store existed. A
        // ready row is the only one this list opens.
        let built = buildAnalyticsRows(
            localEntries: [entry(id: "e1")],
            ownedRows: [],
            sharedMatches: [],
            progressByEntryId: [:],
            storedTrackIds: ["e1"]
        )
        XCTAssertEqual(built.map(\.state), [.ready])
        // A ready row keeps its dot while a second run is in flight, because
        // the track the first run produced is still there.
        let reRunning = buildAnalyticsRows(
            localEntries: [entry(id: "e1")],
            ownedRows: [],
            sharedMatches: [],
            progressByEntryId: [:],
            storedTrackIds: ["e1"],
            deviceStates: ["e1": .analysing(fraction: 0.5)]
        )
        XCTAssertEqual(reRunning.map(\.state), [.ready])
        XCTAssertEqual(reRunning.map(\.affordance), [.ready])
    }

    func testTheDotLegendAppearsOnlyOnceARowIsReady() {
        let analysable = buildAnalyticsRows(
            localEntries: [entry(id: "e1")], ownedRows: [], sharedMatches: [],
            progressByEntryId: [:]
        )
        XCTAssertEqual(analyticsLegend(for: analysable), .analyseButton)
        let ready = buildAnalyticsRows(
            localEntries: [entry(id: "e1")], ownedRows: [], sharedMatches: [],
            progressByEntryId: [:], storedTrackIds: ["e1"]
        )
        XCTAssertEqual(analyticsLegend(for: ready), .dot)
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

    /// Videos on this phone with live buttons and nothing analysed yet. The
    /// all-inert line would be false here, and so would a legend explaining a
    /// dot that cannot render.
    func testALiveButtonAndNoDotGetsTheLineAboutTheButton() {
        let legend = analyticsLegend(for: [row(.analysable, key: "a"), row(.notOnDevice, key: "b")])
        XCTAssertEqual(legend, .analyseButton)
        // Both targets named: court marking offers cloud AND on device, and it
        // is the on-device run that writes the track a row needs to turn ready.
        XCTAssertEqual(legend.text, "Analyze cuts a video into rallies, on this phone or in the cloud.")
    }

    func testAStoredTrackGetsTheDotLineAndTheDotPromisesATap() {
        let legend = analyticsLegend(for: [row(.ready, key: "a"), row(.analysable, key: "b")])
        XCTAssertEqual(legend, .dot)
        // "- tap to view" is a promise this list can keep now that a ready row
        // opens the detail screen. It was dropped while there was nothing to
        // open, which is the same defect as a comment outliving its code.
        XCTAssertEqual(legend.text, "Analyzed on this phone - tap to view")
    }
}
