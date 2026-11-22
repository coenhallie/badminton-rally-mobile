import SwiftUI
import UIKit
import Shared

struct RootView: View {
    let rally: RallyApp
    let analyze: AnalyzeCoordinator
    /// The single app-wide runner owned by `RallyIOSApp`, used both for reading
    /// stored cloud/local analyses and (when ONNX models are staged) running
    /// on-device analyses. Always non-nil so cloud heatmaps, base positions,
    /// and skeletons stay available even when on-device inference models are
    /// absent; UI buttons for on-device runs gate on `ModelCatalog.canAnalyseOnDevice`.
    let localAnalysis: LocalAnalysisRunner
    /// What the app is working on, for the chrome indicator. Held here for the
    /// same reason `localAnalysis` is: a run outlives every screen that shows it,
    /// and its two `for await` loops never return.
    @State private var backgroundWork: BackgroundWorkModel
    @Environment(\.scenePhase) private var scenePhase
    @State private var authState: AuthState? = nil
    @State private var themeMode: ThemeMode
    @State private var uploading = false
    /// Where the scoring board says it is on screen. See `KeepAwake`.
    @State private var keepAwake = KeepAwake()

    /// Both models are made here, once, as `HomeView` makes its own: they need
    /// `rally`, which a property initialiser cannot see, and a second instance
    /// of either would restart loops that are meant to run for the life of the
    /// app.
    init(rally: RallyApp, analyze: AnalyzeCoordinator, localAnalysis: LocalAnalysisRunner) {
        self.rally = rally
        self.analyze = analyze
        self.localAnalysis = localAnalysis
        // Seeded from the stored preference, not a fixed .light: the `.task`
        // below only delivers it after the first frame, so a dark-theme cold
        // start drew the splash (and a signed-out sign-in) light, then flipped.
        _themeMode = State(initialValue: rally.themePrefs.mode.value)
        _backgroundWork = State(
            initialValue: BackgroundWorkModel(localVideos: rally.localVideos, analyze: analyze, runner: localAnalysis)
        )
    }

    var body: some View {
        Group {
            switch authState {
            case nil, .loading:
                SplashView()
            case .authenticated:
                // The scope is what reads `work` and so what re-evaluates on
                // every progress callback; Home is built by this body and handed
                // in, so only the bars that actually read the environment follow.
                BackgroundWorkScope(model: backgroundWork) {
                    HomeView(rally: rally, analyze: analyze, localAnalysis: localAnalysis)
                }
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
        // One writer for the whole app. An upload, an on-device run and the
        // scoring board all need the screen awake, and setting this from two
        // places means whichever finishes first turns the lock back on under
        // the other - which for a 20-minute analysis is the run dying at the
        // lock screen. The board was such a second writer until `KeepAwake`;
        // it reports there now and the rule is `shouldKeepAwake`.
        .environment(keepAwake)
        .onChange(of: shouldKeepAwake(keepAwakeReasons(
            uploading: uploading,
            analysing: localAnalysis.isRunning,
            boardsOnScreen: keepAwake.boardsOnScreen.count
        ))) { _, awake in
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
            case .background: localAnalysis.suspendForBackground()
            case .active:     localAnalysis.resumeFromBackground()
            default:          break
            }
        }
    }
}
