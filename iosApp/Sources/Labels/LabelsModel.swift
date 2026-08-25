import Foundation
import Shared

@MainActor
final class LabelsModel: ObservableObject {
    private let rally: RallyApp
    @Published var labels: [AnnotationLabel] = []
    @Published var expandedId: String? = nil
    @Published var errorMessage: String? = nil

    init(rally: RallyApp) {
        self.rally = rally
    }

    /// Kicks a real load off the network, then streams the signed-in user's
    /// labels for the lifetime of the screen. Every mutation below (create,
    /// rename, recolor, delete) writes straight into the repository's backing
    /// state, so this same subscription is what shows their result on screen;
    /// no separate reload is needed after each one.
    func start() async {
        let message = try? await SwiftInteropKt.refreshLabelsOrMessage(rally.labels)
        if let message { errorMessage = message }
        for await ls in rally.labels.labels {
            labels = ls
        }
    }

    /// Passing the id already expanded collapses it, so a row is its own toggle.
    func expand(_ id: String) {
        expandedId = LabelsLogic.nextExpanded(current: expandedId, tapped: id)
    }

    func create(_ name: String, color: LabelColor) async {
        guard let outcome = try? await SwiftInteropKt.createLabelForSwift(rally.labels, name: name, color: color) else {
            errorMessage = "Couldn't add label"
            return
        }
        errorMessage = outcome.errorMessage
    }

    /// Returns whether the rename succeeded, so the editor can roll its local
    /// "last committed" text back on failure (a duplicate name is a normal,
    /// user-visible rejection here, not an edge case) instead of treating a
    /// rejected edit as if it had gone through.
    @discardableResult
    func rename(_ id: String, to name: String) async -> Bool {
        let message = try? await SwiftInteropKt.renameLabelOrMessage(rally.labels, id: id, name: name)
        errorMessage = message
        return message == nil
    }

    func recolor(_ id: String, to color: LabelColor) async {
        errorMessage = try? await SwiftInteropKt.recolorLabelOrMessage(rally.labels, id: id, color: color)
    }

    func delete(_ id: String) async {
        errorMessage = try? await SwiftInteropKt.deleteLabelOrMessage(rally.labels, id: id)
    }
}
