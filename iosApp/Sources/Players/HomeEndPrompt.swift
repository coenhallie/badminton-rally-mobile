import SwiftUI
import Shared

/// "Which end was {home} on?", asked once when a video joins a scored match
/// that has a profile on either side, before court marking (section 4.4a of
/// the player profiles design). `title` is the shared `homeEndTitle`, read by
/// the caller from the players as they are now, so a list still loading names
/// Home once it lands.
///
/// A sheet sized to its content, on the dialog fill, holding the app's
/// buttons: Near and Far, the two equal answers, side by side in the primary
/// style, then Skip, the way past, under them in the secondary style. Skip
/// leaves the end unsaid, for the details editor to set later. `onAnswer`
/// hands back the side nearest the camera (Near is Home, Far is Away) or nil
/// for Skip, and the sheet closes itself. A swipe down is Skip too: the caller
/// goes on to court marking from the sheet's dismissal, whichever way it went.
///
/// Mirrors androidApp's HomeEndDialog.kt.
struct HomeEndPrompt: View {
    let title: String
    let onAnswer: (Side?) -> Void

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        let copy = PlayersCopy.shared
        VStack(alignment: .leading, spacing: 0) {
            Text(title)
                .shuttlType(ShuttlType.headlineMedium)
                .foregroundStyle(Shuttl.textHeading)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityAddTraits(.isHeader)
            Text(copy.ATTACH_SUBTITLE)
                .shuttlType(ShuttlType.bodyMedium)
                .foregroundStyle(Shuttl.textSecondary)
                .padding(.top, 8)
            HStack(spacing: 12) {
                Button(copy.NEAR) { answer(.home) }
                    .buttonStyle(PrimaryButtonStyle())
                Button(copy.FAR) { answer(.away) }
                    .buttonStyle(PrimaryButtonStyle())
            }
            .padding(.top, 24)
            Button(copy.ATTACH_SKIP) { answer(nil) }
                .buttonStyle(SecondaryPillButtonStyle())
                .padding(.top, 12)
        }
        .padding(.horizontal, 20)
        .padding(.top, 24)
        .padding(.bottom, 16)
        .sizedToContentSheet()
        .presentationDragIndicator(.visible)
        // See `PlayerNameSheet`: the sheets' usual fill is the pill track's.
        .presentationBackground(Shuttl.bgDialog)
    }

    private func answer(_ nearSide: Side?) {
        onAnswer(nearSide)
        dismiss()
    }
}
