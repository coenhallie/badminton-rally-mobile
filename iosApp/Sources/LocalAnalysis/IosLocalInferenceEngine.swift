import CoreVideo
import Foundation
import Shared

/// The iOS platform layer: decode, run models, emit raw output.
///
/// Port of Android's `AndroidLocalInferenceEngine`, and it satisfies the whole
/// of the pipeline design's section 5.1 contract and nothing beyond it. It
/// computes no metric, assigns no player_id and decides no rally boundary -
/// those live in `:analysis`, which this calls through the `Shared` framework.
///
/// What it emits is section 5.2's contract exactly: the **unfiltered** TrackNet
/// peak per frame, plus the detector's boxes. Not a filtered or fused track -
/// the cloud derives two different tracks from these, its filtered track from
/// raw TrackNet and its fusion track from TrackNet with YOLO as fallback, and
/// collapsing them here would make one of the two rally detectors read the
/// wrong input.
///
/// All the models share one decode pass. Decode and colour conversion are about
/// a third of the per-frame cost, so running any of them separately would pay
/// that again.
final class IosLocalInferenceEngine: NSObject, LocalInferenceEngine {

    enum Failure: LocalizedError {
        case noModels
        case noSuchVideo(String)

        var errorDescription: String? {
            switch self {
            case .noModels:
                // The ONNX_MODELS_OPTIONAL build. Said in the words a coach
                // could act on rather than naming a build flag.
                return "This build has no analysis models, so it can only analyze in the cloud."
            case .noSuchVideo(let path):
                return "The video is missing or access was revoked: \(path)"
            }
        }
    }

    /// Stop after this many frames. For bounded measurement on a device only: a
    /// partial track silently produces rallies for part of a match, so
    /// production must never set it.
    private let maxFrames: Int

    /// Whether to run pose.
    ///
    /// Opt-in rather than always-on because pose roughly doubles the per-frame
    /// cost. A caller that only wants rallies must not pay for a player track
    /// it will not read.
    private let wantsPose: Bool

    /// Where the pass says how the camera tracking went. The same sink the
    /// coordinator logs to, so one run reads as one stream.
    private let log: @Sendable (String) -> Void

    init(maxFrames: Int = .max, wantsPose: Bool = false, log: @escaping @Sendable (String) -> Void = { _ in }) {
        self.maxFrames = maxFrames
        self.wantsPose = wantsPose
        self.log = log
    }

    /// The `LocalInferenceEngine` contract.
    ///
    /// The completion-handler shape, not the `async` one SKIE generates: that
    /// extension exists for CALLING a Kotlin engine from Swift, and what this
    /// class does is the other direction - it is the engine, and a Kotlin
    /// `suspend fun` reaches an Objective-C implementation as this method.
    ///
    /// The work is dispatched onto a background queue rather than run inline:
    /// the coordinator calls this from whatever context it is on, the run takes
    /// minutes, and blocking that context would freeze the caller. An error
    /// handed to the completion becomes a thrown Kotlin exception, which
    /// `LocalAnalysisCoordinator.analyze` already turns into a `Result.failure`
    /// the UI can render rather than a crash.
    /// Spelled `__run`, with the underscores, because SKIE takes the plain
    /// `run` name for the `async` extension it generates for CALLERS. The
    /// underscored one is the protocol requirement an implementation satisfies;
    /// a Kotlin caller still sees `engine.run(...)`.
    func __run(
        videoPath: String,
        markingTimeS: KotlinDouble?,
        onProgress: @escaping (KotlinFloat) -> Void,
        completionHandler: @escaping @Sendable (RawInference?, Error?) -> Void
    ) {
        Self.queue.async {
            do {
                let raw = try self.rawInference(
                    videoPath: videoPath,
                    markingTimeS: markingTimeS?.doubleValue
                ) { onProgress(KotlinFloat(float: $0)) }
                completionHandler(raw, nil)
            } catch {
                completionHandler(nil, error)
            }
        }
    }

    /// One queue for the process, serial.
    ///
    /// Serial deliberately: two concurrent analyses would hold six ONNX
    /// sessions and two decoders, and on a phone that is a memory kill rather
    /// than twice the throughput. `LocalAnalysisRunner` also refuses a second
    /// run for the same video, but nothing stops two different videos, and this
    /// is the level that can actually enforce it.
    private static let queue = DispatchQueue(label: "com.badmintontracker.local-analysis", qos: .userInitiated)

    /// The engine as Swift sees it: throwing, synchronous, no Kotlin in the
    /// call path.
    ///
    /// `__run` above is how KOTLIN calls this, and it is the only safe way to
    /// reach it from Kotlin. It is not a safe way to reach it from Swift:
    /// SKIE's generated `run` extension routes a Swift call back through a
    /// Kotlin coroutine, and an error raised here then surfaces there as an
    /// uncaught coroutine exception, which Kotlin/Native treats as fatal. The
    /// app never does that - the coordinator is Kotlin and wraps the call in
    /// `runCatching` - but a test or a future Swift caller easily would, and
    /// would get a process abort instead of a catchable error.
    ///
    /// So Swift callers use this, and the round trip does not exist.
    ///
    /// - Parameter markingTimeS: when in the video the court was marked; the
    ///   engine follows the camera from that frame when it runs pose. Nil: do
    ///   not follow the camera.
    func rawInference(
        videoPath: String,
        markingTimeS: Double?,
        onProgress: @escaping (Float) -> Void
    ) throws -> RawInference {
        let url = URL(fileURLWithPath: videoPath)
        guard FileManager.default.isReadableFile(atPath: url.path) else {
            throw Failure.noSuchVideo(videoPath)
        }
        guard let trackNetPath = ModelCatalog.path(.trackNet),
              let detectorPath = ModelCatalog.path(.detector)
        else { throw Failure.noModels }

        let source = VideoFrameSource(url: url)
        let metadata = try source.metadata()

        var detections: [Int: [ShuttleDetection]] = [:]
        var people: [Int: [RawPerson]] = [:]
        // The container's presentation time per frame. Never index / fps, which
        // is wrong on a variable-frame-rate source; a skeleton drawn over
        // playback is where that shows.
        var timestamps: [Int: Double] = [:]

        let detector = try DetectorRunner(modelPath: detectorPath)
        // Loaded only when asked for. A missing pose graph is not fatal even
        // when pose was asked for: Phase 1 is a complete analysis on its own,
        // and refusing the whole run would withhold the rally clips too.
        let pose = try wantsPose ? ModelCatalog.path(.pose).map { try PoseRunner(modelPath: $0) } : nil
        // Only beside pose: the camera corrects where a skeleton stands on the
        // court, and a rallies-only pass has none. Only with a marking time:
        // the camera is followed relative to the frame the marks were drawn
        // on, and without it there is nothing to anchor to.
        let camera = pose == nil ? nil : markingTimeS.flatMap {
            CameraFollower(markingTimeS: $0, sourceWidth: metadata.width, sourceHeight: metadata.height, log: log)
        }

        let track = try TrackNetRunner(source: source, modelPath: trackNetPath).track(
            sourceWidth: metadata.width,
            sourceHeight: metadata.height,
            maxFrames: maxFrames,
            onProgress: onProgress,
            onFrame: { index, seconds, buffer in
                timestamps[index] = seconds
                // Inside this callback, while the decoder holds the buffer
                // locked: the plane is read in place and never outlives it.
                camera?.follow(seconds: seconds, buffer: buffer)
                let found = try detector.detect(buffer)
                if !found.isEmpty { detections[index] = found }
                // Same frame, same decode. Emitted raw: which person is the
                // near player, and where they stand on the court, are
                // `:analysis` decisions.
                if let pose {
                    let found = try pose.detect(frame: index, buffer: buffer).people
                    if !found.isEmpty {
                        people[index] = found.map { person in
                            RawPerson(
                                box: RawBox(classId: 0, confidence: person.boxConfidence,
                                            x1: 0, y1: 0, x2: 0, y2: 0),
                                keypoints: person.keypoints.enumerated().map { k, point in
                                    RawKeypoint(
                                        x: Float(point.x), y: Float(point.y),
                                        confidence: person.keypointConfidence[k].floatValue
                                    )
                                }
                            )
                        }
                    }
                }
            }
        )

        let frameCount = maxFrames == .max ? metadata.frameCount : min(metadata.frameCount, maxFrames)

        // Every decoded frame gets a record, present or absent. A sparse frame
        // list would make the rally detectors' frame arithmetic wrong, since
        // they index by position rather than by key.
        let frames = (0..<frameCount).map { i -> RawFrame in
            let shuttle = track[i]
            return RawFrame(
                frame: Int32(i),
                timestamp: timestamps[i] ?? (metadata.fps > 0 ? Double(i) / metadata.fps : 0),
                shuttle: shuttle.map {
                    RawShuttle(x: Float($0.x), y: Float($0.y),
                               confidence: $0.visible ? 1 : 0, visible: $0.visible)
                },
                // class 2 is the shuttle, the only class the fusion reads.
                boxes: (detections[i] ?? []).map {
                    RawBox(classId: 2, confidence: Float($0.confidence),
                           x1: Float($0.x), y1: Float($0.y), x2: Float($0.x), y2: Float($0.y))
                },
                persons: people[i] ?? []
            )
        }

        return RawInference(
            header: RawHeader(
                version: RawInferenceCodec.shared.VERSION,
                fps: metadata.fps,
                totalFrames: Int32(frameCount),
                videoWidth: Int32(metadata.width),
                videoHeight: Int32(metadata.height),
                // Nil only in an ONNX_MODELS_OPTIONAL build, which cannot reach
                // this line: the model lookup above already failed.
                modelVersion: ModelCatalog.version ?? "unpinned"
            ),
            frames: frames,
            camera: camera?.finish()
        )
    }
}

/// The camera tracker as the decode pass drives it. Design
/// `2026-09-27-camera-motion-design.md` §4.1, §4.2 and §5.5.
///
/// Its own type rather than lines in the engine's frame callback because the
/// rules around the shared `CameraMotionTracker` are this platform's, and a
/// test must be able to hold a real decoded buffer to them without running
/// three models: the luma plane is read in place through `BulkInterop`, a plane
/// of the wrong size stops the tracking rather than being scaled, and nothing
/// the tracker does can fail the run.
///
/// That last is the rule everything here bends to. The camera refines where a
/// skeleton stands; without it the analysis falls back to the fixed marks, as
/// it did before there was a camera. So any failure is logged once and the
/// tracker is offered nothing more, and `finish` hands back what it followed
/// until then, the frames after it reading as camera lost.
///
/// Single-threaded, like the tracker: the decode queue owns it.
final class CameraFollower {

    private let tracker: CameraMotionTracker
    private let sourceWidth: Int
    private let sourceHeight: Int
    private let log: (String) -> Void
    private var stopped = false
    private var offered = 0
    private var nanoseconds: UInt64 = 0
    private var readingNanoseconds: UInt64 = 0

    /// Nil for a source with no size, which no decodable video has. Checked
    /// here because the tracker's constructor would throw on it, and a Kotlin
    /// constructor throwing into Swift ends the process.
    init?(markingTimeS: Double, sourceWidth: Int, sourceHeight: Int, log: @escaping (String) -> Void) {
        guard sourceWidth > 0, sourceHeight > 0 else { return nil }
        self.tracker = CameraMotionTracker(
            markingTimeS: markingTimeS, sourceWidth: Int32(sourceWidth), sourceHeight: Int32(sourceHeight)
        )
        self.sourceWidth = sourceWidth
        self.sourceHeight = sourceHeight
        self.log = log
    }

    /// One decoded frame. `buffer` must be locked for reading, as
    /// `VideoFrameSource.forEachFrame` holds it for its callback; the plane is
    /// only read, and only during this call.
    func follow(seconds: Double, buffer: CVPixelBuffer) {
        guard !stopped, tracker.wants(timestamp: seconds) else { return }
        // Plane 0 of the 420v the decoder is asked for: 8-bit luma, one byte
        // per pixel, rows padded out to its own stride.
        guard let plane = CVPixelBufferGetBaseAddressOfPlane(buffer, 0) else {
            return stop("the decoded frame at \(seconds)s has no luma plane")
        }
        let width = CVPixelBufferGetWidthOfPlane(buffer, 0)
        let height = CVPixelBufferGetHeightOfPlane(buffer, 0)
        // The tracker maps its samples back to source pixels with the scale it
        // was built for. A plane of another size would give a camera in the
        // wrong units, wrong everywhere and confidently so; no camera is the
        // honest answer.
        guard width == sourceWidth, height == sourceHeight else {
            return stop(
                "the luma plane is \(width)x\(height), the video says \(sourceWidth)x\(sourceHeight)"
            )
        }
        let started = DispatchTime.now().uptimeNanoseconds
        do {
            let image = try BulkInterop.shared.lumaDownsample(
                plane: plane,
                width: Int32(width),
                height: Int32(height),
                rowStride: Int32(CVPixelBufferGetBytesPerRowOfPlane(buffer, 0)),
                outWidth: tracker.trackerWidth,
                outHeight: tracker.trackerHeight
            )
            readingNanoseconds += DispatchTime.now().uptimeNanoseconds - started
            try tracker.offer(timestamp: seconds, image: image)
        } catch {
            return stop("the tracker failed at \(seconds)s: \(error.localizedDescription)")
        }
        nanoseconds += DispatchTime.now().uptimeNanoseconds - started
        offered += 1
    }

    /// The camera as followed, or nil when the marking frame was never
    /// tracked. Logs how the tracking went either way: a run with no camera
    /// looks exactly like one that was never asked to follow it, and this is
    /// the line that tells them apart.
    func finish() -> CameraMotion? {
        let motion: CameraMotion?
        do {
            motion = try tracker.finish()
        } catch {
            log("camera: finishing failed: \(error.localizedDescription)")
            motion = nil
        }
        // The first sample's cost includes tracking the frames buffered
        // before the marking frame, so this is a mean, not a steady rate.
        // The plane read is reported apart because it is the one part that
        // scales with the recording's resolution rather than the tracker's.
        func ms(_ total: UInt64) -> String {
            String(format: "%.1f", offered == 0 ? 0 : Double(total) / Double(offered) / 1_000_000)
        }
        log(
            "camera: \(tracker.acceptedSamples) samples followed, \(tracker.droppedSamples) dropped, "
                + "\(ms(nanoseconds)) ms per sample (\(ms(readingNanoseconds)) reading the plane)"
                + (motion == nil ? ", no camera" : "")
        )
        return motion
    }

    private func stop(_ reason: String) {
        stopped = true
        log("camera: stopped following, \(reason)")
    }
}
