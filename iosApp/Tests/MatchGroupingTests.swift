import XCTest
@testable import iosApp

final class MatchGroupingTests: XCTestCase {
    private func clip(
        id: String, videoId: String, owner: String = "me",
        rallyIndex: Int32, createdAt: Int64
    ) -> ClipInfo {
        ClipInfo(
            id: id, videoId: videoId, ownerId: owner, rallyIndex: rallyIndex,
            createdAtMillis: createdAt, title: nil, durationSeconds: 10,
            annotationCount: 0
        )
    }

    func testGroupsByVideoCoverIsMinRallyIndexSortedByLatestDesc() {
        let clips = [
            clip(id: "a", videoId: "v1", rallyIndex: 2, createdAt: 100),
            clip(id: "b", videoId: "v1", rallyIndex: 1, createdAt: 200),
            clip(id: "c", videoId: "v2", rallyIndex: 1, createdAt: 300),
        ]
        let result = MatchGrouping.matches(from: clips, currentUserId: "me", sharerByVideoId: [:])
        XCTAssertEqual(result.owned.map(\.videoId), ["v2", "v1"])   // latestCreatedAt desc
        XCTAssertEqual(result.owned[1].coverClipId, "b")            // min rallyIndex
        XCTAssertEqual(result.owned[1].rallyCount, 2)
        XCTAssertEqual(result.owned[1].latestCreatedAtMillis, 200)  // max createdAt
        XCTAssertTrue(result.shared.isEmpty)
    }

    func testPartitionsSharedMatchesWithSharerEmail() {
        let clips = [clip(id: "a", videoId: "v9", owner: "someone-else", rallyIndex: 1, createdAt: 50)]
        let result = MatchGrouping.matches(
            from: clips, currentUserId: "me", sharerByVideoId: ["v9": "coach@x.com"]
        )
        XCTAssertTrue(result.owned.isEmpty)
        XCTAssertEqual(result.shared.first?.sharerEmail, "coach@x.com")
        XCTAssertEqual(result.shared.first?.isOwned, false)
    }
}
