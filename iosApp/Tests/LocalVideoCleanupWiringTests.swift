import XCTest
import Shared
@testable import iosApp

/// Covers the one link the Kotlin unit tests cannot reach: that the Swift closures
/// handed to `createRallyApp` are the ones the shared registry actually calls back
/// into. Everything either side of that boundary is tested in its own language.
final class LocalVideoCleanupWiringTests: XCTestCase {

    private func entry(id: String) -> LocalVideoEntry {
        LocalVideoEntry(
            id: id,
            uri: "LocalVideos/\(id).mp4",
            displayName: "\(id).mp4",
            durationMs: 1000,
            sizeBytes: 2,
            addedAtEpochMs: 1,
            title: nil,
            description: nil,
            keypoints: nil,
            stage: .local,
            failedStep: nil,
            failureMessage: nil,
            resultSeen: false,
            scoreLogId: nil
        )
    }

    func testRemovingAnEntryCallsBackIntoTheSwiftFileDelete() {
        var deleted: [String] = []
        let rally = RallyAppIosKt.createRallyApp(
            url: "https://test.supabase.co",
            anonKey: "test-anon-key",
            deleteLocalVideoFile: { deleted.append($0) },
            deleteLocalAnalysis: { _, _ in }
        )
        // A unique id so the shared NSUserDefaults registry this reads is left as
        // it was found: add and remove touch only this entry.
        let id = "wiring-\(UUID().uuidString)"
        rally.localVideos.add(entry: entry(id: id))

        rally.localVideos.remove(id: id)

        XCTAssertEqual(deleted, ["LocalVideos/\(id).mp4"])
    }

    func testTheRealDeleteClosureRemovesTheFileOnDisk() {
        // The closure createRallyApp is given in production is
        // LocalVideoFiles.delete; prove that pairing removes a real file.
        let rally = RallyAppIosKt.createRallyApp(
            url: "https://test.supabase.co",
            anonKey: "test-anon-key",
            deleteLocalVideoFile: { LocalVideoFiles.delete(relativePath: $0) },
            deleteLocalAnalysis: { AnalysisFiles.deleteAll(entryId: $0, reason: $1) }
        )
        let temp = FileManager.default.temporaryDirectory
            .appendingPathComponent("wiring-\(UUID().uuidString).mp4")
        try? Data([0x00, 0x01]).write(to: temp)
        guard let relativePath = try? LocalVideoFiles.store(tempURL: temp) else {
            return XCTFail("could not stage a file in the store")
        }
        let stored = LocalVideoFiles.resolve(relativePath: relativePath)
        let id = "wiring-\(UUID().uuidString)"
        rally.localVideos.add(entry: LocalVideoEntry(
            id: id, uri: relativePath, displayName: "m.mp4", durationMs: 1000,
            sizeBytes: 2, addedAtEpochMs: 1, title: nil, description: nil,
            keypoints: nil, stage: .local,
            failedStep: nil, failureMessage: nil, resultSeen: false, scoreLogId: nil
        ))
        XCTAssertTrue(FileManager.default.fileExists(atPath: stored.path))

        rally.localVideos.remove(id: id)

        XCTAssertFalse(FileManager.default.fileExists(atPath: stored.path))
    }

    func testRemovingAnEntryAlsoDeletesWhatAnAnalysisOfItWrote() throws {
        // The leak this closure exists to close: a track is kilobytes, but the
        // clips beside it are tens of megabytes of re-encoded video, and once
        // the entry is gone nothing in the app can reach them again.
        let rally = RallyAppIosKt.createRallyApp(
            url: "https://test.supabase.co",
            anonKey: "test-anon-key",
            deleteLocalVideoFile: { LocalVideoFiles.delete(relativePath: $0) },
            deleteLocalAnalysis: { AnalysisFiles.deleteAll(entryId: $0, reason: $1) }
        )
        let id = "wiring-\(UUID().uuidString)"

        // One of each shape deleteAll knows about: a file named for the entry
        // and a directory named for it.
        let track = try AnalysisFiles.directory("player-tracks")
            .appendingPathComponent("\(id).track")
        try "v2 30.0 0\n".write(to: track, atomically: true, encoding: .utf8)
        let clip = try AnalysisFiles.directory("local-clips/\(id)")
            .appendingPathComponent("rally-1.mp4")
        try Data([0x00, 0x01]).write(to: clip)
        XCTAssertTrue(FileManager.default.fileExists(atPath: track.path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: clip.path))

        rally.localVideos.add(entry: entry(id: id))
        rally.localVideos.remove(id: id)

        XCTAssertFalse(FileManager.default.fileExists(atPath: track.path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: clip.path))
    }

    func testFreeingTheVideoKeepsBothTracksAndDropsWhatNeedsTheFootage() throws {
        // The coach wants the space back, not the match gone. Only the skeleton
        // needs the video - it is drawn over playback - so it goes with the
        // footage, while the tracks stay and the heatmap and base position keep
        // working. Taking the tracks here would turn "get my storage back" into
        // "lose my analysis".
        let rally = RallyAppIosKt.createRallyApp(
            url: "https://test.supabase.co",
            anonKey: "test-anon-key",
            deleteLocalVideoFile: { LocalVideoFiles.delete(relativePath: $0) },
            deleteLocalAnalysis: { AnalysisFiles.deleteAll(entryId: $0, reason: $1) }
        )
        let id = "wiring-\(UUID().uuidString)"

        let cloudTrack = try AnalysisFiles.directory("cloud-tracks")
            .appendingPathComponent("\(id).track")
        try "v3 30.0 2\n".write(to: cloudTrack, atomically: true, encoding: .utf8)
        let localTrack = try AnalysisFiles.directory("player-tracks")
            .appendingPathComponent("\(id).track")
        try "v2 30.0 0\n".write(to: localTrack, atomically: true, encoding: .utf8)
        let cloudSkeleton = try AnalysisFiles.directory("cloud-skeletons")
            .appendingPathComponent("\(id).skel")
        try "v1\n".write(to: cloudSkeleton, atomically: true, encoding: .utf8)

        rally.localVideos.add(entry: entry(id: id))
        rally.localVideos.freeVideo(id: id)

        XCTAssertTrue(FileManager.default.fileExists(atPath: cloudTrack.path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: localTrack.path))
        // An 11MB artifact with no footage under it is a panel that can never open.
        XCTAssertFalse(FileManager.default.fileExists(atPath: cloudSkeleton.path))
        try? FileManager.default.removeItem(at: cloudTrack)
        try? FileManager.default.removeItem(at: localTrack)
    }

    func testDiscardingAnEntryTakesTheCloudAnalysisToo() throws {
        // The other half of the same rule: a coach deleting a video wants
        // everything derived from it gone, cloud included.
        let rally = RallyAppIosKt.createRallyApp(
            url: "https://test.supabase.co",
            anonKey: "test-anon-key",
            deleteLocalVideoFile: { LocalVideoFiles.delete(relativePath: $0) },
            deleteLocalAnalysis: { AnalysisFiles.deleteAll(entryId: $0, reason: $1) }
        )
        let id = "wiring-\(UUID().uuidString)"
        let cloudTrack = try AnalysisFiles.directory("cloud-tracks")
            .appendingPathComponent("\(id).track")
        try "v3 30.0 2\n".write(to: cloudTrack, atomically: true, encoding: .utf8)

        rally.localVideos.add(entry: entry(id: id))
        rally.localVideos.remove(id: id)

        XCTAssertFalse(FileManager.default.fileExists(atPath: cloudTrack.path))
    }
}
