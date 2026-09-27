import SwiftUI
import Shared

/// What shows while the stored session is read. The same header as the
/// sign-in screen and the list, with the wordmark in the title's slot, so the
/// handover moves nothing: to sign-in the wordmark holds still, to the list
/// "Matches" takes its place. Paints `Shuttl.bg` itself; it used to leave the
/// system background, pure black in dark, and sign-in's own bg then drew a
/// visible step on the handover. Mirrors androidApp's `Splash`.
struct SplashView: View {
    var body: some View {
        VStack(spacing: 0) {
            HomeHeader(SignInCopy.shared.WORDMARK, titleRole: ShuttlType.wordmark)
            LoadingView()
        }
        .background(Shuttl.bg)
    }
}
