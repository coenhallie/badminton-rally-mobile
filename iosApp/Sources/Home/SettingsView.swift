import SwiftUI
import Shared

/// Everything Home's overflow menu and the drawer's footer used to hold, in one
/// place: the version line used to be drawn twice. The sign-in screen keeps its
/// own theme toggle; it is outside the tabs.
struct SettingsView: View {
    let rally: RallyApp
    let onLabels: () -> Void

    @State private var themeMode: ThemeMode

    init(rally: RallyApp, onLabels: @escaping () -> Void) {
        self.rally = rally
        self.onLabels = onLabels
        // Seeded from the stored preference, not a fixed .light: the `.task`
        // below only delivers it after the first frame, so a coach in dark
        // theme saw the switch start off and flip on.
        _themeMode = State(initialValue: rally.themePrefs.mode.value)
    }

    var body: some View {
        VStack(spacing: 0) {
            // The indicator on every tab's header, as on every other screen's bar.
            TabHeader(HomeCopy.shared.TAB_SETTINGS) { BackgroundWorkAction() }
            ScrollView {
                VStack(spacing: 12) {
                    card {
                        Button(action: onLabels) {
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
                            }
                            .settingsRow()
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel(HomeCopy.shared.SETTINGS_LABELS)
                        Divider().overlay(Shuttl.border)
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
                            Task { _ = try? await SwiftInteropKt.signOutOrMessage(rally.auth) }
                        } label: {
                            Text(HomeCopy.shared.SETTINGS_SIGN_OUT)
                                .shuttlType(ShuttlType.bodyLarge)
                                .foregroundStyle(Shuttl.error)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .settingsRow()
                        }
                        .buttonStyle(.plain)
                    }
                    Text(versionLabel())
                        .shuttlType(ShuttlType.bodySmall)
                        .foregroundStyle(Shuttl.textTertiary)
                        .padding(.top, 6)
                }
                // The page gutter, so each card's edge sits under the tab's
                // title as on Analytics and on Android.
                .padding(.horizontal, ShuttlGutter.page)
                .padding(.top, 8)
                .padding(.bottom, 24)
            }
        }
        .background(Shuttl.bg)
        .task {
            for await mode in rally.themePrefs.mode { themeMode = mode }
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
