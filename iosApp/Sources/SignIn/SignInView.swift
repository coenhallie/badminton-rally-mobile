import SwiftUI
import Shared

/// Laid out the way the list it hands over to is: the list's own header
/// (`HomeHeader`) with the wordmark in the title's slot and the theme switch
/// where the Settings gear sits, then the page's heading and the form at the
/// page gutter, top-anchored. No card around the form: the app uses cards to
/// group rows (Settings), not to frame a page's only content. Mirrors
/// androidApp's SignInScreen.
///
/// The theme switch stays here, outside Settings, because a signed-out coach
/// cannot reach Settings.
struct SignInView: View {
    let rally: RallyApp
    @State private var email = ""
    @State private var password = ""
    @State private var isSubmitting = false
    @State private var error: String? = nil
    @State private var themeMode: ThemeMode
    @FocusState private var focus: Field?

    private enum Field { case email, password }

    init(rally: RallyApp) {
        self.rally = rally
        // Seeded from the stored preference, as SettingsView's is: the
        // `.task` below only delivers it after the first frame, so in dark
        // theme the button first drew the moon and "Switch to dark mode".
        _themeMode = State(initialValue: rally.themePrefs.mode.value)
    }

    var body: some View {
        VStack(spacing: 0) {
            HomeHeader(SignInCopy.shared.WORDMARK, titleRole: ShuttlType.wordmark) {
                themeButton
            }
            ScrollView {
                form
                    // Capped, then the gutter outside the cap, so on a phone
                    // the heading lines up with the wordmark at exactly the
                    // gutter and only a wider screen centres the column.
                    .frame(maxWidth: 400, alignment: .leading)
                    .padding(.horizontal, ShuttlGutter.page)
                    .padding(.top, 8)
                    .padding(.bottom, 24)
                    .frame(maxWidth: .infinity)
            }
            .scrollDismissesKeyboard(.interactively)
        }
        .background(Shuttl.bg)
        .task {
            for await mode in rally.themePrefs.mode {
                themeMode = mode
            }
        }
        // Editing either field clears a stale error, as Android's view model
        // does: the message is about what was sent, not what is typed now.
        .onChange(of: email) { error = nil }
        .onChange(of: password) { error = nil }
        // A failed sign-in changes nothing else VoiceOver would land on. High
        // priority, because the button is relabelled ("Signing in…" back to
        // "Sign in") in the same update, and VoiceOver reading the focused
        // button's new label would otherwise cut a default announcement off.
        .onChange(of: error) { _, message in
            guard let message else { return }
            var announcement = AttributedString(message)
            announcement.accessibilitySpeechAnnouncementPriority = .high
            AccessibilityNotification.Announcement(announcement).post()
        }
    }

    private var form: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(SignInCopy.shared.TITLE)
                .shuttlType(ShuttlType.headlineLarge)
                .foregroundStyle(Shuttl.textHeading)

            ShuttlTextField(SignInCopy.shared.EMAIL, text: $email)
                .keyboardType(.emailAddress)
                .textContentType(.username)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .submitLabel(.next)
                .focused($focus, equals: .email)
                .onSubmit { focus = .password }
                .disabled(isSubmitting)
                .padding(.top, 24)

            ShuttlTextField(SignInCopy.shared.PASSWORD, text: $password, secure: true)
                .textContentType(.password)
                .submitLabel(.go)
                .focused($focus, equals: .password)
                .onSubmit(submit)
                .disabled(isSubmitting)
                .padding(.top, 16)

            // Above the button, beside the fields it is about, so it is still
            // on screen with the keyboard up.
            if let error {
                ErrorBanner(message: error)
                    .padding(.top, 16)
            }

            Button(action: submit) {
                HStack(spacing: 8) {
                    if isSubmitting {
                        ProgressView()
                            .tint(Shuttl.onAccent)
                            .controlSize(.small)
                    }
                    Text(isSubmitting ? SignInCopy.shared.SUBMITTING : SignInCopy.shared.SUBMIT)
                }
            }
            .buttonStyle(PrimaryButtonStyle())
            .disabled(isSubmitting)
            .padding(.top, 24)

            Group {
                Text(SignInCopy.shared.FOOTNOTE)
                    .padding(.top, 24)
                Text(versionLabel())
                    .padding(.top, 8)
            }
            .shuttlType(ShuttlType.bodySmall)
            .foregroundStyle(Shuttl.textTertiary)
            .multilineTextAlignment(.center)
            .frame(maxWidth: .infinity)
        }
    }

    /// Drawn the way the list's Settings gear is (MatchesScreen): a bare
    /// glyph at titleMedium in a 44pt target. It used to sit in a bordered
    /// 36pt square, under the minimum target and a box nothing else draws.
    /// The glyph is the theme a tap switches to: a moon while light.
    private var themeButton: some View {
        Button {
            rally.themePrefs.toggle()
        } label: {
            Image(systemName: themeMode == .dark ? "sun.max" : "moon")
                .shuttlType(ShuttlType.titleMedium)
                .foregroundStyle(Shuttl.textSecondary)
                .frame(width: ShuttlTarget.minimum, height: ShuttlTarget.minimum)
                .contentShape(Rectangle())
        }
        .buttonStyle(.borderless)
        .accessibilityLabel(SignInCopy.shared.themeToggle(current: themeMode))
    }

    private func submit() {
        guard !isSubmitting else { return }
        // Go drops the keyboard; the button does the same, so the banner that
        // may follow is never left under it.
        focus = nil
        isSubmitting = true
        error = nil
        // Trimmed, as Android's view model does: a trailing space from
        // autofill or the keyboard would otherwise fail on this phone only.
        let address = email.trimmingCharacters(in: .whitespacesAndNewlines)
        Task {
            do {
                let message = try await SwiftInteropKt.signInEmailOrMessage(
                    rally.auth, email: address, password: password
                )
                if let message { error = message }
            } catch {
                // Only a cancellation reaches Swift as a throw; the call never
                // got an answer, which is what the shared message says.
                self.error = AuthErrorMessagesKt.SIGN_IN_UNREACHABLE_MESSAGE
            }
            isSubmitting = false
        }
    }
}
