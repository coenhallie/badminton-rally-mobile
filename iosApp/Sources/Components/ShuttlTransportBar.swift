import AVFoundation
import Shared
import SwiftUI

/// The mock's centred transport: a skip pill, a frame step, the accent play
/// button, a frame step, a skip pill.
///
/// This replaces the two stacked rows of flat, full-bleed buttons the players
/// used to carry (`PlaybackControlBar` over `FrameStepBar`). Those were the
/// pre-redesign look; the mock has one row of circles and capsules, and Android
/// deleted its own two bar files for the same reason.
///
/// The speed control is NOT here, and its absence is the point: the mock moved it
/// onto the video card's own chip. The settings sheet stays reachable from that
/// chip and from holding either skip pill, which is Android's fallback too.
///
/// Port of androidApp's `TransportBar.kt`.
struct ShuttlTransportBar: View {
    let player: AVPlayer
    let prefs: PlaybackPreferenceRepository
    /// Steps whole frames. The caller owns it because only it knows the media's
    /// frame rate (`FrameStepMath`).
    let step: (Int64) -> Void
    /// Opens the playback sheet. Owned by the caller so the card's speed chip and
    /// these pills open one sheet rather than two.
    let onSettings: () -> Void

    @State private var skipSeconds = Int(PlaybackOptions.shared.DEFAULT_SKIP_SECONDS)
    @State private var playing = false

    var body: some View {
        // The circles keep their size and the two pills give way: at a large text
        // size the skip labels grow, and it is the pills that should narrow
        // before the row overflows.
        HStack(spacing: Metrics.gap) {
            SkipPill(
                symbol: "chevron.left.2",
                label: "\(skipSeconds)s",
                iconFirst: true,
                accessibilityLabel: "Skip back \(skipSeconds) seconds",
                onTap: { skip(-1) },
                onHold: onSettings
            )
            RoundTransportButton(
                symbol: "backward.frame.fill",
                accessibilityLabel: "Previous frame",
                onTap: {
                    pauseIfPlaying()
                    step(-1)
                },
                onHoldTick: { step(-3) }
            )
            RoundTransportButton(
                symbol: playing ? "pause.fill" : "play.fill",
                accessibilityLabel: playing ? "Pause" : "Play",
                accent: true,
                size: Metrics.playSize,
                onTap: togglePlay
            )
            RoundTransportButton(
                symbol: "forward.frame.fill",
                accessibilityLabel: "Next frame",
                onTap: {
                    pauseIfPlaying()
                    step(1)
                },
                onHoldActivate: { player.play() },
                onRelease: { pauseIfPlaying() }
            )
            SkipPill(
                symbol: "chevron.right.2",
                label: "\(skipSeconds)s",
                iconFirst: false,
                accessibilityLabel: "Skip forward \(skipSeconds) seconds",
                onTap: { skip(1) },
                onHold: onSettings
            )
        }
        .frame(maxWidth: .infinity)
        .padding(.horizontal, Metrics.gutter)
        .task {
            for await value in prefs.skipSeconds {
                skipSeconds = value.intValue
            }
        }
        .task(id: ObjectIdentifier(player)) { await trackPlaying() }
    }

    /// `rate` rather than `timeControlStatus`: the latter reports `.playing`
    /// while a seek settles, which would flip the glyph to a pause during frame
    /// stepping.
    private func trackPlaying() async {
        playing = player.rate != 0
        while !Task.isCancelled {
            let now = player.rate != 0
            if now != playing { playing = now }
            try? await Task.sleep(for: .milliseconds(100))
        }
    }

    private func pauseIfPlaying() {
        if player.rate != 0 { player.pause() }
    }

    private func togglePlay() {
        if player.rate != 0 {
            player.pause()
            return
        }
        // At the end, play means from the start; otherwise `play()` sits on the
        // last frame and does nothing. Android argues the same way.
        if let item = player.currentItem,
           item.duration.isNumeric,
           CMTimeGetSeconds(item.currentTime()) >= CMTimeGetSeconds(item.duration) - 0.05 {
            player.seek(to: .zero)
        }
        player.play()
    }

    private func skip(_ direction: Int) {
        let current = player.currentTime()
        let position = current.isNumeric ? Int64(CMTimeGetSeconds(current) * 1000) : 0
        let durationTime = player.currentItem?.duration
        // A non-positive duration tells SkipMath "unknown", which is what an
        // indefinite CMTime means here - do not clamp the skip against it.
        let duration = (durationTime?.isNumeric == true)
            ? Int64(CMTimeGetSeconds(durationTime!) * 1000)
            : -1
        let target = SkipMath.shared.targetMs(
            positionMs: position,
            deltaSeconds: Int32(direction * skipSeconds),
            durationMs: duration
        )
        player.seek(
            to: CMTime(value: target, timescale: 1000),
            toleranceBefore: .zero,
            toleranceAfter: .zero
        )
    }

    /// Mirrors androidApp's `TransportBar.kt` number for number.
    fileprivate enum Metrics {
        static let gutter: CGFloat = 24
        static let gap: CGFloat = 8
        static let buttonSize: CGFloat = 48
        static let playSize: CGFloat = 60
        static let glyph: CGFloat = 14
        static let accentGlyph: CGFloat = 18
        static let pillPaddingH: CGFloat = 16
        static let pillGap: CGFloat = 6
    }
}

/// A 48pt pill with a double chevron and the skip interval, lettered in the
/// secondary text colour.
private struct SkipPill: View {
    let symbol: String
    let label: String
    let iconFirst: Bool
    let accessibilityLabel: String
    let onTap: () -> Void
    let onHold: () -> Void

    var body: some View {
        TransportSurface(shape: Capsule(), accent: false, onTap: onTap, onHoldActivate: onHold) {
            HStack(spacing: ShuttlTransportBar.Metrics.pillGap) {
                if iconFirst { glyph }
                Text(label)
                    .shuttlType(ShuttlType.bodySmall)
                    .foregroundStyle(Shuttl.textSecondary)
                    .lineLimit(1)
                if !iconFirst { glyph }
            }
            .padding(.horizontal, ShuttlTransportBar.Metrics.pillPaddingH)
            .frame(height: ShuttlTransportBar.Metrics.buttonSize)
        }
        .accessibilityLabel(accessibilityLabel)
        // The pills, not the circles, are what give way when the labels grow.
        .layoutPriority(-1)
    }

    private var glyph: some View {
        Image(systemName: symbol)
            .font(.system(size: ShuttlTransportBar.Metrics.glyph, weight: .semibold))
            .foregroundStyle(Shuttl.textSecondary)
    }
}

/// A circle: 48pt on the card colour, or 60pt in the accent for play/pause.
private struct RoundTransportButton: View {
    let symbol: String
    let accessibilityLabel: String
    var accent: Bool = false
    var size: CGFloat = ShuttlTransportBar.Metrics.buttonSize
    var onTap: () -> Void = {}
    var onHoldActivate: () -> Void = {}
    var onHoldTick: (() -> Void)? = nil
    var onRelease: () -> Void = {}

    var body: some View {
        TransportSurface(
            shape: Circle(),
            accent: accent,
            onTap: onTap,
            onHoldActivate: onHoldActivate,
            onHoldTick: onHoldTick,
            onRelease: onRelease
        ) {
            Image(systemName: symbol)
                .font(.system(
                    size: accent
                        ? ShuttlTransportBar.Metrics.accentGlyph
                        : ShuttlTransportBar.Metrics.glyph,
                    weight: .semibold
                ))
                .foregroundStyle(accent ? Shuttl.onAccent : Shuttl.text)
                .frame(width: size, height: size)
        }
        .accessibilityLabel(accessibilityLabel)
    }
}

/// The press behaviour every transport control shares: a tap that fires on press
/// so repeated taps stay responsive, a hold that activates once after a delay,
/// and an optional tick that repeats afterwards.
///
/// Carried over from the `TransportButton` this replaced - only the surface it
/// draws changed, not how it reads a finger.
private struct TransportSurface<S: Shape, Content: View>: View {
    let shape: S
    let accent: Bool
    var onTap: () -> Void = {}
    var onHoldActivate: () -> Void = {}
    var onHoldTick: (() -> Void)? = nil
    var onRelease: () -> Void = {}
    var holdActivationDelay: Duration = .milliseconds(400)
    var holdTickPeriod: Duration = .milliseconds(100)
    @ViewBuilder var content: Content

    @State private var pressed = false
    @State private var holdTask: Task<Void, Never>? = nil

    var body: some View {
        content
            .background(fill)
            .clipShape(shape)
            .contentShape(shape)
            // A gesture-driven view, not a `Button`, because a tap has to fire on
            // press and a hold has to be told apart from it - which `Button` does
            // not offer. That costs the traits a Button would have carried, so
            // they are added back by hand: without them VoiceOver announces these
            // as plain images and never says "button".
            .accessibilityElement(children: .ignore)
            .accessibilityAddTraits(.isButton)
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { _ in
                        guard !pressed else { return }
                        pressed = true
                        onTap()
                        holdTask = Task {
                            try? await Task.sleep(for: holdActivationDelay)
                            guard !Task.isCancelled else { return }
                            onHoldActivate()
                            guard let tick = onHoldTick else { return }
                            while !Task.isCancelled {
                                tick()
                                try? await Task.sleep(for: holdTickPeriod)
                            }
                        }
                    }
                    .onEnded { _ in endPress() }
            )
            .onDisappear { endPress() }
    }

    private var fill: Color {
        if accent { return pressed ? Shuttl.accentDark : Shuttl.accent }
        return pressed ? Shuttl.bgTertiary : Shuttl.bgSecondary
    }

    private func endPress() {
        holdTask?.cancel()
        holdTask = nil
        pressed = false
        onRelease()
    }
}
