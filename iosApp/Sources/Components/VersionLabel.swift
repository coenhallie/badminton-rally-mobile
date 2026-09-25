import Foundation
import Shared

/// The app version as shown to a coach, from the bundle.
///
/// Calls into `HomeCopy.version` rather than building its own string: that is
/// also what androidApp's Settings tab calls, so the two platforms cannot draw
/// two different version lines for the same build.
func versionLabel() -> String {
    let v = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "?"
    let b = Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "?"
    return HomeCopy.shared.version(name: v, code: Int32(b) ?? 0)
}
