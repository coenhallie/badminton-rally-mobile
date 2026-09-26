import Shared
import SwiftUI

/// Navigation value for the clips one on-device run cut.
struct LocalClipsRoute: Hashable {
    let entryId: String
}

/// The device rallies as their own pushed screen, for the list row's menu until
/// the match page hosts `DeviceRalliesSection` itself.
struct LocalClipsView: View {
    let localAnalysis: LocalAnalysisRunner?
    let entryId: String

    var body: some View {
        DeviceRalliesSection(localAnalysis: localAnalysis, entryId: entryId)
            .navigationTitle("Clips on this phone")
            .navigationBarTitleDisplayMode(.inline)
            .shuttlNavigationBarBackground()
    }
}
