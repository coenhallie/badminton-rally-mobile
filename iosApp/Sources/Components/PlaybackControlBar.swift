import AVFoundation
import Shared
import SwiftUI

/// Skip back / speed / skip forward, sitting directly above `FrameStepBar`.
/// Port of Android's PlaybackControlBar; both read one shared preference so a
/// speed or interval chosen on any clip is the one every player uses next.
struct PlaybackControlBar: View {
    let player: AVPlayer
    let prefs: PlaybackPreferenceRepository

    @State private var skipSeconds = Int(PlaybackOptions.shared.DEFAULT_SKIP_SECONDS)
    @State private var speed = PlaybackOptions.shared.DEFAULT_SPEED
    @State private var showSettings = false

    var body: some View {
        HStack(spacing: 0) {
            TransportButton(
                text: "\u{25C0}\u{25C0} \(skipSeconds)s",
                accessibilityText: "Skip back \(skipSeconds) seconds",
                onTap: { skip(-1) },
                onHoldActivate: { showSettings = true }
            )
            TransportButton(
                text: PlaybackOptions.shared.formatSpeed(speed: speed),
                accessibilityText: "Playback settings, speed \(PlaybackOptions.shared.formatSpeed(speed: speed))",
                onTap: { showSettings = true }
            )
            TransportButton(
                text: "\(skipSeconds)s \u{25B6}\u{25B6}",
                accessibilityText: "Skip forward \(skipSeconds) seconds",
                onTap: { skip(1) },
                onHoldActivate: { showSettings = true }
            )
        }
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
        // The preference is app-wide and the player can be replaced under us (a
        // clip is re-signed on playback failure), so re-apply on both.
        .task(id: ObjectIdentifier(player)) { applySpeed() }
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

    /// `player.rate = x` *starts* playback on AVPlayer, so the speed lives in
    /// `defaultRate` (what play() honours) and `rate` is only touched when the
    /// player is already running.
    private func applySpeed() {
        player.defaultRate = speed
        if player.rate != 0 {
            player.rate = speed
        }
    }
}

private struct PlaybackSettingsSheet: View {
    let skipSeconds: Int
    let speed: Float
    let onSkipSeconds: (Int) -> Void
    let onSpeed: (Float) -> Void
    @Environment(\.dismiss) private var dismiss

    /// The detent follows the measured content instead of a fixed height: the
    /// chip rows wrap, so how tall the sheet needs to be depends on the text
    /// size. This is only the estimate the sheet opens at; the first layout
    /// replaces it with the real height.
    @State private var contentHeight: CGFloat = 300

    private var speedOptions: [Float] {
        PlaybackOptions.shared.speedOptions.map { $0.floatValue }
    }
    private var skipOptions: [Int] {
        PlaybackOptions.shared.skipSecondsOptions.map { $0.intValue }
    }

    var body: some View {
        // Scrolls only when the content outgrows the detent, which the system
        // caps at the screen height: at the largest text sizes the wrapped rows
        // can be taller than that, and clipping them would hide the chips.
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                HStack {
                    Text("Playback")
                        .font(.title2.weight(.semibold))
                        .foregroundStyle(Shuttl.textHeading)
                    Spacer()
                    Button("Done") { dismiss() }
                }

                Text("Speed")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(Shuttl.textSecondary)
                ChipFlow {
                    ForEach(speedOptions, id: \.self) { option in
                        chip(
                            label: PlaybackOptions.shared.formatSpeed(speed: option),
                            selected: option == speed
                        ) { onSpeed(option) }
                    }
                }

                Text("Skip interval")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(Shuttl.textSecondary)
                ChipFlow {
                    ForEach(skipOptions, id: \.self) { option in
                        chip(label: "\(option)s", selected: option == skipSeconds) {
                            onSkipSeconds(option)
                        }
                    }
                }
            }
            .padding(.horizontal, 24)
            .padding(.top, 24)
            .padding(.bottom, 16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background {
                GeometryReader { proxy in
                    Color.clear.onChange(of: proxy.size.height, initial: true) { _, height in
                        contentHeight = height
                    }
                }
            }
        }
        .scrollBounceBehavior(.basedOnSize)
        .presentationDetents([.height(contentHeight)])
    }

    private func chip(label: String, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(label)
                .font(.footnote)
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .background(Capsule().fill(selected ? Shuttl.accent : Shuttl.bgTertiary))
                .foregroundStyle(selected ? .black : Shuttl.text)
        }
    }
}

/// Chips wrap onto further lines instead of running off the sheet, the way
/// Android's `FlowRow` does. A plain `HStack` fits six skip intervals at the
/// default text size and nothing like it at accessibility sizes.
private struct ChipFlow: Layout {
    var spacing: CGFloat = 8

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let available = proposal.width ?? .infinity
        let rows = rows(of: subviews, within: available)
        let height = rows.reduce(0) { $0 + $1.height } + spacing * CGFloat(max(rows.count - 1, 0))
        // Claim the full proposed width so `placeSubviews` wraps against exactly
        // the width the rows were measured against.
        let width = available.isFinite ? available : (rows.map(\.width).max() ?? 0)
        return CGSize(width: width, height: height)
    }

    func placeSubviews(
        in bounds: CGRect,
        proposal: ProposedViewSize,
        subviews: Subviews,
        cache: inout ()
    ) {
        var y = bounds.minY
        for row in rows(of: subviews, within: bounds.width) {
            var x = bounds.minX
            for index in row.indices {
                let size = subviews[index].sizeThatFits(.unspecified)
                subviews[index].place(
                    at: CGPoint(x: x, y: y),
                    anchor: .topLeading,
                    proposal: ProposedViewSize(size)
                )
                x += size.width + spacing
            }
            y += row.height + spacing
        }
    }

    private struct Row {
        var indices: [Int] = []
        var width: CGFloat = 0
        var height: CGFloat = 0
    }

    private func rows(of subviews: Subviews, within available: CGFloat) -> [Row] {
        var rows: [Row] = []
        var row = Row()
        for index in subviews.indices {
            let size = subviews[index].sizeThatFits(.unspecified)
            let extended = row.indices.isEmpty ? size.width : row.width + spacing + size.width
            if !row.indices.isEmpty && extended > available {
                rows.append(row)
                row = Row(indices: [index], width: size.width, height: size.height)
            } else {
                row.indices.append(index)
                row.width = extended
                row.height = max(row.height, size.height)
            }
        }
        if !row.indices.isEmpty { rows.append(row) }
        return rows
    }
}
