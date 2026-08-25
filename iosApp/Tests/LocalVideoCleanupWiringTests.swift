import XCTest
import Shared
@testable import iosApp

/// Covers the one link the Kotlin unit tests cannot reach: that the Swift closure
/// handed to `createRallyApp` is the one the shared registry actually calls back
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
            resultSeen: false
        )
    }

    func testRemovingAnEntryCallsBackIntoTheSwiftFileDelete() {
        var deleted: [String] = []
        let rally = RallyAppIosKt.createRallyApp(
            url: "https://test.supabase.co",
            anonKey: "test-anon-key",
            deleteLocalVideoFile: { deleted.append($0) }
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
            deleteLocalVideoFile: { LocalVideoFiles.delete(relativePath: $0) }
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
            failedStep: nil, failureMessage: nil, resultSeen: false
        ))
        XCTAssertTrue(FileManager.default.fileExists(atPath: stored.path))

        rally.localVideos.remove(id: id)

        XCTAssertFalse(FileManager.default.fileExists(atPath: stored.path))
    }
}
