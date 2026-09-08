import CoreVideo
import Foundation
import Shared

/// Pose keypoints for the people in one frame.
///
/// Port of Android's `PoseRunner`. `yolo26n-pose` at 960, chosen by measurement
/// rather than by model card: on an S23 the medium model the cloud uses costs
/// about 1570ms a frame cold and 2200 settled, which is eight hours for an
/// eight-minute video; nano is 230ms. Nano's one real weakness is far-player
/// coverage, 44% of frames against 93% near, and this pipeline tracks only the
/// near player, so that weakness does not apply.
///
/// 960 rather than 640 even though 640 is twice as fast and the difference is
/// immaterial for a heatmap. At 640 the worst joint is the wrist, which is the
/// fastest-moving joint on a racket arm and the one a coach looks at.
///
/// Unlike `DetectorRunner` there is no NMS here. This export is end-to-end: the
/// graph emits a fixed 300 rows with NMS already applied, sorted by confidence,
/// so the work is a threshold and un-letterboxing rather than anchor decoding.
final class PoseRunner {

    static let size = 960
    /// Ultralytics' letterbox grey.
    private static let pad: UInt8 = 114
    /// Ultralytics' default, and low enough that the crowd is filtered by court
    /// position instead.
    private static let confidence: Float = 0.25
    private static let personClass = 0
    /// x1, y1, x2, y2, confidence, class, then 17 x (x, y, confidence).
    private static let keypointsOffset = 6

    private let session: OnnxSession
    private var input: [Float]
    private var letterboxed: [UInt8]
    private let keypointCount = Int(Coco.shared.COUNT)

    init(modelPath: String) throws {
        session = try OnnxSession(modelPath: modelPath)
        input = [Float](repeating: 0, count: 3 * Self.size * Self.size)
        letterboxed = [UInt8](repeating: Self.pad, count: Self.size * Self.size * 3)
    }

    private var stride: Int { Self.keypointsOffset + keypointCount * 3 }

    func detect(frame: Int, buffer: CVPixelBuffer) throws -> PoseFrame {
        let box = Letterbox(source: buffer, size: Self.size)
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
        return PoseFrame(frame: Int32(frame), people: decode(output, box: box))
    }

    private func decode(_ output: [Float], box: Letterbox) -> [PosePerson] {
        var people: [PosePerson] = []
        for row in 0..<(output.count / stride) {
            let base = row * stride
            let confidence = output[base + 4]
            // Sorted descending, and the tail is zero padding, so the first row
            // below the threshold ends the list rather than merely being
            // skipped.
            if confidence < Self.confidence { break }
            if Int(output[base + 5]) != Self.personClass { continue }

            var keypoints: [Point] = []
            var keypointConfidence: [KotlinFloat] = []
            keypoints.reserveCapacity(keypointCount)
            keypointConfidence.reserveCapacity(keypointCount)
            for k in 0..<keypointCount {
                let at = base + Self.keypointsOffset + k * 3
                let source = box.toSource(x: Double(output[at]), y: Double(output[at + 1]))
                keypoints.append(Point(x: source.x, y: source.y))
                keypointConfidence.append(KotlinFloat(float: output[at + 2]))
            }
            people.append(PosePerson(
                boxConfidence: confidence, keypoints: keypoints, keypointConfidence: keypointConfidence
            ))
        }
        return people
    }
}
