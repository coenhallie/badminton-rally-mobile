import Shared
import UIKit
import XCTest
@testable import iosApp

@MainActor
final class PlayerInviteFlowTests: XCTestCase {

    private let t0 = KotlinInstant.companion.fromEpochMilliseconds(epochMilliseconds: 1_787_940_000_000)

    override func tearDown() {
        AnalysisFiles.resetOwnerScopeForTesting()
        super.tearDown()
    }

    func testAccountRootMigratesFirstOwnerAndIsolatesSecondOwner() throws {
        let tempBase = FileManager.default.temporaryDirectory
            .appendingPathComponent("analysis-root-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: tempBase) }

        let coachRoot = try XCTUnwrap(AnalysisFiles.accountRoot(baseDir: tempBase, ownerId: "coach-1"))
        XCTAssertEqual(coachRoot.standardizedFileURL, tempBase.standardizedFileURL)

        let playerRoot = try XCTUnwrap(AnalysisFiles.accountRoot(baseDir: tempBase, ownerId: "player-2"))
        XCTAssertNotEqual(playerRoot.standardizedFileURL, coachRoot.standardizedFileURL)
        XCTAssertTrue(AnalysisFiles.isStrictlyInside(playerRoot, base: tempBase))

        // Coach returning gets the original legacy root back; signed-out returns nil.
        XCTAssertEqual(
            AnalysisFiles.accountRoot(baseDir: tempBase, ownerId: "coach-1")?.standardizedFileURL,
            coachRoot.standardizedFileURL
        )
        XCTAssertNil(AnalysisFiles.accountRoot(baseDir: tempBase, ownerId: nil))
    }

    func testPlayerAccessModelCreateRevokeAndUnlinkTransitions() async {
        let repo = IosTestDoublesKt.testPlayerAccessRepository(
            initialAccess: [:],
            initialLinked: [],
            initialLoaded: false
        )
        let model = PlayerAccessModel(repo: repo)
        let observeTask = Task { await model.observe() }
        defer { observeTask.cancel() }

        let created = await model.createInvite(playerId: "p-1")
        XCTAssertEqual(created?.code, "K7M4QP9R")
        let becamePending = await ObservationLifecycle.wait {
            model.accessByPlayerId["p-1"]?.status == .pending
        }
        XCTAssertTrue(becamePending)
        XCTAssertEqual(model.accessByPlayerId["p-1"]?.pendingCode, "K7M4QP9R")

        let revoked = await model.revokeInvite(playerId: "p-1")
        XCTAssertTrue(revoked)
        let becameUninvited = await ObservationLifecycle.wait {
            model.accessByPlayerId["p-1"]?.status == .uninvited
        }
        XCTAssertTrue(becameUninvited)

        await model.refreshLinkedProfiles()
        let loaded = await ObservationLifecycle.wait { model.linkedProfilesLoaded }
        XCTAssertTrue(loaded)
    }

    func testQrImageGenerationProducesImageForInvitePayload() {
        let payload = InviteCodeFormatKt.qrPayloadForCode(code: "K7M4QP9R")
        XCTAssertEqual(payload, "shuttl-invite:K7M4QP9R")
        let image = PlayerInviteSheet.makeQrImage(payload: payload)
        XCTAssertNotNil(image)
        XCTAssertGreaterThan(image?.size.width ?? 0, 0)
        XCTAssertGreaterThan(image?.size.height ?? 0, 0)
    }

    func testMatchModelLoadsReadOnlyLinkedScoreLogWithoutWritePermissions() async throws {
        let linkedLog = ScoreLog(
            id: "log-linked-1",
            videoId: "vid-linked-1",
            title: "Club Ladder",
            homePlayers: [],
            awayPlayers: ["Opponent"],
            homePlayerId: "p-linked",
            awayPlayerId: nil,
            format: .singles,
            rules: ScoringRules.companion.BWF_21,
            setup: MatchSetup(firstServer: .home),
            events: Array(repeating: ScoreEventPointTo(side: .home), count: 42),
            status: .live,
            createdAt: t0,
            updatedAt: t0,
            ownerId: "coach-1"
        )
        let scoreLogs = IosTestDoublesKt.testScoreLogsRepositoryWithLinked(
            now: t0,
            ownerId: "player-user-1",
            linkedLogs: [linkedLog]
        )
        let linkedProfile = LinkedPlayerProfile(
            playerId: "p-linked",
            coachId: "coach-1",
            coachEmail: "coach@example.com",
            name: "Marco",
            racketArm: .left,
            linkedAt: t0
        )
        let playerAccess = IosTestDoublesKt.testPlayerAccessRepository(
            initialAccess: [:],
            initialLinked: [linkedProfile],
            initialLoaded: true
        )

        let model = MatchModel(
            scoreLogs: scoreLogs,
            localVideos: IosTestDoublesKt.testLocalVideoRepository(),
            clips: IosTestDoublesKt.testClipsRepository(),
            videos: IosTestDoublesKt.testVideosRepository(),
            localAnnotations: IosTestDoublesKt.testLocalAnnotationsRepository(),
            players: nil,
            playerAccess: playerAccess,
            scoreLogId: linkedLog.id
        )

        XCTAssertEqual(model.log?.id, linkedLog.id)
        XCTAssertFalse(model.isOwnedLog)
        XCTAssertFalse(model.canAddVideo)
        XCTAssertFalse(model.canRemoveVideo)
        let card = try XCTUnwrap(model.card)
        XCTAssertEqual(card.playersLine, "Marco vs Opponent")
        XCTAssertEqual(card.statusLine, "Marco won")

        // Write attempts on a linked log must be no-ops.
        let removed = await model.removeVideo()
        XCTAssertFalse(removed)
        await model.updateDetails(
            title: "Tampered",
            format: .singles,
            players: MatchPlayers.companion.NONE,
            end: nil
        )
        XCTAssertEqual(model.log?.title, "Club Ladder")
    }

    func testLinkedProfileRouteIsNotAListScreen() {
        XCTAssertFalse(HomePath.listInFront([.linkedProfile(id: "p-1")]))
    }
}
