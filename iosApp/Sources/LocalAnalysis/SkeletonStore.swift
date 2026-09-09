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
///   magic "SKEL", version i32, fps f64, videoWidth i32, videoHeight i32,
///   hasMarks i32, 12 x (x f64, y f64) in CourtKeypoints.pixels() order,
///   poseCount i32, then per pose: frame i32, timestamp f64, 17 x (x f32, y f32, c f32)
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
/// Version 1 files, written before the marks were stored, are still read; they
/// load with `marks == nil`. Save always writes version 2.
struct SkeletonStore {

    struct Stored {
        let poses: [PlayerPose]
        let fps: Double
        let videoWidth: Int
        let videoHeight: Int
        let marks: AnalysisCourtKeypoints?
    }

    private static let magic: [UInt8] = Array("SKEL".utf8)
    private static let version: Int32 = 2
    private static let directoryName = "skeletons"
    private static var keypointCount: Int { Int(Coco.shared.COUNT) }
    private static let markCount = 12
    private static let markBytes = markCount * 2 * 8
    private static let headerBytesV1 = 4 + 4 + 8 + 4 + 4
    private static var poseBytes: Int { 4 + 8 + keypointCount * 12 }

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
        var out = Writer()
        out.bytes(Self.magic)
        out.int32(Self.version)
        out.double(fps)
        out.int32(Int32(videoWidth))
        out.int32(Int32(videoHeight))
        out.int32(marks != nil ? 1 : 0)
        // The marks field is fixed width whether or not there are marks, so
        // everything after it sits at the same offset in every v2 file.
        let pixels = marks?.pixels()
        for i in 0..<Self.markCount {
            let point = pixels?[i]
            out.double(point?.x ?? 0)
            out.double(point?.y ?? 0)
        }
        out.int32(Int32(poses.count))
        for pose in poses {
            precondition(
                pose.keypoints.count == Self.keypointCount && pose.confidence.count == Self.keypointCount,
                "pose at frame \(pose.frame) has \(pose.keypoints.count) joints"
            )
            out.int32(pose.frame)
            out.double(pose.timestamp)
            for k in 0..<Self.keypointCount {
                out.float(Float(pose.keypoints[k].x))
                out.float(Float(pose.keypoints[k].y))
                out.float(pose.confidence[k].floatValue)
            }
        }
        let directory = try AnalysisFiles.directory(Self.directoryName)
        // Atomically: a partial write must never become visible, which is what
        // makes `has` reading the header alone sound for files this store wrote.
        try out.data.write(to: directory.appendingPathComponent("\(entryId).skel"), options: .atomic)
    }

    /// Whether a skeleton this version can draw exists for `entryId`, from the
    /// header alone.
    func has(entryId: String) -> Bool {
        let url = fileFor(entryId)
        guard let size = (try? FileManager.default.attributesOfItem(atPath: url.path)[.size]) as? Int,
              // The v1 floor, not the v2 one: this reads only the first eight
              // bytes, so the shorter header is the right floor for both.
              size >= Self.headerBytesV1,
              let handle = try? FileHandle(forReadingFrom: url)
        else { return false }
        defer { try? handle.close() }
        guard let head = try? handle.read(upToCount: 8), head.count == 8 else { return false }
        var reader = Reader(head)
        guard reader.bytes(4) == Self.magic else { return false }
        guard let version = reader.int32() else { return false }
        return version == 1 || version == Self.version
    }

    /// Nil when there is nothing stored, or when what is stored cannot be read
    /// whole.
    func load(entryId: String) -> Stored? {
        guard let data = try? Data(contentsOf: fileFor(entryId)) else { return nil }
        var reader = Reader(data)
        guard reader.bytes(4) == Self.magic, let version = reader.int32(),
              version == 1 || version == Self.version,
              let fps = reader.double(),
              let width = reader.int32(), let height = reader.int32()
        else { return nil }

        var marks: AnalysisCourtKeypoints?
        if version == Self.version {
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
            var points: [Point] = []
            for _ in 0..<Self.markCount {
                guard let x = reader.double(), let y = reader.double() else { return nil }
                points.append(Point(x: x, y: y))
            }
            if hasMarks == 1 {
                marks = AnalysisCourtKeypoints(
                    topLeft: points[0], topRight: points[1],
                    bottomRight: points[2], bottomLeft: points[3],
                    netLeft: points[4], netRight: points[5],
                    serviceLineNearLeft: points[6], serviceLineNearRight: points[7],
                    serviceLineFarLeft: points[8], serviceLineFarRight: points[9],
                    centerNear: points[10], centerFar: points[11]
                )
            }
        }

        guard let count = reader.int32() else { return nil }
        // Guarded before the multiply: a negative or absurdly large count -
        // corruption, not a file this store wrote - would otherwise overflow.
        guard count >= 0, Int(count) <= reader.remaining / Self.poseBytes else { return nil }
        // Length checked before parsing: a file killed mid-write ends in a
        // partial pose, and a skeleton missing its last joints is not one.
        guard reader.remaining == Int(count) * Self.poseBytes else { return nil }

        var poses: [PlayerPose] = []
        poses.reserveCapacity(Int(count))
        for _ in 0..<Int(count) {
            guard let frame = reader.int32(), let timestamp = reader.double() else { return nil }
            var keypoints: [Point] = []
            var confidence: [KotlinFloat] = []
            for _ in 0..<Self.keypointCount {
                guard let x = reader.float(), let y = reader.float(), let c = reader.float() else { return nil }
                keypoints.append(Point(x: Double(x), y: Double(y)))
                confidence.append(KotlinFloat(float: c))
            }
            poses.append(PlayerPose(
                frame: frame, timestamp: timestamp, keypoints: keypoints, confidence: confidence
            ))
        }
        return Stored(
            poses: poses, fps: fps,
            videoWidth: Int(width), videoHeight: Int(height), marks: marks
        )
    }

    /// Removes any skeleton stored for `entryId`.
    ///
    /// Called by the runner when a completed run did not ask for a skeleton, so
    /// an earlier run's poses cannot outlive the run that made them and be
    /// offered against a track that has since moved on. A no-op when the file is
    /// not there; a failure that leaves it there throws, because a stale
    /// skeleton that silently failed to delete keeps answering `has` and `load`
    /// as if this call had succeeded.
    func delete(entryId: String) throws {
        let url = fileFor(entryId)
        guard FileManager.default.fileExists(atPath: url.path) else { return }
        try FileManager.default.removeItem(at: url)
    }

    private func fileFor(_ entryId: String) -> URL {
        AnalysisFiles.directory
            .appendingPathComponent(Self.directoryName, isDirectory: true)
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
