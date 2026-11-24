import Foundation
import Shared

/// Observes `PlayerAccessRepository` for both coach-side access states and
/// player-side linked profiles. Mirrors androidApp's `PlayerAccessViewModel`.
@Observable @MainActor
final class PlayerAccessModel {
    private let repo: PlayerAccessRepository

    private(set) var accessByPlayerId: [String: PlayerAccessState]
    private(set) var linkedProfiles: [LinkedPlayerProfile]
    private(set) var linkedProfilesLoaded: Bool
    private(set) var busyPlayerId: String? = nil
    var errorMessage: String? = nil

    init(repo: PlayerAccessRepository) {
        self.repo = repo
        self.accessByPlayerId = repo.accessByPlayerId.value
        self.linkedProfiles = repo.linkedProfiles.value
        self.linkedProfilesLoaded = repo.linkedProfilesLoaded.value.boolValue
    }

    convenience init(rally: RallyApp) {
        self.init(repo: rally.playerAccess)
    }

    func observe() async {
        await withTaskGroup(of: Void.self) { group in
            group.addTask { @MainActor [weak self] in
                guard let self else { return }
                for await map in self.repo.accessByPlayerId {
                    self.accessByPlayerId = map
                }
            }
            group.addTask { @MainActor [weak self] in
                guard let self else { return }
                for await profiles in self.repo.linkedProfiles {
                    self.linkedProfiles = profiles
                }
            }
            group.addTask { @MainActor [weak self] in
                guard let self else { return }
                for await loaded in self.repo.linkedProfilesLoaded {
                    self.linkedProfilesLoaded = loaded.boolValue
                }
            }
        }
    }

    func refreshAccessStates() async {
        _ = try? await SwiftInteropKt.refreshAccessStatesOrMessage(repo)
    }

    func refreshLinkedProfiles() async {
        _ = try? await SwiftInteropKt.refreshLinkedProfilesOrMessage(repo)
    }

    @discardableResult
    func createInvite(playerId: String) async -> CreatedInvite? {
        busyPlayerId = playerId
        defer { if busyPlayerId == playerId { busyPlayerId = nil } }
        do {
            let outcome = try await SwiftInteropKt.createInviteForSwift(repo, playerId: playerId)
            if let message = outcome.errorMessage {
                errorMessage = message
                return nil
            }
            return outcome.invite
        } catch {
            errorMessage = PlayerInviteCopy.shared.errorMessage(kind: .unknown)
            return nil
        }
    }

    @discardableResult
    func revokeInvite(playerId: String) async -> Bool {
        busyPlayerId = playerId
        defer { if busyPlayerId == playerId { busyPlayerId = nil } }
        do {
            if let message = try await SwiftInteropKt.revokeInviteOrMessage(repo, playerId: playerId) {
                errorMessage = message
                return false
            }
            return true
        } catch {
            errorMessage = PlayerInviteCopy.shared.errorMessage(kind: .unknown)
            return false
        }
    }

    @discardableResult
    func unlinkPlayer(playerId: String) async -> Bool {
        busyPlayerId = playerId
        defer { if busyPlayerId == playerId { busyPlayerId = nil } }
        do {
            if let message = try await SwiftInteropKt.unlinkPlayerOrMessage(repo, playerId: playerId) {
                errorMessage = message
                return false
            }
            return true
        } catch {
            errorMessage = PlayerInviteCopy.shared.errorMessage(kind: .unknown)
            return false
        }
    }

    func redeemExistingCode(_ code: String) async -> RedeemInviteOutcome {
        do {
            return try await SwiftInteropKt.redeemInviteExistingAccountForSwift(repo, code: code)
        } catch {
            return RedeemInviteOutcome(
                redeemed: nil,
                errorKind: .unknown,
                errorMessage: PlayerInviteCopy.shared.errorMessage(kind: .unknown)
            )
        }
    }

    func errorShown() {
        errorMessage = nil
    }
}
