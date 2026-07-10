import SwiftUI
import Shared

@main
struct RallyIOSApp: App {
    let rally: RallyApp

    init() {
        let info = Bundle.main.infoDictionary
        rally = RallyAppIosKt.createRallyApp(
            url: info?["SUPABASE_URL"] as? String ?? "",
            anonKey: info?["SUPABASE_ANON_KEY"] as? String ?? ""
        )
    }

    var body: some Scene {
        WindowGroup {
            RootView(rally: rally)
        }
    }
}
