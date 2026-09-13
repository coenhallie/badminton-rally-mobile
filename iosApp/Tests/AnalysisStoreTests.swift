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
        AnalysisFiles.deleteAll(entryId: entry)
    }

    override func tearDownWithError() throws {
        AnalysisFiles.deleteAll(entryId: entry)
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
                confidence: (0..<joints).map { KotlinFloat(float: Float($0) / Float(joints)) }
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
        // version 2, little-endian.
        XCTAssertEqual(Array(data[4..<8]), [2, 0, 0, 0])
        // fps 30.0 as a little-endian f64.
        XCTAssertEqual(data[8..<16].withUnsafeBytes { $0.load(as: UInt64.self).littleEndian },
                       Double(30).bitPattern)
        XCTAssertEqual(Array(data[16..<20]), [128, 7, 0, 0])   // 1920
        XCTAssertEqual(Array(data[20..<24]), [56, 4, 0, 0])    // 1080
        XCTAssertEqual(Array(data[24..<28]), [0, 0, 0, 0])     // hasMarks
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

        AnalysisFiles.deleteAll(entryId: entry)

        XCTAssertFalse(tracks.has(entryId: entry, source: .cloud))
        XCTAssertFalse(skeletons.has(entryId: entry, source: .cloud))
    }
}
