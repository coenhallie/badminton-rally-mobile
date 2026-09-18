import AVFoundation
import Foundation
import Shared

@Observable @MainActor
final class LocalPlayerModel {
    let rally: RallyApp
    let entry: LocalVideoEntry
    private(set) var player: AVPlayer
    private(set) var annotations: [LocalAnnotation] = []
    var labels: [AnnotationLabel] = []
    var playbackError: String? = nil
    var actionError: String? = nil
    private var fps: Float = 0
    private var statusObservation: NSKeyValueObservation?

    init(rally: RallyApp, entry: LocalVideoEntry) {
        self.rally = rally
        self.entry = entry
        self.player = AVPlayer(url: LocalVideoFiles.resolve(relativePath: entry.uri))
        observeFailure()
    }

    /// Whether the label refresh that opens the screen has completed. See
    /// `start()`.
    private var labelsRefreshed = false

    /// Runs for as long as the player is on screen: `LocalPlayerView`'s `.task`
    /// calls this on every appearance and cancels it on every disappearance, and
    /// pushing court marking is a disappearance.
    ///
    /// The loops used to live in that `.task` behind `guard model == nil`, which
    /// is true exactly once: marking the court cancelled them, and on the way
    /// back the guard returned before restarting anything, so notes and labels
    /// stopped updating for the rest of the screen's life.
    ///
    /// Everything runs side by side rather than the loads first. They used to be
    /// awaited ahead of the loops, which held the notes list empty until a
    /// network request for labels had come back - in a sports hall, until it had
    /// timed out.
    func start() async {
        await withTaskGroup(of: Void.self) { group in
            group.addTask { @MainActor [weak self] in await self?.loadMetadata() }
            group.addTask { @MainActor [weak self] in await self?.refreshLabelsOnce() }
            group.addTask { @MainActor [weak self] in await self?.observe() }
        }
    }

    /// The two loops, as structured children of the caller's task - the same
    /// shape as `BackgroundWorkModel.observe`. Apart from `start()` so the tests
    /// can hold them to that contract without the network refresh riding along.
    func observe() async {
        await withTaskGroup(of: Void.self) { group in
            group.addTask { @MainActor [weak self] in
                guard let self else { return }
                for await map in self.rally.localAnnotations.byVideoId {
                    self.annotations = map[self.entry.id] ?? []
                }
            }
            // The clip-scoped subset of labels, for the picker and the badge
            // lookup. `rally.labels` is a singleton, so this just mirrors its
            // current value.
            group.addTask { @MainActor [weak self] in
                guard let self else { return }
                for await ls in self.rally.labels.clipLabels {
                    self.labels = ls
                }
            }
        }
    }

    /// Frame stepping needs the frame rate and nothing else does. Skipped once
    /// it is known, and NOT latched on having been tried: a load cancelled by
    /// the view leaving comes back with nothing, and must be tried again on the
    /// way back rather than leaving the step buttons dead.
    private func loadMetadata() async {
        guard fps == 0 else { return }
        let asset = AVURLAsset(url: LocalVideoFiles.resolve(relativePath: entry.uri))
        if let track = try? await asset.loadTracks(withMediaType: .video).first,
           let rate = try? await track.load(.nominalFrameRate) {
            fps = rate
        }
    }

    /// Kicks a real load off the network so a stale or empty cache catches up.
    /// Errors are swallowed here: the cached/streamed value above still renders.
    ///
    /// Once per model rather than once per appearance - coming back from court
    /// marking is not a reason to ask the server again - but only a refresh
    /// that ran to the end counts, for the same reason as `loadMetadata`.
    private func refreshLabelsOnce() async {
        guard !labelsRefreshed else { return }
        _ = try? await rally.labels.refreshLabelsOrMessage()
        if !Task.isCancelled { labelsRefreshed = true }
    }

    func currentTimestampSeconds() -> Float {
        let time = player.currentTime()
        guard time.isNumeric else { return 0 }
        return max(0, Float(CMTimeGetSeconds(time)))
    }

    func add(label: AnnotationLabel?, body: String, atSeconds: Float) {
        let trimmed = body.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty && label == nil { return }
        _ = rally.localAnnotations.add(
            videoId: entry.id,
            timestampSeconds: max(0, atSeconds),
            body: trimmed,
            label: label
        )
        actionError = nil
    }

    func delete(annotationId: String) {
        rally.localAnnotations.delete(videoId: entry.id, annotationId: annotationId)
        actionError = nil
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
