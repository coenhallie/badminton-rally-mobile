import SwiftUI
import Shared

/// The root screen: its title with the background-work indicator and the
/// Settings gear, record and import errors, the match list, the Add button,
/// and the toast a finished run leaves. Every other screen is pushed over it.
/// The errors show here because Add, which starts a record or import, is only
/// on this screen. Mirrors androidApp's HomeScreen.kt.
struct MatchesScreen: View {
    let rally: RallyApp
    let analyze: AnalyzeCoordinator
    let intake: LocalVideoIntake
    let model: ClipListModel
    let heroPaused: Bool
    let onMatchTap: (MatchRoute) -> Void
    let onCourtMarking: (CourtMarkingRoute) -> Void
    let onOverlayChange: (Bool) -> Void
    let onAdd: () -> Void
    let onSettings: () -> Void
    /// What a finished run says. Owned by HomeView, which hears the runs land.
    @Binding var toast: ToastMessage?

    var body: some View {
        VStack(spacing: 0) {
            HomeHeader(HomeCopy.shared.TAB_MATCHES) {
                BackgroundWorkAction()
                Button(action: onSettings) {
                    Image(systemName: "gearshape")
                        .shuttlType(ShuttlType.titleMedium)
                        .foregroundStyle(Shuttl.textSecondary)
                        .frame(width: 44, height: 44)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.borderless)
                .accessibilityLabel(MatchPageCopy.shared.SETTINGS)
            }
            if let error = intake.error {
                ErrorBanner(message: error)
            }
            MatchesList(
                rally: rally, analyze: analyze, intake: intake, model: model,
                onMatchTap: onMatchTap, onCourtMarking: onCourtMarking,
                heroPaused: heroPaused, onOverlayChange: onOverlayChange
            )
        }
        .background(Shuttl.bg)
        .overlay(alignment: .bottomTrailing) {
            AddMatchButton(action: onAdd)
                .padding(.trailing, 16)
                .padding(.bottom, 16)
        }
        // Above the Add button rather than over it: the 56pt button, its 16pt
        // margin, and 16pt of air.
        .shuttlToast($toast, bottomInset: 88)
    }
}
