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
            anonKey: info?["SUPABASE_ANON_KEY"] as? String ?? "",
            deleteLocalVideoFile: { LocalVideoFiles.delete(relativePath: $0) }
        )
        // Before anything can import: reclaim files whose entry is already gone.
        // Analyzed videos removed by builds that did not delete the file are pure
        // dead weight in the container, and the user has no way to get at them.
        LocalVideoFiles.sweepOrphans(referenced: rally.localVideos.entries.value.map(\.uri))
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
