import XCTest
@testable import iosApp

final class ClipSortTests: XCTestCase {
    private func clip(id: String, rallyIndex: Int32, notes: Int32) -> ClipInfo {
        ClipInfo(
            id: id, videoId: "v", ownerId: "me", rallyIndex: rallyIndex,
            createdAtMillis: 0, title: nil, durationSeconds: 10,
            annotationCount: notes
        )
    }

    func testRallyOrderSortsByRallyIndex() {
        let sorted = ClipSort.rallyOrder.sorted([
            clip(id: "b", rallyIndex: 2, notes: 5),
            clip(id: "a", rallyIndex: 1, notes: 0),
        ])
        XCTAssertEqual(sorted.map(\.id), ["a", "b"])
    }

    func testMostNotesSortsDescendingWithRallyIndexTiebreak() {
        let sorted = ClipSort.mostNotes.sorted([
            clip(id: "c", rallyIndex: 3, notes: 1),
            clip(id: "a", rallyIndex: 1, notes: 1),
            clip(id: "b", rallyIndex: 2, notes: 3),
        ])
        XCTAssertEqual(sorted.map(\.id), ["b", "a", "c"])
    }
}
