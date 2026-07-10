import AVKit
import Foundation
import Shared

@Observable @MainActor
final class ClipDetailModel {
    let rally: RallyApp
    let clipId: String
    private(set) var isLoading = true
    private(set) var clip: RallyClip? = nil
    private(set) var annotations: [RallyAnnotation] = []
    private(set) var player: AVPlayer? = nil
    private(set) var isOwner = false
    var error: String? = nil
    var actionError: String? = nil

    init(rally: RallyApp, clipId: String) {
        self.rally = rally
        self.clipId = clipId
    }

    func load() async {
        isLoading = true
        error = nil
        // Cache first, then one refresh — mirrors ClipDetailViewModel.load().
        var found = try? await firstCachedClip()
        if found == nil {
            do {
                try await rally.clips.refresh()
                found = try? await firstCachedClip()
            } catch {
                self.error = "Couldn't load clip: \(error.localizedDescription)"
            }
        }
        guard let clip = found else {
            if error == nil { error = "Clip not found" }
            isLoading = false
            return
        }
        self.clip = clip
        isOwner = clip.ownerId == rally.auth.currentUserId()

        if let rows = try? await rally.annotations.list(clipId: clipId) {
            annotations = rows   // server-sorted by timestamp ascending
        } else {
            actionError = "Couldn't load annotations"
        }
        await sign(clip: clip)
        isLoading = false
    }

    /// Re-sign the URL (used by load and by the manual Retry on player failure).
    func sign(clip: RallyClip) async {
        do {
            let signed = try await rally.media.signedClipUrl(clip: clip)
            if let url = URL(string: signed) {
                player = AVPlayer(url: url)
            } else {
                error = "Couldn't load video"
            }
        } catch {
            self.error = "Couldn't sign clip URL"
        }
    }

    func retry() async {
        error = nil
        if let clip { await sign(clip: clip) } else { await load() }
    }

    func seek(to seconds: Float) {
        player?.seek(to: CMTime(seconds: Double(seconds), preferredTimescale: 600))
    }

    func add(kind: AnnotationKind?, body: String) async {
        guard isOwner else { return }
        let trimmed = body.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty && kind == nil { return }
        let ts = max(0, currentTimeSeconds())
        guard let outcome = try? await SwiftInteropKt.addAnnotationForSwift(
            rally.annotations, clipId: clipId, timestampSeconds: ts, body: trimmed, kind: kind
        ) else {
            actionError = "Couldn't add annotation"
            return
        }
        if let row = outcome.annotation {
            annotations = (annotations + [row]).sorted { $0.timestampSeconds < $1.timestampSeconds }
        } else {
            actionError = outcome.errorMessage
        }
    }

    func delete(id: String) async {
        guard isOwner else { return }
        // `try?` on an async-throws call returning `String?` flattens to `String?`
        // (SE-0230), so a single unwrap already covers both "call threw" and
        // "call succeeded with nil" — both fall through to local removal below,
        // matching the intended double-optional semantics.
        if let message = try? await SwiftInteropKt.deleteAnnotationOrMessage(rally.annotations, id: id) {
            actionError = message
        } else {
            annotations = annotations.filter { $0.id != id }
        }
    }

    func currentTimeSeconds() -> Float {
        guard let time = player?.currentTime(), time.isNumeric else { return 0 }
        return Float(CMTimeGetSeconds(time))
    }

    private func firstCachedClip() async throws -> RallyClip? {
        for await clips in rally.clips.observeClips() {
            return clips.first { $0.id == clipId }   // take first emission only
        }
        return nil
    }
}
