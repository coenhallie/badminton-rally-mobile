import SwiftUI
import Shared

@main
struct RallyIOSApp: App {
    let rally: RallyApp
    let analyze: AnalyzeCoordinator

    init() {
        let info = Bundle.main.infoDictionary
        rally = RallyAppIosKt.createRallyApp(
            url: info?["SUPABASE_URL"] as? String ?? "",
            anonKey: info?["SUPABASE_ANON_KEY"] as? String ?? ""
        )
        analyze = AnalyzeCoordinatorIosKt.createIosAnalyzeCoordinator(
            rally: rally,
            documentsPath: LocalVideoFiles.documents.path
        )
        analyze.reattachToProcessing()
    }

    var body: some Scene {
        WindowGroup {
            RootView(rally: rally, analyze: analyze)
        }
    }
}
