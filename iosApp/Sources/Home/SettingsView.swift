import SwiftUI
import Shared

/// Everything Home's overflow menu and the drawer's footer used to hold, in one
/// place: the version line used to be drawn twice. A page pushed from the
/// match list's gear, with a back button. The sign-in screen keeps its own
/// theme toggle; it is outside the signed-in stack.
struct SettingsView: View {
    let rally: RallyApp
    var accountRole: AccountRole = .coach
    var accessModel: PlayerAccessModel? = nil
    var onLinkedCoach: ((RedeemedInvite) -> Void)? = nil

    @State private var themeMode: ThemeMode
    /// Registered here rather than on HomeView: Labels is pushed from this
    /// page, and a destination belongs on the view that is on screen when it
    /// is pushed, not on one under it.
    @State private var showLabels = false
    @State private var showJoinCoach = false
    @State private var confirmSignOut = false
    @State private var isSigningOut = false
    @State private var signOutError: String? = nil

    init(
        rally: RallyApp,
        accountRole: AccountRole = .coach,
        accessModel: PlayerAccessModel? = nil,
        onLinkedCoach: ((RedeemedInvite) -> Void)? = nil
    ) {
        self.rally = rally
        self.accountRole = accountRole
        self.accessModel = accessModel
        self.onLinkedCoach = onLinkedCoach
        // Seeded from the stored preference, not a fixed .light: the `.task`
        // below only delivers it after the first frame, so a coach in dark
        // theme saw the switch start off and flip on.
        _themeMode = State(initialValue: rally.themePrefs.mode.value)
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 12) {
                card {
                    if accountRole == .coach {
                        Button { showLabels = true } label: {
                            HStack {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(HomeCopy.shared.SETTINGS_LABELS)
                                        .shuttlType(ShuttlType.bodyLarge)
                                        .foregroundStyle(Shuttl.textHeading)
                                    Text(HomeCopy.shared.SETTINGS_LABELS_DETAIL)
                                        .shuttlType(ShuttlType.bodySmall)
                                        .foregroundStyle(Shuttl.textSecondary)
                                }
                                Spacer()
                                Image(systemName: "chevron.right")
                                    .shuttlType(ShuttlType.bodySmall)
                                    .foregroundStyle(Shuttl.textSecondary)
                                    .accessibilityHidden(true)
                            }
                            .settingsRow()
                        }
                        .buttonStyle(.plain)
                        .accessibilityElement(children: .combine)
                        Divider().overlay(Shuttl.border)
                    }
                    if accessModel != nil {
                        Button { showJoinCoach = true } label: {
                            HStack {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(PlayerInviteCopy.shared.JOIN_ANOTHER_COACH)
                                        .shuttlType(ShuttlType.bodyLarge)
                                        .foregroundStyle(Shuttl.textHeading)
                                    Text(PlayerInviteCopy.shared.JOIN_ANOTHER_COACH_DETAIL)
                                        .shuttlType(ShuttlType.bodySmall)
                                        .foregroundStyle(Shuttl.textSecondary)
                                }
                                Spacer()
                                Image(systemName: "chevron.right")
                                    .shuttlType(ShuttlType.bodySmall)
                                    .foregroundStyle(Shuttl.textSecondary)
                                    .accessibilityHidden(true)
                            }
                            .settingsRow()
                        }
                        .buttonStyle(.plain)
                        .accessibilityElement(children: .combine)
                        Divider().overlay(Shuttl.border)
                    }
                    Toggle(isOn: Binding(
                        get: { themeMode == .dark },
                        set: { _ in rally.themePrefs.toggle() }
                    )) {
                        Text(HomeCopy.shared.SETTINGS_DARK_THEME)
                            .shuttlType(ShuttlType.bodyLarge)
                            .foregroundStyle(Shuttl.textHeading)
                    }
                    .tint(Shuttl.accent)
                    .settingsRow()
                }
                card {
                    if let email = rally.auth.currentUserEmail() {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(HomeCopy.shared.SETTINGS_SIGNED_IN_AS)
                                .shuttlType(ShuttlType.bodySmall)
                                .foregroundStyle(Shuttl.textSecondary)
                            Text(email)
                                .shuttlType(ShuttlType.bodyLarge)
                                .foregroundStyle(Shuttl.textHeading)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .settingsRow()
                        Divider().overlay(Shuttl.border)
                    }
                    Button {
                        signOutError = nil
                        confirmSignOut = true
                    } label: {
                        Text(HomeCopy.shared.SETTINGS_SIGN_OUT)
                            .shuttlType(ShuttlType.bodyLarge)
                            .foregroundStyle(Shuttl.error)
                            .opacity(isSigningOut ? 0.4 : 1)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .settingsRow()
                    }
                    .buttonStyle(.plain)
                    .disabled(isSigningOut)
                }
                if let signOutError {
                    ErrorBanner(message: signOutError)
                }
                Text(versionLabel())
                    .shuttlType(ShuttlType.bodySmall)
                    .foregroundStyle(Shuttl.textTertiary)
                    .padding(.top, 6)
            }
            // The page gutter, so each card's edge sits in the column the
            // other pages and Android lay their content in.
            .padding(.horizontal, ShuttlGutter.page)
            .padding(.top, 8)
            .padding(.bottom, 24)
        }
        .background(Shuttl.bg)
        .navigationTitle(MatchPageCopy.shared.SETTINGS)
        .navigationBarTitleDisplayMode(.inline)
        .shuttlNavigationBarBackground()
        .toolbar {
            if accountRole == .coach {
                // The chrome indicator, on every bar androidApp puts it on. See
                // `BackgroundWorkAction`.
                ToolbarItem(placement: .topBarTrailing) { BackgroundWorkAction() }
            }
        }
        .navigationDestination(isPresented: $showLabels) {
            LabelsView(rally: rally)
        }
        .sheet(isPresented: $showJoinCoach) {
            if let accessModel {
                RedeemInviteSheet(
                    onRedeem: { await accessModel.redeemExistingCode($0) },
                    onLinked: { redeemed in onLinkedCoach?(redeemed) }
                )
            }
        }
        .alert("Sign out?", isPresented: $confirmSignOut) {
            Button("Cancel", role: .cancel) {}
            Button(HomeCopy.shared.SETTINGS_SIGN_OUT, role: .destructive) {
                signOut()
            }
        } message: {
            Text("You'll need to sign in again to access your cloud matches and labels.")
        }
        .task {
            for await mode in rally.themePrefs.mode { themeMode = mode }
        }
    }

    private func signOut() {
        guard !isSigningOut else { return }
        isSigningOut = true
        signOutError = nil
        Task {
            do {
                if let message = try await SwiftInteropKt.signOutOrMessage(rally.auth) {
                    signOutError = message
                }
            } catch {
                signOutError = "Sign-out failed."
            }
            isSigningOut = false
        }
    }

    /// bgSecondary on the page's bg: never bgInput on bgTertiary, one colour in light.
    private func card<Content: View>(@ViewBuilder _ content: () -> Content) -> some View {
        VStack(spacing: 0) { content() }
            .background(Shuttl.bgSecondary, in: RoundedRectangle(cornerRadius: ShuttlRadius.large, style: .continuous))
    }
}

private extension View {
    func settingsRow() -> some View {
        padding(.horizontal, 18).frame(minHeight: 56).contentShape(Rectangle())
    }
}
