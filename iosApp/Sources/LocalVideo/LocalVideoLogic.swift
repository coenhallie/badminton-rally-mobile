import Foundation
import Shared

enum LocalVideoLogic {
    /// Returns the user-facing rejection message, or nil when the size is acceptable.
    /// The cap and its copy live in Shared (LocalVideoLimits) so Android and iOS
    /// cannot drift apart on either.
    static func oversizeMessage(bytes: Int64) -> String? {
        LocalVideoLimits.shared.oversizeMessage(sizeBytes: bytes)
    }

    /// m:ss — matches Android's LocalVideoListViewModel.formatDuration.
    static func formatDuration(ms: Int64) -> String {
        let totalSec = ms / 1000
        return String(format: "%d:%02d", totalSec / 60, totalSec % 60)
    }
}
