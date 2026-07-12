import SwiftUI

/// Port of Android's FrameStepBar: tap = single frame step; hold-left = -3
/// frames every 100ms; hold-right = real playback until release. 400ms hold
/// activation, like Android's HoldButton.
struct FrameStepBar: View {
    let model: LocalPlayerModel

    var body: some View {
        HStack(spacing: 0) {
            HoldButton(
                text: "Previous frame",
                onTap: { model.stepFrames(-1) },
                onHoldTick: { model.stepFrames(-3) }
            )
            HoldButton(
                text: "Next frame",
                onTap: { model.stepFrames(1) },
                onHoldActivate: { model.player.play() },
                onRelease: { if model.player.rate != 0 { model.player.pause() } }
            )
        }
    }
}

private struct HoldButton: View {
    let text: String
    var onTap: () -> Void = {}
    var onHoldActivate: () -> Void = {}
    var onHoldTick: () -> Void = {}
    var onRelease: () -> Void = {}
    @State private var pressed = false
    @State private var holdTask: Task<Void, Never>? = nil

    var body: some View {
        Text(text)
            .font(.body.weight(.semibold))
            .foregroundStyle(Shuttl.text)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 12)
            .background(pressed ? Shuttl.bgSecondary : Shuttl.bgTertiary)
            .overlay(Rectangle().stroke(Shuttl.borderSecondary, lineWidth: 1))
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { _ in
                        guard !pressed else { return }
                        pressed = true
                        onTap()
                        holdTask = Task {
                            try? await Task.sleep(for: .milliseconds(400))
                            guard !Task.isCancelled else { return }
                            onHoldActivate()
                            while !Task.isCancelled {
                                onHoldTick()
                                try? await Task.sleep(for: .milliseconds(100))
                            }
                        }
                    }
                    .onEnded { _ in
                        holdTask?.cancel()
                        holdTask = nil
                        pressed = false
                        onRelease()
                    }
            )
    }
}
