import SwiftUI

/// The page's own background, on the navigation bar, always.
///
/// SwiftUI leaves the bar transparent until it decides content has scrolled
/// under it, and on these screens it never decided: a page long enough to
/// scroll drew its content straight through the title
/// (`docs/screenshots/2026-09-09-ios-base-panel-rallies.png`, where the Base
/// panel's court card runs through "Base panel fixture"). The heatmap and the
/// skeleton are short enough that it never showed, which is why it survived
/// this long.
///
/// `Shuttl.bg` rather than a system material because that is what every one of
/// these pages paints behind its content: at rest the bar is indistinguishable
/// from the page, which is how it already looked, and the only thing that
/// changes is that scrolled content no longer shows through the title.
///
/// One modifier rather than the line repeated on each screen: the bar is the
/// same object on all of them, and a per-screen fix is how two of them end up
/// treated differently.
///
/// For a page that paints `Shuttl.bg`, which is the Analytics list, the
/// Analytics detail and the clips list. The labels screen is deliberately NOT
/// one of them: its `List` keeps the system's own background rather than the
/// palette's, and a `Shuttl.bg` bar over it would seam against that in dark,
/// where the two are 0x0B0C0D and black. That screen keeps the system bar.
struct ShuttlNavigationBarBackground: ViewModifier {
    func body(content: Content) -> some View {
        content
            .toolbarBackground(.visible, for: .navigationBar)
            .toolbarBackground(Shuttl.bg, for: .navigationBar)
    }
}

extension View {
    /// See `ShuttlNavigationBarBackground`.
    func shuttlNavigationBarBackground() -> some View {
        modifier(ShuttlNavigationBarBackground())
    }
}
