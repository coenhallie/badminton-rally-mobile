import SwiftUI
import Shared

/// All matches: the match list, every match whether a player holds it or not,
/// with its filters, sort and "Shared with me". Pushed from the Players
/// screen, which holds the Add button and the gear; this is the list under a
/// back bar with the background-work indicator. It draws the root toast too,
/// as Players does, so a finished run or a failed refresh is said here.
/// Mirrors androidApp's AllMatchesScreen.kt.
struct MatchesScreen: View {
    let rally: RallyApp
    let analyze: AnalyzeCoordinator
    let intake: LocalVideoIntake
    let model: ClipListModel
    let heroPaused: Bool
    let onMatchTap: (MatchRoute) -> Void
    let onCourtMarking: (CourtMarkingRoute) -> Void
    let onOverlayChange: (Bool) -> Void
    /// What the root says. Owned by HomeView, which hears the runs land.
    @Binding var toast: ToastMessage?

    var body: some View {
        MatchesList(
            rally: rally, analyze: analyze, intake: intake, model: model,
            onMatchTap: onMatchTap, onCourtMarking: onCourtMarking,
            heroPaused: heroPaused, onOverlayChange: onOverlayChange
        )
        .background(Shuttl.bg)
        .navigationTitle(PlayersCopy.shared.ALL_MATCHES)
        .navigationBarTitleDisplayMode(.inline)
        .shuttlNavigationBarBackground()
        .toolbar {
            // The chrome indicator, on every bar androidApp puts it on. See
            // `BackgroundWorkAction`.
            ToolbarItem(placement: .topBarTrailing) { BackgroundWorkAction() }
        }
        // No Add button here to stand clear of: the toast sits the button's
        // own 16pt margin off the bottom, where Players' Add button would be.
        // HomeView ends it when the coach leaves the list screens; see
        // `RootToasts.listsLeft`.
        .shuttlToast($toast, bottomInset: 16, endsWhenScreenGoes: false)
    }
}
