import Shared
import SwiftUI

/// The rotating hero line.
///
/// Two stacked Texts with explicit spacing rather than one Text with a line
/// height: SwiftUI's .lineSpacing is additive and cannot tighten below Archivo's
/// natural 1.088 leading, and the mock's hero is 1.08. Stacking sidesteps
/// leading entirely and hits the figure exactly.
///
/// The second line is textMuted, which is the only place that token is allowed:
/// it clears the 3:1 large-text threshold at this size but not the 4.5:1 body
/// threshold, and it is only legible on `bg`.
struct HeroTickerView: View {
    @State private var index = 0
    @State private var leaving = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    /// Paused whenever the caller decides this hero is not usefully on
    /// screen - Home's own copy while the drawer covers it, and the empty
    /// Matches tab's copy while something is pushed over it or another tab is
    /// front. An animation nobody can see still costs a wake per tick.
    let isPaused: Bool

    private let dwell: Duration = .milliseconds(2600)

    var body: some View {
        VStack(alignment: .leading, spacing: 40 * 0.08) {
            Text(HeroTicker.shared.leadLine)
                .foregroundStyle(Shuttl.textHeading)
            // Sized to the tallest phrase this slot will ever need at the
            // current width, not to whichever phrase is showing: some phrases
            // wrap to two lines here and some do not, and the empty Matches
            // tab sets its body copy straight under the hero, so a slot that
            // followed the current phrase would jump that copy a line up and
            // down on every rotation (parity with androidApp's HeroTickerView,
            // commit 9b7cbe7). Every candidate phrase is drawn hidden, purely
            // to claim its own height; a ZStack's own size is the largest of
            // its children, so it settles on the tallest one, and the real,
            // visible phrase draws on top of them at that fixed size. Under
            // Reduce Motion only the first phrase is ever shown (see run()
            // below), so the sizing set is just that one phrase - reserving
            // room for every phrase would hold open space for a rotation a
            // coach with that setting will never see.
            ZStack(alignment: .leading) {
                ForEach(
                    reduceMotion ? [HeroTicker.shared.phrases[0]] : HeroTicker.shared.phrases,
                    id: \.self
                ) { phrase in
                    Text(phrase).hidden().accessibilityHidden(true)
                }
                Text(HeroTicker.shared.phrases[index])
                    .foregroundStyle(Shuttl.textMuted)
                    .opacity(leaving ? 0 : 1)
                    .offset(y: leaving ? -12 : 0)
                    .animation(.easeOut(duration: 0.38), value: leaving)
                    .animation(.easeOut(duration: 0.38), value: index)
            }
        }
        .shuttlType(ShuttlType.display)
        .frame(maxWidth: .infinity, alignment: .leading)
        // Two Texts read as two separate VoiceOver elements by default; combined
        // into one so the hero is announced as a single sentence rather than two
        // stops.
        .accessibilityElement(children: .combine)
        .task(id: isPaused) { await run() }
    }

    private func run() async {
        // Reduce Motion shows the first phrase and stops. A line of copy that
        // rewrites itself every 2.6 seconds is exactly the motion that setting
        // exists to switch off.
        guard !reduceMotion, !isPaused else { return }
        // This task can be cancelled between `leaving = true` and
        // `leaving = false` (the caller flipping `isPaused` mid-transition -
        // the drawer opening over Home's copy, say - does exactly that).
        // Without this reset, a cancellation caught there leaves the
        // second line invisible until the next full dwell-and-transition
        // cycle completes.
        leaving = false
        while !Task.isCancelled {
            try? await Task.sleep(for: dwell)
            if Task.isCancelled { return }
            leaving = true
            try? await Task.sleep(for: .milliseconds(380))
            if Task.isCancelled { return }
            index = Int(HeroTicker.shared.next(after: Int32(index)))
            leaving = false
        }
    }
}
