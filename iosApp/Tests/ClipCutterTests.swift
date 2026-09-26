import AVFoundation
import Shared
import XCTest
@testable import iosApp

/// The cutter, on a video whose frames say which frame they are.
///
/// `TestVideo` writes each frame as a solid grey encoding its own index, so
/// these can check WHICH frames landed in a clip rather than only how many -
/// the whole reason the cutter re-encodes instead of stream-copying is that the
/// boundary has to be exact.
final class ClipCutterTests: XCTestCase {

    private var scratch: URL!

    override func setUpWithError() throws {
        scratch = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("ClipCutterTests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: scratch, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        if let scratch { try? FileManager.default.removeItem(at: scratch) }
    }

    /// One second at 30 fps: every frame identifiable, frame `n` at `n / 30` s.
    private func source() throws -> URL {
        let url = scratch.appendingPathComponent("source.mp4")
        try TestVideo.write(frames: 30, to: url, test: self)
        return url
    }

    private func window(_ start: Double, _ end: Double) -> ClipWindow {
        // The cutter reads the padded bounds and nothing of the rally inside.
        ClipWindow(
            rally: Rally(
                id: 1, startFrame: 0, endFrame: 0,
                startTimestamp: start, endTimestamp: end, durationSeconds: end - start
            ),
            clipStart: start, clipEnd: end
        )
    }

    /// Every frame of a clip, as (the source frame its pixels say it is, its
    /// presentation time in the clip).
    private func frames(of url: URL) throws -> [(frame: Int, seconds: Double)] {
        let asset = AVURLAsset(url: url)
        let loaded = expectation(description: "track loads")
        var found: AVAssetTrack?
        Task {
            found = try? await asset.loadTracks(withMediaType: .video).first
            loaded.fulfill()
        }
        wait(for: [loaded], timeout: 30)
        let track = try XCTUnwrap(found, "the clip has no video track")
        let reader = try AVAssetReader(asset: asset)
        let output = AVAssetReaderTrackOutput(track: track, outputSettings: [
            kCVPixelBufferPixelFormatTypeKey as String: Int(kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange)
        ])
        reader.add(output)
        XCTAssertTrue(reader.startReading())
        var result: [(Int, Double)] = []
        while let sample = output.copyNextSampleBuffer() {
            let buffer = try XCTUnwrap(CMSampleBufferGetImageBuffer(sample))
            CVPixelBufferLockBaseAddress(buffer, .readOnly)
            let luma = CVPixelBufferGetBaseAddressOfPlane(buffer, 0)!.assumingMemoryBound(to: UInt8.self)[0]
            CVPixelBufferUnlockBaseAddress(buffer, .readOnly)
            result.append((
                TestVideo.frame(forLuma: luma),
                CMTimeGetSeconds(CMSampleBufferGetPresentationTimeStamp(sample))
            ))
        }
        XCTAssertEqual(reader.status, .completed, "\(reader.error?.localizedDescription ?? "")")
        return result
    }

    func testAClipHoldsExactlyTheFramesInsideItsWindow() throws {
        // Both bounds fall INSIDE a frame, so there is one right answer and no
        // argument about an edge. Frame 7 runs 0.2333..0.2667 s and is what is
        // on screen at 0.25 s, so the clip opens on it - AVAssetReader's time
        // range keeps a frame that overlaps the start, and dropping it would
        // open the clip on a frame of nothing. Frame 22 (0.7333 s) is the last
        // to start before 0.75 s. A cut that seeked to a keyframe instead would
        // open on frame 0.
        let directory = scratch.appendingPathComponent("clips", isDirectory: true)
        var progress: [Int] = []

        let clips = try ClipCutter().cut(
            source: try source(), windows: [window(0.25, 0.75)], into: directory
        ) { progress.append($0) }

        XCTAssertEqual(progress, [1])
        XCTAssertEqual(clips.count, 1)
        let clip = try XCTUnwrap(clips.first)
        // rally_index is 1-based, and the filename follows it.
        XCTAssertEqual(clip.index, 1)
        XCTAssertEqual(clip.url.lastPathComponent, "rally-1.mp4")
        XCTAssertEqual(clip.startSeconds, 0.25)
        XCTAssertEqual(clip.endSeconds, 0.75)

        let cut = try frames(of: clip.url)
        XCTAssertEqual(cut.map(\.frame), Array(7...22))
        guard cut.count == 16 else { return }
        // Rebased to the clip's own zero. The opening frame is on screen from
        // the first instant, and every later one sits where it sat relative to
        // the window's start rather than at its time in the source.
        XCTAssertEqual(cut[0].seconds, 0, accuracy: 0.002)
        XCTAssertEqual(cut[1].seconds, 8.0 / 30.0 - 0.25, accuracy: 0.002)
        XCTAssertEqual(cut[15].seconds, 22.0 / 30.0 - 0.25, accuracy: 0.002)
    }

    func testTheClipPlaysForTheLengthOfItsWindow() throws {
        let directory = scratch.appendingPathComponent("clips", isDirectory: true)
        let clips = try ClipCutter().cut(source: try source(), windows: [window(0.25, 0.75)], into: directory)
        let asset = AVURLAsset(url: try XCTUnwrap(clips.first).url)

        let loaded = expectation(description: "asset loads")
        var playable = false
        var seconds = 0.0
        Task {
            playable = (try? await asset.load(.isPlayable)) ?? false
            seconds = ((try? await asset.load(.duration)).map(CMTimeGetSeconds)) ?? 0
            loaded.fulfill()
        }
        wait(for: [loaded], timeout: 30)

        XCTAssertTrue(playable)
        // Half a second, give or take the one frame either bound can fall short by.
        XCTAssertEqual(seconds, 0.5, accuracy: 1.0 / 30.0 + 0.002)
    }

    func testOverlappingWindowsAreCutIndependently() throws {
        // `refineRallies` produces overlapping rallies and padding keeps them,
        // which is why there is one reader per clip.
        let directory = scratch.appendingPathComponent("clips", isDirectory: true)
        let clips = try ClipCutter().cut(
            source: try source(), windows: [window(0.05, 0.55), window(0.35, 0.85)], into: directory
        )

        XCTAssertEqual(clips.map(\.index), [1, 2])
        XCTAssertEqual(try frames(of: clips[0].url).map(\.frame), Array(1...16))
        XCTAssertEqual(try frames(of: clips[1].url).map(\.frame), Array(10...25))
    }

    func testAWindowPastTheEndOfTheVideoFailsAndLeavesNoFile() throws {
        let directory = scratch.appendingPathComponent("clips", isDirectory: true)
        XCTAssertThrowsError(
            try ClipCutter().cut(source: try source(), windows: [window(5, 6)], into: directory)
        ) { error in
            guard case ClipCutter.Failure.noFramesInWindow = error else {
                return XCTFail("expected noFramesInWindow, got \(error)")
            }
        }
        XCTAssertFalse(FileManager.default.fileExists(atPath: directory.appendingPathComponent("rally-1.mp4").path))
    }

    // MARK: - Waiting on the writer

    /// Runs the wait on its own thread and reports how it ended, or nil if it
    /// was still waiting when time ran out - which is the defect.
    private func outcomeOfWaiting(
        isReady: @escaping () -> Bool,
        status: @escaping () -> AVAssetWriter.Status,
        error: @escaping () -> Error?
    ) -> Result<Void, Error>? {
        let done = DispatchSemaphore(value: 0)
        var outcome: Result<Void, Error>?
        Thread.detachNewThread {
            outcome = Result {
                try ClipCutter.waitUntilReady(name: "rally-1.mp4", isReady: isReady, status: status, error: error)
            }
            done.signal()
        }
        return done.wait(timeout: .now() + 3) == .success ? outcome : nil
    }

    func testAWriterThatHasFailedEndsTheWaitWithItsError() throws {
        // AVError -11847 is what a writer reports when the app is backgrounded
        // mid-encode. Its input then never becomes ready again, and the cutter
        // waited on that flag alone - so the analysis queue spun for good and
        // the run never finished or failed.
        let interrupted = NSError(domain: AVFoundationErrorDomain, code: -11847)
        let outcome = outcomeOfWaiting(isReady: { false }, status: { .failed }, error: { interrupted })

        guard case .failure(let thrown) = try XCTUnwrap(outcome, "still waiting on a writer that has failed") else {
            return XCTFail("the wait reported ready for a writer that has failed")
        }
        guard case ClipCutter.Failure.writeFailed(let name, let underlying) = thrown else {
            return XCTFail("expected writeFailed, got \(thrown)")
        }
        XCTAssertEqual(name, "rally-1.mp4")
        XCTAssertEqual((underlying as NSError?)?.code, -11847)
    }

    func testARealCancelledWriterEndsTheWait() throws {
        // The same, against AVFoundation rather than a description of it: what
        // the flag and the status actually do once a writer has stopped.
        let url = scratch.appendingPathComponent("cancelled.mp4")
        let writer = try AVAssetWriter(outputURL: url, fileType: .mp4)
        let input = AVAssetWriterInput(mediaType: .video, outputSettings: [
            AVVideoCodecKey: AVVideoCodecType.h264, AVVideoWidthKey: 64, AVVideoHeightKey: 64,
        ])
        writer.add(input)
        XCTAssertTrue(writer.startWriting())
        writer.startSession(atSourceTime: .zero)
        writer.cancelWriting()
        XCTAssertFalse(input.isReadyForMoreMediaData, "a cancelled writer's input still reports ready")

        let outcome = outcomeOfWaiting(
            isReady: { input.isReadyForMoreMediaData }, status: { writer.status }, error: { writer.error }
        )

        guard case .failure = try XCTUnwrap(outcome, "still waiting on a writer that was cancelled") else {
            return XCTFail("the wait reported ready for a cancelled writer")
        }
    }

    func testAWriterThatIsMerelyBusyIsWaitedFor() throws {
        // The other half: not ready is the normal state between drains, and
        // must not be mistaken for a failure.
        var polls = 0
        let outcome = outcomeOfWaiting(
            isReady: { polls += 1; return polls > 50 }, status: { .writing }, error: { nil }
        )

        guard case .success = try XCTUnwrap(outcome, "never became ready") else {
            return XCTFail("a busy writer was treated as a failed one")
        }
        XCTAssertEqual(polls, 51)
    }
}
