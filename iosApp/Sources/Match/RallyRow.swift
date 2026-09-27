import SwiftUI

/// One rally as the Rallies section lists it: its name over a small caps meta
/// line. Shared by the device rallies and the cloud ones, so a match's rallies
/// look the same wherever they were cut: the action differs (the clip sheet
/// for a device clip, the rally page for a cloud one), the row does not. Port
/// of the row androidApp's `ClipRow` and `DeviceRalliesSection` both draw.
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
    /// The page's gutter either side, and `MatchPageLayout.rowInset` above and
    /// below, the inset the cloud and device rally rows share.
    static let insets = EdgeInsets(
        top: MatchPageLayout.rowInset, leading: ShuttlGutter.page,
        bottom: MatchPageLayout.rowInset, trailing: ShuttlGutter.page
    )
}

extension View {
    /// A rally row's list chrome: `RallyRow.insets`, a separator edge to edge
    /// in the border token rather than the system grey, and no row fill of its own, so the
    /// page's background shows through.
    ///
    /// No rule above the list's first row: a plain list draws its section's
    /// top separator there, which sat 2pt under the match page's section row
    /// and read as an underline on it. Rules go between and under rows only,
    /// as Android's dividers do.
    func rallyListRow() -> some View {
        listRowInsets(RallyRow.insets)
            .fullBleedListSeparator()
            .listRowSeparatorTint(Shuttl.border)
            .listSectionSeparator(.hidden, edges: .top)
            .listRowBackground(Color.clear)
    }
}

extension View {
    /// The rule under a list row drawn from edge to edge, as Android's
    /// dividers between rallies and points are. The system's own starts at the
    /// row's 24pt content inset and stops 16pt short of the trailing edge,
    /// which matched neither edge of anything on the page. The guides are in
    /// the row content's coordinates, which start at the gutter, so the content
    /// is first given the row's full width: a Points header measured at its
    /// own width drew its rule halfway across.
    func fullBleedListSeparator() -> some View {
        frame(maxWidth: .infinity, alignment: .leading)
            .alignmentGuide(.listRowSeparatorLeading) { _ in -ShuttlGutter.page }
            .alignmentGuide(.listRowSeparatorTrailing) { $0.width + ShuttlGutter.page }
    }
}
