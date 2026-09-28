import Foundation
import Shared

/// The near player's joints for one analysed video, kept on disk so the
/// skeleton view outlives the run that made it.
///
/// Port of Android's `SkeletonStore`, **byte-for-byte the same format**. That is
/// deliberate and not merely tidy: the file is little-endian and fixed width on
/// both platforms, so a skeleton written on one phone is readable on the other,
/// which is what a future sync would need and what makes an A/B comparison of
/// the two pose passes possible at all.
///
///   v1: magic "SKEL", version i32 = 1, fps f64, videoWidth i32, videoHeight i32,
///   poseCount i32, then per pose: frame i32, timestamp f64, 17 x (x f32, y f32, c f32)
///
///   v2: as v1, with hasMarks i32 and 12 x (x f64, y f64) in
///   CourtKeypoints.pixels() order between videoHeight and poseCount.
///
///   v3: magic "SKEL", version i32 = 3, fps f64, videoWidth i32, videoHeight i32,
///   hasMarks i32, 12 x (x f64, y f64), trackCount i32, then per track:
///     side i32 (CourtSide ordinal), poseCount i32, then poses as in v2.
///
///   v4: as v3, with hasCamera i32 between the marks and trackCount. When it is
///   1 every pose record is followed by its camera, h00 h01 h02 h10 h11 h12 h20
///   h21 as f64 (h22 is 1.0 by construction and not stored); when it is 0 the
///   poses are exactly v3's.
///
/// Binary, because a person does not read a joint list and a 30-minute match is
/// 11MB of them: 216 bytes a pose against the track store's three numbers. The
/// fixed width is what lets `has` answer from the header and `load` check the
/// length before parsing a byte.
///
/// The timestamp is the frame's container presentation time, which is what
/// playback matches on; the video size is what the joints are measured in. Both
/// travel with the poses because a renderer that took either from somewhere else
/// would be pairing them by coincidence. The court marks travel with them for
/// the same reason and one more: a reading in metres needs the homography those
/// marks fit, and the local entry that holds them can be deleted while this file
/// lives on.
///
/// The camera travels for the last of those reasons. The marks describe the
/// frame they were placed on; a pose from a frame the camera had moved in
/// reaches the court only through that frame's camera, and a file that dropped
/// it would turn every pan into metres of error (design §5.3). Within one file
/// every pose has a camera or none does, because selection only returns a pose
/// for a frame whose camera it knew; a mix is refused at save rather than
/// written with cameras dropped or invented.
///
/// Both `save` and `saveAll` write version 4. A device run writes it too, as
/// one near track, because a device run's poses carry cameras as well: v2 has
/// nowhere to put them. Versions 1 to 3, already on phones, keep loading, with
/// no marks for v1 and no camera for any of them.
struct SkeletonStore {

    /// One player's joints, and which half of the court they were on.
    struct SidePoses {
        let side: CourtSide
        let poses: [PlayerPose]
    }

    struct Stored {
        let tracks: [SidePoses]
        let fps: Double
        let videoWidth: Int
        let videoHeight: Int
        let marks: AnalysisCourtKeypoints?

        /// The near player's joints, or empty when the file holds only a far
        /// one. Every caller that predates the far player wants this.
        var poses: [PlayerPose] { tracks.first { $0.side == .near }?.poses ?? [] }
    }

    enum Failure: LocalizedError {
        /// Some poses had a camera and some did not. The file has one flag
        /// for all of them, so writing it would drop cameras or invent them.
        case mixedCameras(withCamera: Int, total: Int)

        var errorDescription: String? {
            switch self {
            case .mixedCameras(let withCamera, let total):
                return "\(withCamera) of \(total) poses have a camera; a skeleton file holds all or none"
            }
        }
    }

    private static let magic: [UInt8] = Array("SKEL".utf8)
    /// What `save` and `saveAll` write.
    private static let version: Int32 = 4
    private static let versionV2: Int32 = 2
    private static let versionV3: Int32 = 3
    private static var keypointCount: Int { Int(Coco.shared.COUNT) }
    private static let markCount = 12
    private static let markBytes = markCount * 2 * 8
    private static let headerBytesV1 = 4 + 4 + 8 + 4 + 4
    private static var poseBytes: Int { 4 + 8 + keypointCount * 12 }
    private static let cameraBytes = 8 * 8

    /// The near player's joints from a device run, as one near track in the
    /// version 4 format. Throws `Failure.mixedCameras` when some poses have a
    /// camera and some do not.
    func save(
        entryId: String,
        poses: [PlayerPose],
        fps: Double,
        videoWidth: Int,
        videoHeight: Int,
        marks: AnalysisCourtKeypoints?
    ) throws {
        // Nothing to draw is nothing to offer: no file, so `has` stays false.
        guard !poses.isEmpty else { return }
        let data = try Self.encode(
            tracks: [SidePoses(side: .near, poses: poses)],
            fps: fps, videoWidth: videoWidth, videoHeight: videoHeight, marks: marks
        )
        let directory = try AnalysisFiles.directory(TrackSource.local.skeletonDir)
        // Atomically: a partial write must never become visible, which is what
        // makes `has` reading the header alone sound for files this store wrote.
        try data.write(to: directory.appendingPathComponent("\(entryId).skel"), options: .atomic)
    }

    /// Every player's joints from one analysis, in the version 4 format.
    /// Throws `Failure.mixedCameras` when some poses have a camera and some do
    /// not, across every track: the file has one flag for all of them.
    ///
    /// Byte-for-byte Android's `saveAll`, for the reason the whole file is:
    /// the format is the contract between the two platforms and the cloud.
    func saveAll(
        entryId: String,
        source: TrackSource,
        selections: [PlayerSelection],
        fps: Double,
        videoWidth: Int,
        videoHeight: Int,
        marks: AnalysisCourtKeypoints?
    ) throws {
        let withPoses = selections.filter { !$0.poses.isEmpty }
        // Nothing to draw is nothing to offer, matching save().
        guard !withPoses.isEmpty else { return }
        let data = try Self.encode(
            tracks: withPoses.map { SidePoses(side: $0.side, poses: $0.poses) },
            fps: fps, videoWidth: videoWidth, videoHeight: videoHeight, marks: marks
        )
        let directory = try AnalysisFiles.directory(source.skeletonDir)
        try data.write(
            to: directory.appendingPathComponent("\(entryId).skel"), options: .atomic
        )
    }

    /// The version 4 bytes for `tracks`, none of them empty.
    private static func encode(
        tracks: [SidePoses],
        fps: Double,
        videoWidth: Int,
        videoHeight: Int,
        marks: AnalysisCourtKeypoints?
    ) throws -> Data {
        let total = tracks.reduce(0) { $0 + $1.poses.count }
        let withCamera = tracks.reduce(0) { sum, track in sum + track.poses.filter { $0.camera != nil }.count }
        guard withCamera == 0 || withCamera == total else {
            throw Failure.mixedCameras(withCamera: withCamera, total: total)
        }
        var out = Writer()
        out.bytes(magic)
        out.int32(version)
        out.double(fps)
        out.int32(Int32(videoWidth))
        out.int32(Int32(videoHeight))
        out.int32(marks != nil ? 1 : 0)
        // The marks field is fixed width whether or not there are marks, so
        // everything after it sits at the same offset in every file.
        let pixels = marks?.pixels()
        for i in 0..<markCount {
            let point = pixels?[i]
            out.double(point?.x ?? 0)
            out.double(point?.y ?? 0)
        }
        out.int32(withCamera > 0 ? 1 : 0)
        out.int32(Int32(tracks.count))
        for track in tracks {
            // The ordinal, matching Android's `side.ordinal`. The NAME is what
            // the track store writes, because that file is text; here the width
            // is fixed and an ordinal is what fits.
            out.int32(track.side.ordinal)
            out.int32(Int32(track.poses.count))
            for pose in track.poses {
                put(pose, into: &out)
                if let camera = pose.camera { put(camera: camera, into: &out) }
            }
        }
        return out.data
    }

    /// Writes one pose in the fixed-width layout common to every version.
    private static func put(_ pose: PlayerPose, into out: inout Writer) {
        precondition(
            pose.keypoints.count == keypointCount && pose.confidence.count == keypointCount,
            "pose at frame \(pose.frame) has \(pose.keypoints.count) joints"
        )
        out.int32(pose.frame)
        out.double(pose.timestamp)
        for k in 0..<keypointCount {
            out.float(Float(pose.keypoints[k].x))
            out.float(Float(pose.keypoints[k].y))
            out.float(pose.confidence[k].floatValue)
        }
    }

    /// Writes a camera as its first eight entries; h22 is 1.0 by construction.
    private static func put(camera: [[KotlinDouble]], into out: inout Writer) {
        precondition(
            camera.count == 3 && camera.allSatisfy { $0.count == 3 } && camera[2][2].doubleValue == 1.0,
            "a camera is a 3x3 matrix with h22 == 1.0"
        )
        for r in 0..<3 {
            for c in 0..<3 where r != 2 || c != 2 { out.double(camera[r][c].doubleValue) }
        }
    }

    /// Whether a skeleton this version can draw exists for `entryId`, from the
    /// header alone.
    func has(entryId: String, source: TrackSource = .local) -> Bool {
        let url = fileFor(entryId, source)
        guard let size = (try? FileManager.default.attributesOfItem(atPath: url.path)[.size]) as? Int,
              // The v1 floor, not a later one: this reads only the first eight
              // bytes, so the shortest header is the right floor for every version.
              size >= Self.headerBytesV1,
              let handle = try? FileHandle(forReadingFrom: url)
        else { return false }
        defer { try? handle.close() }
        guard let head = try? handle.read(upToCount: 8), head.count == 8 else { return false }
        var reader = Reader(head)
        guard reader.bytes(4) == Self.magic else { return false }
        guard let version = reader.int32() else { return false }
        return (1...Self.version).contains(version)
    }

    /// Nil when there is nothing stored, or when what is stored cannot be read
    /// whole.
    func load(entryId: String, source: TrackSource = .local) -> Stored? {
        guard let data = try? Data(contentsOf: fileFor(entryId, source)) else { return nil }
        var reader = Reader(data)
        guard reader.bytes(4) == Self.magic, let version = reader.int32() else { return nil }
        switch version {
        case 1, Self.versionV2: return loadV1OrV2(version: version, reader: &reader)
        case Self.versionV3: return loadMultiTrack(reader: &reader, withCameraFlag: false)
        case Self.version: return loadMultiTrack(reader: &reader, withCameraFlag: true)
        default: return nil
        }
    }

    private func loadV1OrV2(version: Int32, reader: inout Reader) -> Stored? {
        guard let fps = reader.double(),
              let width = reader.int32(), let height = reader.int32()
        else { return nil }

        var marks: AnalysisCourtKeypoints?
        if version == Self.versionV2 {
            guard let hasMarks = reader.int32() else { return nil }
            // Refused rather than guessed at, as a bad magic or an unknown
            // version is: this store writes only 0 or 1, so anything else is
            // not a file it wrote.
            guard hasMarks == 0 || hasMarks == 1 else { return nil }
            // Guarded before the reads, not after: a file cut inside the marks
            // would otherwise fall out of a later bounds check, which reads as
            // "no skeleton" for the wrong reason and hides where the file
            // ended. The 4 is the count that follows the marks.
            guard reader.remaining >= Self.markBytes + 4 else { return nil }
            guard let read = Self.readMarks(&reader) else { return nil }
            if hasMarks == 1 { marks = read }
        }

        guard let count = reader.int32() else { return nil }
        guard let poses = Self.readPoses(count: count, reader: &reader) else { return nil }
        return Stored(
            tracks: [SidePoses(side: .near, poses: poses)], fps: fps,
            videoWidth: Int(width), videoHeight: Int(height), marks: marks
        )
    }

    /// The multi-track formats. v4 is v3 with a camera flag after the marks,
    /// and a camera after every pose when it is set.
    private func loadMultiTrack(reader: inout Reader, withCameraFlag: Bool) -> Stored? {
        guard let fps = reader.double(),
              let width = reader.int32(), let height = reader.int32(),
              let hasMarks = reader.int32(), hasMarks == 0 || hasMarks == 1,
              // The 4 is the count that follows the marks, and v4's flag another 4.
              reader.remaining >= Self.markBytes + 4 + (withCameraFlag ? 4 : 0),
              let read = Self.readMarks(&reader)
        else { return nil }
        let marks = hasMarks == 1 ? read : nil
        let hasCamera: Int32
        if withCameraFlag {
            // Refused, as a marks flag other than 0 or 1 is: not a file this
            // store wrote.
            guard let flag = reader.int32(), flag == 0 || flag == 1 else { return nil }
            hasCamera = flag
        } else {
            hasCamera = 0
        }
        let poseBytes = Self.poseBytes + (hasCamera == 1 ? Self.cameraBytes : 0)
        guard let trackCount = reader.int32() else { return nil }
        // Rejected before the loop can allocate on it: a negative or
        // implausibly large count is corruption, not a file this store wrote.
        guard trackCount >= 0, Int(trackCount) <= CourtSide.allCases.count else { return nil }

        var tracks: [SidePoses] = []
        tracks.reserveCapacity(Int(trackCount))
        for _ in 0..<Int(trackCount) {
            guard reader.remaining >= 8,
                  let ordinal = reader.int32(),
                  ordinal >= 0, Int(ordinal) < CourtSide.allCases.count,
                  let poseCount = reader.int32(),
                  poseCount >= 0, Int(poseCount) <= reader.remaining / poseBytes,
                  let poses = Self.readPoses(
                      count: poseCount, reader: &reader, exact: false, withCamera: hasCamera == 1
                  )
            else { return nil }
            guard let side = CourtSide.allCases.first(where: { $0.ordinal == ordinal }) else { return nil }
            tracks.append(SidePoses(side: side, poses: poses))
        }
        // A file killed mid-write ends short: nothing but a fully-formed
        // trailing pose section is offered.
        guard reader.remaining == 0 else { return nil }
        return Stored(
            tracks: tracks, fps: fps,
            videoWidth: Int(width), videoHeight: Int(height), marks: marks
        )
    }

    private static func readMarks(_ reader: inout Reader) -> AnalysisCourtKeypoints? {
        var points: [Point] = []
        for _ in 0..<markCount {
            guard let x = reader.double(), let y = reader.double() else { return nil }
            points.append(Point(x: x, y: y))
        }
        return AnalysisCourtKeypoints(
            topLeft: points[0], topRight: points[1],
            bottomRight: points[2], bottomLeft: points[3],
            netLeft: points[4], netRight: points[5],
            serviceLineNearLeft: points[6], serviceLineNearRight: points[7],
            serviceLineFarLeft: points[8], serviceLineFarRight: points[9],
            centerNear: points[10], centerFar: points[11]
        )
    }

    /// `exact` is the v1/v2 rule that the poses run to the end of the file. v3
    /// and v4 have sections after this one, so they check only that this
    /// section fits. `withCamera` reads the camera that follows each pose in a
    /// v4 file whose flag is set.
    private static func readPoses(
        count: Int32, reader: inout Reader, exact: Bool = true, withCamera: Bool = false
    ) -> [PlayerPose]? {
        let recordBytes = poseBytes + (withCamera ? cameraBytes : 0)
        // Guarded before the multiply: a negative or absurdly large count -
        // corruption, not a file this store wrote - would otherwise overflow.
        guard count >= 0, Int(count) <= reader.remaining / recordBytes else { return nil }
        // Length checked before parsing: a file killed mid-write ends in a
        // partial pose, and a skeleton missing its last joints is not one.
        if exact { guard reader.remaining == Int(count) * recordBytes else { return nil } }

        var poses: [PlayerPose] = []
        poses.reserveCapacity(Int(count))
        for _ in 0..<Int(count) {
            guard let frame = reader.int32(), let timestamp = reader.double() else { return nil }
            var keypoints: [Point] = []
            var confidence: [KotlinFloat] = []
            for _ in 0..<keypointCount {
                guard let x = reader.float(), let y = reader.float(), let c = reader.float() else { return nil }
                keypoints.append(Point(x: Double(x), y: Double(y)))
                confidence.append(KotlinFloat(float: c))
            }
            var camera: [[KotlinDouble]]?
            if withCamera {
                var h: [Double] = []
                for _ in 0..<8 {
                    guard let value = reader.double() else { return nil }
                    h.append(value)
                }
                camera = [[h[0], h[1], h[2]], [h[3], h[4], h[5]], [h[6], h[7], 1.0]]
                    .map { $0.map { KotlinDouble(double: $0) } }
            }
            poses.append(PlayerPose(
                frame: frame, timestamp: timestamp, keypoints: keypoints, confidence: confidence, camera: camera
            ))
        }
        return poses
    }

    /// Removes any skeleton stored for `entryId`.
    ///
    /// Called by the runner when a completed run did not ask for a skeleton, so
    /// an earlier run's poses cannot outlive the run that made them and be
    /// offered against a track that has since moved on. A no-op when the file is
    /// not there; a failure that leaves it there throws, because a stale
    /// skeleton that silently failed to delete keeps answering `has` and `load`
    /// as if this call had succeeded.
    func delete(entryId: String, source: TrackSource = .local) throws {
        let url = fileFor(entryId, source)
        guard FileManager.default.fileExists(atPath: url.path) else { return }
        try FileManager.default.removeItem(at: url)
    }

    private func fileFor(_ entryId: String, _ source: TrackSource = .local) -> URL {
        AnalysisFiles.directory
            .appendingPathComponent(source.skeletonDir, isDirectory: true)
            .appendingPathComponent("\(entryId).skel")
    }
}

/// Little-endian primitives, matching Android's `ByteBuffer.LITTLE_ENDIAN`.
///
/// Written out rather than reached for through `withUnsafeBytes` on a struct:
/// the byte order is the contract with the other platform, and a layout that
/// happens to match today is not the same as one that is stated.
private struct Writer {
    private(set) var data = Data()

    mutating func bytes(_ value: [UInt8]) { data.append(contentsOf: value) }
    mutating func int32(_ value: Int32) { append(UInt32(bitPattern: value)) }
    mutating func float(_ value: Float) { append(value.bitPattern) }
    mutating func double(_ value: Double) { append(value.bitPattern) }

    private mutating func append(_ value: UInt32) {
        withUnsafeBytes(of: value.littleEndian) { data.append(contentsOf: $0) }
    }
    private mutating func append(_ value: UInt64) {
        withUnsafeBytes(of: value.littleEndian) { data.append(contentsOf: $0) }
    }
}

/// The reading half. Every accessor returns nil past the end rather than
/// trapping, so a truncated file is "no skeleton" instead of a crash.
private struct Reader {
    private let data: Data
    private var offset: Int

    init(_ data: Data) {
        self.data = data
        self.offset = 0
    }

    var remaining: Int { data.count - offset }

    mutating func bytes(_ count: Int) -> [UInt8]? {
        guard remaining >= count else { return nil }
        // data.startIndex, not 0: a Data made by slicing another does not start
        // at zero, and indexing it from zero reads the wrong bytes or traps.
        let start = data.startIndex + offset
        defer { offset += count }
        return Array(data[start..<(start + count)])
    }

    mutating func int32() -> Int32? { unsigned32().map(Int32.init(bitPattern:)) }
    mutating func float() -> Float? { unsigned32().map(Float.init(bitPattern:)) }
    mutating func double() -> Double? {
        guard let raw = bytes(8) else { return nil }
        var value: UInt64 = 0
        for (i, byte) in raw.enumerated() { value |= UInt64(byte) << (8 * i) }
        return Double(bitPattern: value)
    }

    private mutating func unsigned32() -> UInt32? {
        guard let raw = bytes(4) else { return nil }
        var value: UInt32 = 0
        for (i, byte) in raw.enumerated() { value |= UInt32(byte) << (8 * i) }
        return value
    }
}
