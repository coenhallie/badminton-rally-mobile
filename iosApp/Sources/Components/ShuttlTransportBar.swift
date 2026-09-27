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
/// The playback speed is read out in the row's trailing gutter and opens the
/// settings sheet when tapped. It is not a sixth control: a sixth control does not
/// fit a phone, and would push play off centre. How the five controls give way to
/// make room for it is the shared `TransportRowLayout`'s decision, which is what
/// Android's row asks too. The sheet also stays reachable from the gear on the
/// video card and from holding either skip pill.
///
/// Port of androidApp's `TransportBar.kt`.
struct ShuttlTransportBar: View {
    let player: AVPlayer
    let prefs: PlaybackPreferenceRepository
    /// Steps whole frames. The caller owns it because only it knows the media's
    /// frame rate (`FrameStepMath`).
    let step: (Int64) -> Void
    /// Opens the playback sheet. Owned by the caller so the card's settings chip and
    /// these pills open one sheet rather than two.
    let onSettings: () -> Void
    /// True in fullscreen, where the row sits on the frame rather than on the page:
    /// the readout has no surface of its own, so it is lettered for the video.
    var overVideo = false

    @State private var skipSeconds = Int(PlaybackOptions.shared.DEFAULT_SKIP_SECONDS)
    @State private var playing = false
    @State private var speed = PlaybackOptions.shared.DEFAULT_SPEED
    @State private var width: CGFloat = 0
    /// Read so a text size change re-runs `metrics`: the measurement goes
    /// through UIKit's font metrics, which SwiftUI does not track, and with the
    /// width unchanged nothing else invalidates the row. Without it, a row laid
    /// out at an accessibility size kept those metrics back at the default
    /// size, its skip pills empty.
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    /// Measured, not guessed, so a larger text size costs the row a step rather
    /// than clipping a label. `.custom(_:size:)` scales with Dynamic Type relative
    /// to the body style, so the measurement does too.
    private static func textWidth(_ text: String, _ role: ShuttlType.Role) -> CGFloat {
        let size = UIFontMetrics(forTextStyle: .body).scaledValue(for: role.size)
        let font = UIFont(name: role.weight.rawValue, size: size) ?? .systemFont(ofSize: size)
        return ceil((text as NSString).size(withAttributes: [.font: font, .kern: role.kerning]).width)
    }

    private var metrics: TransportRowMetrics {
        let layout = TransportRowLayout.shared
        func pill(_ forward: Bool, _ icons: Bool) -> CGFloat {
            Self.textWidth(
                layout.pillLabel(skipSeconds: Int32(skipSeconds), forward: forward, showPillIcons: icons),
                ShuttlType.bodySmall
            )
        }
        // The widest the readout gets, not what it says now: the row must not
        // re-space itself when the speed changes.
        let readout = PlaybackOptions.shared.speedOptions
            .map { Self.textWidth(PlaybackOptions.shared.formatSpeed(speed: $0.floatValue), ShuttlType.labelMedium) }
            .max() ?? 0
        return layout.fit(
            availableWidth: Float(width),
            readoutWidth: Float(readout),
            pillLabelWidth: Float(pill(true, true)),
            signedPillLabelWidth: Float(max(pill(true, false), pill(false, false)))
        )
    }

    var body: some View {
        let _ = dynamicTypeSize
        let metrics = metrics
        let layout = TransportRowLayout.shared
        HStack(spacing: CGFloat(metrics.gap)) {
            SkipPill(
                symbol: "chevron.left.2",
                label: layout.pillLabel(skipSeconds: Int32(skipSeconds), forward: false, showPillIcons: metrics.showPillIcons),
                metrics: metrics,
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
                label: layout.pillLabel(skipSeconds: Int32(skipSeconds), forward: true, showPillIcons: metrics.showPillIcons),
                metrics: metrics,
                iconFirst: false,
                accessibilityLabel: "Skip forward \(skipSeconds) seconds",
                onTap: { skip(1) },
                onHold: onSettings
            )
        }
        .frame(maxWidth: .infinity)
        .padding(.horizontal, CGFloat(metrics.gutter))
        // In the trailing gutter, which the leading one matches: that is what keeps
        // play in the middle of the screen.
        .overlay(alignment: .trailing) {
            Button(action: onSettings) {
                Text(PlaybackOptions.shared.formatSpeed(speed: speed))
                    .shuttlType(ShuttlType.labelMedium)
                    .lineLimit(1)
                    .foregroundStyle(overVideo ? ShuttlVideoOverlay.text : Shuttl.textSecondary)
                    // The whole gutter and the row's height, not the few points
                    // the text covers.
                    .frame(width: CGFloat(metrics.gutter), height: Metrics.buttonSize)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Speed \(PlaybackOptions.shared.formatSpeed(speed: speed))")
            .accessibilityHint("Opens playback settings")
        }
        .onGeometryChange(for: CGFloat.self, of: { $0.size.width }) { width = $0 }
        .task {
            for await value in prefs.skipSeconds {
                skipSeconds = value.intValue
            }
        }
        .task {
            for await value in prefs.speed {
                speed = value.floatValue
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
        static let buttonSize: CGFloat = 48
        static let playSize: CGFloat = 60
        static let glyph: CGFloat = 14
        static let accentGlyph: CGFloat = 18
        static let pillGap: CGFloat = 6
    }
}

/// A 48pt pill with a double chevron and the skip interval, lettered in the
/// secondary text colour. On a narrow screen `metrics` takes the chevron away and
/// the direction is carried by the sign: "-10s" and "+10s".
private struct SkipPill: View {
    let symbol: String
    let label: String
    let metrics: TransportRowMetrics
    let iconFirst: Bool
    let accessibilityLabel: String
    let onTap: () -> Void
    let onHold: () -> Void

    var body: some View {
        TransportSurface(shape: Capsule(), accent: false, onTap: onTap, onHoldActivate: onHold) {
            HStack(spacing: ShuttlTransportBar.Metrics.pillGap) {
                if iconFirst && metrics.showPillIcons { glyph }
                Text(label)
                    .shuttlType(ShuttlType.bodySmall)
                    .foregroundStyle(Shuttl.textSecondary)
                    .lineLimit(1)
                if !iconFirst && metrics.showPillIcons { glyph }
            }
            .padding(.horizontal, CGFloat(metrics.pillPadding))
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
