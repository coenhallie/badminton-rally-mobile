import SwiftUI
import Shared

/// The three ways a match starts, behind the match list's one "Add match"
/// button.
///
/// A single button replaced what was once a three item menu. The actions are
/// unchanged, so nothing a coach could do before is gone; only the way in is.
/// Presented as a sheet rather than a menu because a primary button implies a
/// destination, not a popover.
struct AddMatchSheet: View {
    let onNewMatch: () -> Void
    let onRecord: () -> Void
    let onImport: () -> Void

    /// The rows' measured height, the sheet's detent. Seeded with the three
    /// rows at the default text size so the first frame is not a sliver.
    @State private var contentHeight: CGFloat = 260

    var body: some View {
        ScrollView {
            VStack(spacing: 0) {
                row(HomeCopy.shared.ADD_NEW_MATCH, detail: HomeCopy.shared.ADD_NEW_MATCH_DETAIL,
                    systemImage: "plus.circle", action: onNewMatch)
                Divider().overlay(Shuttl.border)
                row(HomeCopy.shared.ADD_RECORD_VIDEO, detail: HomeCopy.shared.ADD_RECORD_VIDEO_DETAIL,
                    systemImage: "video", action: onRecord)
                Divider().overlay(Shuttl.border)
                row(HomeCopy.shared.ADD_IMPORT_VIDEO, detail: HomeCopy.shared.ADD_IMPORT_VIDEO_DETAIL,
                    systemImage: "square.and.arrow.down", action: onImport)
            }
            .padding(.vertical, 8)
            .fixedSize(horizontal: false, vertical: true)
            .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { new in
                if new > 0 { contentHeight = new }
            }
        }
        .scrollBounceBehavior(.basedOnSize)
        .background(Shuttl.bgSecondary)
        .presentationBackground(Shuttl.bgSecondary)
        // The rows' own height, measured: with a line under each title a fixed
        // detent clipped the last row at a large text size, and scrolling keeps
        // every row reachable at accessibility sizes.
        .presentationDetents([.height(contentHeight)])
        .presentationDragIndicator(.visible)
    }

    private func row(
        _ title: String, detail: String, systemImage: String, action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            HStack(spacing: 14) {
                Image(systemName: systemImage)
                    .foregroundStyle(Shuttl.accentDark)
                    .frame(width: 24)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .shuttlType(ShuttlType.bodyLarge)
                        .foregroundStyle(Shuttl.text)
                    Text(detail)
                        .shuttlType(ShuttlType.bodySmall)
                        .foregroundStyle(Shuttl.textSecondary)
                }
                Spacer()
            }
            .padding(.horizontal, 24)
            .padding(.vertical, 16)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        // The name alone, as before the line under it: VoiceOver reads the
        // line as the hint, and the UI tests find the button by its name.
        .accessibilityLabel(title)
        .accessibilityHint(detail)
    }
}
