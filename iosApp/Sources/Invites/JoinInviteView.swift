import SwiftUI
import Shared

/// Signed-out invite onboarding: enter or scan a coach's 8-character invite
/// code, confirm the verified profile preview, and either create a new player
/// account or sign into an existing account to link it.
/// Mirrors androidApp's `JoinInviteScreen.kt` + `JoinInviteViewModel.kt`.
struct JoinInviteView: View {
    enum AuthMode { case createAccount, signIn }

    let rally: RallyApp
    /// Held true while "Sign in & link" signs into an existing account and then
    /// redeems the invite so `RootView` waits to transition until linking finishes.
    @Binding var isLinkingInvite: Bool

    @Environment(\.dismiss) private var dismiss

    @State private var codeInput = ""
    @State private var preview: InvitePreview? = nil
    @State private var verifiedCode: String? = nil
    @State private var authMode: AuthMode = .createAccount
    @State private var email = ""
    @State private var password = ""
    @State private var isCheckingCode = false
    @State private var isSubmitting = false
    @State private var error: String? = nil
    @State private var offerSignInSwitch = false
    @State private var showQrScanner = false

    @FocusState private var focus: Field?
    private enum Field { case code, email, password }

    var body: some View {
        let copy = PlayerInviteCopy.shared
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    ShuttlTextField(copy.JOIN_CODE_LABEL, text: $codeInput)
                        .textInputAutocapitalization(.characters)
                        .autocorrectionDisabled()
                        .submitLabel(.go)
                        .focused($focus, equals: .code)
                        .onSubmit(verifyCode)
                        .disabled(isCheckingCode || isSubmitting)
                        .onChange(of: codeInput) { _, raw in
                            let formatted = InviteCodeFormatKt.formatInviteInput(raw: raw)
                            if formatted != raw { codeInput = formatted }
                            let normalized = InviteCodeFormatKt.normalizeInviteCode(raw: formatted)
                            if let verifiedCode, normalized != verifiedCode {
                                preview = nil
                                self.verifiedCode = nil
                            }
                            error = nil
                            offerSignInSwitch = false
                        }

                    if let preview {
                        verifiedPreviewCard(preview)
                            .padding(.top, 16)

                        ShuttlTextField(copy.JOIN_EMAIL_LABEL, text: $email)
                            .keyboardType(.emailAddress)
                            .textContentType(.username)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                            .submitLabel(.next)
                            .focused($focus, equals: .email)
                            .onSubmit { focus = .password }
                            .disabled(isSubmitting)
                            .padding(.top, 20)

                        ShuttlTextField(
                            authMode == .createAccount ? copy.JOIN_PASSWORD_LABEL : copy.JOIN_SIGN_IN_PASSWORD_LABEL,
                            text: $password,
                            secure: true
                        )
                        .textContentType(authMode == .createAccount ? .newPassword : .password)
                        .submitLabel(.go)
                        .focused($focus, equals: .password)
                        .onSubmit(submit)
                        .disabled(isSubmitting)
                        .padding(.top, 14)

                        errorBlock

                        Button(action: submit) {
                            HStack(spacing: 8) {
                                if isSubmitting {
                                    ProgressView().tint(Shuttl.onAccent).controlSize(.small)
                                }
                                Text(submitLabel)
                            }
                        }
                        .buttonStyle(PrimaryButtonStyle())
                        .disabled(isSubmitting)
                        .padding(.top, 20)

                        Button {
                            authMode = authMode == .createAccount ? .signIn : .createAccount
                            error = nil
                            offerSignInSwitch = false
                        } label: {
                            Text(
                                authMode == .createAccount
                                    ? copy.JOIN_SWITCH_TO_SIGN_IN
                                    : copy.JOIN_SWITCH_TO_CREATE
                            )
                            .shuttlType(ShuttlType.bodyMedium)
                            .foregroundStyle(Shuttl.accentDark)
                            .frame(maxWidth: .infinity)
                            .frame(minHeight: ShuttlTarget.minimum)
                        }
                        .buttonStyle(.plain)
                        .disabled(isSubmitting)
                        .padding(.top, 8)
                    } else {
                        errorBlock

                        Button(action: verifyCode) {
                            HStack(spacing: 8) {
                                if isCheckingCode {
                                    ProgressView().tint(Shuttl.onAccent).controlSize(.small)
                                }
                                Text(isCheckingCode ? copy.JOIN_VERIFYING : copy.JOIN_VERIFY)
                            }
                        }
                        .buttonStyle(PrimaryButtonStyle())
                        .disabled(codeInput.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || isCheckingCode)
                        .padding(.top, 20)
                    }
                }
                .frame(maxWidth: 400, alignment: .leading)
                .padding(.horizontal, ShuttlGutter.page)
                .padding(.top, 12)
                .padding(.bottom, 24)
                .frame(maxWidth: .infinity)
            }
            .scrollDismissesKeyboard(.interactively)
            .background(Shuttl.bg)
            .navigationTitle(copy.JOIN_TITLE)
            .navigationBarTitleDisplayMode(.inline)
            .shuttlNavigationBarBackground()
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") {
                        isLinkingInvite = false
                        dismiss()
                    }
                    .disabled(isSubmitting)
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button(copy.JOIN_SCAN_QR) {
                        focus = nil
                        showQrScanner = true
                    }
                    .disabled(isCheckingCode || isSubmitting)
                }
            }
            .sheet(isPresented: $showQrScanner) {
                QrCodeScannerSheet { raw in
                    handleQrScan(raw)
                }
            }
            .onChange(of: email) { error = nil; offerSignInSwitch = false }
            .onChange(of: password) { error = nil; offerSignInSwitch = false }
            .onDisappear { isLinkingInvite = false }
        }
    }

    private var submitLabel: String {
        let copy = PlayerInviteCopy.shared
        switch authMode {
        case .createAccount: return isSubmitting ? copy.JOIN_CREATING : copy.JOIN_CREATE_SUBMIT
        case .signIn:        return isSubmitting ? copy.JOIN_LINKING : copy.JOIN_LINK_SUBMIT
        }
    }

    @ViewBuilder
    private var errorBlock: some View {
        if let error {
            VStack(spacing: 10) {
                ErrorBanner(message: error)
                if offerSignInSwitch {
                    Button(PlayerInviteCopy.shared.JOIN_SWITCH_TO_SIGN_IN) {
                        authMode = .signIn
                        self.error = nil
                        offerSignInSwitch = false
                    }
                    .buttonStyle(CompactPillButtonStyle(secondary: true, fullTarget: true))
                }
            }
            .padding(.top, 14)
        }
    }

    private func verifiedPreviewCard(_ preview: InvitePreview) -> some View {
        let copy = PlayerInviteCopy.shared
        return HStack(spacing: 12) {
            PlayerAvatar(name: preview.playerName, size: PlayerAvatar.rowSize)
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 8) {
                    Text(preview.playerName)
                        .shuttlType(ShuttlType.titleMedium)
                        .foregroundStyle(Shuttl.textHeading)
                    Text(copy.JOIN_VERIFIED_BADGE)
                        .shuttlType(ShuttlType.labelSmall)
                        .foregroundStyle(Shuttl.accentDark)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 2)
                        .background(Shuttl.accent.opacity(0.16), in: Capsule())
                }
                if let hand = PlayersCopy.shared.handLine(racketArm: preview.racketArm) {
                    Text(hand)
                        .shuttlType(ShuttlType.bodySmall)
                        .foregroundStyle(Shuttl.textSecondary)
                }
                Text(copy.invitedBy(coachEmail: preview.coachEmail))
                    .shuttlType(ShuttlType.bodySmall)
                    .foregroundStyle(Shuttl.textSecondary)
            }
            Spacer(minLength: 0)
        }
        .padding(16)
        .background(
            Shuttl.bgSecondary,
            in: RoundedRectangle(cornerRadius: ShuttlRadius.large, style: .continuous)
        )
        .overlay(
            RoundedRectangle(cornerRadius: ShuttlRadius.large, style: .continuous)
                .stroke(Shuttl.border, lineWidth: 1)
        )
    }

    private func handleQrScan(_ raw: String) {
        guard let parsed = InviteCodeFormatKt.parseQrInvitePayload(raw: raw) else {
            error = PlayerInviteCopy.shared.JOIN_QR_INVALID
            offerSignInSwitch = false
            return
        }
        codeInput = InviteCodeFormatKt.formatInviteCode(code: parsed)
        preview = nil
        verifiedCode = nil
        verifyCode()
    }

    private func verifyCode() {
        guard !isCheckingCode, !isSubmitting else { return }
        focus = nil
        guard let normalized = InviteCodeFormatKt.normalizeInviteCode(raw: codeInput) else {
            error = PlayerInviteCopy.shared.errorMessage(kind: .invalidOrExpired)
            return
        }
        isCheckingCode = true
        error = nil
        offerSignInSwitch = false
        Task {
            defer { isCheckingCode = false }
            do {
                let outcome = try await SwiftInteropKt.previewInviteForSwift(rally.playerAccess, code: normalized)
                if let found = outcome.preview {
                    codeInput = InviteCodeFormatKt.formatInviteCode(code: normalized)
                    preview = found
                    verifiedCode = normalized
                } else {
                    preview = nil
                    verifiedCode = nil
                    error = outcome.errorMessage ?? PlayerInviteCopy.shared.errorMessage(kind: .invalidOrExpired)
                }
            } catch {
                self.error = PlayerInviteCopy.shared.errorMessage(kind: .network)
            }
        }
    }

    private func submit() {
        guard let code = verifiedCode, !isSubmitting, !isCheckingCode else { return }
        focus = nil
        let trimmedEmail = email.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedEmail.isEmpty, !password.isEmpty else {
            error = "Enter your email and password."
            return
        }
        isSubmitting = true
        error = nil
        offerSignInSwitch = false
        isLinkingInvite = true

        Task {
            switch authMode {
            case .createAccount:
                do {
                    let outcome = try await SwiftInteropKt.redeemInviteNewAccountForSwift(
                        rally.playerAccess, code: code, email: trimmedEmail, password: password
                    )
                    if outcome.redeemed != nil {
                        isSubmitting = false
                        isLinkingInvite = false
                        dismiss()
                    } else {
                        isSubmitting = false
                        isLinkingInvite = false
                        error = outcome.errorMessage ?? PlayerInviteCopy.shared.errorMessage(kind: .unknown)
                        offerSignInSwitch = outcome.errorKind == .emailExists
                    }
                } catch {
                    isSubmitting = false
                    isLinkingInvite = false
                    self.error = PlayerInviteCopy.shared.errorMessage(kind: .network)
                }

            case .signIn:
                let currentEmail = rally.auth.currentUserEmail()
                let needsSignIn = rally.auth.currentUserId() == nil
                    || currentEmail?.caseInsensitiveCompare(trimmedEmail) != .orderedSame
                if needsSignIn {
                    do {
                        if let signInErr = try await SwiftInteropKt.signInEmailOrMessage(
                            rally.auth, email: trimmedEmail, password: password
                        ) {
                            isSubmitting = false
                            isLinkingInvite = false
                            error = signInErr
                            return
                        }
                    } catch {
                        isSubmitting = false
                        isLinkingInvite = false
                        self.error = AuthErrorMessagesKt.SIGN_IN_UNREACHABLE_MESSAGE
                        return
                    }
                }
                do {
                    let outcome = try await SwiftInteropKt.redeemInviteExistingAccountForSwift(
                        rally.playerAccess, code: code
                    )
                    if outcome.redeemed != nil {
                        isSubmitting = false
                        isLinkingInvite = false
                        dismiss()
                    } else {
                        isSubmitting = false
                        error = outcome.errorMessage ?? PlayerInviteCopy.shared.errorMessage(kind: .unknown)
                        // Keep isLinkingInvite true while the error stays visible so
                        // RootView doesn't yank the sheet away before the user reads it.
                    }
                } catch {
                    isSubmitting = false
                    self.error = PlayerInviteCopy.shared.errorMessage(kind: .network)
                }
            }
        }
    }
}
