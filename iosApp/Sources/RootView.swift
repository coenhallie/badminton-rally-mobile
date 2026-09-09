import SwiftUI
import UIKit
import Shared

struct RootView: View {
    let rally: RallyApp
    let analyze: AnalyzeCoordinator
    /// The on-device pipeline, held here rather than by a screen because an
    /// analysis outlives the screen that starts it - the same reason
    /// `analyze` is held here.
    ///
    /// Nil when this build has no ONNX graphs staged, which is CI's build and
    /// any checkout that has not run the export scripts. Passed down as nil so
    /// every screen offers the cloud alone rather than a second button that
    /// fails on the tap.
    @State private var localAnalysis: LocalAnalysisRunner? =
        ModelCatalog.canAnalyseOnDevice ? LocalAnalysisRunner() : nil
    @Environment(\.scenePhase) private var scenePhase
    @State private var authState: AuthState? = nil
    @State private var themeMode: ThemeMode = .light
    @State private var uploading = false

    var body: some View {
        Group {
            switch authState {
            case nil, .loading:
                SplashView()
            case .authenticated:
                HomeView(rally: rally, analyze: analyze, localAnalysis: localAnalysis)
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
                uploading = active.boolValue
            }
        }
        // One writer for the whole app. An upload and an on-device run both
        // need the screen awake, and setting this from two places means
        // whichever finishes first turns the lock back on under the other -
        // which for a 20-minute analysis is the run dying at the lock screen.
        .onChange(of: uploading || (localAnalysis?.isRunning ?? false)) { _, awake in
            UIApplication.shared.isIdleTimerDisabled = awake
        }
        .onChange(of: scenePhase) { _, phase in
            // iOS has no foreground service, so a run in the background gets no
            // CPU and says so; see LocalAnalysisState.paused.
            //
            // `.background` against `.active`, with `.inactive` left alone on
            // purpose: an app switcher glance, a control centre pull and a
            // notification banner all pass through inactive while the app keeps
            // running, and a row that announced a stopped analysis for each of
            // them would be wrong every time.
            switch phase {
            case .background: localAnalysis?.suspendForBackground()
            case .active:     localAnalysis?.resumeFromBackground()
            default:          break
            }
        }
    }
}
