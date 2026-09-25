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
    /// Reports the playhead to a caller that needs it for more than the card's
    /// own timecode and bar.
    ///
    /// The skeleton panel needs it to pick the pose to draw and to centre the
    /// graph, and it cannot read it out of the overlay: `overlay` is a value
    /// built by the caller before this view runs, so the caller has to know
    /// where the playhead is to build it. One observer, reported outward, rather
    /// than a second periodic observer on the same player.
    var onPosition: ((PlaybackPosition) -> Void)? = nil
    /// The labels that can be tapped to tag the moment on screen: a row of chips
    /// under the transport, and a floating window over a fullscreen video. Empty
    /// where tagging is not offered - the skeleton view, or somebody else's match.
    var labels: [AnnotationLabel] = []
    /// A label was tapped with the playhead at the given position. The caller owns
    /// the write; this view only says what happened.
    var onTag: ((AnnotationLabel, Int64) -> Void)? = nil
    /// The caller's last tagging failure. Said inside the fullscreen window, where
    /// the page's own error banner is covered.
    var tagError: String? = nil
    /// Whether the card offers fullscreen. Off for the skeleton view, whose panels
    /// under the card are the point of it.
    var allowsFullscreen = false
    /// Drawn over the frame and scaled with it.
    @ViewBuilder var overlay: Overlay
    @ViewBuilder var errorContent: ErrorContent

    @State private var position = PlaybackPosition()
    @State private var speed = PlaybackOptions.shared.DEFAULT_SPEED
    @State private var showSettings = false
    @State private var skipSeconds = Int(PlaybackOptions.shared.DEFAULT_SKIP_SECONDS)
    @State private var isFullscreen = false
    @State private var tagMessage: String? = nil
    @State private var tagMessageClock = 0
    @State private var panelAnchor = PanelAnchor.companion.DEFAULT

    private func card(fullscreen: Bool) -> some View {
        ShuttlVideoCard(
            player: player,
            position: position,
            onSettingsTap: { showSettings = true },
            aspectRatio: aspectRatio,
            onSeek: player.map { p in { fraction in seek(p, to: fraction) } },
            fullscreen: fullscreen,
            onFullscreenToggle: allowsFullscreen ? { isFullscreen.toggle() } : nil,
            overlay: { overlay },
            errorContent: { errorContent }
        )
    }

    private func tag(_ label: AnnotationLabel) {
        let at = position.positionMs
        onTag?(label, at)
        tagMessage = quickTagConfirmation(label, positionMs: at)
        tagMessageClock += 1
    }

    var body: some View {
        VStack(spacing: 0) {
            card(fullscreen: false)
            if let player {
                ShuttlTransportBar(
                    player: player,
                    prefs: prefs,
                    step: step,
                    onSettings: { showSettings = true }
                )
                .padding(.top, PlayerMetrics.transportGap)
            }
            if onTag != nil {
                ReplayLabelRow(labels: labels, message: tagMessage, onTag: tag)
                    .padding(.top, PlayerMetrics.labelGap)
            }
        }
        // Long enough to read at a glance, short enough not to be there for the
        // next rally. Keyed on a counter, so the same label twice restarts it.
        .task(id: tagMessageClock) {
            guard tagMessage != nil else { return }
            try? await Task.sleep(for: .milliseconds(2500))
            if !Task.isCancelled { tagMessage = nil }
        }
        .task {
            for await value in prefs.labelPanelAnchor {
                panelAnchor = value
            }
        }
        .fullScreenCover(isPresented: $isFullscreen) { fullscreenContent }
        // The mask has to change before the cover lays out, or it lays out
        // upright and turns a moment later.
        .onChange(of: isFullscreen) { _, now in OrientationLock.setLandscape(now) }
        .onDisappear { if isFullscreen { OrientationLock.setLandscape(false) } }
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
        // A sheet on this view cannot present while the cover is up, so the cover
        // carries its own; this one stands down for it.
        .sheet(isPresented: settingsBinding(whenFullscreen: false)) { settingsSheet }
    }

    private var settingsSheet: some View {
        PlaybackSettingsSheet(
            skipSeconds: skipSeconds,
            speed: speed,
            onSkipSeconds: { prefs.setSkipSeconds(seconds: Int32($0)) },
            onSpeed: { prefs.setSpeed(speed: $0) }
        )
    }

    private func settingsBinding(whenFullscreen: Bool) -> Binding<Bool> {
        Binding(
            get: { showSettings && isFullscreen == whenFullscreen },
            set: { showSettings = $0 }
        )
    }

    /// The same player, full bleed and in landscape: the card without its gutter,
    /// the transport laid over the frame, and the labels in a window the coach can
    /// move. One `AVPlayer` throughout, so entering and leaving does not lose the
    /// playhead - the reason Android reuses its player across both hosts too.
    private var fullscreenContent: some View {
        ZStack(alignment: .bottom) {
            Color.black.ignoresSafeArea()
            card(fullscreen: true)
                // The frame may run under the home indicator; the chips may not
                // run under the sensor housing, which is on a short edge here.
                .ignoresSafeArea(edges: .vertical)
            if let player {
                ShuttlTransportBar(
                    player: player,
                    prefs: prefs,
                    step: step,
                    onSettings: { showSettings = true },
                    overVideo: true
                )
                .padding(.bottom, PlayerMetrics.fullscreenTransportInset)
            }
            if onTag != nil, !labels.isEmpty {
                GeometryReader { proxy in
                    FloatingLabelPanel(
                        labels: labels,
                        area: proxy.size,
                        anchor: panelAnchor,
                        onAnchorChange: { prefs.setLabelPanelAnchor(anchor: $0) },
                        onTag: tag,
                        message: tagError ?? tagMessage
                    )
                }
                .coordinateSpace(name: PanelMetrics.areaSpace)
                // Anywhere except over the card's chips or the transport: it is
                // drawn above both, so parked there it would take the taps meant
                // for play, or for the way out of fullscreen.
                .padding(.top, PanelMetrics.chipClearance)
                .padding(.bottom, PanelMetrics.transportClearance)
            }
        }
        .sheet(isPresented: settingsBinding(whenFullscreen: true)) { settingsSheet }
        .statusBarHidden()
        .persistentSystemOverlays(.hidden)
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
            onPosition?(next)
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
    static let labelGap: CGFloat = 16
    /// Android's fullscreen transport sits 24dp off the bottom edge.
    static let fullscreenTransportInset: CGFloat = 24
}

extension ShuttlPlayer where Overlay == EmptyView, ErrorContent == EmptyView {
    init(
        player: AVPlayer?,
        prefs: PlaybackPreferenceRepository,
        step: @escaping (Int64) -> Void,
        aspectRatio: Double? = nil,
        onPosition: ((PlaybackPosition) -> Void)? = nil,
        labels: [AnnotationLabel] = [],
        onTag: ((AnnotationLabel, Int64) -> Void)? = nil,
        tagError: String? = nil,
        allowsFullscreen: Bool = false
    ) {
        self.init(
            player: player,
            prefs: prefs,
            step: step,
            aspectRatio: aspectRatio,
            onPosition: onPosition,
            labels: labels,
            onTag: onTag,
            tagError: tagError,
            allowsFullscreen: allowsFullscreen,
            overlay: { EmptyView() },
            errorContent: { EmptyView() }
        )
    }
}
