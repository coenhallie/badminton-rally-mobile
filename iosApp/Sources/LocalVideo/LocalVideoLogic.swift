import Foundation
import Shared

/// An entry's registry id is its identity, so SwiftUI's identity-keyed APIs
/// (`ForEach`, `sheet(item:)`) can take entries directly.
extension LocalVideoEntry: Identifiable {}

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

/// Where a video's Analyze goes when it is tapped, on the list's row and on the
/// match page alike.
enum AnalyseAction: Equatable {
    /// A failed cloud run whose court is already saved: `AnalyzeCoordinator.retry`
    /// picks up at the step that failed, so there is nothing to ask the coach for.
    case resume(entryId: String)
    /// Everything else starts at court marking.
    case markCourt(entryId: String)
}

/// Where this video's Analyze goes. The rule itself is shared
/// (`canResumeFailedAnalysis`) rather than written out here: five hand-written
/// copies of `stage == .failed && keypoints != nil` are exactly what put it in
/// `shared/` in the first place, and this would have been the sixth.
func analyseAction(for entry: LocalVideoEntry) -> AnalyseAction {
    LocalVideoEntryKt.canResumeFailedAnalysis(entry: entry)
        ? .resume(entryId: entry.id)
        : .markCourt(entryId: entry.id)
}
