import SwiftUI

/// A page's own loading state: an indicator centred in the space the page
/// gives it. Split from `SplashView`, which is the app's launch screen and
/// draws the wordmark in a title bar; that has no place inside a match page.
struct LoadingView: View {
    var body: some View {
        ProgressView()
            .tint(Shuttl.textSecondary)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}
