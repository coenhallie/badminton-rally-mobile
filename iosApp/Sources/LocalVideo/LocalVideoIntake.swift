import AVFoundation
import Foundation
import Photos
import Shared

/// Mirrors Android's VideoIntake.addEntryFromUri: copy into the store, extract
/// metadata, enforce the 1 GB cap, persist a LOCAL-stage entry.
@MainActor @Observable
final class LocalVideoIntake {
    let rally: RallyApp
    var error: String? = nil

    init(rally: RallyApp) { self.rally = rally }

    /// tempURL: file handed over by the picker/camera (consumed by this call).
    /// suggestedName: picker-provided name; recordings pass nil and get the
    /// shuttl_<epochMillis>.mp4 pattern. isRecording additionally saves to Photos.
    func add(tempURL: URL, suggestedName: String?, isRecording: Bool) async {
        error = nil
        let sizeBytes = (try? FileManager.default
            .attributesOfItem(atPath: tempURL.path)[.size] as? Int64).flatMap { $0 } ?? 0
        if let message = LocalVideoLogic.oversizeMessage(bytes: sizeBytes) {
            try? FileManager.default.removeItem(at: tempURL)
            error = message
            return
        }

        let epochMs = Int64(Date().timeIntervalSince1970 * 1000)
        let displayName = suggestedName ?? "shuttl_\(epochMs).mp4"

        if isRecording {
            await saveToPhotos(tempURL) // best-effort; in-app copy is authoritative
        }

        let relativePath: String
        do {
            relativePath = try LocalVideoFiles.store(tempURL: tempURL)
        } catch {
            try? FileManager.default.removeItem(at: tempURL)
            self.error = "Couldn't save the video. Please try again."
            return
        }

        let durationMs = await loadDurationMs(LocalVideoFiles.resolve(relativePath: relativePath))
        rally.localVideos.add(entry: LocalVideoEntry(
            id: UUID().uuidString,
            uri: relativePath,
            displayName: displayName,
            durationMs: durationMs,
            sizeBytes: sizeBytes,
            addedAtEpochMs: epochMs,
            keypoints: nil,
            stage: .local,
            failedStep: nil,
            failureMessage: nil,
            resultSeen: false
        ))
    }

    func remove(entry: LocalVideoEntry) {
        rally.localVideos.remove(id: entry.id)
        rally.localAnnotations.removeAllFor(videoId: entry.id)
        LocalVideoFiles.delete(relativePath: entry.uri)
    }

    /// Best-effort, mirrors Android's runCatching retriever (0 on failure).
    private func loadDurationMs(_ url: URL) async -> Int64 {
        let asset = AVURLAsset(url: url)
        guard let duration = try? await asset.load(.duration) else { return 0 }
        let seconds = CMTimeGetSeconds(duration)
        return seconds.isFinite ? Int64(seconds * 1000) : 0
    }

    private func saveToPhotos(_ url: URL) async {
        let status = await PHPhotoLibrary.requestAuthorization(for: .addOnly)
        guard status == .authorized || status == .limited else { return }
        // Copy first: creationRequestForAssetFromVideo needs the file to outlive
        // the change block, and store(tempURL:) moves the original afterwards.
        let photosCopy = FileManager.default.temporaryDirectory
            .appendingPathComponent("photos-\(UUID().uuidString).mp4")
        guard (try? FileManager.default.copyItem(at: url, to: photosCopy)) != nil else { return }
        try? await PHPhotoLibrary.shared().performChanges {
            PHAssetChangeRequest.creationRequestForAssetFromVideo(atFileURL: photosCopy)
        }
        try? FileManager.default.removeItem(at: photosCopy)
    }
}
