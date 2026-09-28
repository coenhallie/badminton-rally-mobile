import Shared
import XCTest
@testable import iosApp

/// What the two stores have to survive: a process that dies, a format that
/// moves on, and a file that is not what it claims to be.
///
/// Android's equivalents are instrumented. These are not, because neither store
/// touches a decoder - which is the point of testing them here: a run costs
/// minutes, and everything it produced is reachable only through these two
/// files.
final class AnalysisStoreTests: XCTestCase {

    private let entry = "entry-under-test"

    override func setUpWithError() throws {
        AnalysisFiles.deleteAll(entryId: entry, reason: .discarded)
    }

    override func tearDownWithError() throws {
        AnalysisFiles.deleteAll(entryId: entry, reason: .discarded)
    }

    // MARK: - The player track

    private func track(samples: Int) -> PlayerTrack {
        PlayerTrack(
            samples: (0..<samples).map {
                PlayerSample(frame: Int32($0), courtPosition: Point(x: Double($0) * 0.1, y: 3.05))
            },
            framesWithPose: Int32(samples),
            rejections: [:]
        )
    }

    func testATrackSurvivesASaveAndLoad() throws {
        let store = PlayerTrackStore()
        try store.save(entryId: entry, track: track(samples: 3), fps: 29.7357)
        let loaded = try XCTUnwrap(store.load(entryId: entry))
        XCTAssertEqual(loaded.fps, 29.7357)
        // `near` now: a v2 file is one near track, which is what Stored
        // reports it as. The far player only ever comes from a v3 file.
        let near = try XCTUnwrap(loaded.near)
        XCTAssertEqual(near.framesWithPose, 3)
        XCTAssertEqual(near.samples.map(\.frame), [0, 1, 2])
        XCTAssertEqual(near.samples[1].courtPosition.x, 0.1, accuracy: 1e-9)
        XCTAssertEqual(near.samples[2].courtPosition.y, 3.05, accuracy: 1e-9)
    }

    func testHasAnswersWithoutParsingTheWholeFile() throws {
        let store = PlayerTrackStore()
        XCTAssertFalse(store.has(entryId: entry))
        try store.save(entryId: entry, track: track(samples: 500), fps: 30)
        XCTAssertTrue(store.has(entryId: entry))
    }

    func testATrackFromAnOlderFormatIsRefusedRatherThanMisread() throws {
        // v1 tracks were built on a homography that could be metres off and on
        // hip positions two to three metres off. A row offering one would offer
        // a heatmap that is wrong rather than merely unloadable.
        let directory = try AnalysisFiles.directory("player-tracks")
        try "v1 30.0 2\n0,1.0,2.0\n".write(
            to: directory.appendingPathComponent("\(entry).track"), atomically: true, encoding: .utf8
        )
        XCTAssertFalse(PlayerTrackStore().has(entryId: entry))
        XCTAssertNil(PlayerTrackStore().load(entryId: entry))
    }

    // MARK: - Clips

    func testTheClipIndexRoundTrips() throws {
        let store = PlayerTrackStore()
        let directory = try AnalysisFiles.directory("local-clips/\(entry)")
        var clips: [PlayerTrackStore.Clip] = []
        for index in 1...3 {
            let url = directory.appendingPathComponent("rally-\(index).mp4")
            try Data([0]).write(to: url)
            clips.append(PlayerTrackStore.Clip(
                index: index, url: url,
                startSeconds: Double(index), endSeconds: Double(index) + 4.5
            ))
        }
        try store.saveClips(entryId: entry, clips: clips)
        let loaded = store.loadClips(entryId: entry)
        XCTAssertEqual(loaded.map(\.index), [1, 2, 3])
        XCTAssertEqual(loaded[1].startSeconds, 2)
        XCTAssertEqual(loaded[1].endSeconds, 6.5)
    }

    func testClipsAreRecoveredWhenTheIndexIsMissing() throws {
        // The clips are the expensive artifact - tens of megabytes and minutes
        // of re-encoding. Refusing to list them because a small sidecar is
        // missing would throw away the thing worth keeping to protect the
        // bookkeeping.
        let directory = try AnalysisFiles.directory("local-clips/\(entry)")
        for index in [1, 2] {
            try Data([0]).write(to: directory.appendingPathComponent("rally-\(index).mp4"))
        }
        let loaded = PlayerTrackStore().loadClips(entryId: entry)
        XCTAssertEqual(loaded.map(\.index), [1, 2])
        // Bounds are not recoverable from the file, so they are left at zero
        // and the caller shows the rally number alone rather than a fabricated
        // "0.0s - 0.0s".
        XCTAssertTrue(loaded.allSatisfy { $0.startSeconds == 0 && $0.endSeconds == 0 })
    }

    func testAnIndexedClipWhoseFileIsGoneIsNotListed() throws {
        // Listing it would offer a player that opens on nothing.
        let store = PlayerTrackStore()
        let directory = try AnalysisFiles.directory("local-clips/\(entry)")
        let present = directory.appendingPathComponent("rally-1.mp4")
        try Data([0]).write(to: present)
        try store.saveClips(entryId: entry, clips: [
            PlayerTrackStore.Clip(index: 1, url: present, startSeconds: 0, endSeconds: 1),
            PlayerTrackStore.Clip(
                index: 2, url: directory.appendingPathComponent("rally-2.mp4"),
                startSeconds: 1, endSeconds: 2
            ),
        ])
        XCTAssertEqual(store.loadClips(entryId: entry).map(\.index), [1])
    }

    // MARK: - The skeleton

    private func poses(_ count: Int) -> [PlayerPose] {
        let joints = Int(Coco.shared.COUNT)
        return (0..<count).map { frame in
            PlayerPose(
                frame: Int32(frame),
                timestamp: Double(frame) / 30,
                keypoints: (0..<joints).map { Point(x: Double($0) + Double(frame), y: Double($0) * 2) },
                confidence: (0..<joints).map { KotlinFloat(float: Float($0) / Float(joints)) },
                camera: nil
            )
        }
    }

    private func marks() -> AnalysisCourtKeypoints {
        func point(_ x: Double, _ y: Double) -> Point { Point(x: x, y: y) }
        return AnalysisCourtKeypoints(
            topLeft: point(1, 2), topRight: point(3, 4),
            bottomRight: point(5, 6), bottomLeft: point(7, 8),
            netLeft: point(9, 10), netRight: point(11, 12),
            serviceLineNearLeft: point(13, 14), serviceLineNearRight: point(15, 16),
            serviceLineFarLeft: point(17, 18), serviceLineFarRight: point(19, 20),
            centerNear: point(21, 22), centerFar: point(23, 24)
        )
    }

    func testASkeletonSurvivesASaveAndLoad() throws {
        let store = SkeletonStore()
        try store.save(
            entryId: entry, poses: poses(4), fps: 29.7357,
            videoWidth: 1920, videoHeight: 1080, marks: marks()
        )
        let loaded = try XCTUnwrap(store.load(entryId: entry))
        XCTAssertEqual(loaded.fps, 29.7357)
        XCTAssertEqual(loaded.videoWidth, 1920)
        XCTAssertEqual(loaded.videoHeight, 1080)
        XCTAssertEqual(loaded.poses.count, 4)
        XCTAssertEqual(loaded.poses[2].frame, 2)
        XCTAssertEqual(loaded.poses[2].timestamp, 2.0 / 30, accuracy: 1e-12)
        // Float, not double, in the pose payload: 216 bytes a pose against 432.
        XCTAssertEqual(loaded.poses[3].keypoints[5].x, 8, accuracy: 1e-4)
        XCTAssertEqual(loaded.poses[3].confidence[5].floatValue, 5.0 / 17, accuracy: 1e-6)
        // The marks travel with the poses: a reading in metres needs the
        // homography they fit, and the local entry that holds them can be
        // deleted while this file lives on.
        XCTAssertEqual(try XCTUnwrap(loaded.marks).centerFar.y, 24)
    }

    func testASkeletonWithoutMarksLoadsWithoutThem() throws {
        let store = SkeletonStore()
        try store.save(
            entryId: entry, poses: poses(2), fps: 30,
            videoWidth: 640, videoHeight: 360, marks: nil
        )
        let loaded = try XCTUnwrap(store.load(entryId: entry))
        XCTAssertNil(loaded.marks)
        XCTAssertEqual(loaded.poses.count, 2)
    }

    func testAnEmptySkeletonWritesNothingAtAll() throws {
        // Nothing to draw is nothing to offer, so the detail screen does not
        // grow a Skeleton tab that opens on an empty canvas.
        let store = SkeletonStore()
        try store.save(entryId: entry, poses: [], fps: 30, videoWidth: 1, videoHeight: 1, marks: nil)
        XCTAssertFalse(store.has(entryId: entry))
        XCTAssertNil(store.load(entryId: entry))
    }

    func testATruncatedSkeletonIsRefusedRatherThanPartiallyRead() throws {
        // A file killed mid-write ends in a partial pose, and a skeleton missing
        // its last joints is not one.
        let store = SkeletonStore()
        try store.save(entryId: entry, poses: poses(4), fps: 30, videoWidth: 1, videoHeight: 1, marks: nil)
        let url = AnalysisFiles.directory
            .appendingPathComponent("skeletons").appendingPathComponent("\(entry).skel")
        let whole = try Data(contentsOf: url)
        try whole.dropLast(20).write(to: url)
        XCTAssertNil(store.load(entryId: entry))
        // The header still parses, which is exactly why `has` is not proof and
        // the detail screen must survive a load that returns nil.
        XCTAssertTrue(store.has(entryId: entry))
    }

    func testAFileThatIsNotASkeletonIsRefused() throws {
        let directory = try AnalysisFiles.directory("skeletons")
        try Data(repeating: 7, count: 512).write(to: directory.appendingPathComponent("\(entry).skel"))
        XCTAssertFalse(SkeletonStore().has(entryId: entry))
        XCTAssertNil(SkeletonStore().load(entryId: entry))
    }

    func testDeletingASkeletonLeavesNothingBehind() throws {
        let store = SkeletonStore()
        try store.save(entryId: entry, poses: poses(2), fps: 30, videoWidth: 1, videoHeight: 1, marks: nil)
        try store.delete(entryId: entry)
        XCTAssertFalse(store.has(entryId: entry))
        // A second delete is a no-op, not a failure: the runner deletes on every
        // completed run that did not ask for a skeleton, most of which never had
        // one.
        XCTAssertNoThrow(try store.delete(entryId: entry))
    }

    func testTheBinaryLayoutMatchesAndroidsHeader() throws {
        // The format is shared with Android byte for byte, so a skeleton written
        // on one phone is readable on the other. Only the header is checked
        // here - it is where a field order or an endianness slip would show,
        // and the rest of the file is fixed width behind it.
        try SkeletonStore().save(
            entryId: entry, poses: poses(1), fps: 30,
            videoWidth: 1920, videoHeight: 1080, marks: nil
        )
        let data = try Data(contentsOf: AnalysisFiles.directory
            .appendingPathComponent("skeletons").appendingPathComponent("\(entry).skel"))
        XCTAssertEqual(Array(data[0..<4]), Array("SKEL".utf8))
        // version 4, little-endian.
        XCTAssertEqual(Array(data[4..<8]), [4, 0, 0, 0])
        // fps 30.0 as a little-endian f64.
        XCTAssertEqual(data[8..<16].withUnsafeBytes { $0.load(as: UInt64.self).littleEndian },
                       Double(30).bitPattern)
        XCTAssertEqual(Array(data[16..<20]), [128, 7, 0, 0])   // 1920
        XCTAssertEqual(Array(data[20..<24]), [56, 4, 0, 0])    // 1080
        XCTAssertEqual(Array(data[24..<28]), [0, 0, 0, 0])     // hasMarks
        // hasCamera after the twelve marks (4+4+8+4+4+4 + 12 x 16), then one track.
        XCTAssertEqual(Array(data[220..<224]), [0, 0, 0, 0])   // hasCamera
        XCTAssertEqual(Array(data[224..<228]), [1, 0, 0, 0])   // trackCount
        XCTAssertEqual(Array(data[228..<232]), [0, 0, 0, 0])   // side: near
        XCTAssertEqual(Array(data[232..<236]), [1, 0, 0, 0])   // poseCount
        XCTAssertEqual(data.count, 236 + 216)
    }

    // MARK: - Version 4: every pose keeps its camera (design §5.3)

    private func matrix(_ rows: [[Double]]) -> [[KotlinDouble]] {
        rows.map { $0.map { KotlinDouble(double: $0) } }
    }

    private func doubles(_ camera: [[KotlinDouble]]?) -> [[Double]]? {
        camera?.map { $0.map(\.doubleValue) }
    }

    private let panLeft: [[Double]] = [[1, 0, 30], [0, 1, -12], [0, 0, 1]]
    private let tiltedPan: [[Double]] = [[1.01, 0.002, 30.5], [-0.003, 0.99, -12.25], [0.00001, -0.00002, 1.0]]

    private func withCamera(_ pose: PlayerPose, _ camera: [[Double]]) -> PlayerPose {
        PlayerPose(
            frame: pose.frame, timestamp: pose.timestamp, keypoints: pose.keypoints,
            confidence: pose.confidence, camera: matrix(camera)
        )
    }

    private func skeletonFile(_ source: TrackSource = .local) throws -> URL {
        try AnalysisFiles.directory(source.skeletonDir).appendingPathComponent("\(entry).skel")
    }

    private func int32(_ data: Data, at offset: Int) -> Int32 {
        var value: UInt32 = 0
        for i in 0..<4 { value |= UInt32(data[data.startIndex + offset + i]) << (8 * i) }
        return Int32(bitPattern: value)
    }

    func testADeviceSaveWritesVersion4AndRoundTripsCameras() throws {
        let store = SkeletonStore()
        let base = poses(2)
        let saved = [withCamera(base[0], panLeft), withCamera(base[1], tiltedPan)]
        try store.save(entryId: entry, poses: saved, fps: 30, videoWidth: 1920, videoHeight: 1080, marks: marks())
        let data = try Data(contentsOf: skeletonFile())
        XCTAssertEqual(int32(data, at: 4), 4)
        XCTAssertEqual(int32(data, at: 220), 1)
        XCTAssertTrue(store.has(entryId: entry))
        let loaded = try XCTUnwrap(store.load(entryId: entry))
        XCTAssertEqual(loaded.tracks.map(\.side), [.near])
        // Bit for bit: the doubles written are the doubles read, h22 the 1.0 it
        // always is.
        XCTAssertEqual(loaded.poses.map { doubles($0.camera) }, [panLeft, tiltedPan])
        XCTAssertEqual(loaded.poses.map(\.frame), [0, 1])
    }

    func testPosesWithoutCamerasWriteHasCamera0AndNoCameraBytes() throws {
        let store = SkeletonStore()
        try store.save(entryId: entry, poses: poses(3), fps: 30, videoWidth: 1920, videoHeight: 1080, marks: nil)
        let data = try Data(contentsOf: skeletonFile())
        // The v3 formula (header, one track header, three poses) plus the flag.
        let v3 = 4 + 4 + 8 + 4 + 4 + 4 + 12 * 16 + 4 + (4 + 4) + 3 * 216
        XCTAssertEqual(data.count, v3 + 4)
        XCTAssertEqual(int32(data, at: 220), 0)
        XCTAssertEqual(try XCTUnwrap(store.load(entryId: entry)).poses.map { $0.camera == nil }, [true, true, true])
    }

    func testACloudSaveAllWritesVersion4WithBothTracks() throws {
        let store = SkeletonStore()
        let base = poses(2)
        let near = [withCamera(base[0], panLeft), withCamera(base[1], tiltedPan)]
        let far = [withCamera(base[0], tiltedPan)]
        let empty = PlayerTrack(samples: [], framesWithPose: 0, rejections: [:])
        try store.saveAll(entryId: entry, source: .cloud, selections: [
            PlayerSelection(side: .near, track: empty, poses: near),
            PlayerSelection(side: .far, track: empty, poses: far),
        ], fps: 59.94, videoWidth: 1920, videoHeight: 1080, marks: marks())
        XCTAssertEqual(int32(try Data(contentsOf: skeletonFile(.cloud)), at: 4), 4)
        let stored = try XCTUnwrap(store.load(entryId: entry, source: .cloud))
        XCTAssertEqual(stored.tracks.map(\.side), [.near, .far])
        XCTAssertEqual(stored.tracks[0].poses.map { doubles($0.camera) }, [panLeft, tiltedPan])
        XCTAssertEqual(stored.tracks[1].poses.map { doubles($0.camera) }, [tiltedPan])
        XCTAssertEqual(try XCTUnwrap(stored.marks).centerFar.y, 24)
    }

    func testVersion1And2And3FilesStillLoadWithoutCameras() throws {
        let store = SkeletonStore()
        for version: Int32 in 1...3 {
            try legacyFile(version: version, poses: poses(2), marks: marks()).write(to: skeletonFile())
            XCTAssertTrue(store.has(entryId: entry), "v\(version)")
            let stored = try XCTUnwrap(store.load(entryId: entry), "v\(version)")
            XCTAssertEqual(stored.tracks.map(\.side), [.near])
            XCTAssertEqual(stored.poses.map(\.frame), [0, 1])
            XCTAssertEqual(stored.poses.map { $0.camera == nil }, [true, true])
            XCTAssertEqual(stored.marks?.centerFar.y, version == 1 ? nil : 24)
        }
    }

    func testAFileMixingPosesWithAndWithoutCamerasIsRefusedAtSave() throws {
        // Selection never returns such a list (design §5.3), and the file has
        // one flag for all of them: writing it would drop cameras or invent them.
        let store = SkeletonStore()
        let base = poses(2)
        XCTAssertThrowsError(try store.save(
            entryId: entry, poses: [withCamera(base[0], panLeft), base[1]],
            fps: 30, videoWidth: 1920, videoHeight: 1080, marks: nil
        ))
        let empty = PlayerTrack(samples: [], framesWithPose: 0, rejections: [:])
        XCTAssertThrowsError(try store.saveAll(entryId: entry, source: .cloud, selections: [
            PlayerSelection(side: .near, track: empty, poses: [withCamera(base[0], panLeft)]),
            PlayerSelection(side: .far, track: empty, poses: [base[0]]),
        ], fps: 30, videoWidth: 1920, videoHeight: 1080, marks: nil))
        XCTAssertFalse(store.has(entryId: entry))
        XCTAssertFalse(store.has(entryId: entry, source: .cloud))
    }

    func testAnotherFormatVersionIsRefused() throws {
        let store = SkeletonStore()
        try store.save(entryId: entry, poses: poses(1), fps: 30, videoWidth: 1280, videoHeight: 720, marks: nil)
        var data = try Data(contentsOf: skeletonFile())
        data[data.startIndex + 4] = 9
        try data.write(to: skeletonFile())
        XCTAssertFalse(store.has(entryId: entry))
        XCTAssertNil(store.load(entryId: entry))
    }

    func testACameraFlagThisStoreNeverWritesIsRefused() throws {
        let store = SkeletonStore()
        try store.save(entryId: entry, poses: poses(1), fps: 30, videoWidth: 1280, videoHeight: 720, marks: nil)
        var data = try Data(contentsOf: skeletonFile())
        data[data.startIndex + 220] = 2
        try data.write(to: skeletonFile())
        XCTAssertNil(store.load(entryId: entry))
    }

    func testAVersion4FileCutInsideACameraIsRefused() throws {
        let store = SkeletonStore()
        try store.save(
            entryId: entry, poses: [withCamera(poses(1)[0], panLeft)],
            fps: 30, videoWidth: 1280, videoHeight: 720, marks: nil
        )
        let data = try Data(contentsOf: skeletonFile())
        try data.dropLast(8).write(to: skeletonFile())
        XCTAssertNil(store.load(entryId: entry))
    }

    /// Android's `SkeletonStoreTest` holds the same hex: both platforms load
    /// it, and both platforms' writers produce it from `fixtureSelections`,
    /// which is what makes a skeleton written on one phone readable on the
    /// other. Two tracks, one pose each, marks, and a camera on every pose.
    private static let sharedV4Fixture: Data = {
        let hex = """
        534b454c04000000b81e85eb51f83d4080070000380400000100000000000000
        004c8440cdcccccccc4c7e403333333333f593403333333333137e409a999999
        99ad9840cdcccccccc348f4000000000008076400000000000608f4000000000
        003081403333333333bf8440cdcccccccc9895409a99999999cd8440cdcccccc
        cc8c7f4000000000003c87400000000000389640cdcccccccc10874000000000
        006082409a999999999982403333333333eb94409a99999999998240cdcccccc
        cc308e4033333333338b8240cdcccccccc308e40666666666602874001000000
        0200000000000000010000000c000000aa60545227a0d93f0000c84200004843
        0000003d0000dc4200004d430000803d0000f042000052430000c03d00000243
        000057430000003e00000c4300005c430000203e00001643000061430000403e
        00002043000066430000603e00002a4300006b430000803e0000344300007043
        0000903e00003e43000075430000a03e0000484300007a430000b03e00005243
        00007f430000c03e00005c43000082430000d03e00006643008084430000e03e
        00007043000087430000f03e00007a43008089430000003f0000824300008c43
        0000083f295c8fc2f528f03ffca9f1d24d62603f0000000000803e40fa7e6abc
        749368bfae47e17a14aeef3f00000000008028c0f168e388b5f8e43ef168e388
        b5f8f4be01000000010000000d000000e09c11a5bdc1db3f00002f4400009643
        0000803f00003044000097430000783f00003144000098430000703f00003244
        000099430000683f0000334400009a430000603f0000344400009b430000583f
        0000354400009c430000503f0000364400009d430000483f0000374400009e43
        0000403f0000384400009f430000383f000039440000a0430000303f00003a44
        0000a1430000283f00003b440000a2430000203f00003c440000a3430000183f
        00003d440000a4430000103f00003e440000a5430000083f00003f440000a643
        0000003f5c8fc2f5285cef3ffca9f1d24d6250bf00000000008021c0fca9f1d2
        4d62703f52b81e85eb51f03f0000000000001a40691d554d1075ffbef168e388
        b5f8e43e
        """.filter { !$0.isWhitespace }
        var data = Data()
        var index = hex.startIndex
        while index < hex.endIndex {
            let next = hex.index(index, offsetBy: 2)
            data.append(UInt8(hex[index..<next], radix: 16)!)
            index = next
        }
        return data
    }()

    /// 743d7fb1's marks, as Android's fixture carries them.
    private func fixtureMarks() -> AnalysisCourtKeypoints {
        func point(_ x: Double, _ y: Double) -> Point { Point(x: x, y: y) }
        return AnalysisCourtKeypoints(
            topLeft: point(649.5, 484.8), topRight: point(1277.3, 481.2),
            bottomRight: point(1579.4, 998.6), bottomLeft: point(360.0, 1004.0),
            netLeft: point(550.0, 663.9), netRight: point(1382.2, 665.7),
            serviceLineNearLeft: point(504.8, 743.5), serviceLineNearRight: point(1422.0, 738.1),
            serviceLineFarLeft: point(588.0, 595.2), serviceLineFarRight: point(1338.8, 595.2),
            centerNear: point(966.1, 593.4), centerFar: point(966.1, 736.3)
        )
    }

    private func fixtureSelections() -> [PlayerSelection] {
        let joints = Int(Coco.shared.COUNT)
        let near = PlayerPose(
            frame: 12, timestamp: 0.4004,
            keypoints: (0..<joints).map { Point(x: 100.0 + 10 * Double($0), y: 200.0 + 5 * Double($0)) },
            confidence: (0..<joints).map { KotlinFloat(float: Float($0 + 1) / 32) },
            camera: matrix(tiltedPan)
        )
        let far = PlayerPose(
            frame: 13, timestamp: 0.4337,
            keypoints: (0..<joints).map { Point(x: 700.0 + 4 * Double($0), y: 300.0 + 2 * Double($0)) },
            confidence: (0..<joints).map { KotlinFloat(float: Float(32 - $0) / 32) },
            camera: matrix([[0.98, -0.001, -8.75], [0.004, 1.02, 6.5], [-0.00003, 0.00001, 1.0]])
        )
        let empty = PlayerTrack(samples: [], framesWithPose: 0, rejections: [:])
        return [
            PlayerSelection(side: .near, track: empty, poses: [near]),
            PlayerSelection(side: .far, track: empty, poses: [far]),
        ]
    }

    func testTheSharedV4FixtureLoadsAndIsWhatSaveAllWrites() throws {
        let store = SkeletonStore()
        try Self.sharedV4Fixture.write(to: skeletonFile(.cloud))
        let stored = try XCTUnwrap(store.load(entryId: entry, source: .cloud))
        let expected = fixtureSelections()
        XCTAssertEqual(stored.fps, 29.97)
        XCTAssertEqual(stored.videoWidth, 1920)
        XCTAssertEqual(stored.videoHeight, 1080)
        XCTAssertEqual(stored.marks, fixtureMarks())
        XCTAssertEqual(stored.tracks.map(\.side), [.near, .far])
        for (track, selection) in zip(stored.tracks, expected) {
            XCTAssertEqual(track.poses.count, 1)
            let (got, want) = (track.poses[0], selection.poses[0])
            XCTAssertEqual(got.frame, want.frame)
            XCTAssertEqual(got.timestamp, want.timestamp)
            XCTAssertEqual(got.keypoints.map(\.x), want.keypoints.map(\.x))
            XCTAssertEqual(got.keypoints.map(\.y), want.keypoints.map(\.y))
            XCTAssertEqual(got.confidence.map(\.floatValue), want.confidence.map(\.floatValue))
            XCTAssertEqual(doubles(got.camera), doubles(want.camera))
        }

        try store.saveAll(
            entryId: entry, source: .cloud, selections: expected,
            fps: 29.97, videoWidth: 1920, videoHeight: 1080, marks: fixtureMarks()
        )
        XCTAssertEqual(try Data(contentsOf: skeletonFile(.cloud)), Self.sharedV4Fixture)
    }

    func testSaveWritesWhatSaveAllWritesForALoneNearTrack() throws {
        // One writer behind both: a device file is a cloud file with one track.
        let store = SkeletonStore()
        let base = poses(2)
        let saved = [withCamera(base[0], panLeft), withCamera(base[1], tiltedPan)]
        try store.save(entryId: entry, poses: saved, fps: 30, videoWidth: 1920, videoHeight: 1080, marks: marks())
        try store.saveAll(entryId: entry, source: .cloud, selections: [
            PlayerSelection(side: .near, track: PlayerTrack(samples: [], framesWithPose: 0, rejections: [:]), poses: saved),
        ], fps: 30, videoWidth: 1920, videoHeight: 1080, marks: marks())
        XCTAssertEqual(try Data(contentsOf: skeletonFile()), try Data(contentsOf: skeletonFile(.cloud)))
    }

    /// A file in an older layout, written by hand so a writer that stopped
    /// emitting it cannot make a compatibility test pass. One near track; v1
    /// has no marks.
    private func legacyFile(version: Int32, poses: [PlayerPose], marks: AnalysisCourtKeypoints) -> Data {
        var data = Data("SKEL".utf8)
        func int32(_ v: Int32) { withUnsafeBytes(of: UInt32(bitPattern: v).littleEndian) { data.append(contentsOf: $0) } }
        func float(_ v: Float) { withUnsafeBytes(of: v.bitPattern.littleEndian) { data.append(contentsOf: $0) } }
        func double(_ v: Double) { withUnsafeBytes(of: v.bitPattern.littleEndian) { data.append(contentsOf: $0) } }
        int32(version)
        double(30)
        int32(1920)
        int32(1080)
        if version >= 2 {
            int32(1)
            for point in marks.pixels() {
                double(point.x)
                double(point.y)
            }
        }
        if version == 3 {
            int32(1)            // trackCount
            int32(0)            // side: near
        }
        int32(Int32(poses.count))
        for pose in poses {
            int32(pose.frame)
            double(pose.timestamp)
            for k in 0..<pose.keypoints.count {
                float(Float(pose.keypoints[k].x))
                float(Float(pose.keypoints[k].y))
                float(pose.confidence[k].floatValue)
            }
        }
        return data
    }

    // MARK: - Two players, and the cloud subtree (Task 8)

    private func sideTrack(_ side: CourtSide, x: Double, y: Double) -> PlayerSelection {
        PlayerSelection(
            side: side,
            track: PlayerTrack(
                samples: [PlayerSample(frame: 0, courtPosition: Point(x: x, y: y))],
                framesWithPose: 1,
                rejections: [:]
            ),
            poses: []
        )
    }

    private func singleSampleTrack() -> PlayerTrack { track(samples: 1) }

    private func sidePoses(_ side: CourtSide, count: Int) -> PlayerSelection {
        PlayerSelection(
            side: side,
            track: PlayerTrack(samples: [], framesWithPose: 0, rejections: [:]),
            poses: poses(count)
        )
    }

    func testAV2FileWrittenByThePreviousBuildStillLoads() throws {
        // Same regression as Android's. version was compared for exact
        // equality, so a bump refuses every track already on every phone.
        // Written as text rather than through save(), so a writer that
        // stopped emitting v2 cannot make this pass.
        let store = PlayerTrackStore()
        let directory = try AnalysisFiles.directory("player-tracks")
        try "v2 29.97 120\n0,1.5,2.5\n1,1.6,2.6\n"
            .write(to: directory.appendingPathComponent("\(entry).track"), atomically: true, encoding: .utf8)

        XCTAssertTrue(store.has(entryId: entry))
        let stored = try XCTUnwrap(store.load(entryId: entry))
        XCTAssertEqual(stored.fps, 29.97)
        XCTAssertEqual(stored.tracks.count, 1)
        XCTAssertEqual(stored.tracks[0].side, .near)
        XCTAssertEqual(stored.near?.samples.count, 2)
    }

    func testAV1FileIsStillRefused() throws {
        let store = PlayerTrackStore()
        let directory = try AnalysisFiles.directory("player-tracks")
        try "v1 30.0 10\n0,1.0,2.0,1\n"
            .write(to: directory.appendingPathComponent("\(entry).track"), atomically: true, encoding: .utf8)

        XCTAssertFalse(store.has(entryId: entry))
        XCTAssertNil(store.load(entryId: entry))
    }

    func testTwoTracksRoundTripThroughV3() throws {
        let store = PlayerTrackStore()
        try store.saveAll(entryId: entry, source: .cloud, selections: [
            sideTrack(.near, x: 1.0, y: 2.0),
            sideTrack(.far, x: 5.0, y: 11.0),
        ], fps: 30.0)

        let stored = try XCTUnwrap(store.load(entryId: entry, source: .cloud))
        XCTAssertEqual(stored.tracks.map(\.side), [.near, .far])
        XCTAssertEqual(stored.tracks[1].track.samples.first?.courtPosition.x, 5.0)
    }

    func testTheCloudAndLocalSubtreesDoNotSeeEachOther() throws {
        let store = PlayerTrackStore()
        try store.save(entryId: entry, track: singleSampleTrack(), fps: 30.0)

        XCTAssertTrue(store.has(entryId: entry, source: .local))
        XCTAssertFalse(store.has(entryId: entry, source: .cloud))
    }

    func testTwoPlayersRoundTripThroughSkeletonV3() throws {
        let store = SkeletonStore()
        try store.saveAll(entryId: entry, source: .cloud, selections: [
            sidePoses(.near, count: 2),
            sidePoses(.far, count: 1),
        ], fps: 59.94, videoWidth: 1920, videoHeight: 1080, marks: nil)

        let stored = try XCTUnwrap(store.load(entryId: entry, source: .cloud))
        XCTAssertEqual(stored.tracks.map(\.side), [.near, .far])
        XCTAssertEqual(stored.tracks[0].poses.count, 2)
        XCTAssertEqual(stored.poses.count, 2)   // the near list
    }

    func testADeletedLocalSkeletonLeavesTheCloudOneAlone() throws {
        // skeletonAction treats a completed run as the new truth for its
        // entry, which is right for a device run and wrong across sources: a
        // rally-only run would otherwise delete a cloud skeleton the coach
        // waited on a GPU for.
        let store = SkeletonStore()
        try store.save(
            entryId: entry, poses: poses(1), fps: 30, videoWidth: 100, videoHeight: 50, marks: nil
        )
        try store.saveAll(
            entryId: entry, source: .cloud, selections: [sidePoses(.near, count: 1)],
            fps: 30, videoWidth: 100, videoHeight: 50, marks: nil
        )

        try store.delete(entryId: entry, source: .local)

        XCTAssertFalse(store.has(entryId: entry, source: .local))
        XCTAssertTrue(store.has(entryId: entry, source: .cloud))
    }

    func testRemovingTheEntryTakesTheCloudArtifactsToo() throws {
        // Android's AnalysisFiles already lists the cloud stores; its comment
        // says iOS picks them up in this task. Without it a removed match
        // keeps its cloud track and skeleton for the life of the install.
        let tracks = PlayerTrackStore()
        let skeletons = SkeletonStore()
        try tracks.saveAll(entryId: entry, source: .cloud, selections: [
            sideTrack(.near, x: 1, y: 2),
        ], fps: 30)
        try skeletons.saveAll(
            entryId: entry, source: .cloud, selections: [sidePoses(.near, count: 1)],
            fps: 30, videoWidth: 100, videoHeight: 50, marks: nil
        )

        AnalysisFiles.deleteAll(entryId: entry, reason: .discarded)

        XCTAssertFalse(tracks.has(entryId: entry, source: .cloud))
        XCTAssertFalse(skeletons.has(entryId: entry, source: .cloud))
    }
}
