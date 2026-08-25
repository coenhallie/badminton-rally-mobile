import XCTest
import Shared
@testable import iosApp

final class LocalVideoLogicTests: XCTestCase {
    func testOversizeMessageExactlyAtCapIsAllowed() {
        XCTAssertNil(LocalVideoLogic.oversizeMessage(bytes: 10_737_418_240))
    }

    func testOversizeMessageAboveCap() {
        XCTAssertEqual(
            LocalVideoLogic.oversizeMessage(bytes: 10_737_418_241),
            "Video is larger than 10GB. Please use a shorter recording."
        )
    }

    /// Pins the shared constant as Swift actually sees it across the interop.
    func testSharedCapIsTenGibibytes() {
        XCTAssertEqual(LocalVideoLimits.shared.MAX_SIZE_BYTES, 10_737_418_240)
    }

    func testFormatDuration() {
        XCTAssertEqual(LocalVideoLogic.formatDuration(ms: 65_000), "1:05")
        XCTAssertEqual(LocalVideoLogic.formatDuration(ms: 0), "0:00")
        XCTAssertEqual(LocalVideoLogic.formatDuration(ms: 599_999), "9:59")
    }

    func testRelativePathRoundTrip() {
        let rel = "LocalVideos/abc.mp4"
        let url = LocalVideoFiles.resolve(relativePath: rel)
        XCTAssertTrue(url.path.hasSuffix("/Documents/LocalVideos/abc.mp4"))
        XCTAssertFalse(rel.hasPrefix("/"))
    }

    func testOrphanedFileNamesFindsFilesNoEntryReferences() {
        let orphans = LocalVideoLogic.orphanedFileNames(
            inStore: ["a.mp4", "b.mp4", "c.mp4"],
            referenced: ["LocalVideos/b.mp4"]
        )
        XCTAssertEqual(orphans.sorted(), ["a.mp4", "c.mp4"])
    }

    func testOrphanedFileNamesKeepsEveryReferencedFile() {
        let orphans = LocalVideoLogic.orphanedFileNames(
            inStore: ["a.mp4", "b.mp4"],
            referenced: ["LocalVideos/a.mp4", "LocalVideos/b.mp4"]
        )
        XCTAssertTrue(orphans.isEmpty)
    }

    func testOrphanedFileNamesOnAnEmptyStoreIsEmpty() {
        XCTAssertTrue(LocalVideoLogic.orphanedFileNames(inStore: [], referenced: []).isEmpty)
    }

    func testOrphanedFileNamesIgnoresEntriesWhoseFileIsAlreadyGone() {
        // A registry entry can outlive its file (manual container edit, restore
        // from a partial backup). That must not make the sweep delete anything.
        let orphans = LocalVideoLogic.orphanedFileNames(
            inStore: ["a.mp4"],
            referenced: ["LocalVideos/a.mp4", "LocalVideos/vanished.mp4"]
        )
        XCTAssertTrue(orphans.isEmpty)
    }

    func testSweepOrphansDeletesOnlyUnreferencedFiles() throws {
        let keep = try LocalVideoFiles.store(tempURL: tempVideo())
        let orphan = try LocalVideoFiles.store(tempURL: tempVideo())

        LocalVideoFiles.sweepOrphans(referenced: [keep])

        XCTAssertTrue(FileManager.default.fileExists(
            atPath: LocalVideoFiles.resolve(relativePath: keep).path))
        XCTAssertFalse(FileManager.default.fileExists(
            atPath: LocalVideoFiles.resolve(relativePath: orphan).path))
        LocalVideoFiles.delete(relativePath: keep)
    }

    private func tempVideo() -> URL {
        let temp = FileManager.default.temporaryDirectory
            .appendingPathComponent("sweep-\(UUID().uuidString).mp4")
        try? Data([0x00, 0x01]).write(to: temp)
        return temp
    }

    func testStoreCopiesIntoLocalVideosAndDeleteRemoves() throws {
        let temp = FileManager.default.temporaryDirectory
            .appendingPathComponent("store-test-\(UUID().uuidString).mp4")
        try Data([0x00, 0x01]).write(to: temp)
        let rel = try LocalVideoFiles.store(tempURL: temp)
        XCTAssertTrue(rel.hasPrefix("LocalVideos/"))
        let stored = LocalVideoFiles.resolve(relativePath: rel)
        XCTAssertTrue(FileManager.default.fileExists(atPath: stored.path))
        LocalVideoFiles.delete(relativePath: rel)
        XCTAssertFalse(FileManager.default.fileExists(atPath: stored.path))
    }
}
