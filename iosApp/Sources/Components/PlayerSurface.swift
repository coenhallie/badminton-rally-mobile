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
/// Handing a controller a paused player moves the player: AVKit snaps it to the
/// nearest keyframe as it attaches, measured on the simulator as 4.3s to 0:00 on
/// a 6s clip and 5.4s to 10.7s on a 25s one. Fullscreen does exactly that (the
/// cover shows the same player as the card under it), so entering fullscreen
/// on a paused video jumped it, and play resumed from the wrong place. The time
/// is read before the attach and put back exactly after it. Only while paused:
/// a playing player is not snapped, and seeking it back to a time already a
/// frame behind would stutter.
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
        let paused = player.map { $0.rate == 0 } ?? false
        let before = player?.currentTime()
        controller.player = player
        if paused, let player, let before, before.isNumeric, player.currentTime() != before {
            player.seek(to: before, toleranceBefore: .zero, toleranceAfter: .zero)
        }
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
