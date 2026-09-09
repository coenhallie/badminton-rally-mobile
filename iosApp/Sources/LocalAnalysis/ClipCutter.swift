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
    ) throws -> [PlayerTrackStore.Clip] {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        var clips: [PlayerTrackStore.Clip] = []
        for (i, window) in windows.enumerated() {
            // rally_index is 1-based throughout this project, and the clip
            // filenames follow it so a file can be matched to a row by eye.
            let index = i + 1
            let out = directory.appendingPathComponent("rally-\(index).mp4")
            try cutOne(source: source, start: window.clipStart, end: window.clipEnd, to: out)
            clips.append(PlayerTrackStore.Clip(
                index: index, url: out,
                startSeconds: window.clipStart, endSeconds: window.clipEnd
            ))
            onProgress(clips.count)
        }
        return clips
    }

    private func cutOne(source: URL, start: Double, end: Double, to out: URL) throws {
        // A stale file from an interrupted run would make the writer refuse to
        // start, and the error it gives says nothing about why.
        try? FileManager.default.removeItem(at: out)

        let asset = AVURLAsset(url: source)
        guard let track = try loadTrack(asset) else { throw Failure.noVideoTrack(source.lastPathComponent) }
        let size = try load { try await track.load(.naturalSize) }

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

        let writer = try AVAssetWriter(outputURL: out, fileType: .mp4)
        let input = AVAssetWriterInput(mediaType: .video, outputSettings: [
            AVVideoCodecKey: AVVideoCodecType.h264,
            AVVideoWidthKey: Int(size.width.rounded()),
            AVVideoHeightKey: Int(size.height.rounded()),
            AVVideoCompressionPropertiesKey: [
                // Generous rather than tuned, matching Android: these clips are
                // the reviewing surface for a coach looking at a shuttle a few
                // pixels across, and re-encoding artifacts there defeat the
                // point of the app.
                AVVideoAverageBitRateKey: max(Int(size.width * size.height * 8), 4_000_000),
                // Every second. A clip is seconds long and gets scrubbed, so
                // sparse keyframes make seeking within it unpleasant.
                AVVideoMaxKeyFrameIntervalDurationKey: 1.0,
            ],
        ])
        input.expectsMediaDataInRealTime = false
        guard writer.canAdd(input) else { throw Failure.writeFailed(out.lastPathComponent, nil) }
        writer.add(input)

        guard reader.startReading() else { throw Failure.writeFailed(out.lastPathComponent, reader.error) }
        guard writer.startWriting() else { throw Failure.writeFailed(out.lastPathComponent, writer.error) }
        writer.startSession(atSourceTime: .zero)

        let startTime = CMTime(seconds: start, preferredTimescale: 600)
        var rendered = 0
        var failure: Error?

        // Pull-driven rather than the callback form: this runs on the analysis
        // queue with nothing else to do, and requestMediaDataWhenReady would
        // hand the loop to a second queue for no benefit.
        while let sample = output.copyNextSampleBuffer() {
            defer { CMSampleBufferInvalidate(sample) }
            guard let buffer = CMSampleBufferGetImageBuffer(sample) else { continue }
            // Rebase to the clip's own zero, as Android does. A clip that kept
            // the source's timestamps would start playing at minute nine.
            let presentation = CMTimeSubtract(CMSampleBufferGetPresentationTimeStamp(sample), startTime)
            while !input.isReadyForMoreMediaData {
                // Busy-waiting, because the alternative here is the callback
                // form. The writer drains in microseconds at this bitrate and
                // the queue this runs on has nothing else waiting.
                usleep(500)
            }
            guard let timed = Self.retimed(sample, buffer: buffer, to: presentation) else { continue }
            if !input.append(timed) {
                failure = writer.error
                break
            }
            rendered += 1
        }

        input.markAsFinished()
        if reader.status == .failed { failure = failure ?? reader.error }
        let finished = DispatchSemaphore(value: 0)
        writer.finishWriting { finished.signal() }
        finished.wait()

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

    private func loadTrack(_ asset: AVURLAsset) throws -> AVAssetTrack? {
        try load { try await asset.loadTracks(withMediaType: .video).first }
    }

    /// Bridges AVFoundation's async loaders into this synchronous cut, for the
    /// reason `VideoFrameSource` does the same: the loop hands buffers to an
    /// encoder that must finish with them in order, and it runs on a background
    /// queue where blocking costs nothing a person can see.
    private func load<T>(_ work: @escaping () async throws -> T) throws -> T {
        let semaphore = DispatchSemaphore(value: 0)
        var result: Result<T, Error>!
        Task {
            do { result = .success(try await work()) } catch { result = .failure(error) }
            semaphore.signal()
        }
        semaphore.wait()
        return try result.get()
    }
}
