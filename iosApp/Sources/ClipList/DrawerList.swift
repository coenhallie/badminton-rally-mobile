import SwiftUI

/// The drawer list's shared metrics, from the `Rally Drawer` mock.
///
/// The list is an inset one now, not a full-bleed one: rows are cards sitting on
/// the panel with a margin either side, separated by a gap rather than by a
/// divider. Everything that renders a row in the drawer - the "On this phone"
/// card in LocalVideoSection.swift and both match rows in ClipListView.swift -
/// reads its numbers from here, so the two files cannot drift a point apart.
///
/// Mirrors androidApp's DrawerList.kt number for number.
enum DrawerList {
    /// The panel's own gutter. The mock's `margin: … 24px`.
    static let sideMargin: CGFloat = 24
    static let rowPaddingH: CGFloat = 16
    static let rowPaddingV: CGFloat = 14
    /// Thumbnail to text, and text to the trailing control.
    static let gap: CGFloat = 12
    /// Between two rows. Replaces the separator the old full-bleed list drew.
    static let rowGap: CGFloat = 6
    /// Above a section label. `rowGap` adds to this between two sections.
    static let sectionGap: CGFloat = 22
    /// A section label to its first row. `rowGap` adds to this.
    static let labelGap: CGFloat = 4
    /// The list's own top inset. On Android the LazyColumn carries it as
    /// contentPadding and every label carries `sectionGap`, which puts the first
    /// label 26dp below the drawer header; `firstSectionGap` is that same sum,
    /// stated once, because a SwiftUI row carries its whole inset itself.
    static let listTopInset: CGFloat = 4
    /// Above the FIRST label, measured from the drawer's header. Unlike a later
    /// label there is no row above it to contribute `rowGap`.
    static let firstSectionGap: CGFloat = sectionGap + listTopInset
    static let thumbWidth: CGFloat = 64
    static let thumbHeight: CGFloat = 40
}

extension View {
    /// Strips the styling `List` gives a row so an inset card can be drawn in
    /// its place: no separator, no system row fill over the drawer's own panel,
    /// and the row's margin and gap stated as insets rather than as spacing
    /// between rows (`listRowSpacing` is a `List` initialiser argument, and this
    /// list needs different gaps above a section label and above a row).
    ///
    /// [top] carries whatever extra the caller needs above this row - a section
    /// label asks for `DrawerList.sectionGap`.
    func drawerListRow(top: CGFloat = 0) -> some View {
        self
            .listRowInsets(EdgeInsets(
                top: top,
                leading: DrawerList.sideMargin,
                bottom: DrawerList.rowGap,
                trailing: DrawerList.sideMargin
            ))
            .listRowBackground(Color.clear)
            .listRowSeparator(.hidden)
    }
}

/// A drawer section's label: 12pt, secondary, sentence case.
///
/// Deliberately not `Shuttl.sectionLabel`, the uppercase tracked `labelSmall`
/// the app's older lists use: the mocks for this redesign carry no uppercase and
/// no positive tracking anywhere. AnalyticsListView.swift has since moved to
/// this form and states its own spacing, so it draws the label itself rather
/// than calling this; the two want one shared component. ShareSheetView.swift is
/// the last screen still on the older form.
struct DrawerSectionLabel: View {
    let text: String

    init(_ text: String) { self.text = text }

    var body: some View {
        Text(text)
            .shuttlType(ShuttlType.bodySmall)
            .foregroundStyle(Shuttl.textSecondary)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.bottom, DrawerList.labelGap)
    }
}
