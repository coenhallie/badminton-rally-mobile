import XCTest
@testable import iosApp

final class LocalVideoLogicTests: XCTestCase {
    func testOversizeMessageExactlyAtCapIsAllowed() {
        XCTAssertNil(LocalVideoLogic.oversizeMessage(bytes: 1_073_741_824))
    }

    func testOversizeMessageAboveCap() {
        XCTAssertEqual(
            LocalVideoLogic.oversizeMessage(bytes: 1_073_741_825),
            "Video is larger than 1GB. Please use a shorter recording."
        )
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
