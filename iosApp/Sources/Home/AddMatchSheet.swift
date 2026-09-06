import SwiftUI

/// The three ways a match starts, behind Home's one "Add new match" button.
///
/// The mock shows a single button where the list had a three item menu. The
/// actions are unchanged, so nothing a coach could do before is gone; only the
/// way in is. Presented as a sheet rather than a menu because a 60pt primary
/// button implies a destination, not a popover.
struct AddMatchSheet: View {
    let onNewMatch: () -> Void
    let onRecord: () -> Void
    let onImport: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            row("New match", systemImage: "plus.circle", action: onNewMatch)
            Divider().overlay(Shuttl.border)
            row("Record video", systemImage: "video", action: onRecord)
            Divider().overlay(Shuttl.border)
            row("Import video", systemImage: "square.and.arrow.down", action: onImport)
        }
        .padding(.vertical, 8)
        .frame(maxHeight: .infinity, alignment: .top)
        .background(Shuttl.bgSecondary)
        .presentationDetents([.height(220)])
        .presentationDragIndicator(.visible)
    }

    private func row(_ title: String, systemImage: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 14) {
                Image(systemName: systemImage)
                    .foregroundStyle(Shuttl.accentDark)
                    .frame(width: 24)
                Text(title)
                    .shuttlType(ShuttlType.bodyLarge)
                    .foregroundStyle(Shuttl.text)
                Spacer()
            }
            .padding(.horizontal, 24)
            .padding(.vertical, 16)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}
