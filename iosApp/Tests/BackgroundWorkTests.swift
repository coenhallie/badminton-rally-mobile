import Shared
import XCTest
@testable import iosApp

/// What the chrome indicator shows, driven the way a run drives it.
///
/// The merge rules are `backgroundWork` in `shared` and are covered in
/// `commonTest` against both platforms. What is covered here is the Swift that
/// feeds them: whether a live run on the runner actually reaches the indicator,
/// whether it goes out again when the run settles, and whether the shared
/// failure rule survives the Objective-C bridge in both directions.
@MainActor
final class BackgroundWorkTests: XCTestCase {

    private var scratch: URL!

    override func setUpWithError() throws {
        scratch = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("BackgroundWorkTests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: scratch, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        if let scratch { try? FileManager.default.removeItem(at: scratch) }
    }

    private func model(runner: LocalAnalysisRunner?) -> BackgroundWorkModel {
        let localVideos = IosTestDoublesKt.testLocalVideoRepository()
        return BackgroundWorkModel(
            localVideos: localVideos,
            analyze: IosTestDoublesKt.testAnalyzeCoordinator(
                localVideos: localVideos,
                clips: IosTestDoublesKt.testClipsRepository()
            ),
            runner: runner
        )
    }

    func testAnIdleAppShowsNoIndicatorAtAll() {
        // Absent rather than idle: the ring costs nothing on the eight bars it
        // sits in when there is nothing to say.
        XCTAssertNil(model(runner: nil).work)
    }

    /// The question no screenshot answers: does the ring follow a run?
    ///
    /// `work` is computed rather than stored precisely so that reading it picks
    /// up the runner's state, and nothing else in the app would have caught it
    /// being cached.
    func testALiveRunReachesTheIndicatorAndGoesOutWhenItSettles() async throws {
        try XCTSkipUnless(
            ModelCatalog.canAnalyseOnDevice,
            "no ONNX graphs staged: build with the models exported"
        )
        let runner = LocalAnalysisRunner()
        let model = model(runner: runner)
        XCTAssertNil(model.work, "nothing has started yet")

        let url = scratch.appendingPathComponent("clip.mp4")
        try TestVideo.write(frames: 12, width: 320, height: 180, to: url, test: self)
        runner.start(entryId: "e1", videoPath: url.path, keypoints: Self.court())

        // The run passes through preparing and analysing on the way to done, and
        // every one of those must light the indicator. Sampled rather than
        // asserted at one instant: a 12-frame clip finishes fast.
        var seenLabels: Set<String> = []
        let deadline = Date().addingTimeInterval(180)
        while Date() < deadline {
            if let work = model.work {
                XCTAssertEqual(work.activeCount, 1)
                seenLabels.insert(work.label)
            }
            if case .done = runner.state(for: "e1") { break }
            if case .failed(let message) = runner.state(for: "e1") {
                return XCTFail("run failed: \(message)")
            }
            try await Task.sleep(nanoseconds: 10_000_000)
        }
        guard case .done = runner.state(for: "e1") else { return XCTFail("run did not finish") }

        XCTAssertFalse(seenLabels.isEmpty, "the indicator never lit during a run")
        XCTAssertTrue(
            seenLabels.contains { $0.hasPrefix("Preparing video") || $0.hasPrefix("Analyzing on device") },
            "the indicator lit but never named the stage: \(seenLabels.sorted())"
        )
        // A finished run is the banner's, not the ring's.
        XCTAssertNil(model.work, "the indicator stayed lit after the run finished")
    }

    // MARK: - The failure rule, across the bridge

    func testAFailureSeenWhileWatchingIsBadgedAndARetryClearsIt() {
        // The shared rule, called from Swift: both sets are Kotlin `Set<String>`
        // and reach here as Swift sets, which is the half of it that no
        // `commonTest` can check.
        let failed = BackgroundWorkKt.failureTransitions(
            seen: ["a": AnalyzeStage.uploading],
            now: ["a": AnalyzeStage.failed]
        )
        XCTAssertEqual(failed.failed, ["a"])
        XCTAssertTrue(failed.resolved.isEmpty)

        let retried = BackgroundWorkKt.failureTransitions(
            seen: ["a": AnalyzeStage.failed],
            now: ["a": AnalyzeStage.processing]
        )
        XCTAssertTrue(retried.failed.isEmpty)
        XCTAssertEqual(retried.resolved, ["a"])
    }

    func testAnEntryAlreadyFailedAtLaunchIsNotBadged() {
        // It failed in an earlier run of the app; its row carries the failure and
        // the Retry that clears it, and a badge here would never go out.
        let t = BackgroundWorkKt.failureTransitions(seen: [:], now: ["a": AnalyzeStage.failed])
        XCTAssertTrue(t.failed.isEmpty)
        XCTAssertTrue(t.resolved.isEmpty)
    }

    // MARK: - The banner

    func testTheBannerSaysTheNumbersACloudRunCanBeHeldAgainst() {
        let done = LocalAnalysisState.Done(
            rallies: 7,
            shuttleVisible: 5_012,
            totalFrames: 5_972,
            clips: [],
            elapsedSeconds: 61.6,
            playerTrack: PlayerTrack(samples: [], framesWithPose: 0, rejections: [:]),
            fps: 29.7
        )
        // Whole seconds, and the order androidApp sets them in, so two runs of
        // the same video can be read side by side.
        XCTAssertEqual(
            LocalAnalysisBanner.summary(done),
            "7 rallies, 0 clips, shuttle in 5012/5972 frames, 62s"
        )
    }

    private static func court() -> CourtKeypoints {
        func point(_ x: Double, _ y: Double) -> [KotlinFloat] {
            [KotlinFloat(float: Float(x * 320)), KotlinFloat(float: Float(y * 180))]
        }
        return CourtKeypoints(
            topLeft: point(0.2, 0.2), topRight: point(0.8, 0.2),
            bottomRight: point(0.95, 0.9), bottomLeft: point(0.05, 0.9),
            netLeft: point(0.1, 0.55), netRight: point(0.9, 0.55),
            serviceLineNearLeft: point(0.08, 0.75), serviceLineNearRight: point(0.92, 0.75),
            serviceLineFarLeft: point(0.22, 0.3), serviceLineFarRight: point(0.78, 0.3),
            centerNear: point(0.5, 0.9), centerFar: point(0.5, 0.2)
        )
    }
}
