import SwiftUI

/// The root screen's own title: "Matches" at headline size, with the
/// background-work indicator and the Settings gear trailing. Not a navigation
/// bar title: the stack's bar is hidden on its root, the list (see HomeView),
/// and every page pushed over it has a bar of its own. Mirrors androidApp's
/// `HomeTopBar`.
struct HomeHeader<Trailing: View>: View {
    let title: String
    let trailing: Trailing

    init(_ title: String, @ViewBuilder trailing: () -> Trailing) {
        self.title = title
        self.trailing = trailing()
    }

    var body: some View {
        HStack {
            Text(title)
                .shuttlType(ShuttlType.headlineLarge)
                .foregroundStyle(Shuttl.textHeading)
            Spacer()
            trailing
                // The trailing controls' 44pt targets reach into the padding
                // above and below rather than setting the row's height: the
                // title's line is 29pt, so without this the header grew by 15
                // and the title and everything under it moved down with it.
                .padding(.vertical, -Self.targetOverhang)
        }
        .padding(.leading, ShuttlGutter.page)
        // The filter row's inset: a trailing control centred in a 44pt box
        // lands in one column with the info button and the rows' menus below.
        .padding(.trailing, MatchFilterRow.trailingInset)
        .padding(.top, 12)
        .padding(.bottom, 8)
    }

    /// Half of a 44pt target less the title's 29pt line. Under the 8 of
    /// padding below, so a target never reaches past the header's own edge.
    private static var targetOverhang: CGFloat { 7.5 }
}
