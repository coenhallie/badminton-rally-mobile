import Foundation

/// The Home hero's copy and rotation.
///
/// Separate from the view so the phrases and the wrap-around can be asserted
/// without a running timer, and so the copy sits in one obvious place to edit.
/// Mirrors androidApp's HeroTicker.kt word for word; HeroTickerTests and
/// HeroTickerTest check the two against each other.
enum HeroTicker {
    /// The fixed first line. Held here rather than in the view so both lines of
    /// the hero are edited in the same file.
    static let leadLine = "Your game,"

    static let phrases = [
        "clipped rally by rally.",
        "mapped as heatmaps.",
        "tracked as skeletons.",
        "annotated and shared.",
    ]

    /// The next phrase index, wrapping at the end.
    ///
    /// Clamps rather than trusting its input: the index is view state, and state
    /// restored after a process death has been seen to arrive stale. An out of
    /// range value returns a valid index instead of trapping.
    static func next(after index: Int) -> Int {
        guard !phrases.isEmpty else { return 0 }
        let safe = index < 0 || index >= phrases.count ? 0 : index
        return (safe + 1) % phrases.count
    }
}
