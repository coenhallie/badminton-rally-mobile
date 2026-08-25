/// The one piece of expansion state for the whole Labels screen: which row
/// currently shows its in-place editor. `.new` is the not-yet-created draft
/// row opened by the toolbar "+"; `.existing` is a real label being renamed
/// or recoloured. Both live in the same optional, so it is structurally
/// impossible for two editors to be open at once.
enum LabelEditTarget: Equatable {
    case existing(String)
    case new
}

/// Row expansion is its own toggle: tapping the open row (or the toolbar
/// "+" again) closes it.
enum LabelsLogic {
    static func nextExpanded(current: LabelEditTarget?, tapped: LabelEditTarget) -> LabelEditTarget? {
        current == tapped ? nil : tapped
    }
}

/// Shared by [LabelEditor] (rename) and [DraftLabelEditor] (create): both
/// commit on the Done key *and* on focus loss, since tapping a swatch to
/// recolour moves focus away from the name field without ever firing Done.
/// A single Done press therefore reaches `commit()` twice - once from
/// `.onSubmit`, once from the blur that follows the keyboard dismissing -
/// and the second call must be a no-op or it resubmits the same text as a
/// second, phantom create or rename.
///
/// This is a value type, not a bare predicate, because the decision needs
/// state, not just the current text: "was this text just committed" and
/// "was this text just committed and then rejected" are different answers
/// for the same input, and only a guard that remembers what happened last
/// can tell them apart. `begin` arms the guard optimistically before the
/// caller dispatches; `failed` rolls it back so retyping the identical text
/// after resolving a rejection (a duplicate name is a normal, user-visible
/// outcome here, not an edge case) is not silently swallowed as a no-op.
struct CommitGuard {
    private(set) var lastCommitted: String

    init(lastCommitted: String = "") {
        self.lastCommitted = lastCommitted
    }

    /// True when this commit should actually dispatch. Arms the guard
    /// (updates `lastCommitted`) whenever it returns true, so the very next
    /// call with the same text returns false unless [failed] intervenes.
    mutating func begin(_ trimmed: String) -> Bool {
        guard !trimmed.isEmpty, trimmed != lastCommitted else { return false }
        lastCommitted = trimmed
        return true
    }

    /// Restores the guard to what it was before the most recent [begin]
    /// that returned true, so that same text is eligible to commit again.
    mutating func failed(previous: String) {
        lastCommitted = previous
    }
}
