import Foundation
import OnnxRuntimeBindings

/// A thin ONNX Runtime wrapper.
///
/// Port of Android's `OnnxSession`, and deliberately thin for the same reason:
/// the pipeline design's section 8 keeps a native runtime swap as a designed-for
/// escape hatch, and every `ORTSession` that reaches a caller closes that hatch
/// a little further.
///
/// **No execution provider is added.** Android's version offers NNAPI and
/// XNNPACK and passes neither at any call site, so the two platforms agree by
/// running the same graphs on the same CPU provider. CoreML is the iPhone
/// analogue and it is a later change with its own parity gate, because handing
/// subgraphs to the Neural Engine changes numbers: the point of running the same
/// `.onnx` files on both platforms is that the A/B comparison has one variable,
/// and an execution provider is a second one.
final class OnnxSession {

    /// Anything ONNX Runtime reports, with the model that was being loaded or
    /// run. `NSError` alone says "load model from ..." and nothing about which
    /// of the three graphs, which is the first thing a crash report needs.
    struct Failure: LocalizedError {
        let model: String
        let stage: String
        let underlying: Error
        var errorDescription: String? {
            "\(stage) \(model): \(underlying.localizedDescription)"
        }
    }

    /// One environment for the process.
    ///
    /// `ORTEnv` owns the runtime's thread pools and its logger; creating one per
    /// session would build and tear those down three times per frame. The
    /// analysis holds three sessions at once and runs them in lockstep, so they
    /// share it.
    private static let env: Result<ORTEnv, Error> = Result {
        try ORTEnv(loggingLevel: ORTLoggingLevel.warning)
    }

    private let session: ORTSession
    private let name: String
    let inputName: String
    let outputName: String

    init(modelPath: String) throws {
        name = (modelPath as NSString).lastPathComponent
        do {
            let options = try ORTSessionOptions()
            try options.setGraphOptimizationLevel(ORTGraphOptimizationLevel.all)
            session = try ORTSession(
                env: try OnnxSession.env.get(),
                modelPath: modelPath,
                sessionOptions: options
            )
            // Read once, and checked rather than assumed: every graph this app
            // loads has exactly one input and one output, which is what makes
            // taking the only element correct. A graph with two would need its
            // names quoted at the call site, and the check below is what turns
            // that from silently feeding the wrong tensor into a clear failure.
            let inputs = try session.inputNames()
            let outputs = try session.outputNames()
            guard inputs.count == 1, outputs.count == 1,
                  let input = inputs.first, let output = outputs.first
            else {
                throw Failure(
                    model: name,
                    stage: "load",
                    underlying: NSError(
                        domain: "OnnxSession", code: 1,
                        userInfo: [NSLocalizedDescriptionKey:
                            "expected one input and one output, found \(inputs.count) and \(outputs.count)"]
                    )
                )
            }
            inputName = input
            outputName = output
        } catch let failure as Failure {
            throw failure
        } catch {
            throw Failure(model: name, stage: "load", underlying: error)
        }
    }

    /// Run one inference.
    ///
    /// `shape` comes from the caller rather than from the graph, matching
    /// Android: every graph here has static shapes today, but the InpaintNet
    /// case the Android comment names - a genuinely dynamic length axis fed
    /// several sizes from one session - is the reason the parameter exists, and
    /// removing it would have to be undone the moment a dynamic graph arrives.
    ///
    /// The returned array is the flat tensor in the graph's own layout. Nothing
    /// here reshapes it: TrackNet's caller wants planes, the detector's wants
    /// channel-first anchors, and a shared "helpful" reshape would be wrong for
    /// one of them.
    func run(input: [Float], shape: [NSNumber]) throws -> [Float] {
        do {
            // ORTValue does NOT copy: it wraps the bytes and the caller must
            // keep them alive for as long as the value is. Holding `data` in a
            // local through the run is what does that - handing
            // `NSMutableData(bytes:length:)` straight into the initialiser as a
            // temporary is the classic way to get a tensor of freed memory,
            // which reads as nondeterministic model output rather than as a
            // crash.
            let data = input.withUnsafeBufferPointer {
                NSMutableData(bytes: $0.baseAddress, length: $0.count * MemoryLayout<Float>.size)
            }
            let value = try ORTValue(
                tensorData: data,
                elementType: ORTTensorElementDataType.float,
                shape: shape
            )
            let outputs = try session.run(
                withInputs: [inputName: value],
                outputNames: [outputName],
                runOptions: try ORTRunOptions()
            )
            guard let result = outputs[outputName] else {
                throw Failure(
                    model: name, stage: "run",
                    underlying: NSError(
                        domain: "OnnxSession", code: 2,
                        userInfo: [NSLocalizedDescriptionKey: "no output named \(outputName)"]
                    )
                )
            }
            let bytes = try result.tensorData() as Data
            // withUnsafeBytes over the whole buffer, rather than an element
            // loop: TrackNet's output is 1.2M floats per sequence and the naive
            // loop is a bounds check and a retain per element. Android's own
            // comment on this function records the same class of mistake
            // costing it a 256MB heap.
            return bytes.withUnsafeBytes { raw in
                Array(raw.bindMemory(to: Float.self))
            }
        } catch let failure as Failure {
            throw failure
        } catch {
            throw Failure(model: name, stage: "run", underlying: error)
        }
    }
}
