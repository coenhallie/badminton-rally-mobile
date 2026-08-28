import AVFoundation
import Foundation
import Photos
import Shared

/// The match a pick is for. Mirrors Android's `VideoIntake.MatchTarget`: the
/// title is not decoration - it rides along on the videos INSERT and the
/// database grants no UPDATE on videos.title, so this is the only moment the
/// video can be given the name the coach already chose.
struct MatchTarget {
    let scoreLogId: String
    let title: String
}

/// Mirrors Android's VideoIntake.addEntryFromUri: copy into the store, extract
/// metadata, enforce the 1 GB cap, persist a LOCAL-stage entry.
@MainActor @Observable
final class LocalVideoIntake {
    let rally: RallyApp
    var error: String? = nil
    /// Id of the entry this intake just persisted, for the host view to open the
    /// details sheet over. Cleared by the host once it has acted on it.
    var lastAddedId: String? = nil

    init(rally: RallyApp) { self.rally = rally }

    /// tempURL: file handed over by the picker/camera (consumed by this call).
    /// suggestedName: picker-provided name; recordings pass nil and get the
    /// shuttl_<epochMillis>.mp4 pattern. isRecording additionally saves to Photos.
    /// forMatch: the score log this pick is for, or nil for a video-first import.
    func add(tempURL: URL, suggestedName: String?, isRecording: Bool, forMatch: MatchTarget? = nil) async {
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
        // Persist first, then surface the id: a dismissed details sheet, a
        // backgrounded app or a crash must never cost the video just taken.
        let id = UUID().uuidString
        rally.localVideos.add(entry: LocalVideoEntry(
            id: id,
            uri: relativePath,
            displayName: displayName,
            durationMs: durationMs,
            sizeBytes: sizeBytes,
            addedAtEpochMs: epochMs,
            title: forMatch?.title,
            description: nil,
            keypoints: nil,
            stage: .local,
            failedStep: nil,
            failureMessage: nil,
            resultSeen: false,
            scoreLogId: forMatch?.scoreLogId
        ))
        lastAddedId = id
    }

    func remove(entry: LocalVideoEntry) {
        // The file goes with the entry: the registry's onRemoved hook is wired to
        // LocalVideoFiles.delete in createRallyApp, so every removal path cleans up.
        rally.localVideos.remove(id: entry.id)
        rally.localAnnotations.removeAllFor(videoId: entry.id)
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
