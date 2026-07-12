import Foundation

enum LocalVideoLogic {
    /// Same cap as Android's VideoIntake (and the web app): 1 GB.
    static let maxSizeBytes: Int64 = 1_073_741_824

    /// Returns the user-facing rejection message, or nil when the size is acceptable.
    static func oversizeMessage(bytes: Int64) -> String? {
        bytes > maxSizeBytes
            ? "Video is larger than 1GB. Please use a shorter recording."
            : nil
    }

    /// m:ss — matches Android's LocalVideoListViewModel.formatDuration.
    static func formatDuration(ms: Int64) -> String {
        let totalSec = ms / 1000
        return String(format: "%d:%02d", totalSec / 60, totalSec % 60)
    }
}
