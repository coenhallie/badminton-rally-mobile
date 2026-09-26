import SwiftUI

/// One rally as the Rallies section lists it: its name over a small caps meta
/// line. Shared by the device rallies and, from Task 7, the cloud ones, so a
/// match's rallies look the same wherever they were cut: the wrapper differs (a
/// `Button` opening the clip sheet, a `NavigationLink` opening the rally page),
/// the row does not. Port of the row androidApp's `ClipRow` and
/// `DeviceRalliesSection` both draw.
///
/// `meta` is set in capitals here rather than by the caller, so the two lists
/// cannot disagree on it; nil draws the name alone (a device clip whose bounds
/// were lost).
struct RallyRowLabel: View {
    let title: String
    let meta: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title)
                .shuttlType(ShuttlType.titleMedium)
                .foregroundStyle(Shuttl.text)
            if let meta {
                Text(meta.uppercased())
                    .shuttlType(ShuttlType.labelSmall)
                    .foregroundStyle(Shuttl.textSecondary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .contentShape(Rectangle())
    }
}

/// A rally row's place in its `List`.
enum RallyRow {
    /// The page's gutter either side, and Android's `MatchRowInset` (14)
    /// above and below, the inset its cloud and device rally rows share.
    static let insets = EdgeInsets(top: 14, leading: ShuttlGutter.page, bottom: 14, trailing: ShuttlGutter.page)
}

extension View {
    /// A rally row's list chrome: `RallyRow.insets`, a separator in the border
    /// token rather than the system grey, and no row fill of its own, so the
    /// page's background shows through.
    func rallyListRow() -> some View {
        listRowInsets(RallyRow.insets)
            .listRowSeparatorTint(Shuttl.border)
            .listRowBackground(Color.clear)
    }
}
