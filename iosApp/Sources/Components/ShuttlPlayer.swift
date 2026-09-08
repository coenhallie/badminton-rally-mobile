import AVFoundation
import Shared
import SwiftUI

/// The video card and the transport under it, as one control.
///
/// Both player screens place this rather than assembling the pieces themselves.
/// The plumbing between the two halves - where the playhead is, what speed the
/// shared preference holds, and which of them opens the playback sheet - is the
/// part that would otherwise be written twice and drift, which is exactly what
/// happened to the pair of flat bars this replaces. Android says the same thing
/// by having one `VideoCard` and one `TransportBar` serve both screens.
struct ShuttlPlayer<Overlay: View, ErrorContent: View>: View {
    let player: AVPlayer?
    let prefs: PlaybackPreferenceRepository
    /// Steps whole frames; the caller owns it because only it knows the media's
    /// frame rate (`FrameStepMath`).
    let step: (Int64) -> Void
    /// The video's own ratio when the caller knows it; otherwise the card reads
    /// it from the player once the first frame decodes.
    var aspectRatio: Double? = nil
    /// Drawn over the frame and scaled with it. Unused on iPhone today: Android
    /// spends it on the skeleton, which arrives here with pipeline Stage 2.
    @ViewBuilder var overlay: Overlay
    @ViewBuilder var errorContent: ErrorContent

    @State private var position = PlaybackPosition()
    @State private var speed = PlaybackOptions.shared.DEFAULT_SPEED
    @State private var showSettings = false
    @State private var skipSeconds = Int(PlaybackOptions.shared.DEFAULT_SKIP_SECONDS)

    var body: some View {
        VStack(spacing: 0) {
            ShuttlVideoCard(
                player: player,
                position: position,
                speed: speed,
                onSpeedTap: { showSettings = true },
                aspectRatio: aspectRatio,
                onSeek: player.map { p in { fraction in seek(p, to: fraction) } },
                overlay: { overlay },
                errorContent: { errorContent }
            )
            if let player {
                ShuttlTransportBar(
                    player: player,
                    prefs: prefs,
                    step: step,
                    onSettings: { showSettings = true }
                )
                .padding(.top, PlayerMetrics.transportGap)
            }
        }
        .task {
            for await value in prefs.speed {
                speed = value.floatValue
            }
        }
        .task {
            for await value in prefs.skipSeconds {
                skipSeconds = value.intValue
            }
        }
        // One task, not two keyed the same: the preference is app-wide and the
        // player can be replaced under us (a clip is re-signed on playback
        // failure), so both the speed and the position observer have to restart
        // together on the new player. Two `.task(id:)` with one id would leave
        // which of them wins to SwiftUI, and losing either is silent - a frozen
        // timecode, or a speed the preference no longer matches.
        .task(id: playerIdentity) {
            applySpeed()
            await observePosition()
        }
        .onChange(of: speed) { _, _ in applySpeed() }
        .sheet(isPresented: $showSettings) {
            PlaybackSettingsSheet(
                skipSeconds: skipSeconds,
                speed: speed,
                onSkipSeconds: { prefs.setSkipSeconds(seconds: Int32($0)) },
                onSpeed: { prefs.setSpeed(speed: $0) }
            )
        }
    }

    private var playerIdentity: ObjectIdentifier? {
        player.map(ObjectIdentifier.init)
    }

    /// The playhead, through AVPlayer's own periodic observer rather than a
    /// display link: Android polls per frame only because Media3 has no callback
    /// to wait on, and this one exists. Thirty a second is enough for a timecode
    /// and a three-point bar.
    ///
    /// The observer is torn down when this task is cancelled, which `task(id:)`
    /// does whenever the player is replaced - leaving it attached would hold the
    /// old player alive and freeze the timecode on the new one.
    private func observePosition() async {
        guard let player else {
            position = PlaybackPosition()
            return
        }
        let interval = CMTime(seconds: 1.0 / 30.0, preferredTimescale: 600)
        let stream = AsyncStream<PlaybackPosition> { continuation in
            let token = player.addPeriodicTimeObserver(forInterval: interval, queue: .main) { time in
                let duration = player.currentItem?.duration
                continuation.yield(PlaybackPosition(
                    positionMs: time.isNumeric ? Int64(CMTimeGetSeconds(time) * 1000) : 0,
                    durationMs: (duration?.isNumeric == true)
                        ? Int64(CMTimeGetSeconds(duration!) * 1000)
                        : 0
                ))
            }
            continuation.onTermination = { _ in
                Task { @MainActor in player.removeTimeObserver(token) }
            }
        }
        for await next in stream {
            position = next
        }
    }

    /// `player.rate = x` *starts* playback on AVPlayer, so the speed lives in
    /// `defaultRate` (what play() honours) and `rate` is only touched when the
    /// player is already running.
    private func applySpeed() {
        guard let player else { return }
        player.defaultRate = speed
        if player.rate != 0 {
            player.rate = speed
        }
    }

    private func seek(_ player: AVPlayer, to fraction: Double) {
        guard let duration = player.currentItem?.duration, duration.isNumeric else { return }
        let target = CMTimeGetSeconds(duration) * min(max(fraction, 0), 1)
        player.seek(
            to: CMTime(seconds: target, preferredTimescale: 600),
            toleranceBefore: .zero,
            toleranceAfter: .zero
        )
    }

}

/// Mirrors the mock's `margin-top: 14px` between the card and the transport.
/// A file-scope constant rather than a nested one: `ShuttlPlayer` is generic over
/// its overlay, and a generic type cannot hold a static stored property.
private enum PlayerMetrics {
    static let transportGap: CGFloat = 14
}

extension ShuttlPlayer where Overlay == EmptyView, ErrorContent == EmptyView {
    init(
        player: AVPlayer?,
        prefs: PlaybackPreferenceRepository,
        step: @escaping (Int64) -> Void,
        aspectRatio: Double? = nil
    ) {
        self.init(
            player: player,
            prefs: prefs,
            step: step,
            aspectRatio: aspectRatio,
            overlay: { EmptyView() },
            errorContent: { EmptyView() }
        )
    }
}
