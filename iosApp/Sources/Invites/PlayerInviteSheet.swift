import CoreImage.CIFilterBuiltins
import SwiftUI
import UIKit
import Shared

/// Bottom sheet showing a single-use invite code, tap-to-copy, crisp CoreImage
/// QR code, native `ShareLink`, regenerate, cancel, and optional phone-only
/// matches warning. Mirrors androidApp's `PlayerInviteSheet.kt`.
struct PlayerInviteSheet: View {
    let playerName: String
    let code: String?
    let expiresAt: KotlinInstant?
    let phoneOnlyMatchCount: Int
    let loading: Bool
    let onCopyCode: () -> Void
    let onGenerateNewCode: () -> Void
    let onCancelInvite: () -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var copiedRecently = false

    private var formattedCode: String? {
        code.map { InviteCodeFormatKt.formatInviteCode(code: $0) }
    }

    var body: some View {
        let copy = PlayerInviteCopy.shared
        NavigationStack {
            ScrollView {
                VStack(spacing: 16) {
                    Text(copy.inviteSheetSubtitle(playerName: playerName))
                        .shuttlType(ShuttlType.bodyMedium)
                        .foregroundStyle(Shuttl.textSecondary)
                        .multilineTextAlignment(.center)

                    if loading && formattedCode == nil {
                        ProgressView()
                            .tint(Shuttl.accent)
                            .frame(height: 180)
                    } else if let formattedCode, let rawCode = code {
                        Button {
                            UIPasteboard.general.string = formattedCode
                            copiedRecently = true
                            onCopyCode()
                        } label: {
                            VStack(spacing: 4) {
                                Text(formattedCode)
                                    .shuttlType(ShuttlType.headlineLarge, monospacedDigit: true)
                                    .tracking(2)
                                    .foregroundStyle(Shuttl.textHeading)
                                Text(copiedRecently ? copy.CODE_COPIED : copy.TAP_TO_COPY)
                                    .shuttlType(ShuttlType.labelSmall)
                                    .foregroundStyle(Shuttl.accentDark)
                            }
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 16)
                            .padding(.horizontal, 16)
                            .background(
                                Shuttl.bgSecondary,
                                in: RoundedRectangle(cornerRadius: ShuttlRadius.medium, style: .continuous)
                            )
                            .overlay(
                                RoundedRectangle(cornerRadius: ShuttlRadius.medium, style: .continuous)
                                    .stroke(Shuttl.border, lineWidth: 1)
                            )
                        }
                        .buttonStyle(.plain)

                        if let qrImage = Self.makeQrImage(payload: InviteCodeFormatKt.qrPayloadForCode(code: rawCode)) {
                            // Always on white card with quiet zone for dark-mode scanning.
                            Image(uiImage: qrImage)
                                .interpolation(.none)
                                .resizable()
                                .scaledToFit()
                                .frame(width: 188, height: 188)
                                .padding(14)
                                .background(Color.white, in: RoundedRectangle(cornerRadius: ShuttlRadius.medium, style: .continuous))
                                .accessibilityLabel("QR code for \(formattedCode)")
                        }

                        if let expiresAt {
                            let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
                            Text(copy.expiryCaption(expiresAt: expiresAt, nowEpochMs: nowMs))
                                .shuttlType(ShuttlType.bodySmall)
                                .foregroundStyle(Shuttl.textSecondary)
                        }

                        if phoneOnlyMatchCount > 0 {
                            Text(copy.phoneOnlyMatchesNote(playerName: playerName, count: Int32(phoneOnlyMatchCount)))
                                .shuttlType(ShuttlType.bodySmall)
                                .foregroundStyle(Shuttl.textSecondary)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .padding(.horizontal, 14)
                                .padding(.vertical, 10)
                                .background(
                                    Shuttl.bgSecondary,
                                    in: RoundedRectangle(cornerRadius: ShuttlRadius.small, style: .continuous)
                                )
                        }

                        ShareLink(
                            item: copy.shareInviteText(playerName: playerName, code: rawCode),
                            subject: Text(copy.SHARE_SUBJECT)
                        ) {
                            Text(copy.SHARE_INVITE)
                        }
                        .buttonStyle(PrimaryButtonStyle())
                        .disabled(loading)
                        .padding(.top, 4)

                        Button(action: onGenerateNewCode) {
                            HStack(spacing: 8) {
                                if loading { ProgressView().controlSize(.small) }
                                Text(copy.GENERATE_NEW_CODE)
                            }
                        }
                        .buttonStyle(SecondaryPillButtonStyle())
                        .disabled(loading)

                        Button(copy.ACCESS_CANCEL_INVITE, role: .destructive, action: onCancelInvite)
                            .shuttlType(ShuttlType.bodyMedium)
                            .foregroundStyle(Shuttl.error)
                            .frame(minHeight: ShuttlTarget.minimum)
                            .disabled(loading)
                    }
                }
                .padding(.horizontal, ShuttlGutter.page)
                .padding(.vertical, 16)
            }
            .background(Shuttl.bg)
            .navigationTitle(copy.inviteSheetTitle(playerName: playerName))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done") { dismiss() }
                }
            }
        }
        .presentationDetents([.large])
    }

    static func makeQrImage(payload: String) -> UIImage? {
        guard let data = payload.data(using: .utf8) else { return nil }
        let filter = CIFilter.qrCodeGenerator()
        filter.message = data
        filter.correctionLevel = "M"
        guard let output = filter.outputImage else { return nil }
        let scaled = output.transformed(by: CGAffineTransform(scaleX: 10, y: 10))
        let context = CIContext()
        guard let cgImage = context.createCGImage(scaled, from: scaled.extent) else { return nil }
        return UIImage(cgImage: cgImage)
    }
}
