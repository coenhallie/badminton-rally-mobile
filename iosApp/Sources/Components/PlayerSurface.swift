import AVKit
import SwiftUI

/// The video surface for both player screens.
///
/// AVKit's own controls are off entirely. `ShuttlVideoCard` draws the timecode
/// chip, the settings chip and the scrub bar, and `ShuttlTransportBar` under the
/// card owns playback; AVKit's play button and time bar would sit on top of both.
/// The iOS counterpart of Android's `useController = false`.
///
/// `speeds` is emptied as well, and not redundantly: the playback-speed menu is
/// reached from the controller's own overflow, which `showsPlaybackControls =
/// false` hides but does not disable for a hardware or accessibility path - and
/// it offers rates the shared preference does not.
///
/// `allowsVideoFrameAnalysis` is off for the same reason as the controls: on a
/// paused frame with any text in it (a scoreboard, a sponsor board, which every
/// broadcast has) AVKit lays its own Live Text button over the card's corner,
/// a system control the card does not draw and Android does not have.
///
/// `videoGravity` is left at `.resizeAspect`: the card imposes the aspect ratio,
/// so the layer letterboxes inside a box that already matches it, and a portrait
/// video pillarboxes in its square card rather than being cropped.
struct PlayerSurface: UIViewControllerRepresentable {
    /// Optional to match the `VideoPlayer(player:)` signature this replaces:
    /// ClipDetailView has no player until the clip URL is signed.
    let player: AVPlayer?

    func makeUIViewController(context: Context) -> AVPlayerViewController {
        let controller = AVPlayerViewController()
        controller.player = player
        controller.showsPlaybackControls = false
        controller.speeds = []
        controller.allowsVideoFrameAnalysis = false
        return controller
    }

    func updateUIViewController(_ controller: AVPlayerViewController, context: Context) {
        if controller.player !== player {
            controller.player = player
        }
        // Re-asserted because AVKit restores them when the item changes.
        controller.showsPlaybackControls = false
        controller.speeds = []
        controller.allowsVideoFrameAnalysis = false
    }
}
