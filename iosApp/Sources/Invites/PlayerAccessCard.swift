import SwiftUI
import Shared

/// Coach-side "Player access" card rendered on a player's Profile tab directly
/// below the 3 stat tiles. Mirrors androidApp's `PlayerAccessCard.kt`.
struct PlayerAccessCard: View {
    let playerName: String
    let accessState: PlayerAccessState?
    let busy: Bool
    let onOpenInvite: () -> Void
    let onCancelInvite: () -> Void
    let onRemoveAccess: () -> Void

    @State private var confirmRemove = false

    private var status: PlayerAccessStatus {
        accessState?.status ?? .uninvited
    }

    var body: some View {
        let copy = PlayerInviteCopy.shared
        VStack(alignment: .leading, spacing: 10) {
            Text(copy.ACCESS_SECTION_TITLE.uppercased())
                .shuttlType(ShuttlType.labelSmall)
                .foregroundStyle(Shuttl.textSecondary)

            VStack(alignment: .leading, spacing: 12) {
                switch status {
                case .uninvited:
                    Text(copy.inviteCardHeading(playerName: playerName))
                        .shuttlType(ShuttlType.titleMedium)
                        .foregroundStyle(Shuttl.textHeading)
                    Text(copy.inviteCardBody(playerName: playerName))
                        .shuttlType(ShuttlType.bodySmall)
                        .foregroundStyle(Shuttl.textSecondary)
                    Button {
                        onOpenInvite()
                    } label: {
                        HStack(spacing: 8) {
                            if busy { ProgressView().tint(Shuttl.onAccent).controlSize(.small) }
                            Text(copy.inviteButton(playerName: playerName))
                        }
                    }
                    .buttonStyle(PrimaryButtonStyle())
                    .disabled(busy)

                case .pending:
                    statusBadge(label: copy.ACCESS_BADGE_PENDING, color: Shuttl.warning)
                    if let code = accessState?.pendingCode, !code.isEmpty {
                        Text(InviteCodeFormatKt.formatInviteCode(code: code))
                            .shuttlType(ShuttlType.headlineMedium, monospacedDigit: true)
                            .tracking(1.5)
                            .foregroundStyle(Shuttl.textHeading)
                    }
                    if let expiresAt = accessState?.pendingExpiresAt {
                        let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
                        Text(copy.expiryCaption(expiresAt: expiresAt, nowEpochMs: nowMs))
                            .shuttlType(ShuttlType.bodySmall)
                            .foregroundStyle(Shuttl.textSecondary)
                    }
                    HStack(spacing: 12) {
                        Button(action: onOpenInvite) {
                            HStack(spacing: 6) {
                                if busy { ProgressView().tint(Shuttl.onAccent).controlSize(.small) }
                                Text(copy.ACCESS_SHOW_CODE)
                            }
                            .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(CompactPillButtonStyle(fullTarget: true))
                        .disabled(busy)

                        Button(copy.ACCESS_CANCEL, action: onCancelInvite)
                            .shuttlType(ShuttlType.labelMedium)
                            .foregroundStyle(Shuttl.textSecondary)
                            .frame(minHeight: ShuttlTarget.minimum)
                            .disabled(busy)
                    }

                case .linked:
                    statusBadge(label: copy.ACCESS_BADGE_CONNECTED, color: Shuttl.accent)
                    if let email = accessState?.linkedEmail, !email.isEmpty {
                        Text(email)
                            .shuttlType(ShuttlType.bodyLarge)
                            .foregroundStyle(Shuttl.textHeading)
                    }
                    if let linkedAt = accessState?.linkedAt {
                        Text(copy.linkedDateLabel(linkedAt: linkedAt, timeZone: .companion.currentSystemDefault()))
                            .shuttlType(ShuttlType.bodySmall)
                            .foregroundStyle(Shuttl.textSecondary)
                    }
                    Button {
                        confirmRemove = true
                    } label: {
                        HStack(spacing: 6) {
                            if busy { ProgressView().controlSize(.small) }
                            Text(copy.ACCESS_REMOVE)
                        }
                        .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(CompactPillButtonStyle(secondary: true, fullTarget: true))
                    .disabled(busy)
                    .confirmationDialog(
                        copy.removeAccessTitle(playerName: playerName),
                        isPresented: $confirmRemove,
                        titleVisibility: .visible
                    ) {
                        Button(copy.ACCESS_REMOVE_CONFIRM, role: .destructive, action: onRemoveAccess)
                        Button(copy.ACCESS_CANCEL, role: .cancel) {}
                    } message: {
                        Text(copy.removeAccessBody(playerName: playerName))
                    }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(18)
            .background(
                Shuttl.bgSecondary,
                in: RoundedRectangle(cornerRadius: ShuttlRadius.large, style: .continuous)
            )
        }
    }

    private func statusBadge(label: String, color: Color) -> some View {
        HStack(spacing: 8) {
            Circle()
                .fill(color)
                .frame(width: 8, height: 8)
            Text(label)
                .shuttlType(ShuttlType.labelMedium)
                .foregroundStyle(Shuttl.textHeading)
        }
    }
}
