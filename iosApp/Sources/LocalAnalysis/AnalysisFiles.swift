import Foundation
import os
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

    enum Failure: LocalizedError {
        case invalidEntryId(String)
        case invalidPath(String)
        case signedOut

        var errorDescription: String? {
            switch self {
            case .invalidEntryId(let id): return "invalid analysis entry id: \(id)"
            case .invalidPath(let path): return "invalid analysis path: \(path)"
            case .signedOut: return "no signed-in account for analysis storage"
            }
        }
    }

    private enum OwnerScope {
        case unscoped
        case signedOut
        case user(String)
    }

    private static let root = "Analysis"
    private static let legacyOwnerMarker = ".analysis-legacy-owner"
    private static let ownerScope = OSAllocatedUnfairLock<OwnerScope>(initialState: .unscoped)

    /// Updates the active signed-in user ID for on-disk track/skeleton isolation.
    /// Passing `nil` hides all accounts' cached files while signed out.
    static func setActiveOwner(_ ownerId: String?) {
        ownerScope.withLock { state in
            if let ownerId, isValidEntryId(ownerId) {
                state = .user(ownerId)
            } else {
                state = .signedOut
            }
        }
    }

    /// Restores legacy unscoped behavior (used in unit tests).
    static func resetOwnerScopeForTesting() {
        ownerScope.withLock { $0 = .unscoped }
    }

    /// Resolves the per-account root directory under `baseDir`. The first
    /// signed-in account claims `baseDir` so pre-upgrade coach files stay
    /// intact; subsequent accounts live under `baseDir/users/<ownerId>`.
    static func accountRoot(baseDir: URL, ownerId: String?) -> URL? {
        guard let ownerId, isValidEntryId(ownerId) else { return nil }
        let marker = baseDir.appendingPathComponent(legacyOwnerMarker)
        let existing = (try? String(contentsOf: marker, encoding: .utf8))?
            .trimmingCharacters(in: .whitespacesAndNewlines)
        if let existing, !existing.isEmpty {
            if existing == ownerId { return baseDir }
            let usersDir = baseDir.appendingPathComponent("users", isDirectory: true)
            let candidate = usersDir.appendingPathComponent(ownerId, isDirectory: true)
            return isStrictlyInside(candidate, base: usersDir) ? candidate : nil
        }
        try? FileManager.default.createDirectory(at: baseDir, withIntermediateDirectories: true)
        try? ownerId.write(to: marker, atomically: true, encoding: .utf8)
        return baseDir
    }

    /// Validates that `entryId` matches `^[A-Za-z0-9_-]{1,128}$` so it can
    /// never traverse directories or escape its store.
    static func isValidEntryId(_ entryId: String) -> Bool {
        let count = entryId.utf8.count
        guard count >= 1, count <= 128 else { return false }
        return entryId.utf8.allSatisfy { b in
            (b >= 0x30 && b <= 0x39)       // 0-9
                || (b >= 0x41 && b <= 0x5A) // A-Z
                || (b >= 0x61 && b <= 0x7A) // a-z
                || b == 0x5F                // _
                || b == 0x2D                // -
        }
    }

    /// Verifies that `url`'s standardized file path remains strictly inside `base`.
    static func isStrictlyInside(_ url: URL, base: URL) -> Bool {
        let basePath = base.standardizedFileURL.path
        let prefix = basePath.hasSuffix("/") ? basePath : basePath + "/"
        let resolvedPath = url.standardizedFileURL.path
        return resolvedPath.hasPrefix(prefix) && resolvedPath.count > prefix.count
    }

    static func validatedFile(in store: String, entryId: String, suffix: String) -> URL? {
        guard isValidEntryId(entryId), let rootDir = activeDirectory else { return nil }
        let base = rootDir.appendingPathComponent(store, isDirectory: true)
        let target = base.appendingPathComponent(entryId + suffix)
        guard isStrictlyInside(target, base: base) else { return nil }
        return target
    }

    static func validatedSubdirectory(in store: String, entryId: String) -> URL? {
        guard isValidEntryId(entryId), let rootDir = activeDirectory else { return nil }
        let base = rootDir.appendingPathComponent(store, isDirectory: true)
        let target = base.appendingPathComponent(entryId, isDirectory: true)
        guard isStrictlyInside(target, base: base) else { return nil }
        return target
    }

    /// Base unscoped Analysis folder in Application Support.
    static var baseDirectory: URL {
        let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        return support.appendingPathComponent(root, isDirectory: true)
    }

    /// Active per-account Analysis folder, or nil when explicitly signed out.
    private static var activeDirectory: URL? {
        let scope = ownerScope.withLock { $0 }
        switch scope {
        case .unscoped: return baseDirectory
        case .signedOut: return nil
        case .user(let uid): return accountRoot(baseDir: baseDirectory, ownerId: uid)
        }
    }

    /// Paths are relative to this and resolved on every use, never persisted:
    /// the app container path changes across updates, which is the same reason
    /// `LocalVideoFiles` persists paths relative to Documents.
    static var directory: URL {
        activeDirectory ?? baseDirectory
    }

    /// Creates a subdirectory of the analysis root, excluding the root from
    /// backup the first time it is made.
    @discardableResult
    static func directory(_ name: String) throws -> URL {
        guard var base = activeDirectory else { throw Failure.signedOut }
        let existed = FileManager.default.fileExists(atPath: base.path)
        let url = base.appendingPathComponent(name, isDirectory: true)
        guard isStrictlyInside(url, base: base) else { throw Failure.invalidPath(name) }
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        if !existed {
            var values = URLResourceValues()
            values.isExcludedFromBackup = true
            try? base.setResourceValues(values)
        }
        return url
    }

    /// What goes whatever the reason: the footage, the re-encoded clips beside
    /// it, and both skeletons.
    ///
    /// The skeletons are here rather than with the tracks because a skeleton is
    /// drawn OVER playback. With the video gone it is an artifact nothing can
    /// display - roughly 11MB of it for a 30-minute match - so keeping one past
    /// its video would be storage held for a panel that can never open.
    private static let videoStores = [
        "local-clips", "local-sources", "skeletons", "cloud-skeletons",
    ]

    /// The player tracks, local and cloud.
    ///
    /// Kept when the coach is only freeing the footage. The heatmap and the
    /// base position are computed entirely from these and need no video at all:
    /// a track kept after its file is gone still opens the match page's
    /// Analysis, with Heatmap and Base but no Skeleton. Taking them here would
    /// turn "get my storage back" into "lose my analysis". Only a match with a
    /// cloud copy is listed again afterwards; one only on this phone leaves the
    /// list with its entry, and its kept track with it.
    private static let trackStores = ["player-tracks", "cloud-tracks"]

    /// Removes what this video left on disk, as much of it as `reason` allows.
    ///
    /// Called when the video leaves the phone. Without it a deleted match keeps
    /// its clips - tens of megabytes - and its skeleton for the life of the
    /// install, reachable by nothing.
    ///
    /// Mirrors Android's `AnalysisFiles.deleteAll`, over the same six names and
    /// the same rule.
    static func deleteAll(entryId: String, reason: LocalVideoRemoval) {
        guard isValidEntryId(entryId), let rootDir = activeDirectory else { return }
        let stores = reason == .discarded ? videoStores + trackStores : videoStores
        for store in stores {
            let base = rootDir.appendingPathComponent(store, isDirectory: true)
            // Two shapes: a directory named for the entry (clips) and a file
            // named for it with an extension (the rest). Removing a path that
            // is not there is not an error worth reporting.
            let dirTarget = base.appendingPathComponent(entryId, isDirectory: true)
            if isStrictlyInside(dirTarget, base: base) {
                try? FileManager.default.removeItem(at: dirTarget)
            }
            for suffix in [".track", ".skel", ".mp4"] {
                let fileTarget = base.appendingPathComponent(entryId + suffix)
                if isStrictlyInside(fileTarget, base: base) {
                    try? FileManager.default.removeItem(at: fileTarget)
                }
            }
        }
    }
}
