import AVFoundation
import CoreVideo
import Foundation

/// Decoding for the local analysis pass.
///
/// Two passes, not one, and not three. Port of Android's `VideoFrameSource`,
/// and the same correction to the pipeline design's "one decode pass" applies:
/// production's first step is a median background computed from up to 300
/// frames sampled evenly across the WHOLE video, and that background is an
/// input to the very first inference of the main pass, so it cannot be computed
/// lazily as the main pass goes.
///
/// **Neither pass seeks.** Android reaches its 300 sampled frames with
/// `MediaMetadataRetriever.getFrameAtIndex`, which does. On this platform the
/// only frame-indexed-looking API is `AVAssetImageGenerator`, and it is not
/// frame-indexed at all - it seeks by *time*, and on a variable-frame-rate
/// source a time-derived index lands on a different frame than production's
/// `cap.set(CAP_PROP_POS_FRAMES, idx)`. So the background pass reads the file
/// straight through and keeps the frames whose index is in the sampled set. It
/// costs one full decode with no inference attached, which is small beside a
/// pass running three models per frame, and it gets production's exact frames
/// by construction rather than by approximation.
final class VideoFrameSource {

    /// What the container says about the track, read once.
    struct Metadata {
        let frameCount: Int
        let width: Int
        let height: Int
        let fps: Double
    }

    enum Failure: LocalizedError {
        case noVideoTrack(String)
        case unreadable(String, Error)
        case decodeFailed(String, Error?)

        var errorDescription: String? {
            switch self {
            case .noVideoTrack(let name): return "no video track in \(name)"
            case .unreadable(let name, let error): return "cannot read \(name): \(error.localizedDescription)"
            case .decodeFailed(let name, let error):
                return "decoding \(name) failed: \(error?.localizedDescription ?? "unknown")"
            }
        }
    }

    private let url: URL
    private let name: String

    init(url: URL) {
        self.url = url
        self.name = url.lastPathComponent
    }

    /// One decoded frame, as the two passes hand it to their callers.
    ///
    /// The pixel buffer is only valid for the duration of the call it is passed
    /// to, matching Android's `Image`: the reader recycles the underlying
    /// memory as soon as the sample buffer is released, and holding one past
    /// the callback reads whatever the next frame wrote there.
    struct Frame {
        let index: Int
        /// The container's presentation time, in seconds.
        ///
        /// Never `index / fps`. On a variable-frame-rate source those disagree,
        /// and the cloud's own timestamps are container timestamps too; a
        /// skeleton drawn over playback is where the difference shows.
        let seconds: Double
        let pixelBuffer: CVPixelBuffer
    }

    // MARK: - Metadata

    /// Reads the track's dimensions and counts its samples.
    ///
    /// Counting walks the container without decompressing anything, the way
    /// Android's `MediaExtractor` sample walk does: one container sample is one
    /// encoded frame for every codec this app decodes. Estimating instead -
    /// `nominalFrameRate` times duration - is what the variable-frame-rate
    /// corpus punishes, and the count sets every frame index and therefore
    /// every rally boundary.
    ///
    /// Cached, because both passes and the coordinator ask for it.
    func metadata() throws -> Metadata {
        if let cached = cachedMetadata { return cached }
        let asset = AVURLAsset(url: url)
        guard let track = try loadVideoTrack(asset) else { throw Failure.noVideoTrack(name) }

        let size = try awaitValue { try await track.load(.naturalSize) }
        // A portrait recording is stored landscape with a rotation transform,
        // and every model here is fed the pixels as decoded. Applying the
        // transform would give the dimensions a viewer sees; the raw size is
        // what the pixel buffers actually carry, and court keypoints were
        // marked against those. Taking the presented size would rotate every
        // coordinate this pipeline produces.
        let duration = try awaitValue { try await asset.load(.duration) }

        let frames = try countSamples(asset: asset, track: track)
        let seconds = CMTimeGetSeconds(duration)
        let meta = Metadata(
            frameCount: frames,
            width: Int(size.width.rounded()),
            height: Int(size.height.rounded()),
            // Frames over duration, matching Android, rather than
            // `nominalFrameRate`: that reports the recording rate rather than
            // the playback rate and is a round number on files whose real rate
            // is not.
            fps: seconds > 0 ? Double(frames) / seconds : 0
        )
        cachedMetadata = meta
        return meta
    }

    /// Walks the video track counting samples, decoding nothing.
    ///
    /// `outputSettings: nil` is what makes that true: the output then vends
    /// samples in the format they are stored in, so this is container parsing
    /// rather than a third full decode of the file. With decompression
    /// requested - which is what every other reader here asks for - counting a
    /// 30-minute match would cost as much as the background pass, before the
    /// progress bar has anything to report.
    private func countSamples(asset: AVURLAsset, track: AVAssetTrack) throws -> Int {
        let reader: AVAssetReader
        do {
            reader = try AVAssetReader(asset: asset)
        } catch {
            throw Failure.unreadable(name, error)
        }
        let output = AVAssetReaderTrackOutput(track: track, outputSettings: nil)
        output.alwaysCopiesSampleData = false
        guard reader.canAdd(output) else { throw Failure.decodeFailed(name, nil) }
        reader.add(output)
        guard reader.startReading() else { throw Failure.decodeFailed(name, reader.error) }
        defer { if reader.status == .reading { reader.cancelReading() } }

        var count = 0
        while let sample = output.copyNextSampleBuffer() {
            // A sample with no duration and no data is a marker, not a frame.
            // Counting one would shift every index after it.
            if CMSampleBufferGetNumSamples(sample) > 0 { count += CMSampleBufferGetNumSamples(sample) }
            CMSampleBufferInvalidate(sample)
        }
        // A reader that stopped on an error looks exactly like one that reached
        // the end, and a short count is a plausible-looking wrong analysis
        // rather than a failure.
        if reader.status == .failed { throw Failure.decodeFailed(name, reader.error) }
        return count
    }

    private var cachedMetadata: Metadata?

    // MARK: - Pass 1: the background samples

    /// Decode the sampled frames for the median background, one at a time.
    ///
    /// Streamed through `transform` rather than collected first, for the reason
    /// Android's version records: 300 decoded 1920x1080 frames held at once is
    /// about 2.5GB and kills the process well before anything resizes them down
    /// to what the model needs. What this holds is 300 of whatever `transform`
    /// returns, which in practice is a 512x288 RGB byte array - about 130MB.
    ///
    /// The indices come from `:analysis`' `backgroundSampleIndices`, the same
    /// function Android calls, so the two platforms cannot sample different
    /// frames.
    func sampleFramesForBackground<T>(
        indices: [Int],
        transform: (CVPixelBuffer) throws -> T
    ) throws -> [T] {
        guard !indices.isEmpty else { return [] }
        // A set, not a sorted-array cursor: the cursor version is faster and
        // gets subtly wrong the moment the caller hands over unsorted indices,
        // which nothing here guarantees.
        let wanted = Set(indices)
        var samples: [T] = []
        samples.reserveCapacity(indices.count)
        try forEachFrame { frame in
            guard wanted.contains(frame.index) else { return }
            samples.append(try transform(frame.pixelBuffer))
        }
        return samples
    }

    // MARK: - Pass 2: every frame, in order

    /// One sequential pass over every frame, no seeking.
    ///
    /// `maxFrames` stops the pass early. Present for bounded measurement on a
    /// device, not for production use: a partial track silently produces
    /// rallies for part of a match.
    func forEachFrame(maxFrames: Int = .max, body: (Frame) throws -> Void) throws {
        let asset = AVURLAsset(url: url)
        guard let track = try loadVideoTrack(asset) else { throw Failure.noVideoTrack(name) }

        let reader: AVAssetReader
        do {
            reader = try AVAssetReader(asset: asset)
        } catch {
            throw Failure.unreadable(name, error)
        }
        let output = AVAssetReaderTrackOutput(
            track: track,
            outputSettings: [
                // The YUV the decoder produces, not BGRA. Letting AVFoundation
                // convert would hand the colour matrix to a framework whose
                // choice is undocumented and version-dependent, and
                // FramePreprocessor's whole job is to make that choice
                // deliberately: production decodes this BT.709 footage with
                // BT.601 coefficients, and matching the cloud's implementation
                // beats matching the standard it claims to follow.
                kCVPixelBufferPixelFormatTypeKey as String:
                    Int(kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange)
            ]
        )
        // The reader keeps only what the caller has not consumed. This pass is
        // strictly sequential and each frame is finished with before the next
        // is asked for, so buffering ahead would cost memory for nothing.
        output.alwaysCopiesSampleData = false
        guard reader.canAdd(output) else { throw Failure.decodeFailed(name, nil) }
        reader.add(output)
        guard reader.startReading() else { throw Failure.decodeFailed(name, reader.error) }
        defer { if reader.status == .reading { reader.cancelReading() } }

        var index = 0
        while index < maxFrames, let sample = output.copyNextSampleBuffer() {
            defer { CMSampleBufferInvalidate(sample) }
            guard let buffer = CMSampleBufferGetImageBuffer(sample) else { continue }
            let time = CMSampleBufferGetPresentationTimeStamp(sample)
            CVPixelBufferLockBaseAddress(buffer, .readOnly)
            defer { CVPixelBufferUnlockBaseAddress(buffer, .readOnly) }
            try body(Frame(index: index, seconds: CMTimeGetSeconds(time), pixelBuffer: buffer))
            index += 1
        }

        // A reader that stopped on an error looks exactly like one that reached
        // the end: copyNextSampleBuffer returns nil for both. Without this
        // check a file that failed to decode halfway would produce a shorter
        // frame list and a plausible-looking, wrong analysis.
        if reader.status == .failed { throw Failure.decodeFailed(name, reader.error) }
    }

    // MARK: - Plumbing

    private func loadVideoTrack(_ asset: AVURLAsset) throws -> AVAssetTrack? {
        try awaitValue { try await asset.loadTracks(withMediaType: .video).first }
    }

    /// Bridges AVFoundation's async loaders into this synchronous pass.
    ///
    /// The pass itself cannot be async: it hands a `CVPixelBuffer` to a callback
    /// that must finish with it before the next frame is read, and a suspension
    /// point inside that callback would let the reader recycle the buffer under
    /// it. So the two `load` calls are bridged rather than the pass being turned
    /// inside out. The caller is already on a background queue - this is never
    /// reached from the main actor - so blocking here blocks nothing a person
    /// can see.
    private func awaitValue<T>(_ work: @escaping () async throws -> T) throws -> T {
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
