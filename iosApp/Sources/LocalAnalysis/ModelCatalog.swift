import Foundation

/// The ONNX graphs the on-device analysis runs, as they sit in the app bundle.
///
/// Port of Android's `ModelCatalog`, minus its staging step: over there the
/// graphs live in assets, which are not files until something extracts them,
/// so each is copied to the files directory on first use. A bundle resource is
/// already a path, so this is a lookup.
///
/// Three graphs, not Android's four. See `Scripts/stage-onnx-models.sh` for why
/// InpaintNet is not among them.
enum Model: String, CaseIterable {
    case trackNet = "tracknet.fp16.onnx"
    case detector = "badminton.fp16.onnx"

    /// `yolo26n-pose` at 960. Loaded only when a metric that needs pose was
    /// asked for: it roughly doubles a run, and a coach who wanted rally clips
    /// must not pay for a player track they will not read.
    case pose = "posen.fp16.onnx"
}

enum ModelCatalog {

    /// Where the staging script puts the graphs, relative to the bundle.
    private static let directory = "models"

    /// Identifies the weights these graphs came from, or nil when this build
    /// has no graphs staged.
    ///
    /// Read from the file the staging script writes rather than typed here, for
    /// the reason Android reads it from a generated `BuildConfig` field: a
    /// hand-maintained string that someone forgets to bump makes a weights
    /// change invisible, and the re-anchoring rule keys on exactly this value to
    /// decide whether annotation timestamps have to move.
    ///
    /// Phase 1 weights only, matching Android. Pose cannot move a clip boundary,
    /// so folding it in would re-anchor every annotation in the library the
    /// first time the pose model changed, for no reason.
    static let version: String? = {
        guard let url = Bundle.main.url(
            forResource: "model-version", withExtension: "txt", subdirectory: directory
        ) else { return nil }
        return (try? String(contentsOf: url, encoding: .utf8))?
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }()

    /// Nil when the graph was not staged, which is a build made with
    /// `ONNX_MODELS_OPTIONAL=YES` - CI's, and any checkout that has not run the
    /// export scripts. The caller turns that into a refusal to start an
    /// on-device run, rather than a crash at the first inference.
    static func path(_ model: Model) -> String? {
        Bundle.main.url(
            forResource: (model.rawValue as NSString).deletingPathExtension,
            withExtension: (model.rawValue as NSString).pathExtension,
            subdirectory: directory
        )?.path
    }

    /// Whether this build can analyse on the device at all.
    ///
    /// Pose is deliberately not required: a Phase 1 run - rally clips, no player
    /// track - is a complete and useful analysis, and refusing it because the
    /// pose graph is absent would withhold the cheaper half of the feature over
    /// the more expensive one.
    static var canAnalyseOnDevice: Bool {
        path(.trackNet) != nil && path(.detector) != nil
    }
}
