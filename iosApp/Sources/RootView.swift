import SwiftUI
import UIKit
import Shared

struct RootView: View {
    let rally: RallyApp
    let analyze: AnalyzeCoordinator
    @State private var authState: AuthState? = nil
    @State private var themeMode: ThemeMode = .light

    var body: some View {
        Group {
            switch authState {
            case nil, .loading:
                SplashView()
            case .authenticated:
                NavigationStack { ClipListView(rally: rally, analyze: analyze) }
            case .unauthenticated:
                SignInView(rally: rally)
            case .some:
                SplashView()
            }
        }
        .preferredColorScheme(themeMode == .dark ? .dark : .light)
        .task {
            for await state in rally.authState {
                authState = state
            }
        }
        .task {
            for await mode in rally.themePrefs.mode {
                themeMode = mode
            }
        }
        .task {
            for await active in analyze.hasActiveUpload {
                UIApplication.shared.isIdleTimerDisabled = active.boolValue
            }
        }
    }
}
