import CoreVideo
import Foundation
import Shared

/// The badminton detector, for the shuttle positions TrackNet misses.
///
/// Port of Android's `DetectorRunner`. The cloud's per-frame track prefers
/// TrackNet and falls back to this, and on one real capture the fallback
/// supplied 1,472 of 4,355 positions - a third. Without it the fusion track and
/// the TrackNet track are the same thing, and rally counts cannot be compared
/// with the cloud's.
///
/// Classes are `0: person, 1: racket, 2: shuttle`; only the shuttle is used
/// here. The cloud matches its class by name against a list including
/// "shuttle", "shuttlecock", "birdie" and "ball", which on this model resolves
/// to index 2.
final class DetectorRunner {

    static let size = 640
    private static let shuttleClass = 2
    private static let classes = 3
    /// Ultralytics' letterbox fill.
    private static let pad: UInt8 = 114
    /// Ultralytics' predict default, which is what the cloud gets by calling
    /// the model with no `conf` argument.
    static let confidence: Float = 0.25
    /// Ultralytics' default NMS IoU threshold.
    static let iou: Float = 0.45

    private let session: OnnxSession
    private var input: [Float]
    private var letterboxed: [UInt8]

    init(modelPath: String) throws {
        session = try OnnxSession(modelPath: modelPath)
        input = [Float](repeating: 0, count: 3 * Self.size * Self.size)
        letterboxed = [UInt8](repeating: Self.pad, count: Self.size * Self.size * 3)
    }

    /// Detections in SOURCE pixel coordinates, centre points.
    ///
    /// The cloud stores a detection's `x`/`y` as the box centre, and the fusion
    /// gates compare those against the court polygon, so returning corners here
    /// would put every detection in the wrong place.
    func detect(_ buffer: CVPixelBuffer) throws -> [ShuttleDetection] {
        let box = Letterbox(source: buffer, size: Self.size)
        // Refilled every frame, not once: the previous frame's image occupies
        // the middle, and a source whose aspect ratio changed would leave its
        // pixels in the new padding.
        for i in letterboxed.indices { letterboxed[i] = Self.pad }
        FramePreprocessor.toRgbResizedInto(
            buffer, canvas: &letterboxed, canvasWidth: Self.size,
            offsetX: box.padX, offsetY: box.padY, fitWidth: box.fitWidth, fitHeight: box.fitHeight
        )
        FramePreprocessor.toChwTensor(letterboxed, width: Self.size, height: Self.size, into: &input)

        let output = try session.run(
            input: input,
            shape: [1, 3, NSNumber(value: Self.size), NSNumber(value: Self.size)]
        )
        // Output is (1, 4 + classes, anchors), channels-first: all the cx
        // values, then all the cy values, and so on.
        let anchors = output.count / (4 + Self.classes)
        var raw: [Candidate] = []
        for a in 0..<anchors {
            let score = output[(4 + Self.shuttleClass) * anchors + a]
            if score < Self.confidence { continue }
            let cx = output[a], cy = output[anchors + a]
            let w = output[2 * anchors + a], h = output[3 * anchors + a]
            raw.append(Candidate(
                x1: cx - w / 2, y1: cy - h / 2, x2: cx + w / 2, y2: cy + h / 2, score: score
            ))
        }

        return nonMaximumSuppression(raw).map { candidate in
            let centre = box.toSource(
                x: Double((candidate.x1 + candidate.x2) / 2),
                y: Double((candidate.y1 + candidate.y2) / 2)
            )
            return ShuttleDetection(
                x: min(max(centre.x, 0), Double(box.sourceWidth)),
                y: min(max(centre.y, 0), Double(box.sourceHeight)),
                confidence: Double(candidate.score)
            )
        }
    }

    private struct Candidate {
        let x1, y1, x2, y2, score: Float
    }

    /// Greedy non-maximum suppression, highest confidence first.
    private func nonMaximumSuppression(_ boxes: [Candidate]) -> [Candidate] {
        var kept: [Candidate] = []
        for box in boxes.sorted(by: { $0.score > $1.score }) {
            if !kept.contains(where: { intersectionOverUnion($0, box) > Self.iou }) { kept.append(box) }
        }
        return kept
    }

    private func intersectionOverUnion(_ a: Candidate, _ b: Candidate) -> Float {
        let x1 = max(a.x1, b.x1), y1 = max(a.y1, b.y1)
        let x2 = min(a.x2, b.x2), y2 = min(a.y2, b.y2)
        let intersection = max(0, x2 - x1) * max(0, y2 - y1)
        let areaA = (a.x2 - a.x1) * (a.y2 - a.y1)
        let areaB = (b.x2 - b.x1) * (b.y2 - b.y1)
        let union = areaA + areaB - intersection
        return union <= 0 ? 0 : intersection / union
    }
}

/// Ultralytics' letterbox: scale to fit, pad the short axis, centred.
///
/// Shared by the two YOLO models rather than written out in each, which is what
/// Android does - and where the two copies had already begun to differ in how
/// they undo it. Resizing to a square instead of letterboxing would stretch the
/// image and move every box, since both models were trained on letterboxed
/// input.
struct Letterbox {
    let sourceWidth: Int
    let sourceHeight: Int
    let scale: Double
    let fitWidth: Int
    let fitHeight: Int
    let padX: Int
    let padY: Int

    init(source: CVPixelBuffer, size: Int) {
        sourceWidth = CVPixelBufferGetWidth(source)
        sourceHeight = CVPixelBufferGetHeight(source)
        scale = min(Double(size) / Double(sourceWidth), Double(size) / Double(sourceHeight))
        fitWidth = Int(Double(sourceWidth) * scale)
        fitHeight = Int(Double(sourceHeight) * scale)
        padX = (size - fitWidth) / 2
        padY = (size - fitHeight) / 2
    }

    /// Undo the letterbox, then the scale, to land back in source pixels.
    func toSource(x: Double, y: Double) -> (x: Double, y: Double) {
        ((x - Double(padX)) / scale, (y - Double(padY)) / scale)
    }
}
