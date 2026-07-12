import AVFoundation
import Foundation
import Shared

@Observable @MainActor
final class LocalPlayerModel {
    let rally: RallyApp
    let entry: LocalVideoEntry
    private(set) var player: AVPlayer
    private(set) var annotations: [LocalAnnotation] = []
    var playbackError: String? = nil
    private var fps: Float = 0
    private var statusObservation: NSKeyValueObservation?

    init(rally: RallyApp, entry: LocalVideoEntry) {
        self.rally = rally
        self.entry = entry
        self.player = AVPlayer(url: LocalVideoFiles.resolve(relativePath: entry.uri))
        observeFailure()
    }

    func loadMetadata() async {
        let asset = AVURLAsset(url: LocalVideoFiles.resolve(relativePath: entry.uri))
        if let track = try? await asset.loadTracks(withMediaType: .video).first,
           let rate = try? await track.load(.nominalFrameRate) {
            fps = rate
        }
    }

    func observeAnnotations() async {
        for await map in rally.localAnnotations.byVideoId {
            annotations = map[entry.id] ?? []
        }
    }

    func currentTimestampSeconds() -> Float {
        let time = player.currentTime()
        guard time.isNumeric else { return 0 }
        return max(0, Float(CMTimeGetSeconds(time)))
    }

    func add(kind: AnnotationKind?, body: String, atSeconds: Float) {
        let trimmed = body.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty && kind == nil { return }
        _ = rally.localAnnotations.add(
            videoId: entry.id,
            timestampSeconds: max(0, atSeconds),
            body: trimmed,
            kind: kind
        )
    }

    func delete(annotationId: String) {
        rally.localAnnotations.delete(videoId: entry.id, annotationId: annotationId)
    }

    func seek(toSeconds seconds: Float) {
        player.seek(
            to: CMTime(seconds: Double(seconds), preferredTimescale: 600),
            toleranceBefore: .zero,
            toleranceAfter: .zero
        )
    }

    func stepFrames(_ delta: Int64) {
        if player.rate != 0 { player.pause() }
        let current = CMTimeGetSeconds(player.currentTime())
        let durationTime = player.currentItem?.duration
        let duration = (durationTime?.isNumeric == true) ? CMTimeGetSeconds(durationTime!) : 0
        let target = FrameStepMath.targetSeconds(
            currentSeconds: current.isFinite ? current : 0,
            fps: fps,
            delta: delta,
            durationSeconds: duration.isFinite ? duration : 0
        )
        player.seek(
            to: CMTime(seconds: target, preferredTimescale: 600),
            toleranceBefore: .zero,
            toleranceAfter: .zero
        )
    }

    func retry() {
        playbackError = nil
        statusObservation?.invalidate()
        player.pause()
        player = AVPlayer(url: LocalVideoFiles.resolve(relativePath: entry.uri))
        observeFailure()
    }

    private func observeFailure() {
        statusObservation = player.currentItem?.observe(\.status, options: [.new]) { [weak self] item, _ in
            guard item.status == .failed else { return }
            Task { @MainActor [weak self] in
                self?.playbackError = "Couldn't play this video. The file may have been moved or deleted."
            }
        }
    }
}
