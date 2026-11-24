import Foundation
import Shared

/// What `PlayersModel.create` came to: the new player, or the sentence to say
/// in the sheet that asked. Mirrors androidApp's `PlayerCreation`.
enum PlayerCreation {
    case created(Player)
    case failed(String)
}

/// The coach's players, for the Players screen and a player's page. Mirrors
/// androidApp's PlayersViewModel; every sentence it sets comes from
/// PlayersCopy, by way of the shared functions it calls.
///
/// One instance for the app, made by HomeView beside the match list's model,
/// so the Players screen and a player's page read one list.
@Observable @MainActor
final class PlayersModel {
    /// Sorted by name: the repository publishes them through `sortedPlayers`.
    private(set) var players: [Player] = []
    /// True when the most recent refresh failed, so an empty list can say the
    /// load failed rather than "Add your first player" once the toast that
    /// reported it has gone.
    private(set) var loadFailed = false
    /// False until the first refresh has settled, either way. With no cached
    /// players, an empty list before then says nothing yet: "Add your first
    /// player" on a fresh install whose players are still on their way would
    /// invite a duplicate.
    private(set) var loaded = false
    /// A sentence for the root toast; cleared by `errorShown`.
    private(set) var errorMessage: String? = nil

    /// An empty list that means it: loaded, or failed, and nothing cached.
    var showsNoPlayers: Bool { players.isEmpty && loaded }

    private let repo: PlayersRepository
    private let scoreLogs: ScoreLogsRepository
    private let localVideos: LocalVideoRepository
    private let drillProgress: DrillProgressRepository
    /// Who is signed in, for the refresh's forget guard (see
    /// `refreshPlayersAndForgetVanishedOrMessage`). Not isolated to the main
    /// actor: shared code asks it again after the request, on whatever thread
    /// that resumed on.
    private let currentUserId: @Sendable () -> String?
    /// Whether the refresh that opens the screen has been kicked off: once per
    /// model, as androidApp's view model refreshes once in its init.
    private var initialRefreshStarted = false

    init(
        rally: RallyApp,
        players: PlayersRepository? = nil,
        scoreLogs: ScoreLogsRepository? = nil,
        localVideos: LocalVideoRepository? = nil,
        drillProgress: DrillProgressRepository? = nil,
        currentUserId: (@Sendable () -> String?)? = nil
    ) {
        self.repo = players ?? rally.players
        self.scoreLogs = scoreLogs ?? rally.scoreLogs
        self.localVideos = localVideos ?? rally.localVideos
        self.drillProgress = drillProgress ?? rally.drillProgress
        let auth = rally.auth
        self.currentUserId = currentUserId ?? { auth.currentUserId() }
    }

    /// For a screen's `.task`: the first refresh, once per model, then
    /// `observe()` until the screen goes. The refresh is unstructured on
    /// purpose, as `ClipListModel.start()`'s is: it is finite, and cancelling
    /// it because the coach opened a match would report a failed load for a
    /// request that was merely abandoned.
    func start() async {
        if !initialRefreshStarted {
            initialRefreshStarted = true
            Task { await refresh() }
        }
        await observe()
    }

    /// Follows the repository's list until the calling task is cancelled.
    /// Every create, rename and delete lands in that list, so this one loop is
    /// what shows their result.
    func observe() async {
        for await list in repo.players {
            players = list
        }
    }

    /// Through the shared refresh-and-forget rather than the repository's own
    /// refresh. A bare refresh would drop a player deleted on another device
    /// from the repository's list, and the match list's next refresh, which
    /// reads its "before" from that same list, would then never see the player
    /// go: the cached logs would keep the dead id and the next sync would be
    /// refused.
    func refresh() async {
        // do/catch, not `try?`: a throw read as nil would be this call's
        // success, and the screen would invite a first player over a load that
        // never happened.
        let message: String?
        do {
            message = try await DeletePlayerKt.refreshPlayersAndForgetVanishedOrMessage(
                players: repo, scoreLogs: scoreLogs, localVideos: localVideos,
                drillProgress: drillProgress, currentOwnerId: currentUserId
            )
        } catch {
            message = PlayersCopy.shared.LOAD_FAILED
        }
        loaded = true
        loadFailed = message != nil
        if let message { errorMessage = message }
    }

    /// The new player, or the sentence saying why not. Not put on the toast:
    /// the sheet that asked stays open and says it there, so a failed add never
    /// costs the coach the name they typed.
    ///
    /// The insert runs in a task of its own and is only awaited here, as
    /// androidApp's runs on its view model's scope: a sheet that goes away
    /// mid-request abandons the wait, not an insert that may already have
    /// reached the server.
    func create(name: String, arm: RacketArm?) async -> PlayerCreation {
        let repo = self.repo
        return await Task { @MainActor in
            do {
                let outcome = try await SwiftInteropKt.createPlayerForSwift(repo, name: name, racketArm: arm)
                if let player = outcome.player { return PlayerCreation.created(player) }
                return .failed(outcome.errorMessage ?? PlayersCopy.shared.SAVE_FAILED)
            } catch {
                return .failed(PlayersCopy.shared.SAVE_FAILED)
            }
        }.value
    }

    /// Nil once the new name is saved, or the sentence saying why not. Like
    /// `create`, for the sheet that asked: it stays open with the name typed.
    func rename(id: String, name: String) async -> String? {
        let repo = self.repo
        return await Task { @MainActor in
            do {
                return try await SwiftInteropKt.renamePlayerOrMessage(repo, id: id, name: name)
            } catch {
                return PlayersCopy.shared.SAVE_FAILED
            }
        }.value
    }

    /// `arm` nil clears the hand. A failure goes to the toast.
    func setRacketArm(id: String, arm: RacketArm?) async {
        do {
            if let message = try await SwiftInteropKt.setPlayerRacketArmOrMessage(repo, id: id, racketArm: arm) {
                errorMessage = message
            }
        } catch {
            errorMessage = PlayersCopy.shared.SAVE_FAILED
        }
    }

    /// The one delete path: the matches keep the name and lose the id, here
    /// and on the server. Whether the server agreed; a refusal goes to the
    /// toast.
    func delete(_ player: Player) async -> Bool {
        do {
            if let message = try await DeletePlayerKt.deletePlayerOrMessage(
                players: repo, scoreLogs: scoreLogs, localVideos: localVideos,
                player: player, drillProgress: drillProgress
            ) {
                errorMessage = message
                return false
            }
            return true
        } catch {
            errorMessage = PlayersCopy.shared.DELETE_FAILED
            return false
        }
    }

    func errorShown() {
        errorMessage = nil
    }
}
