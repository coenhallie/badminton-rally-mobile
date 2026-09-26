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
    // Passed as it is: CFBundleVersion is a string and need not be an integer.
    return HomeCopy.shared.version(name: v, code: b)
}
