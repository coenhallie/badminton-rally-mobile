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

/// The video on the mock's card: rounded, in the page gutter, with the timecode
/// chip top left, the speed chip top right, and the progress bar along the bottom
/// edge, which also scrubs.
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
/// Port of androidApp's `VideoCard.kt`, minus its pinch-to-zoom and pan, which
/// are an interaction rather than a token and have not been ported yet.
struct ShuttlVideoCard<Overlay: View, ErrorContent: View>: View {
    let player: AVPlayer?
    let position: PlaybackPosition
    let speed: Float
    let onSpeedTap: () -> Void
    var aspectRatio: Double? = nil
    var onSeek: ((Double) -> Void)? = nil
    @ViewBuilder var overlay: Overlay
    @ViewBuilder var errorContent: ErrorContent

    /// Non-zero only once the first frame has decoded, so the card opens at 16:9
    /// and settles rather than jumping from nothing.
    @State private var measured: Double? = nil

    private var ratio: Double {
        // Never below 1: a portrait video in a card of its own ratio would be
        // taller than the screen. It gets a square card and pillarboxes.
        max(aspectRatio ?? measured ?? (16.0 / 9.0), 1)
    }

    var body: some View {
        ZStack {
            Color.black
            PlayerSurface(player: player)
            overlay
            chips
            scrubBar
            errorContent
        }
        .aspectRatio(ratio, contentMode: .fit)
        .frame(maxWidth: .infinity)
        .clipShape(RoundedRectangle(cornerRadius: ShuttlRadius.large))
        .padding(.horizontal, CardMetrics.gutter)
        .task(id: playerIdentity) { await trackPresentationSize() }
    }

    private var chips: some View {
        VStack {
            HStack {
                OverlayChip(text: LocalVideoLogic.formatDuration(ms: position.positionMs))
                Spacer()
                OverlayChip(
                    text: PlaybackOptions.shared.formatSpeed(speed: speed),
                    tint: ShuttlVideoOverlay.accentText,
                    action: onSpeedTap,
                    accessibilityLabel: "Playback settings, speed "
                        + PlaybackOptions.shared.formatSpeed(speed: speed)
                )
            }
            Spacer()
        }
        .padding(CardMetrics.chipInset)
    }

    private var scrubBar: some View {
        VStack {
            Spacer()
            ScrubBar(fraction: position.fraction, onSeek: onSeek)
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
        speed: Float,
        onSpeedTap: @escaping () -> Void,
        aspectRatio: Double? = nil,
        onSeek: ((Double) -> Void)? = nil
    ) {
        self.init(
            player: player,
            position: position,
            speed: speed,
            onSpeedTap: onSpeedTap,
            aspectRatio: aspectRatio,
            onSeek: onSeek,
            overlay: { EmptyView() },
            errorContent: { EmptyView() }
        )
    }
}

/// The three-point progress bar, with a touch strip eight times taller so it can
/// be scrubbed. While a finger is down the bar follows the finger and the seek
/// lands on release; a tap seeks straight away.
private struct ScrubBar: View {
    let fraction: Double
    let onSeek: ((Double) -> Void)?

    @State private var dragging = false
    @State private var dragFraction: Double = 0

    var body: some View {
        GeometryReader { proxy in
            let shown = dragging ? dragFraction : fraction
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
        .allowsHitTesting(onSeek != nil)
    }

    private func seekGesture(width: CGFloat) -> some Gesture {
        DragGesture(minimumDistance: 0)
            .onChanged { value in
                guard width > 0 else { return }
                dragging = true
                dragFraction = min(max(Double(value.location.x / width), 0), 1)
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
        static let touchHeight: CGFloat = 24
    }
}

/// A small pill over the video: the mock's `rgba(11,12,13,.7)` scrim with an 11pt
/// label. Port of androidApp's `OverlayChip`.
private struct OverlayChip: View {
    let text: String
    var tint: Color = ShuttlVideoOverlay.text
    var action: (() -> Void)? = nil
    var accessibilityLabel: String? = nil

    var body: some View {
        let label = Text(text)
            .shuttlType(ShuttlType.labelSmall)
            .foregroundStyle(tint)
            .padding(.horizontal, 9)
            .padding(.vertical, 5)
            .background(ShuttlVideoOverlay.scrim)
            .clipShape(Capsule())

        if let action {
            Button(action: action) { label }
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
    /// The unfilled part of the progress bar: the mock's `rgba(255,255,255,.12)`.
    static let track = Color.white.opacity(0.12)
    static let progress = Color(rgb: ShuttlPalette.accent.dark)
}
