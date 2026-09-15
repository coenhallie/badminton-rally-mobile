import Foundation
import Shared

/// Where an on-device analysis keeps what it produced.
///
/// Android hangs everything off `context.filesDir`. The iOS equivalent is
/// Application Support, not Documents: Documents is where `LocalVideoFiles`
/// puts videos because those are the user's own recordings and belong in a
/// backup and, on a file-sharing build, in Files. A track, a skeleton and a set
/// of cut clips are derived - re-analysing reproduces them - so they belong
/// beside the app's other private state, out of the user's way and out of
/// iCloud's.
///
/// Excluded from backup for the same reason: a 30-minute match's skeleton is
/// 11MB and its clips are tens of megabytes of re-encoded video, and pushing
/// that into a coach's iCloud quota to save re-running an analysis they can
/// re-run is not a trade this app gets to make for them.
enum AnalysisFiles {

    private static let root = "Analysis"

    /// Paths are relative to this and resolved on every use, never persisted:
    /// the app container path changes across updates, which is the same reason
    /// `LocalVideoFiles` persists paths relative to Documents.
    static var directory: URL {
        let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        return support.appendingPathComponent(root, isDirectory: true)
    }

    /// Creates a subdirectory of the analysis root, excluding the root from
    /// backup the first time it is made.
    @discardableResult
    static func directory(_ name: String) throws -> URL {
        var base = directory
        let existed = FileManager.default.fileExists(atPath: base.path)
        let url = base.appendingPathComponent(name, isDirectory: true)
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        if !existed {
            var values = URLResourceValues()
            values.isExcludedFromBackup = true
            try? base.setResourceValues(values)
        }
        return url
    }

    /// What this device's own analysis wrote. Always removed: the entry is
    /// leaving either way, and a local track is only reachable through it.
    private static let localStores = [
        "player-tracks", "skeletons", "local-clips", "local-sources",
    ]

    /// What a cloud analysis wrote for this entry.
    ///
    /// Removed on a discard and kept on a retire. A cloud artifact costs a
    /// re-download rather than half an hour of device time, which is why it was
    /// once safe to treat it like the local ones - but nothing re-downloads it
    /// today (`CloudPoseCoordinator.install` has one caller, inside the upload
    /// pipeline), so deleting it on the pipeline's own success path loses it
    /// for good.
    private static let cloudStores = ["cloud-tracks", "cloud-skeletons"]

    /// Removes what this video left on disk, as much of it as `reason` allows.
    ///
    /// Called when the video leaves the phone. Without it a deleted match keeps
    /// its clips - tens of megabytes - and its skeleton for the life of the
    /// install, reachable by nothing.
    ///
    /// Mirrors Android's `AnalysisFiles.deleteAll`, over the same six names and
    /// the same rule.
    static func deleteAll(entryId: String, reason: LocalVideoRemoval) {
        let stores = reason == .discarded ? localStores + cloudStores : localStores
        for store in stores {
            let base = directory.appendingPathComponent(store, isDirectory: true)
            // Two shapes: a directory named for the entry (clips) and a file
            // named for it with an extension (the rest). Removing a path that
            // is not there is not an error worth reporting.
            try? FileManager.default.removeItem(at: base.appendingPathComponent(entryId, isDirectory: true))
            for suffix in [".track", ".skel", ".mp4"] {
                try? FileManager.default.removeItem(at: base.appendingPathComponent(entryId + suffix))
            }
        }
    }
}
