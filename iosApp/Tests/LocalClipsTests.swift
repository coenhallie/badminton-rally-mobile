import Foundation
import XCTest
@testable import iosApp

/// How the clips an on-device run cut are named, and what the screen listing
/// them finds on disk.
///
/// The naming is pinned because it is the only thing on that screen: a coach
/// judging whether the two pipelines cut at the same moment reads the bounds
/// before pressing play, and a clip whose bounds are unknown must say so rather
/// than read as one that starts and ends at zero.
final class LocalClipsTests: XCTestCase {

    private let entry = "local-clips-under-test"

    override func setUpWithError() throws {
        AnalysisFiles.deleteAll(entryId: entry)
    }

    override func tearDownWithError() throws {
        AnalysisFiles.deleteAll(entryId: entry)
    }

    private func clip(_ index: Int, _ start: Double, _ end: Double) -> PlayerTrackStore.Clip {
        PlayerTrackStore.Clip(
            index: index,
            url: URL(fileURLWithPath: "/tmp/rally-\(index).mp4"),
            startSeconds: start,
            endSeconds: end
        )
    }

    func testTheListNamesARallyItsBoundsAndItsLength() {
        XCTAssertEqual(
            localClipLabel(clip(3, 12.34, 19.86)),
            "Rally 3  12.3s - 19.9s  (7.5s)"
        )
    }

    func testThePlayerSetsTheSameBoundsFinerAndDropsTheLength() {
        // Two decimals over the player, where the number is the boundary being
        // judged rather than a label being scanned; the length is on the list.
        XCTAssertEqual(
            localClipLabel(clip(3, 12.34, 19.86), decimals: 2),
            "Rally 3  12.34s - 19.86s"
        )
    }

    func testAClipWithNoBoundsIsNamedWithoutFabricatingThem() {
        // Clips recovered by filename have no bounds - loadClips falls back to
        // scanning the directory when the index sidecar is gone - and a
        // fabricated "0.0s - 0.0s" would read as a broken clip rather than as
        // missing bookkeeping.
        XCTAssertEqual(localClipLabel(clip(7, 0, 0)), "Rally 7")
        XCTAssertEqual(localClipLabel(clip(7, 5, 5), decimals: 2), "Rally 7")
    }

    @MainActor
    func testTheCountIsAbsentRatherThanZeroForAnEntryWithNoClips() {
        // The row's menu item is offered on "has a count", so an entry with no
        // clips must not come back as zero and put "Clips on this phone (0)" in
        // front of a coach.
        let runner = LocalAnalysisRunner()
        XCTAssertNil(runner.storedClipCounts(among: [entry])[entry])
        XCTAssertTrue(runner.storedClips(entryId: entry).isEmpty)
    }

    @MainActor
    func testTheCountMatchesWhatTheScreenWillList() throws {
        let saved = try writeClips([(1, 0, 4), (2, 9, 15)])

        let runner = LocalAnalysisRunner()
        XCTAssertEqual(runner.storedClipCounts(among: [entry])[entry], 2)
        XCTAssertEqual(runner.storedClips(entryId: entry).map(\.index), [1, 2])
        XCTAssertEqual(runner.storedClips(entryId: entry)[1].endSeconds, 15)
        XCTAssertEqual(runner.storedClips(entryId: entry)[0].url, saved[0].url)
    }

    @MainActor
    func testAClipWhoseFileHasGoneIsNeitherCountedNorListed() throws {
        // The count and the list have to agree, or the row promises three clips
        // and the screen it opens shows two.
        let saved = try writeClips([(1, 0, 4), (2, 9, 15)])
        try FileManager.default.removeItem(at: saved[0].url)

        let runner = LocalAnalysisRunner()
        XCTAssertEqual(runner.storedClipCounts(among: [entry])[entry], 1)
        XCTAssertEqual(runner.storedClips(entryId: entry).map(\.index), [2])
    }

    /// Writes the index AND the files it names: `loadClips` drops a clip whose
    /// file has gone, so an index alone lists nothing.
    private func writeClips(_ bounds: [(Int, Double, Double)]) throws -> [PlayerTrackStore.Clip] {
        let directory = try AnalysisFiles.directory("local-clips/\(entry)")
        let clips = try bounds.map { index, start, end -> PlayerTrackStore.Clip in
            let url = directory.appendingPathComponent("rally-\(index).mp4")
            try Data("not really an mp4".utf8).write(to: url)
            return PlayerTrackStore.Clip(index: index, url: url, startSeconds: start, endSeconds: end)
        }
        try PlayerTrackStore().saveClips(entryId: entry, clips: clips)
        return clips
    }
}
