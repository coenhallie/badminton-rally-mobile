import SwiftUI
import Shared

/// The Matches tab's round "Add match", bottom right, above the tab bar. Fixed
/// size: resizing on scroll needs a scroll-position API iOS 17 does not have
/// cleanly. Opens the same three-option sheet the Home pill used to.
struct AddMatchButton: View {
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: "plus")
                .shuttlType(ShuttlType.headlineMedium)
                .foregroundStyle(Shuttl.onAccent)
                .frame(width: 56, height: 56)
                .background(Shuttl.accent, in: Circle())
                .shadow(color: .black.opacity(0.35), radius: 12, y: 6)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(HomeCopy.shared.ADD_MATCH)
    }
}
