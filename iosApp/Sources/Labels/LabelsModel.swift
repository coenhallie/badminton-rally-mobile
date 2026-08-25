import Foundation
import Shared

@MainActor
final class LabelsModel: ObservableObject {
    private let rally: RallyApp
    @Published var labels: [AnnotationLabel] = []
    @Published var expanded: LabelEditTarget? = nil
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
        expanded = LabelsLogic.nextExpanded(current: expanded, tapped: .existing(id))
    }

    /// Opens (or, tapped again, closes) the draft row the toolbar "+" and the
    /// empty state's action button both call. Nothing is created yet -
    /// [create] below does that once the draft's name is committed - so
    /// backing out of the draft without typing anything never touches the
    /// server.
    func startCreating() {
        expanded = LabelsLogic.nextExpanded(current: expanded, tapped: .new)
    }

    /// Unlike [rename]/[recolor]/[delete], a failed create leaves the draft
    /// row open (its typed name and chosen colour survive in the view's own
    /// local state) rather than collapsing it - a rejected duplicate name is
    /// recoverable in place instead of forcing the user to reopen the row
    /// and retype. A successful create hands the new row's own id to
    /// `.existing`, so the same in-place editor keeps showing, now bound to
    /// the real, persisted label.
    func create(_ name: String, color: LabelColor) async {
        guard let outcome = try? await SwiftInteropKt.createLabelForSwift(rally.labels, name: name, color: color) else {
            errorMessage = "Couldn't add label"
            return
        }
        errorMessage = outcome.errorMessage
        if let created = outcome.label {
            expanded = .existing(created.id)
        }
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
