import SwiftUI
import Shared

@main
struct RallyIOSApp: App {
    let rally: RallyApp
    let analyze: AnalyzeCoordinator
    /// The stores a fetched cloud analysis lands in. Held here rather than
    /// made per call: it is the same runner the panels read through.
    let localAnalysis = LocalAnalysisRunner()

    init() {
        let info = Bundle.main.infoDictionary
        rally = RallyAppIosKt.createRallyApp(
            url: info?["SUPABASE_URL"] as? String ?? "",
            anonKey: info?["SUPABASE_ANON_KEY"] as? String ?? "",
            deleteLocalVideoFile: { LocalVideoFiles.delete(relativePath: $0) },
            deleteLocalAnalysis: { AnalysisFiles.deleteAll(entryId: $0) }
        )
        // Before anything can import: reclaim files whose entry is already gone.
        // Analyzed videos removed by builds that did not delete the file are pure
        // dead weight in the container, and the user has no way to get at them.
        LocalVideoFiles.sweepOrphans(referenced: rally.localVideos.entries.value.map(\.uri))
        let runner = localAnalysis
        analyze = AnalyzeCoordinatorIosKt.createIosAnalyzeCoordinator(
            rally: rally,
            documentsPath: LocalVideoFiles.documents.path,
            saveCloudAnalysis: { entryId, outcome in
                // Throwing is caught on the Kotlin side and logged: a heatmap
                // that could not be written is not a Phase 1 failure.
                try? runner.saveCloudAnalysis(entryId: entryId, outcome: outcome)
            }
        )
        analyze.reattachToProcessing()
    }

    var body: some Scene {
        WindowGroup {
            RootView(rally: rally, analyze: analyze)
        }
    }
}
