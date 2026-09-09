import Shared
import XCTest
@testable import iosApp

/// The runner, driven the way a tap drives it.
///
/// `LocalInferenceEngineTests` covers what the engine produces; this covers what
/// the app does with it - which thread the work happens on, and what a row says
/// while the coach is somewhere else.
@MainActor
final class LocalAnalysisRunnerTests: XCTestCase {

    private var scratch: URL!

    override func setUpWithError() throws {
        try XCTSkipUnless(
            ModelCatalog.canAnalyseOnDevice,
            "no ONNX graphs staged: build with the models exported"
        )
        scratch = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("LocalAnalysisRunnerTests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: scratch, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        if let scratch { try? FileManager.default.removeItem(at: scratch) }
    }

    /// Short on purpose. This is not a throughput measurement - it is the one
    /// place the whole runner is exercised - and every frame costs a TrackNet, a
    /// detector and a pose pass.
    private func video(frames: Int = 12) throws -> URL {
        let url = scratch.appendingPathComponent("clip.mp4")
        try TestVideo.write(frames: frames, width: 320, height: 180, to: url, test: self)
        return url
    }

    private func court() -> CourtKeypoints {
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

    /// Polls rather than awaiting a continuation: `start` is fire and forget by
    /// design - an analysis outlives the screen that began it - so there is no
    /// handle to await, and the state map is the only thing it reports through.
    private func wait(
        _ runner: LocalAnalysisRunner, entryId: String, seconds: Double = 180,
        until predicate: (LocalAnalysisState) -> Bool
    ) async throws -> LocalAnalysisState {
        let deadline = Date().addingTimeInterval(seconds)
        while Date() < deadline {
            let state = runner.state(for: entryId)
            if predicate(state) { return state }
            if case .failed(let message) = state { XCTFail("run failed: \(message)") ; return state }
            try await Task.sleep(nanoseconds: 20_000_000)
        }
        XCTFail("timed out in \(runner.state(for: entryId))")
        return runner.state(for: entryId)
    }

    func testTheRunDoesNotHappenOnTheMainThread() async throws {
        // A method of a `@MainActor` class is main-actor isolated unless it says
        // otherwise, and `analyse` did not: the track write, the skeleton write
        // and above all ClipCutter - which re-encodes every rally and blocks its
        // thread for as long as that takes - all ran on the main thread, and the
        // app was frozen for the length of the cut.
        //
        // `log` is the probe because `analyse` calls it directly, outside the
        // MainActor.run hops it uses for state.
        let threads = Threads()
        let runner = LocalAnalysisRunner { line in threads.record(line) }
        let url = try video()

        runner.start(entryId: "e1", videoPath: url.path, keypoints: court())
        let state = try await wait(runner, entryId: "e1") {
            if case .done = $0 { return true } else { return false }
        }

        guard case .done = state else { return }
        XCTAssertFalse(
            threads.sawMainThread,
            "the analysis logged from the main thread, so its file and encoder work is there too"
        )
        XCTAssertTrue(threads.sawAnything, "no log line: the probe checked nothing")
    }

    func testLeavingTheAppPausesTheRowAndComingBackUndoesIt() async throws {
        // The one divergence from Android this port carries: no foreground
        // service, so a run in the background gets no CPU. What it must NOT do
        // is throw the run away - the system freezes the process and thaws it,
        // and the pass carries on from the frame it was on.
        let runner = LocalAnalysisRunner()
        let url = try video()

        runner.start(entryId: "e1", videoPath: url.path, keypoints: court())
        _ = try await wait(runner, entryId: "e1") {
            if case .analysing = $0 { return true } else { return false }
        }

        runner.suspendForBackground()
        guard case .paused = runner.state(for: "e1") else {
            return XCTFail("backgrounding left the row saying \(runner.state(for: "e1"))")
        }
        // Still in flight, so a row cannot offer an "Analyze" button over it.
        XCTAssertTrue(isDeviceRunInFlight(runner.state(for: "e1")))

        runner.resumeFromBackground()
        if case .paused = runner.state(for: "e1") {
            XCTFail("coming back left the row saying paused")
        }

        // And the run itself was never cancelled by any of that.
        let state = try await wait(runner, entryId: "e1") {
            if case .done = $0 { return true } else { return false }
        }
        guard case .done(let done) = state else { return XCTFail("run did not finish") }
        XCTAssertEqual(done.totalFrames, 12)
    }
}

/// Which threads the runner logged from.
///
/// A class rather than a captured local, because the closure is `@Sendable` and
/// is called from whatever thread the run is on.
private final class Threads: @unchecked Sendable {
    private let lock = NSLock()
    private var main = false
    private var any = false

    func record(_ line: String) {
        let isMain = Thread.isMainThread
        lock.lock()
        any = true
        main = main || isMain
        lock.unlock()
    }

    var sawMainThread: Bool { lock.lock(); defer { lock.unlock() }; return main }
    var sawAnything: Bool { lock.lock(); defer { lock.unlock() }; return any }
}
