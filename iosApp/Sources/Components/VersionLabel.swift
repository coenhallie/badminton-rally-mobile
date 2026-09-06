import Foundation

/// The app version as shown to a coach, from the bundle.
///
/// Was duplicated in the sign in screen and the match list. It gains a third
/// caller with the drawer footer, which is one too many copies of four lines.
func versionLabel() -> String {
    let v = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "?"
    let b = Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "?"
    return "Version \(v) (\(b))"
}
