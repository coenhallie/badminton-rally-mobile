import AVKit
import SwiftUI

/// The video surface for both player screens.
///
/// SwiftUI's `VideoPlayer` hosts an `AVPlayerViewController` carrying AVKit's
/// default playback-speed menu (0.5x, 1x, 1.25x, 1.5x, 2x) and exposes no way to
/// clear it. That menu would disagree with `PlaybackControlBar` the moment
/// either one is used - and it offers rates the preference does not - so we host
/// the controller directly and empty `speeds`, AVKit's documented way to drop
/// the control. Everything else is left at the defaults `VideoPlayer` used.
///
/// The iOS counterpart of Android's `withoutMedia3SpeedMenu()`.
struct PlayerSurface: UIViewControllerRepresentable {
    /// Optional to match the `VideoPlayer(player:)` signature this replaces:
    /// ClipDetailView has no player until the clip URL is signed.
    let player: AVPlayer?

    func makeUIViewController(context: Context) -> AVPlayerViewController {
        let controller = AVPlayerViewController()
        controller.player = player
        controller.speeds = []
        return controller
    }

    func updateUIViewController(_ controller: AVPlayerViewController, context: Context) {
        if controller.player !== player {
            controller.player = player
        }
        // Re-asserted because AVKit repopulates `speeds` when the item changes.
        controller.speeds = []
    }
}
