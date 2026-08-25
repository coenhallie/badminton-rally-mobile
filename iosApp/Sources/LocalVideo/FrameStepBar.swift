import AVFoundation
import SwiftUI

/// Port of Android's FrameStepBar: tap = single frame step; hold-left = -3
/// frames every 100ms; hold-right = real playback until release. 400ms hold
/// activation, like Android's TransportButton.
struct FrameStepBar: View {
    let player: AVPlayer
    let step: (Int64) -> Void

    var body: some View {
        HStack(spacing: 0) {
            TransportButton(
                text: "Previous frame",
                onTap: { step(-1) },
                onHoldTick: { step(-3) }
            )
            TransportButton(
                text: "Next frame",
                onTap: { step(1) },
                onHoldActivate: { player.play() },
                onRelease: { if player.rate != 0 { player.pause() } }
            )
        }
    }
}
