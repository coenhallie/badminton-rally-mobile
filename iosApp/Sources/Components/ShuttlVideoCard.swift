import AVFoundation
import Shared
import SwiftUI

/// Where the playhead is and how long the media is, in milliseconds. Duration is
/// zero until the player knows it, which is what makes `fraction` safe to read
/// before the first frame decodes.
///
/// Port of androidApp's `PlaybackPosition`.
struct PlaybackPosition: Equatable {
    var positionMs: Int64 = 0
    var durationMs: Int64 = 0

    var fraction: Double {
        durationMs > 0 ? min(max(Double(positionMs) / Double(durationMs), 0), 1) : 0
    }
}

/// Isolated observable playhead so 30 Hz timecode and scrub-bar ticks only
/// invalidate the leaf controls that render them, never the surrounding video
/// card, transport bar, or host screen.
@Observable @MainActor
final class PlaybackPlayhead {
    var position = PlaybackPosition()
}

/// Pure zoom and pan clamping math for `ShuttlVideoCard`, kept out of the
/// generic view so unit tests can assert it directly.
enum VideoCardZoom {
    static let maxScale: CGFloat = 4.0
    static let doubleTapScale: CGFloat = 2.5

    /// Clamps a centre-anchored translation so the scaled video edges never
    /// pull inside the card's viewport.
    static func clampedOffset(_ candidate: CGSize, scale: CGFloat, size: CGSize) -> CGSize {
        guard scale > 1, size.width > 0, size.height > 0 else { return .zero }
        let maxX = size.width * (scale - 1) / 2
        let maxY = size.height * (scale - 1) / 2
        return CGSize(
            width: min(max(candidate.width, -maxX), maxX),
            height: min(max(candidate.height, -maxY), maxY)
        )
    }

    /// Computes the clamped centre-anchored offset that keeps `tap` stationary
    /// under the finger when zooming from 1× to `targetScale`.
    static func doubleTapOffset(tap: CGPoint, targetScale: CGFloat, size: CGSize) -> CGSize {
        let fromCenter = CGSize(
            width: tap.x - size.width / 2,
            height: tap.y - size.height / 2
        )
        let candidate = CGSize(
            width: fromCenter.width * (1 - targetScale),
            height: fromCenter.height * (1 - targetScale)
        )
        return clampedOffset(candidate, scale: targetScale, size: size)
    }
}

/// The video on the mock's card: rounded, in the page gutter, with the timecode
/// chip top left, the settings chip top right, and the progress bar along the
/// bottom edge, which also scrubs. The settings chip is a gear and opens the
/// playback sheet; it used to be the speed itself, which read as a readout rather
/// than as a way in, and the speed now sits in the transport row.
///
/// In `fullscreen` the card loses its gutter and corners and fits whatever the
/// caller gives it; the chips stay, and the corner-bracket chip beside the gear
/// leaves again. The frame and its overlay pinch-to-zoom (1×–4×), pan while
/// zoomed, and double-tap to toggle zoom while the chips and scrub bar stay put.
///
/// AVKit's own controls are off. Its play button and time bar would sit on top of
/// the scrub bar and the chips, and the transport under the card owns playback
/// anyway - the same reason Android sets `useController = false`.
///
/// `aspectRatio` is the video's own when the caller knows it; otherwise it is
/// read from the player once the first frame decodes, with 16:9 until then.
/// Portrait video gets a square card and sits pillarboxed in it rather than a
/// card taller than the screen.
///
/// Port of androidApp's `VideoCard.kt`.
struct ShuttlVideoCard<Overlay: View, ErrorContent: View>: View {
    let player: AVPlayer?
    private let playhead: PlaybackPlayhead?
    private let staticPosition: PlaybackPosition
    let onSettingsTap: () -> Void
    var aspectRatio: Double? = nil
    var onSeek: ((Double) -> Void)? = nil
    var fullscreen = false
    /// Nil where fullscreen is not offered, and the chip is then not drawn.
    var onFullscreenToggle: (() -> Void)? = nil
    @ViewBuilder var overlay: Overlay
    @ViewBuilder var errorContent: ErrorContent

    /// Non-zero only once the first frame has decoded, so the card opens at 16:9
    /// and settles rather than jumping from nothing.
    @State private var measured: Double? = nil
    @State private var scale: CGFloat = 1
    @State private var offset: CGSize = .zero
    @State private var contentSize: CGSize = .zero
    @State private var pinchStartScale: CGFloat? = nil
    @State private var pinchStartOffset: CGSize = .zero
    @State private var panStartOffset: CGSize? = nil
    @GestureState private var isPinching = false
    @GestureState private var isPanning = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    init(
        player: AVPlayer?,
        position: PlaybackPosition,
        onSettingsTap: @escaping () -> Void,
        aspectRatio: Double? = nil,
        onSeek: ((Double) -> Void)? = nil,
        fullscreen: Bool = false,
        onFullscreenToggle: (() -> Void)? = nil,
        @ViewBuilder overlay: () -> Overlay,
        @ViewBuilder errorContent: () -> ErrorContent
    ) {
        self.player = player
        self.playhead = nil
        self.staticPosition = position
        self.onSettingsTap = onSettingsTap
        self.aspectRatio = aspectRatio
        self.onSeek = onSeek
        self.fullscreen = fullscreen
        self.onFullscreenToggle = onFullscreenToggle
        self.overlay = overlay()
        self.errorContent = errorContent()
    }

    init(
        player: AVPlayer?,
        playhead: PlaybackPlayhead,
        onSettingsTap: @escaping () -> Void,
        aspectRatio: Double? = nil,
        onSeek: ((Double) -> Void)? = nil,
        fullscreen: Bool = false,
        onFullscreenToggle: (() -> Void)? = nil,
        @ViewBuilder overlay: () -> Overlay,
        @ViewBuilder errorContent: () -> ErrorContent
    ) {
        self.player = player
        self.playhead = playhead
        self.staticPosition = PlaybackPosition()
        self.onSettingsTap = onSettingsTap
        self.aspectRatio = aspectRatio
        self.onSeek = onSeek
        self.fullscreen = fullscreen
        self.onFullscreenToggle = onFullscreenToggle
        self.overlay = overlay()
        self.errorContent = errorContent()
    }

    private var ratio: Double {
        // Never below 1: a portrait video in a card of its own ratio would be
        // taller than the screen. It gets a square card and pillarboxes.
        max(aspectRatio ?? measured ?? (16.0 / 9.0), 1)
    }

    var body: some View {
        Group {
            if fullscreen {
                // The whole screen, with the frame fitted inside it by AVKit: the
                // chips and the bar belong to the screen's edges here, not to
                // wherever the letterboxed frame happens to end.
                layers.frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                layers
                    .aspectRatio(ratio, contentMode: .fit)
                    // The video's own box, before the full width: clipped after
                    // it, a card held short by its height kept square corners on
                    // a frame narrower than the rounded one.
                    .clipShape(RoundedRectangle(cornerRadius: ShuttlRadius.large))
                    .frame(maxWidth: .infinity)
                    .padding(.horizontal, CardMetrics.gutter)
            }
        }
        .task(id: playerIdentity) { await trackPresentationSize() }
        .onChange(of: fullscreen) { _, _ in resetZoom(animated: false) }
        .onChange(of: playerIdentity) { _, _ in resetZoom(animated: false) }
    }

    private var layers: some View {
        ZStack {
            Color.black
            zoomableSurface
            chips
            scrubBar
            errorContent
        }
    }

    private var zoomableSurface: some View {
        ZStack {
            PlayerSurface(player: player)
            overlay
        }
        .scaleEffect(scale)
        .offset(offset)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .clipped()
        .contentShape(Rectangle())
        .onGeometryChange(for: CGSize.self, of: { $0.size }) { measuredSize in
            contentSize = measuredSize
            if scale > 1 {
                offset = VideoCardZoom.clampedOffset(offset, scale: scale, size: measuredSize)
            }
        }
        .gesture(doubleTapGesture)
        .simultaneousGesture(magnifyGesture)
        // Pan only while zoomed so vertical scrolling of an enclosing ScrollView
        // stays completely free at 1×.
        .simultaneousGesture(panGesture, including: scale > 1.01 ? .all : .none)
        .onChange(of: isPinching) { _, active in
            if !active {
                pinchStartScale = nil
                if scale <= 1.01 { resetZoom(animated: true) }
            }
        }
        .onChange(of: isPanning) { _, active in
            if !active { panStartOffset = nil }
        }
    }

    private var zoomAnimation: Animation? {
        reduceMotion ? nil : .spring(response: 0.3, dampingFraction: 0.85)
    }

    private func resetZoom(animated: Bool) {
        pinchStartScale = nil
        panStartOffset = nil
        if animated {
            withAnimation(zoomAnimation) {
                scale = 1
                offset = .zero
            }
        } else {
            scale = 1
            offset = .zero
        }
    }

    private var doubleTapGesture: some Gesture {
        SpatialTapGesture(count: 2)
            .onEnded { value in
                withAnimation(zoomAnimation) {
                    if scale > 1.01 {
                        scale = 1
                        offset = .zero
                    } else {
                        let target = VideoCardZoom.doubleTapScale
                        scale = target
                        offset = VideoCardZoom.doubleTapOffset(
                            tap: value.location,
                            targetScale: target,
                            size: contentSize
                        )
                    }
                }
            }
    }

    private var magnifyGesture: some Gesture {
        MagnifyGesture()
            .updating($isPinching) { _, state, _ in state = true }
            .onChanged { value in
                let startScale = pinchStartScale ?? scale
                if pinchStartScale == nil {
                    pinchStartScale = scale
                    pinchStartOffset = offset
                }
                let nextScale = min(max(startScale * value.magnification, 1), VideoCardZoom.maxScale)
                let ratio = nextScale / startScale
                let anchorX = value.startLocation.x - contentSize.width / 2
                let anchorY = value.startLocation.y - contentSize.height / 2
                let candidate = CGSize(
                    width: pinchStartOffset.width * ratio + anchorX * (1 - ratio),
                    height: pinchStartOffset.height * ratio + anchorY * (1 - ratio)
                )
                scale = nextScale
                offset = VideoCardZoom.clampedOffset(candidate, scale: nextScale, size: contentSize)
            }
            .onEnded { _ in
                pinchStartScale = nil
                if scale <= 1.01 { resetZoom(animated: true) }
            }
    }

    private var panGesture: some Gesture {
        DragGesture(minimumDistance: 4)
            .updating($isPanning) { _, state, _ in state = true }
            .onChanged { value in
                guard scale > 1.01 else { return }
                let origin = panStartOffset ?? offset
                if panStartOffset == nil { panStartOffset = offset }
                let candidate = CGSize(
                    width: origin.width + value.translation.width,
                    height: origin.height + value.translation.height
                )
                offset = VideoCardZoom.clampedOffset(candidate, scale: scale, size: contentSize)
            }
            .onEnded { _ in
                panStartOffset = nil
            }
    }

    private var chips: some View {
        VStack {
            HStack(alignment: .center, spacing: 2) {
                TimecodeChip(playhead: playhead, fallback: staticPosition)
                    .frame(minHeight: ShuttlTarget.minimum)
                Spacer()
                OverlayChip(symbol: "gearshape", action: onSettingsTap, accessibilityLabel: "Playback settings")
                if let onFullscreenToggle {
                    OverlayChip(
                        symbol: fullscreen
                            ? "arrow.down.right.and.arrow.up.left"
                            : "arrow.up.left.and.arrow.down.right",
                        action: onFullscreenToggle,
                        accessibilityLabel: fullscreen ? "Exit fullscreen" : "Fullscreen"
                    )
                }
            }
            Spacer()
        }
        .padding(.leading, CardMetrics.chipInset)
        .padding(.trailing, max(0, CardMetrics.chipInset - 6))
        .padding(.top, 2)
    }

    private var scrubBar: some View {
        VStack {
            Spacer()
            ScrubBar(playhead: playhead, fallback: staticPosition, onSeek: onSeek)
        }
    }

    /// Identity, not the object: `ClipDetailView` re-signs its clip on a playback
    /// failure and hands back a different `AVPlayer`, and an observer keyed on
    /// anything coarser would keep reading the old one.
    private var playerIdentity: ObjectIdentifier? {
        player.map(ObjectIdentifier.init)
    }

    /// `presentationSize` is zero until the first frame decodes, so it is polled
    /// alongside the rest rather than read once. Cheap: it settles on the first
    /// non-zero value and the loop ends there.
    private func trackPresentationSize() async {
        guard aspectRatio == nil else { return }
        measured = nil
        guard let player else { return }
        while !Task.isCancelled {
            if let item = player.currentItem {
                if item.status == .failed { return }
                let size = item.presentationSize
                if size.width > 0 && size.height > 0 {
                    measured = Double(size.width / size.height)
                    return
                }
            }
            try? await Task.sleep(for: .milliseconds(100))
        }
    }

}

/// Mirrors the mock's own numbers. File-scope rather than nested in the card:
/// `ShuttlVideoCard` is generic over its overlay, and a generic type cannot hold
/// a static stored property.
private enum CardMetrics {
    /// The page gutter the mock lays the card in.
    static let gutter: CGFloat = 24
    static let chipInset: CGFloat = 12
}

extension ShuttlVideoCard where Overlay == EmptyView, ErrorContent == EmptyView {
    init(
        player: AVPlayer?,
        position: PlaybackPosition,
        onSettingsTap: @escaping () -> Void,
        aspectRatio: Double? = nil,
        onSeek: ((Double) -> Void)? = nil
    ) {
        self.init(
            player: player,
            position: position,
            onSettingsTap: onSettingsTap,
            aspectRatio: aspectRatio,
            onSeek: onSeek,
            overlay: { EmptyView() },
            errorContent: { EmptyView() }
        )
    }
}

/// Leaf view that observes `playhead.position` for the timecode chip alone.
private struct TimecodeChip: View {
    let playhead: PlaybackPlayhead?
    let fallback: PlaybackPosition

    var body: some View {
        let position = playhead?.position ?? fallback
        OverlayChip(
            text: PlaybackClockKt.playbackClock(positionMs: position.positionMs, durationMs: position.durationMs)
        )
    }
}

/// The three-point progress bar, with a 44pt touch strip so it can be scrubbed
/// cleanly. While a finger is down the bar follows the finger and seeks live
/// so the coach previews frames as they drag; a tap seeks straight away.
private struct ScrubBar: View {
    let playhead: PlaybackPlayhead?
    let fallback: PlaybackPosition
    let onSeek: ((Double) -> Void)?

    @State private var dragging = false
    @State private var dragFraction: Double = 0
    @GestureState private var isDragging = false

    var body: some View {
        let position = playhead?.position ?? fallback
        GeometryReader { proxy in
            let shown = dragging ? dragFraction : position.fraction
            ZStack(alignment: .bottomLeading) {
                // The strip is transparent and only there to be touched; the bar
                // it carries is the three points the mock draws.
                Color.clear.contentShape(Rectangle())
                Rectangle()
                    .fill(ShuttlVideoOverlay.track)
                    .frame(height: ScrubMetrics.barHeight)
                Rectangle()
                    .fill(ShuttlVideoOverlay.progress)
                    .frame(width: proxy.size.width * shown, height: ScrubMetrics.barHeight)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomLeading)
            .gesture(seekGesture(width: proxy.size.width))
        }
        .frame(height: ScrubMetrics.touchHeight)
        .onChange(of: isDragging) { _, active in
            if !active { dragging = false }
        }
        .allowsHitTesting(onSeek != nil)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Playback position")
        .accessibilityValue(
            PlaybackClockKt.playbackClock(positionMs: position.positionMs, durationMs: position.durationMs)
        )
        .scrubAdjustable(position: position, onSeek: onSeek)
    }

    private func seekGesture(width: CGFloat) -> some Gesture {
        DragGesture(minimumDistance: 0)
            .updating($isDragging) { _, state, _ in state = true }
            .onChanged { value in
                guard width > 0 else { return }
                let target = min(max(Double(value.location.x / width), 0), 1)
                let shouldSeek = !dragging || abs(target - dragFraction) >= 0.001
                dragging = true
                dragFraction = target
                if shouldSeek { onSeek?(target) }
            }
            .onEnded { value in
                guard width > 0 else { return }
                let target = min(max(Double(value.location.x / width), 0), 1)
                dragging = false
                onSeek?(target)
            }
    }

    private enum ScrubMetrics {
        static let barHeight: CGFloat = 3
        static let touchHeight: CGFloat = ShuttlTarget.minimum
    }
}

private extension View {
    @ViewBuilder
    func scrubAdjustable(position: PlaybackPosition, onSeek: ((Double) -> Void)?) -> some View {
        if let onSeek {
            self.accessibilityAdjustableAction { direction in
                let step = position.durationMs > 0
                    ? min(5_000.0 / Double(position.durationMs), 0.05)
                    : 0.05
                switch direction {
                case .increment:
                    onSeek(min(position.fraction + step, 1))
                case .decrement:
                    onSeek(max(position.fraction - step, 0))
                @unknown default:
                    break
                }
            }
        } else {
            self
        }
    }
}

/// A small pill over the video: the mock's `rgba(11,12,13,.7)` scrim with an 11pt
/// label, or one glyph. Port of androidApp's `OverlayChip`.
private struct OverlayChip: View {
    var text: String = ""
    /// Drawn instead of `text`, in the text chip's line box so a glyph chip and a
    /// text chip sit at one height.
    var symbol: String? = nil
    var tint: Color = ShuttlVideoOverlay.text
    var action: (() -> Void)? = nil
    var accessibilityLabel: String? = nil

    @ViewBuilder
    private var content: some View {
        if let symbol {
            // Through the type scale, not a raw system size: it sits beside a text
            // chip and should grow with it. The frame is the text chip's line box.
            Image(systemName: symbol)
                .shuttlType(ShuttlType.labelMedium)
                .frame(height: ShuttlType.labelSmall.size * ShuttlType.labelSmall.lineHeightMultiple)
        } else {
            Text(text).shuttlType(ShuttlType.labelSmall)
        }
    }

    var body: some View {
        let label = content
            .foregroundStyle(tint)
            .padding(.horizontal, 9)
            .padding(.vertical, 5)
            .background(ShuttlVideoOverlay.scrim)
            .clipShape(Capsule())

        if let action {
            Button(action: action) {
                label
                    .frame(minWidth: ShuttlTarget.minimum, minHeight: ShuttlTarget.minimum)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(accessibilityLabel ?? text)
        } else {
            label.accessibilityLabel(accessibilityLabel ?? text)
        }
    }
}

/// The colours drawn ON the video, which is always dark whatever the app's theme
/// is - so these are the dark palette's values in both themes rather than tokens
/// that flip. Mirrors androidApp's `ShuttlVideoOverlay`.
enum ShuttlVideoOverlay {
    /// 70% of the dark page colour, the mock's own `rgba(11,12,13,.7)`.
    static let scrim = Color(rgb: ShuttlPalette.bg.dark).opacity(0.7)
    static let text = Color(rgb: ShuttlPalette.text.dark)
    static let accentText = Color(rgb: ShuttlPalette.accentDark.dark)
    /// The unfilled part of the progress bar: the chips' own scrim. The mock's
    /// `rgba(255,255,255,.12)` vanished on a bright frame (a lit court floor),
    /// which left the bar's length, and so the scrub target, unseen.
    static let track = ShuttlVideoOverlay.scrim
    static let progress = Color(rgb: ShuttlPalette.accent.dark)
}
