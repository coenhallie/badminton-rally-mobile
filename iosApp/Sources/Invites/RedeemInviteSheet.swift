import SwiftUI
import Shared

/// In-app sheet for an already signed-in account (Settings "Join another coach"
/// or Unlinked player screen "Enter invite code") to link a coach's player
/// profile via code or QR scan. Mirrors androidApp's `RedeemInviteSheet.kt`.
struct RedeemInviteSheet: View {
    let onRedeem: (String) async -> RedeemInviteOutcome
    let onLinked: (RedeemedInvite) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var codeInput = ""
    @State private var isSubmitting = false
    @State private var error: String? = nil
    @State private var showQrScanner = false

    var body: some View {
        let copy = PlayerInviteCopy.shared
        NavigationStack {
            VStack(alignment: .leading, spacing: 16) {
                ShuttlTextField(copy.JOIN_CODE_LABEL, text: $codeInput)
                    .textInputAutocapitalization(.characters)
                    .autocorrectionDisabled()
                    .submitLabel(.go)
                    .onSubmit { submit(codeInput) }
                    .disabled(isSubmitting)
                    .onChange(of: codeInput) { _, raw in
                        let formatted = InviteCodeFormatKt.formatInviteInput(raw: raw)
                        if formatted != raw { codeInput = formatted }
                        error = nil
                    }

                if let error {
                    ErrorBanner(message: error)
                }

                Button {
                    submit(codeInput)
                } label: {
                    HStack(spacing: 8) {
                        if isSubmitting { ProgressView().tint(Shuttl.onAccent).controlSize(.small) }
                        Text(isSubmitting ? copy.JOIN_LINKING : copy.JOIN_ANOTHER_SUBMIT)
                    }
                }
                .buttonStyle(PrimaryButtonStyle())
                .disabled(codeInput.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || isSubmitting)

                Spacer(minLength: 0)
            }
            .padding(.horizontal, ShuttlGutter.page)
            .padding(.top, 16)
            .background(Shuttl.bg)
            .navigationTitle(copy.JOIN_ANOTHER_COACH)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                        .disabled(isSubmitting)
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button(copy.JOIN_SCAN_QR) { showQrScanner = true }
                        .disabled(isSubmitting)
                }
            }
            .sheet(isPresented: $showQrScanner) {
                QrCodeScannerSheet { raw in
                    guard let parsed = InviteCodeFormatKt.parseQrInvitePayload(raw: raw) else {
                        error = copy.JOIN_QR_INVALID
                        return
                    }
                    codeInput = InviteCodeFormatKt.formatInviteCode(code: parsed)
                    submit(parsed)
                }
            }
        }
        .presentationDetents([.medium, .large])
    }

    private func submit(_ raw: String) {
        guard !isSubmitting else { return }
        guard let normalized = InviteCodeFormatKt.normalizeInviteCode(raw: raw) else {
            error = PlayerInviteCopy.shared.errorMessage(kind: .invalidOrExpired)
            return
        }
        isSubmitting = true
        error = nil
        Task {
            let outcome = await onRedeem(normalized)
            isSubmitting = false
            if let redeemed = outcome.redeemed {
                onLinked(redeemed)
                dismiss()
            } else {
                error = outcome.errorMessage ?? PlayerInviteCopy.shared.errorMessage(kind: .unknown)
            }
        }
    }
}
