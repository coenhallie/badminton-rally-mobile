import Shared
import SwiftUI

/// The playback sheet: speed and skip interval as chips, both writing the shared
/// preference so a choice made on any player is the one every player uses next.
///
/// Lived inside `PlaybackControlBar.swift` until that file was replaced by
/// `ShuttlTransportBar`. It is reached from the speed chip on the video card,
/// which is where the mock puts it, and from holding either skip pill.
struct PlaybackSettingsSheet: View {
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
                        .shuttlType(ShuttlType.headlineMedium)
                        .foregroundStyle(Shuttl.textHeading)
                    Spacer()
                    Button("Done") { dismiss() }
                }

                Text("Speed")
                    .shuttlType(ShuttlType.labelMedium)
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
                    .shuttlType(ShuttlType.labelMedium)
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
                .shuttlType(ShuttlType.bodySmall)
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .background(Capsule().fill(selected ? Shuttl.accent : Shuttl.bgTertiary))
                .foregroundStyle(selected ? Shuttl.onAccent : Shuttl.text)
        }
    }
}
