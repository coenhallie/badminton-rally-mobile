import SwiftUI

/// A tab's own title: the tab's name at headline size, as the drawer's header
/// drew "Matches", with the background-work indicator trailing. Not a
/// navigation bar title: the stack's bar is hidden over the tabs, because the
/// tab bar lives inside the stack's root (see HomeView).
struct TabHeader<Trailing: View>: View {
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
        }
        .padding(.horizontal, 24)
        .padding(.top, 12)
        .padding(.bottom, 8)
    }
}

extension TabHeader where Trailing == EmptyView {
    init(_ title: String) { self.init(title) { EmptyView() } }
}
