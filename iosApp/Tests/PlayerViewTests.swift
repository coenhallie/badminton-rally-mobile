import Shared
import XCTest
@testable import iosApp

/// A player's page and what it starts: the matches its Matches tab lists, when
/// the page closes itself, and the player a match added from it is for. A new
/// scored match has them at home; a recorded or imported video has them at
/// home and at the near end. Mirrors androidApp's PlayerScreenStateTest,
/// NewMatchViewModelTest and NewLocalVideoEntryTest on the same rules.
@MainActor
final class PlayerViewTests: XCTestCase {

    private let t0 = KotlinInstant.companion.fromEpochMilliseconds(epochMilliseconds: 1_787_940_000_000)

    // MARK: - New match

    func testANewMatchForAPlayerMakesThemHome() {
        let repo = SwiftInteropKt.testScoreLogsRepository(now: t0, ownerId: "owner-1")

        let id = NewMatchView.createMatch(
            in: repo, title: "Thu League", rules: ScoringRules.companion.BWF_21, playerId: "p-marco"
        )

        let log = repo.get(id: id)
        XCTAssertEqual(log?.homePlayerId, "p-marco")
        XCTAssertNil(log?.awayPlayerId)
        XCTAssertEqual(log?.homePlayers, [])
        XCTAssertEqual(log?.awayPlayers, [])
        XCTAssertEqual(log?.title, "Thu League")
    }

    func testANewMatchFromPlayersHasNobodyOnIt() {
        let repo = SwiftInteropKt.testScoreLogsRepository(now: t0, ownerId: "owner-1")

        let id = NewMatchView.createMatch(
            in: repo, title: "Thu League", rules: ScoringRules.companion.BWF_21, playerId: nil
        )

        XCTAssertNil(repo.get(id: id)?.homePlayerId)
        XCTAssertNil(repo.get(id: id)?.awayPlayerId)
    }

    // MARK: - A video added for a player

    private func newEntry(forMatch: MatchTarget?, forPlayer: String?) -> LocalVideoEntry {
        LocalVideoIntake.newEntry(
            id: "e1", uri: "LocalVideos/e1.mp4", displayName: "m.mp4",
            durationMs: 1000, sizeBytes: 2, addedAtEpochMs: 1,
            forMatch: forMatch, forPlayer: forPlayer
        )
    }

    func testAVideoAddedForAPlayerHasThemAtHomeAndAtTheNearEnd() {
        let entry = newEntry(forMatch: nil, forPlayer: "p-marco")

        XCTAssertEqual(entry.homePlayerId, "p-marco")
        XCTAssertEqual(entry.nearSide, .home)
        XCTAssertNil(entry.awayPlayerId)
        XCTAssertEqual(entry.homePlayers, [])
        XCTAssertEqual(entry.awayPlayers, [])
        XCTAssertEqual(entry.stage, .local)
    }

    func testAVideoAddedFromElsewhereHasNobodyAndNoEnd() {
        let entry = newEntry(forMatch: MatchTarget(scoreLogId: "log-1", title: "Thu League"), forPlayer: nil)

        XCTAssertNil(entry.homePlayerId)
        XCTAssertNil(entry.nearSide, "an end was claimed for a video nobody said who was in")
        // A pick for a match carries that match's name and link, as before.
        XCTAssertEqual(entry.scoreLogId, "log-1")
        XCTAssertEqual(entry.title, "Thu League")
    }

    // MARK: - The Matches tab

    private func entry(_ id: String, home: String?) -> LocalVideoEntry {
        LocalVideoEntry(
            id: id, uri: "LocalVideos/\(id).mp4", displayName: "m.mp4",
            durationMs: 48_000, sizeBytes: 2, addedAtEpochMs: 1_787_940_000_000,
            title: nil, description: nil, keypoints: nil,
            stage: .local, failedStep: nil, failureMessage: nil, resultSeen: false,
            scoreLogId: nil,
            homePlayerId: home, awayPlayerId: nil, homePlayers: [], awayPlayers: [], nearSide: nil
        )
    }

    private func sharedClip() -> RallyClip {
        RallyClip(
            id: "v-shared-c1", videoId: "v-shared", ownerId: "user-other",
            rallyIndex: 1, startTimestamp: 0, endTimestamp: 5, durationSeconds: 5,
            clipStoragePath: "user-other/v-shared/rally_1.mp4",
            thumbnailStoragePath: nil, title: nil, annotationCount: 0,
            createdAt: t0
        )
    }

    private var list: MatchList {
        let marco = Player(id: "p-marco", ownerId: "user-self", name: "Marco", racketArm: nil, createdAt: t0, updatedAt: t0)
        return MatchListBuilderKt.buildMatchList(input: MatchListInput(
            clips: [sharedClip()], currentUserId: "user-self",
            sharerByVideoId: [:], metadataByVideoId: [:], scoreLogs: [],
            localEntries: [entry("e-marco", home: "p-marco"), entry("e-nobody", home: nil)],
            cloudProgress: [:], deviceRuns: [:],
            storedTrackIds: [], storedSkeletonIds: [],
            deviceClipCounts: [:], localNoteCounts: [:],
            players: [marco],
            linkedProfiles: [],
            linkedScoreLogs: []
        ))
    }

    func testAPlayersMatchesAreTheirOwnAndNoneShared() {
        let all = list
        XCTAssertFalse(all.shared.isEmpty, "the fixture has no shared match to hide")

        let theirs = MatchesList.listed(all, playerId: "p-marco")

        XCTAssertEqual(theirs.mine.map(\.key), ["video-e-marco"])
        XCTAssertTrue(theirs.shared.isEmpty, "Shared with me showed on a player's page")
    }

    func testAllMatchesListsEverything() {
        let all = list

        let listed = MatchesList.listed(all, playerId: nil)

        XCTAssertEqual(listed.mine.map(\.key), all.mine.map(\.key))
        XCTAssertEqual(listed.shared.map(\.key), all.shared.map(\.key))
    }

    // MARK: - The page closing itself

    func testThePageClosesOnceThePlayerHasLeftTheList() {
        // Deleted here, or on another device and dropped by a refresh.
        XCTAssertTrue(PlayerView.gone(present: false, wasPresent: true, loaded: true))
        XCTAssertFalse(PlayerView.gone(present: true, wasPresent: true, loaded: true))
    }

    func testAPageOpenedBeforeThePlayersLoadedWaitsForThem() {
        XCTAssertFalse(PlayerView.gone(present: false, wasPresent: false, loaded: false))
        XCTAssertTrue(PlayerView.gone(present: false, wasPresent: false, loaded: true))
    }

    // MARK: - Single-pass player summaries

    func testPlayerSummariesMatchSharedPlayerSummary() {
        let older = LocalVideoEntry(
            id: "e-older", uri: "LocalVideos/e-older.mp4", displayName: "m1.mp4",
            durationMs: 48_000, sizeBytes: 2, addedAtEpochMs: 1_787_940_000_000,
            title: nil, description: nil, keypoints: nil,
            stage: .local, failedStep: nil, failureMessage: nil, resultSeen: false,
            scoreLogId: nil,
            homePlayerId: "p-marco", awayPlayerId: "p-li", homePlayers: [], awayPlayers: [], nearSide: nil
        )
        let newer = LocalVideoEntry(
            id: "e-newer", uri: "LocalVideos/e-newer.mp4", displayName: "m2.mp4",
            durationMs: 48_000, sizeBytes: 2, addedAtEpochMs: 1_788_000_000_000,
            title: nil, description: nil, keypoints: nil,
            stage: .local, failedStep: nil, failureMessage: nil, resultSeen: false,
            scoreLogId: nil,
            homePlayerId: "p-marco", awayPlayerId: nil, homePlayers: [], awayPlayers: [], nearSide: nil
        )
        let marco = Player(id: "p-marco", ownerId: "user-self", name: "Marco", racketArm: nil, createdAt: t0, updatedAt: t0)
        let li = Player(id: "p-li", ownerId: "user-self", name: "Li", racketArm: nil, createdAt: t0, updatedAt: t0)
        let matchList = MatchListBuilderKt.buildMatchList(input: MatchListInput(
            clips: [sharedClip()], currentUserId: "user-self",
            sharerByVideoId: [:], metadataByVideoId: [:], scoreLogs: [],
            localEntries: [older, newer],
            cloudProgress: [:], deviceRuns: [:],
            storedTrackIds: [], storedSkeletonIds: [],
            deviceClipCounts: ["e-older": KotlinInt(int: 5), "e-newer": KotlinInt(int: 3)],
            localNoteCounts: [:],
            players: [marco, li],
            linkedProfiles: [],
            linkedScoreLogs: []
        ))

        let summaries = PlayersScreen.playerSummaries(from: matchList)

        XCTAssertEqual(summaries["p-marco"], PlayerMatchesKt.playerSummary(list: matchList, playerId: "p-marco"))
        XCTAssertEqual(summaries["p-li"], PlayerMatchesKt.playerSummary(list: matchList, playerId: "p-li"))
        XCTAssertNil(summaries["p-none"])
    }
}
