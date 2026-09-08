import CoreVideo
import Foundation
import Shared

/// The shuttle track, produced the way production produces it.
///
/// Port of Android's `TrackNetRunner`, which ports
/// `TrackNetInference.track_video` (`inference.py:145-192`). Three stages, and
/// the pipeline design's section 5.4 is explicit that an earlier draft omitted
/// all three and that none is optional:
///
///  1. A median background over sampled frames, not the first frame. A median
///     removes everything that moves; one frame contains a player mid-swing.
///  2. Blob detection with an area filter and a weighted centroid, not an
///     argmax. That lives in `:analysis` as `heatmapToCoord` and is called from
///     here rather than reimplemented - see `BulkInterop` for how a buffer that
///     large crosses the boundary.
///  3. InpaintNet gap-filling. **Not run**, matching Android, which never
///     implemented it: section 6.3 measured it filling zero frames on both
///     corpus videos, with production's own PyTorch path filling zero too.
final class TrackNetRunner {

    static let width = 512
    static let height = 288
    /// 8 sequence frames plus one background frame, 3 channels each.
    static let sequenceLength = 8
    static let channels = (sequenceLength + 1) * 3
    private static let plane = width * height

    private let source: VideoFrameSource
    private let modelPath: String

    init(source: VideoFrameSource, modelPath: String) {
        self.source = source
        self.modelPath = modelPath
    }

    /// Everything a frame of the main pass hands to the models that share its
    /// decode.
    ///
    /// The detector and pose run through this rather than in passes of their
    /// own: decode and colour conversion are about a third of the per-frame
    /// cost, and paying them again to run a second model on the same pixels
    /// would be careless.
    typealias FrameHandler = (_ index: Int, _ seconds: Double, _ buffer: CVPixelBuffer) throws -> Void

    /// - Parameters:
    ///   - sourceWidth: source-video pixels, used only to scale the result back
    ///     out of model space (`inference.py:154-155`, 178-180).
    ///   - onFrame: called for every decoded frame, before it is consumed.
    func track(
        sourceWidth: Int,
        sourceHeight: Int,
        maxFrames: Int = .max,
        onProgress: (Float) -> Void = { _ in },
        onFrame: FrameHandler = { _, _, _ in }
    ) throws -> [Int: ShuttleSample] {
        let session = try OnnxSession(modelPath: modelPath)
        var positions: [Int: ShuttleSample] = [:]

        let widthScale = Double(sourceWidth) / Double(Self.width)
        let heightScale = Double(sourceHeight) / Double(Self.height)

        // One sequence's input, reused. 27 planes at 512x288 is 5.6MB as
        // floats, and allocating that per sequence would churn thousands of
        // times over a match.
        var input = [Float](repeating: 0, count: Self.channels * Self.plane)
        let background = try computeBackground()
        input.replaceSubrange(0..<background.count, with: background)

        var resized = [UInt8](repeating: 0, count: Self.plane * 3)
        var sequenceIndices = [Int](repeating: 0, count: Self.sequenceLength)
        var inSequence = 0
        let total = try source.metadata().frameCount

        /// Run the sequence currently in `input` and read its heatmaps out.
        func flush() throws {
            guard inSequence > 0 else { return }
            // Pad a short trailing sequence by repeating its last frame, as
            // production does. The padded planes are computed but their outputs
            // are discarded below: emitting them would fabricate shuttle
            // positions past the end of the video.
            for i in inSequence..<Self.sequenceLength {
                let from = inSequence * 3 * Self.plane
                let to = (1 + i) * 3 * Self.plane
                input.replaceSubrange(to..<(to + 3 * Self.plane), with: input[from..<(from + 3 * Self.plane)])
            }
            let output = try session.run(
                input: input,
                shape: [1, NSNumber(value: Self.channels), NSNumber(value: Self.height), NSNumber(value: Self.width)]
            )
            output.withUnsafeBufferPointer { heatmaps in
                let base = UnsafeMutableRawPointer(mutating: heatmaps.baseAddress!)
                for i in 0..<inSequence {
                    // The blob detector reads straight out of the model's own
                    // output buffer, at an offset. No slice is copied: this is
                    // 590KB per frame and copying it would double the cost of
                    // the one step that is meant to be cheap.
                    let coordinate = BulkInterop.shared.heatmapToCoord(
                        heatmap: base.advanced(by: i * Self.plane * MemoryLayout<Float>.size),
                        width: Int32(Self.width),
                        height: Int32(Self.height),
                        threshold: HeatmapPeakKt.HEATMAP_THRESHOLD,
                        maxArea: HeatmapPeakKt.HEATMAP_MAX_AREA
                    )
                    positions[sequenceIndices[i]] = coordinate.visible
                        ? ShuttleSample(x: coordinate.x * widthScale, y: coordinate.y * heightScale, visible: true)
                        : ShuttleSample.companion.INVISIBLE
                }
            }
            inSequence = 0
        }

        try source.forEachFrame(maxFrames: maxFrames) { frame in
            try onFrame(frame.index, frame.seconds, frame.pixelBuffer)
            FramePreprocessor.toRgbResized(
                frame.pixelBuffer, into: &resized, width: Self.width, height: Self.height
            )
            // Background occupies planes 0...2, so frame i starts at
            // (1 + i) * 3. Ordering matters: production concatenates
            // [background, frames], and reversing it feeds the model a sequence
            // where the background is where a frame should be.
            FramePreprocessor.toChwTensor(
                resized, width: Self.width, height: Self.height,
                into: &input, offset: (1 + inSequence) * 3 * Self.plane
            )
            sequenceIndices[inSequence] = frame.index
            inSequence += 1
            if inSequence == Self.sequenceLength {
                try flush()
                // Clamped below 1: the contract on LocalInferenceEngine is a
                // fraction in [0, 1), because completion is the coordinator's
                // to report after the analysis that FOLLOWS inference, and a
                // bar that fills on the last decoded frame would sit finished
                // through the rally detection and the clip cutting.
                //
                // Android does not clamp here and reaches 1.0 on the last full
                // sequence; the coordinator's own coerceAtMost hides it. Same
                // ceiling used here, so the two agree on the number rather than
                // only on the behaviour.
                if total > 0 {
                    onProgress(min(Float(frame.index + 1) / Float(total), 0.999))
                }
            }
        }
        try flush()
        return positions
    }

    /// The median background plane, already scaled to [0,1] and in CHW.
    ///
    /// Sampled by frame index rather than timestamp, and truncating rather than
    /// rounding, both matching `_compute_median_background` through
    /// `:analysis`' own `backgroundSampleIndices`. Getting either wrong changes
    /// which frames form the background, which changes the background, which
    /// changes every heatmap.
    ///
    /// The per-frame work - convert, resize down to 512x288 - runs inside the
    /// sampling pass, so what is held across all 300 samples is 300 resized
    /// frames, about 130MB, rather than 300 source-resolution ones, which for a
    /// 1920x1080 source is about 2.5GB and kills the process outright.
    private func computeBackground() throws -> [Float] {
        let total = try source.metadata().frameCount
        let indices = MedianBackgroundKt
            .backgroundSampleIndices(totalFrames: Int32(total), maxSamples: MedianBackgroundKt.MAX_BACKGROUND_SAMPLES)
            .map { Int(truncating: $0) }

        // One contiguous buffer rather than an array of arrays: BulkInterop
        // takes a single pointer, and this is the shape the samples arrive in
        // anyway.
        var frames = [UInt8]()
        frames.reserveCapacity(indices.count * Self.plane * 3)
        var scratch = [UInt8](repeating: 0, count: Self.plane * 3)
        let samples = try source.sampleFramesForBackground(indices: indices) { buffer -> Bool in
            FramePreprocessor.toRgbResized(buffer, into: &scratch, width: Self.width, height: Self.height)
            frames.append(contentsOf: scratch)
            return true
        }
        guard !samples.isEmpty else {
            throw VideoFrameSource.Failure.decodeFailed("no frames sampled for the background", nil)
        }

        var median = [UInt8](repeating: 0, count: Self.plane * 3)
        frames.withUnsafeBufferPointer { source in
            median.withUnsafeMutableBufferPointer { out in
                BulkInterop.shared.medianBackgroundInto(
                    frames: UnsafeMutableRawPointer(mutating: source.baseAddress!),
                    frameCount: Int32(samples.count),
                    frameLength: Int32(Self.plane * 3),
                    out: UnsafeMutableRawPointer(out.baseAddress!)
                )
            }
        }

        var plane = [Float](repeating: 0, count: 3 * Self.plane)
        FramePreprocessor.toChwTensor(median, width: Self.width, height: Self.height, into: &plane)
        return plane
    }
}
