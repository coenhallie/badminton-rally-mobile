import SwiftUI
import Shared

struct RootView: View {
    let rally: RallyApp
    @State private var authState: AuthState? = nil

    var body: some View {
        Group {
            switch authState {
            case nil, .loading:
                SplashView()
            case .authenticated:
                NavigationStack { ClipListView(rally: rally) }
            case .unauthenticated:
                SignInView(rally: rally)
            case .some:
                SplashView()
            }
        }
        .task {
            for await state in rally.authState {
                authState = state
            }
        }
    }
}
