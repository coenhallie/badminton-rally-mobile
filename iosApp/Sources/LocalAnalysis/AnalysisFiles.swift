import Foundation

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

    /// Removes everything an analysis produced for one video.
    ///
    /// Called when the video leaves the phone. Without it a deleted match keeps
    /// its clips - tens of megabytes - and its skeleton for the life of the
    /// install, reachable by nothing.
    static func deleteAll(entryId: String) {
        // The cloud stores are here on the same footing as the local ones:
        // removing the entry already removes its local analysis, and nothing
        // protects a cloud artifact the way it protects a local one - a local
        // track costs half an hour of device time to recreate, a cloud one
        // only a re-download. A match uploaded from a different phone has no
        // local entry here, so this never reaches an artifact whose entry
        // lives elsewhere. Mirrors Android's AnalysisFiles.STORES.
        let stores = [
            "player-tracks", "skeletons", "local-clips", "local-sources",
            "cloud-tracks", "cloud-skeletons",
        ]
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
