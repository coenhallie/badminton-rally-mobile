import Shared
import XCTest
@testable import iosApp

/// What this hardware actually manages, per frame, at a real resolution.
///
/// Not an assertion about speed: it prints numbers. `DeviceThroughput` seeds
/// from measurements taken on a Galaxy S23 - 235ms base, 230ms pose - and those
/// seeds are what `estimateAnalysis` quotes to a coach before the phone in hand
/// has produced its own. Shipping the picker with an S23's numbers on an iPhone
/// means the first estimate every iOS user sees is wrong by whatever the two
/// platforms differ by, and nobody knows what that is until someone measures.
///
/// Skipped unless the app is built with `-D MEASURE_THROUGHPUT`, because it
/// decodes and infers over a real-sized clip and would multiply the normal
/// suite's runtime:
///
///     xcodebuild test -project iosApp/iosApp.xcodeproj -scheme iosApp \
///       -destination "platform=iOS Simulator,name=iPhone 17 Pro" \
///       -only-testing:iosAppTests/ThroughputMeasurementTests \
///       CODE_SIGNING_ALLOWED=NO \
///       OTHER_SWIFT_FLAGS="$(inherited) -D MEASURE_THROUGHPUT"
///
/// A compile flag rather than an environment variable, which is the obvious
/// choice and does not work: `xcodebuild test` forwards neither the shell's
/// environment nor a `TEST_RUNNER_`-prefixed setting into a unit-test bundle
/// hosted in an app. Both were tried; the test simply skipped.
///
/// Read the numbers off the test log, then record them where the seeds live.
/// A simulator figure is a floor, not the device number: it runs on the Mac's
/// CPU with no thermal ceiling, and the design's section 4 turns on how long a
/// coach has to keep the app open.
final class ThroughputMeasurementTests: XCTestCase {

    private var scratch: URL!

    /// Read as a value rather than fencing the body with `#if`, so the
    /// measurement keeps compiling on every ordinary build and cannot rot
    /// behind a flag nobody sets.
    private var enabled: Bool {
        #if MEASURE_THROUGHPUT
        return true
        #else
        return false
        #endif
    }

    override func setUpWithError() throws {
        try XCTSkipUnless(
            enabled,
            "build with -D MEASURE_THROUGHPUT to run the throughput measurement"
        )
        try XCTSkipUnless(ModelCatalog.canAnalyseOnDevice, "no ONNX graphs staged")
        scratch = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("Throughput-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: scratch, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        if let scratch { try? FileManager.default.removeItem(at: scratch) }
    }

    func testMeasurePerFrameCost() throws {
        // 1920x1080, because the per-frame cost is dominated by the fused
        // convert-and-resize over the SOURCE pixels and by the models' fixed
        // input sizes. Measuring at 320x180 would flatter the first and leave
        // the second unchanged, which is the wrong shape of wrong.
        //
        // What this clip cannot represent is decode cost on real footage: solid
        // grey compresses to almost nothing, so the decoder has far less to do
        // than on a match. The models dominate the per-frame figure either way,
        // and the number is a floor, which is how it should be read.
        let frames = 60
        let url = scratch.appendingPathComponent("clip.mp4")
        try TestVideo.write(frames: frames, width: 1920, height: 1080, to: url, test: self)

        for wantsPose in [false, true] {
            let engine = IosLocalInferenceEngine(wantsPose: wantsPose)
            let started = Date()
            let raw = try engine.rawInference(videoPath: url.path) { _ in }
            let elapsed = Date().timeIntervalSince(started)
            XCTAssertEqual(raw.frames.count, frames)
            let perFrame = elapsed / Double(frames) * 1000
            print("""
            THROUGHPUT \(wantsPose ? "base+pose" : "base"): \
            \(String(format: "%.1f", perFrame))ms/frame over \(frames) frames \
            (\(String(format: "%.1f", elapsed))s total, 1920x1080)
            """)
        }

        // For comparison, what the estimate would quote today. Printed rather
        // than asserted: the point is to see the two side by side.
        let seed = DeviceThroughput(
            baseMsPerFrame: DeviceThroughput.companion.SEED_BASE_MS,
            poseMsPerFrame: DeviceThroughput.companion.SEED_POSE_MS,
            cutMsPerClipSecond: DeviceThroughput.companion.SEED_CUT_MS,
            measured: false
        )
        let estimate = AnalysisPlanKt.estimateAnalysis(
            frames: 5972, fps: 29.7357,
            metrics: Set([AnalysisMetric.rallyClips, AnalysisMetric.playerMovement]),
            throughput: seed,
            rallyFraction: AnalysisPlanKt.TYPICAL_RALLY_FRACTION
        )
        print("THROUGHPUT seeded estimate for the corpus video: \(estimate.describe())")
    }
}
