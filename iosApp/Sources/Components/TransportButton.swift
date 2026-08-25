import SwiftUI

/// One cell of the player's transport rows: a flat, full-bleed button that
/// distinguishes a tap from a press-and-hold. Shared by `FrameStepBar` and
/// `PlaybackControlBar` so the two rows stack as one control surface.
///
/// Port of Android's TransportButton (androidApp .../clipdetail/TransportButton.kt).
/// `onTap` fires immediately on press so repeated taps stay responsive;
/// `onHoldActivate` fires once after `holdActivationDelay`; `onHoldTick` repeats
/// afterwards, and a nil tick means a hold is a one-shot gesture.
struct TransportButton: View {
    let text: String
    var accessibilityText: String? = nil
    var onTap: () -> Void = {}
    var holdActivationDelay: Duration = .milliseconds(400)
    var onHoldActivate: () -> Void = {}
    var onHoldTick: (() -> Void)? = nil
    var holdTickPeriod: Duration = .milliseconds(100)
    var onRelease: () -> Void = {}

    @State private var pressed = false
    @State private var holdTask: Task<Void, Never>? = nil

    var body: some View {
        Text(text)
            .font(.body.weight(.semibold))
            .foregroundStyle(Shuttl.text)
            .lineLimit(1)
            .minimumScaleFactor(0.7)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 12)
            .background(pressed ? Shuttl.bgSecondary : Shuttl.bgTertiary)
            .overlay(Rectangle().stroke(Shuttl.borderSecondary, lineWidth: 1))
            .contentShape(Rectangle())
            .accessibilityLabel(accessibilityText ?? text)
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
                    .onEnded { _ in
                        endPress()
                    }
            )
            .onDisappear { endPress() }
    }

    private func endPress() {
        holdTask?.cancel()
        holdTask = nil
        pressed = false
        onRelease()
    }
}
