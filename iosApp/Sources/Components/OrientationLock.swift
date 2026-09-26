import SwiftUI
import UIKit

/// Which orientations the app will take right now. Portrait, except while a video
/// is playing fullscreen.
///
/// The app is portrait-only by design - Info.plist says so, and nothing but a
/// fullscreen video is laid out for anything else. UIKit asks the app delegate
/// before it consults Info.plist, so this is the one place the answer can be made
/// to depend on what is on screen.
@MainActor
enum OrientationLock {
    private(set) static var mask: UIInterfaceOrientationMask = .portrait

    /// Turns the interface to landscape and holds it there, or back to portrait.
    /// Both halves are needed: the mask alone only says what is allowed, and a
    /// phone held upright would stay upright.
    static func setLandscape(_ landscape: Bool) {
        mask = landscape ? .landscape : .portrait
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        for scene in scenes {
            for window in scene.windows {
                window.rootViewController?.setNeedsUpdateOfSupportedInterfaceOrientations()
            }
            scene.requestGeometryUpdate(.iOS(interfaceOrientations: mask)) { _ in
                // Refused only if the mask and the delegate disagree, which they
                // cannot here. Nothing to recover: the video still plays upright.
            }
        }
    }
}

final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(
        _ application: UIApplication,
        supportedInterfaceOrientationsFor window: UIWindow?
    ) -> UIInterfaceOrientationMask {
        MainActor.assumeIsolated { OrientationLock.mask }
    }
}
