import SwiftUI
import Shared

/// All matches: the match list, every match whether a player holds it or not,
/// with its filters, sort and "Shared with me". Pushed from the Players
/// screen, which holds the gear; this is the list under a back bar with the
/// background-work indicator, and the same Add button Players and a player's
/// page carry: its empty state says to score, record or import, and a page
/// that says so has to offer the way to. It draws the root toast too, as
/// Players does, so a finished run or a failed refresh is said here.
/// Mirrors androidApp's AllMatchesScreen.kt.
struct MatchesScreen: View {
    let rally: RallyApp
    let analyze: AnalyzeCoordinator
    let intake: LocalVideoIntake
    let model: ClipListModel
    let playersModel: PlayersModel
    let heroPaused: Bool
    let onMatchTap: (MatchRoute) -> Void
    let onCourtMarking: (CourtMarkingRoute) -> Void
    let onOverlayChange: (Bool) -> Void
    /// The Add sheet, for a match that pre-fills nobody, as on Players.
    let onAdd: () -> Void
    /// What the root says. Owned by HomeView, which hears the runs land.
    @Binding var toast: ToastMessage?

    /// The list's filter and order. See `MatchesList.filter`.
    @State private var filter: MatchListFilter = .all
    @State private var sort: MatchListSort = .newest

    var body: some View {
        MatchesList(
            rally: rally, analyze: analyze, intake: intake, model: model, playersModel: playersModel,
            onMatchTap: onMatchTap, onCourtMarking: onCourtMarking,
            heroPaused: heroPaused, onOverlayChange: onOverlayChange,
            playerId: nil,
            // Clear of the Add button, as a player's Matches tab is.
            bottomInset: 88,
            filter: $filter,
            sort: $sort
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
        .overlay(alignment: .bottomTrailing) {
            AddMatchButton(action: onAdd)
                .padding(.trailing, 16)
                .padding(.bottom, 16)
        }
        // Above the Add button, as on Players. HomeView ends it when the
        // coach leaves the list screens; see `RootToasts.listsLeft`.
        .shuttlToast($toast, bottomInset: 88, endsWhenScreenGoes: false)
    }
}
