import XCTest
@testable import iosApp

/// That ONNX Runtime loads and runs the bundled graphs on this platform.
///
/// The iOS half of Android's `OnnxSessionTest`, and the first thing that proves
/// the runtime works here rather than on a laptop. Everything downstream -
/// decode, preprocessing, the three runners - is worth nothing if a graph
/// cannot be loaded, and this is the cheapest place to learn that.
///
/// Skipped rather than failed when no graph is staged. `ONNX_MODELS_OPTIONAL=YES`
/// builds have none, which is every CI run and every checkout that has not run
/// the export scripts, and a red suite there would say "the export was not run",
/// which is not a defect in this code.
final class OnnxSessionTests: XCTestCase {

    /// Seeded noise, never zeros.
    ///
    /// Desktop measurement recorded that an all-zero image drives YOLO26's
    /// score-indexed postprocessing out of range and kills the run with
    /// `GatherElements op: Out of range value in index tensor`. Seeded rather
    /// than random so a failure is reproducible.
    private func noise(count: Int, seed: UInt64 = 0x5EED) -> [Float] {
        var state = seed
        return (0..<count).map { _ in
            // xorshift64*, which needs no dependency and is more than uniform
            // enough for pixels the model only has to not choke on.
            state ^= state >> 12
            state ^= state << 25
            state ^= state >> 27
            return Float(Double((state &* 2_685_821_657_736_338_717) >> 40) / Double(1 << 24))
        }
    }

    private func session(for model: Model) throws -> OnnxSession {
        guard let path = ModelCatalog.path(model) else {
            throw XCTSkip("\(model.rawValue) is not staged: build with the ONNX graphs exported")
        }
        return try OnnxSession(modelPath: path)
    }

    func testDetectorProducesUltralyticsOutputShape() throws {
        let session = try self.session(for: .detector)
        let size = 640
        let output = try session.run(
            input: noise(count: 3 * size * size),
            shape: [1, 3, NSNumber(value: size), NSNumber(value: size)]
        )
        // [1, 7, 8400]: four box terms plus three classes, over 8400 anchors.
        // The shape the desktop export produced, recorded in
        // tools/models/README.md. A different one means the graph in the bundle
        // is not the graph DetectorRunner decodes.
        XCTAssertEqual(output.count, 7 * 8400)
    }

    func testTrackNetProducesOneHeatmapPerSequenceFrame() throws {
        let session = try self.session(for: .trackNet)
        let width = 512, height = 288
        // 27 planes: a background frame plus eight sequence frames, three
        // channels each.
        let output = try session.run(
            input: noise(count: 27 * width * height),
            shape: [1, 27, NSNumber(value: height), NSNumber(value: width)]
        )
        XCTAssertEqual(output.count, 8 * width * height)
    }

    func testPoseProducesThreeHundredRows() throws {
        let session = try self.session(for: .pose)
        let size = 960
        let output = try session.run(
            input: noise(count: 3 * size * size),
            shape: [1, 3, NSNumber(value: size), NSNumber(value: size)]
        )
        // [1, 300, 57]: an end-to-end export, NMS already applied, 300 rows of
        // four box terms, a confidence, a class and 17 keypoints of three.
        XCTAssertEqual(output.count, 300 * 57)
    }

    /// The same input twice gives the same output.
    ///
    /// Not a property of ONNX Runtime worth assuming: the pipeline design's
    /// section 3.9 rests on a re-run over the same video producing the same
    /// clip bounds, and a nondeterministic runtime would break that quietly,
    /// as differing rally counts rather than as an error.
    func testInferenceIsDeterministic() throws {
        let session = try self.session(for: .detector)
        let size = 640
        let input = noise(count: 3 * size * size)
        let shape: [NSNumber] = [1, 3, NSNumber(value: size), NSNumber(value: size)]
        XCTAssertEqual(try session.run(input: input, shape: shape),
                       try session.run(input: input, shape: shape))
    }

    /// The model identity the bundle carries, which the re-anchoring rule keys
    /// on. Present exactly when the graphs are.
    func testModelVersionTravelsWithTheGraphs() throws {
        guard ModelCatalog.canAnalyseOnDevice else {
            throw XCTSkip("no graphs staged")
        }
        let version = try XCTUnwrap(ModelCatalog.version)
        // Three eight-hex terms joined by dashes, as
        // androidApp/build.gradle.kts derives it.
        XCTAssertEqual(version.split(separator: "-").count, 3)
        XCTAssertTrue(version.split(separator: "-").allSatisfy { $0.count == 8 })
    }
}
