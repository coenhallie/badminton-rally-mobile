import Foundation
import Shared

enum LocalVideoLogic {
    /// Returns the user-facing rejection message, or nil when the size is acceptable.
    /// The cap and its copy live in Shared (LocalVideoLimits) so Android and iOS
    /// cannot drift apart on either.
    static func oversizeMessage(bytes: Int64) -> String? {
        LocalVideoLimits.shared.oversizeMessage(sizeBytes: bytes)
    }

    /// Names of files in the store that no registry entry points at, given the
    /// store's file names and the entries' Documents-relative paths.
    ///
    /// Compares by file name so a referenced path whose file is already gone
    /// simply matches nothing, rather than shifting the result.
    static func orphanedFileNames(inStore names: [String], referenced: [String]) -> [String] {
        let keep = Set(referenced.map { ($0 as NSString).lastPathComponent })
        return names.filter { !keep.contains($0) }
    }

    /// m:ss — matches Android's LocalVideoListViewModel.formatDuration.
    static func formatDuration(ms: Int64) -> String {
        let totalSec = ms / 1000
        return String(format: "%d:%02d", totalSec / 60, totalSec % 60)
    }
}
