import SwiftUI
import Shared

/// The landing tab: its title, record and import errors, and the match list.
/// The errors show here because Add, which starts a record or import, is only
/// on this tab. The list is the one the drawer used to hold, full width now.
struct MatchesTabView: View {
    let rally: RallyApp
    let analyze: AnalyzeCoordinator
    let intake: LocalVideoIntake
    let model: ClipListModel
    let heroPaused: Bool
    let onMatchTap: (MatchRoute) -> Void
    let onCourtMarking: (CourtMarkingRoute) -> Void
    let onOverlayChange: (Bool) -> Void
    let onAdd: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            TabHeader(HomeCopy.shared.TAB_MATCHES) { BackgroundWorkAction() }
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
    }
}
