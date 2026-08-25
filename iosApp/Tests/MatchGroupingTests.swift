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

    // MARK: - Match name (mirrors Android ClipListViewModelTest / MatchRowLabelsTest)

    private func titledClip(
        id: String, videoId: String, rallyIndex: Int32, title: String?
    ) -> ClipInfo {
        ClipInfo(
            id: id, videoId: videoId, ownerId: "me", rallyIndex: rallyIndex,
            createdAtMillis: 1_784_980_800_000, title: title,
            durationSeconds: 10, annotationCount: 0
        )
    }

    func testMatchTitleComesFromTheClipsMatchName() {
        let clips = [
            titledClip(id: "a", videoId: "v1", rallyIndex: 0, title: "Thu League vs Marco"),
            titledClip(id: "b", videoId: "v1", rallyIndex: 1, title: "Thu League vs Marco"),
        ]
        let result = MatchGrouping.matches(from: clips, currentUserId: "me", sharerByVideoId: [:])
        XCTAssertEqual(result.owned.first?.title, "Thu League vs Marco")
    }

    func testMatchTitleIsNilWhenNoClipCarriesOne() {
        let clips = [titledClip(id: "a", videoId: "v1", rallyIndex: 0, title: nil)]
        let result = MatchGrouping.matches(from: clips, currentUserId: "me", sharerByVideoId: [:])
        XCTAssertNil(result.owned.first?.title)
    }

    func testMatchTitleSurvivesASingleClipBeingRenamed() {
        let clips = [
            titledClip(id: "a", videoId: "v1", rallyIndex: 0, title: "Great smash"),
            titledClip(id: "b", videoId: "v1", rallyIndex: 1, title: "Thu League vs Marco"),
            titledClip(id: "c", videoId: "v1", rallyIndex: 2, title: "Thu League vs Marco"),
        ]
        let result = MatchGrouping.matches(from: clips, currentUserId: "me", sharerByVideoId: [:])
        XCTAssertEqual(result.owned.first?.title, "Thu League vs Marco")
    }

    private func summary(title: String?, rallyCount: Int = 12) -> MatchSummary {
        MatchSummary(
            videoId: "v", rallyCount: rallyCount,
            latestCreatedAtMillis: 1_784_980_800_000,
            coverClipId: "c", isOwned: true, sharerEmail: nil, title: title
        )
    }

    func testNamedMatchLeadsWithTheMatchName() {
        XCTAssertEqual(matchRowPrimary(summary(title: "Thu League vs Marco")), "Thu League vs Marco")
    }

    func testNamedMatchKeepsTheDateBesideTheRallyCount() {
        XCTAssertEqual(
            matchRowSecondary(summary(title: "Thu League vs Marco")),
            "12 RALLIES \u{00B7} JUL 25, 2026"
        )
    }

    func testUnnamedMatchKeepsTheOriginalDateHeadline() {
        XCTAssertEqual(matchRowPrimary(summary(title: nil)), "Match \u{00B7} Jul 25, 2026")
    }

    func testUnnamedMatchSecondaryStaysRallyCountOnly() {
        XCTAssertEqual(matchRowSecondary(summary(title: nil)), "12 RALLIES")
    }

    func testSingleRallyIsNotPluralised() {
        XCTAssertEqual(matchRowSecondary(summary(title: nil, rallyCount: 1)), "1 RALLY")
    }

    // MARK: - Rally row labels (mirrors Android MatchRowLabelsTest)

    func testClipCarryingOnlyTheMatchNameShowsItsRallyNumber() {
        let c = titledClip(id: "a", videoId: "v1", rallyIndex: 3, title: "Thu League vs Marco")
        XCTAssertEqual(clipRowTitle(c, matchTitle: "Thu League vs Marco"), "Rally #3")
    }

    func testClipRenamedByTheUserKeepsItsOwnTitle() {
        let c = titledClip(id: "a", videoId: "v1", rallyIndex: 3, title: "Great smash")
        XCTAssertEqual(clipRowTitle(c, matchTitle: "Thu League vs Marco"), "Great smash")
    }

    func testUntitledClipShowsItsRallyNumber() {
        let c = titledClip(id: "a", videoId: "v1", rallyIndex: 3, title: nil)
        XCTAssertEqual(clipRowTitle(c, matchTitle: "Thu League vs Marco"), "Rally #3")
    }

    func testUntitledClipInAnUnnamedMatchShowsItsRallyNumber() {
        let c = titledClip(id: "a", videoId: "v1", rallyIndex: 3, title: nil)
        XCTAssertEqual(clipRowTitle(c, matchTitle: nil), "Rally #3")
    }
}
