import AVFoundation
import Foundation
import Shared

/// Cuts rally clips out of a source video, re-encoding.
///
/// Port of Android's `ClipCutter`. The pipeline design's section 5.4:
/// "Frame-accurate boundaries need a re-encode, not a stream copy, for the same
/// reason the cloud re-encodes." A stream copy can only start on a keyframe, and
/// a rally boundary almost never is one, so a copied clip either starts seconds
/// early or opens on a corrupt frame.
///
/// `AVAssetReader` into `AVAssetWriter`, not `AVAssetExportSession`. Export is
/// the shorter route and gives no control over the two things that matter here:
/// which frames land inside the window, and whether the encoder is asked for a
/// bitrate a coach can review a shuttle in.
///
/// One reader per clip, each restricted to its own time range. Clips are cut
/// independently because `ClipWindow`s can overlap - `refineRallies` produces
/// overlapping rallies and padding preserves them - so a single pass would need
/// two writers live on the same frame.
///
/// **Video only.** The source's audio track is not carried across, matching
/// Android. Clips are silent, which is a real gap for reviewing a rally and is
/// not yet addressed on either platform.
struct ClipCutter {

    enum Failure: LocalizedError {
        case noVideoTrack(String)
        case noFramesInWindow(String, Double, Double)
        case writeFailed(String, Error?)

        var errorDescription: String? {
            switch self {
            case .noVideoTrack(let name): return "no video track in \(name)"
            case .noFramesInWindow(let name, let start, let end):
                return String(format: "no frames fell inside %.2f..%.2fs of %@", start, end, name)
            case .writeFailed(let name, let error):
                return "couldn't write \(name): \(error?.localizedDescription ?? "unknown")"
            }
        }
    }

    /// Dedicated queue for the blocking sample-copy loop so Swift Concurrency's
    /// cooperative pool threads are never blocked while waiting on the encoder.
    private static let queue = DispatchQueue(
        label: "com.badmintontracker.clip-cutter", qos: .userInitiated
    )

    /// Cuts every window, reporting after each so a long cut can show progress.
    ///
    /// `onProgress` fires with the number finished, not a fraction: the caller
    /// shows "3 of 12", which is what Android's `Cutting(done, total)` state
    /// carries, and a fraction would have to be turned back into that.
    func cut(
        source: URL,
        windows: [ClipWindow],
        into directory: URL,
        onProgress: (Int) -> Void = { _ in }
    ) async throws -> [PlayerTrackStore.Clip] {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        var clips: [PlayerTrackStore.Clip] = []
        for (i, window) in windows.enumerated() {
            // rally_index is 1-based throughout this project, and the clip
            // filenames follow it so a file can be matched to a row by eye.
            let index = i + 1
            let out = directory.appendingPathComponent("rally-\(index).mp4")
            try await cutOne(source: source, start: window.clipStart, end: window.clipEnd, to: out)
            clips.append(PlayerTrackStore.Clip(
                index: index, url: out,
                startSeconds: window.clipStart, endSeconds: window.clipEnd
            ))
            onProgress(clips.count)
        }
        return clips
    }

    private func cutOne(source: URL, start: Double, end: Double, to out: URL) async throws {
        // A stale file from an interrupted run would make the writer refuse to
        // start, and the error it gives says nothing about why.
        try? FileManager.default.removeItem(at: out)

        let asset = AVURLAsset(url: source)
        guard let track = try await asset.loadTracks(withMediaType: .video).first else {
            throw Failure.noVideoTrack(source.lastPathComponent)
        }
        let size = try await track.load(.naturalSize)

        let reader = try AVAssetReader(asset: asset)
        // The reader's own time range does the seeking, and it is what makes the
        // boundary exact rather than keyframe-aligned: AVFoundation decodes from
        // the preceding sync sample and discards what falls before the range.
        // Seeking to the nearest sync AFTER the start would silently drop the
        // opening of the rally.
        reader.timeRange = CMTimeRange(
            start: CMTime(seconds: start, preferredTimescale: 600),
            end: CMTime(seconds: end, preferredTimescale: 600)
        )
        let output = AVAssetReaderTrackOutput(
            track: track,
            outputSettings: [
                kCVPixelBufferPixelFormatTypeKey as String:
                    Int(kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange)
            ]
        )
        guard reader.canAdd(output) else { throw Failure.writeFailed(out.lastPathComponent, nil) }
        reader.add(output)

        let width = Int(size.width.rounded())
        let height = Int(size.height.rounded())
        let writer = try AVAssetWriter(outputURL: out, fileType: .mp4)
        let input = AVAssetWriterInput(mediaType: .video, outputSettings: [
            AVVideoCodecKey: AVVideoCodecType.h264,
            AVVideoWidthKey: width,
            AVVideoHeightKey: height,
            AVVideoCompressionPropertiesKey: [
                // Generous rather than tuned, matching Android, capped at 16 Mbps
                // so 4K recordings do not inflate clips to 66 Mbps+.
                AVVideoAverageBitRateKey: min(max(width * height * 8, 4_000_000), 16_000_000),
                // Every second. A clip is seconds long and gets scrubbed, so
                // sparse keyframes make seeking within it unpleasant.
                AVVideoMaxKeyFrameIntervalDurationKey: 1.0,
            ],
        ])
        input.expectsMediaDataInRealTime = false
        guard writer.canAdd(input) else { throw Failure.writeFailed(out.lastPathComponent, nil) }
        writer.add(input)

        guard reader.startReading() else { throw Failure.writeFailed(out.lastPathComponent, reader.error) }
        defer { if reader.status == .reading { reader.cancelReading() } }
        guard writer.startWriting() else { throw Failure.writeFailed(out.lastPathComponent, writer.error) }
        writer.startSession(atSourceTime: .zero)

        let startTime = CMTime(seconds: start, preferredTimescale: 600)
        let outName = out.lastPathComponent

        // Run the pull-driven sample copy on a dedicated DispatchQueue so
        // waiting for the encoder never blocks a cooperative pool thread.
        let copyResult: (rendered: Int, failure: Error?) = await withCheckedContinuation { continuation in
            Self.queue.async {
                var rendered = 0
                var failure: Error?
                while true {
                    let keepGoing = autoreleasepool { () -> Bool in
                        guard let sample = output.copyNextSampleBuffer() else { return false }
                        defer { CMSampleBufferInvalidate(sample) }
                        guard let buffer = CMSampleBufferGetImageBuffer(sample) else { return true }
                        // Rebase to the clip's own zero, as Android does. A clip that kept
                        // the source's timestamps would start playing at minute nine.
                        let presentation = CMTimeSubtract(CMSampleBufferGetPresentationTimeStamp(sample), startTime)
                        do {
                            try Self.waitUntilReady(
                                name: outName,
                                isReady: { input.isReadyForMoreMediaData },
                                status: { writer.status },
                                error: { writer.error }
                            )
                        } catch {
                            // The writer's error rather than the one thrown: everything
                            // that ends this loop is wrapped once, below, and wrapping a
                            // `writeFailed` in a `writeFailed` reads "couldn't write x:
                            // couldn't write x". A writer that stopped without an error
                            // leaves this nil and is caught by the status check below.
                            failure = writer.error
                            return false
                        }
                        guard let timed = Self.retimed(sample, buffer: buffer, to: presentation) else { return true }
                        if !input.append(timed) {
                            failure = writer.error
                            return false
                        }
                        rendered += 1
                        return true
                    }
                    if !keepGoing { break }
                }
                continuation.resume(returning: (rendered, failure))
            }
        }

        let rendered = copyResult.rendered
        var failure = copyResult.failure

        input.markAsFinished()
        if reader.status == .failed { failure = failure ?? reader.error }
        await writer.finishWriting()

        if let failure {
            try? FileManager.default.removeItem(at: out)
            throw Failure.writeFailed(out.lastPathComponent, failure)
        }
        if writer.status != .completed {
            try? FileManager.default.removeItem(at: out)
            throw Failure.writeFailed(out.lastPathComponent, writer.error)
        }
        guard rendered > 0 else {
            try? FileManager.default.removeItem(at: out)
            throw Failure.noFramesInWindow(source.lastPathComponent, start, end)
        }
    }

    /// Blocks until the writer's input will take another sample, or throws once
    /// it never will.
    ///
    /// Busy-waiting, because the alternative here is the callback form. The
    /// writer drains in microseconds at this bitrate and the queue this runs on
    /// has nothing else waiting.
    ///
    /// The status check is the point of this being a function. The loop used to
    /// wait on `isReadyForMoreMediaData` alone, and a writer that has stopped -
    /// AVError -11847 when the app is backgrounded mid-encode is the one that
    /// happens - never raises that flag again: the analysis queue spun for good
    /// and the run neither finished nor failed. Anything but `.writing` means
    /// no sample will ever be accepted, so it ends the wait with the writer's
    /// own error, which is what `append` returning false would have reported
    /// had the loop ever got that far.
    ///
    /// Closures rather than the writer and its input, so the rule can be tested
    /// against a writer in a state AVFoundation cannot be put into on demand.
    static func waitUntilReady(
        name: String,
        isReady: () -> Bool,
        status: () -> AVAssetWriter.Status,
        error: () -> Error?,
        pause: () -> Void = { usleep(500) }
    ) throws {
        while !isReady() {
            // Status second: a writer that is ready is by definition writing,
            // and this order costs the healthy path nothing.
            guard status() == .writing else { throw Failure.writeFailed(name, error()) }
            pause()
        }
    }

    /// The same pixels with a new presentation time.
    ///
    /// `AVAssetWriterInput.append` takes the sample's timestamp as it stands, so
    /// rebasing means building a new sample buffer around the same image buffer
    /// rather than mutating the old one. `AVAssetWriterInputPixelBufferAdaptor`
    /// would do this too, at the cost of a pixel-buffer pool copy per frame.
    private static func retimed(
        _ sample: CMSampleBuffer, buffer: CVPixelBuffer, to presentation: CMTime
    ) -> CMSampleBuffer? {
        var timing = CMSampleTimingInfo(
            duration: CMSampleBufferGetDuration(sample),
            presentationTimeStamp: presentation,
            decodeTimeStamp: .invalid
        )
        var format: CMFormatDescription?
        guard CMVideoFormatDescriptionCreateForImageBuffer(
            allocator: kCFAllocatorDefault, imageBuffer: buffer, formatDescriptionOut: &format
        ) == noErr, let format else { return nil }
        var out: CMSampleBuffer?
        guard CMSampleBufferCreateReadyWithImageBuffer(
            allocator: kCFAllocatorDefault, imageBuffer: buffer,
            formatDescription: format, sampleTiming: &timing, sampleBufferOut: &out
        ) == noErr else { return nil }
        return out
    }
}
